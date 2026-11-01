# Ingot benchmark

A reproducible frame-time and energy benchmark for Minecraft 26.3 on macOS. It compares four configurations on the same
pre-generated world, the same scripted camera path, the same options and the same JVM. Every run records frame times and
the energy the game used (CPU energy of the game process, energy of the whole GPU), uncapped or at a fixed frame rate
(`--fps-cap 60`), where every configuration renders the same frames and only the energy differs. See "Energy" below.

| config | what runs | Ingot |
|---|---|---|
| `vanilla` | Fabric Loader + the bench harness | on the classpath, disabled (`-Dingot.enabled=false`) |
| `mainstream` | Sodium, Lithium, FerriteCore, ImmediatelyFast, EntityCulling, MoreCulling, ModernFix + their required dependencies (the performance core of Fabulously Optimized 26.3) | disabled |
| `ingot` | Ingot's Metal backend, no other mods | enabled |
| `ingot-plus` | Ingot + the mainstream set | enabled |

The configurations are defined in `configs.json`. The mod sets are defined in `mods.json` and pinned in `mods.lock.json`.

## Running it

```sh
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home   # run.sh sets this if unset

bench/run.sh prepare                      # once: generates bench/worlds/standard (about 40 s)
bench/run.sh vanilla --repeat 3           # one configuration
bench/run.sh vanilla,mainstream --repeat 3
bench/run.sh all --repeat 3               # every configuration, interleaved
bench/run.sh all --repeat 3 --fps-cap off,60   # uncapped and capped at 60 FPS, interleaved
bench/report.py                           # writes bench/results/summary.md and summary.json
```

A Minecraft window opens for every run and closes by itself. Leave it visible and do not touch the mouse or keyboard
while it runs.

**The Mac must be unlocked with the display on.** With the display asleep or the screen locked, WindowServer does not
composite the game window and presentation never waits for the display, so the same configuration renders much faster
(vanilla measured 199 to 244 FPS with the screen locked against 119 to 131 FPS with the display on, same world and
settings). `run.sh` wakes a sleeping display, refuses to start while the screen is locked, and the harness marks any run
invalid whose window was not on screen at the start or end of the measurement. Start a batch while the Mac is
unlocked: `run.sh` holds a `caffeinate -dims` assertion until it exits, so the display does not go to sleep during the
batch. If a screen saver or the lock still kicks in, the affected run is marked invalid and `run.sh` stops before the
next one.

`run.sh` options:

| option | default | meaning |
|---|---|---|
| `--scenario NAME` | `standard` | `bench/scenarios/NAME.json` |
| `--duration S` | scenario (60) | measured seconds |
| `--warmup S` | scenario (15) | warmup seconds |
| `--repeat N` | 1 | runs per configuration; with several configurations the order is A B A B ... |
| `--cooldown S` | 0 | pause between runs |
| `--width W --height H` | 1280 x 720 | logical window size; the Retina framebuffer is twice that (2560 x 1440) |
| `--without-ingot` | off | leave Ingot off the classpath; only for when `src/main` does not build. The result gets a warning, and the Ingot configs refuse to run |
| `--fps-cap off\|N\|off,N` | off | Max Framerate for the runs, vsync off: `off` is Unlimited, `N` a multiple of 10 from 10 to 250 (the video settings slider). A comma list runs each variant, interleaved (`for repeat: for cap: for config`). Capped results are stored under `results/raw/<scenario>-cap<N>/` and get their own tables in the report |
| `--no-energy` | off | skip the energy sampler and the idle GPU baseline |
| `--max-background C` | 5 | CPU cores the rest of the Mac may use during a measurement; above that the run is marked disturbed (see below) |
| `--retries N` | 2 | how often a disturbed run is repeated before it is kept as disturbed |
| `--allow-hidden` | off | run even when the screen is locked or the display is off. Every result is invalid; only for testing the harness |
| `--survey` | off | with `prepare`: print a biome survey of the seed instead of generating the world |
| `--force` | off | with `prepare`: replace an existing world template (this starts a new template id) |

