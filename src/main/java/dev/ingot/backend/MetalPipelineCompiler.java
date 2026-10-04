package dev.ingot.backend;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;

/**
 * GpuDeviceBackend.compilePipeline for Metal. Runs on worker threads, concurrently: SPIR-V → MSL, per-stage
 * MTLLibrary (cached by MSL hash), default PSO. Must finish all SPIR-V access before returning (the frontend frees
 * the modules after finishCompile). Failures log and yield Pending.NULL. Owner: pipelines.
 */
public final class MetalPipelineCompiler {
	private MetalPipelineCompiler() {
	}

	/** CONTRACT */
	public static BackendRenderPipeline.Pending compile(MetalDevice device, BackendRenderPipeline.CreateInfo createInfo) {
		throw new UnsupportedOperationException("not implemented");
	}
}
