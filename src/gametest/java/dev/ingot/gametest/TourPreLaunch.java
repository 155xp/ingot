package dev.ingot.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * The game-test run directory is recreated for every run, so options start at their defaults. Vsync must be off
 * before the first frame: with the display asleep, OpenGL's vsync'd buffer swap blocks forever.
 */
public class TourPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
		if (Files.notExists(options)) {
			try {
				// -Dingot.test.api=vulkan|opengl picks the vanilla reference backend (with -Dingot.enabled=false).
				String api = System.getProperty("ingot.test.api");
				Files.writeString(options, "enableVsync:false\n" + (api != null ? "preferredGraphicsBackend:\"" + api + "\"\n" : ""));
			} catch (IOException e) {
				throw new IllegalStateException("Could not seed " + options, e);
			}
		}
	}
}
