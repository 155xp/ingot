package dev.ingot.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Prepare mode: create the scenario world (fixed seed, default world preset, peaceful, spectator, commands on), freeze
 * time/weather/spawning through game rules, generate every chunk the camera path can see, let fluids settle, save and
 * quit. bench/run.sh then copies saves/&lt;world&gt; to bench/worlds/&lt;scenario&gt;/ as the template for all runs.
 */
final class PrepareController implements Bench.Controller {
	private enum State {
		BOOT,
		LOADING,
		SETUP,
		GENERATE,
		SETTLE,
		FINALIZE,
		DONE
	}

	private static final long GENERATE_BUDGET_NANOS = 40_000_000L;

	private final BenchSettings settings;
	private final Scenario scenario;
	private State state = State.BOOT;
	private long stateStart;
	private long lastProgressLog;

	private volatile boolean setupDone;
	private volatile boolean finalizeDone;
	private volatile int[] chunkQueue;
	private volatile int generated;
	private volatile boolean generating;
	private volatile JsonObject survey;
	private volatile JsonObject composition;
	private volatile int surfaceAtStart;

	PrepareController(BenchSettings settings) {
		this.settings = settings;
		this.scenario = settings.scenario();
	}

	private void enter(State next, long now) {
		Bench.LOGGER.info("[bench/prepare] {} -> {}", this.state, next);
		this.state = next;
		this.stateStart = now;
	}

	private double inState(long now) {
		return (now - this.stateStart) / 1e9;
	}

