package dev.ingot.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * Everything recorded inside one render encoder: cached state, lazy uniform binding, draws, multi-draw loops,
 * triangle-fan conversion. The render encoder itself is begun and ended by MetalCommandEncoder. Owner: passes.
 */
public final class MetalRenderPass implements RenderPassBackend {
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
	}

	/** Called by MetalCommandEncoder.submitRenderPass before it ends the encoder. CONTRACT */
	public void finish() {
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void popDebugGroup() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void pushConstants(ByteBuffer value) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void disableScissor() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		throw new UnsupportedOperationException("not implemented");
	}
}
