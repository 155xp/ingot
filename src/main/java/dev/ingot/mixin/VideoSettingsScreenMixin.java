package dev.ingot.mixin;

import dev.ingot.gui.IngotOptions;
import dev.ingot.gui.IngotSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts Ingot's power mode and a button to its settings at the top of Video Settings. Optional. */
@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("HEAD"), require = 0)
	private void ingot$addPowerMode(CallbackInfo ci) {
		if (this.list != null) {
			this.list.addSmall(
				IngotOptions.powerMode().createButton(this.options),
				Button.builder(Component.literal("Ingot Settings..."), button -> this.minecraft.gui.setScreen(new IngotSettingsScreen(this, this.options))).build()
			);
		}
	}
}
