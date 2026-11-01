package dev.ingot.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Measurement run: open the prepared world, wait for chunks to load and mesh, warm up with one full turn, wait again,
 * then follow the scenario path for the configured duration while recording the wall time of every frame.
 */
final class RunController implements Bench.Controller {
	private enum State {
		BOOT,
		LOADING,
		SETTLE_LOAD,
		WARMUP,
		SETTLE_WARM,
		MEASURE,
		DONE
	}

	private static final double LOAD_TIMEOUT_SECONDS = 240;
	private static final double SETTLE_STABLE_SECONDS = 3.0;

	private final BenchSettings settings;
	private final Scenario scenario;
	private State state = State.BOOT;
	private long stateStart;
	private final long processStart = System.nanoTime();
	private final SettleProbe probe = new SettleProbe(SETTLE_STABLE_SECONDS);

	private double[] frameMs = new double[1 << 16];
	private int[] framePhase = new int[1 << 16];
	private int frames;
	private long measureStart;
	private long lastFrame;
	private long measureEnd;
	private int throttledFrames;
	private int unfocusedFrames;
	private int screenInterruptions;
	private JsonObject gcBefore;
	private long jitBefore;
	private long cpuBefore;
	private long renderCpuBefore;
	// One entry per second of the measurement: {seconds since start, frames, render thread busy, process CPU cores}.
	private final List<double[]> timeline = new ArrayList<>();
	private long bucketStart;
	private int bucketFrames;
	private long bucketRenderCpu;
	private long bucketProcessCpu;
	private double busyCoreSeconds;
	private DisplayState.State displayAtStart;
	private long measureStartEpochMs;
	private long measureEndEpochMs;

	private double loadSeconds;
	private double settleLoadSeconds;
	private boolean settleLoadTimedOut;
	private double settleWarmSeconds;
	private boolean settleWarmTimedOut;
	private SettleProbe.Snapshot settledSnapshot;
	private final List<String> warnings = new ArrayList<>();
	private JsonObject template;

	RunController(BenchSettings settings) {
		this.settings = settings;
		this.scenario = settings.scenario();
	}

	private void enter(State next, long now) {
		Bench.LOGGER.info("[bench] {} -> {} ({} s since launch)", this.state, next,
			String.format(Locale.ROOT, "%.1f", (now - this.processStart) / 1e9));
		this.state = next;
		this.stateStart = now;
		this.probe.reset();
		if (next == State.SETTLE_WARM) {
			// Tells bench/benchlib.py's sampler to take its "before" snapshot now, while nothing is being timed.
			try {
				Files.writeString(this.settings.outFile().resolveSibling("bench-phase"), "settle-warm\n");
			} catch (IOException e) {
				Bench.LOGGER.warn("[bench] could not write the phase marker", e);
			}
		}
	}

	private double inState(long now) {
		return (now - this.stateStart) / 1e9;
	}

