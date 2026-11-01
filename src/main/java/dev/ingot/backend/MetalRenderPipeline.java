package dev.ingot.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
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
public final class MetalRenderPipeline implements CompiledRenderPipeline {
	/** Set once any pipeline has built a variant on the render thread; later ones log at debug level. Render thread. */
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
	private final String[] uniformNames;
	private final SpirvToMsl.BindingType[] uniformTypes;
	private final int[] uniformSlots;
	private final int[] uniformSamplerSlots;
	private final int[] uniformStages;
	private final @Nullable GpuFormat[] texelBufferFormats;
	private final long vertexFunction;
	private final long fragmentFunction;
	/** The IngotPipelineDesc the default variant was built from; variants rewrite its attachment fields. */
	private final MemorySegment desc;
	private final List<@Nullable ColorTargetState> colorTargets;
	// PSO variants, render thread only once published. Index 0 is the default variant (plus the D32 one for depth-less
	// pipelines, built by the compiling worker); a 0 state is a variant that failed to build.
	private long[] variantKeys = new long[2];
	private long[] variantStates = new long[2];
	private int variantCount;
	private boolean closed;

	MetalRenderPipeline(MetalDevice device, RenderPipeline renderPipeline, List<@Nullable ColorTargetState> colorTargets,
						List<SpirvToMsl.Binding> bindings, List<SpirvToMsl.Slot> slots, SpirvToMsl.Stage vertex, SpirvToMsl.Stage fragment,
						long vertexFunction, long fragmentFunction, MemorySegment desc, long defaultKey, long defaultState) {
		this.device = device;
		this.name = renderPipeline.getLocation().toString();
		this.topology = renderPipeline.getPrimitiveTopology();
		this.primitiveType = MetalFormats.primitiveType(this.topology);
		this.cullMode = renderPipeline.isCull() ? MetalFormats.CULL_BACK : MetalFormats.CULL_NONE;
		this.fillMode = renderPipeline.getPolygonMode() == PolygonMode.WIREFRAME ? MetalFormats.FILL_LINES : MetalFormats.FILL_FILL;
		this.depthStencilState = renderPipeline.getDepthStencilState();
		this.depthBiasConstant = this.depthStencilState != null ? this.depthStencilState.depthBiasConstant() : 0.0F;
		this.depthBiasSlope = this.depthStencilState != null ? this.depthStencilState.depthBiasScaleFactor() : 0.0F;

		int count = bindings.size();
		this.uniformNames = new String[count];
		this.uniformTypes = new SpirvToMsl.BindingType[count];
		this.uniformSlots = new int[count];
		this.uniformSamplerSlots = new int[count];
		this.uniformStages = new int[count];
		this.texelBufferFormats = new GpuFormat[count];
		for (int i = 0; i < count; i++) {
			SpirvToMsl.Binding binding = bindings.get(i);
			SpirvToMsl.Slot slot = slots.get(i);
			this.uniformNames[i] = binding.name();
			this.uniformTypes[i] = binding.type();
			this.uniformSlots[i] = binding.type() == SpirvToMsl.BindingType.UNIFORM_BUFFER ? slot.buffer() : slot.texture();
			this.uniformSamplerSlots[i] = slot.sampler();
			this.uniformStages[i] = stageMask(vertex.uses(i), fragment.uses(i));
			this.texelBufferFormats[i] = binding.texelFormat();
		}

		this.vertexFunction = vertexFunction;
		this.fragmentFunction = fragmentFunction;
		this.desc = desc;
		this.colorTargets = colorTargets;
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

	/** Argument-table index (vertex stage) of RenderPass vertex buffer slot {@code slot}. CONTRACT */
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

	/** Number of bindings (uniform buffers, texel buffers and sampled textures). CONTRACT */
	public int uniformCount() {
		return this.uniformTypes.length;
	}

	/** Index of the binding the game sets under {@code name}, or -1 if the pipeline has none. CONTRACT */
	public int uniformIndex(String name) {
		String[] names = this.uniformNames;
		for (int i = 0; i < names.length; i++) {
			// Names are mostly the same string constants, so the identity check usually decides.
			if (names[i] == name || names[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** CONTRACT */
	public String uniformName(int index) {
		return this.uniformNames[index];
	}

	/** CONTRACT */
	public SpirvToMsl.BindingType uniformType(int index) {
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

	/**
	 * id&lt;MTLRenderPipelineState&gt; for a pass with these attachments, compiled on first use (unless the compiling
	 * worker already built it) and cached. Returns 0 if the variant cannot be built (logged once). Render thread. CONTRACT
	 */
	public long pipelineState(int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask) {
		long key = variantKey(depthPixelFormat, stencilPixelFormat, colorAttachmentMask);
		for (int i = 0; i < this.variantCount; i++) {
			if (this.variantKeys[i] == key) {
				return this.variantStates[i];
			}
		}
		return this.createVariant(key, depthPixelFormat, stencilPixelFormat, colorAttachmentMask, true);
	}

	/** Builds a variant while the pipeline is being compiled, before its first use. */
	void prebuildVariant(int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask) {
		this.createVariant(variantKey(depthPixelFormat, stencilPixelFormat, colorAttachmentMask), depthPixelFormat, stencilPixelFormat,
			colorAttachmentMask, false);
	}

	private long createVariant(long key, int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask, boolean renderThread) {
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
			} else if (renderThread && !variantWarned) {
				variantWarned = true;
				Ingot.LOGGER.warn("Ingot: compiling a render pipeline state on the render thread: {} for {} (further variants are logged at debug level)",
					this.name, attachments);
			} else if (renderThread) {
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
	public boolean isValid() {
		return true;
	}

	public boolean isClosed() {
		return this.closed;
	}

	/** Releases the PSOs and functions once the GPU is done with them (MetalPipelineCache.clear). */
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
