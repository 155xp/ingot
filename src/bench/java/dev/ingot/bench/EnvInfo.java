package dev.ingot.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Snapshots of the environment that go into the result JSON. */
final class EnvInfo {
	private EnvInfo() {
	}

	static JsonObject renderer() {
		JsonObject o = new JsonObject();
		try {
			DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
			o.addProperty("backend", info.backendName());
			o.addProperty("gpu", info.name());
			o.addProperty("vendor", info.vendorName());
			o.addProperty("driver", info.driverInfo());
			o.addProperty("deviceType", String.valueOf(info.type()));
		} catch (RuntimeException e) {
			o.addProperty("error", e.toString());
		}
		return o;
	}

	static String backendName() {
		try {
			return RenderSystem.getDevice().getDeviceInfo().backendName();
		} catch (RuntimeException e) {
			return "unknown";
		}
	}

	/**
	 * The frame limiter that paces a capped run: "ingot" when Ingot's mixin replaces FramerateLimiter.limitDisplayFPS
	 * and its native library is loaded (dev.ingot.energy.PowerPolicy.NATIVE; without it Ingot falls back to vanilla's
	 * limiter), otherwise "vanilla". Reflection only, the harness does not compile against Ingot. Call it after a few
	 * frames: by then the class is initialized, so reading the field has no side effects.
	 */
	static String frameLimiter() {
		boolean replaced = false;
		for (Method m : FramerateLimiter.class.getDeclaredMethods()) {
			if (m.getName().contains("ingot$")) {
				replaced = true;
				break;
			}
		}
		if (!replaced) {
			return "vanilla";
		}
		try {
			Class<?> policy = Class.forName("dev.ingot.energy.PowerPolicy", false, FramerateLimiter.class.getClassLoader());
			Field nativeField = policy.getDeclaredField("NATIVE");
			nativeField.setAccessible(true);
			return nativeField.getBoolean(null) ? "ingot" : "vanilla (Ingot's mixin is applied but libingot is unavailable)";
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return "ingot (unverified: " + e + ")";
		}
	}

	static JsonObject display(Minecraft minecraft) {
		Window window = minecraft.getWindow();
		JsonObject o = new JsonObject();
		o.addProperty("framebufferWidth", window.getWidth());
		o.addProperty("framebufferHeight", window.getHeight());
		o.addProperty("windowWidth", window.getScreenWidth());
		o.addProperty("windowHeight", window.getScreenHeight());
		o.addProperty("pixelDensity", window.getScreenWidth() > 0 ? (float) window.getWidth() / window.getScreenWidth() : 1.0F);
		o.addProperty("guiScale", window.getGuiScale());
		o.addProperty("fullscreen", minecraft.options.fullscreen().get());
		o.addProperty("refreshRate", refreshRate(minecraft));
		try {
			o.addProperty("mainTargetWidth", minecraft.gameRenderer.mainRenderTarget().width);
			o.addProperty("mainTargetHeight", minecraft.gameRenderer.mainRenderTarget().height);
		} catch (RuntimeException ignored) {
			// not every backend exposes it the same way; the window framebuffer size is the authoritative value
		}
		return o;
	}

