package dev.ingot.gui;

import com.mojang.serialization.Codec;
import dev.ingot.IngotConfig;
import dev.ingot.IngotConfig.PowerMode;
import dev.ingot.IngotConfig.Resolution;
import dev.ingot.energy.PowerPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Ingot's settings as vanilla OptionInstances, so they render as ordinary option buttons. Every change goes straight
 * to IngotConfig (PowerPolicy picks it up on the next frame) and is saved.
 *
 * <p>Cycle buttons show "Caption: value" themselves, so the label functions return only the value.
 *
 * <p>Ingot ships no language files (vanilla only loads them from mods through Fabric API), so captions are English:
 * an OptionInstance caption is a translation key, and a key without a translation displays as itself.
 */
public final class IngotOptions {
	private static final IngotConfig CONFIG = IngotConfig.get();

	private IngotOptions() {
	}

	public static OptionInstance<PowerMode> powerMode() {
		return new OptionInstance<>(
			"Power Mode",
			mode -> Tooltip.create(Component.literal(switch (mode) {
				case AUTO -> "Battery mode while on battery power or in Low Power Mode, Performance mode on the charger.";
				case PERFORMANCE -> "Highest frame rate. Uses the Max Framerate from Video Settings and the performance cores.";
				case BATTERY -> "Longest battery life. Caps the frame rate (to the display's refresh rate by default) and runs "
					+ "background work on the efficiency cores.";
			})),
			(caption, mode) -> Component.literal(switch (mode) {
				case AUTO -> "Auto";
				case PERFORMANCE -> "Performance";
				case BATTERY -> "Battery";
			}),
			new OptionInstance.Enum<>(Arrays.asList(PowerMode.values()), Codec.STRING.xmap(PowerMode::valueOf, PowerMode::name)),
			CONFIG.powerMode,
			mode -> {
				CONFIG.powerMode = mode;
				CONFIG.save();
			}
		);
	}

	public static OptionInstance<Integer> batteryFps() {
		return framerate("Max FPS", IngotConfig.FPS_DISPLAY, "Display",
			"Frame cap in Battery mode. Display matches your screen's refresh rate (120 on ProMotion): every frame drawn is shown.",
			CONFIG.batteryFps, value -> CONFIG.batteryFps = value, 30, 45, 60, 90, 120);
	}

	public static OptionInstance<Integer> performanceFps() {
		return framerate("Max FPS", IngotConfig.FPS_VIDEO_SETTINGS, "Video Settings",
			"Frame cap in Performance mode. Video Settings uses Max Framerate from Video Settings (Unlimited by default).",
			CONFIG.performanceFps, value -> CONFIG.performanceFps = value, 60, 120, 144, 240);
	}

	private static OptionInstance<Integer> framerate(String caption, int special, String specialLabel, String tooltip, int current,
		IntConsumer setter, int... rates) {
		List<Integer> values = new ArrayList<>();
		values.add(special);
		for (int rate : rates) {
			values.add(rate);
		}
		if (!values.contains(current)) {
			values.add(current);
			values.sort(null);
		}
		return new OptionInstance<>(
			caption,
			OptionInstance.cachedConstantTooltip(Component.literal(tooltip)),
			(label, value) -> Component.literal(value != special ? Integer.toString(value)
				: special == IngotConfig.FPS_DISPLAY ? specialLabel + " (" + PowerPolicy.displayRefreshRate() + ")" : specialLabel),
			new OptionInstance.Enum<>(values, Codec.INT),
			current,
			value -> {
				setter.accept(value);
				CONFIG.save();
			}
		);
	}

	public static OptionInstance<Resolution> batteryResolution() {
		return resolution(CONFIG.batteryResolution, value -> CONFIG.batteryResolution = value);
	}

	public static OptionInstance<Resolution> performanceResolution() {
		return resolution(CONFIG.performanceResolution, value -> CONFIG.performanceResolution = value);
	}

	private static OptionInstance<Resolution> resolution(Resolution current, java.util.function.Consumer<Resolution> setter) {
		return new OptionInstance<>(
			"Resolution",
			OptionInstance.cachedConstantTooltip(Component.literal(
				"Half renders one pixel per point instead of the Retina display's four: about a quarter of the GPU work. "
					+ "Menus and text stay sharp; the world looks a little softer. Needs the Metal renderer.")),
			(caption, value) -> Component.literal(value == Resolution.FULL ? "Full" : "Half"),
			new OptionInstance.Enum<>(Arrays.asList(Resolution.values()), Codec.STRING.xmap(Resolution::valueOf, Resolution::name)),
			current,
			value -> {
				setter.accept(value);
				CONFIG.save();
			}
		);
	}

	public static OptionInstance<Boolean> efficiencyCores() {
		return OptionInstance.createBoolean(
			"Efficiency Cores",
			OptionInstance.cachedConstantTooltip(Component.literal(
				"Battery mode runs chunk building and world generation on the efficiency cores. New chunks appear a little "
					+ "slower; the frame rate is unaffected.")),
			CONFIG.batteryEfficiencyCores,
			value -> {
				CONFIG.batteryEfficiencyCores = value;
				CONFIG.save();
			}
		);
	}

	public static OptionInstance<Boolean> throttleWhenHidden() {
		return OptionInstance.createBoolean(
			"Pause When Hidden",
			OptionInstance.cachedConstantTooltip(Component.literal(
				"Drop to 10 fps while the game window is completely covered by other windows (the Metal renderer then skips "
					+ "presenting entirely).")),
			CONFIG.throttleWhenOccluded,
			value -> {
				CONFIG.throttleWhenOccluded = value;
				CONFIG.save();
			}
		);
	}
}
