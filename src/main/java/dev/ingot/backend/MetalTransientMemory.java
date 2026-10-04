package dev.ingot.backend;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Per-submit scratch memory: a pool of large Shared MTLBuffer blocks (WriteCombined for staging), recycled once the
 * serial that last used a block completes. Semantics follow docs/research/frontend-contracts.md section 10.
 * Owner: core.
 */
public final class MetalTransientMemory implements TransientMemory {
	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
		throw new UnsupportedOperationException("not implemented");
	}
}
