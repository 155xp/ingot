package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.ingot.Ingot;
import dev.ingot.nativ.Mtl;
import dev.ingot.shader.SpirvToMsl;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A compiled pipeline: per-stage MTLFunctions, the PSO variants (keyed by attachment formats) and the immutable
 * state the render pass needs on every setPipeline. Owner: pipelines.
 */
public final class MetalRenderPipeline implements BackendRenderPipeline {
	/** Argument-table index of push constants in both stages. CONTRACT */
	public static final int PUSH_CONSTANT_SLOT = SpirvToMsl.PUSH_CONSTANT_BUFFER;

	/** Set once any pipeline has built a non-default variant; later ones log at debug level. Render thread. */
	private static boolean variantWarned;

	private final MetalDevice device;
	private final String name;
	private final PrimitiveTopology topology;
	private final int primitiveType;
	private final int cullMode;
	private final int fillMode;
	private final @Nullable DepthStencilState depthStencilState;
	private final float depthBiasConstant;
	private final float depthBiasSlope;
	private final UniformType[] uniformTypes;
	private final int[] uniformSlots;
	private final int[] uniformSamplerSlots;
	private final int[] uniformStages;
	private final @Nullable GpuFormat[] texelBufferFormats;
	private final int pushConstantSize;
	private final int pushConstantStages;
	private final long vertexFunction;
	private final long fragmentFunction;
	/** The IngotPipelineDesc the default variant was built from; variants rewrite its attachment fields. */
	private final MemorySegment desc;
	private final List<@Nullable ColorTargetState> colorTargets;
	// PSO variants, render thread only. Index 0 is the default variant; a 0 state is a variant that failed to build.
	private long[] variantKeys = new long[2];
	private long[] variantStates = new long[2];
	private int variantCount;
	private boolean closed;

	MetalRenderPipeline(MetalDevice device, BackendRenderPipeline.CreateInfo createInfo, List<SpirvToMsl.Slot> slots, SpirvToMsl.Stage vertex,
						SpirvToMsl.@Nullable Stage fragment, long vertexFunction, long fragmentFunction, MemorySegment desc, long defaultKey,
						long defaultState) {
		this.device = device;
		this.name = createInfo.name();
		this.topology = createInfo.primitiveTopology();
		this.primitiveType = MetalFormats.primitiveType(this.topology);
		this.cullMode = createInfo.cull() ? MetalFormats.CULL_BACK : MetalFormats.CULL_NONE;
		this.fillMode = createInfo.polygonMode() == PolygonMode.WIREFRAME ? MetalFormats.FILL_LINES : MetalFormats.FILL_FILL;
		this.depthStencilState = createInfo.depthStencilState();
		this.depthBiasConstant = this.depthStencilState != null ? this.depthStencilState.depthBiasConstant() : 0.0F;
		this.depthBiasSlope = this.depthStencilState != null ? this.depthStencilState.depthBiasScaleFactor() : 0.0F;

		int count = createInfo.uniforms().size();
		this.uniformTypes = new UniformType[count];
		this.uniformSlots = new int[count];
		this.uniformSamplerSlots = new int[count];
		this.uniformStages = new int[count];
		this.texelBufferFormats = new GpuFormat[count];
		for (int i = 0; i < count; i++) {
			BindGroupLayout.UniformDescription uniform = createInfo.uniforms().get(i);
			SpirvToMsl.Slot slot = slots.get(i);
			this.uniformTypes[i] = uniform.type();
			this.uniformSlots[i] = uniform.type() == UniformType.UNIFORM_BUFFER ? slot.buffer() : slot.texture();
			this.uniformSamplerSlots[i] = slot.sampler();
			this.uniformStages[i] = stageMask(vertex.uses(i), fragment != null && fragment.uses(i));
			this.texelBufferFormats[i] = uniform.type() == UniformType.TEXEL_BUFFER ? uniform.gpuFormat() : null;
		}
		this.pushConstantSize = createInfo.pushConstantsSize();
		this.pushConstantStages = stageMask(vertex.usesPushConstants(), fragment != null && fragment.usesPushConstants());

		this.vertexFunction = vertexFunction;
		this.fragmentFunction = fragmentFunction;
		this.desc = desc;
		this.colorTargets = createInfo.colorTargetStates();
		this.variantKeys[0] = defaultKey;
		this.variantStates[0] = defaultState;
		this.variantCount = 1;
	}

	private static int stageMask(boolean vertex, boolean fragment) {
		return (vertex ? Mtl.STAGE_VERTEX : 0) | (fragment ? Mtl.STAGE_FRAGMENT : 0);
	}

	/** Packs a variant's attachment signature. Pixel formats are small enum values (< 2^24). */
	static long variantKey(int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask) {
		return (long) depthPixelFormat << 40 | (long) stencilPixelFormat << 16 | colorAttachmentMask & 0xFFFFL;
	}

	/** Argument-table index (vertex stage) of renderpearl vertex buffer slot {@code slot}. CONTRACT */
	public static int vertexBufferSlot(int slot) {
		return 30 - slot;
	}

	/** CONTRACT */
	public String name() {
		return this.name;
	}

