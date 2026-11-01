package dev.ingot.energy;

import com.mojang.blaze3d.platform.VideoMode;
import dev.ingot.Ingot;
import dev.ingot.IngotConfig;
import dev.ingot.backend.MetalSurface;
import dev.ingot.nativ.Mtl;
import net.minecraft.client.Minecraft;

/**
 * Ingot's energy policy (docs/ARCHITECTURE.md, "Energy efficiency"), called by the mixins on the render thread: the
 * power modes, the spin-free frame limiter and the occlusion throttle. None of it needs the Metal backend to be active
 * except the render resolution, which needs Ingot's surface to stretch the frame.
 *
 * <p>Power modes: Performance leaves the frame rate to Video Settings and keeps everything on the performance cores.
 * Battery caps the frame rate (by default to the display's refresh rate, 120 on ProMotion), runs background work on
 * the efficiency cores and can render at half resolution. Auto is Battery on battery power or in Low Power Mode.
 */
public final class PowerPolicy {
	private static final long SECOND_NS = 1_000_000_000L;

	private static final IngotConfig CONFIG = IngotConfig.get();
	private static final boolean NATIVE = probeNative();

	/** End of the previous frame-limiter wait; the same accounting as FramerateLimiter.lastFrameTime. */
	private static long lastFrameTime = System.nanoTime();
	private static boolean onBatteryOrLowPower;
	private static long nextPowerQuery = System.nanoTime();
	private static int windowRefreshRate = 60;
	private static long nextRefreshRateQuery = System.nanoTime();
	/** Profile in effect for the current frame. */
	private static boolean batteryMode;

	private PowerPolicy() {
	}

	/** Once per frame, at the top of Minecraft.runTick: resolves the power mode and applies what changed. */
	public static void update(Minecraft minecraft) {
		batteryMode = switch (CONFIG.powerMode) {
			case PERFORMANCE -> false;
			case BATTERY -> true;
			case AUTO -> onBatteryOrLowPowerMode();
		};
		if (NATIVE) {
			WorkerQos.setEfficient(batteryMode && CONFIG.batteryEfficiencyCores);
		}
		IngotConfig.Resolution resolution = batteryMode ? CONFIG.batteryResolution : CONFIG.performanceResolution;
		RenderScale.apply(minecraft, MetalSurface.current() != null ? resolution.divisor : 1);
		// Frames beyond the refresh rate (vsync off, limit above it, e.g. Unlimited in Performance mode) must not wait
		// for the compositor, or the window holds the game to the refresh rate.
		MetalSurface.setPresentWithoutWaiting(!minecraft.options.enableVsync().get()
			&& minecraft.getFramerateLimitTracker().getFramerateLimit() > displayRefreshRate());
	}

	/** True while Battery mode applies (explicitly, or Auto on battery power / Low Power Mode). */
	public static boolean isBatteryMode() {
		return batteryMode;
	}

	/** True when Auto would pick Battery mode right now. */
	public static boolean isOnBatteryOrLowPower() {
		return onBatteryOrLowPowerMode();
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
	 * The power mode's frame cap applied to the limit FramerateLimitTracker computed (which already includes the menu,
	 * AFK and minimized-window limits): the lower of the two wins. With vsync on the display already paces frames at
	 * its refresh rate, so a cap at or above it is left to the display.
	 */
	public static int limitFramerate(int framerateLimit) {
		int cap = batteryMode
			? (CONFIG.batteryFps == IngotConfig.FPS_DISPLAY ? displayRefreshRate() : CONFIG.batteryFps)
			: CONFIG.performanceFps;
		if (cap <= 0 || cap >= framerateLimit) {
			return framerateLimit;
		}
		if (cap >= displayRefreshRate() && Minecraft.getInstance().options.enableVsync().get()) {
			return framerateLimit;
		}
		return cap;
	}

	private static boolean onBatteryOrLowPowerMode() {
		long now = System.nanoTime();
		if (NATIVE && now - nextPowerQuery >= 0L) {
			nextPowerQuery = now + SECOND_NS;
			onBatteryOrLowPower = (Mtl.powerState() & (Mtl.POWER_ON_BATTERY | Mtl.POWER_LOW_POWER_MODE)) != 0;
		}
		return onBatteryOrLowPower;
	}

	/** Maximum refresh rate of the display showing the window (120 on ProMotion). */
	public static int displayRefreshRate() {
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
			Ingot.LOGGER.warn("Ingot: libingot has the wrong ABI version; frame limiter and power modes stay vanilla");
		} catch (Throwable t) {
			Ingot.LOGGER.warn("Ingot: libingot is unavailable; frame limiter and power modes stay vanilla", t);
		}
		return false;
	}
}
