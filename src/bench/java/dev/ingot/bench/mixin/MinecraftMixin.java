package dev.ingot.bench.mixin;

import dev.ingot.bench.Bench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/** One call per iteration of the client main loop: the frame boundary used for frame timing. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void ingotBench$frameBoundary(boolean advanceGameTime, CallbackInfo ci) {
		Bench.onFrameBoundary((Minecraft) (Object) this);
	}

	/** After this frame's client ticks, before the camera is extracted: where the scripted pose is applied. */
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void ingotBench$beforeRender(boolean advanceGameTime, CallbackInfo ci) {
		Bench.beforeRender((Minecraft) (Object) this);
	}
}
