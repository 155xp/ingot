package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.CompareOp;
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
import dev.ingot.Ingot;
import dev.ingot.IngotConfig;
import dev.ingot.nativ.Mtl;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The Metal GpuDeviceBackend. Owner: core.
 *
 * <p>Owns the native device, the serial bookkeeping every lifetime rule hangs off (see docs/ARCHITECTURE.md
 * "Submission model"), the deferred release queue, the cached depth-stencil states and the single command encoder,
 * which is created here because the frontend queries device info and creates query pools before it asks for it.
 */
public final class MetalDevice implements GpuDeviceBackend {
	/** How long a GPU wait blocks before it checks for command buffer errors and logs that it is still waiting. */
	private static final long STALL_REPORT_NS = 5_000_000_000L;
	// MTLCommandBufferError codes after which the device is unusable.
	private static final int ERROR_ACCESS_REVOKED = 4;
	private static final int ERROR_DEVICE_REMOVED = 11;
	private static final int LABEL_CAPACITY = 1024;
	/** At most 3 UTF-8 bytes per char, so this many chars plus the terminator always fit LABEL_CAPACITY. */
	private static final int MAX_LABEL_CHARS = 255;
	private static final int TEXEL_BUFFER_ALLOCATION_ALIGNMENT = 256;
	private static final int UNIFORM_ALLOCATION_ALIGNMENT = 16;
	private static final int CPU_ACCESS_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE;

	private final long handle;
	private final MetalCaps caps;
	private final boolean debugLabels;
	private final DeviceInfo deviceInfo;
	private final DeferredQueue deferred = new DeferredQueue();
	/** id&lt;MTLDepthStencilState&gt; per (MTLCompareFunction, depth write). */
	private final long[] depthStencilStates = new long[16];
	/** Managed buffers with open write maps (non-unified devices only). */
	private final ArrayList<MetalBuffer> managedWrites = new ArrayList<>();
	/** Render-thread scratch: a C string for labels, then 16 bytes for ingot_device_serial_times. */
	private final long scratch;
	private final ByteBuffer labelScratch;
	private final MetalCommandEncoder encoder;
	private volatile long currentSerial = 1L;
	private volatile long completedSerial;
	private boolean gpuErrorReported;
	private volatile boolean closed;

	/**
	 * Creates the native device. Every failure must surface as BackendCreationException (so Minecraft falls back).
	 * CONTRACT
	 */
	public MetalDevice(MetalBackend backend, GpuDebugOptions debugOptions) throws BackendCreationException {
		long device;
		try {
			this.debugLabels = IngotConfig.get().debugLabels || debugOptions.useLabels();
			device = Mtl.deviceCreate(this.debugLabels ? Mtl.DEVICE_FLAG_DEBUG_LABELS : 0);
		} catch (Throwable t) {
			throw new BackendCreationException("Could not create the Metal device: " + t, BackendCreationException.Reason.PLATFORM_ERROR);
		}
		if (device == 0L) {
			throw new BackendCreationException("No usable Metal device: " + lastError(), BackendCreationException.Reason.PLATFORM_ERROR);
		}
		this.handle = device;
		long scratch = 0L;
		try {
			this.caps = queryCaps(device);
			boolean mac2 = Mtl.deviceQuery(device, Mtl.QUERY_MAC2_FAMILY) != 0L;
			if (this.caps.appleFamily() == 0 && !mac2) {
				throw new BackendCreationException(this.caps.name() + " supports neither an Apple GPU family nor MTLGPUFamilyMac2",
					BackendCreationException.Reason.PLATFORM_ERROR);
			}
			this.deviceInfo = createDeviceInfo(this.caps, mac2);
			scratch = MemoryUtil.nmemAllocChecked(LABEL_CAPACITY + 16);
			this.scratch = scratch;
			this.labelScratch = MemoryUtil.memByteBuffer(scratch, LABEL_CAPACITY);
			this.encoder = new MetalCommandEncoder(this);
		} catch (Throwable t) {
			MemoryUtil.nmemFree(scratch);
			Mtl.deviceDestroy(device);
			if (t instanceof BackendCreationException e) {
				throw e;
			}
			throw new BackendCreationException("Could not initialize the Metal device: " + t, BackendCreationException.Reason.PLATFORM_ERROR);
		}
	}

