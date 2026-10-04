package dev.ingot.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.ingot.Ingot;
import dev.ingot.IngotConfig;
import dev.ingot.nativ.Mtl;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/**
 * Entry point Minecraft sees: tried before the vanilla backends on macOS. Every failure becomes a
 * BackendCreationException so Minecraft moves on to OpenGL/Vulkan exactly as it would without Ingot.
 */
public final class MetalBackend implements GpuBackend {
	private boolean libraryLoaded;

	/** Whether Metal should be put in front of the vanilla backends for this preference. CONTRACT */
	public static boolean shouldTry(PreferredGraphicsApi preference) {
		return Ingot.isMacOs() && preference == PreferredGraphicsApi.DEFAULT && IngotConfig.get().enabled;
	}

	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void loadLibrary() throws BackendCreationException {
		if (this.libraryLoaded) {
			return;
		}
		try {
			int abi = Mtl.abiVersion();
			if (abi != Mtl.ABI_VERSION) {
				throw new BackendCreationException("libingot ABI " + abi + " does not match " + Mtl.ABI_VERSION,
					BackendCreationException.Reason.PLATFORM_ERROR);
			}
		} catch (BackendCreationException e) {
			throw e;
		} catch (Throwable t) {
			throw new BackendCreationException("Could not load the Metal bridge (libingot): " + t, BackendCreationException.Reason.PLATFORM_ERROR);
		}
		this.libraryLoaded = true;
	}

	@Override
	public void unloadLibrary() {
		// A native library cannot be unloaded from a global arena; nothing to do.
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, flags | SDLVideo.SDL_WINDOW_METAL);
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		try {
			GpuDevice device = new FrontendGpuDevice(new MetalDevice(this, debugOptions));
			Ingot.LOGGER.info("Ingot: Metal backend active");
			return device;
		} catch (BackendCreationException e) {
			throw e;
		} catch (Throwable t) {
			Ingot.LOGGER.error("Ingot: Metal device creation failed, falling back", t);
			throw new BackendCreationException("Metal device creation failed: " + t, BackendCreationException.Reason.PLATFORM_ERROR);
		}
	}
}
