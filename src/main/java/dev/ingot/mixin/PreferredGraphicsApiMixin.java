package dev.ingot.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.ingot.backend.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the Metal backend in front of the vanilla backends when the graphics API preference is DEFAULT. If Metal
 * fails it throws BackendCreationException and Minecraft moves on to the next backend in the array.
 */
@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void ingot$preferMetal(CallbackInfoReturnable<GpuBackend[]> cir) {
		if (!MetalBackend.shouldTry((PreferredGraphicsApi) (Object) this)) {
			return;
		}
		GpuBackend[] vanilla = cir.getReturnValue();
		GpuBackend[] withMetal = new GpuBackend[vanilla.length + 1];
		withMetal[0] = new MetalBackend();
		System.arraycopy(vanilla, 0, withMetal, 1, vanilla.length);
		cir.setReturnValue(withMetal);
	}

	/** Video Settings shows "Default (Metal)" so users can tell which backend Default means. Optional. */
	@ModifyReturnValue(method = "caption", at = @At("RETURN"), require = 0)
	private Component ingot$captionMetal(Component caption) {
		// The vanilla caption stays translated; "Metal" is a name.
		return MetalBackend.shouldTry((PreferredGraphicsApi) (Object) this) ? caption.copy().append(" (Metal)") : caption;
	}
}
