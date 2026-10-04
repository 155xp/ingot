package dev.ingot.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import org.joml.Vector4fc;

/**
 * The single command encoder. Owns the frame's command buffer, the lazily opened blit encoder, render pass
 * begin/end (filling IngotRenderPassDesc, folding pending clears into load actions), submission, fences, readback
 * callbacks and transient memory. Owner: core.
 */
public final class MetalCommandEncoder implements CommandEncoderBackend {
	/** Current command buffer handle, created lazily. Does not end any encoder. CONTRACT */
	public long commandBuffer() {
		throw new UnsupportedOperationException("not implemented");
	}

	/**
	 * Flushes pending clears and ends the blit encoder, then returns the current command buffer so the caller can
	 * encode its own pass on it (used by MetalSurface for the present blit). CONTRACT
	 */
	public long commandBufferForExternalPass() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Typed access to the transient allocator. CONTRACT */
	public MetalTransientMemory metalTransientMemory() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void submit() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public TransientMemory transientMemory() {
		return this.metalTransientMemory();
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void submitRenderPass() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
										   int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
									int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y,
									int width, int height) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY,
									 int width, int height) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuFence createFence() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		throw new UnsupportedOperationException("not implemented");
	}
}
