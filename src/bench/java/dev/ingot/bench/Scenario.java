package dev.ingot.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A benchmark scenario (bench/scenarios/&lt;name&gt;.json): the world (seed), where the camera is and the scripted path it
 * follows. The path is defined in space and scaled to the measured duration, so every configuration and every
 * duration renders exactly the same sequence of views.
 */
public record Scenario(
	String name,
	String description,
	long seed,
	double startX,
	double startY,
	double startZ,
	float yaw,
	float pitch,
	int renderDistance,
	int simulationDistance,
	long dayTime,
	double durationSeconds,
	double warmupSeconds,
	double settleTimeoutSeconds,
	List<Phase> phases,
	int prepareMarginChunks,
	double prepareSettleSeconds
) {
	/**
	 * One leg of the camera path. {@code share} is its relative share of the measured duration.
	 * <ul>
	 * <li>{@code spin}: rotate the yaw by {@code degrees} in place at constant angular speed</li>
	 * <li>{@code fly}: move by (dx, dy, dz) blocks at constant speed, keeping yaw and pitch</li>
	 * <li>{@code hold}: stand still</li>
	 * </ul>
	 */
	public record Phase(String name, String type, double share, double degrees, double dx, double dy, double dz) {
	}

	/** Camera pose: player feet position (the eye is 1.62 blocks higher) and rotation in degrees. */
	public record Pose(double x, double y, double z, float yaw, float pitch, int phase) {
	}

	public static Scenario load(Path file) throws IOException {
		JsonObject o;
		try (Reader reader = Files.newBufferedReader(file)) {
			o = JsonParser.parseReader(reader).getAsJsonObject();
		}
		JsonObject start = o.getAsJsonObject("start");
		JsonObject prepare = o.has("prepare") ? o.getAsJsonObject("prepare") : new JsonObject();
		List<Phase> phases = new ArrayList<>();
		JsonArray array = o.getAsJsonArray("phases");
		for (JsonElement e : array) {
			JsonObject p = e.getAsJsonObject();
			String type = p.get("type").getAsString();
			if (!type.equals("spin") && !type.equals("fly") && !type.equals("hold")) {
				throw new IllegalArgumentException("unknown phase type " + type);
			}
			phases.add(new Phase(
				p.has("name") ? p.get("name").getAsString() : type,
				type,
				num(p, "share", 1.0),
				num(p, "degrees", 360.0),
				num(p, "dx", 0.0),
				num(p, "dy", 0.0),
				num(p, "dz", 0.0)
			));
		}
		if (phases.isEmpty()) {
			throw new IllegalArgumentException("scenario has no phases");
		}
		return new Scenario(
			o.get("name").getAsString(),
			o.has("description") ? o.get("description").getAsString() : "",
			Long.parseLong(o.get("seed").getAsString()),
			start.get("x").getAsDouble(),
			start.get("y").getAsDouble(),
			start.get("z").getAsDouble(),
			(float) num(o, "yaw", 0.0),
			(float) num(o, "pitch", 15.0),
			(int) num(o, "renderDistance", 16),
			(int) num(o, "simulationDistance", 12),
			(long) num(o, "dayTime", 6000),
			num(o, "durationSeconds", 60),
			num(o, "warmupSeconds", 15),
			num(o, "settleTimeoutSeconds", 120),
			List.copyOf(phases),
			(int) num(prepare, "marginChunks", 3),
			num(prepare, "settleSeconds", 10)
		);
	}

	private static double num(JsonObject o, String key, double fallback) {
		return o.has(key) ? o.get(key).getAsDouble() : fallback;
	}

	public Pose start() {
		return new Pose(this.startX, this.startY, this.startZ, this.yaw, this.pitch, -1);
	}

	/** Pose at fraction {@code f} (0..1) of the measured path. */
	public Pose poseAt(double f) {
		f = Math.clamp(f, 0.0, 1.0);
		double total = 0;
		for (Phase p : this.phases) {
			total += p.share();
		}
		double x = this.startX, y = this.startY, z = this.startZ;
		double yaw = this.yaw;
		double cursor = 0;
		for (int i = 0; i < this.phases.size(); i++) {
			Phase p = this.phases.get(i);
			double begin = cursor / total;
			double end = (cursor + p.share()) / total;
			cursor += p.share();
			boolean last = i == this.phases.size() - 1;
			if (f < end || last) {
				double local = end > begin ? Math.clamp((f - begin) / (end - begin), 0.0, 1.0) : 1.0;
				switch (p.type()) {
					case "spin" -> yaw += p.degrees() * local;
					case "fly" -> {
						x += p.dx() * local;
						y += p.dy() * local;
						z += p.dz() * local;
					}
					default -> {
					}
				}
				return new Pose(x, y, z, wrap(yaw), this.pitch, i);
			}
			switch (p.type()) {
				case "spin" -> yaw += p.degrees();
				case "fly" -> {
					x += p.dx();
					y += p.dy();
					z += p.dz();
				}
				default -> {
				}
			}
		}
		throw new IllegalStateException("unreachable");
	}

	/** Warmup: one full turn in place at the start position, so every direction is meshed before measuring. */
	public Pose warmupPoseAt(double f) {
		return new Pose(this.startX, this.startY, this.startZ, wrap(this.yaw + 360.0 * Math.clamp(f, 0.0, 1.0)), this.pitch, -1);
	}

	/** Index of the phase active at fraction {@code f}. */
	public int phaseAt(double f) {
		return this.poseAt(f).phase();
	}

	/** Chunk-coordinate bounding box {minX, minZ, maxX, maxZ} that must exist on disk for the whole path. */
	public int[] requiredChunkBox() {
		double minX = this.startX, maxX = this.startX, minZ = this.startZ, maxZ = this.startZ;
		double x = this.startX, z = this.startZ;
		for (Phase p : this.phases) {
			if (p.type().equals("fly")) {
				x += p.dx();
				z += p.dz();
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
		}
		int r = this.renderDistance + this.prepareMarginChunks;
		return new int[] {
			Math.floorDiv((int) Math.floor(minX), 16) - r,
			Math.floorDiv((int) Math.floor(minZ), 16) - r,
			Math.floorDiv((int) Math.floor(maxX), 16) + r,
			Math.floorDiv((int) Math.floor(maxZ), 16) + r
		};
	}

	private static float wrap(double degrees) {
		double d = degrees % 360.0;
		if (d >= 180.0) {
			d -= 360.0;
		} else if (d < -180.0) {
			d += 360.0;
		}
		return (float) d;
	}
}
