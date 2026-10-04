import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding2;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * SPIR-V (as handed to a backend by renderpearl's PipelineBuilder) -> MSL, via SPIRV-Cross (LWJGL spvc).
 *
 * This is the piece the Ingot Metal backend needs inside GpuDeviceBackend.compilePipeline(CreateInfo).
 * It is deliberately free of Minecraft-specific logic beyond the CreateInfo record.
 *
 * Metal argument-table layout used here (per stage, both stages use the same table):
 *   [[buffer(0..13)]]   uniform buffers (and nothing else)
 *   [[buffer(14)]]      push constants (setVertexBytes / setFragmentBytes)
 *   [[buffer(15..30)]]  vertex buffers: renderpearl vertex buffer slot s -> buffer(30 - s)   (MTLVertexDescriptor only)
 *   [[texture(n)]]      combined image samplers and texel buffers (texture_buffer<T>)
 *   [[sampler(n)]]      the sampler half of combined image samplers
 */
public final class MslTranslator {
    public static final int SPV_EXEC_MODEL_VERTEX = 0;   // SpvExecutionModelVertex
    public static final int SPV_EXEC_MODEL_FRAGMENT = 4; // SpvExecutionModelFragment
    public static final int MAX_UNIFORM_BUFFERS = 14;
    public static final int PUSH_CONSTANT_BUFFER_INDEX = 14;
    public static final int MAX_SAMPLERS = 16; // Metal per-stage sampler limit on macOS

    public static int vertexBufferIndex(int renderpearlSlot) {
        return 30 - renderpearlSlot;
    }

    public enum BindingMode {
        /** binding i -> buffer(i) / texture(i) / sampler(i). Simple, but sparse; sampler(i) must stay < 16. */
        IDENTITY,
        /** per-kind counters: k-th UBO -> buffer(k), j-th texture-ish -> texture(j), m-th sampler -> sampler(m). */
        COMPACT
    }

    public record Options(int mslVersion, boolean flipVertexY, BindingMode bindingMode, boolean useDecorationBinding,
                          boolean textureBufferNative, boolean padFragmentOutputs, boolean pointSizeBuiltin,
                          boolean forceZeroInit) {
        public static Options defaults() {
            return new Options(20400, true, BindingMode.COMPACT, false, true, true, true, true);
        }
    }

    /** One CreateInfo.uniforms() entry (list index == SPIR-V binding in set 0) and where it lands in Metal. -1 = not applicable. */
    public record Slot(int binding, String name, UniformType type, int mslBuffer, int mslTexture, int mslSampler) {
    }

    public record StageResult(ShaderType type, String entryPoint, String msl, long nanos,
                              List<Integer> usedBindings, List<Integer> usedVertexAttributes) {
    }

    public record Result(List<Slot> slots, List<StageResult> stages) {
    }

    public static final class TranslationException extends Exception {
        public TranslationException(String message) {
            super(message);
        }
    }

    private MslTranslator() {
    }

    public static List<Slot> assignSlots(List<BindGroupLayout.UniformDescription> uniforms, BindingMode mode) throws TranslationException {
        List<Slot> slots = new ArrayList<>(uniforms.size());
        int nextBuffer = 0, nextTexture = 0, nextSampler = 0;
        for (int binding = 0; binding < uniforms.size(); binding++) {
            BindGroupLayout.UniformDescription u = uniforms.get(binding);
            int buf = -1, tex = -1, smp = -1;
            switch (u.type()) {
                case UNIFORM_BUFFER -> buf = mode == BindingMode.IDENTITY ? binding : nextBuffer++;
                case COMBINED_IMAGE_SAMPLER -> {
                    tex = mode == BindingMode.IDENTITY ? binding : nextTexture++;
                    smp = mode == BindingMode.IDENTITY ? binding : nextSampler++;
                }
                case TEXEL_BUFFER -> tex = mode == BindingMode.IDENTITY ? binding : nextTexture++;
            }
            if (buf >= MAX_UNIFORM_BUFFERS) throw new TranslationException("too many uniform buffers for the reserved range: " + u.name() + " -> buffer(" + buf + ")");
            if (smp >= MAX_SAMPLERS) throw new TranslationException("sampler index out of range: " + u.name() + " -> sampler(" + smp + ")");
            slots.add(new Slot(binding, u.name(), u.type(), buf, tex, smp));
        }
        return slots;
    }

    public static Result translate(BackendRenderPipeline.CreateInfo createInfo, Options options) throws TranslationException {
        List<Slot> slots = assignSlots(createInfo.uniforms(), options.bindingMode());
        List<StageResult> stages = new ArrayList<>();
        for (BackendRenderPipeline.CreateInfo.Shader shader : createInfo.shaders()) {
            stages.add(translateStage(shader, slots, options));
        }
        return new Result(slots, stages);
    }

    private static void check(long ctx, int result, String what) throws TranslationException {
        if (result != SPVC_SUCCESS) {
            String detail = ctx != 0L ? spvc_context_get_last_error_string(ctx) : "";
            throw new TranslationException(what + " failed (" + result + "): " + detail);
        }
    }

    public static StageResult translateStage(BackendRenderPipeline.CreateInfo.Shader shader, List<Slot> slots, Options o) throws TranslationException {
        long start = System.nanoTime();
        ShaderType type = shader.module().type();
        int model = type == ShaderType.VERTEX ? SPV_EXEC_MODEL_VERTEX : SPV_EXEC_MODEL_FRAGMENT;
        // The frontend has already rewritten DescriptorSet=0 / Binding=<pipeline-wide index> in these words.
        IntBuffer words = shader.module().spv().asIntBuffer();
        long ctx = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(0L, spvc_context_create(pp), "spvc_context_create");
            ctx = pp.get(0);
            check(ctx, spvc_context_parse_spirv(ctx, words, words.remaining(), pp), "spvc_context_parse_spirv");
            long ir = pp.get(0);
            check(ctx, spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pp), "spvc_context_create_compiler");
            long compiler = pp.get(0);
            check(ctx, spvc_compiler_create_compiler_options(compiler, pp), "spvc_compiler_create_compiler_options");
            long opts = pp.get(0);

            spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_VERSION, o.mslVersion());
            spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
            // renderpearl renders "GL memory layout" (see doc): flip clip-space Y in every vertex shader.
            spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, type == ShaderType.VERTEX && o.flipVertexY());
            // samplerBuffer/isamplerBuffer -> texture_buffer<T> (MSL 2.1+), instead of a 2D-texture emulation.
            spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, o.textureBufferNative());
            spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, o.padFragmentOutputs());
            spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, o.pointSizeBuiltin());
            spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, o.useDecorationBinding());
            if (o.forceZeroInit()) {
                spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);
            }

            if (!o.useDecorationBinding()) {
                SpvcMslResourceBinding2 b = SpvcMslResourceBinding2.calloc(stack);
                for (Slot s : slots) {
                    spvc_msl_resource_binding_init_2(b);
                    b.stage(model).desc_set(0).binding(s.binding()).count(1)
                        .msl_buffer(Math.max(s.mslBuffer(), 0))
                        .msl_texture(Math.max(s.mslTexture(), 0))
                        .msl_sampler(Math.max(s.mslSampler(), 0));
                    check(ctx, spvc_compiler_msl_add_resource_binding_2(compiler, b), "add_resource_binding " + s.name());
                }
            }
            // Push constants: SPIR-V has no set/binding for them; spvc matches the magic (desc_set=~0u, binding=0) pair.
            SpvcMslResourceBinding2 pc = SpvcMslResourceBinding2.calloc(stack);
            spvc_msl_resource_binding_init_2(pc);
            pc.stage(model).desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(SPVC_MSL_PUSH_CONSTANT_BINDING).count(1)
                .msl_buffer(PUSH_CONSTANT_BUFFER_INDEX);
            check(ctx, spvc_compiler_msl_add_resource_binding_2(compiler, pc), "add_resource_binding push constants");

            check(ctx, spvc_compiler_set_entry_point(compiler, shader.entryPoint(), model), "spvc_compiler_set_entry_point");
            check(ctx, spvc_compiler_install_compiler_options(compiler, opts), "spvc_compiler_install_compiler_options");
            check(ctx, spvc_compiler_compile(compiler, pp), "spvc_compiler_compile");
            String msl = MemoryUtil.memUTF8(pp.get(0));
            String entry = spvc_compiler_get_cleansed_entry_point_name(compiler, shader.entryPoint(), model);

            if (spvc_compiler_msl_needs_swizzle_buffer(compiler) || spvc_compiler_msl_needs_buffer_size_buffer(compiler)
                || spvc_compiler_msl_needs_output_buffer(compiler)) {
                throw new TranslationException("spvc wants an auxiliary buffer (swizzle/buffer-size/output); not supported by this layout");
            }

            List<Integer> used = new ArrayList<>();
            for (Slot s : slots) {
                if (spvc_compiler_msl_is_resource_used(compiler, model, 0, s.binding())) used.add(s.binding());
            }
            List<Integer> usedAttribs = new ArrayList<>();
            if (type == ShaderType.VERTEX) {
                for (int loc = 0; loc < 16; loc++) {
                    if (spvc_compiler_msl_is_vertex_attribute_used(compiler, loc)) usedAttribs.add(loc);
                }
            }
            return new StageResult(type, entry, msl, System.nanoTime() - start, List.copyOf(used), List.copyOf(usedAttribs));
        } finally {
            if (ctx != 0L) spvc_context_destroy(ctx);
        }
    }
}
