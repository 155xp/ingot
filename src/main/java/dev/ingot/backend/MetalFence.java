package dev.ingot.backend;

import com.mojang.renderpearl.api.commands.GpuFence;

/**
 * Completes when the command buffer serial captured at creation completes. Owner: core. Holds no native object:
 * waiting goes through the device's completed serial and its kernel wait, so fences are cheap to create.
 */
public class MetalFence implements GpuFence {
	private final MetalCommandEncoder encoder;
	private final long serial;
	private boolean completed;

	MetalFence(MetalCommandEncoder encoder, long serial) {
		this.encoder = encoder;
		this.serial = serial;
	}

	@Override
	public void close() {
		this.completed = true;
	}

	@Override
	public boolean awaitCompletion(long timeoutNS) {
		if (!this.completed) {
			this.completed = this.encoder.awaitSerial(this.serial, timeoutNS);
		}
		return this.completed;
	}
}
