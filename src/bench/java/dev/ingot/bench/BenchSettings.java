package dev.ingot.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Everything the harness is told through system properties (bench/run.sh writes them into a .properties file that the
 * Gradle "bench" run turns into -D flags).
 *
 * <pre>
 * ingot.bench.scenario       scenario name; setting it is what activates the harness
 * ingot.bench.scenarioFile   path of the scenario JSON (default: &lt;gameDir&gt;/bench-scenario.json)
 * ingot.bench.mode           run (default) | prepare
 * ingot.bench.duration       measured seconds (overrides the scenario)
 * ingot.bench.warmup         warmup seconds (overrides the scenario)
 * ingot.bench.settleTimeout  max seconds to wait for chunk loading/meshing to settle (overrides the scenario)
 * ingot.bench.out            result JSON path (default: &lt;gameDir&gt;/bench-result.json)
 * ingot.bench.config         configuration label written into the result (vanilla, mainstream, ingot, ...)
 * ingot.bench.expectBackend  case-insensitive substring the GPU backend name must contain, else the result is invalid
 * ingot.bench.rejectBackend  case-insensitive substring the GPU backend name must NOT contain (Ingot leaking into a
 *                            configuration where it is disabled), else the result is invalid
 * ingot.bench.windowWidth    logical window width the run was launched with; a different size makes the result invalid
 * ingot.bench.windowHeight   logical window height, likewise
 * ingot.bench.world          save folder name (default: bench)
 * ingot.bench.freezeMobs     cancel mob AI so entities stand still (default: true)
 * ingot.bench.survey         prepare only: log a biome survey around the world spawn instead of generating
 * ingot.bench.recordFrames   include every frame time in the result JSON (default: true)
 * ingot.bench.fpsCap         Max Framerate for the run: 0 (default) = Unlimited (260), or a multiple of 10 from 10 to
 *                            250 like the video settings slider (bench/run.sh --fps-cap), with vsync off either way; the
 *                            result is invalid if the game runs with another limit
 * </pre>
 */
public record BenchSettings(
	Mode mode,
	Scenario scenario,
	Path scenarioFile,
	double durationSeconds,
	double warmupSeconds,
	double settleTimeoutSeconds,
	Path outFile,
	String configName,
	String expectBackend,
	String rejectBackend,
	int windowWidth,
	int windowHeight,
	String worldName,
	boolean freezeMobs,
	boolean survey,
	boolean recordFrames,
	int fpsCap
) {
	/** options.framerateLimit value that means "Unlimited" (the top of the Max Framerate slider). */
	public static final int UNLIMITED_FPS = 260;

	public enum Mode {
		RUN,
		PREPARE
	}

	static BenchSettings fromSystemProperties() throws Exception {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		String name = System.getProperty("ingot.bench.scenario");
		Path scenarioFile = Path.of(prop("scenarioFile", gameDir.resolve("bench-scenario.json").toString())).toAbsolutePath();
		if (!Files.isRegularFile(scenarioFile)) {
			throw new IllegalArgumentException("scenario file not found: " + scenarioFile);
		}
		Scenario scenario = Scenario.load(scenarioFile);
		if (!scenario.name().equals(name)) {
			Bench.LOGGER.warn("[bench] scenario property '{}' does not match the scenario file name '{}'", name, scenario.name());
		}
		Mode mode = Mode.valueOf(prop("mode", "run").toUpperCase(Locale.ROOT));
		double duration = Double.parseDouble(prop("duration", Double.toString(scenario.durationSeconds())));
		double warmup = Double.parseDouble(prop("warmup", Double.toString(scenario.warmupSeconds())));
		double settle = Double.parseDouble(prop("settleTimeout", Double.toString(scenario.settleTimeoutSeconds())));
		if (duration <= 0 || warmup < 0 || settle <= 0) {
			throw new IllegalArgumentException("duration/warmup/settleTimeout out of range");
		}
		Path out = Path.of(prop("out", gameDir.resolve("bench-result.json").toString())).toAbsolutePath();
		int fpsCap = Integer.parseInt(prop("fpsCap", "0"));
		// The option only holds multiples of 10 (OptionInstance.set rounds anything else down).
		if (fpsCap != 0 && (fpsCap < 10 || fpsCap >= UNLIMITED_FPS || fpsCap % 10 != 0)) {
			throw new IllegalArgumentException("fpsCap must be 0 (unlimited) or a multiple of 10 from 10 to 250, got " + fpsCap);
		}
		String expect = prop("expectBackend", "").trim();
		String reject = prop("rejectBackend", "").trim();
		return new BenchSettings(
			mode,
			scenario,
			scenarioFile,
			duration,
			warmup,
			settle,
			out,
			prop("config", "unknown"),
			expect.isEmpty() ? null : expect,
			reject.isEmpty() ? null : reject,
			Integer.parseInt(prop("windowWidth", "0")),
			Integer.parseInt(prop("windowHeight", "0")),
			prop("world", "bench"),
			Boolean.parseBoolean(prop("freezeMobs", "true")),
			Boolean.parseBoolean(prop("survey", "false")),
			Boolean.parseBoolean(prop("recordFrames", "true")),
			fpsCap
		);
	}

	/** The Max Framerate option the run must use: the cap, or 260 (Unlimited). */
	public int framerateLimit() {
		return this.fpsCap > 0 ? this.fpsCap : UNLIMITED_FPS;
	}

	private static String prop(String key, String fallback) {
		String value = System.getProperty("ingot.bench." + key);
		return value == null || value.isBlank() ? fallback : value;
	}
}
