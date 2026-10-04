package dev.ingot.backend;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

/**
 * A view over a mip range of a {@link MetalTexture} (cube views for cube textures). Owner: core. A view that covers
 * every mip has no native object of its own and binds the texture itself.
 */
public class MetalTextureView extends BaseGpuTextureView {
	/** The MTLTexture view, or 0 when the view covers the whole texture. */
	private final long viewHandle;
	private boolean closed;

	MetalTextureView(MetalTexture texture, int baseMipLevel, int mipLevels, long viewHandle) {
		super(texture, baseMipLevel, mipLevels);
		this.viewHandle = viewHandle;
	}

	/** id&lt;MTLTexture&gt; to bind for sampling: a texture view, or the texture itself when the view covers it. CONTRACT */
	public long handle() {
		return this.viewHandle != 0L ? this.viewHandle : this.metalTexture().handle();
	}

	/** CONTRACT */
	public MetalTexture metalTexture() {
		return (MetalTexture) this.texture();
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			MetalTexture texture = this.metalTexture();
			texture.device.releaseLater(this.viewHandle);
			texture.releaseReference();
		}
	}
}