Each run gets a fresh game directory `run/bench/<config>/`: `options.txt` from `options.base.txt`, `mods/` from the lock,
`saves/bench` copied from the world template, an empty `config/` (plus `bench/config-overrides/<config>/` if that folder
exists). It then runs `./gradlew runBench`, which launches the game through Loom's `bench` run configuration (see the
`// ---- Benchmark harness ----` block in `build.gradle`). As soon as the game's JVM exists, `run.sh` starts the energy
sampler against its pid (see "Energy"). The game writes its result JSON and quits; `run.sh` adds the energy summary and
copies the result, `latest.log`, the Gradle output, the energy samples (`.energy.csv`) and any crash report to
`bench/results/raw/<scenario>/<config>/<timestamp>.*` (`<scenario>-cap<N>` for a capped run).

Only one benchmark runs at a time: `run.sh` holds `run/bench/bench.lock` (taken atomically with `shlock`; a lock whose
process is dead is replaced) and also refuses to start while any benchmark game is still running, for example one left
behind by a killed runner. The game is a child of the Gradle daemon, not of `run.sh`, so `run.sh` stops it explicitly:
Ctrl-C or a kill of `run.sh` stops the game, the Gradle client and both samplers and releases the lock. Besides the
in-game watchdog (below), `run.sh` stops a run that has not ended after the watchdog budget plus 10 minutes. It warns
when the Mac is on battery, in Low Power Mode, busy (1-minute load above 3), or when another Minecraft process is
running.

**Background load.** Before each run `run.sh` waits (at most 2 minutes) until the rest of the Mac uses at most
`--max-background` CPU cores (default 5). During the measurement the harness measures, once per second and
in-process, the CPU the rest of the Mac uses: host CPU ticks minus the game's own CPU time (`counters.otherCpuCores`
and the last column of `timeline`). A run is marked `disturbed` when that average is above `--max-background`, or when
the load jumps: at least 2 seconds (or 5% of them) more than 1.5 cores above the run's own quiet level (its 10th
percentile). `run.sh` repeats a disturbed run up to `--retries` times; `report.py` leaves disturbed runs out. The
jumps matter more than the level: in the audit a steady 4 to 5 cores of Spotlight and Photos analysis left vanilla
within 3% of the other runs, while a 4-second jump from about 3.6 to 6.2 cores halved its frame rate for those seconds. On
this Mac an idle machine starts Photos analysis (`mediaanalysisd`), Spotlight (`spotlightknowledged`,
`corespotlightd`) and Siri indexing; a Gradle build in another terminal uses several cores too. A small sampler (`benchlib.py sample`) also records which processes
used the CPU and GPU around the measurement (snapshots right before and right after it, never during it: an `ioreg` query
of the GPU driver once per second measurably slowed the frames after it). The result lists them under `background`.

`bench/fetch_mods.py` downloads and verifies the jars in `mods.lock.json` (run.sh calls it). `bench/fetch_mods.py
--update` resolves the newest versions again from the Modrinth API and rewrites the lock.

## What a run does

The harness is a separate mod, `src/bench` (id `ingot-bench`, depends only on Fabric Loader and Minecraft). It does
nothing unless `-Dingot.bench.scenario` is set; without it, its mixin plugin does not apply any hook.

1. **Open the world.** When the title screen is ready it opens `saves/bench` directly. Every option that could throttle
   the frame loop is enforced again in memory (vsync off, max FPS 260 which means unlimited or the `--fps-cap` value,
   inactivity limit `minimized`, `pauseOnLostFocus` off). Autosave is turned off on the integrated server (`/save-off`) so chunk saving
   cannot land in the measurement.
2. **Settle.** The camera is held at the start pose until the world is idle: the renderer reports an empty compile queue
   (vanilla `SectionRenderDispatcher` queue and `hasRenderedAllSections()`, or Sodium's
   `SodiumWorldRenderer.isTerrainRenderComplete()` through reflection) and the loaded chunk count and rendered section
   count have not changed for 3 s. The wait is capped by `settleTimeoutSeconds` (120 s); a timeout is recorded as a
   warning.
3. **Warm up.** One full 360 degree turn in place over the warmup time. This warms the JIT and makes the renderer mesh
   every direction, because both vanilla and Sodium only build sections that are inside the view.