	@Override
	public void onFrameBoundary(Minecraft minecraft, long now) {
		switch (this.state) {
			case BOOT -> {
				if (minecraft.isGameLoadFinished() && minecraft.gui.overlay() == null) {
					enforceOptions(minecraft, this.settings.framerateLimit());
					if (!minecraft.getLevelSource().levelExists(this.settings.worldName())) {
						Watchdog.failAndHalt(this.settings, "world '" + this.settings.worldName() + "' not found in saves/ (run bench/run.sh prepare first)");
						return;
					}
					this.template = readTemplate(minecraft);
					enter(State.LOADING, now);
					minecraft.createWorldOpenFlows().openWorld(this.settings.worldName(),
						() -> Watchdog.failAndHalt(this.settings, "opening the world was cancelled"));
				}
			}
			case LOADING -> {
				if (minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null) {
					this.loadSeconds = inState(now);
					enter(State.SETTLE_LOAD, now);
					// No autosave (chunk serialization on the server thread) can land inside the measurement.
					var server = minecraft.getSingleplayerServer();
					if (server != null) {
						server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "save-off"));
					}
				} else if (inState(now) > LOAD_TIMEOUT_SECONDS) {
					Watchdog.failAndHalt(this.settings, "world did not finish loading within " + LOAD_TIMEOUT_SECONDS + " s");
				}
			}
			case SETTLE_LOAD -> {
				closeScreens(minecraft, false);
				boolean settled = inState(now) > 2.0 && this.probe.poll(minecraft, now);
				boolean timedOut = inState(now) > this.settings.settleTimeoutSeconds();
				if (settled || timedOut) {
					this.settleLoadSeconds = inState(now);
					this.settleLoadTimedOut = !settled;
					logSettle("load", settled);
					enter(this.settings.warmupSeconds() > 0 ? State.WARMUP : State.SETTLE_WARM, now);
				}
			}
			case WARMUP -> {
				closeScreens(minecraft, false);
				if (inState(now) >= this.settings.warmupSeconds()) {
					enter(State.SETTLE_WARM, now);
				}
			}
			case SETTLE_WARM -> {
				closeScreens(minecraft, false);
				boolean settled = inState(now) > 0.5 && this.probe.poll(minecraft, now);
				boolean timedOut = inState(now) > this.settings.settleTimeoutSeconds();
				if (settled || timedOut) {
					this.settleWarmSeconds = inState(now);
					this.settleWarmTimedOut = !settled;
					this.settledSnapshot = this.probe.last();
					logSettle("warmup", settled);
					enter(State.MEASURE, now);
					// -Dingot.bench.debugOverlay=true shows the F3 overlay while measuring (for screenshots, not numbers).
					if (Boolean.getBoolean("ingot.bench.debugOverlay")) {
						minecraft.debugEntries.setOverlayVisible(true);
					}
					this.displayAtStart = DisplayState.query();
					this.gcBefore = EnvInfo.gcDelta(null);
					this.jitBefore = EnvInfo.jitMillis();
					this.cpuBefore = EnvInfo.processCpuNanos();
					this.renderCpuBefore = EnvInfo.currentThreadCpuNanos();
					this.measureStartEpochMs = System.currentTimeMillis();
					// Start the clock after the bookkeeping above so the first measured frame does not include it.
					this.measureStart = System.nanoTime();
					this.lastFrame = this.measureStart;
					this.bucketStart = this.measureStart;
					this.bucketRenderCpu = this.renderCpuBefore;
					this.bucketProcessCpu = this.cpuBefore;
					EnvInfo.systemCpuLoad(); // resets the system-wide CPU load interval to the start of the measurement
				}
			}
			case MEASURE -> {
				record(minecraft, now);
				if ((now - this.measureStart) / 1e9 >= this.settings.durationSeconds()) {
					this.measureEnd = now;
					this.measureEndEpochMs = System.currentTimeMillis();
					finish(minecraft);
				}
			}
			case DONE -> {
			}
		}
	}

	private void record(Minecraft minecraft, long now) {
		if (this.frames == this.frameMs.length) {
			this.frameMs = Arrays.copyOf(this.frameMs, this.frames * 2);
			this.framePhase = Arrays.copyOf(this.framePhase, this.frames * 2);
		}
		double fraction = (this.lastFrame - this.measureStart) / 1e9 / this.settings.durationSeconds();
		this.frameMs[this.frames] = (now - this.lastFrame) / 1e6;
		this.framePhase[this.frames] = this.scenario.phaseAt(fraction);
		this.frames++;
		this.lastFrame = now;
		this.bucketFrames++;
		if (now - this.bucketStart >= 1_000_000_000L) {
			closeBucket(now);
		}
		// A throttle reason (window iconified, AFK, ...) or a lower effective limit than the option asks for (a battery
		// saver, such as Ingot's Battery mode cap below the run's) means this frame was not paced the way the run asked.
		FramerateLimitTracker tracker = minecraft.getFramerateLimitTracker();
		if (tracker.getThrottleReason() != FramerateLimitTracker.FramerateThrottleReason.NONE
			|| tracker.getFramerateLimit() != this.settings.framerateLimit()) {
			this.throttledFrames++;
		}
		if (!minecraft.getWindow().isFocused()) {
			this.unfocusedFrames++;
		}
		closeScreens(minecraft, true);
	}

	/**
	 * Ends one timeline bucket: FPS, render thread busy share, this process's CPU cores and the CPU cores everything
	 * else on the Mac used (system-wide busy cores from the host CPU ticks minus this process). All in-process calls of
	 * a few microseconds; nothing outside the game is touched while frames are timed.
	 */
	private void closeBucket(long now) {
		long render = EnvInfo.currentThreadCpuNanos();
		long process = EnvInfo.processCpuNanos();
		double load = EnvInfo.systemCpuLoad();
		double wall = (now - this.bucketStart) / 1e9;
		if (wall <= 0) {
			return;
		}
		double processCores = process < 0 ? -1 : (process - this.bucketProcessCpu) / 1e9 / wall;
		double busyCores = load < 0 ? -1 : load * Runtime.getRuntime().availableProcessors();
		if (busyCores >= 0) {
			this.busyCoreSeconds += busyCores * wall;
		} else {
			this.busyCoreSeconds = Double.NaN;
		}
		this.timeline.add(new double[] {
			FrameStats.round((this.bucketStart - this.measureStart) / 1e9, 2),
			FrameStats.round(this.bucketFrames / wall, 1),
			render < 0 ? -1 : FrameStats.round((render - this.bucketRenderCpu) / 1e9 / wall, 3),
			FrameStats.round(processCores, 2),
			busyCores < 0 || processCores < 0 ? -1 : FrameStats.round(Math.max(0, busyCores - processCores), 2)
		});
		this.bucketStart = now;
		this.bucketFrames = 0;
		this.bucketRenderCpu = render;
		this.bucketProcessCpu = process;
	}

	@Override
	public void beforeRender(Minecraft minecraft, long now) {
		Scenario.Pose pose = switch (this.state) {
			case SETTLE_LOAD, SETTLE_WARM -> this.scenario.start();
			case WARMUP -> this.scenario.warmupPoseAt(inState(now) / this.settings.warmupSeconds());
			case MEASURE -> this.scenario.poseAt((now - this.measureStart) / 1e9 / this.settings.durationSeconds());
			default -> null;
		};
		if (pose != null) {
			applyPose(minecraft, pose);
		}
	}

	static void applyPose(Minecraft minecraft, Scenario.Pose pose) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		// snapTo also sets the previous position/rotation, so the camera does not interpolate between ticks.
		player.snapTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
		player.setDeltaMovement(0.0, 0.0, 0.0);
		player.setYHeadRot(pose.yaw());
		player.yHeadRotO = pose.yaw();
	}

	/** The ingot-bench-world.json that prepare stored next to level.dat (template id, creation time). */
	private JsonObject readTemplate(Minecraft minecraft) {
		Path meta = minecraft.getLevelSource().getBaseDir().resolve(this.settings.worldName()).resolve("ingot-bench-world.json");
		try {
			return JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			this.warnings.add("world template has no readable ingot-bench-world.json");
			return null;
		}
	}

	/** Remove anything that throttles or pauses the frame loop. options.txt already sets these; mods may not agree. */
	static void enforceOptions(Minecraft minecraft) {
		enforceOptions(minecraft, BenchSettings.UNLIMITED_FPS);
	}

	/**
	 * Same, with the Max Framerate the run asks for: 260 (Unlimited) or the --fps-cap value. Vsync stays off in both
	 * cases, so a capped run is paced only by the game's frame limiter (vanilla's, or the replacement of a mod such as
	 * Ingot's, which is part of what is measured).
	 */
	static void enforceOptions(Minecraft minecraft, int framerateLimit) {
		var o = minecraft.options;
		if (o.enableVsync().get()) {
			o.enableVsync().set(false);
		}
		if (o.framerateLimit().get() != framerateLimit) {
			o.framerateLimit().set(framerateLimit);
		}
		if (o.inactivityFpsLimit().get() != InactivityFpsLimit.MINIMIZED) {
			o.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		}
		o.pauseOnLostFocus = false;
		minecraft.getFramerateLimitTracker().setFramerateLimit(o.framerateLimit().get());
	}

	private void closeScreens(Minecraft minecraft, boolean count) {
		if (minecraft.gui.screen() != null) {
			Bench.LOGGER.warn("[bench] closing screen {} during the benchmark", minecraft.gui.screen().getClass().getName());
			minecraft.gui.setScreen(null);
			if (count) {
				this.screenInterruptions++;
			}
		}
	}

	private void logSettle(String which, boolean settled) {
		SettleProbe.Snapshot s = this.probe.last();
		Bench.LOGGER.info("[bench] settle after {}: {} in {} s ({})", which, settled ? "settled" : "TIMED OUT",
			String.format(Locale.ROOT, "%.1f", which.equals("load") ? this.settleLoadSeconds : this.settleWarmSeconds),
			s == null ? "no snapshot" : s.toJson());
		if (!settled) {
			this.warnings.add("settle after " + which + " timed out; chunk meshing may overlap the measurement");
		}
	}

	private void finish(Minecraft minecraft) {
		this.state = State.DONE;
		JsonObject result = new JsonObject();
		List<String> invalid = new ArrayList<>();

		// Snapshot the counters first, before building the result does any work of its own.
		if (this.measureEnd > this.bucketStart) {
			closeBucket(this.measureEnd);
		}
		long jitMs = this.jitBefore < 0 ? -1 : EnvInfo.jitMillis() - this.jitBefore;
		long cpuNs = this.cpuBefore < 0 ? -1 : EnvInfo.processCpuNanos() - this.cpuBefore;
		long renderCpuNs = this.renderCpuBefore < 0 ? -1 : EnvInfo.currentThreadCpuNanos() - this.renderCpuBefore;
		JsonObject gcDuring = EnvInfo.gcDelta(this.gcBefore);
		double measuredSeconds = (this.measureEnd - this.measureStart) / 1e9;
		DisplayState.State displayAtEnd = DisplayState.query();
		for (DisplayState.State state : new DisplayState.State[] {this.displayAtStart, displayAtEnd}) {
			if (state != null && !state.visible()) {
				invalid.add("the game window was not on screen (" + state.describe() + "): without compositing, frames are "
					+ "not comparable with a visible window; keep the Mac unlocked with the display on");
				break;
			}
		}

		String backend = EnvInfo.backendName();
		String backendLower = backend.toLowerCase(Locale.ROOT);
		// Backend name only: on macOS the OpenGL driver string also mentions Metal.
		if (this.settings.expectBackend() != null && !backendLower.contains(this.settings.expectBackend().toLowerCase(Locale.ROOT))) {
			invalid.add("expected backend containing '" + this.settings.expectBackend() + "' but got '" + backend + "'");
		}
		if (this.settings.rejectBackend() != null && backendLower.contains(this.settings.rejectBackend().toLowerCase(Locale.ROOT))) {
			invalid.add("backend '" + backend + "' must not be used by this configuration (Ingot was supposed to be disabled)");
		}
		var window = minecraft.getWindow();
		if (this.settings.windowWidth() > 0 && this.settings.windowHeight() > 0
			&& (window.getScreenWidth() != this.settings.windowWidth() || window.getScreenHeight() != this.settings.windowHeight())) {
			invalid.add("window is " + window.getScreenWidth() + "x" + window.getScreenHeight() + " but the run asked for "
				+ this.settings.windowWidth() + "x" + this.settings.windowHeight() + " (macOS shrinks windows that do not fit the screen)");
		}
		var o = minecraft.options;
		if (o.renderDistance().get() != this.scenario.renderDistance()) {
			invalid.add("render distance " + o.renderDistance().get() + " != scenario " + this.scenario.renderDistance());
		}
		if (o.enableVsync().get()) {
			invalid.add("vsync was on");
		}
		if (o.framerateLimit().get() != this.settings.framerateLimit()) {
			invalid.add("framerate limit " + o.framerateLimit().get() + " but the run asked for " + this.settings.framerateLimit()
				+ (this.settings.fpsCap() > 0 ? "" : " (Unlimited)"));
		}
		// Which limiter paced the frames (it only acts when capped). Ingot's replacement limiter is part of Ingot, so
		// a capped Ingot run must use it, and a run with Ingot disabled must not.
		String frameLimiter = EnvInfo.frameLimiter();
		boolean ingotEnabled = Boolean.parseBoolean(System.getProperty("ingot.enabled", "false"));
		if (this.settings.fpsCap() > 0 && ingotEnabled != frameLimiter.startsWith("ingot")) {
			invalid.add("the frames were paced by the " + frameLimiter + " frame limiter, but Ingot is "
				+ (ingotEnabled ? "enabled" : "disabled") + " in this configuration");
		}
		if (this.throttledFrames > this.frames / 100) {
			invalid.add(this.throttledFrames + " frames were throttled by the framerate limit tracker or ran under a lower effective limit");
		} else if (this.throttledFrames > 0) {
			this.warnings.add(this.throttledFrames + " frames were throttled by the framerate limit tracker or ran under a lower effective limit");
		}
		if (this.unfocusedFrames > 0) {
			this.warnings.add(this.unfocusedFrames + " of " + this.frames + " frames ran while the window was unfocused");
		}
		if (this.screenInterruptions > 0) {
			invalid.add(this.screenInterruptions + " screens opened during the measurement");
		}
		// Catches worlds that never streamed in around the camera (e.g. a spectator that does not load chunks).
		int expectedChunks = (int) (Math.PI * this.scenario.renderDistance() * this.scenario.renderDistance());
		int loaded = this.settledSnapshot == null ? 0 : this.settledSnapshot.loadedChunks();
		if (loaded < expectedChunks * 0.6) {
			invalid.add("only " + loaded + " chunks were loaded around the camera (expected about " + expectedChunks + ")");
		}
		if (this.frames < 10) {
			invalid.add("only " + this.frames + " frames measured");
		}
		// A capped run compares energy at equal frame rate. A configuration that cannot hold the cap renders fewer
		// frames, so its watts are not comparable (its energy per frame still is); say so instead of hiding it.
		double avgFps = measuredSeconds > 0 ? this.frames / measuredSeconds : 0;
		if (this.settings.fpsCap() > 0 && avgFps < 0.97 * this.settings.fpsCap()) {
			this.warnings.add(String.format(Locale.ROOT, "did not hold the %d FPS cap (avg %.1f FPS)", this.settings.fpsCap(), avgFps));
		}
		// Uncapped with vsync off, frames should not wait for the display. A run whose average and median both sit on the
		// refresh interval was paced by presentation (a backend that still syncs to the display, or a full swap chain),
		// so its frame rate and energy measure the display, not the renderer.
		FrameStats all = FrameStats.of(this.frameMs, 0, this.frames);
		int refreshHz = EnvInfo.refreshRate(minecraft);
		if (this.settings.fpsCap() == 0 && refreshHz > 0 && this.frames > 0 && Math.abs(avgFps / refreshHz - 1) < 0.02
			&& Math.abs(all.p50Ms() * refreshHz / 1000.0 - 1) < 0.02) {
			this.warnings.add(String.format(Locale.ROOT, "frame rate locked to the %d Hz display refresh (avg %.1f FPS, p50 %.2f ms) "
				+ "although vsync is off", refreshHz, avgFps, all.p50Ms()));
		}
		// Compiler threads run beside the game, so this is not time the frames waited for. Minecraft keeps the JIT busy
		// for minutes (about 12-17% of one core during a measurement, whether the warmup is 15 s or 60 s), so only
		// warn when it is clearly more than that, e.g. with --warmup 0.
		if (jitMs > 0.25 * measuredSeconds * 1000) {
			this.warnings.add("the JIT compiled for " + jitMs + " ms during the measurement; consider a longer warmup");
		}

		// Schema 3: on-screen check (display awake, session unlocked), per-second timeline with background CPU, render
		// thread/JIT counters, disturbed-run screening. Schema 4: measureWindow (System.nanoTime bounds for the energy
		// sampler), fpsCap, effective-limit check. bench/report.py only compares runs of the newest schema.
		result.addProperty("schema", Bench.RESULT_SCHEMA);
		result.addProperty("config", this.settings.configName());
		result.addProperty("scenario", this.scenario.name());
		result.addProperty("valid", invalid.isEmpty());
		JsonArray inv = new JsonArray();
		invalid.forEach(inv::add);
		result.add("invalidReasons", inv);
		JsonArray warn = new JsonArray();
		this.warnings.forEach(warn::add);
		result.add("warnings", warn);
		result.addProperty("timestamp", Instant.now().toString());
		result.addProperty("minecraft", EnvInfo.minecraftVersion());
		result.addProperty("durationSeconds", this.settings.durationSeconds());
		result.addProperty("warmupSeconds", this.settings.warmupSeconds());
		result.addProperty("measuredSeconds", FrameStats.round(measuredSeconds, 3));
		// 0 = Unlimited; otherwise the Max Framerate of a capped run (vsync off).
		result.addProperty("fpsCap", this.settings.fpsCap());
		// "ingot" or "vanilla" (EnvInfo.frameLimiter): the limiter that paces a capped run.
		result.addProperty("frameLimiter", frameLimiter);
		// The measurement window in System.nanoTime(), which on macOS is mach_absolute_time() in ns: the same clock as the
		// timestamps of bench/power/ingot-energy, so bench/benchlib.py can integrate energy over exactly these frames.
		// The frame times add up to end - start.
		JsonObject measureWindow = new JsonObject();
		measureWindow.addProperty("startNanoTime", this.measureStart);
		measureWindow.addProperty("endNanoTime", this.measureEnd);
		measureWindow.addProperty("frames", this.frames);
		measureWindow.addProperty("clock", "System.nanoTime (mach_absolute_time in ns on macOS)");
		result.add("measureWindow", measureWindow);
		// Wall-clock window of the measurement and the game's pid: bench/benchlib.py uses them to attribute the CPU and
		// GPU time other processes used during exactly this window (see "background" in the collected result).
		result.addProperty("measureStartEpochMs", this.measureStartEpochMs);
		result.addProperty("measureEndEpochMs", this.measureEndEpochMs);
		result.addProperty("pid", ProcessHandle.current().pid());

		result.add("stats", all.toJson());
		JsonArray phases = new JsonArray();
		for (int p = 0; p < this.scenario.phases().size(); p++) {
			double[] subset = new double[this.frames];
			int n = 0;
			for (int i = 0; i < this.frames; i++) {
				if (this.framePhase[i] == p) {
					subset[n++] = this.frameMs[i];
				}
			}
			JsonObject phase = FrameStats.of(subset, 0, n).toJson();
			phase.addProperty("name", this.scenario.phases().get(p).name());
			phase.addProperty("type", this.scenario.phases().get(p).type());
			phases.add(phase);
		}
		result.add("phases", phases);

		JsonObject timing = new JsonObject();
		timing.addProperty("worldLoadSeconds", FrameStats.round(this.loadSeconds, 2));
		timing.addProperty("settleAfterLoadSeconds", FrameStats.round(this.settleLoadSeconds, 2));
		timing.addProperty("settleAfterLoadTimedOut", this.settleLoadTimedOut);
		timing.addProperty("settleAfterWarmupSeconds", FrameStats.round(this.settleWarmSeconds, 2));
		timing.addProperty("settleAfterWarmupTimedOut", this.settleWarmTimedOut);
		if (this.settledSnapshot != null) {
			timing.add("settledState", this.settledSnapshot.toJson());
		}
		result.add("settle", timing);

		JsonObject counters = new JsonObject();
		counters.addProperty("throttledFrames", this.throttledFrames);
		counters.addProperty("unfocusedFrames", this.unfocusedFrames);
		counters.addProperty("screenInterruptions", this.screenInterruptions);
		counters.add("gcDuringMeasurement", gcDuring);
		// JIT time spent during the measurement; a large value means the warmup was too short.
		counters.addProperty("jitMsDuringMeasurement", jitMs);
		// Average number of cores the game process kept busy (all threads, including the integrated server).
		counters.addProperty("processCpuCores", cpuNs < 0 || measuredSeconds <= 0 ? -1 : FrameStats.round(cpuNs / 1e9 / measuredSeconds, 2));
		// Share of the wall time the render thread was on a CPU. Near 1.0: the frame loop is CPU bound; clearly below 1.0:
		// it waits (GPU, present, locks) or was descheduled.
		counters.addProperty("renderThreadBusy", renderCpuNs < 0 || measuredSeconds <= 0 ? -1 : FrameStats.round(renderCpuNs / 1e9 / measuredSeconds, 3));
		result.add("counters", counters);
		// Everything else on the Mac, in CPU cores, averaged over the measurement. bench/run.sh marks the run disturbed
		// when this is above its --max-background limit: background load moves the CPU and GPU clocks the game gets.
		counters.addProperty("otherCpuCores", Double.isNaN(this.busyCoreSeconds) || cpuNs < 0 || measuredSeconds <= 0 ? -1
			: FrameStats.round(Math.max(0, this.busyCoreSeconds - cpuNs / 1e9) / measuredSeconds, 2));
		counters.addProperty("otherCpuCoresPeak", this.timeline.stream().mapToDouble(b -> b[4]).max().orElse(-1));
		// Per second: [start s, FPS, render thread busy, process CPU cores, other processes' CPU cores]. Shows whether
		// slow stretches were the render thread working harder (CPU bound), waiting (GPU, presentation, clocks) or
		// competing with background load.
		JsonArray seconds = new JsonArray();
		for (double[] b : this.timeline) {
			JsonArray row = new JsonArray();
			for (double v : b) {
				row.add(v);
			}
			seconds.add(row);
		}
		result.add("timeline", seconds);

		result.add("renderer", EnvInfo.renderer());
		JsonObject display = EnvInfo.display(minecraft);
		display.addProperty("screenAtStart", this.displayAtStart == null ? "unknown" : this.displayAtStart.describe());
		display.addProperty("screenAtEnd", displayAtEnd.describe());
		result.add("display", display);
		result.add("options", EnvInfo.options(minecraft));
		JsonObject world = new JsonObject();
		world.addProperty("name", this.settings.worldName());
		world.addProperty("seed", Long.toString(this.scenario.seed()));
		world.addProperty("startX", this.scenario.startX());
		world.addProperty("startY", this.scenario.startY());
		world.addProperty("startZ", this.scenario.startZ());
		world.addProperty("pitch", this.scenario.pitch());
		world.addProperty("freezeMobs", this.settings.freezeMobs());
		if (this.template != null) {
			for (String key : new String[] {"templateId", "createdAt"}) {
				if (this.template.has(key)) {
					world.add("template" + (key.equals("templateId") ? "Id" : "CreatedAt"), this.template.get(key));
				}
			}
		}
		if (minecraft.level != null) {
			world.addProperty("loadedChunks", minecraft.level.getChunkSource().getLoadedChunksCount());
			world.addProperty("entities", minecraft.level.getEntityCount());
		}
		result.add("world", world);
		result.add("mods", EnvInfo.mods());
		result.add("java", EnvInfo.java());
		result.add("os", EnvInfo.os());
		if (this.settings.recordFrames()) {
			JsonArray times = new JsonArray(this.frames);
			for (int i = 0; i < this.frames; i++) {
				times.add(FrameStats.round(this.frameMs[i], 4));
			}
			result.add("frameTimesMs", times);
			JsonArray phaseIdx = new JsonArray(this.frames);
			for (int i = 0; i < this.frames; i++) {
				phaseIdx.add(this.framePhase[i]);
			}
			result.add("framePhases", phaseIdx);
		}

		Watchdog.writeJson(this.settings.outFile(), result);
		Bench.LOGGER.info("[bench] RESULT config={} frames={} avgFps={} 1%low={} 0.1%low={} p50={}ms p99={}ms backend={} valid={}",
			this.settings.configName(), all.frames(), String.format(Locale.ROOT, "%.1f", all.avgFps()),
			String.format(Locale.ROOT, "%.1f", all.onePercentLowFps()), String.format(Locale.ROOT, "%.1f", all.pointOnePercentLowFps()),
			String.format(Locale.ROOT, "%.2f", all.p50Ms()), String.format(Locale.ROOT, "%.2f", all.p99Ms()), backend, invalid.isEmpty());
		// bench/power/ingot-energy reads its counters every 100 ms until this process exits, and the energy of the window
		// is interpolated between the samples around its end. Shutdown alone left only about 0.4 s; stay alive at least
		// 0.5 s past the window so several samples follow it whatever the configuration's shutdown time.
		long tailMs = (this.measureEnd + 500_000_000L - System.nanoTime()) / 1_000_000L;
		if (tailMs > 0) {
			try {
				Thread.sleep(tailMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Watchdog.markFinished();
		minecraft.stop();
	}
}
