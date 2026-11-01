package dev.ingot.backend;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.ingot.IngotConfig;
import dev.ingot.energy.RenderScale;
import dev.ingot.nativ.Mtl;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.MemoryUtil;

/**
 * CAMetalLayer-backed surface with late drawable acquisition. Owner: passes.
 *
 * <p>acquireNextTexture has nothing to do. The drawable is taken in blitFromTexture, after all of the frame's CPU work,
 * and the flip-blit plus presentDrawable: are encoded into the frame's command buffer there, because Minecraft calls
 * present() only after submit() has committed it, so present() has nothing left to do. Nothing in the present path
 * throws: a frame without a drawable is simply not shown.
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
	/** Set by PowerPolicy each frame: the frame limit is above the display's refresh rate and vsync is off. */
	private static boolean presentWithoutWaiting;

	private final MetalDevice device;
	private final BooleanSupplier isIconified;
	/** Treat a fully covered window as invisible: no drawable is taken (it could block) and nothing is presented. */
	private final boolean skipWhenOccluded;
	private long surface;
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

		// The layer stays on GLFW's content view for the window's lifetime; Ingot owns its drawableSize (set in configure).
		long layer = Mtl.viewMetalLayer(GLFWNativeCocoa.glfwGetCocoaView(windowHandle));
		this.surface = layer != 0L ? Mtl.surfaceCreate(device.handle(), layer) : 0L;
		if (this.surface == 0L) {
			throw new IllegalStateException("Could not set up the window's CAMetalLayer: " + MemoryUtil.memUTF8(Mtl.lastError()));
		}
		current = this;
	}

	/** The live surface (for occlusion / refresh-rate queries by the energy policy), or null. CONTRACT */
	public static @Nullable MetalSurface current() {
		return current;
	}

	/**
	 * Uncapped presentation: blitFromTexture takes a drawable only when one is already free and otherwise skips showing
	 * the frame, instead of waiting for the compositor, which in a window releases drawables only at the display's
	 * refresh rate. Render thread.
	 */
	public static void setPresentWithoutWaiting(boolean value) {
		presentWithoutWaiting = value;
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
		// Minecraft configures the window's size in pixels; the drawable has the size it renders at (RenderScale), which
		// the layer stretches over the window.
		int width = RenderScale.scale(config.width());
		int height = RenderScale.scale(config.height());
		int maxSize = this.device.caps().maxTextureSize();
		if (width <= 0 || height <= 0 || width > maxSize || height > maxSize) {
			throw new SurfaceException("Surface size " + width + "x" + height + " is outside 1.." + maxSize);
		}
		int displaySync = config.presentMode() == GpuSurface.PresentMode.FIFO ? 1 : 0;
		Mtl.surfaceConfigure(this.surface, width, height, displaySync, MAX_DRAWABLES);
		this.suboptimal = false;
	}

	@Override
	public boolean isSuboptimal() {
		return this.suboptimal;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		// Nothing to do: a minimized window is skipped in blitFromTexture. Throwing here would make Minecraft reconfigure
		// and fail again every other frame, logging each time.
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		if (this.isIconified.getAsBoolean() || this.skipWhenOccluded && !this.isVisible()) {
			return;
		}
		boolean withoutWaiting = presentWithoutWaiting;
		long drawable = withoutWaiting ? Mtl.surfaceTryDrawable(this.surface) : Mtl.surfaceNextDrawable(this.surface);
		if (drawable == 0L) {
			// Uncapped: no drawable free yet, so this frame is not shown. Otherwise none came within the layer's
			// timeout: skip this frame and have Minecraft reconfigure the surface.
			this.suboptimal |= !withoutWaiting;
			return;
		}
		long commandBuffer = ((MetalCommandEncoder) commandEncoder).commandBufferForExternalPass();
		MetalTextureView view = (MetalTextureView) textureView;
		Mtl.surfaceBlit(this.surface, commandBuffer, drawable, view.handle(), view.getWidth(0), view.getHeight(0), 1);
		// Released once the command buffer that draws and presents it completes. Releasing in present() would tag it
		// with the next frame's serial (present() runs after submit()), and the layer cannot reuse a drawable the app
		// still holds, so nextDrawable would block sooner.
		this.presentSerial = this.device.currentSerial();
		this.device.releaseLater(drawable);
	}

	@Override
	public void present() {
		// presentDrawable: was encoded with the blit and committed by submit(); the drawable is already queued for release.
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
		// The last presented frame's blit pass may still be reading the surface's pipeline state on the GPU.
		if (this.presentSerial > this.device.completedSerial()) {
			Mtl.deviceWaitSerial(this.device.handle(), this.presentSerial, CLOSE_TIMEOUT_NS);
		}
		Mtl.surfaceDestroy(this.surface);
		this.surface = 0L;
		if (current == this) {
			current = null;
		}
	}
}