4. **Settle again** at the start pose.
5. **Measure.** The camera follows the scenario path for the measured duration. Every iteration of the client main loop
   is timed with `System.nanoTime()` at the top of `Minecraft.runTick`. `Minecraft.run` calls `pollEvents` and then
   `runTick`, and `runTick` contains the client ticks, `renderFrame` with the GPU submit and `present`
   (`SDL_GL_SwapWindow` on OpenGL), and the frame limiter (inactive at 260 = unlimited; with `--fps-cap` it waits for
   the frame's deadline). So a frame time is the wall time of one whole loop iteration, present and limiter included. The camera pose is applied at the top of
   `Minecraft.renderFrame` with `snapTo`, which also sets the previous position and rotation, so there is no tick
   interpolation. The pose is a function of elapsed time, so every configuration renders the same views regardless of
   its frame rate. Nothing is logged during the measurement. Once per second the harness reads a few in-process
   counters (render thread CPU time, process CPU time, host CPU load; microseconds each) for the `timeline`. Right
   before and right after the measurement it checks through CoreGraphics that the display is awake and the session is
   unlocked. It records the window's first and last `System.nanoTime()` (`measureWindow`), the clock the energy
   sampler's timestamps use.
6. **Write the result and quit.** `bench-result.json` is written atomically, then the game shuts down normally.

A watchdog thread writes a failure result and halts the JVM if a run takes longer than
`300 + 2 x settleTimeout + warmup + duration` seconds, so a hung run cannot block a batch.

### Metrics

With n frames and frame times in milliseconds:

- **avg FPS** = n / (sum of frame times in seconds), which is frames per wall-clock second.
- **p50 / p95 / p99** = nearest-rank percentiles: the frame time at sorted index ceil(p x n) - 1.
- **1% low FPS** = 1000 / (mean of the slowest ceil(0.01 x n) frame times). **0.1% low** uses ceil(0.001 x n) frames.
- **max** = the slowest single frame.

The result also has these numbers per path phase, every frame time (`frameTimesMs`, with `framePhases`), GC activity
during the measurement, and the settle times. Diagnostics that explain a number rather than define it:

- `counters.renderThreadBusy`: share of the measured wall time the render thread was on a CPU. Vanilla on OpenGL sits
  near 0.4: the render thread spends most of each frame blocked in `SDL_GL_SwapWindow` (63% of 40 stack samples), so
  CPU and GPU take turns instead of overlapping. Mainstream sits near 0.25 with the GPU busier.
- `counters.processCpuCores`, `counters.otherCpuCores` (and `otherCpuCoresPeak`): CPU cores used by the game and by
  the rest of the Mac during the measurement.
- `counters.jitMsDuringMeasurement`: JIT compile time during the measurement. Minecraft keeps the compiler busy for
  minutes (about 12 to 17% of one core with a 15 s or a 60 s warmup alike); a warning appears only above 25%.
- `timeline`: one row per second, `[start s, FPS, render thread busy, game CPU cores, other CPU cores]`.
- `background` (added by `run.sh`): which other processes used the CPU and the GPU from the settle phase until the
  result was written, and the game's own GPU busy share (from the GPU driver's per-process accounting).

Energy metrics (`energy` in the result, defined in "Energy" below): game CPU J and W, GPU J and W, total W, mJ per
frame, GPU W over the idle baseline.

### Result JSON

`bench/results/raw/<scenario>/<config>/<timestamp>.json` contains `schema`, `valid`, `invalidReasons`, `warnings`,
`disturbed`, `fpsCap` (0 = unlimited), `frameLimiter` (`ingot` or `vanilla`), `stats`, `phases`, `timeline`, `measureWindow` (`startNanoTime`, `endNanoTime`,
`frames`), `energy`, `settle`, `counters` (throttled, unfocused and interrupted frames, GC, JIT,
CPU), `renderer` (backend, GPU, vendor, driver from `RenderSystem.getDevice().getDeviceInfo()`), `display` (framebuffer
and window size, pixel density, GUI scale, refresh rate of the display, screen state at start and end), `options` (render distance, preset and every
graphics option), `world` (seed, start, template id), `mods` (every loaded mod with its version), `java` (version, heap,
collectors, JVM flags), `os`, `host` (CPU, model, macOS, power source, Low Power Mode, Energy Mode, thermal warning,
free memory, load average), `background`, `git` (revision, dirty flag), the measurement window (`measureStartEpochMs`,
`measureEndEpochMs`, `pid`) and the config definition.