	/** Refresh rate in Hz of the display that shows the window (120 on a ProMotion panel), or -1 if unknown. */
	static int refreshRate(Minecraft minecraft) {
		try {
			int rate = minecraft.getWindow().getRefreshRate();
			return rate >= 1 ? rate : -1;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	static JsonObject options(Minecraft minecraft) {
		Options opt = minecraft.options;
		JsonObject o = new JsonObject();
		o.addProperty("renderDistance", opt.renderDistance().get());
		o.addProperty("effectiveRenderDistance", opt.getEffectiveRenderDistance());
		o.addProperty("simulationDistance", opt.simulationDistance().get());
		o.addProperty("graphicsPreset", opt.graphicsPreset().get().getSerializedName());
		o.addProperty("preferredGraphicsBackend", opt.preferredGraphicsBackend().get().getSerializedName());
		o.addProperty("enableVsync", opt.enableVsync().get());
		o.addProperty("maxFps", opt.framerateLimit().get());
		o.addProperty("inactivityFpsLimit", opt.inactivityFpsLimit().get().getSerializedName());
		o.addProperty("pauseOnLostFocus", opt.pauseOnLostFocus);
		o.addProperty("guiScaleOption", opt.guiScale().get());
		o.addProperty("fov", opt.fov().get());
		o.addProperty("ambientOcclusion", opt.ambientOcclusion().get());
		o.addProperty("biomeBlendRadius", opt.biomeBlendRadius().get());
		o.addProperty("clouds", opt.cloudStatus().get().getSerializedName());
		o.addProperty("cloudRange", opt.cloudRange().get());
		o.addProperty("particles", String.valueOf(opt.particles().get()));
		o.addProperty("mipmapLevels", opt.mipmapLevels().get());
		o.addProperty("textureFiltering", String.valueOf(opt.textureFiltering().get()));
		o.addProperty("entityShadows", opt.entityShadows().get());
		o.addProperty("entityDistanceScaling", opt.entityDistanceScaling().get());
		o.addProperty("cutoutLeaves", opt.cutoutLeaves().get());
		o.addProperty("improvedTransparency", opt.improvedTransparency().get());
		o.addProperty("prioritizeChunkUpdates", String.valueOf(opt.prioritizeChunkUpdates().get()));
		o.addProperty("chunkSectionFadeInTime", opt.chunkSectionFadeInTime().get());
		return o;
	}

	static JsonArray mods() {
		List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
		mods.sort(Comparator.comparing(m -> m.getMetadata().getId()));
		JsonArray array = new JsonArray();
		for (ModContainer mod : mods) {
			JsonObject o = new JsonObject();
			o.addProperty("id", mod.getMetadata().getId());
			o.addProperty("name", mod.getMetadata().getName());
			o.addProperty("version", mod.getMetadata().getVersion().getFriendlyString());
			mod.getContainingMod().ifPresent(parent -> o.addProperty("nestedIn", parent.getMetadata().getId()));
			array.add(o);
		}
		return array;
	}

	static JsonObject java() {
		JsonObject o = new JsonObject();
		o.addProperty("version", Runtime.version().toString());
		o.addProperty("vendor", System.getProperty("java.vendor"));
		o.addProperty("vmName", System.getProperty("java.vm.name"));
		o.addProperty("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
		o.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
		JsonArray gcs = new JsonArray();
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			gcs.add(gc.getName());
		}
		o.add("collectors", gcs);
		JsonArray args = new JsonArray();
		for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			// Loom's dev launch passes long classpath/config paths; keep the flags that affect performance.
			if (arg.startsWith("-X") || arg.startsWith("-XX") || arg.startsWith("-Dingot") || arg.startsWith("--enable")) {
				args.add(arg);
			}
		}
		o.add("jvmArgs", args);
		return o;
	}

	static JsonObject os() {
		JsonObject o = new JsonObject();
		o.addProperty("name", System.getProperty("os.name"));
		o.addProperty("version", System.getProperty("os.version"));
		o.addProperty("arch", System.getProperty("os.arch"));
		return o;
	}

	static String minecraftVersion() {
		return SharedConstants.getCurrentVersion().name();
	}

	/** Cumulative JIT compilation time in ms (all compiler threads), or -1 if the VM does not report it. */
	static long jitMillis() {
		try {
			var jit = ManagementFactory.getCompilationMXBean();
			return jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : -1;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/** Cumulative CPU time of this process (all threads) in ns, or -1 if unavailable. */
	static long processCpuNanos() {
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				return os.getProcessCpuTime();
			}
		} catch (RuntimeException | LinkageError ignored) {
			// fall through
		}
		return -1;
	}

	/**
	 * System-wide CPU load (0..1, all cores) since the previous call, from the host CPU tick counters, or -1 if the
	 * platform does not report it.
	 */
	static double systemCpuLoad() {
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				double load = os.getCpuLoad();
				return load >= 0 ? load : -1;
			}
		} catch (RuntimeException | LinkageError ignored) {
			// fall through
		}
		return -1;
	}

	/** CPU time of the calling thread in ns, or -1 if unavailable. */
	static long currentThreadCpuNanos() {
		try {
			var threads = ManagementFactory.getThreadMXBean();
			return threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/** Sum of collection counts and times over all collectors: {count, millis}. */
	static long[] gcTotals() {
		long count = 0;
		long time = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += Math.max(0, gc.getCollectionCount());
			time += Math.max(0, gc.getCollectionTime());
		}
		return new long[] {count, time};
	}

	/** Per-collector {name -> [count, millis]} snapshot, for deltas over the measured window. */
	static JsonObject gcDelta(JsonObject before) {
		JsonObject o = new JsonObject();
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			long count = gc.getCollectionCount();
			long time = gc.getCollectionTime();
			if (before != null && before.has(gc.getName())) {
				JsonObject b = before.getAsJsonObject(gc.getName());
				count -= b.get("count").getAsLong();
				time -= b.get("ms").getAsLong();
			}
			JsonObject e = new JsonObject();
			e.addProperty("count", count);
			e.addProperty("ms", time);
			o.add(gc.getName(), e);
		}
		return o;
	}
}
