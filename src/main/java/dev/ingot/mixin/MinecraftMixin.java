package dev.ingot.mixin;

import dev.ingot.energy.PowerPolicy;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the power policy once per frame (PowerPolicy.update; without it the power modes never switch). */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"), require = 0)
	private void ingot$updatePowerPolicy(CallbackInfo ci) {
		PowerPolicy.update((Minecraft) (Object) this);
	}
}