A run is marked **invalid** when: the display was asleep or the screen locked at the start or end of the measurement;
the backend name does not contain the config's `expectBackend` (the Ingot configs expect `metal`, which catches Ingot
falling back to OpenGL); it contains the config's `rejectBackend` (vanilla and mainstream reject `metal`, which catches
Ingot running where it should be disabled); the window's logical size differs from `--width`/`--height` (macOS shrinks
windows that do not fit); the render distance differs from the scenario; vsync was on; the Max Framerate option differs
from the run's (260, or the `--fps-cap` value); more than 1% of the frames were throttled by the game's framerate limit
tracker (a throttle reason such as an iconified window, or an effective limit below the option, such as a battery
saver); a capped run was paced by the other configuration's frame limiter (see "Capped runs"); a screen opened
during the measurement;
fewer than 0.6 x pi x RD^2 chunks were loaded; or fewer than 10 frames were measured. `bench/report.py` leaves invalid
runs out unless `--include-invalid` is given. A run is **disturbed** (valid, but left out of the report) when the
background load was too high, see above.

An uncapped run whose average frame rate and median frame time both sit within 2% of the display's refresh interval
gets the warning "frame rate locked to the N Hz display refresh although vsync is off" (shown in the report notes).
Such a run was paced by presentation, not by the renderer, so its FPS and its energy say little about the renderer.

### Report

`bench/report.py` reads every raw result and writes `bench/results/summary.md` (per group: a frame-time table with runs,
avg FPS, 1% low, 0.1% low, p50 and p99 ms, change against vanilla, backend, notes; per-phase FPS; an energy table, see
"Energy"; the mod list) and `bench/results/summary.json`. Every number is the median over the runs of a configuration;
the notes give the spread of avg FPS (and of mJ/frame) in percent and the median background load. Runs are only
compared within a group: same scenario, FPS cap, measured duration, warmup, framebuffer size, render distance and world
template (the path is fixed in space, so a shorter run moves the camera faster). Uncapped and capped runs are therefore
separate tables. Only the newest result `schema` is used: results from an older harness lack checks that changed what
counts as comparable (schema 3 added the on-screen check and the background screening, schema 4 the measurement window
for energy, the FPS cap and the effective frame-limit check). Invalid and
disturbed runs are left out (`--include-invalid`, `--include-disturbed`); `--max-background C` re-screens every run with
another limit; `--latest N` limits each configuration to its N most recent runs.

`bench/results/raw/` is ignored by git; the summaries are meant to be committed.

## Energy

Battery life and heat matter as much as frame rate, so every run also measures energy, without root.

**What is measured.** `bench/power/ingot-energy` (C, built by `run.sh` into `bench/power/build/`, which git ignores)
samples two cumulative counters every 100 ms until the game exits:

- **Game CPU energy**: `proc_pid_rusage(pid, RUSAGE_INFO_V6)` of the game's JVM, `ri_energy_nj`, the CPU energy macOS
  attributes to that process (all its threads, including the integrated server and the JIT and GC threads). Also
  `ri_penergy_nj` (the P-core part), instructions, cycles, CPU time and wakeups. This works for another process of
  the same user.
- **GPU energy**: the IOReport channel "GPU Energy" (group "Energy Model", in nJ). It is the energy of the whole GPU,
  whatever process uses it: the game, but also WindowServer compositing the desktop and any other app.

The CPU channels of the same IOReport group ("CPU Energy", the per-cluster channels) and "DRAM" never change on this
macOS (27.2), so CPU energy comes from the process and DRAM energy is not measured.

**The window.** The sampler's timestamps are `mach_absolute_time` in ns, the clock `System.nanoTime()` reads on macOS.
(Checked with JDK 25 on this Mac: two `System.nanoTime()` reads around a program that prints `mach_absolute_time` in
ns bracket its value within 5 ms. `CLOCK_MONOTONIC`, which also counts sleep, differed by the time the Mac had slept.)
The harness writes the first and last `System.nanoTime()` of the measured frames (`measureWindow`), and `benchlib.py`
interpolates each counter linearly between the samples around those two instants. So the energy belongs to exactly
the frames in `stats`, not to the world load, settle or warmup. The sampler starts once the game's JVM exists (20 to
35 s before the window) and stops when it exits; the harness keeps the game alive at least 0.5 s past the window, so
several samples follow its end (shutdown alone left only about 0.4 s). As a check of the clocks, the sampler's CPU time over
the window is compared with the game's own CPU time over it (`energy.cpuCoresVsGame`, about 1.0; a warning appears
beyond 5%). The samples are kept next to the result (`<timestamp>.energy.csv`); `bench/report.py` integrates them again with
the current code, and `bench/benchlib.py energy <result.json>` prints one run's energy summary.

