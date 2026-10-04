package dev.ingot.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.ingot.Ingot;
import dev.ingot.nativ.Mtl;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Everything recorded inside one render encoder: cached state, lazy uniform binding, draws, multi-draw loops,
 * triangle-fan conversion. The render encoder itself is begun and ended by MetalCommandEncoder. Owner: passes.
 *
 * <p>This is the hottest code in Ingot. Encoder state is mirrored in Java and only sent when it changes. setUniform
 * just stores the value (the frontend replays every stored uniform after each setPipeline); the next draw binds the
 * uniforms marked dirty, and only argument-table entries whose handle or offset differ from what the encoder already
 * holds cross the FFM boundary (an offset-only change uses setBufferOffset).
 */
public final class MetalRenderPass implements RenderPassBackend {
	private static final int BUFFER_SLOTS = 31;
	private static final int TEXTURE_SLOTS = 31;
	private static final int SAMPLER_SLOTS = 16;
	/** The dirty and missing uniform masks are longs. Metal's argument tables cap real pipelines well below this. */
	private static final int MAX_UNIFORMS = 64;
	/** Push constants are at most 128 bytes and always sent padded to that size (MSL structs can be larger). */
	private static final int PUSH_CONSTANT_BYTES = 128;
	private static final int FAN_MIN_VERTEX_CAPACITY = 64;

	// Passes never overlap (the frontend allows one open pass, all on the render thread), so every pass reuses these
	// tables instead of allocating its own. They mirror the current encoder's argument tables, per stage.
	private static final long[] VERTEX_BUFFERS = new long[BUFFER_SLOTS];
	private static final long[] VERTEX_BUFFER_OFFSETS = new long[BUFFER_SLOTS];
	private static final long[] FRAGMENT_BUFFERS = new long[BUFFER_SLOTS];
	private static final long[] FRAGMENT_BUFFER_OFFSETS = new long[BUFFER_SLOTS];
	private static final long[] VERTEX_TEXTURES = new long[TEXTURE_SLOTS];
	private static final long[] FRAGMENT_TEXTURES = new long[TEXTURE_SLOTS];
	private static final long[] VERTEX_SAMPLERS = new long[SAMPLER_SLOTS];
	private static final long[] FRAGMENT_SAMPLERS = new long[SAMPLER_SLOTS];
	/** Uniform values by pipeline uniform index, as last given to setUniform. */
	private static final @Nullable Object[] UNIFORMS = new Object[MAX_UNIFORMS];
	private static final long PUSH_CONSTANTS = Arena.global().allocate(PUSH_CONSTANT_BYTES, 16).address();
	/** Pipelines whose draws were skipped, so each problem is logged once. */
	private static final Set<String> WARNED = new HashSet<>();

	// Shared uint32 index list (0, k, k + 1) for k = 1, 2, ... that turns any non-indexed fan into a triangle list
	// when drawn with baseVertex = firstVertex. Grown on demand; render thread only.
	private static @Nullable MetalDevice fanDevice;
	private static long fanIndexBuffer;
	private static int fanVertexCapacity;

	private final MetalDevice device;
	private final MetalCommandEncoder encoder;
	private final long encoderHandle;
	/** Serial of the command buffer this pass records into; stamped on every buffer the pass uses. */
	private final long serial;
	private final boolean debugLabels;
	private final int depthPixelFormat;
	private final int stencilPixelFormat;
	private final int colorAttachmentMask;
	private final boolean hasDepth;
	private final RenderPass.RenderArea renderArea;

	private @Nullable MetalRenderPipeline pipeline;
	/** PSO variant for this pass's attachments; 0 when it could not be built, which skips draws. */
	private long pipelineState;
	private int primitive;
	private boolean fan;
	private int uniformCount;
	private long uniformMask;
	private long dirtyUniforms;
	/** Uniforms the pipeline reads that have no (valid) value; draws are skipped while any is set. */
	private long missingUniforms;

	private @Nullable MetalBuffer indexBuffer;
	private long indexHandle;
	private int indexType;
	private int indexSize;

