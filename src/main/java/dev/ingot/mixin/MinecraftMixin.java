package dev.ingot.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.device.BackendCreationException;
import dev.ingot.backend.MetalBackend;
import dev.ingot.energy.PowerPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.main.GameConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the power policy once per frame (PowerPolicy.update; without it the power modes never switch).
 *
 * <p>With the Default graphics API, Minecraft boots MoltenVK just to probe Vulkan (the result only feeds telemetry and
 * crash reports). Skip that when Metal is first in line. Optional: without it the probe runs as in vanilla.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@WrapOperation(method = "<init>", require = 0, at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;checkBackendAvailable()Lcom/mojang/renderpearl/api/device/BackendCreationException;"))
	private @Nullable BackendCreationException ingot$skipVulkanProbe(Operation<BackendCreationException> probe, @Local(argsOnly = true) GameConfig gameConfig) {
		// The same effective preference Minecraft computed just before (launch argument, else the possibly reset option).
		PreferredGraphicsApi forced = gameConfig.game.forcedGraphicsApi;
		PreferredGraphicsApi preference = forced != null ? forced : ((Minecraft) (Object) this).options.preferredGraphicsBackend().get();
		return MetalBackend.shouldTry(preference) ? null : probe.call();
	}

	@Inject(method = "runTick", at = @At("HEAD"), require = 0)
	private void ingot$updatePowerPolicy(CallbackInfo ci) {
		PowerPolicy.update((Minecraft) (Object) this);
	}
}
