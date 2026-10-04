package dev.ingot.mixin;

import dev.ingot.energy.PowerPolicy;
import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla parks and then spins for the last 0.5 ms (plus average overshoot) of every limited frame. Ingot sleeps to
 * the same absolute deadline in the kernel instead. Optional: without it the vanilla limiter runs.
 */
@Mixin(FramerateLimiter.class)
abstract class FramerateLimiterMixin {
	@Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true, require = 0)
	private static void ingot$sleepWithoutSpinning(int framerateLimit, CallbackInfo ci) {
		if (PowerPolicy.limitDisplayFps(framerateLimit)) {
			ci.cancel();
		}
	}
}
