package dev.ingot.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.Window;
import dev.ingot.energy.RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Render resolution (RenderScale): every place Window learns its size in pixels reports the scaled size, and a fixed
 * GUI scale shrinks with it. Points (window size, mouse coordinates) are untouched. Optional: without these hooks
 * Minecraft renders at full resolution.
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@ModifyReturnValue(method = "queryFramebufferSize", at = @At("RETURN"), require = 0)
	private Window.FramebufferSize ingot$scaleFramebuffer(Window.FramebufferSize size) {
		return RenderScale.divisor() == 1 ? size : new Window.FramebufferSize(RenderScale.scale(size.width()), RenderScale.scale(size.height()));
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
	private int ingot$scaleResizeWidth(int width) {
		return RenderScale.scale(width);
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
	private int ingot$scaleResizeHeight(int height) {
		return RenderScale.scale(height);
	}

	@ModifyVariable(method = "calculateScale", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
	private int ingot$scaleGuiScale(int maxScale) {
		return RenderScale.scaleGuiScale(maxScale);
	}

	@ModifyReturnValue(method = "getPixelDensity", at = @At("RETURN"), require = 0)
	private float ingot$scalePixelDensity(float density) {
		return density / RenderScale.divisor();
	}
}
