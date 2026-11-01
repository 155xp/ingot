package dev.ingot.bench.mixin;

import dev.ingot.bench.Bench;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
	/** Prepare mode generates the scenario's chunks a few at a time on the integrated server thread. */
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void ingotBench$afterTick(BooleanSupplier haveTime, CallbackInfo ci) {
		Bench.onServerTick((MinecraftServer) (Object) this);
	}
}
