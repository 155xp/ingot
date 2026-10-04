package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The Metal GpuDeviceBackend. Owner: core. */
public final class MetalDevice implements GpuDeviceBackend {
	/**
	 * Creates the native device. Every failure must surface as BackendCreationException (so Minecraft falls back).
	 * CONTRACT
	 */
	public MetalDevice(MetalBackend backend, GpuDebugOptions debugOptions) throws BackendCreationException {
		throw new BackendCreationException("not implemented", BackendCreationException.Reason.OTHER);
	}

	/** IngotDevice* handle. CONTRACT */
	public long handle() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CONTRACT */
	public MetalCaps caps() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** True when debug labels/groups should be sent to Metal (config debugLabels or GpuDebugOptions.useLabels). CONTRACT */
	public boolean debugLabels() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** The single command encoder (created by createCommandEncoder, which the frontend calls once). CONTRACT */
	public MetalCommandEncoder encoder() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Serial of the command buffer currently being recorded. CONTRACT */
	public long currentSerial() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Highest serial known to have completed on the GPU. Cheap (cached, refreshed from native). CONTRACT */
	public long completedSerial() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Releases a native handle (ingot_release) once the GPU has finished the current serial. Any thread. CONTRACT */
	public void releaseLater(long nativeHandle) {
		throw new UnsupportedOperationException("not implemented");
	}

	/** Runs the task on the render thread inside the first submit() after the current serial completes. CONTRACT */
	public void runLater(Runnable task) {
		throw new UnsupportedOperationException("not implemented");
	}

	/**
	 * Cached id&lt;MTLDepthStencilState&gt;. A null state means "no depth test, no depth write" (compare always),
	 * which is what pipelines without depth state need when the pass has a depth attachment. Render thread. CONTRACT
	 */
	public long depthStencilState(@Nullable DepthStencilState state) {
		throw new UnsupportedOperationException("not implemented");
	}

	/**
	 * A MTLTextureTypeTextureBuffer view of [offset, offset + length) of the buffer, for TEXEL_BUFFER uniforms.
	 * Cached; released automatically after the GPU is done with it. Render thread. CONTRACT
	 */
	public long textureBufferView(MetalBuffer buffer, long offset, long length, GpuFormat format) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
									int maxAnisotropy, OptionalDouble maxLod) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
		return MetalPipelineCompiler.compile(this, pipelineCreateInfo);
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		throw new UnsupportedOperationException("not implemented");
	}
}