	/** CONTRACT */
	public PrimitiveTopology topology() {
		return this.topology;
	}

	/** MTLPrimitiveType for draws (TRIANGLE_FAN reports triangle; the pass converts fans). CONTRACT */
	public int primitiveType() {
		return this.primitiveType;
	}

	/** MTLCullMode. CONTRACT */
	public int cullMode() {
		return this.cullMode;
	}

	/** MTLTriangleFillMode. CONTRACT */
	public int fillMode() {
		return this.fillMode;
	}

	/** CONTRACT */
	public float depthBiasConstant() {
		return this.depthBiasConstant;
	}

	/** CONTRACT */
	public float depthBiasSlope() {
		return this.depthBiasSlope;
	}

	/** Null = the pipeline has no depth test/write. CONTRACT */
	public @Nullable DepthStencilState depthStencilState() {
		return this.depthStencilState;
	}

	/** Size of CreateInfo.uniforms(). CONTRACT */
	public int uniformCount() {
		return this.uniformTypes.length;
	}

	/** CONTRACT */
	public UniformType uniformType(int index) {
		return this.uniformTypes[index];
	}

	/** Mtl.STAGE_VERTEX | Mtl.STAGE_FRAGMENT mask of stages that read uniform {@code index}; 0 if none. CONTRACT */
	public int uniformStages(int index) {
		return this.uniformStages[index];
	}

	/** Buffer index (UNIFORM_BUFFER) or texture index (COMBINED_IMAGE_SAMPLER, TEXEL_BUFFER). CONTRACT */
	public int uniformSlot(int index) {
		return this.uniformSlots[index];
	}

	/** Sampler index for COMBINED_IMAGE_SAMPLER, -1 otherwise. CONTRACT */
	public int uniformSamplerSlot(int index) {
		return this.uniformSamplerSlots[index];
	}

	/** Texel format for TEXEL_BUFFER uniforms, null otherwise. CONTRACT */
	public @Nullable GpuFormat texelBufferFormat(int index) {
		return this.texelBufferFormats[index];
	}

	/** Declared push constant size in bytes (0 = none). CONTRACT */
	public int pushConstantSize() {
		return this.pushConstantSize;
	}

	/** Mtl.STAGE_* mask of stages that read the push constant block; 0 if none. CONTRACT */
	public int pushConstantStages() {
		return this.pushConstantStages;
	}

	/**
	 * id&lt;MTLRenderPipelineState&gt; for a pass with these attachments, compiled on first use and cached.
	 * Returns 0 if the variant cannot be built (logged once). Render thread. CONTRACT
	 */
	public long pipelineState(int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask) {
		long key = variantKey(depthPixelFormat, stencilPixelFormat, colorAttachmentMask);
		for (int i = 0; i < this.variantCount; i++) {
			if (this.variantKeys[i] == key) {
				return this.variantStates[i];
			}
		}
		return this.createVariant(key, depthPixelFormat, stencilPixelFormat, colorAttachmentMask);
	}

	private long createVariant(long key, int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask) {
		if (this.closed) {
			return 0L;
		}
		String attachments = "depth format " + depthPixelFormat + ", stencil format " + stencilPixelFormat + ", color attachments 0b"
			+ Integer.toBinaryString(colorAttachmentMask);
		long state = 0L;
		try {
			MetalPipelineCompiler.writeAttachments(this.desc, this.colorTargets, depthPixelFormat, stencilPixelFormat, colorAttachmentMask);
			state = Mtl.pipelineCreate(this.device.handle(), this.desc.address());
			if (state == 0L) {
				Ingot.LOGGER.error("Ingot: cannot build pipeline {} for {}: {}", this.name, attachments, MetalPipelineCompiler.lastError());
			} else if (!variantWarned) {
				variantWarned = true;
				Ingot.LOGGER.warn("Ingot: compiling a render pipeline state on the render thread: {} for {} (further variants are logged at debug level)",
					this.name, attachments);
			} else {
				Ingot.LOGGER.debug("Ingot: compiling a render pipeline state on the render thread: {} for {}", this.name, attachments);
			}
		} catch (MetalPipelineCompiler.PipelineException e) {
			Ingot.LOGGER.error("Ingot: cannot build pipeline {} for {}: {}", this.name, attachments, e.getMessage());
		}
		// Failures are cached as 0 so they are neither retried nor logged again.
		if (this.variantCount == this.variantKeys.length) {
			this.variantKeys = Arrays.copyOf(this.variantKeys, this.variantCount * 2);
			this.variantStates = Arrays.copyOf(this.variantStates, this.variantCount * 2);
		}
		this.variantKeys[this.variantCount] = key;
		this.variantStates[this.variantCount] = state;
		this.variantCount++;
		return state;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		for (int i = 0; i < this.variantCount; i++) {
			if (this.variantStates[i] != 0L) {
				this.device.releaseLater(this.variantStates[i]);
			}
		}
		this.variantCount = 0;
		this.device.releaseLater(this.vertexFunction);
		if (this.fragmentFunction != 0L) {
			this.device.releaseLater(this.fragmentFunction);
		}
	}
}
