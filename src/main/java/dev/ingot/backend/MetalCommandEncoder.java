package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import dev.ingot.nativ.Mtl;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The single command encoder. Owns the frame's command buffer, the lazily opened blit encoder, render pass
 * begin/end (filling IngotRenderPassDesc, folding pending clears into load actions), submission, fences, readback
 * callbacks and transient memory. Owner: core.
 *
 * <p>Command buffers are numbered by serials (see {@link MetalDevice#currentSerial()}). The current one is created on
 * first use and committed by {@link #submit()} (once per frame) or by a fence wait that needs it on the GPU. Copies
 * share one blit encoder until anything else needs the command buffer. Whole-texture clears are recorded on the
 * texture and encoded only when something else touches it, so the common "clear, then render into it" becomes a
 * load-action clear of that pass instead of a separate full-texture store and reload.
 */
public final class MetalCommandEncoder implements CommandEncoderBackend {
	// IngotRenderPassDesc (native/include/ingot.h), 376 bytes.
	private static final int PASS_DESC_SIZE = 376;
	private static final int COLOR_STRIDE = 40;
	private static final int COLOR_TEXTURE = 0;
	private static final int COLOR_LEVEL = 8;
	private static final int COLOR_LOAD = 16;
	private static final int COLOR_STORE = 20;
	private static final int COLOR_CLEAR = 24;
	private static final int DEPTH_TEXTURE = 320;
	private static final int DEPTH_LEVEL = 328;
	private static final int DEPTH_LOAD = 332;
	private static final int DEPTH_STORE = 336;
	private static final int CLEAR_DEPTH = 340;
	private static final int STENCIL_TEXTURE = 344;
	private static final int STENCIL_LOAD = 352;
	private static final int STENCIL_STORE = 356;
	private static final int LABEL = 368;
	// ingot_util_clear_region flags.
	private static final int CLEAR_REGION_COLOR = 1;
	private static final int CLEAR_REGION_DEPTH = 2;
	/** Staging offsets: Metal wants 4-byte aligned buffer copies and pixel-size aligned buffer-to-texture copies. */
	private static final long BUFFER_STAGING_ALIGNMENT = 4L;
	private static final long TEXTURE_STAGING_ALIGNMENT = 16L;

	private final MetalDevice device;
	private final long deviceHandle;
	private final MetalTransientMemory transientMemory;
	/** Reusable off-heap IngotRenderPassDesc. */
	private final long passDesc;
	private long commandBuffer;
	private long blitEncoder;
	private long renderEncoder;
	private @Nullable MetalRenderPass renderPass;
	/** Textures with a recorded but not yet encoded whole-texture clear. */
	private MetalTexture[] pendingClears = new MetalTexture[8];
	private int pendingClearCount;
	private MetalTexture[] clearScratch = new MetalTexture[8];
	/** Serial committed by the previous submit(): the next submit waits for it (2 frames in flight). */
	private long previousFrameSerial;

	MetalCommandEncoder(MetalDevice device) {
		this.device = device;
		this.deviceHandle = device.handle();
		this.transientMemory = new MetalTransientMemory(device);
		this.passDesc = MemoryUtil.nmemAllocChecked(PASS_DESC_SIZE);
	}

	/** Current command buffer handle, created lazily. Does not end any encoder. CONTRACT */
	public long commandBuffer() {
		if (this.commandBuffer == 0L) {
			long label = this.device.debugLabels() ? this.device.cLabel("Ingot command buffer " + this.device.currentSerial()) : 0L;
			this.commandBuffer = Mtl.cmdCreate(this.deviceHandle, label);
			if (this.commandBuffer == 0L) {
				throw new IllegalStateException("Could not create a Metal command buffer: " + MetalDevice.lastError());
			}
		}
		return this.commandBuffer;
	}

	/**
	 * Flushes pending clears and ends the blit encoder, then returns the current command buffer so the caller can
	 * encode its own pass on it (used by MetalSurface for the present blit). CONTRACT
	 */
	public long commandBufferForExternalPass() {
		this.flushPendingClears(null);
		this.endBlit();
		return this.commandBuffer();
	}

	/** Typed access to the transient allocator. CONTRACT */
	public MetalTransientMemory metalTransientMemory() {
		return this.transientMemory;
	}

	@Override
	public void submit() {
		if (this.renderPass != null) {
			throw new IllegalStateException("Cannot submit while a render pass is open");
		}
		this.flushPendingClears(null);
		this.endBlit();
		long serial = this.device.currentSerial();
		this.commit();
		long now = System.nanoTime();
		this.transientMemory.endFrame(serial, now);
		// Two frames in flight: the CPU records frame N+1 while the GPU runs frame N, never N-1.
		long previous = this.previousFrameSerial;
		this.previousFrameSerial = serial;
		this.device.awaitSerial(previous);
		long completed = this.device.completedSerial();
		this.device.drainDeferred(completed);
		this.transientMemory.recycle(completed, now);
		this.device.checkForGpuErrors();
	}

	@Override
	public TransientMemory transientMemory() {
		return this.metalTransientMemory();
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		int colorCount = Math.min(colors.size(), Mtl.MAX_COLOR_TARGETS);

		// The viewport is the size of the last color attachment, or of the depth attachment (like Vulkan).
		int width = 0;
		int height = 0;
		boolean sized = false;
		for (int i = 0; i < colorCount; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			if (attachment != null) {
				width = attachment.textureView().getWidth(0);
				height = attachment.textureView().getHeight(0);
				sized = true;
			}
		}
		if (!sized && depth != null) {
			width = depth.textureView().getWidth(0);
			height = depth.textureView().getHeight(0);
		}
		RenderPass.RenderArea area = descriptor.renderArea();
		// Load-action clears cover whole attachments, so they are only usable when the render area does too.
		boolean fullArea = area.x() == 0 && area.y() == 0 && area.width() == width && area.height() == height;

		this.endBlit();
		long buffer = this.commandBuffer();
		this.flushPendingClears(fullArea ? descriptor : null);
		if (!fullArea) {
			this.clearRenderArea(buffer, colors, colorCount, depth, area);
		}

		long desc = this.passDesc;
		MemoryUtil.memSet(desc, 0, PASS_DESC_SIZE);
		int colorAttachmentMask = 0;
		for (int i = 0; i < colorCount; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			if (attachment == null) {
				continue;
			}
			MetalTextureView view = (MetalTextureView) attachment.textureView();
			MetalTexture texture = view.metalTexture();
			long slot = desc + (long) i * COLOR_STRIDE;
			Optional<Vector4fc> clear = attachment.clearValue();
			if (fullArea && clear.isPresent()) {
				Vector4fc color = clear.get();
				putColorAttachment(slot, texture, view.baseMipLevel(), MetalFormats.LOAD_CLEAR, color.x(), color.y(), color.z(), color.w());
			} else if (texture.clearPending) {
				putColorAttachment(slot, texture, view.baseMipLevel(), MetalFormats.LOAD_CLEAR, texture.clearRed, texture.clearGreen, texture.clearBlue,
					texture.clearAlpha);
			} else {
				putColorAttachment(slot, texture, view.baseMipLevel(), MetalFormats.LOAD_LOAD, 0.0F, 0.0F, 0.0F, 0.0F);
			}
			colorAttachmentMask |= 1 << i;
		}
		int depthPixelFormat = 0;
		int stencilPixelFormat = 0;
		if (depth != null) {
			MetalTextureView view = (MetalTextureView) depth.textureView();
			MetalTexture texture = view.metalTexture();
			OptionalDouble clear = depth.clearValue();
			if (fullArea && clear.isPresent()) {
				putDepthStencilAttachment(desc, texture, view.baseMipLevel(), MetalFormats.LOAD_CLEAR, (float) clear.getAsDouble());
			} else if (texture.clearPending) {
				putDepthStencilAttachment(desc, texture, view.baseMipLevel(), MetalFormats.LOAD_CLEAR, texture.clearDepth);
			} else {
				putDepthStencilAttachment(desc, texture, view.baseMipLevel(), MetalFormats.LOAD_LOAD, 0.0F);
			}
			GpuFormat format = texture.getFormat();
			depthPixelFormat = format.hasDepthAspect() ? texture.pixelFormat() : 0;
			stencilPixelFormat = format.hasStencilAspect() ? texture.pixelFormat() : 0;
		}
		MemoryUtil.memPutLong(desc + LABEL, this.device.cLabel(descriptor.label()));

		long encoderHandle = Mtl.renderBegin(buffer, desc);
		if (encoderHandle == 0L) {
			throw new IllegalStateException("Could not begin a Metal render pass: " + MetalDevice.lastError());
		}
		// Everything still pending was an attachment of this pass and has just been folded into its load actions.
		for (int i = 0; i < this.pendingClearCount; i++) {
			this.pendingClears[i].clearPending = false;
			this.pendingClears[i] = null;
		}
		this.pendingClearCount = 0;

		this.renderEncoder = encoderHandle;
		MetalRenderPass pass = new MetalRenderPass(this.device, this, encoderHandle, descriptor, depthPixelFormat, stencilPixelFormat,
			colorAttachmentMask, width, height);
		this.renderPass = pass;
		return pass;
	}

	/** Clears requested for a render area smaller than the attachments: scissored quads before the pass loads them. */
	private void clearRenderArea(long buffer, List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors, int colorCount,
								 RenderPassDescriptor.@Nullable Attachment<OptionalDouble> depth, RenderPass.RenderArea area) {
		for (int i = 0; i < colorCount; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			if (attachment != null && attachment.clearValue().isPresent()) {
				MetalTextureView view = (MetalTextureView) attachment.textureView();
				Vector4fc color = attachment.clearValue().get();
				Mtl.utilClearRegion(this.deviceHandle, buffer, view.metalTexture().handle(), 0L, view.baseMipLevel(), area.x(), area.y(), area.width(),
					area.height(), color.x(), color.y(), color.z(), color.w(), 0.0F, CLEAR_REGION_COLOR);
			}
		}
		if (depth != null && depth.clearValue().isPresent()) {
			MetalTextureView view = (MetalTextureView) depth.textureView();
			if (view.metalTexture().getFormat().hasDepthAspect()) {
				Mtl.utilClearRegion(this.deviceHandle, buffer, 0L, view.metalTexture().handle(), view.baseMipLevel(), area.x(), area.y(), area.width(),
					area.height(), 0.0F, 0.0F, 0.0F, 0.0F, (float) depth.clearValue().getAsDouble(), CLEAR_REGION_DEPTH);
			}
		}
	}

	private static void putColorAttachment(long slot, MetalTexture texture, int level, int loadAction, float red, float green, float blue, float alpha) {
		MemoryUtil.memPutLong(slot + COLOR_TEXTURE, texture.handle());
		MemoryUtil.memPutInt(slot + COLOR_LEVEL, level);
		MemoryUtil.memPutInt(slot + COLOR_LOAD, loadAction);
		MemoryUtil.memPutInt(slot + COLOR_STORE, MetalFormats.STORE_STORE);
		MemoryUtil.memPutFloat(slot + COLOR_CLEAR, red);
		MemoryUtil.memPutFloat(slot + COLOR_CLEAR + 4, green);
		MemoryUtil.memPutFloat(slot + COLOR_CLEAR + 8, blue);
		MemoryUtil.memPutFloat(slot + COLOR_CLEAR + 12, alpha);
	}

	/**
	 * Depth goes to the depth attachment, stencil to the stencil attachment (the same texture for combined formats).
	 * Clears only ever target depth; stencil contents are preserved, as in the Vulkan backend.
	 */
	private static void putDepthStencilAttachment(long desc, MetalTexture texture, int level, int depthLoadAction, float clearDepth) {
		GpuFormat format = texture.getFormat();
		MemoryUtil.memPutInt(desc + DEPTH_LEVEL, level);
		if (format.hasDepthAspect()) {
			MemoryUtil.memPutLong(desc + DEPTH_TEXTURE, texture.handle());
			MemoryUtil.memPutInt(desc + DEPTH_LOAD, depthLoadAction);
			MemoryUtil.memPutInt(desc + DEPTH_STORE, MetalFormats.STORE_STORE);
			MemoryUtil.memPutFloat(desc + CLEAR_DEPTH, clearDepth);
		}
		if (format.hasStencilAspect()) {
			MemoryUtil.memPutLong(desc + STENCIL_TEXTURE, texture.handle());
			MemoryUtil.memPutInt(desc + STENCIL_LOAD, MetalFormats.LOAD_LOAD);
			MemoryUtil.memPutInt(desc + STENCIL_STORE, MetalFormats.STORE_STORE);
		}
	}

	@Override
	public void submitRenderPass() {
		MetalRenderPass pass = this.renderPass;
		if (pass == null) {
			throw new IllegalStateException("Cannot submit a render pass if one hasn't been started");
		}
		pass.finish();
		Mtl.renderEnd(this.renderEncoder);
		this.renderEncoder = 0L;
		this.renderPass = null;
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		MetalTexture texture = (MetalTexture) colorTexture;
		texture.clearRed = clearColor.x();
		texture.clearGreen = clearColor.y();
		texture.clearBlue = clearColor.z();
		texture.clearAlpha = clearColor.w();
		this.addPendingClear(texture);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		this.clearColorTexture(colorTexture, clearColor);
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
										   int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
		MetalTexture color = (MetalTexture) colorTexture;
		MetalTexture depth = (MetalTexture) depthTexture;
		int width = color.getWidth(mipLevel);
		int height = color.getHeight(mipLevel);
		if (regionX == 0 && regionY == 0 && regionWidth == width && regionHeight == height && color.getMipLevels() == 1
			&& depth.getWidth(0) == width && depth.getHeight(0) == height) {
			// Covers both single-mip textures entirely: an ordinary (foldable) clear.
			this.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
			return;
		}
		this.flushPendingClear(color);
		this.flushPendingClear(depth);
		this.endBlit();
		Mtl.utilClearRegion(this.deviceHandle, this.commandBuffer(), color.handle(), depth.handle(), mipLevel, regionX, regionY, regionWidth,
			regionHeight, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), (float) clearDepth, CLEAR_REGION_COLOR | CLEAR_REGION_DEPTH);
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		MetalTexture texture = (MetalTexture) depthTexture;
		texture.clearDepth = (float) clearDepth;
		this.addPendingClear(texture);
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		int size = data.remaining();
		if (size == 0) {
			return;
		}
		MetalBuffer target = (MetalBuffer) destination.buffer();
		MetalTransientMemory memory = this.transientMemory;
		long offset = memory.stage(size, BUFFER_STAGING_ALIGNMENT);
		MetalTransientMemory.copy(data, memory.stagedContents() + offset, size);
		Mtl.blitCopyBuffer(this.blitEncoder(), memory.stagedHandle(), offset, target.handle(), destination.offset(), size);
		target.lastUseSerial = this.device.currentSerial();
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		if (source.length() == 0L) {
			return;
		}
		MetalBuffer from = (MetalBuffer) source.buffer();
		MetalBuffer to = (MetalBuffer) target.buffer();
		long blit = this.blitEncoder();
		Mtl.blitCopyBuffer(blit, from.handle(), source.offset(), to.handle(), target.offset(), source.length());
		if (to.managed() && (to.usage() & GpuBuffer.USAGE_MAP_READ) != 0) {
			Mtl.blitSynchronize(blit, to.handle());
		}
		long serial = this.device.currentSerial();
		from.lastUseSerial = serial;
		to.lastUseSerial = serial;
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
		MetalTexture texture = (MetalTexture) destination;
		GpuFormat format = texture.getFormat();
		long bytesPerRow = (long) width * copyTexelSize(format);
		long bytes = bytesPerRow * height;
		if (bytes == 0L) {
			return;
		}
		this.flushPendingClear(texture);
		MetalTransientMemory memory = this.transientMemory;
		long offset = memory.stage(bytes, TEXTURE_STAGING_ALIGNMENT);
		MetalTransientMemory.copy(source, memory.stagedContents() + offset, bytes);
		Mtl.blitCopyBufferToTexture(this.blitEncoder(), memory.stagedHandle(), offset, bytesPerRow, bytes, texture.handle(), depthOrLayer, mipLevel,
			destX, destY, width, height, blitOptions(format));
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
									int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer) {
		MetalBuffer from = (MetalBuffer) source.buffer();
		MetalTexture texture = (MetalTexture) destination;
		GpuFormat format = texture.getFormat();
		this.flushPendingClear(texture);
		// The source is a sourceWidth x sourceHeight image with tightly packed rows (frontend-contracts 7.8).
		int texelSize = copyTexelSize(format);
		long bytesPerRow = (long) sourceWidth * texelSize;
		long offset = source.offset() + (sourceX + (long) sourceY * sourceWidth) * texelSize;
		Mtl.blitCopyBufferToTexture(this.blitEncoder(), from.handle(), offset, bytesPerRow, bytesPerRow * copyHeight, texture.handle(), arrayLayer,
			mipLevel, destinationX, destinationY, copyWidth, copyHeight, blitOptions(format));
		from.lastUseSerial = this.device.currentSerial();
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y,
									int width, int height) {
		MetalTexture texture = (MetalTexture) source;
		MetalBuffer target = (MetalBuffer) destination;
		GpuFormat format = texture.getFormat();
		this.flushPendingClear(texture);
		// Rows tightly packed, row 0 = texel row y (frontend-contracts 7.9).
		long bytesPerRow = (long) width * copyTexelSize(format);
		long blit = this.blitEncoder();
		Mtl.blitCopyTextureToBuffer(blit, texture.handle(), 0, mipLevel, x, y, width, height, target.handle(), offset, bytesPerRow,
			bytesPerRow * height, blitOptions(format));
		if (target.managed()) {
			Mtl.blitSynchronize(blit, target.handle());
		}
		target.lastUseSerial = this.device.currentSerial();
		this.device.runLater(callback);
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY,
									 int width, int height) {
		MetalTexture from = (MetalTexture) source;
		MetalTexture to = (MetalTexture) destination;
		this.flushPendingClear(from);
		this.flushPendingClear(to);
		Mtl.blitCopyTexture(this.blitEncoder(), from.handle(), 0, mipLevel, sourceX, sourceY, to.handle(), 0, mipLevel, destX, destY, width, height);
	}

	/**
	 * A fence for the serial being recorded. When nothing has been recorded since the last commit, the work the caller
	 * wants to protect is all in the previous serial, so the fence takes that one and completes a frame earlier (both
	 * choices satisfy the vanilla contract, see hot-paths-and-features 8.3).
	 */
	@Override
	public GpuFence createFence() {
		long serial = this.device.currentSerial();
		if (this.nothingRecorded()) {
			serial--;
		}
		return new MetalFence(this, serial);
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		((MetalQueryPool) pool).write(index, this.device.currentSerial(), this.nothingRecorded());
	}

	private boolean nothingRecorded() {
		return this.commandBuffer == 0L && this.pendingClearCount == 0;
	}

	/**
	 * MetalFence.awaitCompletion: true once {@code serial} completed. Timeout 0 never waits. Any other timeout on the
	 * serial still being recorded commits it first and continues recording into a new command buffer (the vanilla
	 * backends throw there instead, which the chunk staging ring can hit).
	 */
	boolean awaitSerial(long serial, long timeoutNs) {
		if (this.device.completedSerial() >= serial) {
			return true;
		}
		if (timeoutNs == 0L) {
			return false;
		}
		if (serial >= this.device.currentSerial()) {
			if (this.renderPass != null) {
				throw new IllegalStateException("Cannot wait on a fence for the current submit while a render pass is open");
			}
			this.endBlit();
			this.commit();
		}
		return this.device.waitSerial(serial, timeoutNs);
	}

	/**
	 * Commits the current command buffer (an empty one if nothing was recorded, so the serial still completes) and
	 * advances the serial. Pending clears may stay pending: they are encoded before anything touches their texture,
	 * whichever command buffer that is in.
	 */
	private void commit() {
		long buffer = this.commandBuffer();
		this.device.flushManagedWrites();
		Mtl.cmdCommit(this.deviceHandle, buffer, this.device.currentSerial());
		this.commandBuffer = 0L;
		this.device.advanceSerial();
	}

	private long blitEncoder() {
		if (this.blitEncoder == 0L) {
			this.blitEncoder = Mtl.blitBegin(this.commandBuffer());
			if (this.blitEncoder == 0L) {
				throw new IllegalStateException("Could not begin a Metal blit encoder: " + MetalDevice.lastError());
			}
		}
		return this.blitEncoder;
	}

	private void endBlit() {
		if (this.blitEncoder != 0L) {
			Mtl.blitEnd(this.blitEncoder);
			this.blitEncoder = 0L;
		}
	}

	private void addPendingClear(MetalTexture texture) {
		if (texture.clearPending) {
			// The later clear values replace the earlier ones; nothing in between touched the texture.
			return;
		}
		if (this.pendingClearCount == this.pendingClears.length) {
			this.pendingClears = Arrays.copyOf(this.pendingClears, this.pendingClearCount * 2);
			this.clearScratch = new MetalTexture[this.pendingClears.length];
		}
		texture.clearPending = true;
		this.pendingClears[this.pendingClearCount++] = texture;
	}

	/** Forgets the pending clear of a texture whose handle is being released. */
	void dropPendingClear(MetalTexture texture) {
		if (!texture.clearPending) {
			return;
		}
		texture.clearPending = false;
		for (int i = 0; i < this.pendingClearCount; i++) {
			if (this.pendingClears[i] == texture) {
				System.arraycopy(this.pendingClears, i + 1, this.pendingClears, i, this.pendingClearCount - i - 1);
				this.pendingClears[--this.pendingClearCount] = null;
				return;
			}
		}
	}

	/** Encodes the pending clear of one texture, if any, before a command that touches it. */
	private void flushPendingClear(MetalTexture texture) {
		if (texture.clearPending) {
			this.dropPendingClear(texture);
			boolean color = texture.getFormat().hasColorAspect();
			this.encodeClear(color ? texture : null, color ? null : texture);
		}
	}

	/**
	 * Encodes pending clears. With {@code foldInto}, clears of single-mip attachments of that pass stay pending so
	 * createRenderPass turns them into load actions; everything else must be encoded now, because the pass may sample
	 * it. Color and depth clears of equally sized textures share a pass, like clearColorAndDepthTextures.
	 */
	private void flushPendingClears(@Nullable RenderPassDescriptor foldInto) {
		if (this.pendingClearCount == 0) {
			return;
		}
		int kept = 0;
		int flushCount = 0;
		for (int i = 0; i < this.pendingClearCount; i++) {
			MetalTexture texture = this.pendingClears[i];
			this.pendingClears[i] = null;
			if (foldInto != null && texture.getMipLevels() == 1 && attaches(foldInto, texture)) {
				this.pendingClears[kept++] = texture;
			} else {
				texture.clearPending = false;
				this.clearScratch[flushCount++] = texture;
			}
		}
		this.pendingClearCount = kept;
		for (int i = 0; i < flushCount; i++) {
			MetalTexture texture = this.clearScratch[i];
			if (texture == null) {
				continue;
			}
			this.clearScratch[i] = null;
			boolean color = texture.getFormat().hasColorAspect();
			MetalTexture partner = null;
			for (int j = i + 1; j < flushCount; j++) {
				MetalTexture candidate = this.clearScratch[j];
				if (candidate != null && candidate.getFormat().hasColorAspect() != color && candidate.getWidth(0) == texture.getWidth(0)
					&& candidate.getHeight(0) == texture.getHeight(0) && candidate.getMipLevels() == texture.getMipLevels()) {
					partner = candidate;
					this.clearScratch[j] = null;
					break;
				}
			}
			this.encodeClear(color ? texture : partner, color ? partner : texture);
		}
	}

	private static boolean attaches(RenderPassDescriptor descriptor, MetalTexture texture) {
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		for (int i = 0; i < colors.size(); i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			if (attachment != null && attachment.textureView().texture() == texture) {
				return true;
			}
		}
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		return depth != null && depth.textureView().texture() == texture;
	}

	/** Whole-texture clear of every mip: one empty render pass per mip with clear load actions. */
	private void encodeClear(@Nullable MetalTexture color, @Nullable MetalTexture depth) {
		this.endBlit();
		long buffer = this.commandBuffer();
		int mipLevels = color != null ? color.getMipLevels() : depth.getMipLevels();
		long desc = this.passDesc;
		for (int level = 0; level < mipLevels; level++) {
			MemoryUtil.memSet(desc, 0, PASS_DESC_SIZE);
			if (color != null) {
				putColorAttachment(desc, color, level, MetalFormats.LOAD_CLEAR, color.clearRed, color.clearGreen, color.clearBlue, color.clearAlpha);
			}
			if (depth != null) {
				putDepthStencilAttachment(desc, depth, level, MetalFormats.LOAD_CLEAR, depth.clearDepth);
			}
			long encoder = Mtl.renderBegin(buffer, desc);
			if (encoder == 0L) {
				throw new IllegalStateException("Could not begin a Metal clear pass: " + MetalDevice.lastError());
			}
			Mtl.renderEnd(encoder);
		}
	}

	/** Bytes per texel in buffers: the depth part only for combined depth/stencil formats (copied with DepthFromDepthStencil). */
	private static int copyTexelSize(GpuFormat format) {
		return format.hasDepthAspect() && format.hasStencilAspect() ? 4 : format.blockSize();
	}

	private static int blitOptions(GpuFormat format) {
		return format.hasDepthAspect() && format.hasStencilAspect() ? MetalFormats.BLIT_DEPTH_FROM_DEPTH_STENCIL : MetalFormats.BLIT_NONE;
	}

	/** Device close: commits what was recorded, waits for the GPU to go idle and releases everything still queued. */
	void shutdown() {
		for (int i = 0; i < this.pendingClearCount; i++) {
			this.pendingClears[i].clearPending = false;
			this.pendingClears[i] = null;
		}
		this.pendingClearCount = 0;
		if (this.renderPass != null) {
			// Left open by a failure while rendering; Metal cannot commit a command buffer with an open encoder.
			Mtl.renderEnd(this.renderEncoder);
			this.renderEncoder = 0L;
			this.renderPass = null;
		}
		this.endBlit();
		if (this.commandBuffer != 0L) {
			this.commit();
		}
		this.device.awaitSerial(this.device.currentSerial() - 1L);
		// Nothing runs on the GPU any more, so every queued release and readback callback may run, including the ones
		// tagged with the uncommitted serial and those the callbacks queue themselves.
		this.device.drainDeferred(Long.MAX_VALUE);
		this.transientMemory.close();
		MemoryUtil.nmemFree(this.passDesc);
	}
}
