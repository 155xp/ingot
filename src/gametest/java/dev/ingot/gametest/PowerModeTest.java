package dev.ingot.gametest;

import dev.ingot.IngotConfig;
import dev.ingot.energy.PowerPolicy;
import dev.ingot.energy.RenderScale;
import dev.ingot.gui.IngotSettingsScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Power modes: the settings screens render, and switching to Battery mode with half resolution halves the framebuffer
 * (and switching back restores it) without reopening anything. Screenshots and a summary go to
 * -Dingot.test.out/power-modes.
 */
public class PowerModeTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!TestFilter.runs(this)) {
			return;
		}
		Path out = Path.of(System.getProperty("ingot.test.out", "screenshots/tour")).resolve("power-modes");
		List<String> report = new ArrayList<>();
		IngotConfig config = IngotConfig.get();
		config.powerMode = IngotConfig.PowerMode.PERFORMANCE;
		config.performanceResolution = IngotConfig.Resolution.FULL;

		context.waitForScreen(TitleScreen.class);
		context.setScreen(() -> new VideoSettingsScreen(new TitleScreen(), context.computeOnClient(mc -> mc), context.computeOnClient(mc -> mc.options)));
		context.waitTicks(5);
		shot(context, out, "01-video-settings");
		context.setScreen(() -> new IngotSettingsScreen(new TitleScreen(), context.computeOnClient(mc -> mc.options)));
		context.waitTicks(5);
		shot(context, out, "02-ingot-settings");
		context.setScreen(TitleScreen::new);

		try (TestSingleplayerContext world = context.worldBuilder()
			.adjustSettings(state -> {
				var normal = state.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
				state.setWorldType(new net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry(normal));
				state.setSeed("20261004");
			})
			.create()) {
			var server = world.getServer();
			server.runCommand("gamemode creative @a");
			server.runOnServer(srv -> srv.getPlayerList().getPlayers().forEach(player -> {
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}));
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("tp @a -1023.5 110 640.5 90 20");
			world.getConnection().waitForChunksRender();
			context.waitTicks(40);

			// The game-test harness pins the framebuffer to 854x480 when the client starts; a resolution switch re-reads
			// the window's real size in pixels, which is the reference here.
			int[] full = context.computeOnClient(mc -> {
				int[] width = new int[1];
				int[] height = new int[1];
				org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(mc.getWindow().handle(), width, height);
				return new int[]{width[0], height[0], mc.getWindow().getGuiScale()};
			});
			report.add("performance/full: window pixels " + full[0] + "x" + full[1] + ", gui scale " + full[2] + ", battery=" + context.computeOnClient(mc -> PowerPolicy.isBatteryMode()));
			shot(context, out, "03-performance-full");

			config.powerMode = IngotConfig.PowerMode.BATTERY;
			config.batteryResolution = IngotConfig.Resolution.HALF;
			context.waitTicks(10);
			world.getConnection().waitForChunksRender();
			int[] half = size(context);
			report.add("battery/half: framebuffer " + half[0] + "x" + half[1] + ", gui scale " + half[2] + ", divisor " + context.computeOnClient(mc -> RenderScale.divisor())
				+ ", battery=" + context.computeOnClient(mc -> PowerPolicy.isBatteryMode()));
			shot(context, out, "04-battery-half");
			context.setScreen(() -> new InventoryScreen(world.getConnection().getClientPlayer()));
			context.waitTicks(10);
			shot(context, out, "05-battery-half-inventory");
			context.setScreen(() -> null);

			config.batteryResolution = IngotConfig.Resolution.FULL;
			context.waitTicks(10);
			int[] back = size(context);
			report.add("battery/full: framebuffer " + back[0] + "x" + back[1] + ", gui scale " + back[2]);
			shot(context, out, "06-battery-full");

			boolean sizesOk = half[0] == (full[0] + 1) / 2 && half[1] == (full[1] + 1) / 2 && back[0] == full[0] && back[1] == full[1];
			// The automatic GUI scale halves with the framebuffer, so the GUI covers the same part of the screen.
			// (Compared with the scale after switching back: the pinned startup framebuffer also skewed the first one.)
			boolean guiOk = half[2] * 2 == back[2] || (back[2] == 1 && half[2] == 1);
			boolean ok = sizesOk && guiOk;
			report.add(ok ? "PASS" : "FAIL: framebuffer or GUI scale did not follow the resolution");
			config.powerMode = IngotConfig.PowerMode.PERFORMANCE;
		}
		try {
			Files.createDirectories(out);
			Files.write(out.resolve("summary.txt"), report);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		if (!report.getLast().equals("PASS")) {
			throw new AssertionError(String.join("\n", report));
		}
		context.waitForScreen(TitleScreen.class);
	}

	private static int[] size(ClientGameTestContext context) {
		return context.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getWindow().getGuiScale()});
	}

	private static void shot(ClientGameTestContext context, Path dir, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(dir));
	}
}