	// Encoder state as last sent. A new encoder starts with no culling, fill mode Fill, no depth bias, clockwise
	// front faces (the winding Ingot needs, so it is never changed) and the default "always pass, no write"
	// depth-stencil state, which is what a null DepthStencilState means.
	private long boundPipelineState;
	private @Nullable DepthStencilState boundDepthState;
	private long boundDepthStencil;
	private int boundCull = MetalFormats.CULL_NONE;
	private int boundFill = MetalFormats.FILL_FILL;
	private float boundBiasConstant;
	private float boundBiasSlope;
	private int scissorX;
	private int scissorY;
	private int scissorWidth;
	private int scissorHeight;

	/**
	 * Called by MetalCommandEncoder.createRenderPass right after ingot_render_begin. The pass must set the
	 * viewport to (0, 0, width, height), the scissor to the descriptor's render area, the front-face winding
	 * (clockwise) and is responsible for all later encoder state. CONTRACT
	 *
	 * @param encoderHandle       id&lt;MTLRenderCommandEncoder&gt; (owned by MetalCommandEncoder)
	 * @param depthPixelFormat    MTLPixelFormat of the depth attachment, 0 if none
	 * @param stencilPixelFormat  MTLPixelFormat of the stencil attachment, 0 if none
	 * @param colorAttachmentMask bit i set when color slot i has a texture
	 * @param width               attachment width (viewport)
	 * @param height              attachment height (viewport)
	 */
	public MetalRenderPass(MetalDevice device, MetalCommandEncoder encoder, long encoderHandle, RenderPassDescriptor descriptor,
						   int depthPixelFormat, int stencilPixelFormat, int colorAttachmentMask, int width, int height) {
		this.device = device;
		this.encoder = encoder;
		this.encoderHandle = encoderHandle;
		this.serial = device.currentSerial();
		this.debugLabels = device.debugLabels();
		this.depthPixelFormat = depthPixelFormat;
		this.stencilPixelFormat = stencilPixelFormat;
		this.colorAttachmentMask = colorAttachmentMask;
		this.hasDepth = depthPixelFormat != MetalFormats.PIXEL_INVALID;
		this.renderArea = descriptor.renderArea();

		Arrays.fill(VERTEX_BUFFERS, 0L);
		Arrays.fill(FRAGMENT_BUFFERS, 0L);
		Arrays.fill(VERTEX_TEXTURES, 0L);
		Arrays.fill(FRAGMENT_TEXTURES, 0L);
		Arrays.fill(VERTEX_SAMPLERS, 0L);
		Arrays.fill(FRAGMENT_SAMPLERS, 0L);

		Mtl.renderSetViewport(encoderHandle, 0.0, 0.0, width, height, 0.0, 1.0);
		// The encoder's initial scissor is the whole attachment, so the usual full-size render area costs no call.
		this.scissorWidth = width;
		this.scissorHeight = height;
		this.disableScissor();
	}

