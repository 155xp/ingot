package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

/**
 * An MTLTexture (2D or cube, Private storage). Owner: core.
 *
 * <p>The native texture is shared with every view that covers all of its mips, so it is reference counted like
 * Vulkan's: the texture holds one reference, every view one more, and the handle is released (deferred until the GPU
 * is done) when the last of them closes.
 */
public class MetalTexture extends BaseGpuTexture {
	final MetalDevice device;
	private final long handle;
	private final int pixelFormat;
	private final boolean cube;
	private boolean closed;
	private int references = 1;

	// A whole-texture clear recorded by MetalCommandEncoder but not encoded yet (all mips). Owned by the encoder.
	boolean clearPending;
	float clearRed;
	float clearGreen;
	float clearBlue;
	float clearAlpha;
	float clearDepth;

	MetalTexture(MetalDevice device, long handle, int pixelFormat, boolean cube, int usage, String label, GpuFormat format, int width, int height,
				 int depthOrLayers, int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.device = device;
		this.handle = handle;
		this.pixelFormat = pixelFormat;
		this.cube = cube;
	}

	/** id&lt;MTLTexture&gt; handle. CONTRACT */
	public long handle() {
		return this.handle;
	}

	/** MTLPixelFormat of the texture. CONTRACT */
	public int pixelFormat() {
		return this.pixelFormat;
	}

	/** True for cube-compatible textures (6 faces). CONTRACT */
	public boolean isCube() {
		return this.cube;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.releaseReference();
		}
	}

	void retain() {
		this.references++;
	}

	void releaseReference() {
		if (--this.references == 0) {
			this.device.encoder().dropPendingClear(this);
			this.device.releaseLater(this.handle);
		}
	}
}
