package dev.ingot.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.ingot.energy.RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render resolution (RenderScale): every place Window learns its size in pixels reports the scaled size, and a fixed
 * GUI scale shrinks with it. Points (window size, mouse coordinates) are untouched. Optional: without these hooks
 * Minecraft renders at full resolution.
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@Shadow
	private int framebufferWidth;
	@Shadow
	private int framebufferHeight;

	@Inject(method = "refreshFramebufferSize", at = @At("TAIL"), require = 0)
	private void ingot$scaleFramebuffer(CallbackInfo ci) {
		this.framebufferWidth = RenderScale.scale(this.framebufferWidth);
		this.framebufferHeight = RenderScale.scale(this.framebufferHeight);
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
}
