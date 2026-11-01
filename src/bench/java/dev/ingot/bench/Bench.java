package dev.ingot.bench;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static entry points for the mixins. Everything here is a no-op unless {@code -Dingot.bench.scenario} is set; in that
 * case the mixin plugin does not even apply the hooks (see {@link dev.ingot.bench.mixin.BenchMixinPlugin}).
 */
public final class Bench {
	public static final Logger LOGGER = LoggerFactory.getLogger("ingot-bench");
	public static final boolean ACTIVE = System.getProperty("ingot.bench.scenario") != null;
	/** Version of the result JSON; bump it when a change makes new results incomparable with older ones. */
	public static final int RESULT_SCHEMA = 4;

	private static BenchSettings settings;
	private static Controller controller;

	private Bench() {
	}

	/** Called once from the client entrypoint. */
	static synchronized void init() {
		if (!ACTIVE || settings != null) {
			return;
		}
		try {
			settings = BenchSettings.fromSystemProperties();
		} catch (Exception e) {
			LOGGER.error("[bench] invalid benchmark settings", e);
			Watchdog.failAndHalt(null, "invalid benchmark settings: " + e);
			return;
		}
		controller = settings.mode() == BenchSettings.Mode.PREPARE ? new PrepareController(settings) : new RunController(settings);
		LOGGER.info("[bench] active: mode={} scenario={} config={} duration={}s", settings.mode(), settings.scenario().name(),
			settings.configName(), settings.durationSeconds());
		Watchdog.start(settings);
	}

	public static BenchSettings settings() {
		return settings;
	}

	/** Top of {@code Minecraft.runTick}: one call per iteration of the client main loop. */
	public static void onFrameBoundary(Minecraft minecraft) {
		Controller c = controller;
		if (c != null) {
			c.onFrameBoundary(minecraft, System.nanoTime());
		}
	}

	/** Top of {@code Minecraft.renderFrame}: after the client ticks of this frame, before the camera is extracted. */
	public static void beforeRender(Minecraft minecraft) {
		Controller c = controller;
		if (c != null) {
			c.beforeRender(minecraft, System.nanoTime());
		}
	}

	/** End of {@code MinecraftServer.tickServer} (integrated server thread). */
	public static void onServerTick(MinecraftServer server) {
		Controller c = controller;
		if (c != null) {
			c.onServerTick(server);
		}
	}

	/** Mob AI is frozen in benchmark worlds so every configuration renders the same entity poses. */
	public static boolean freezeMobs() {
		BenchSettings s = settings;
		return s != null && s.freezeMobs();
	}

	interface Controller {
		void onFrameBoundary(Minecraft minecraft, long now);

		void beforeRender(Minecraft minecraft, long now);

		default void onServerTick(MinecraftServer server) {
		}
	}
}
