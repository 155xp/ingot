package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

/** An MTLTexture (2D or cube, Private storage). Owner: core. */
public class MetalTexture extends BaseGpuTexture {
	protected MetalTexture(int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
	}

	/** id&lt;MTLTexture&gt; handle. CONTRACT */
	public long handle() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** MTLPixelFormat of the texture. CONTRACT */
	public int pixelFormat() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** True for cube-compatible textures (6 faces). CONTRACT */
	public boolean isCube() {
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
}
