package dev.ingot.backend;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import dev.ingot.Ingot;
import dev.ingot.shader.SpirvToMsl;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The device's pipelines, compiled on first use and kept until the next resource reload (clearPipelineCache), like
 * the vanilla backends. GLSL → SPIR-V with vanilla's Vulkan GLSL compiler (shaderc), whose rebind numbers the vertex
 * inputs and stage interface and rewrites every resource binding to its index in the pipeline's binding list, exactly
 * as for Vulkan; MetalPipelineCompiler takes it from there. Render thread. Owner: pipelines.
 */
final class MetalPipelineCache {
	/** A pipeline whose shaders failed to compile (logged); setPipeline skips its draws. */
	static final CompiledRenderPipeline INVALID = () -> false;
	// SpvDim of sampled images.
	private static final int SPV_DIM_2D = 1;
	private static final int SPV_DIM_CUBE = 3;
	private static final int SPV_DIM_BUFFER = 5;

	private final MetalDevice device;
	private final ShaderSource defaultShaderSource;
	private final GlslCompiler glslCompiler = new GlslCompiler();
	private final Map<RenderPipeline, CompiledRenderPipeline> pipelines = new IdentityHashMap<>();
	private final Map<ShaderKey, IntermediaryShaderModule> shaders = new HashMap<>();

	MetalPipelineCache(MetalDevice device, ShaderSource defaultShaderSource) {
		this.device = device;
		this.defaultShaderSource = defaultShaderSource;
	}

	/** The compiled pipeline (INVALID if it cannot be built), compiling it from {@code shaderSource} on first use. */
	CompiledRenderPipeline get(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
		CompiledRenderPipeline compiled = this.pipelines.get(pipeline);
		if (compiled == null) {
			compiled = this.compile(pipeline, shaderSource != null ? shaderSource : this.defaultShaderSource);
			this.pipelines.put(pipeline, compiled);
		}
		return compiled;
	}

	/** Drops every pipeline and shader; the GPU may still use the pipelines, so their release is deferred. */
	void clear() {
		for (CompiledRenderPipeline pipeline : this.pipelines.values()) {
			if (pipeline instanceof MetalRenderPipeline metalPipeline) {
				metalPipeline.close();
			}
		}
		this.pipelines.clear();
		this.shaders.values().forEach(IntermediaryShaderModule::close);
		this.shaders.clear();
	}

	void close() {
		this.clear();
		this.glslCompiler.close();
	}

	private CompiledRenderPipeline compile(RenderPipeline pipeline, ShaderSource shaderSource) {
		IntermediaryShaderModule vertex = this.shader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
		IntermediaryShaderModule fragment = this.shader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
		if (vertex == IntermediaryShaderModule.INVALID || fragment == IntermediaryShaderModule.INVALID) {
			Ingot.LOGGER.error("Ingot: cannot compile pipeline {}: {} shader {} was invalid", pipeline.getLocation(),
				vertex == IntermediaryShaderModule.INVALID ? "vertex" : "fragment",
				vertex == IntermediaryShaderModule.INVALID ? pipeline.getVertexShader() : pipeline.getFragmentShader());
			return INVALID;
		}
		List<SpirvToMsl.Binding> bindings = new ArrayList<>();
		try {
			SpirvToMsl.Reflection vertexReflection = SpirvToMsl.reflect(vertex.spirv());
			addBindings(bindings, vertexReflection, pipeline);
			addBindings(bindings, SpirvToMsl.reflect(fragment.spirv()), pipeline);
			List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>(bindings.size());
			for (SpirvToMsl.Binding binding : bindings) {
				entries.add(new VulkanBindGroupLayout.Entry(switch (binding.type()) {
					case UNIFORM_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER;
					case COMBINED_IMAGE_SAMPLER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE;
					case TEXEL_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER;
				}, binding.name(), binding.texelFormat()));
			}
			// Rewrites the cached modules in place; they are translated right away, before any other pipeline rebinds them.
			vertex.rebind(vertexInputNames(pipeline), entries);
			fragment.rebind(vertexReflection.outputs(), entries);
		} catch (ShaderCompileException | SpirvToMsl.TranslationException e) {
			Ingot.LOGGER.error("Ingot: cannot compile pipeline {}: {}", pipeline.getLocation(), e.getMessage());
			return INVALID;
		}
		MetalRenderPipeline compiled = MetalPipelineCompiler.compile(this.device, pipeline, vertex.spirv(), fragment.spirv(), bindings);
		return compiled != null ? compiled : INVALID;
	}