**Metrics** (`energy` in the result; medians in the report). With E_cpu and E_gpu the energy over the window, t its
length and n the number of frames:

- **game CPU J** = E_cpu, **GPU J** = E_gpu
- **game CPU W** = E_cpu / t (the game process), **GPU W** = E_gpu / t (the whole GPU), **total W** = their sum
- **mJ/frame** = 1000 x (E_cpu + E_gpu) / n, the energy of one frame. Uncapped, a faster configuration draws more
  watts because it renders more frames; mJ/frame is the comparison that holds there.
- **GPU over idle W** = GPU W minus the batch's idle GPU baseline: roughly what the game adds to the GPU.
- **memoryMB** (`mean`, `peakMeasured`, `peakRun`): the game process's physical footprint
  (`ri_phys_footprint`, what Activity Monitor shows as Memory) sampled with the energy counters: the mean and peak
  over the measured frames, and the peak over the whole run including world load.
- diagnostics: `cpuPCoreShare` (share of the CPU energy spent on P-cores), `cpuCores`, `instructionsPerFrame`,
  `instructionsPerCycle`, `wakeupsPerSecond`, and `energy.timeline`, one row per second on the same buckets as
  `timeline`: `[start s, game CPU W, GPU W]`.

**Idle GPU baseline.** Before each batch, `run.sh` waits for the Mac to be quiet and then samples the GPU for 10 s with
no game running (`run/bench/idle-baseline.json`, copied into every result of the batch as `energy.idleBaseline`). It
shows how much of the GPU figure is the desktop and how much is the game. The baseline also records which processes
used the GPU meanwhile (`topGpu`, from the GPU driver's per-process time; normally only WindowServer) and warns when
anything else kept it 5% or more busy, such as another game left running, because that would inflate the baseline.
The report prints the median baseline above each energy table.

**Capped runs: energy at equal frame rate.** `--fps-cap 60` sets the vanilla Max Framerate option to 60 with vsync
off, so every configuration renders 60 frames per second of the same camera path and only the energy differs; W and
mJ/frame then rank the configurations the same way. The game's frame limiter does the pacing: vanilla's (park, then
spin) for vanilla and mainstream, Ingot's (sleep until the deadline) for the Ingot configurations. The result records
which one ran (`frameLimiter`: `ingot` when Ingot's mixin replaces `FramerateLimiter.limitDisplayFPS` and libingot is
loaded, otherwise `vanilla`; read by reflection), and the report lists it in the capped energy tables. A capped run is
invalid when the option differs from the cap, or when the limiter does not match the configuration (an Ingot
configuration paced by vanilla's limiter, or a configuration with Ingot disabled paced by Ingot's). A warning (shown
in the report) says when a configuration could not hold the cap (average below 97% of it). `--fps-cap off,60` runs
both variants in one batch.

**Cost of measuring.** The subscription holds only the GPU channel: back to back, a sample of all 278 channels of the
group costs about 3.3 ms of CPU, one channel about 65 us. The sampler reports its own cost (`energy.sampler`: share of
one core and watts of its sampling loop), and `energy.sampler.frameCheck` compares the frames during which a sample was
taken (and the frame after) with the frames half a sampling interval later. Both sets are picked by a point in time,
so both favour long frames equally; a ratio above 1 would mean the sampling slows the game. The report prints the
medians below each energy table. The background sampler (`ps`, `ioreg`) still never runs during the measurement.

**What the validation runs showed** (M4 Pro, macOS 27.2, screen locked, so every run was hidden and invalid; the
numbers show that the pipeline works, not how the configurations compare on screen). 60 s runs, 15 s warmup:

| run | avg FPS | game CPU W | GPU W | mJ/frame (CPU + GPU) |
|---|---:|---:|---:|---:|
| vanilla, uncapped | 267 | 2.90 | 14.42 | 64.8 (10.8 + 53.9) |
| mainstream, uncapped | 266 | 2.19 | 5.60 | 29.3 (8.2 + 21.0) |
| vanilla, 60 FPS cap | 59.8 | 0.73 | 0.91 | 27.4 (12.2 + 15.3) |
| mainstream, 60 FPS cap | 59.7 | 0.80 | 0.69 | 24.9 (13.3 + 11.5) |

- The idle GPU baseline was 0.001 to 0.002 W with the screen locked (no compositing); expect more with the display on.
- At the cap the GPU energy per frame drops (lower clocks), while the CPU energy per frame rises: the integrated
  server's ticks, chunk streaming, JIT and GC cost about the same per second and are spread over a quarter of the
  frames. In a 30 s run with a 5 s warmup (more JIT per frame in the window) mainstream's capped mJ/frame came out
  equal to its uncapped one, so use the default warmup and duration for energy.
- The sampler's CPU time over the window matched the game's own (`cpuCoresVsGame` 0.993 to 1.003), so the two clocks
  line up. Samples came every 100 ms (largest gap 110 ms). The sampling loop used 0.4 to 0.7% of one core and 4 to
  7 mW, and `frameCheck` was 0.99 to 1.01: no measurable effect on the frames.

**Caveats.**

- GPU energy is **system-wide**: anything else that uses the GPU during the measurement counts. Compare the idle
  baseline and the `background` GPU attribution (`otherGpuBusy`); `finalize` warns when other processes kept the GPU
  5% or more busy.
- CPU energy is **per process**: CPU work the game causes in other processes (WindowServer compositing its window,
  the audio daemon, kernel work not charged to the process) is not included, and neither is any CPU work of other
  processes.
- **Not included**: the display (backlight and panel, often the largest consumer on a laptop), DRAM, the SoC fabric,
  storage, Wi-Fi. "total W" is the game's CPU plus the GPU. It compares configurations; the Mac as a whole draws
  more, and battery runtime cannot be read from it directly.
- The counters are energy models of the SoC, not a meter at the battery. They are consistent between runs on the same
  Mac, which is what the comparison needs.
- Hidden runs (screen locked, display asleep) are invalid for energy as for FPS: without compositing the GPU does
  different work, and the idle baseline drops to almost nothing.
- Temperature changes power at the same clocks (leakage). Interleaved repeats spread that over all configurations;
  `--cooldown` helps for long batches.

## The `standard` scenario

`bench/scenarios/standard.json`:

- Seed `20261004`, default world preset, structures on. Start at x -1023.5, z 640.5, feet at y 110 (eye at 111.6), yaw
  -90 (facing east), pitch 20 (looking down). Noon (`dayTime` 6000), clear weather.
- Render distance 16 and simulation distance 12 (the values the vanilla "Fancy" preset applies).
- Path: phase `spin` turns 360 degrees in place for 2/3 of the duration; phase `fly` moves 160 blocks east at constant
  speed for the last 1/3, into chunks that are on disk but not yet loaded, so it also measures chunk streaming and
  meshing while moving. With the default 60 s that is 9 degrees/s and 8 blocks/s.
- Within the render distance of the start the surface is about 35% forest (forest, birch, old growth birch, dark forest,
  a little jungle), 35% plains, 27% water (river and ocean) and 4% beach. Surface height ranges from 21 to 105, mean 67.
  The spot was picked with `bench/run.sh prepare --survey`, which scores 384 x 384 block windows around spawn by how
  evenly they mix forest, plains and water.

`prepare` creates the world with that seed (peaceful, spectator, commands on), sets game rules that freeze it
(`advance_time`, `advance_weather`, every `spawn_*` rule and `spread_vines` off, `random_tick_speed` 0, `mob_griefing` off,
command feedback off), teleports the player to the start, generates every chunk of the path's bounding box plus render
distance plus 3 chunks (chunks x -83..-35, z 21..59, 1911 chunks), lets the world tick for 10 s so worldgen fluids settle,
saves, and quits. `run.sh` stores the save as `bench/worlds/standard/` with `ingot-bench-world.json` (template id, biome
mix, surface heights). During runs the harness also cancels mob AI (`Mob.serverAiStep`), so entities stand still in the
same places in every run.

`spectators_generate_chunks` stays on. With it off the server does not load any chunks around a spectator. No terrain is
generated during runs anyway because the camera never leaves the prepared box.

Minecraft's multithreaded world generation is not bit-for-bit reproducible, so running `prepare` again produces a
slightly different world with a new template id. Compare only results from the same template; the report does this.

## Fairness

Identical for every configuration:

- **Options** (`options.base.txt`): the values the vanilla Fancy preset applies (render distance 16, simulation 12,
  biome blend 2, smooth lighting on, fancy clouds at range 64, all particles, mipmap 4, entity shadows on, entity
  distance 100%, cutout leaves, improved transparency off, weather radius 10, RGSS texture filtering, chunk updates
  "player affected"), GUI scale 4, vsync off, max FPS unlimited, inactivity limit `minimized` (an unfocused window is
  not throttled), pause on lost focus off, windowed, sound volume 0, graphics API `default` (OpenGL first, as vanilla).
  The harness records the effective options in every result and checks the render distance.
- **Window**: 1280 x 720 logical, which is a 2560 x 1440 framebuffer on Retina. 1600 x 900 does not fit the built-in
  14" display (1512 x 982 logical), and macOS would shrink the window.
- **JVM**: Java 25, `-Xms2G -Xmx4G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication
  -XX:+UseZGC`, meant to match the official launcher's defaults for 26.3 (not verified against a launcher install
  here; the 26.3 version manifest itself only adds `-XX:StackShadowPages=32` and `--enable-native-access=ALL-UNNAMED`,
  which Loom passes too). A configuration's `jvmArgs` (configs.json) replaces these flags, for JVM experiments such as
  `ingot-g1`; the result lists the effective flags.
