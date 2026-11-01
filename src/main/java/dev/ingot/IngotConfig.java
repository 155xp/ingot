package dev.ingot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/ingot.properties. System properties of the same name prefixed with "ingot." override the file
 * (e.g. -Dingot.enabled=false), which is how the benchmark disables the backend.
 *
 * <p>enabled and debugLabels are read once at startup. The power settings are edited in game (Ingot settings screen)
 * and read by {@link dev.ingot.energy.PowerPolicy} every frame, so they are volatile and saved with {@link #save()}.
 */
public final class IngotConfig {
	/** Battery mode cap meaning "the display's refresh rate" (120 on ProMotion). */
	public static final int FPS_DISPLAY = 0;
	/** Performance mode cap meaning "whatever Max Framerate in Video Settings says". */
	public static final int FPS_VIDEO_SETTINGS = -1;

	/** Try the Metal backend before the vanilla backends. */
	public final boolean enabled;
	/** Keep Metal debug labels and groups (visible in Xcode GPU captures); off by default for speed. */
	public final boolean debugLabels;

	/** Which profile applies: always Performance, always Battery, or Battery only on battery power / Low Power Mode. */
	public volatile PowerMode powerMode;
	/** Battery mode frame cap: FPS_DISPLAY or a fixed rate. */
	public volatile int batteryFps;
	/** Battery mode render resolution; full by default (half is an opt-in: about 43% less GPU power at 120 fps). */
	public volatile Resolution batteryResolution;
	/** Battery mode: run chunk building and world generation on the efficiency cores. */
	public volatile boolean batteryEfficiencyCores;
	/** Performance mode frame cap: FPS_VIDEO_SETTINGS or a fixed rate. */
	public volatile int performanceFps;
	/** Performance mode render resolution. */
	public volatile Resolution performanceResolution;
	/** Throttle to the background frame rate while the window is fully covered by other windows. */
	public volatile boolean throttleWhenOccluded;

	public enum PowerMode {
		AUTO,
		PERFORMANCE,
		BATTERY
	}

	/** Render resolution relative to the window's Retina pixels. HALF renders one pixel per point. */
	public enum Resolution {
		FULL(1),
		HALF(2);

		/** Window pixels per rendered pixel along each axis. */
		public final int divisor;

		Resolution(int divisor) {
			this.divisor = divisor;
		}
	}

	private static IngotConfig instance;

	private final Path file;

	private IngotConfig(Path file, Properties props) {
		this.file = file;
		this.enabled = Boolean.parseBoolean(get(props, "enabled", "true"));
		this.debugLabels = Boolean.parseBoolean(get(props, "debugLabels", "false"));
		this.powerMode = parsePowerMode(props);
		this.batteryFps = Integer.parseInt(get(props, "batteryFps", Integer.toString(FPS_DISPLAY)));
		this.batteryResolution = parseEnum(Resolution.class, get(props, "batteryResolution", "full"));
		this.batteryEfficiencyCores = Boolean.parseBoolean(get(props, "batteryEfficiencyCores", "true"));
		this.performanceFps = Integer.parseInt(get(props, "performanceFps", Integer.toString(FPS_VIDEO_SETTINGS)));
		this.performanceResolution = parseEnum(Resolution.class, get(props, "performanceResolution", "full"));
		this.throttleWhenOccluded = Boolean.parseBoolean(get(props, "throttleWhenOccluded", "true"));
	}

	/** powerMode, or the batterySaver key it replaced (auto, always = battery, never = performance). */
	private static PowerMode parsePowerMode(Properties props) {
		String mode = get(props, "powerMode", null);
		if (mode != null) {
			return parseEnum(PowerMode.class, mode);
		}
		return switch (get(props, "batterySaver", "auto").toLowerCase(Locale.ROOT)) {
			case "always" -> PowerMode.BATTERY;
			case "never" -> PowerMode.PERFORMANCE;
			case "auto" -> PowerMode.AUTO;
			default -> throw new IllegalArgumentException("batterySaver must be auto, always or never");
		};
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
		return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
	}

	private static String get(Properties props, String key, String fallback) {
		String override = System.getProperty("ingot." + key);
		if (override != null) {
			return override.trim();
		}
		String value = props.getProperty(key);
		return value != null ? value.trim() : fallback;
	}

	public static synchronized IngotConfig get() {
		if (instance == null) {
			instance = load(FabricLoader.getInstance().getConfigDir().resolve("ingot.properties"));
		}
		return instance;
	}

	private static IngotConfig load(Path file) {
		Properties props = new Properties();
		boolean exists = Files.isRegularFile(file);
		if (exists) {
			try (Reader reader = Files.newBufferedReader(file)) {
				props.load(reader);
			} catch (IOException e) {
				Ingot.LOGGER.warn("Ingot: could not read {}, using defaults", file, e);
			}
		}
		IngotConfig config;
		try {
			config = new IngotConfig(file, props);
		} catch (IllegalArgumentException e) {
			Ingot.LOGGER.warn("Ingot: invalid value in {}, using defaults", file, e);
			config = new IngotConfig(file, new Properties());
		}
		if (!exists) {
			config.save();
		}
		return config;
	}

	/** Writes every setting (atomically) to config/ingot.properties. */
	public synchronized void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(this.enabled));
		props.setProperty("debugLabels", Boolean.toString(this.debugLabels));
		props.setProperty("powerMode", this.powerMode.name().toLowerCase(Locale.ROOT));
		props.setProperty("batteryFps", Integer.toString(this.batteryFps));
		props.setProperty("batteryResolution", this.batteryResolution.name().toLowerCase(Locale.ROOT));
		props.setProperty("batteryEfficiencyCores", Boolean.toString(this.batteryEfficiencyCores));
		props.setProperty("performanceFps", Integer.toString(this.performanceFps));
		props.setProperty("performanceResolution", this.performanceResolution.name().toLowerCase(Locale.ROOT));
		props.setProperty("throttleWhenOccluded", Boolean.toString(this.throttleWhenOccluded));
		try {
			Files.createDirectories(this.file.getParent());
			Path tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				props.store(writer, "Ingot (Metal renderer). enabled=false falls back to the vanilla OpenGL/Vulkan backends. "
					+ "powerMode: auto (battery mode on battery power), performance or battery. batteryFps 0 = display refresh rate; "
					+ "performanceFps -1 = Max Framerate from Video Settings.");
			}
			Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Ingot.LOGGER.warn("Ingot: could not write {}", this.file, e);
		}
	}
}
