package dev.ingot.gui;

import dev.ingot.IngotConfig;
import dev.ingot.backend.MetalSurface;
import dev.ingot.energy.PowerPolicy;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Ingot's settings: the power mode and what each mode does. Opened from Video Settings. */
public final class IngotSettingsScreen extends OptionsSubScreen {
	private static final Component TITLE = Component.literal("Ingot Settings");
	private static final Component BATTERY_HEADER = header("Battery Mode");
	private static final Component PERFORMANCE_HEADER = header("Performance Mode");
	private static final Component GENERAL_HEADER = header("General");

	private final LinearLayout header = LinearLayout.vertical().spacing(2);
	private @Nullable StringWidget status;

	public IngotSettingsScreen(Screen lastScreen, Options options) {
		super(lastScreen, options, TITLE);
	}

	private static Component header(String text) {
		return Component.literal(text).withStyle(ChatFormatting.UNDERLINE, ChatFormatting.BOLD);
	}

	@Override
	protected void addTitle() {
		this.header.defaultCellSetting().alignHorizontallyCenter().alignVerticallyMiddle();
		this.header.addChild(new StringWidget(this.title, this.font));
		this.status = this.header.addChild(new StringWidget(status(), this.font));
		this.layout.addToHeader(this.header);
	}

	@Override
	protected void addOptions() {
		this.list.addBig(IngotOptions.powerMode());
		this.list.addHeader(BATTERY_HEADER);
		this.list.addSmall(IngotOptions.batteryFps(), IngotOptions.batteryResolution());
		this.list.addSmall(IngotOptions.efficiencyCores());
		this.list.addHeader(PERFORMANCE_HEADER);
		this.list.addSmall(IngotOptions.performanceFps(), IngotOptions.performanceResolution());
		this.list.addHeader(GENERAL_HEADER);
		this.list.addSmall(IngotOptions.throttleWhenHidden());
	}

	@Override
	public void tick() {
		if (this.status != null) {
			Component text = status();
			if (!text.equals(this.status.getMessage())) {
				this.status.setMessage(text);
				this.repositionElements();
			}
		}
		super.tick();
	}

	/** What applies right now and why, e.g. "Now: Battery mode (on battery power)". */
	private static Component status() {
		IngotConfig config = IngotConfig.get();
		String mode = PowerPolicy.isBatteryMode() ? "Battery mode" : "Performance mode";
		String reason = switch (config.powerMode) {
			case AUTO -> PowerPolicy.isOnBatteryOrLowPower() ? " (on battery power)" : " (plugged in)";
			case PERFORMANCE, BATTERY -> "";
		};
		Component line = Component.literal("Now: " + mode + reason).withStyle(ChatFormatting.GRAY);
		return MetalSurface.current() != null
			? line
			: Component.empty().append(line).append(Component.literal("  Metal renderer off: Resolution has no effect").withStyle(ChatFormatting.YELLOW));
	}
}