- **Ingot's power modes and occlusion throttle are off in every configuration unless it says otherwise**
  (`-Dingot.powerMode=performance -Dingot.performanceFps=-1 -Dingot.performanceResolution=full
  -Dingot.throttleWhenOccluded=false`, also written to `config/ingot.properties`). In Auto mode Ingot switches to
  Battery mode (frame cap at the display refresh rate, efficiency cores) on battery or in Low Power Mode, and drops to
  10 FPS while its window is covered; vanilla and the mainstream mods do neither, so leaving them on would measure the
  power policy instead of the renderer. Configurations that measure a power mode on purpose set it through
  `ingotProps` (Ingot settings without the `ingot.` prefix, e.g. `ingot-battery`, `ingot-battery-half`); run those at
  an `--fps-cap` equal to their frame cap (120 on ProMotion), or the cap counts as throttled frames. Ingot's
  replacement frame limiter stays on (it has no switch, and the harness does not try to remove it): where vanilla's
  `FramerateLimiter` parks and then spins through the last 0.5 ms or more of every limited frame, Ingot sleeps in the
  kernel until the deadline. It does nothing uncapped and paces the frames at `--fps-cap`, and it is part of what
  Ingot is, so the capped comparison includes it. With Ingot disabled these properties do nothing.
- **World and path**: the same template, copied fresh for each run.
- **Mods**: exact jars from `mods.lock.json`, verified by sha1 and sha512.