	private static MetalCaps queryCaps(long device) {
		int appleFamily = (int) Mtl.deviceQuery(device, Mtl.QUERY_APPLE_FAMILY);
		// Apple GPUs bind constant buffers at any 4-byte offset; 16 keeps every padded std140 block at least as large as
		// its MSL struct. Mac2-only GPUs need the 256 bytes the device reports.
		int uniformAlignment = appleFamily > 0 ? 16 : (int) Math.max(256L, Mtl.deviceQuery(device, Mtl.QUERY_BUFFER_OFFSET_ALIGNMENT));
		return new MetalCaps(
			MemoryUtil.memUTF8(Mtl.deviceName(device)),
			Mtl.deviceQuery(device, Mtl.QUERY_UNIFIED_MEMORY) != 0L,
			appleFamily,
			Mtl.deviceQuery(device, Mtl.QUERY_MAX_BUFFER_LENGTH),
			uniformAlignment,
			(int) Mtl.deviceQuery(device, Mtl.QUERY_MAX_TEXTURE_SIZE),
			Mtl.deviceQuery(device, Mtl.QUERY_LOW_POWER) != 0L
		);
	}

	/** The values documented in docs/ARCHITECTURE.md "Device info reported". */
	private static DeviceInfo createDeviceInfo(MetalCaps caps, boolean mac2) {
		boolean wireframeFillMode = true;
		boolean shaderDrawParameters = true;
		boolean multiDrawDirectInterleaved = true;
		boolean multiDrawDirectSeparate = true;
		boolean multiDrawIndirect = true;
		boolean drawIndirect = true;
		boolean nonZeroFirstInstance = true;
		boolean persistentMapping = true;
		DeviceFeatures features = new DeviceFeatures(wireframeFillMode, shaderDrawParameters, multiDrawDirectInterleaved, multiDrawDirectSeparate,
			multiDrawIndirect, drawIndirect, nonZeroFirstInstance, persistentMapping);

		int maxAnisotropy = 16;
		int maxMultiDrawDirectInterleavedDrawCount = 1 << 20;
		int maxDrawIndirectDrawCount = Integer.MAX_VALUE;
		DeviceLimits limits = new DeviceLimits(maxAnisotropy, caps.uniformOffsetAlignment(), caps.maxTextureSize(), caps.maxBufferLength(),
			maxMultiDrawDirectInterleavedDrawCount, Mtl.MAX_COLOR_TARGETS, maxDrawIndirectDrawCount);

		// writeToBufferIsSlow selects the persistently mapped chunk staging ring, ideal for unified memory.
		boolean writeToBufferIsSlow = true;
		boolean anisotropyHasKnownIssues = false;
		// The vanilla rule (Util.isAppleSiliconMac): true on every Apple GPU.
		boolean isExplicitDepthRequired = caps.name().startsWith("Apple");
		boolean multiDrawIndirectHasKnownIssues = false;
		HintsAndWorkarounds hints = new HintsAndWorkarounds(writeToBufferIsSlow, anisotropyHasKnownIssues, isExplicitDepthRequired,
			multiDrawIndirectHasKnownIssues);

		String family = caps.appleFamily() > 0 ? "MTLGPUFamilyApple" + caps.appleFamily() : "MTLGPUFamilyMac2";
		List<String> extensions = new ArrayList<>(3);
		extensions.add(family);
		if (caps.appleFamily() > 0 && mac2) {
			extensions.add("MTLGPUFamilyMac2");
		}
		if (caps.unifiedMemory()) {
			extensions.add("unifiedMemory");
		}
		DeviceType type = caps.unifiedMemory() || caps.lowPower() ? DeviceType.INTEGRATED : DeviceType.DISCRETE;
		return new DeviceInfo(caps.name(), vendorName(caps.name()), "Metal (" + family + ") on macOS " + System.getProperty("os.version"), true,
			"Metal", 1.0F, limits, features, Set.copyOf(extensions), hints, type);
	}

	private static String vendorName(String deviceName) {
		String name = deviceName.toLowerCase(Locale.ROOT);
		if (name.startsWith("apple")) {
			return "Apple";
		} else if (name.contains("amd") || name.contains("radeon")) {
			return "AMD";
		} else if (name.contains("intel")) {
			return "Intel";
		} else if (name.contains("nvidia") || name.contains("geforce")) {
			return "NVIDIA";
		}
		return "Unknown";
	}

	/** ingot_last_error() of this thread. */
	static String lastError() {
		return MemoryUtil.memUTF8(Mtl.lastError());
	}

