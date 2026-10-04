package dev.ingot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/ingot.properties. System properties of the same name prefixed with "ingot." override the file
 * (e.g. -Dingot.enabled=false), which is how the benchmark disables the backend.
 */
public final class IngotConfig {
	/** Try the Metal backend before the vanilla backends. */
	public final boolean enabled;
	/** Keep Metal debug labels and groups (visible in Xcode GPU captures); off by default for speed. */
	public final boolean debugLabels;
	/** On battery / Low Power Mode: cap "Unlimited" FPS to the display refresh rate. auto, always or never. */
	public final BatterySaver batterySaver;
	/** Throttle to the background frame rate while the window is fully covered by other windows. */
	public final boolean throttleWhenOccluded;

	public enum BatterySaver {
		AUTO,
		ALWAYS,
		NEVER
	}

	private static IngotConfig instance;

	private IngotConfig(Properties props) {
		this.enabled = Boolean.parseBoolean(get(props, "enabled", "true"));
		this.debugLabels = Boolean.parseBoolean(get(props, "debugLabels", "false"));
		this.batterySaver = BatterySaver.valueOf(get(props, "batterySaver", "auto").toUpperCase(java.util.Locale.ROOT));
		this.throttleWhenOccluded = Boolean.parseBoolean(get(props, "throttleWhenOccluded", "true"));
	}

	private static String get(Properties props, String key, String fallback) {
		String override = System.getProperty("ingot." + key);
		return override != null ? override : props.getProperty(key, fallback).trim();
	}

	public static synchronized IngotConfig get() {
		if (instance == null) {
			instance = load(FabricLoader.getInstance().getConfigDir().resolve("ingot.properties"));
		}
		return instance;
	}

	private static IngotConfig load(Path file) {
		Properties props = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				props.load(reader);
			} catch (IOException e) {
				Ingot.LOGGER.warn("Ingot: could not read {}, using defaults", file, e);
			}
		} else {
			props.setProperty("enabled", "true");
			props.setProperty("debugLabels", "false");
			props.setProperty("batterySaver", "auto");
			props.setProperty("throttleWhenOccluded", "true");
			try {
				Files.createDirectories(file.getParent());
				try (Writer writer = Files.newBufferedWriter(file)) {
					props.store(writer, "Ingot (Metal renderer). enabled=false falls back to the vanilla OpenGL/Vulkan backends.");
				}
			} catch (IOException e) {
				Ingot.LOGGER.warn("Ingot: could not write default {}", file, e);
			}
		}
		try {
			return new IngotConfig(props);
		} catch (IllegalArgumentException e) {
			Ingot.LOGGER.warn("Ingot: invalid value in {}, using defaults", file, e);
			return new IngotConfig(new Properties());
		}
	}
}
