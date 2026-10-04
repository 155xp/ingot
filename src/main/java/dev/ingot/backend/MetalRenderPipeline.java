package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import org.jspecify.annotations.Nullable;

/**
 * A compiled pipeline: per-stage MTLFunctions, the PSO variants (keyed by attachment formats) and the immutable
 * state the render pass needs on every setPipeline. Owner: pipelines.
 */
public final class MetalRenderPipeline implements BackendRenderPipeline {
	/** Argument-table index of push constants in both stages. CONTRACT */
	public static final int PUSH_CONSTANT_SLOT = 14;

	/** Argument-table index (vertex stage) of renderpearl vertex buffer slot {@code slot}. CONTRACT */
	public static int vertexBufferSlot(int slot) {
		return 30 - slot;
	}

	/** CONTRACT */
	public String name() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CONTRACT */
	public PrimitiveTopology topology() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** MTLPrimitiveType for draws (TRIANGLE_FAN reports triangle; the pass converts fans). CONTRACT */
	public int primitiveType() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** MTLCullMode. CONTRACT */
	public int cullMode() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** MTLTriangleFillMode. CONTRACT */
	public int fillMode() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CONTRACT */
	public float depthBiasConstant() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CONTRACT */
	public float depthBiasSlope() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Null = the pipeline has no depth test/write. CONTRACT */
	public @Nullable DepthStencilState depthStencilState() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Size of CreateInfo.uniforms(). CONTRACT */
	public int uniformCount() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CONTRACT */
	public UniformType uniformType(int index) {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Mtl.STAGE_VERTEX | Mtl.STAGE_FRAGMENT mask of stages that read uniform {@code index}; 0 if none. CONTRACT */
	public int uniformStages(int index) {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Buffer index (UNIFORM_BUFFER) or texture index (COMBINED_IMAGE_SAMPLER, TEXEL_BUFFER). CONTRACT */
	public int uniformSlot(int index) {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Sampler index for COMBINED_IMAGE_SAMPLER, -1 otherwise. CONTRACT */
	public int uniformSamplerSlot(int index) {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Texel format for TEXEL_BUFFER uniforms, null otherwise. CONTRACT */
	public @Nullable GpuFormat texelBufferFormat(int index) {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Declared push constant size in bytes (0 = none). CONTRACT */
	public int pushConstantSize() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Stages that declare the push constant block. CONTRACT */
	public int pushConstantStages() {
		throw new UnsupportedOperationException("not implemented");
	}

	/**
	 * id&lt;MTLRenderPipelineState&gt; for a pass with these attachments, compiled on first use and cached.
	 * Returns 0 if the variant cannot be built (logged once). Render thread. CONTRACT
	 */
	public long pipelineState(int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public boolean isClosed() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException("not implemented");
	}
}