The mainstream set (from the current lock):

| mod | version | why |
|---|---|---|
| Sodium | 0.9.3-alpha.1+mc26.3 | the newest 26.3 build (`"channel": "any"` in `mods.json`; Fabulously Optimized 15.0.0-alpha.5 pins 0.9.2) |
| Lithium | 0.26.2+mc26.3 | |
| FerriteCore | 9.0.0 | |
| ImmediatelyFast | 1.17.1+26.3 | |
| EntityCulling | 1.11.2 | |
| MoreCulling | 1.9.0 | |
| ModernFix | 5.27.20-build.2 | the `modernfix-mvus` project. The original `modernfix` project has no 26.3 release; this fork is the one Fabulously Optimized ships |
| Fabric API | 0.161.0+26.3 | required by EntityCulling and ModernFix |
| Cloth Config | 26.3.159 | required by MoreCulling |

Mod settings are the mods' own defaults, written fresh on every run. Fabulously Optimized's own defaults are not applied:
its `options.txt` lowers graphics (render distance 8, fast clouds, no entity shadows), which would measure a different
workload, and its MoreCulling and ModernFix config tweaks are not used either. The rest of the pack (Iris, Sodium
Extra, Dynamic FPS, Ixeris, Continuity, EMF/ETF and others) is left out: some are not performance mods, and Dynamic FPS
throttles unfocused windows.

Sodium reads render distance, texture filtering and the other graphics options from the vanilla options, so those
match. Its own defaults (`config/sodium-options.json`) add behaviour vanilla does not have: block face culling, fog
occlusion culling, entity culling, hidden fluid culling, animating only visible textures, and chunk updates deferred
until ready (`chunk_build_defer_mode: ALWAYS`). These are part of what "mainstream" means and are kept.

