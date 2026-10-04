package dev.ingot.gametest;

import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Walks through scenes that exercise most of the renderer and saves a GPU read-back screenshot of each, named after
 * the active backend, so a Metal run can be diffed against an OpenGL run (bench/compare_shots.py).
 * Output: -Dingot.test.out (default: screenshots/tour) inside the run directory.
 */
public class VisualTourTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		String backend = context.computeOnClient(mc -> com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName())
			.toLowerCase(java.util.Locale.ROOT);
		Path out = Path.of(System.getProperty("ingot.test.out", "screenshots/tour")).resolve(backend);

		// With the display asleep, OpenGL's vsync'd swap blocks forever; the tour does not need vsync on either backend.
		context.runOnClient(mc -> mc.options.enableVsync().set(false));
		context.waitForScreen(TitleScreen.class);
		context.waitTicks(40);
		shot(context, out, "01-title");

		try (TestSingleplayerContext world = context.worldBuilder()
			.adjustSettings(VisualTourTest::normalWorld)
			.create()) {
			var server = world.getServer();
			server.runCommand("gamemode creative @a");
			// Creative players fall unless flying; every shot below is taken mid-air at y=110.
			server.runOnServer(srv -> srv.getPlayerList().getPlayers().forEach(player -> {
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}));
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("tp @a -1023.5 110 640.5 90 20");
			world.getConnection().waitForChunksRender();
			context.waitTicks(60);
			world.getConnection().waitForChunksRender();
			server.runCommand("tick freeze");
			context.waitTicks(5);
			shot(context, out, "02-day-vista");

			server.runCommand("tp @a -1023.5 110 640.5 0 75");
			world.getConnection().waitForChunksRender();
			context.waitTicks(10);
			shot(context, out, "03-looking-down");

			server.runCommand("tick unfreeze");
			server.runCommand("tp @a -1023.5 110 640.5 -90 10");
			server.runCommand("summon pig -1018.5 110 640.5 {NoAI:1b,Rotation:[90f,0f],NoGravity:1b}");
			server.runCommand("summon cow -1018.5 110 643 {NoAI:1b,Rotation:[120f,0f],NoGravity:1b}");
			server.runCommand("summon villager -1018.5 110 638 {NoAI:1b,Rotation:[60f,0f],NoGravity:1b}");
			server.runCommand("summon armor_stand -1016.5 109 640.5 {NoGravity:1b,ShowArms:1b,equipment:{head:{id:\"diamond_helmet\",count:1,components:{\"minecraft:enchantments\":{\"protection\":1}}},chest:{id:\"golden_chestplate\",count:1}}}");
			server.runCommand("give @a diamond_sword[enchantments={sharpness:1}] 1");
			server.runCommand("give @a oak_log 64");
			server.runCommand("give @a glass_pane 16");
			context.waitTicks(20);
			world.getConnection().waitForChunksRender();
			shot(context, out, "04-entities-hand-glint");

			context.setScreen(() -> new InventoryScreen(world.getConnection().getClientPlayer()));
			context.waitTicks(10);
			shot(context, out, "05-inventory");
			context.setScreen(() -> null);
			context.waitTicks(5);

			context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(true));
			context.waitTicks(10);
			shot(context, out, "06-debug-overlay");
			context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));

			server.runCommand("effect give @e[type=!player] glowing 1000 0 true");
			context.waitTicks(10);
			shot(context, out, "07-glowing-outline");

			server.runCommand("time set midnight");
			server.runCommand("tp @a -1023.5 110 640.5 90 -30");
			context.waitTicks(20);
			shot(context, out, "08-night-sky");

			server.runCommand("time set noon");
			server.runCommand("weather rain");
			server.runCommand("tp @a -1023.5 110 640.5 90 5");
			context.waitTicks(80);
			shot(context, out, "09-rain");
			server.runCommand("weather clear");
		}

		context.waitForScreen(TitleScreen.class);
	}

	private static void normalWorld(WorldCreationUiState state) {
		var normal = state.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
		state.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
		state.setSeed("20261004");
	}

	private static void shot(ClientGameTestContext context, Path dir, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(dir));
	}
}
