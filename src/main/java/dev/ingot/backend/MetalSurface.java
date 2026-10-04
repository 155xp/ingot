package dev.ingot.backend;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.ingot.IngotConfig;
import dev.ingot.nativ.Mtl;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.system.MemoryUtil;

/**
 * CAMetalLayer-backed surface with late drawable acquisition. Owner: passes.
 *
 * <p>acquireNextTexture only checks that the window can be presented to. The drawable is taken in blitFromTexture,
 * after all of the frame's CPU work, and the flip-blit plus presentDrawable: are encoded into the frame's command
 * buffer there, because Minecraft calls present() only after submit() has committed it. present() then just hands
 * the drawable back. Nothing in the present path throws: a frame without a drawable is simply not shown.
 */
public final class MetalSurface implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> PRESENT_MODES =
		Collections.unmodifiableSet(EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE));
	private static final int MAX_DRAWABLES = 3;
	/** visible / maxRefreshRate are AppKit queries on the main thread; refresh them at most this often. */
	private static final long WINDOW_QUERY_INTERVAL_NS = 250_000_000L;
	/** Bound on the wait for the last presented frame in close(); shutdown must never hang. */
	private static final long CLOSE_TIMEOUT_NS = 1_000_000_000L;

	private static @Nullable MetalSurface current;

	private final MetalDevice device;
	private final BooleanSupplier isIconified;
	/** Treat a fully covered window as invisible: no drawable is taken (it could block) and nothing is presented. */
	private final boolean skipWhenOccluded;
	private long view;
	private long surface;
	private long drawable;
	/** Serial of the command buffer that carries the most recent presentDrawable:. */
	private long presentSerial;
	private boolean suboptimal;

	private boolean visible = true;
	private long nextVisibilityQuery = System.nanoTime();
	private int refreshRate = 60;
	private long nextRefreshRateQuery = System.nanoTime();

	/** Called by MetalDevice.createSurface. CONTRACT */
	public MetalSurface(MetalDevice device, long windowHandle, BooleanSupplier isIconified) {
		this.device = device;
		this.isIconified = isIconified;
		this.skipWhenOccluded = IngotConfig.get().throttleWhenOccluded;

		// Ingot owns drawableSize (set in configure); the hint is only honoured when set before the view exists.
		SDLHints.SDL_SetHint(SDLHints.SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE, "0");
		this.view = SDLMetal.SDL_Metal_CreateView(windowHandle);
		if (this.view == 0L) {
			throw new IllegalStateException("SDL_Metal_CreateView failed: " + SDLError.SDL_GetError());
		}
		this.surface = Mtl.surfaceCreate(device.handle(), SDLMetal.SDL_Metal_GetLayer(this.view));
		if (this.surface == 0L) {
			String error = MemoryUtil.memUTF8(Mtl.lastError());
			SDLMetal.SDL_Metal_DestroyView(this.view);
			throw new IllegalStateException("Could not set up the window's CAMetalLayer: " + error);
		}
		current = this;
	}

	/** The live surface (for occlusion / refresh-rate queries by the energy policy), or null. CONTRACT */
	public static @Nullable MetalSurface current() {
		return current;
	}

	/** True when any part of the window is visible on screen. Main thread. CONTRACT */
	public boolean isVisible() {
		long now = System.nanoTime();
		if (now - this.nextVisibilityQuery >= 0L && this.surface != 0L) {
			this.nextVisibilityQuery = now + WINDOW_QUERY_INTERVAL_NS;
			this.visible = Mtl.surfaceVisible(this.surface) != 0;
		}
		return this.visible;
	}

	/** Maximum refresh rate of the window's display (e.g. 120 on ProMotion). Main thread. CONTRACT */
	public int maxRefreshRate() {
		long now = System.nanoTime();
		if (now - this.nextRefreshRateQuery >= 0L && this.surface != 0L) {
			this.nextRefreshRateQuery = now + WINDOW_QUERY_INTERVAL_NS;
			this.refreshRate = Mtl.surfaceMaxRefreshRate(this.surface);
		}
		return this.refreshRate;
	}

	@Override
	public void configure(GpuSurface.Configuration config) throws SurfaceException {
		int maxSize = this.device.caps().maxTextureSize();
		if (config.width() <= 0 || config.height() <= 0 || config.width() > maxSize || config.height() > maxSize) {
			throw new SurfaceException("Surface size " + config.width() + "x" + config.height() + " is outside 1.." + maxSize);
		}
		int displaySync = config.presentMode() == GpuSurface.PresentMode.FIFO ? 1 : 0;
		Mtl.surfaceConfigure(this.surface, config.width(), config.height(), displaySync, MAX_DRAWABLES);
		this.suboptimal = false;
	}

	@Override
	public boolean isSuboptimal() {
		return this.suboptimal;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		// Like the GL backend: Minecraft then stops acquiring (and logs once) until the window is restored.
		if (this.isIconified.getAsBoolean()) {
			throw new SurfaceException("Cannot acquire a minimized window");
		}
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		if (this.skipWhenOccluded && !this.isVisible()) {
			return;
		}
		long drawable = Mtl.surfaceNextDrawable(this.surface);
		if (drawable == 0L) {
			// No drawable within the layer's timeout: skip this frame and have Minecraft reconfigure the surface.
			this.suboptimal = true;
			return;
		}
		long commandBuffer = ((MetalCommandEncoder) commandEncoder).commandBufferForExternalPass();
		MetalTextureView view = (MetalTextureView) textureView;
		Mtl.surfaceBlit(this.surface, commandBuffer, drawable, view.handle(), view.getWidth(0), view.getHeight(0), 1);
		this.drawable = drawable;
		this.presentSerial = this.device.currentSerial();
	}

	@Override
	public void present() {
		if (this.drawable != 0L) {
			this.device.releaseLater(this.drawable);
			this.drawable = 0L;
		}
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return PRESENT_MODES;
	}

	@Override
	public void close() {
		if (this.surface == 0L) {
			return;
		}
		this.present();
		// The last presented frame's blit pass may still be reading the surface's pipeline state on the GPU.
		if (this.presentSerial > this.device.completedSerial()) {
			Mtl.deviceWaitSerial(this.device.handle(), this.presentSerial, CLOSE_TIMEOUT_NS);
		}
		Mtl.surfaceDestroy(this.surface);
		this.surface = 0L;
		SDLMetal.SDL_Metal_DestroyView(this.view);
		this.view = 0L;
		if (current == this) {
			current = null;
		}
	}
}
