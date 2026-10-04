package dev.ingot.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

/**
 * Timestamp queries. Apple GPUs only sample at encoder boundaries, so a timestamp resolves to the GPU start time of
 * its command buffer (if written before any work in it) or the GPU end time otherwise. Owner: core.
 */
public class MetalQueryPool implements GpuQueryPool {
	@Override
	public int size() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public OptionalLong getValue(int index) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException("not implemented");
	}
}
