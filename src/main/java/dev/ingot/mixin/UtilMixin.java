package dev.ingot.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ingot.energy.WorkerQos;
import net.minecraft.TracingExecutor;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets battery mode move the background worker pool to the efficiency cores (WorkerQos). Optional: without it the
 * workers stay on the JVM's default QoS.
 */
@Mixin(Util.class)
abstract class UtilMixin {
	@ModifyReturnValue(method = "makeExecutor", at = @At("RETURN"), require = 0)
	private static TracingExecutor ingot$qosAwareWorkers(TracingExecutor executor) {
		return new TracingExecutor(WorkerQos.wrap(executor.service()));
	}
}