	/** Called by MetalCommandEncoder.submitRenderPass before it ends the encoder. CONTRACT */
	public void finish() {
		// Drop references to the pass's resources; the shared tables are reset by the next pass.
		Arrays.fill(UNIFORMS, 0, this.uniformCount, null);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		if (this.debugLabels) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Mtl.renderPushDebugGroup(this.encoderHandle, MemoryUtil.memAddress(stack.UTF8(label.get())));
			}
		}
	}

	@Override
	public void popDebugGroup() {
		if (this.debugLabels) {
			Mtl.renderPopDebugGroup(this.encoderHandle);
		}
	}

	@Override
	public void setPipeline(BackendRenderPipeline backendPipeline) {
		MetalRenderPipeline pipeline = (MetalRenderPipeline) backendPipeline;
		if (pipeline == this.pipeline) {
			// Same uniform layout: the frontend's replay of the stored uniforms marks exactly what may have changed.
			return;
		}
		this.pipeline = pipeline;
		this.primitive = pipeline.primitiveType();
		this.fan = pipeline.topology() == PrimitiveTopology.TRIANGLE_FAN;

		// Uniform indices mean different things in different pipelines: forget the old values. The frontend sends
		// every stored uniform the new pipeline uses right after this; anything it does not send is missing.
		int count = Math.min(pipeline.uniformCount(), MAX_UNIFORMS);
		Arrays.fill(UNIFORMS, 0, Math.max(count, this.uniformCount), null);
		this.uniformCount = count;
		this.uniformMask = count == MAX_UNIFORMS ? -1L : (1L << count) - 1;
		this.dirtyUniforms = this.uniformMask;
		this.missingUniforms = 0L;

		if (pipeline.uniformCount() > MAX_UNIFORMS) {
			warnOnce(pipeline, "uses more uniforms than a render pass tracks");
			this.pipelineState = 0L;
			return;
		}
		// 0 when the variant for these attachments cannot be built (the pipeline logs that once).
		long state = pipeline.pipelineState(this.depthPixelFormat, this.stencilPixelFormat, this.colorAttachmentMask);
		this.pipelineState = state;
		if (state == 0L) {
			return;
		}
		if (state != this.boundPipelineState) {
			Mtl.renderSetPipeline(this.encoderHandle, state);
			this.boundPipelineState = state;
		}

		if (this.hasDepth) {
			DepthStencilState depthState = pipeline.depthStencilState();
			if (depthState != this.boundDepthState) {
				this.boundDepthState = depthState;
				long depthStencil = this.device.depthStencilState(depthState);
				if (depthStencil != this.boundDepthStencil) {
					Mtl.renderSetDepthStencil(this.encoderHandle, depthStencil);
					this.boundDepthStencil = depthStencil;
				}
			}
			float biasConstant = pipeline.depthBiasConstant();
			float biasSlope = pipeline.depthBiasSlope();
			if (biasConstant != this.boundBiasConstant || biasSlope != this.boundBiasSlope) {
				Mtl.renderSetDepthBias(this.encoderHandle, biasConstant, biasSlope, 0.0F);
				this.boundBiasConstant = biasConstant;
				this.boundBiasSlope = biasSlope;
			}
		}

		int cull = pipeline.cullMode();
		int fill = pipeline.fillMode();
		if (cull != this.boundCull || fill != this.boundFill) {
			Mtl.renderSetRaster(this.encoderHandle, cull, MetalFormats.WINDING_CLOCKWISE, fill);
			this.boundCull = cull;
			this.boundFill = fill;
		}
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		if (index < MAX_UNIFORMS) {
			UNIFORMS[index] = value;
			this.dirtyUniforms |= 1L << index;
		}
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		int stages = this.pipeline.pushConstantStages();
		if (stages == 0) {
			return;
		}
		int length = Math.min(value.remaining(), PUSH_CONSTANT_BYTES);
		if (value.isDirect()) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(value), PUSH_CONSTANTS, length);
		} else {
			for (int i = 0, position = value.position(); i < length; i++) {
				MemoryUtil.memPutByte(PUSH_CONSTANTS + i, value.get(position + i));
			}
		}
		// setBytes copies at call time, so the scratch can be reused immediately.
		Mtl.renderSetBytes(this.encoderHandle, stages, PUSH_CONSTANTS, PUSH_CONSTANT_BYTES, MetalRenderPipeline.PUSH_CONSTANT_SLOT);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		if (x != this.scissorX || y != this.scissorY || width != this.scissorWidth || height != this.scissorHeight) {
			Mtl.renderSetScissor(this.encoderHandle, x, y, width, height);
			this.scissorX = x;
			this.scissorY = y;
			this.scissorWidth = width;
			this.scissorHeight = height;
		}
	}

	@Override
	public void disableScissor() {
		RenderPass.RenderArea area = this.renderArea;
		this.enableScissor(area.x(), area.y(), area.width(), area.height());
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		// Like Vulkan, null keeps the previous binding (CloudRenderer pulls vertices from a texel buffer instead).
		if (vertexBuffer != null) {
			MetalBuffer buffer = (MetalBuffer) vertexBuffer.buffer();
			buffer.lastUseSerial = this.serial;
			this.bindBuffer(Mtl.STAGE_VERTEX, buffer.handle(), vertexBuffer.offset(), MetalRenderPipeline.vertexBufferSlot(slot));
		}
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		MetalBuffer buffer = (MetalBuffer) indexBuffer;
		buffer.lastUseSerial = this.serial;
		this.indexBuffer = buffer;
		this.indexHandle = buffer.handle();
		this.indexType = MetalFormats.indexType(indexType);
		this.indexSize = indexType.bytes;
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (indexCount <= 0 || instanceCount <= 0 || !this.readyToDrawIndexed()) {
			return;
		}
		long offset = (long) firstIndex * this.indexSize;
		if (this.fan) {
			this.drawIndexedFan(offset, indexCount, instanceCount, vertexOffset, firstInstance);
		} else {
			Mtl.renderDrawIndexed(this.encoderHandle, this.primitive, indexCount, this.indexType, this.indexHandle, offset, instanceCount,
				vertexOffset, firstInstance);
		}
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (drawCount <= 0 || instanceCount <= 0 || !this.readyToDrawIndexed()) {
			return;
		}
		long params = address(drawParameters);
		if (this.fan) {
			for (int i = 0; i < drawCount; i++, params += 12) {
				this.drawIndexedFan((long) MemoryUtil.memGetInt(params) * this.indexSize, MemoryUtil.memGetInt(params + 4), instanceCount,
					MemoryUtil.memGetInt(params + 8), firstInstance);
			}
		} else {
			Mtl.renderMultiDrawIndexed(this.encoderHandle, this.primitive, this.indexType, this.indexHandle, 0L, params, drawCount, instanceCount,
				firstInstance);
		}
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		if (drawCount <= 0 || !this.readyToDrawIndexed()) {
			return;
		}
		long counts = address(indexCounts);
		long baseVertices = address(vertexOffsets);
		if (this.fan) {
			for (int i = 0, position = firstIndexOffsets.position(); i < drawCount; i++) {
				this.drawIndexedFan(firstIndexOffsets.get(position + i), MemoryUtil.memGetInt(counts + 4L * i), 1,
					MemoryUtil.memGetInt(baseVertices + 4L * i), 0);
			}
		} else {
			Mtl.renderMultiDrawIndexedSeparate(this.encoderHandle, this.primitive, this.indexType, this.indexHandle, firstIndexOffsets.address(),
				counts, baseVertices, drawCount);
		}
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		if (drawCount <= 0 || !this.readyToDrawIndexed()) {
			return;
		}
		MetalBuffer buffer = (MetalBuffer) commands.buffer();
		buffer.lastUseSerial = this.serial;
		if (this.fan) {
			this.drawIndirectFans(buffer, commands.offset(), drawCount, true);
		} else {
			Mtl.renderDrawIndexedIndirect(this.encoderHandle, this.primitive, this.indexType, this.indexHandle, 0L, buffer.handle(),
				commands.offset(), drawCount, RenderPass.INDIRECT_INDEXED_DRAW_SIZE);
		}
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (vertexCount <= 0 || instanceCount <= 0 || !this.readyToDraw()) {
			return;
		}
		if (this.fan) {
			this.drawFan(vertexCount, instanceCount, firstVertex, firstInstance);
		} else {
			Mtl.renderDraw(this.encoderHandle, this.primitive, firstVertex, vertexCount, instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		if (drawCount <= 0 || instanceCount <= 0 || !this.readyToDraw()) {
			return;
		}
		long params = address(drawParameters);
		if (this.fan) {
			for (int i = 0; i < drawCount; i++, params += 8) {
				this.drawFan(MemoryUtil.memGetInt(params + 4), instanceCount, MemoryUtil.memGetInt(params), firstInstance);
			}
		} else {
			Mtl.renderMultiDraw(this.encoderHandle, this.primitive, params, drawCount, instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		if (drawCount <= 0 || !this.readyToDraw()) {
			return;
		}
		long firsts = address(firstVertices);
		long counts = address(vertexCounts);
		if (this.fan) {
			for (int i = 0; i < drawCount; i++) {
				this.drawFan(MemoryUtil.memGetInt(counts + 4L * i), 1, MemoryUtil.memGetInt(firsts + 4L * i), 0);
			}
		} else {
			Mtl.renderMultiDrawSeparate(this.encoderHandle, this.primitive, firsts, counts, drawCount);
		}
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		if (drawCount <= 0 || !this.readyToDraw()) {
			return;
		}
		MetalBuffer buffer = (MetalBuffer) commands.buffer();
		buffer.lastUseSerial = this.serial;
		if (this.fan) {
			this.drawIndirectFans(buffer, commands.offset(), drawCount, false);
		} else {
			Mtl.renderDrawIndirect(this.encoderHandle, this.primitive, buffer.handle(), commands.offset(), drawCount, RenderPass.INDIRECT_DRAW_SIZE);
		}
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		this.encoder.writeTimestamp(pool, index);
	}

	private boolean readyToDrawIndexed() {
		return this.indexHandle != 0L && this.readyToDraw();
	}

	/** Binds the dirty uniforms; false when the draw must be skipped (no usable PSO, or a uniform is missing). */
	private boolean readyToDraw() {
		if (this.pipelineState == 0L) {
			return false;
		}
		if (this.dirtyUniforms != 0L) {
			this.bindUniforms();
		}
		return this.missingUniforms == 0L;
	}

	private void bindUniforms() {
		MetalRenderPipeline pipeline = this.pipeline;
		long dirty = this.dirtyUniforms & this.uniformMask;
		long missing = this.missingUniforms & ~dirty;
		this.dirtyUniforms = 0L;
		while (dirty != 0L) {
			int index = Long.numberOfTrailingZeros(dirty);
			dirty &= dirty - 1;
			if (!this.bindUniform(pipeline, index)) {
				missing |= 1L << index;
			}
		}
		this.missingUniforms = missing;
		if (missing != 0L && WARNED.add(pipeline.name())) {
			Ingot.LOGGER.warn("Ingot: skipping draws: pipeline {} has no value for uniform {}", pipeline.name(), Long.numberOfTrailingZeros(missing));
		}
	}

	private boolean bindUniform(MetalRenderPipeline pipeline, int index) {
		int stages = pipeline.uniformStages(index);
		if (stages == 0) {
			return true;
		}
		Object value = UNIFORMS[index];
		switch (pipeline.uniformType(index)) {
			case UNIFORM_BUFFER -> {
				if (!(value instanceof GpuBufferSlice slice)) {
					return false;
				}
				MetalBuffer buffer = (MetalBuffer) slice.buffer();
				buffer.lastUseSerial = this.serial;
				this.bindBuffer(stages, buffer.handle(), slice.offset(), pipeline.uniformSlot(index));
			}
			case TEXEL_BUFFER -> {
				if (!(value instanceof GpuBufferSlice slice)) {
					return false;
				}
				MetalBuffer buffer = (MetalBuffer) slice.buffer();
				buffer.lastUseSerial = this.serial;
				long view = this.device.textureBufferView(buffer, slice.offset(), slice.length(), pipeline.texelBufferFormat(index));
				if (view == 0L) {
					return false;
				}
				this.bindTexture(stages, view, pipeline.uniformSlot(index));
			}
			case COMBINED_IMAGE_SAMPLER -> {
				if (!(value instanceof TextureViewAndSampler pair)) {
					return false;
				}
				this.bindTexture(stages, ((MetalTextureView) pair.view()).handle(), pipeline.uniformSlot(index));
				this.bindSampler(stages, ((MetalSampler) pair.sampler()).handle(), pipeline.uniformSamplerSlot(index));
			}
		}
		return true;
	}

	private void bindBuffer(int stages, long handle, long offset, int index) {
		int bind = 0;
		int moveOffset = 0;
		if ((stages & Mtl.STAGE_VERTEX) != 0) {
			if (VERTEX_BUFFERS[index] != handle) {
				bind = Mtl.STAGE_VERTEX;
			} else if (VERTEX_BUFFER_OFFSETS[index] != offset) {
				moveOffset = Mtl.STAGE_VERTEX;
			}
			VERTEX_BUFFERS[index] = handle;
			VERTEX_BUFFER_OFFSETS[index] = offset;
		}
		if ((stages & Mtl.STAGE_FRAGMENT) != 0) {
			if (FRAGMENT_BUFFERS[index] != handle) {
				bind |= Mtl.STAGE_FRAGMENT;
			} else if (FRAGMENT_BUFFER_OFFSETS[index] != offset) {
				moveOffset |= Mtl.STAGE_FRAGMENT;
			}
			FRAGMENT_BUFFERS[index] = handle;
			FRAGMENT_BUFFER_OFFSETS[index] = offset;
		}
		if (bind != 0) {
			Mtl.renderSetBuffer(this.encoderHandle, bind, handle, offset, index);
		}
		if (moveOffset != 0) {
			Mtl.renderSetBufferOffset(this.encoderHandle, moveOffset, offset, index);
		}
	}

	private void bindTexture(int stages, long handle, int index) {
		int bind = 0;
		if ((stages & Mtl.STAGE_VERTEX) != 0 && VERTEX_TEXTURES[index] != handle) {
			VERTEX_TEXTURES[index] = handle;
			bind = Mtl.STAGE_VERTEX;
		}
		if ((stages & Mtl.STAGE_FRAGMENT) != 0 && FRAGMENT_TEXTURES[index] != handle) {
			FRAGMENT_TEXTURES[index] = handle;
			bind |= Mtl.STAGE_FRAGMENT;
		}
		if (bind != 0) {
			Mtl.renderSetTexture(this.encoderHandle, bind, handle, index);
		}
	}

	private void bindSampler(int stages, long handle, int index) {
		int bind = 0;
		if ((stages & Mtl.STAGE_VERTEX) != 0 && VERTEX_SAMPLERS[index] != handle) {
			VERTEX_SAMPLERS[index] = handle;
			bind = Mtl.STAGE_VERTEX;
		}
		if ((stages & Mtl.STAGE_FRAGMENT) != 0 && FRAGMENT_SAMPLERS[index] != handle) {
			FRAGMENT_SAMPLERS[index] = handle;
			bind |= Mtl.STAGE_FRAGMENT;
		}
		if (bind != 0) {
			Mtl.renderSetSampler(this.encoderHandle, bind, handle, index);
		}
	}

	/** Non-indexed fan as triangles (first, first + k, first + k + 1) via the shared fan index list. */
	private void drawFan(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (vertexCount < 3 || instanceCount <= 0) {
			return;
		}
		Mtl.renderDrawIndexed(this.encoderHandle, MetalFormats.PRIMITIVE_TRIANGLE, (vertexCount - 2) * 3, MetalFormats.INDEX_UINT32,
			fanIndices(this.device, vertexCount), 0L, instanceCount, firstVertex, firstInstance);
	}

	/**
	 * Indexed fan: reads the fan's indices from the index buffer's CPU mapping and draws them as a triangle list from
	 * transient memory. The index data must have been written through a CPU mapping (as the shared sequential buffers
	 * and StagedVertexBuffer are), not by a GPU copy still pending in this frame.
	 */
	private void drawIndexedFan(long byteOffset, int indexCount, int instanceCount, int baseVertex, int firstInstance) {
		if (indexCount < 3 || instanceCount <= 0) {
			return;
		}
		MetalBuffer source = this.indexBuffer;
		long contents = source.contents();
		if (contents == 0L || byteOffset < 0L || byteOffset + (long) indexCount * this.indexSize > source.size()) {
			warnOnce(this.pipeline, "draws an indexed triangle fan from an index range the CPU cannot read");
			return;
		}
		int triangles = indexCount - 2;
		GpuBufferSlice.MappedView expanded = this.encoder.metalTransientMemory().allocateStaging(triangles * 12L, 4L, GpuBuffer.USAGE_INDEX);
		long in = contents + byteOffset;
		long out = MemoryUtil.memAddress(expanded.data());
		boolean shortIndices = this.indexSize == 2;
		int center = readIndex(in, 0, shortIndices);
		int previous = readIndex(in, 1, shortIndices);
		for (int k = 2; k < indexCount; k++, out += 12) {
			int next = readIndex(in, k, shortIndices);
			MemoryUtil.memPutInt(out, center);
			MemoryUtil.memPutInt(out + 4, previous);
			MemoryUtil.memPutInt(out + 8, next);
			previous = next;
		}
		expanded.close();
		GpuBufferSlice slice = expanded.slice();
		MetalBuffer target = (MetalBuffer) slice.buffer();
		target.lastUseSerial = this.serial;
		Mtl.renderDrawIndexed(this.encoderHandle, MetalFormats.PRIMITIVE_TRIANGLE, triangles * 3, MetalFormats.INDEX_UINT32, target.handle(),
			slice.offset(), instanceCount, baseVertex, firstInstance);
	}

	/** Indirect fans: the commands are read on the CPU (mapped buffers only) and drawn one by one. */
	private void drawIndirectFans(MetalBuffer commands, long offset, int drawCount, boolean indexed) {
		long contents = commands.contents();
		if (contents == 0L) {
			warnOnce(this.pipeline, "draws an indirect triangle fan from a buffer the CPU cannot read");
			return;
		}
		long at = contents + offset;
		if (indexed) {
			// {indexCount, instanceCount, firstIndex, vertexOffset, firstInstance}
			for (int i = 0; i < drawCount; i++, at += RenderPass.INDIRECT_INDEXED_DRAW_SIZE) {
				this.drawIndexedFan((long) MemoryUtil.memGetInt(at + 8) * this.indexSize, MemoryUtil.memGetInt(at), MemoryUtil.memGetInt(at + 4),
					MemoryUtil.memGetInt(at + 12), MemoryUtil.memGetInt(at + 16));
			}
		} else {
			// {vertexCount, instanceCount, firstVertex, firstInstance}
			for (int i = 0; i < drawCount; i++, at += RenderPass.INDIRECT_DRAW_SIZE) {
				this.drawFan(MemoryUtil.memGetInt(at), MemoryUtil.memGetInt(at + 4), MemoryUtil.memGetInt(at + 8), MemoryUtil.memGetInt(at + 12));
			}
		}
	}

	private static int readIndex(long base, int i, boolean shortIndices) {
		return shortIndices ? Short.toUnsignedInt(MemoryUtil.memGetShort(base + 2L * i)) : MemoryUtil.memGetInt(base + 4L * i);
	}

	/** The shared fan index buffer, grown (to a power of two) to cover fans of {@code vertexCount} vertices. */
	private static long fanIndices(MetalDevice device, int vertexCount) {
		if (vertexCount <= fanVertexCapacity && device == fanDevice) {
			return fanIndexBuffer;
		}
		int capacity = Math.max(FAN_MIN_VERTEX_CAPACITY, Integer.highestOneBit(vertexCount - 1) << 1);
		long bytes = (capacity - 2) * 12L;
		long data = MemoryUtil.nmemAlloc(bytes);
		long buffer;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int k = 1; k < capacity - 1; k++) {
				long at = data + (k - 1) * 12L;
				MemoryUtil.memPutInt(at, 0);
				MemoryUtil.memPutInt(at + 4, k);
				MemoryUtil.memPutInt(at + 8, k + 1);
			}
			buffer = Mtl.bufferCreate(device.handle(), bytes, MetalFormats.RESOURCE_STORAGE_SHARED, data,
				MemoryUtil.memAddress(stack.UTF8("Ingot triangle fan indices")));
		} finally {
			MemoryUtil.nmemFree(data);
		}
		if (buffer == 0L) {
			throw new IllegalStateException("Could not create the triangle fan index buffer: " + MemoryUtil.memUTF8(Mtl.lastError()));
		}
		if (device == fanDevice) {
			device.releaseLater(fanIndexBuffer);
		}
		fanDevice = device;
		fanIndexBuffer = buffer;
		fanVertexCapacity = capacity;
		return buffer;
	}

	private static long address(IntBuffer buffer) {
		if (!buffer.isDirect()) {
			throw new IllegalArgumentException("Multi-draw parameters must be in off-heap memory");
		}
		return MemoryUtil.memAddress(buffer);
	}

	private static void warnOnce(MetalRenderPipeline pipeline, String problem) {
		if (WARNED.add(pipeline.name())) {
			Ingot.LOGGER.warn("Ingot: skipping draws: pipeline {} {}", pipeline.name(), problem);
		}
	}
}
