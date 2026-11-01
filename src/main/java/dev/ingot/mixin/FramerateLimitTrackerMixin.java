package dev.ingot.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.ingot.energy.PowerPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Occlusion throttle (a fully covered window gets the minimized-window frame rate) and the power mode's frame cap
 * (Battery mode: the display refresh rate by default). Both change only the value read each frame, never the saved
 * option. Optional: without them the vanilla limits apply.
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitTrackerMixin {
	@ModifyReturnValue(method = "getThrottleReason", at = @At("RETURN"), require = 0)
	private FramerateLimitTracker.FramerateThrottleReason ingot$throttleWhenOccluded(FramerateLimitTracker.FramerateThrottleReason reason) {
		return reason != FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED && PowerPolicy.isWindowOccluded()
			? FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED
			: reason;
	}

	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"), require = 0)
	private int ingot$powerModeCap(int framerateLimit) {
		return PowerPolicy.limitFramerate(framerateLimit);
	}
}