	@Override
	public void onFrameBoundary(Minecraft minecraft, long now) {
		switch (this.state) {
			case BOOT -> {
				if (minecraft.isGameLoadFinished() && minecraft.gui.overlay() == null) {
					RunController.enforceOptions(minecraft);
					if (minecraft.getLevelSource().levelExists(this.settings.worldName())) {
						Watchdog.failAndHalt(this.settings, "saves/" + this.settings.worldName() + " already exists; prepare needs a fresh run directory");
						return;
					}
					enter(State.LOADING, now);
					LevelSettings levelSettings = new LevelSettings(
						this.settings.worldName(),
						GameType.SPECTATOR,
						new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true),
						true,
						WorldDataConfiguration.DEFAULT
					);
					WorldOptions options = new WorldOptions(this.scenario.seed(), true, false);
					minecraft.createWorldOpenFlows().createFreshLevel(this.settings.worldName(), levelSettings, options,
						WorldPresets::createNormalWorldDimensions, new TitleScreen());
				}
			}
			case LOADING -> {
				if (minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null
					&& minecraft.getSingleplayerServer() != null) {
					enter(State.SETUP, now);
					MinecraftServer server = minecraft.getSingleplayerServer();
					server.execute(() -> setup(server));
				} else if (inState(now) > 600) {
					Watchdog.failAndHalt(this.settings, "world creation did not finish");
				}
			}
			case SETUP -> {
				if (this.setupDone) {
					if (this.settings.survey()) {
						Watchdog.writeJson(this.settings.outFile(), this.survey);
						Watchdog.markFinished();
						enter(State.DONE, now);
						minecraft.disconnectFromWorld(Component.literal("survey done"));
						minecraft.stop();
						return;
					}
					this.chunkQueue = buildChunkQueue();
					this.generated = 0;
					this.generating = true;
					this.lastProgressLog = now;
					enter(State.GENERATE, now);
				}
			}
			case GENERATE -> {
				int total = this.chunkQueue.length / 2;
				if ((now - this.lastProgressLog) / 1e9 >= 5) {
					this.lastProgressLog = now;
					Bench.LOGGER.info("[bench/prepare] generated {}/{} chunks ({} s)", this.generated, total,
						String.format(Locale.ROOT, "%.0f", inState(now)));
				}
				if (!this.generating) {
					Bench.LOGGER.info("[bench/prepare] generated all {} chunks in {} s", total, String.format(Locale.ROOT, "%.1f", inState(now)));
					enter(State.SETTLE, now);
				}
			}
			case SETTLE -> {
				// Keep the integrated server ticking around the start position so worldgen fluid ticks settle and
				// do not trigger re-meshing during the measurements.
				if (inState(now) >= this.scenario.prepareSettleSeconds()) {
					enter(State.FINALIZE, now);
					MinecraftServer server = minecraft.getSingleplayerServer();
					server.execute(() -> finalizeWorld(server));
				}
			}
			case FINALIZE -> {
				if (this.finalizeDone) {
					enter(State.DONE, now);
					minecraft.disconnectFromWorld(Component.literal("benchmark world prepared"));
					writeMeta(minecraft);
					Watchdog.markFinished();
					minecraft.stop();
				}
			}
			case DONE -> {
			}
		}
	}

	@Override
	public void beforeRender(Minecraft minecraft, long now) {
		if (this.state.ordinal() >= State.SETUP.ordinal() && this.state != State.DONE) {
			RunController.applyPose(minecraft, this.scenario.start());
		}
	}

	@Override
	public void onServerTick(MinecraftServer server) {
		if (!this.generating) {
			return;
		}
		int[] queue = this.chunkQueue;
		ServerLevel level = server.overworld();
		long deadline = System.nanoTime() + GENERATE_BUDGET_NANOS;
		int i = this.generated;
		while (i < queue.length / 2 && System.nanoTime() < deadline) {
			level.getChunk(queue[2 * i], queue[2 * i + 1]);
			i++;
		}
		this.generated = i;
		if (i >= queue.length / 2) {
			this.generating = false;
		}
	}

	private void setup(MinecraftServer server) {
		Scenario s = this.scenario;
		String[] commands = {
			"gamerule advance_time false",
			"gamerule advance_weather false",
			"gamerule spawn_mobs false",
			"gamerule spawn_monsters false",
			"gamerule spawn_patrols false",
			"gamerule spawn_phantoms false",
			"gamerule spawn_wandering_traders false",
			"gamerule spawn_wardens false",
			"gamerule random_tick_speed 0",
			"gamerule spread_vines false",
			"gamerule mob_griefing false",
			"gamerule fire_spread_radius_around_player 0",
			"gamerule show_advancement_messages false",
			"gamerule send_command_feedback false",
			"gamerule log_admin_commands false",
			"time set " + s.dayTime(),
			"weather clear",
			"difficulty peaceful",
			"gamemode spectator @a",
			String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", s.startX(), s.startY(), s.startZ(), s.yaw(), s.pitch())
		};
		for (String command : commands) {
			Bench.LOGGER.info("[bench/prepare] /{}", command);
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
		}
		if (this.settings.survey()) {
			this.survey = runSurvey(server.overworld());
		}
		this.setupDone = true;
	}

	private void finalizeWorld(MinecraftServer server) {
		// spectators_generate_chunks stays true: with it off the server does not load ANY chunks around a spectator.
		// Runs do not generate terrain anyway, because the whole path plus render distance plus a margin is on disk.
		ServerLevel level = server.overworld();
		this.surfaceAtStart = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(this.scenario.startX()),
			(int) Math.floor(this.scenario.startZ()));
		this.composition = composition(level);
		server.saveEverything(false, true, true);
		this.finalizeDone = true;
	}

	/** All chunks of the required box, nearest to the start first, as interleaved (x, z) pairs. */
	private int[] buildChunkQueue() {
		int[] box = this.scenario.requiredChunkBox();
		int cx = Math.floorDiv((int) Math.floor(this.scenario.startX()), 16);
		int cz = Math.floorDiv((int) Math.floor(this.scenario.startZ()), 16);
		List<int[]> list = new ArrayList<>();
		for (int x = box[0]; x <= box[2]; x++) {
			for (int z = box[1]; z <= box[3]; z++) {
				list.add(new int[] {x, z});
			}
		}
		list.sort((a, b) -> Integer.compare(
			(a[0] - cx) * (a[0] - cx) + (a[1] - cz) * (a[1] - cz),
			(b[0] - cx) * (b[0] - cx) + (b[1] - cz) * (b[1] - cz)));
		int[] out = new int[list.size() * 2];
		for (int i = 0; i < list.size(); i++) {
			out[2 * i] = list.get(i)[0];
			out[2 * i + 1] = list.get(i)[1];
		}
		Bench.LOGGER.info("[bench/prepare] chunk box x[{}..{}] z[{}..{}] = {} chunks", box[0], box[2], box[1], box[3], list.size());
		return out;
	}

	private static String biomeId(Holder<Biome> biome) {
		return biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown");
	}

	static String category(String biome) {
		if (biome.contains("ocean") || biome.contains("river")) {
			return "water";
		}
		if (biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle") || biome.contains("grove") || biome.contains("cherry")) {
			return "forest";
		}
		if (biome.contains("plains") || biome.contains("meadow") || biome.contains("savanna")) {
			return "plains";
		}
		if (biome.contains("peak") || biome.contains("slope") || biome.contains("windswept") || biome.contains("badlands")) {
			return "mountain";
		}
		return "other";
	}

	/** Surface biome mix within the render distance of the start, from the generated chunks (documentation only). */
	private JsonObject composition(ServerLevel level) {
		int r = this.scenario.renderDistance() * 16;
		int sx = (int) Math.floor(this.scenario.startX());
		int sz = (int) Math.floor(this.scenario.startZ());
		Map<String, Integer> biomes = new HashMap<>();
		Map<String, Integer> categories = new HashMap<>();
		int samples = 0;
		long heightSum = 0;
		int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;
		for (int dx = -r; dx <= r; dx += 8) {
			for (int dz = -r; dz <= r; dz += 8) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				int x = sx + dx, z = sz + dz;
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				String id = biomeId(level.getBiome(new BlockPos(x, Math.max(y - 1, level.getMinY()), z)));
				biomes.merge(id, 1, Integer::sum);
				categories.merge(category(id), 1, Integer::sum);
				heightSum += y;
				minHeight = Math.min(minHeight, y);
				maxHeight = Math.max(maxHeight, y);
				samples++;
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("radiusBlocks", r);
		o.addProperty("samples", samples);
		o.addProperty("surfaceMin", minHeight);
		o.addProperty("surfaceMax", maxHeight);
		o.addProperty("surfaceMean", samples == 0 ? 0 : (double) heightSum / samples);
		o.add("categories", percentages(categories, samples));
		o.add("biomes", percentages(biomes, samples));
		return o;
	}

	private static JsonObject percentages(Map<String, Integer> counts, int total) {
		JsonObject o = new JsonObject();
		counts.entrySet().stream()
			.sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
			.forEach(e -> o.addProperty(e.getKey(), FrameStats.round(100.0 * e.getValue() / Math.max(1, total), 1)));
		return o;
	}

	/**
	 * Biome survey around the world spawn from the noise biome source (no chunk generation): scores 384x384-block
	 * windows by how evenly they mix forest, plains and water. Used once to pick the scenario start position.
	 */
	private JsonObject runSurvey(ServerLevel level) {
		BlockPos spawn = level.getRespawnData().globalPos().pos();
		int range = 3072, step = 32, window = 192;
		int n = 2 * range / step + 1;
		String[][] grid = new String[n][n];
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				int x = spawn.getX() - range + i * step;
				int z = spawn.getZ() - range + j * step;
				grid[i][j] = category(biomeId(level.getUncachedBiome(x, 64, z)));
			}
		}
		record Candidate(int x, int z, double forest, double plains, double water, double mountain, double score) {
		}
		List<Candidate> candidates = new ArrayList<>();
		int w = window / step;
		for (int i = w; i < n - w; i += 2) {
			for (int j = w; j < n - w; j += 2) {
				int total = 0, forest = 0, plains = 0, water = 0, mountain = 0;
				for (int a = -w; a <= w; a++) {
					for (int b = -w; b <= w; b++) {
						if (a * a + b * b > w * w) {
							continue;
						}
						total++;
						switch (grid[i + a][j + b]) {
							case "forest" -> forest++;
							case "plains" -> plains++;
							case "water" -> water++;
							case "mountain" -> mountain++;
							default -> {
							}
						}
					}
				}
				double f = (double) forest / total, p = (double) plains / total, wa = (double) water / total, m = (double) mountain / total;
				double score = Math.min(f, Math.min(p, wa)) - 0.5 * m;
				candidates.add(new Candidate(spawn.getX() - range + i * step, spawn.getZ() - range + j * step, f, p, wa, m, score));
			}
		}
		candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
		JsonObject o = new JsonObject();
		o.addProperty("seed", Long.toString(this.scenario.seed()));
		o.addProperty("spawnX", spawn.getX());
		o.addProperty("spawnZ", spawn.getZ());
		o.addProperty("windowRadiusBlocks", window);
		JsonArray top = new JsonArray();
		for (Candidate c : candidates.subList(0, Math.min(15, candidates.size()))) {
			JsonObject e = new JsonObject();
			e.addProperty("x", c.x());
			e.addProperty("z", c.z());
			e.addProperty("distanceFromSpawn", (int) Math.hypot(c.x() - spawn.getX(), c.z() - spawn.getZ()));
			e.addProperty("forest", FrameStats.round(100 * c.forest(), 1));
			e.addProperty("plains", FrameStats.round(100 * c.plains(), 1));
			e.addProperty("water", FrameStats.round(100 * c.water(), 1));
			e.addProperty("mountain", FrameStats.round(100 * c.mountain(), 1));
			e.addProperty("score", FrameStats.round(c.score(), 3));
			top.add(e);
			Bench.LOGGER.info("[bench/survey] {}", e);
		}
		o.add("candidates", top);
		return o;
	}

	private void writeMeta(Minecraft minecraft) {
		Path dir = minecraft.getLevelSource().getBaseDir().resolve(this.settings.worldName());
		JsonObject o = new JsonObject();
		o.addProperty("scenario", this.scenario.name());
		// Minecraft worldgen is not bit-for-bit reproducible under multithreaded generation, so results are only
		// comparable within one template; every result records this id.
		o.addProperty("templateId", UUID.randomUUID().toString());
		o.addProperty("seed", Long.toString(this.scenario.seed()));
		o.addProperty("minecraft", EnvInfo.minecraftVersion());
		o.addProperty("createdAt", Instant.now().toString());
		JsonObject start = new JsonObject();
		start.addProperty("x", this.scenario.startX());
		start.addProperty("y", this.scenario.startY());
		start.addProperty("z", this.scenario.startZ());
		start.addProperty("yaw", this.scenario.yaw());
		start.addProperty("pitch", this.scenario.pitch());
		start.addProperty("surfaceY", this.surfaceAtStart);
		o.add("start", start);
		int[] box = this.scenario.requiredChunkBox();
		JsonObject region = new JsonObject();
		region.addProperty("minChunkX", box[0]);
		region.addProperty("minChunkZ", box[1]);
		region.addProperty("maxChunkX", box[2]);
		region.addProperty("maxChunkZ", box[3]);
		region.addProperty("chunks", this.chunkQueue == null ? 0 : this.chunkQueue.length / 2);
		o.add("region", region);
		o.addProperty("renderDistance", this.scenario.renderDistance());
		if (this.composition != null) {
			o.add("composition", this.composition);
		}
		JsonArray rules = new JsonArray();
		for (String r : new String[] {"advance_time=false", "advance_weather=false", "spawn_*=false", "random_tick_speed=0",
			"difficulty=peaceful", "gamemode=spectator", "mob AI frozen by the harness"}) {
			rules.add(r);
		}
		o.add("frozen", rules);
		Watchdog.writeJson(dir.resolve("ingot-bench-world.json"), o);
		Watchdog.writeJson(this.settings.outFile(), o);
	}
}
