package dev.ingot.energy;

import com.mojang.blaze3d.platform.VideoMode;
import dev.ingot.Ingot;
import dev.ingot.IngotConfig;
import dev.ingot.backend.MetalSurface;
import dev.ingot.nativ.Mtl;
import net.minecraft.client.Minecraft;

/**
 * Ingot's energy policy (docs/ARCHITECTURE.md, "Energy efficiency"), called by the mixins on the render thread: the
 * spin-free frame limiter, the occlusion throttle and the battery saver. None of it needs the Metal backend to be
 * active; only the window queries prefer the Metal surface when there is one.
 */
public final class PowerPolicy {
	/** framerateLimit value meaning "Unlimited" (the top of the Max Framerate slider). */
	private static final int UNLIMITED_FRAMERATE = 260;
	private static final long SECOND_NS = 1_000_000_000L;

	private static final IngotConfig CONFIG = IngotConfig.get();
	private static final boolean NATIVE = probeNative();

	/** End of the previous frame-limiter wait; the same accounting as FramerateLimiter.lastFrameTime. */
	private static long lastFrameTime = System.nanoTime();
	private static boolean savingPower;
	private static long nextPowerQuery = System.nanoTime();
	private static int windowRefreshRate = 60;
	private static long nextRefreshRateQuery = System.nanoTime();

	private PowerPolicy() {
	}

	/**
	 * Replacement for FramerateLimiter.limitDisplayFPS: sleeps in the kernel until the frame's absolute deadline
	 * instead of parking and then spinning through the last half millisecond. Returns false (vanilla must run) when
	 * libingot is unavailable.
	 */
	public static boolean limitDisplayFps(int framerateLimit) {
		if (!NATIVE) {
			return false;
		}
		long deadline = lastFrameTime + SECOND_NS / framerateLimit;
		if (deadline - System.nanoTime() > 0L) {
			Mtl.waitUntil(deadline);
		}
		lastFrameTime = System.nanoTime();
		return true;
	}

	/** True when the game window is completely covered and should be throttled like a minimized one. */
	public static boolean isWindowOccluded() {
		if (!CONFIG.throttleWhenOccluded) {
			return false;
		}
		MetalSurface surface = MetalSurface.current();
		return surface != null && !surface.isVisible();
	}

	/**
	 * Battery saver: while it applies, an Unlimited framerate becomes the display's refresh rate (frames beyond it
	 * are never shown). With vsync on the display already paces frames, so the limiter is left alone there.
	 */
	public static int limitFramerate(int framerateLimit) {
		if (framerateLimit < UNLIMITED_FRAMERATE || !batterySaverActive() || Minecraft.getInstance().options.enableVsync().get()) {
			return framerateLimit;
		}
		return displayRefreshRate();
	}

	private static boolean batterySaverActive() {
		return switch (CONFIG.batterySaver) {
			case ALWAYS -> true;
			case NEVER -> false;
			case AUTO -> onBatteryOrLowPowerMode();
		};
	}

	private static boolean onBatteryOrLowPowerMode() {
		long now = System.nanoTime();
		if (NATIVE && now - nextPowerQuery >= 0L) {
			nextPowerQuery = now + SECOND_NS;
			savingPower = (Mtl.powerState() & (Mtl.POWER_ON_BATTERY | Mtl.POWER_LOW_POWER_MODE)) != 0;
		}
		return savingPower;
	}

	private static int displayRefreshRate() {
		MetalSurface surface = MetalSurface.current();
		if (surface != null) {
			return surface.maxRefreshRate();
		}
		// Another backend is active: ask SDL for the mode of the display that shows the window.
		long now = System.nanoTime();
		if (now - nextRefreshRateQuery >= 0L) {
			nextRefreshRateQuery = now + SECOND_NS;
			VideoMode mode = Minecraft.getInstance().getWindow().getActiveVideoMode();
			if (mode != null && mode.getRefreshRate() >= 1.0F) {
				windowRefreshRate = Math.round(mode.getRefreshRate());
			}
		}
		return windowRefreshRate;
	}

	private static boolean probeNative() {
		try {
			if (Mtl.abiVersion() == Mtl.ABI_VERSION) {
				return true;
			}
			Ingot.LOGGER.warn("Ingot: libingot has the wrong ABI version; frame limiter and battery saver stay vanilla");
		} catch (Throwable t) {
			Ingot.LOGGER.warn("Ingot: libingot is unavailable; frame limiter and battery saver stay vanilla", t);
		}
		return false;
	}
}