	/** IngotDevice* handle. CONTRACT */
	public long handle() {
		return this.handle;
	}

	/** CONTRACT */
	public MetalCaps caps() {
		return this.caps;
	}

	/** True when debug labels/groups should be sent to Metal (config debugLabels or GpuDebugOptions.useLabels). CONTRACT */
	public boolean debugLabels() {
		return this.debugLabels;
	}

	/** The single command encoder (created by createCommandEncoder, which the frontend calls once). CONTRACT */
	public MetalCommandEncoder encoder() {
		return this.encoder;
	}

	/** Serial of the command buffer currently being recorded. CONTRACT */
	public long currentSerial() {
		return this.currentSerial;
	}

	/** Highest serial known to have completed on the GPU. Cheap (cached, refreshed from native). CONTRACT */
	public long completedSerial() {
		long completed = this.completedSerial;
		// Only ask native while something committed is still outstanding.
		if (completed < this.currentSerial - 1L) {
			completed = Mtl.deviceCompletedSerial(this.handle);
			this.completedSerial = completed;
		}
		return completed;
	}

	/** Releases a native handle (ingot_release) once the GPU has finished the current serial. Any thread. CONTRACT */
	public void releaseLater(long nativeHandle) {
		if (nativeHandle == 0L) {
			return;
		}
		if (this.closed) {
			// The GPU is idle for good.
			Mtl.release(nativeHandle);
		} else {
			this.deferred.add(this.currentSerial, nativeHandle, null);
		}
	}

	/** Runs the task on the render thread inside the first submit() after the current serial completes. CONTRACT */
	public void runLater(Runnable task) {
		if (!this.closed) {
			this.deferred.add(this.currentSerial, 0L, task);
		}
	}

	/**
	 * Cached id&lt;MTLDepthStencilState&gt;. A null state means "no depth test, no depth write" (compare always),
	 * which is what pipelines without depth state need when the pass has a depth attachment. Render thread. CONTRACT
	 */
	public long depthStencilState(@Nullable DepthStencilState state) {
		int compare = MetalFormats.compareFunction(state != null ? state.depthTest() : CompareOp.ALWAYS_PASS);
		boolean write = state != null && state.writeDepth();
		int key = compare * 2 + (write ? 1 : 0);
		long depthStencil = this.depthStencilStates[key];
		if (depthStencil == 0L) {
			depthStencil = Mtl.depthStencilCreate(this.handle, compare, write ? 1 : 0);
			if (depthStencil == 0L) {
				throw new IllegalStateException("Could not create a depth-stencil state: " + lastError());
			}
			this.depthStencilStates[key] = depthStencil;
		}
		return depthStencil;
	}

	/**
	 * A MTLTextureTypeTextureBuffer view of [offset, offset + length) of the buffer, for TEXEL_BUFFER uniforms.
	 * Cached; released automatically after the GPU is done with it. Render thread. CONTRACT
	 */
	public long textureBufferView(MetalBuffer buffer, long offset, long length, GpuFormat format) {
		return buffer.textureBufferView(offset, length, format);
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		return new MetalSurface(this, windowHandle, isIconified);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return this.encoder;
	}

