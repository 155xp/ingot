package dev.ingot.backend;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;

/**
 * An MTLBuffer. Owner: core. Shared storage on unified-memory devices, so {@link #contents()} is a persistent CPU
 * mapping and {@link #map} is pointer arithmetic (no downcall, native byte order, position 0).
 */
public class MetalBuffer extends BaseGpuBuffer {
	/** Serial of the most recent command buffer that referenced this buffer. Written by every user. CONTRACT */
	public long lastUseSerial;

	protected MetalBuffer(int usage, long size) {
		super(usage, size);
	}

	/** id&lt;MTLBuffer&gt; handle. CONTRACT */
	public long handle() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CPU address of the buffer storage, or 0 for GPU-private buffers. CONTRACT */
	public long contents() {
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

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		throw new UnsupportedOperationException("not implemented");
	}
}
