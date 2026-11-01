package dev.ingot.bench.mixin;

import dev.ingot.bench.Bench;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
abstract class MobMixin {
	/** Mobs keep their physics but never decide to move or look around, so every run sees the same entity poses. */
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void ingotBench$freezeAi(CallbackInfo ci) {
		if (Bench.freezeMobs()) {
			ci.cancel();
		}
	}
}