	@Override
	public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter,
									int maxAnisotropy, OptionalDouble maxLod) {
		// Mirrors VulkanGpuSampler: a max LOD of 0 becomes nearest-mip sampling clamped to LOD 0.25, which keeps the
		// min/mag filter choice but never leaves mip 0.
		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > 0.25 ? MetalFormats.MIP_LINEAR : MetalFormats.MIP_NEAREST;
		long sampler = Mtl.samplerCreate(this.handle, MetalFormats.addressMode(addressModeU), MetalFormats.addressMode(addressModeV),
			MetalFormats.minMagFilter(minFilter), MetalFormats.minMagFilter(magFilter), mipFilter, maxAnisotropy, 0.0F, (float) Math.max(0.25, lod), 0L);
		if (sampler == 0L) {
			throw new IllegalStateException("Could not create a Metal sampler: " + lastError());
		}
		return new MetalSampler(this, sampler, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		int pixelFormat = MetalFormats.pixelFormat(format);
		if (pixelFormat == MetalFormats.PIXEL_INVALID) {
			throw new IllegalArgumentException("Metal has no texture format for " + format + " (3-component formats only exist as vertex formats)");
		}
		boolean cube = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		int metalUsage = 0;
		// COPY_SRC color textures are also sampled: the surface draws the presented texture into the drawable.
		if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0 && format.hasColorAspect()) {
			metalUsage |= MetalFormats.USAGE_SHADER_READ;
		}
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
			metalUsage |= MetalFormats.USAGE_RENDER_TARGET;
		}
		// Only set when a native view can exist (it costs lossless compression); full-range views reuse the texture.
		if (cube || mipLevels > 1) {
			metalUsage |= MetalFormats.USAGE_PIXEL_FORMAT_VIEW;
		}
		long texture = Mtl.textureCreate(this.handle, cube ? MetalFormats.TEXTURE_CUBE : MetalFormats.TEXTURE_2D, pixelFormat, width, height, mipLevels,
			metalUsage, MetalFormats.STORAGE_PRIVATE, this.cLabel(label));
		if (texture == 0L) {
			throw new GpuOutOfMemoryException("Could not create the " + width + "x" + height + " " + format + " texture " + label + ": " + lastError());
		}
		return new MetalTexture(this, texture, pixelFormat, cube, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		MetalTexture metalTexture = (MetalTexture) texture;
		long view = 0L;
		if (baseMipLevel != 0 || mipLevels != metalTexture.getMipLevels()) {
			boolean cube = metalTexture.isCube();
			view = Mtl.textureViewCreate(metalTexture.handle(), metalTexture.pixelFormat(), cube ? MetalFormats.TEXTURE_CUBE : MetalFormats.TEXTURE_2D,
				baseMipLevel, mipLevels, 0, cube ? 6 : 1);
			if (view == 0L) {
				throw new IllegalStateException("Could not create a view of texture " + texture.getLabel() + ": " + lastError());
			}
		}
		metalTexture.retain();
		return new MetalTextureView(metalTexture, baseMipLevel, mipLevels, view);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
		int storage;
		if (this.caps.unifiedMemory()) {
			storage = MetalFormats.RESOURCE_STORAGE_SHARED;
		} else {
			storage = (usage & CPU_ACCESS_USAGE) != 0 ? MetalFormats.RESOURCE_STORAGE_MANAGED : MetalFormats.RESOURCE_STORAGE_PRIVATE;
		}
		return this.newBuffer(label, usage, size, storage, 0L);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
		long size = data.remaining();
		// Non-unified: Managed, so the initial contents need no blit (Metal uploads them to the GPU copy itself).
		int storage = this.caps.unifiedMemory() ? MetalFormats.RESOURCE_STORAGE_SHARED : MetalFormats.RESOURCE_STORAGE_MANAGED;
		// The buffer is new and unused, so its storage can be initialized directly: by newBufferWithBytes when the
		// data covers the whole allocation, otherwise by a copy into the mapping.
		boolean initializeNatively = data.isDirect() && allocationLength(usage, size) == size;
		MetalBuffer buffer = this.newBuffer(label, usage, size, storage, initializeNatively ? MemoryUtil.memAddress(data) : 0L);
		if (!initializeNatively) {
			MetalTransientMemory.copy(data, buffer.contents(), size);
			if (buffer.managed()) {
				Mtl.bufferDidModifyRange(buffer.handle(), 0L, size);
			}
		}
		return buffer;
	}

	private MetalBuffer newBuffer(@Nullable Supplier<String> label, int usage, long size, int resourceOptions, long initialData) {
		long buffer = Mtl.bufferCreate(this.handle, allocationLength(usage, size), resourceOptions, initialData, this.cLabel(label));
		if (buffer == 0L) {
			throw new GpuOutOfMemoryException("Could not allocate a " + size + " byte Metal buffer: " + lastError());
		}
		return new MetalBuffer(this, buffer, Mtl.bufferContents(buffer), usage, size, resourceOptions == MetalFormats.RESOURCE_STORAGE_MANAGED);
	}

	/**
	 * Native length of a buffer. Uniform buffers are rounded up to 16 bytes because MSL structs are larger than several
	 * std140 blocks the game allocates (Fog 40 → 48); texel buffers to 256 bytes because a texture-buffer view's row
	 * is padded to the linear texture alignment.
	 */
	private static long allocationLength(int usage, long size) {
		if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
			return Mth.roundToward(size, TEXEL_BUFFER_ALLOCATION_ALIGNMENT);
		}
		if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
			return Mth.roundToward(size, UNIFORM_ALLOCATION_ALIGNMENT);
		}
		return size;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return this.debugLabels;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
		return MetalPipelineCompiler.compile(this, pipelineCreateInfo);
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.encoder.shutdown();
		for (long depthStencil : this.depthStencilStates) {
			if (depthStencil != 0L) {
				Mtl.release(depthStencil);
			}
		}
		this.closed = true;
		MemoryUtil.nmemFree(this.scratch);
		Mtl.deviceDestroy(this.handle);
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new MetalQueryPool(this, size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		// Timestamps already are System.nanoTime() values.
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}

	/** Called by the encoder right after committing the current serial. */
	void advanceSerial() {
		this.currentSerial++;
	}

	/** Blocks (in the kernel) until {@code serial} completed, reporting GPU errors and long stalls while waiting. */
	void awaitSerial(long serial) {
		while (this.completedSerial() < serial) {
			if (Mtl.deviceWaitSerial(this.handle, serial, STALL_REPORT_NS) == 0) {
				this.checkForGpuErrors();
				Ingot.LOGGER.warn("Ingot: the GPU has not finished command buffer {} after {} s, still waiting", serial, STALL_REPORT_NS / 1_000_000_000L);
			}
		}
	}

	/** Waits up to {@code timeoutNs} (negative = forever) for a committed serial; returns whether it completed. */
	boolean waitSerial(long serial, long timeoutNs) {
		if (timeoutNs < 0L) {
			this.awaitSerial(serial);
			return true;
		}
		return Mtl.deviceWaitSerial(this.handle, serial, timeoutNs) != 0;
	}

	/** Releases the handles and runs the tasks queued up to {@code completedSerial}. Render thread. */
	void drainDeferred(long completedSerial) {
		this.deferred.drain(completedSerial);
	}

	/**
	 * Logs the first failed command buffer once. Throws GpuDeviceLossException when the failure means the GPU is
	 * gone (removed, or access revoked after repeated faults), like the Vulkan backend on VK_ERROR_DEVICE_LOST.
	 */
	void checkForGpuErrors() {
		if (this.gpuErrorReported) {
			return;
		}
		int code = Mtl.deviceErrorCode(this.handle);
		if (code == 0) {
			return;
		}
		// The native error is the first failure and never changes, so there is nothing more to learn afterwards.
		this.gpuErrorReported = true;
		String message = MemoryUtil.memUTF8(Mtl.deviceErrorMessage(this.handle));
		if (code == ERROR_DEVICE_REMOVED || code == ERROR_ACCESS_REVOKED) {
			throw new GpuDeviceLossException("Metal device lost (MTLCommandBufferError " + code + "): " + message);
		}
		Ingot.LOGGER.error("Ingot: a Metal command buffer failed (MTLCommandBufferError {}): {}", code, message);
	}

	/** GPU start (or end) of a completed serial in System.nanoTime() nanoseconds, or -1 if the device no longer knows it. */
	long serialTime(long serial, boolean start) {
		long times = this.scratch + LABEL_CAPACITY;
		if (Mtl.deviceSerialTimes(this.handle, serial, times) == 0) {
			return -1L;
		}
		return MemoryUtil.memGetLong(start ? times : times + 8L);
	}

	void trackManagedWrites(MetalBuffer buffer) {
		this.managedWrites.add(buffer);
	}

	/** Before every commit: reports Managed ranges still mapped for writing, so the commit sees the CPU writes. */
	void flushManagedWrites() {
		for (int i = this.managedWrites.size() - 1; i >= 0; i--) {
			if (!this.managedWrites.get(i).flushManagedWrites()) {
				this.managedWrites.remove(i);
			}
		}
	}

	/** {@code text} as a C string in the render-thread label scratch, or 0 when labels are off. Valid until the next call. */
	long cLabel(@Nullable String text) {
		if (!this.debugLabels || text == null) {
			return 0L;
		}
		MemoryUtil.memUTF8(text.length() > MAX_LABEL_CHARS ? text.substring(0, MAX_LABEL_CHARS) : text, true, this.labelScratch, 0);
		return this.scratch;
	}

	/** Like {@link #cLabel(String)}, evaluating the supplier only when labels are on. */
	long cLabel(@Nullable Supplier<String> label) {
		return this.debugLabels && label != null ? this.cLabel(label.get()) : 0L;
	}
}