	private IntermediaryShaderModule shader(Identifier id, ShaderType type, ShaderDefines defines, ShaderSource shaderSource) {
		ShaderKey key = new ShaderKey(id, type, defines);
		IntermediaryShaderModule shader = this.shaders.get(key);
		if (shader == null) {
			shader = this.compileShader(key, shaderSource);
			this.shaders.put(key, shader);
		}
		return shader;
	}

	private IntermediaryShaderModule compileShader(ShaderKey key, ShaderSource shaderSource) {
		String source = shaderSource.get(key.id(), key.type());
		if (source == null) {
			Ingot.LOGGER.error("Ingot: no source for {} shader {}", key.type(), key.id());
			return IntermediaryShaderModule.INVALID;
		}
		try {
			return this.glslCompiler.createIntermediary(key.id().toDebugFileName(), GlslPreprocessor.injectDefines(source, key.defines()), key.type());
		} catch (ShaderCompileException e) {
			Ingot.LOGGER.error("Ingot: cannot compile {} shader {}: {}", key.type(), key.id(), e.getMessage());
			return IntermediaryShaderModule.INVALID;
		}
	}

	/** Vertex attribute names in location order: every element of every vertex format, in buffer slot order. */
	private static List<String> vertexInputNames(RenderPipeline pipeline) {
		List<String> names = new ArrayList<>();
		for (VertexFormat format : pipeline.getVertexFormatBindings()) {
			if (format != null) {
				for (VertexFormatElement element : format.getElements()) {
					names.add(element.name());
				}
			}
		}
		return names;
	}

	/** The resources a stage declares, in the vanilla Vulkan order (uniform buffers, then samplers), without duplicates. */
	private static void addBindings(List<SpirvToMsl.Binding> bindings, SpirvToMsl.Reflection shader, RenderPipeline pipeline)
		throws ShaderCompileException {
		List<BindGroupLayout.UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
		for (String name : shader.uniformBuffers()) {
			if (description(uniforms, name) == null) {
				throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
			}
			addBinding(bindings, new SpirvToMsl.Binding(name, SpirvToMsl.BindingType.UNIFORM_BUFFER, null));
		}
		for (int i = 0; i < shader.samplers().size(); i++) {
			String name = shader.samplers().get(i);
			int dimensions = shader.samplerDimensions().get(i);
			BindGroupLayout.UniformDescription description = description(uniforms, name);
			if (description != null) {
				if (dimensions != SPV_DIM_BUFFER) {
					throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
				}
				addBinding(bindings, new SpirvToMsl.Binding(name, SpirvToMsl.BindingType.TEXEL_BUFFER, description.gpuFormat()));
			} else {
				if (!BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()).contains(name)) {
					throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
				}
				if (dimensions != SPV_DIM_2D && dimensions != SPV_DIM_CUBE) {
					throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
				}
				addBinding(bindings, new SpirvToMsl.Binding(name, SpirvToMsl.BindingType.COMBINED_IMAGE_SAMPLER, null));
			}
		}
	}

	private static BindGroupLayout.@Nullable UniformDescription description(List<BindGroupLayout.UniformDescription> uniforms, String name) {
		for (BindGroupLayout.UniformDescription uniform : uniforms) {
			if (uniform.name().equals(name)) {
				return uniform;
			}
		}
		return null;
	}

	private static void addBinding(List<SpirvToMsl.Binding> bindings, SpirvToMsl.Binding binding) {
		for (SpirvToMsl.Binding existing : bindings) {
			if (existing.type() == binding.type() && existing.name().equals(binding.name())) {
				return;
			}
		}
		bindings.add(binding);
	}

	private record ShaderKey(Identifier id, ShaderType type, ShaderDefines defines) {
	}
}
