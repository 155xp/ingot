package dev.ingot.energy;

import dev.ingot.mixin.WindowAccessor;
import net.minecraft.client.Minecraft;

/**
 * Render resolution. With a divisor of 2 Minecraft sees a framebuffer of one pixel per point instead of the Retina
 * window's two, so it renders (world, GUI, post effects) a quarter of the pixels, and the CAMetalLayer stretches the
 * smaller drawable over the window with nearest filtering. The GUI picks the matching scale, so text and icons cover
 * the same screen area with the same pixel-art sharpness; only the 3D world loses resolution.
 *
 * <p>Only Ingot's Metal surface knows how to stretch, so the divisor stays 1 on the vanilla backends. Render thread.
 */
public final class RenderScale {
	private static int divisor = 1;

	private RenderScale() {
	}

	/** Window pixels per rendered pixel along each axis (1 or 2). */
	public static int divisor() {
		return divisor;
	}

	/** A window size in pixels as Minecraft should see it. */
	public static int scale(int pixels) {
		return divisor == 1 ? pixels : Math.max(1, (pixels + divisor - 1) / divisor);
	}

	/** A fixed GUI scale chosen for native resolution, converted so the GUI keeps its size on screen. */
	public static int scaleGuiScale(int guiScale) {
		return divisor == 1 || guiScale <= 0 ? guiScale : Math.max(1, (guiScale + divisor / 2) / divisor);
	}

	/** Switches the resolution and resizes everything the way a window resize does. */
	static void apply(Minecraft minecraft, int newDivisor) {
		if (newDivisor == divisor) {
			return;
		}
		divisor = newDivisor;
		((WindowAccessor) (Object) minecraft.getWindow()).ingot$refreshFramebufferSize();
		// Reconfigures the surface and the GUI scale; GameRenderer resizes its targets on the next frame.
		minecraft.framebufferSizeChanged();
	}
}
