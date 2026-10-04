package dev.ingot.backend;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

/** An MTLSamplerState. Owner: core. */
public class MetalSampler implements GpuSampler {
	/** id&lt;MTLSamplerState&gt; handle. CONTRACT */
	public long handle() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public AddressMode getAddressModeU() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public AddressMode getAddressModeV() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public FilterMode getMinFilter() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public FilterMode getMagFilter() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public int getMaxAnisotropy() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public OptionalDouble getMaxLod() {
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
