package dev.ingot.backend;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

/** A view over a mip range of a {@link MetalTexture} (cube views for cube textures). Owner: core. */
public class MetalTextureView extends BaseGpuTextureView {
	protected MetalTextureView(MetalTexture texture, int baseMipLevel, int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
	}

	/** id&lt;MTLTexture&gt; to bind for sampling: a texture view, or the texture itself when the view covers it. CONTRACT */
	public long handle() {
		throw new UnsupportedOperationException("not implemented");
	}

	/** CONTRACT */
	public MetalTexture metalTexture() {
		return (MetalTexture) this.texture();
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
