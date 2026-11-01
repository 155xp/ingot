package dev.ingot.shader;

import com.mojang.blaze3d.GpuFormat;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * SPIR-V (as compiled and rebound by vanilla's Vulkan GLSL compiler, see MetalPipelineCache) → MSL with SPIRV-Cross
 * (LWJGL spvc). Port of the validated prototype tools/shader-xlate/src/MslTranslator.java with its default options; for
 * the same input both produce byte-identical MSL. Owner: pipelines.
 *
 * <p>Every resource has already been rewritten to descriptor set 0, binding = index in the pipeline's binding list.
 * Both stages share one Metal argument-table layout (compact numbering):
 * <pre>
 *   [[buffer(0..13)]]   uniform buffers: k-th UNIFORM_BUFFER of the pipeline → buffer(k)
 *   [[buffer(14)]]      push constants
 *   [[buffer(15..30)]]  vertex buffers (RenderPass slot s → 30 - s, via MTLVertexDescriptor only)
 *   [[texture(j)]]      j-th COMBINED_IMAGE_SAMPLER or TEXEL_BUFFER (texture_buffer&lt;T&gt;)
 *   [[sampler(m)]]      sampler half of the m-th COMBINED_IMAGE_SAMPLER
 * </pre>
 * Every call creates and destroys its own spvc context, so translation is thread-safe.
 */
public final class SpirvToMsl {
	/** SPIRV-Cross MSL target; libingot compiles the result with MTLLanguageVersion2_4. */
	public static final int MSL_VERSION = 20400;
	/** Uniform buffers occupy [[buffer(0)]] up to (excluding) this index. */
	public static final int MAX_UNIFORM_BUFFERS = 14;
	/** [[buffer(n)]] of the push-constant block in both stages. */
	public static final int PUSH_CONSTANT_BUFFER = 14;
	/** Per-stage sampler limit on macOS. */
	public static final int MAX_SAMPLERS = 16;
	/** Stage.usedBindings is a 64-bit mask. */
	public static final int MAX_UNIFORMS = 64;

	private static final int EXECUTION_MODEL_VERTEX = 0;
	private static final int EXECUTION_MODEL_FRAGMENT = 4;

	/** What a pipeline binding is. */
	public enum BindingType {
		UNIFORM_BUFFER,
		COMBINED_IMAGE_SAMPLER,
		TEXEL_BUFFER
	}

	/** One resource of a pipeline, by the name the game binds it with; texelFormat only for TEXEL_BUFFER. */
	public record Binding(String name, BindingType type, @Nullable GpuFormat texelFormat) {
	}

	/** Metal argument-table indices of one pipeline binding; -1 where the kind does not apply. */
	public record Slot(int buffer, int texture, int sampler) {
	}

	/**
	 * What a module declares, in SPIRV-Cross reflection order: the order vanilla's IntermediaryShaderModule reflects (and
	 * numbers vertex outputs) in.
	 *
	 * @param samplerDimensions SpvDim of each sampled image, parallel to {@code samplers}
	 */
	public record Reflection(List<String> uniformBuffers, List<String> samplers, List<Integer> samplerDimensions, List<String> outputs) {
	}

	/**
	 * One translated stage.
	 *
	 * @param entryPoint         MSL function name (SPIRV-Cross renames "main" to "main0")
	 * @param usedBindings       bit i set when the stage reads uniform i (unused declarations are dropped from the MSL)
	 * @param usesPushConstants  the stage reads the push-constant block
	 */
	public record Stage(String msl, String entryPoint, long usedBindings, boolean usesPushConstants) {
		public boolean uses(int binding) {
			return (this.usedBindings >>> binding & 1L) != 0L;
		}
	}

	public static final class TranslationException extends Exception {
		public TranslationException(String message) {
			super(message);
		}
	}

	private SpirvToMsl() {
	}

	/** Compact slot numbering for a pipeline's bindings; list index i is SPIR-V binding i. */
	public static List<Slot> assignSlots(List<Binding> uniforms) throws TranslationException {
		if (uniforms.size() > MAX_UNIFORMS) {
			throw new TranslationException(uniforms.size() + " uniforms (at most " + MAX_UNIFORMS + " are supported)");
		}
		List<Slot> slots = new ArrayList<>(uniforms.size());
		int buffers = 0;
		int textures = 0;
		int samplers = 0;
		for (Binding uniform : uniforms) {
			Slot slot = switch (uniform.type()) {
				case UNIFORM_BUFFER -> new Slot(buffers++, -1, -1);
				case COMBINED_IMAGE_SAMPLER -> new Slot(-1, textures++, samplers++);
				case TEXEL_BUFFER -> new Slot(-1, textures++, -1);
			};
			if (slot.buffer() >= MAX_UNIFORM_BUFFERS) {
				throw new TranslationException("too many uniform buffers: " + uniform.name() + " would need buffer(" + slot.buffer() + ")");
			}
			if (slot.sampler() >= MAX_SAMPLERS) {
				throw new TranslationException("too many samplers: " + uniform.name() + " would need sampler(" + slot.sampler() + ")");
			}
			slots.add(slot);
		}
		return slots;
	}

	/**
	 * Translates one stage. Reads {@code spirv} without consuming it; nothing is retained after returning.
	 *
	 * @param slots the pipeline's {@link #assignSlots} result (the same list for both stages)
	 */
	public static Stage translate(ByteBuffer spirv, boolean vertex, String entryPoint, List<Slot> slots) throws TranslationException {
		int model = vertex ? EXECUTION_MODEL_VERTEX : EXECUTION_MODEL_FRAGMENT;
		IntBuffer words = spirv.asIntBuffer();
		long context = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.mallocPointer(1);
			check(0L, spvc_context_create(out), "spvc_context_create");
			context = out.get(0);
			check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "spvc_context_parse_spirv");
			long ir = out.get(0);
			check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out),
				"spvc_context_create_compiler");
			long compiler = out.get(0);
			check(context, spvc_compiler_create_compiler_options(compiler, out), "spvc_compiler_create_compiler_options");
			long options = out.get(0);

			spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
			spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
			// Minecraft renders in GL memory order (clip-space y = -1 in texel row 0); see docs/ARCHITECTURE.md.
			spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, vertex);
			// samplerBuffer/isamplerBuffer → texture_buffer<T>, bound as MTLTextureTypeTextureBuffer views.
			spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
			spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
			spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
			spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, false);
			// Same as the GL backend outside RenderDoc: no undefined reads from uninitialized locals.
			spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);

			// Explicit bindings for every uniform; is_resource_used only answers for registered bindings.
			SpvcMslResourceBinding2 binding = SpvcMslResourceBinding2.calloc(stack);
			for (int i = 0; i < slots.size(); i++) {
				Slot slot = slots.get(i);
				spvc_msl_resource_binding_init_2(binding);
				binding.stage(model).desc_set(0).binding(i).count(1)
					.msl_buffer(Math.max(slot.buffer(), 0))
					.msl_texture(Math.max(slot.texture(), 0))
					.msl_sampler(Math.max(slot.sampler(), 0));
				check(context, spvc_compiler_msl_add_resource_binding_2(compiler, binding), "spvc_compiler_msl_add_resource_binding_2");
			}
			// Push constants have no set/binding in SPIR-V; spvc matches them through this reserved pair.
			spvc_msl_resource_binding_init_2(binding);
			binding.stage(model).desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(SPVC_MSL_PUSH_CONSTANT_BINDING).count(1)
				.msl_buffer(PUSH_CONSTANT_BUFFER);
			check(context, spvc_compiler_msl_add_resource_binding_2(compiler, binding), "spvc_compiler_msl_add_resource_binding_2");

			check(context, spvc_compiler_set_entry_point(compiler, entryPoint, model), "spvc_compiler_set_entry_point");
			check(context, spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");
			check(context, spvc_compiler_compile(compiler, out), "spvc_compiler_compile");
			String msl = MemoryUtil.memUTF8(out.get(0));
			String mslEntryPoint = spvc_compiler_get_cleansed_entry_point_name(compiler, entryPoint, model);
			if (mslEntryPoint == null) {
				throw new TranslationException("no entry point named " + entryPoint);
			}
			// The fixed argument-table layout has no room for SPIRV-Cross's auxiliary buffers.
			if (spvc_compiler_msl_needs_swizzle_buffer(compiler) || spvc_compiler_msl_needs_buffer_size_buffer(compiler)
				|| spvc_compiler_msl_needs_output_buffer(compiler)) {
				throw new TranslationException("the shader needs a SPIRV-Cross auxiliary buffer (swizzle, buffer size or output)");
			}

			long used = 0L;
			for (int i = 0; i < slots.size(); i++) {
				if (spvc_compiler_msl_is_resource_used(compiler, model, 0, i)) {
					used |= 1L << i;
				}
			}
			boolean pushConstants = spvc_compiler_msl_is_resource_used(compiler, model, SPVC_MSL_PUSH_CONSTANT_DESC_SET,
				SPVC_MSL_PUSH_CONSTANT_BINDING);
			return new Stage(msl, mslEntryPoint, used, pushConstants);
		} finally {
			if (context != 0L) {
				// Frees the IR, the compiler and the MSL string.
				spvc_context_destroy(context);
			}
		}
	}

	/** Reflects the uniform buffers, sampled images and stage outputs of a module. */
	public static Reflection reflect(ByteBuffer spirv) throws TranslationException {
		IntBuffer words = spirv.asIntBuffer();
		long context = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.mallocPointer(1);
			check(0L, spvc_context_create(out), "spvc_context_create");
			context = out.get(0);
			check(context, spvc_context_parse_spirv(context, words, words.remaining(), out), "spvc_context_parse_spirv");
			long ir = out.get(0);
			check(context, spvc_context_create_compiler(context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out),
				"spvc_context_create_compiler");
			long compiler = out.get(0);
			check(context, spvc_compiler_create_shader_resources(compiler, out), "spvc_compiler_create_shader_resources");
			long resources = out.get(0);
			List<String> uniformBuffers = new ArrayList<>();
			for (SpvcReflectedResource resource : resources(context, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack)) {
				uniformBuffers.add(resource.nameString());
			}
			List<String> samplers = new ArrayList<>();
			List<Integer> samplerDimensions = new ArrayList<>();
			for (SpvcReflectedResource resource : resources(context, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack)) {
				samplers.add(resource.nameString());
				samplerDimensions.add(spvc_type_get_image_dimension(spvc_compiler_get_type_handle(compiler, resource.type_id())));
			}
			List<String> outputs = new ArrayList<>();
			for (SpvcReflectedResource resource : resources(context, resources, SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack)) {
				outputs.add(resource.nameString());
			}
			return new Reflection(uniformBuffers, samplers, samplerDimensions, outputs);
		} finally {
			if (context != 0L) {
				spvc_context_destroy(context);
			}
		}
	}

	private static SpvcReflectedResource.Buffer resources(long context, long resources, int type, MemoryStack stack) throws TranslationException {
		PointerBuffer list = stack.mallocPointer(1);
		PointerBuffer count = stack.mallocPointer(1);
		check(context, spvc_resources_get_resource_list_for_type(resources, type, list, count), "spvc_resources_get_resource_list_for_type");
		return SpvcReflectedResource.create(list.get(0), (int) count.get(0));
	}

	private static void check(long context, int result, String what) throws TranslationException {
		if (result != SPVC_SUCCESS) {
			String detail = context != 0L ? spvc_context_get_last_error_string(context) : "";
			throw new TranslationException(what + " failed (" + result + "): " + detail);
		}
	}
}
