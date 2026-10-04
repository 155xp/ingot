package dev.ingot.backend;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/** CAMetalLayer-backed surface with late drawable acquisition. Owner: passes. */
public final class MetalSurface implements GpuSurfaceBackend {
	/** Called by MetalDevice.createSurface. CONTRACT */
	public MetalSurface(MetalDevice device, long windowHandle, BooleanSupplier isIconified) {
	}

	/** The live surface (for occlusion / refresh-rate queries by the energy policy), or null. CONTRACT */
	public static @Nullable MetalSurface current() {
		return null;
	}

	/** True when any part of the window is visible on screen. Main thread. CONTRACT */
	public boolean isVisible() {
		return true;
	}

	/** Maximum refresh rate of the window's display (e.g. 120 on ProMotion). Main thread. CONTRACT */
	public int maxRefreshRate() {
		return 60;
	}

	@Override
	public void configure(GpuSurface.Configuration config) throws SurfaceException {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public boolean isSuboptimal() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void present() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		throw new UnsupportedOperationException("not implemented");
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException("not implemented");
	}
}
