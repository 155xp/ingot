package dev.ingot.backend;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.ingot.Ingot;
import dev.ingot.IngotConfig;
import dev.ingot.nativ.Mtl;
import java.util.Locale;
import net.minecraft.client.PreferredGraphicsApi;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Entry point Minecraft sees: tried before the vanilla backends on macOS. Every failure becomes a
 * BackendCreationException so Minecraft moves on to OpenGL/Vulkan exactly as it would without Ingot.
 */
public final class MetalBackend implements GpuBackend {
	/** Whether Metal should be put in front of the vanilla backends for this preference. CONTRACT */
	public static boolean shouldTry(PreferredGraphicsApi preference) {
		return Ingot.isMacOs() && preference == PreferredGraphicsApi.DEFAULT && IngotConfig.get().enabled;
	}

	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void setWindowHints() {
		// No OpenGL context: the surface puts a CAMetalLayer on the window's content view.
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(GLFWErrorCapture.@Nullable Error error) throws BackendCreationException {
		String message = error != null ? String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error()) : "Failed to create a window for Metal";
		throw new BackendCreationException(message, BackendCreationException.Reason.GLFW_ERROR);
	}

	private static void loadLibrary() throws BackendCreationException {
		try {
			int abi = Mtl.abiVersion();
			if (abi != Mtl.ABI_VERSION) {
				throw new BackendCreationException("libingot ABI " + abi + " does not match " + Mtl.ABI_VERSION,
					BackendCreationException.Reason.OTHER);
			}
		} catch (BackendCreationException e) {
			throw e;
		} catch (Throwable t) {
			throw new BackendCreationException("Could not load the Metal bridge (libingot): " + t, BackendCreationException.Reason.OTHER);
		}
	}

	@Override
	public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions, Runnable criticalShaderLoader)
		throws BackendCreationException {
		loadLibrary();
		MetalDevice metalDevice = null;
		try {
			metalDevice = new MetalDevice(this, defaultShaderSource, debugOptions);
			GpuDevice device = new GpuDevice(metalDevice, criticalShaderLoader);
			Ingot.LOGGER.info("Ingot: Metal backend active");
			return device;
		} catch (BackendCreationException e) {
			throw e;
		} catch (Throwable t) {
			// The device can fail after the native device exists (encoder); do not keep it alive next to the backend the
			// game falls back to.
			if (metalDevice != null) {
				try {
					metalDevice.close();
				} catch (Throwable closeFailure) {
					t.addSuppressed(closeFailure);
				}
			}
			Ingot.LOGGER.error("Ingot: Metal device creation failed, falling back", t);
			throw new BackendCreationException("Metal device creation failed: " + t, BackendCreationException.Reason.OTHER);
		}
	}
}