## Caveats

- **Development environment.** Runs launch through Loom (`net.fabricmc.devlaunchinjector.Main`, Knot class loader,
  `fabric.development=true`, the Gradle daemon idle in the background) instead of the vanilla launcher. Minecraft 26.3
  is unobfuscated, so production mod jars run unmodified, and the JVM flags match the launcher's. Absolute numbers can
  still differ a little from a launcher install; the comparison between configurations is what this measures.
- **Noise.** See "Repeatability" below. Use `--repeat 3` or more (the report takes the median), keep the Mac on power,
  and do not run other Minecraft instances or Gradle builds at the same time. Energy Mode "High Power" (System Settings,
  Battery) keeps the clocks steadier on this MacBook Pro; the result records the mode (`host.powerMode`: 0 automatic,
  1 low power, 2 high power), and every configuration of a comparison should use the same one.
- **Thermals.** Long batches can heat the machine. Interleaved repeats spread that over all configurations; `--cooldown`
  adds pauses.
- **The integrated server runs in the same process.** Lithium and ModernFix mostly change server-side work, which
  shares the CPU with the client. With mob AI frozen and the world static, the server load is light.
- **The fly phase is noisier** than the spin phase because chunk loading timing varies between runs. The report lists
  both phases separately.
- **Ingot configurations** only count as valid when the backend is Metal. Ingot must also present without display sync
  when vsync is off; otherwise it is capped at the display refresh rate (120 Hz on this Mac) and the comparison is
  meaningless. `ingot-plus` needs Sodium to work on Ingot's backend; if Sodium's renderer bypasses the renderpearl
  device, that combination crashes or falls back, and the harness reports it.
- **Sodium alpha.** The lock uses Sodium's newest 26.3 build, an alpha. To pin the release instead, set Sodium's
  `channel` to `release` in `mods.json` and run `bench/fetch_mods.py --update`. `fetch_mods.py` refuses to use a lock
  that no longer matches `mods.json`.

## Repeatability

What an audit of the harness found on this Mac (M4 Pro, macOS 27.2), 20 s runs of `vanilla` unless noted:

- **Screen state.** Runs made while the display was on measured 119 to 131 FPS. Runs made after the display had turned
  off and the screen locked measured 199 to 244 FPS with the same world and settings. Mainstream moved from 184-193 to
  260-267 FPS. This is the largest effect found and the reason for the on-screen check.
- **The frame loop waits on the GPU.** Vanilla's render thread is on a CPU only about 40% of the time; most of the
  rest it is blocked in `SDL_GL_SwapWindow` until the GPU has finished. CPU and GPU take turns, so neither is fully
  busy (the GPU about 65 to 70%), and the clock speeds macOS picks for both decide the frame rate. Frame times switch
  between two levels (about 3.5 ms and about 8 ms for the same view) when those clocks change.
- **Background load changes the clocks.** A steady 4 to 5 cores of background work (Spotlight, Photos analysis, Siri
  indexing) left vanilla repeatable: five runs measured 230.1, 237.4, 244.3, 238.4 and 236.6 FPS (all within 3.1% of
  the median, consecutive pairs 0.8 to 3.2% apart). A sixth run measured 199.1 FPS: for 4 seconds the background load
  jumped from about 3.6 to 6.2 cores and the frame rate halved for exactly those seconds. The disturbed-run screening catches
  that run and none of the other five.
- **Measuring changes the result.** An earlier version of the background sampler queried the GPU driver (`ioreg`) once
  per second during the measurement; the frames right after each query were slower and run-to-run spread grew to 26%.
  The sampler now only takes snapshots before and after the measurement.
- **JIT.** The JIT keeps compiling for minutes; a 60 s warmup lowered compile time during a 20 s measurement only from
  about 3.3 s to 2.4 s. It runs on other cores and does not decide the result, so the warmup stays at 15 s.

These hidden-window numbers show that the harness itself repeats: the per-second FPS curves of the five undisturbed
runs have the same shape. On-screen repeatability could not be measured during the audit because the screen was
locked; the earlier on-screen runs spread 10% (119 to 131 FPS) without any background screening. Measure with
`--repeat 3` or more and check the "avg range" note in the report.
