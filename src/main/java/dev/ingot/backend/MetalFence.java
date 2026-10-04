package dev.ingot.backend;

import com.mojang.renderpearl.api.commands.GpuFence;

/** Completes when the command buffer serial captured at creation completes. Owner: core. */
public class MetalFence implements GpuFence {
	@Override
	public void close() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public boolean awaitCompletion(long timeoutNS) {
		throw new UnsupportedOperationException("not implemented");
	}
}
