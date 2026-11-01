package dev.ingot.bench;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Guarantees that a benchmark process always ends: if the harness has not finished within a generous budget (world
 * load + settle timeouts + warmup + duration + slack) it writes a failure result and halts the JVM. run.sh can then
 * report the failure instead of hanging forever.
 */
final class Watchdog {
	private static volatile boolean finished;

	private Watchdog() {
	}

	static void start(BenchSettings settings) {
		double budget = settings.mode() == BenchSettings.Mode.PREPARE
			? 3600
			: 300 + 2 * settings.settleTimeoutSeconds() + settings.warmupSeconds() + settings.durationSeconds();
		long deadline = System.nanoTime() + (long) (budget * 1e9);
		Thread thread = new Thread(() -> {
			while (!finished) {
				if (System.nanoTime() > deadline) {
					failAndHalt(settings, "watchdog: benchmark did not finish within " + (long) budget + " s");
					return;
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "ingot-bench-watchdog");
		thread.setDaemon(true);
		thread.start();
	}

	static void markFinished() {
		finished = true;
	}

	static void failAndHalt(BenchSettings settings, String reason) {
		Bench.LOGGER.error("[bench] FAILED: {}", reason);
		if (settings != null) {
			JsonObject o = new JsonObject();
			o.addProperty("schema", Bench.RESULT_SCHEMA);
			o.addProperty("config", settings.configName());
			o.addProperty("scenario", settings.scenario().name());
			o.addProperty("mode", settings.mode().name().toLowerCase());
			o.addProperty("valid", false);
			o.addProperty("failed", true);
			o.addProperty("error", reason);
			o.addProperty("timestamp", Instant.now().toString());
			writeJson(settings.outFile(), o);
		}
		Runtime.getRuntime().halt(3);
	}

	static void writeJson(Path file, JsonObject o) {
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o));
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			Bench.LOGGER.info("[bench] wrote {}", file);
		} catch (IOException e) {
			Bench.LOGGER.error("[bench] could not write {}", file, e);
		}
	}
}
