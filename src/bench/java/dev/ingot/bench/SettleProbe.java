package dev.ingot.bench;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Decides when chunk loading and meshing have settled. The world is "idle" when the renderer reports an empty compile
 * queue; it is "settled" once it has been idle with an unchanged signature (loaded chunk count + rendered/visible
 * section count) for a continuous window.
 *
 * <p>Vanilla (and Ingot, which keeps the vanilla level renderer) is queried directly. Sodium replaces the section
 * renderer, so it is queried through its public SodiumWorldRenderer by reflection (no compile-time dependency). Any
 * other renderer falls back to the chunk-count signature alone.
 */
final class SettleProbe {
	private static final String SODIUM_RENDERER = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer";
	private static Method sodiumInstance;
	private static Method sodiumComplete;
	private static Method sodiumVisible;
	private static boolean sodiumResolved;

	private final double stableSeconds;
	private String lastSignature;
	private long stableSince = -1;
	private Snapshot last;

	SettleProbe(double stableSeconds) {
		this.stableSeconds = stableSeconds;
	}

	record Snapshot(String renderer, boolean idle, int loadedChunks, int sections, int queue) {
		String signature() {
			return this.loadedChunks + "/" + this.sections;
		}

		JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("renderer", this.renderer);
			o.addProperty("idle", this.idle);
			o.addProperty("loadedChunks", this.loadedChunks);
			o.addProperty("sections", this.sections);
			o.addProperty("compileQueue", this.queue);
			return o;
		}
	}

	void reset() {
		this.lastSignature = null;
		this.stableSince = -1;
	}

	Snapshot last() {
		return this.last;
	}

	/** @return true once the world has been idle and unchanged for the stable window */
	boolean poll(Minecraft minecraft, long now) {
		Snapshot s = snapshot(minecraft);
		this.last = s;
		if (!s.idle()) {
			this.stableSince = -1;
			this.lastSignature = null;
			return false;
		}
		String sig = s.signature();
		if (!sig.equals(this.lastSignature)) {
			this.lastSignature = sig;
			this.stableSince = now;
			return false;
		}
		return (now - this.stableSince) / 1e9 >= this.stableSeconds;
	}

	static Snapshot snapshot(Minecraft minecraft) {
		int loaded = minecraft.level == null ? 0 : minecraft.level.getChunkSource().getLoadedChunksCount();
		Object sodium = sodiumRenderer();
		if (sodium != null) {
			try {
				boolean complete = (Boolean) sodiumComplete.invoke(sodium);
				int visible = sodiumVisible == null ? 0 : (Integer) sodiumVisible.invoke(sodium);
				return new Snapshot("sodium", complete, loaded, visible, complete ? 0 : -1);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return new Snapshot("sodium", false, loaded, 0, -1);
			}
		}
		try {
			SectionRenderDispatcher dispatcher = minecraft.levelRenderer.sectionRenderDispatcher();
			int queue = dispatcher == null ? 0 : dispatcher.getCompileQueueSize();
			int rendered = minecraft.levelExtractor.countRenderedSections();
			boolean idle = queue == 0 && minecraft.levelRenderer.hasRenderedAllSections();
			return new Snapshot("vanilla", idle, loaded, rendered, queue);
		} catch (RuntimeException e) {
			return new Snapshot("unknown", true, loaded, 0, -1);
		}
	}

	private static Object sodiumRenderer() {
		if (!sodiumResolved) {
			sodiumResolved = true;
			try {
				Class<?> c = Class.forName(SODIUM_RENDERER);
				sodiumInstance = c.getMethod("instanceNullable");
				sodiumComplete = c.getMethod("isTerrainRenderComplete");
				try {
					sodiumVisible = c.getMethod("getVisibleChunkCount");
				} catch (NoSuchMethodException ignored) {
					sodiumVisible = null;
				}
				Bench.LOGGER.info("[bench] Sodium detected; using SodiumWorldRenderer.isTerrainRenderComplete() for settling");
			} catch (ReflectiveOperationException | LinkageError e) {
				sodiumInstance = null;
			}
		}
		if (sodiumInstance == null) {
			return null;
		}
		try {
			return sodiumInstance.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
