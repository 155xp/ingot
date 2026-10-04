# Integration points: plugging a Metal backend into Minecraft 26.3

Scope: everything **outside** the renderpearl backend internals that touches backend selection, windowing, the
presentation surface, shutdown, diagnostics, and the UI. The aim is that an engineer can write `MetalBackend`,
`MetalSurface` and the Fabric mixins from this document without re-reading the client sources.

Sources (Vineflower decompile of the 26.3 client, unobfuscated names):

- `SRC = scratchpad/mc/src-full/` (whole client), `RP = scratchpad/mc/src-rp/com/mojang/renderpearl/`
- Line numbers cited as `File.java:NNN` refer to these decompiled files. They are good for orientation; anchor
  mixins on method names and invoke targets instead.
- LWJGL facts come from `javap` on `lwjgl-sdl-3.4.3.jar` / `lwjgl-3.4.3.jar` (Gradle cache, same artifacts as the
  version JSON).
- The "Empirical" sections come from probe programs run on this Mac (Apple M4 Pro, macOS 27.2, JDK 25.0.1,
  LWJGL 3.4.3+4, SDL 3.4.14). Appendix A has the probe code.
- Anything not verified in source or by probe is marked **[speculative]**.

---

## 0. Key facts

1. **The device is created before the window.** `Minecraft.<init>` runs `SDL_Init(VIDEO)`, tries each `GpuBackend`
   (`loadLibrary()` then `createDevice()`), and only then constructs `Window`, which calls
   `backend.createWindow(title, w, h, 8224L)`. After that it calls `device.createSurface(window.handle(), window::isIconified)`.
   `MetalDevice` therefore cannot depend on a window or layer.
2. `8224L` = `0x2020` = `SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY`. GL ORs in `SDL_WINDOW_OPENGL` (2) and
   Vulkan ORs in `SDL_WINDOW_VULKAN` (0x10000000). Metal should OR in `SDL_WINDOW_METAL` (0x20000000 = 536870912).
3. **Only `BackendCreationException` triggers fallback** to the next backend. Any other throwable from
   `loadLibrary()`/`createDevice()` crashes the game ("Initializing game"). Window creation and `createSurface` have
   no fallback at all.
4. The frame protocol is `configure (when needed) → acquireNextTexture → render → blitFromTexture → submit → present`.
   **`present()` is called after the command encoder's `submit()`**, which matters for where `presentDrawable:` goes.
5. Present mode: vsync on → `FIFO` (must be supported, otherwise `IllegalStateException` crash). Vsync off →
   first supported of `IMMEDIATE, MAILBOX, FIFO`. The GL surface supports `{FIFO, IMMEDIATE}`. A `CAMetalLayer` maps
   to those two through `displaySyncEnabled`.
6. Framebuffer size is always `SDL_GetWindowSizeInPixels` (physical pixels; 2x points on Retina because of
   `HIGH_PIXEL_DENSITY`). The surface is configured with exactly that.
7. Backend-agnostic diagnostics need no mixins. F3, the crash report, logs and telemetry all read
   `DeviceInfo.backendName()/name()/vendorName()/driverInfo()/type()`.
8. Outside renderpearl, nothing casts to `GlDevice`/`VulkanDevice` or calls `org.lwjgl.opengl`. The only hard
   coupling is in `PreferredGraphicsApi.getBackendsToTry()` and in `VulkanBackend.checkBackendAvailable()` (called from
   `Minecraft.<init>`). Inside the frontend, `FrontendRenderPass` casts every buffer to `backend.common.BaseGpuBuffer`.
   Metal buffers **must** extend it.
9. The recommended hook is a mixin on `PreferredGraphicsApi#getBackendsToTry()` (RETURN) that prepends `MetalBackend`
   when `this == DEFAULT` on macOS. Vanilla's `lastStartWasClean` fallback then applies automatically: an unclean
   start under DEFAULT forces OpenGL.

---

## 1. The `GpuBackend` entry point (what the game calls)

`RP/api/device/GpuBackend.java`:

```java
public interface GpuBackend {
   String getName();
   void loadLibrary() throws BackendCreationException;
   void unloadLibrary();
   long createWindow(@Nullable String title, int width, int height, long flags);
   GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException;
}
```

The only call sites outside renderpearl are:

| Method | Caller | When |
|---|---|---|
| `new XxxBackend()` | `PreferredGraphicsApi.getBackendsToTry()` | once, in `Minecraft.<init>` |
| `getName()` | `Minecraft.<init>` | only on failure: log `"Failed to create backend {}"` and the error message `"- Tried <name>: <msg>"` |
| `loadLibrary()` | `Minecraft.<init>` loop | before `createDevice`. Must throw `BackendCreationException` on failure |
| `createDevice(GpuDebugOptions)` | `Minecraft.<init>` loop | right after `loadLibrary()` |
| `unloadLibrary()` | `Minecraft.<init>` loop | only if `loadLibrary()` succeeded **and** `createDevice` threw `BackendCreationException` |
| `unloadLibrary()` | `RenderSystem.unloadTrackedBackendLibrary()` | at shutdown, after the window is destroyed (see §8) |
| `createWindow(title, w, h, 8224L)` | `Window.createWindow` (`Window.java:163`) | once, after the device exists. A return of `0` → `IllegalStateException("Failed to create window: …")` (crash, no fallback) |

`GpuDebugOptions` (record) = `(int logLevel, boolean synchronousLogs, boolean useLabels, boolean useValidationLayers)`.
`Minecraft` builds it as:
```java
new GpuDebugOptions(this.options.glDebugVerbosity,            // options.txt "glDebugVerbosity", default 1
                    SharedConstants.DEBUG_SYNCHRONOUS_GL_LOGS,
                    gameConfig.game.renderDebugLabels,         // --renderDebugLabels
                    gameConfig.game.vulkanValidation)          // --vulkanValidation
```
Suggested Metal mapping **[speculative]**: `useLabels` → `MTLCommandBuffer/encoder.label` + `pushDebugGroup`;
`useValidationLayers` → only meaningful if the process was launched with `MTL_DEBUG_LAYER=1` (cannot be enabled
at runtime), so log a hint.

### 1.1 How the vanilla backends implement it (reference)

`RP/backend/opengl/GlBackend.java`
- `getName()` → `"OpenGL"`.
- `loadLibrary()`: `SDL_GL_LoadLibrary(GL.getFunctionProvider().getPath())` and a `glGetError` address sanity check.
  Failures are cached in `libraryLoadFailure` and rethrown on later calls (`Reason.OPENGL_MISSING`).
- `createWindow`: sets `SDL_GL_CONTEXT_MAJOR_VERSION(17)=3`, `MINOR(18)=3`, `PROFILE_MASK(20)=CORE(1)`,
  `CONTEXT_FLAGS(19)=FORWARD_COMPATIBLE(2)`, `FRAMEBUFFER_SRGB_CAPABLE(22)=1`, then
  `SDL_CreateWindow(title, w, h, 2L | flags)`.
- `createDevice` → `new FrontendGpuDevice(new GlDevice(this, debugOptions))`. `GlDevice` creates its own
  **hidden utility window** (flags `2147614728L` = `0x80020008` = `NOT_FOCUSABLE|UTILITY|HIDDEN`, plus `OPENGL` from
  `createWindow`) to host the context, because the real window does not exist yet. It also makes and destroys a second
  test window.

`RP/backend/vulkan/VulkanBackend.java`
- `getName()` → `"Vulkan"`.
- `loadLibrary()`: requires `NativeLibrariesBootstrap.isVulkanLoaderAvailable()`, then
  `SDL_Vulkan_LoadLibrary(VK.getFunctionProvider().getPath())` plus a `vkGetInstanceProcAddr` check.
- `createWindow` → `SDL_CreateWindow(title, w, h, 268435456L | flags)` (`SDL_WINDOW_VULKAN`).
- `createDevice` builds the instance, physical device, `VkDevice` and VMA with no window or surface, then
  `return new FrontendGpuDevice(new VulkanDevice(...))`.
- `static BackendCreationException checkBackendAvailable()` creates a probe instance and physical device and returns
  `null` on success. `Minecraft` calls it for `DEFAULT` (§3.3).

**Contract for `MetalBackend`** (derived from the above):
- `createDevice` must return `new FrontendGpuDevice(new MetalDevice(...))`. The public constructor is
  `FrontendGpuDevice(GpuDeviceBackend)`. The game relies on the frontend wrappers; for example,
  `FrontendGpuSurface.blitFromTexture` requires a `FrontendCommandEncoder`.
- Convert **every** failure (missing dylib, `UnsatisfiedLinkError`, `MTLCreateSystemDefaultDevice() == nil`, missing GPU
  family, FFM errors) into `new BackendCreationException(msg, Reason.PLATFORM_ERROR or Reason.OTHER)`. No `METAL_*`
  reason exists in `BackendCreationException.Reason`. Never use `OPENGL_MISSING`, because that reason is special-cased
  (§3.4).
- Cache a load failure and rethrow it, as both vanilla backends do. This is harmless.
- `createWindow`: `SDLVideo.SDL_CreateWindow(title, width, height, flags | SDLVideo.SDL_WINDOW_METAL)`. Do not add
  `OPENGL` or `VULKAN`: SDL rejects the combination (Empirical §11.3).

---

## 2. Process-level startup order (Main → Minecraft constructor)

### 2.1 `net/minecraft/client/main/Main.java`

1. `SharedConstants.tryDetectVersion()`, then a jopt-simple `OptionParser` with `allowsUnrecognizedOptions()`.
   Graphics-related options:
   - `--graphicsBackend <v>` (`Main.java:82`): `withValuesConvertedBy(new EnumConverter<PreferredGraphicsApi>…)`.
     jopt-simple's `EnumConverter.convert` matches `Enum.name()` with `equalsIgnoreCase` (verified with javap on
     jopt-simple 5.0.4), so `default|opengl|vulkan` work in any case.
     **An unknown value such as `metal` throws inside the "Argument parsing" stage, and `Main` turns that into a crash
     report (exit -1).** Do not tell users to pass `--graphicsBackend metal`. Use a system property such as
     `-Dingot.backend=…`.
   - `--renderDebugLabels`, `--vulkanValidation`, `--fullscreen`, `--width` (default 854), `--height` (default 480),
     `--fullscreenWidth/Height`, `--tracy`, `--tracyNoImages`, `--checkGlErrors` (accepted, unused here).
2. `NativeLibrariesBootstrap.loadLibraries()` (`Main.java:127`), before logging and bootstrap (§9).
3. `Bootstrap.bootStrap()`, then `ClientBootstrap.bootstrap()` (`Main.java:160`). This calls
   `VulkanFeatureSets.bootstrap()`, which is pure Java and harmless on macOS.
4. `GameConfig` is built. `GameConfig.GameData.forcedGraphicsApi` is the `@Nullable PreferredGraphicsApi` from
   `--graphicsBackend`. `DisplayData(width, height, fullscreenWidth, fullscreenHeight, isFullscreen)`.
5. Thread is renamed `"Render thread"`, then `RenderSystem.initRenderThread()` and `new Minecraft(gameConfig)`
   (`Main.java:268-269`).
   - `SilentInitException` (including `Window.WindowInitFailed`) → log `"Failed to create window"` and return.
     No crash report is written.
   - Any other `Throwable` → crash report "Initializing game".
6. `minecraft.run()`, then `minecraft.exitWorldAndClose()`, then
   `ClientShutdownWatchdog.startShutdownWatchdog("post-main", true, …)` (§8).

On macOS the vanilla launcher passes `-XstartOnFirstThread` (version JSON `arguments.jvm`, rule `os.name == osx`), so
**the render thread is the process main thread (the AppKit thread)**. All window, view and layer work in
`Minecraft.<init>` and `renderFrame` happens on it. The JSON also passes `--enable-native-access=ALL-UNNAMED`, which
covers FFM restricted methods for mods loaded by Fabric's Knot loader (unnamed module). Ingot's `build.gradle`
already adds the same flag to Loom run configs. Loom normally also adds `-XstartOnFirstThread` for macOS client
runs **[speculative: check the generated run config]**.

### 2.2 `Minecraft.<init>` (graphics-relevant statements in order)

| # | Line | Statement | Notes for Metal |
|---|---|---|---|
| 1 | ~427 | `this.fixerUpper = DataFixers.getDataFixer();` | |
| 2 | **428** | `this.gameThread = Thread.currentThread();` | **The Fabric client entrypoint hook is inserted immediately before this** (§2.3). `ClientModInitializer.onInitializeClient()` runs here: options are not loaded and SDL is not initialized. |
| 3 | 429 | `this.options = new Options(this, gameDirectory)` | `options.txt` loaded, `preferredGraphicsBackendFromStartup` captured (`Options.java:1789`). |
| 4 | 431-433 | `lastStartWasClean = options.startedCleanly; options.startedCleanly = false; options.save();` | Crash sentinel (§3.2). |
| 5 | 437 | `LOGGER.info("Backend library: {}", RenderSystem.getBackendDescription())` | Prints `"LWJGL version 3.4.3…"`. |
| 6 | 438-450 | `DisplayData` adjustments (`overrideWidth/Height`, `fullscreen` option). If `!lastStartWasClean`, force windowed and clear `fullscreenVideoModeString`. | |
| 7 | **454** | `Util.setTimeSource(RenderSystem.initBackendSystem())` | SDL hints, app metadata, `SDL_Init(SDL_INIT_VIDEO = 32)`. The time source becomes `SDL_GetTicksNS`. |
| 8 | 455 | `this.monitorManager = new MonitorManager()` | `SDL_GetDisplays()`, `Monitor.tryCreate` per display. |
| 9 | 459-473 | forced API / unclean-start rewrite of the preference | §3.2 |
| 10 | 475-479 | `backendsToTry = preferred.getBackendsToTry()`; if `preferred == DEFAULT` → `backendCreationException = VulkanBackend.checkBackendAvailable()` | §3.3. On macOS this creates a MoltenVK instance just to probe. |
| 11 | 481 | `initialWindowTitle = createTitle()` | |
| 12 | 483-507 | **backend loop**: `loadLibrary(); createDevice(new GpuDebugOptions(...))` | §3.4 |
| 13 | 509-514 | `device == null` → `MessageBox.error(...)` + `throw new Window.WindowInitFailed(msg)` | Message: `"No supported graphics backend was found."` plus a `"- Tried X: msg"` line per backend and the driver advice. |
| 14 | 516 | `RenderSystem.initRenderer(device)` | Creates `DynamicGpuData` (2 `DynamicGpuDataStorageMapped` → `MappableRingBuffer`s: `"Dynamic Transforms UBO"`, `"Terrain UBO"`, buffer usage `128 | 2`, block size rounded to `minUniformOffsetAlignment`) and `SamplerCache.initialize()` (32 samplers via `device.createSampler`). **Resource creation happens before any window or surface exists.** |
| 15 | 517 | `RenderSystem.trackBackendLibraryForShutdown(backend)` | |
| 16 | 518-522 | logs `"Using graphics backend {}, using drivers: {}"` (`backendName`, `driverInfo`), `"Using graphics device: {} ({})"` (`name`, `vendorName`), `"Using graphics device extensions: {}"` (`underlyingExtensions`); `maxSize = deviceInfo.limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM)` | `maxTextureSizeForFormat(f) = Integer.highestOneBit(min(maxTextureSize, (int)sqrt(maxMemoryAllocationSize / f.blockSize())))`. |
| 17 | **523-532** | `this.window = new Window(this, displayData, fullscreenVideoModeString, exclusiveFullscreen, initialWindowTitle, monitorManager, backend, maxSize)` | Calls `backend.createWindow(...)` (§6.1). The window is **visible immediately** (no HIDDEN flag). |
| 18 | 534-538 | `window.setIcon(...)` | |
| 19 | **540** | `this.windowSurface = device.createSurface(window.handle(), window::isIconified)` | Through `FrontendGpuDevice.createSurface` → `new FrontendGpuSurface(backend.createSurface(handle, isIconified))`. **Create the SDL Metal view and layer here.** No fallback if this throws. |
| 20 | 541-544 | `new SDLEventHandler`, `new TextInputManager`, `sdlEventHandler.flushInputEvents()`, close callback (starts a 15 s shutdown watchdog when the window is closed) | |
| 21 | 558 | `options.applyGraphicsPreset(...)` | Reads `hintsAndWorkarounds().anisotropyHasKnownIssues()`. |
| 22 | 568-660 | Texture, shader, font, model managers. `GameRenderer` creates `MainTarget(window.getWidth(), window.getHeight())`. `LevelRenderer` reads `DeviceFeatures/DeviceLimits/HintsAndWorkarounds` | Main color target: `GpuFormat.RGBA8_UNORM`, usage `15` (COPY_DST\|COPY_SRC\|TEXTURE_BINDING\|RENDER_ATTACHMENT); depth `D32_FLOAT`. |
| 23 | 665-683 | If `mainRenderTarget` size ≠ window framebuffer size → `"Recovering from unsupported resolution"`, `device.getLastDebugMessages()` (inside try/catch Throwable), `window.setWindowed(...)`, `MessageBox.error` | Only happens when `MainTarget` fell back to 854x480 (size > `maxTextureSize` or OOM). |
| 24 | 686-692 | cursor/quit-shortcut options; `MacosUtil.setCtrlClickEmulatesRightClick`, `MacosUtil.setFullscreenMenuVisibility` (SDL hints) | |
| 25 | 693-694 | `resizeGui()`, `loadCriticalShaders()` | Pipeline compiles happen **before the first surface configure**. |
| 26 | **705** | `this.timerQuery = new TimerQuery()` → `device.createTimestampQueryPool(6)` | **Unconditional.** `createTimestampQueryPool` must work, not throw. |
| 27 | 707-729 | loading screen, resource reload starts, `FramerateLimitTracker`, Tracy capture | |
| 28 | **731-733** | `sdlEventHandler.pumpEvents(); framebufferSizeChanged(); renderFrame(false);` | **First configure, acquire, blit, submit and present happen inside the constructor.** |
| 29 | 734-745 | telemetry `GRAPHICS_CAPABILITIES`: `BACKEND_NAME = deviceInfo.backendName()` and backend failure fields from `backendCreationException` | "Metal" is sent as the backend name. |

`onGameLoadFinished` (after the initial resource reload) sets `options.startedCleanly = true; options.save();`.

### 2.3 Where Fabric mod code runs relative to this

Derived from `net/fabricmc/loader/impl/game/minecraft/patch/EntrypointPatch` bytecode in fabric-loader 0.19.5 (the
version pinned in `gradle.properties`). For game versions `>= 1.19.4`, the loader finds the last
`INVOKESTATIC java/lang/Thread.currentThread()` in `Minecraft.<init>` and inserts
`Hooks.startClient(this.gameDirectory, this)` **before** it. In the 26.3 bytecode there is exactly one such call
(offset 555, the `this.gameThread = …` line). Consequences:

- `onInitializeClient` runs **before `Options` are loaded** (`Minecraft.getInstance().options == null`), before
  `SDL_Init`, and before backend selection. It is a good place to load `libingot.dylib`, read
  `config/ingot.json`, and maybe pre-check `MTLCreateSystemDefaultDevice()`. It is **not** a place to read the
  vanilla option.
- Mixins are applied at class-load time, so they are in place before `PreferredGraphicsApi` and `Minecraft` code
  run.
- [speculative] Confirm empirically by logging in `onInitializeClient` and checking that the line appears before
  `"Backend library: LWJGL version …"`.

---

## 3. Backend selection logic in detail

### 3.1 `net/minecraft/client/PreferredGraphicsApi.java` (whole relevant body)

```java
public enum PreferredGraphicsApi implements StringRepresentable {
   DEFAULT("default", "options.graphicsApi.default"),
   OPENGL("opengl", "options.graphicsApi.opengl"),
   VULKAN("vulkan", "options.graphicsApi.vulkan");
   public static final Codec<PreferredGraphicsApi> CODEC = StringRepresentable.fromEnum(PreferredGraphicsApi::values);
   public Component caption() { return this.key; }          // Component.translatable(key)
   public String getSerializedName() { return this.serializedName; }
   public GpuBackend[] getBackendsToTry() {
      GlBackend gl = new GlBackend();
      VulkanBackend vulkan = new VulkanBackend();
      return this == VULKAN ? new GpuBackend[]{vulkan, gl} : new GpuBackend[]{gl, vulkan};
   }
}
```
`DEFAULT` and `OPENGL` produce the same order, `[GL, Vulkan]`. `DEFAULT` differs only in that it triggers the Vulkan
probe and in how the unclean-start logic treats it.

English strings (`en_us.json`): `options.graphicsApi` = "Graphics API", `.default` = "Default", `.opengl` =
"Prefer OpenGL", `.vulkan` = "Prefer Vulkan (Experimental)", `.tooltip` = "Which graphics API to use for rendering.
Leave as Default unless you have a specific need to change this!", `.tooltip.vulkan` = "Experimental. Might reduce
performance or cause instability on some systems."

### 3.2 Forced API and the `lastStartWasClean` logic (`Minecraft.java:459-475`)

```java
PreferredGraphicsApi forcedGraphicsApi = gameConfig.game.forcedGraphicsApi;
if (forcedGraphicsApi != null) {
   LOGGER.warn("Graphics backend forced to {} by launch argument, in-game preferred graphics backend setting is ignored", ...);
} else if (!lastStartWasClean) {
   if (options.preferredGraphicsBackend().get() == VULKAN) {          // VULKAN -> DEFAULT, saved immediately
      LOGGER.warn("... resetting preferred graphics API to Default");
      options.preferredGraphicsBackend().set(DEFAULT);
      options.save();
   } else if (options.preferredGraphicsBackend().get() == DEFAULT) {  // DEFAULT -> OPENGL, NOT saved here
      LOGGER.warn("... forcing preferred graphics API to OpenGL");
      options.preferredGraphicsBackend().set(OPENGL);
   }
}
PreferredGraphicsApi preferredGraphicsBackend = forcedGraphicsApi == null ? options.preferredGraphicsBackend().get() : forcedGraphicsApi;
```

Semantics and pitfalls:
- `startedCleanly` is written `false` at the start of every launch and `true` only in `onGameLoadFinished` (after the
  initial resource reload and first screen). **Any crash or kill between those points, including a native crash in
  Metal device or pipeline creation or during the loading overlay, makes the next launch "unclean".** A crash after
  load finished does not.
- Unclean + `DEFAULT` → `OPENGL` for this launch. The value is not saved in that branch, but the next
  `options.save()` (at the latest `onGameLoadFinished`) **persists `opengl` in options.txt**. The user stays on
  "Prefer OpenGL" until they switch back. Side effect: `isRestartRequiredToApplyVideoSettings()` compares against
  `preferredGraphicsBackendFromStartup` (still `DEFAULT`), so the Video Settings screen shows "restart required" for
  that session.
- Unclean + `VULKAN` → `DEFAULT`, saved immediately.
- Unclean also forces windowed mode (step 6 in §2.2).
- A forced API (`--graphicsBackend`) bypasses all of this, including when the forced value is `default`.

### 3.3 Vulkan availability probe

```java
GpuBackend[] backendsToTry = preferredGraphicsBackend.getBackendsToTry();
if (preferredGraphicsBackend == PreferredGraphicsApi.DEFAULT) {
   this.backendCreationException = VulkanBackend.checkBackendAvailable();
}
```
This is used only for telemetry and the crash report. On macOS the Vulkan loader is MoltenVK (the version JSON ships
`lwjgl-vulkan` natives **only** for `osx`), so with `DEFAULT` the game loads MoltenVK and creates and destroys a
`VkInstance` before trying any backend. That is wasted startup time once Metal is first. It is optional to skip
(§13.1, mixin M3).

### 3.4 The backend loop (`Minecraft.java:483-514`)

```java
for (GpuBackend backendToTry : backendsToTry) {
   boolean libraryLoaded = false;
   try {
      backendToTry.loadLibrary();
      libraryLoaded = true;
      device = backendToTry.createDevice(new GpuDebugOptions(...));
      backend = backendToTry;
      break;
   } catch (BackendCreationException exception) {               // ONLY this type is caught
      LOGGER.error("Failed to create backend {}", backendToTry.getName(), exception);
      errorMsgBuilder.append("\n\n- Tried ").append(backendToTry.getName()).append(": \n  ").append(exception.getMessage());
      if (this.backendCreationException == null || exception.getReason() != BackendCreationException.Reason.OPENGL_MISSING) {
         this.backendCreationException = exception;
      }
      if (libraryLoaded) backendToTry.unloadLibrary();
   }
}
```
- `backendCreationException` keeps the **last non-`OPENGL_MISSING`** failure (or the Vulkan probe result). It ends up
  in the crash report ("Backend failure message/reason/missing capabilities") and in telemetry. If Metal fails and GL
  succeeds, Metal's exception is the one reported. That is acceptable and useful.
- `RenderSystem.initRenderer` throws if called twice. Do not create a second device.

---

## 4. Options and the Video Settings UI

### 4.1 `net/minecraft/client/Options.java`

| Option | Key in options.txt | Default | On change |
|---|---|---|---|
| `preferredGraphicsBackend()` (`OptionInstance<PreferredGraphicsApi>`, `Options.java:134-156`) | `preferredGraphicsBackend` | `DEFAULT` | `OptionInstance.NO_ACTION`. Takes effect only on restart. The tooltip adds "needs restart" when the value differs from `preferredGraphicsBackendFromStartup`, plus the Vulkan warning for `VULKAN`. |
| `enableVsync()` (`Options.java:515-517`) | `enableVsync` | `true` | `Minecraft.getInstance().invalidateSurfaceConfiguration()` → reconfigure next frame |
| `framerateLimit()` | `maxFps` | `120` (range 10-260, 260 = unlimited) | `FramerateLimitTracker.setFramerateLimit` |
| `inactivityFpsLimit()` | `inactivityFpsLimit` | `AFK` | none |
| `fullscreen()` (`Options.java:584-589`) | `fullscreen` | `false` | `window.setFullscreen(value)` (just sets `fullscreenRequested`; applied in `renderFrame`) |
| `exclusiveFullscreen()` | `exclusiveFullscreen` | `false` | `window.setExclusiveFullscreen(value)` (applies immediately if fullscreen) |
| `macFullscreenMenuVisibility()` | `macFullscreenMenuVisibility` | `false` | `MacosUtil.setFullscreenMenuVisibility` → SDL hint `SDL_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY` |
| `glDebugVerbosity` (int field) | `glDebugVerbosity` | `1` | passed as `GpuDebugOptions.logLevel` |
| `startedCleanly` (bool field) | `startedCleanly` | `true` | crash sentinel |
| `fullscreenVideoModeString` | `fullscreenResolution` | null | `VideoMode.read`, used by exclusive fullscreen |
| `overrideWidth/overrideHeight` | same | 0 | initial window size override |

Parsing: `OptionInstance` values go through `option.codec().parse(JsonOps…)`. **An unknown enum string (for
example `metal`) is logged as "Error parsing option value…" and ignored. The default is kept and nothing crashes.**
A datafix, `OptionsForceDefaultGraphicsApiFix`, rewrites the key to `"default"` at some data version.

**Do not add an enum constant to `PreferredGraphicsApi`.** Fabric has no supported enum extension. Mixin-based
`$VALUES` hacks are fragile, and the value would leak into options.txt and `--graphicsBackend`. Keep Ingot's own
switch in `config/ingot.json` or a system property, and reuse `DEFAULT` as "let the game choose", which then means
Metal on macOS.

### 4.2 `net/minecraft/client/gui/screens/options/VideoSettingsScreen.java`

`displayOptions(Options)` (`VideoSettingsScreen.java:73`) lists, in order: `framerateLimit, enableVsync,
inactivityFpsLimit, guiScale, fullscreen, exclusiveFullscreen, gamma, preferredGraphicsBackend`, plus
`macFullscreenMenuVisibility` when `MacosUtil.IS_MACOS`. A big "exclusive fullscreen mode" slider (monitor video modes)
sits above them. The header shows `options.restartRequired` while `options.isRestartRequiredToApplyVideoSettings()`
is true. `onClose()` → `window.changeFullscreenVideoMode()`. The value button text comes from
`PreferredGraphicsApi.caption()`.

---

## 5. Present mode, vsync and frame pacing

```java
// GpuSurface.PresentMode (RP/api/device/GpuSurface.java)
PRESENT_MODES_VSYNC    = {FIFO};
PRESENT_MODES_NO_VSYNC = {IMMEDIATE, MAILBOX, FIFO};
static PresentMode getSupportedVsyncMode(Collection<PresentMode> supported, boolean vsync) {
   for (mode : vsync ? VSYNC : NO_VSYNC) if (supported.contains(mode)) return mode;
   throw new IllegalStateException("No supported presentation mode was found");
}
```
- Called in `renderFrame` **outside** any try/catch. A surface without `FIFO` crashes the game when vsync is on.
- `GlSurface.SUPPORTED_PRESENT_MODES = {FIFO, IMMEDIATE}`, mapped to `SDL_GL_SetSwapInterval(FIFO ? 1 : 0)`.
- `VulkanGpuSurface` reports whatever the `VkSurface` supports. MoltenVK typically reports FIFO and IMMEDIATE
  **[speculative]**.
- Suggested Metal mapping: report `{FIFO, IMMEDIATE}`. In `configure`, set `layer.displaySyncEnabled =
  (mode == FIFO)`. `CAMetalLayer` has no true mailbox mode. Do not report `MAILBOX`.
- F3's FPS line (`DebugEntryFps`) prints `"%d fps T: %s%s @%sHz"` using `windowSurface().currentConfiguration()
  .presentMode()` → `" (fifo)"` / `" (immediate)"`, and the active video mode's refresh rate.
- Independently of vsync, `renderFrame` ends with a CPU limiter: if `framerateLimit < 260`, call
  `FramerateLimiter.limitDisplayFPS(limit)` (park and spin). `FramerateLimitTracker.getFramerateLimit()` returns 10
  when the window is iconified, or when it is unfocused and in exclusive fullscreen. It returns 10 or 30 when AFK
  (with the `AFK` inactivity mode), 60 in menus without a level, and the user's value otherwise.

---

## 6. The window (SDL3) — `com/mojang/blaze3d/platform/Window.java`

### 6.1 Creation

`Window(eventHandler, displayData, fullscreenVideoModeString, exclusiveFullscreen, title, monitorManager, backend,
maximumSize)`:
1. Preferred fullscreen mode from the options string, or from `--fullscreenWidth/Height` (`VideoMode(w, h, 8, 8, 8,
   60)`).
2. `initialMonitor = monitorManager.getMonitor(SDL_GetPrimaryDisplay())`.
3. `width/height = max(displayData.width, 320) / max(displayData.height, 240)` in **points** (854x480 by default).
4. `handle = createWindow(backend, width, height, title)`:
   ```java
   long flags = 8224L;                                    // RESIZABLE | HIGH_PIXEL_DENSITY
   long windowHandle = backend.createWindow(title, width, height, 8224L);
   if (windowHandle == 0L) throw new IllegalStateException("Failed to create window: " + SDL_GetError());
   SDLVideo.SDL_SetWindowMinimumSize(windowHandle, 320, 240);
   LOGGER.info("Created window using SDL video driver: {}", SDL_GetCurrentVideoDriver());  // "cocoa" on macOS
   ```
5. `setWindowMaxSize(maximumSize, maximumSize)` → `SDL_SetWindowMaximumSize`. This is in points, but the value is a
   texture size in pixels: a harmless unit mismatch.
6. `MacosUtil.disableCloseWindowMenuItem()`: an ObjC-bridge call (`ca.weblite.objc`) that hides the
   `performClose:`/`closeAll:` items in NSApp's windows menu.
7. Window position: centered on the primary monitor (or `SDL_GetWindowPosition` if that monitor is unknown).
8. `setMode()` applies fullscreen or windowed (`SDL_SetWindowFullscreen(false)`, `SDL_SetWindowSize`,
   `SDL_SetWindowPosition`), then `SDL_SyncWindow`, `refreshFramebufferSize()`.
9. `refreshFramebufferSize()` → `framebufferWidth = SDL_GetWindowSizeInPixels.w - padding`, where padding is 1 only
   for the Windows-only borderless window mode, so always 0 on macOS.

### 6.2 Sizes, HiDPI and pixel density

| Accessor | Meaning | Source |
|---|---|---|
| `getWidth()/getHeight()` | **cached framebuffer size in pixels** | `refreshFramebufferSize()` or the `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED` payload |
| `queryFramebufferSize()` | **fresh** `SDL_GetWindowSizeInPixels`, each dimension clamped to ≥1 | used for **surface configure** |
| `getScreenWidth()/getScreenHeight()` | window size in points | `SDL_EVENT_WINDOW_RESIZED` payload |
| `getPixelDensity()` | `SDL_GetWindowPixelDensity` (1.0 if ≤0) | F3, `TextInputManager` |
| `getGuiScaledWidth()` | `ceil(framebufferWidth / guiScale)` | GUI |

- Because Minecraft always passes `SDL_WINDOW_HIGH_PIXEL_DENSITY`, it renders at **native Retina resolution**. A
  Retina 854x480-point window means a 1708x960 main render target and swapchain (Empirical §11).
- The main render target follows `window.getWidth()/getHeight()` (`GameRenderer.render()` →
  `resize(windowRenderState.width, …)` when they differ). The surface follows `queryFramebufferSize()`. They can
  disagree briefly. Both vanilla presenters copy `min(swapchain, texture)` anchored at the **top-left** of the
  window, and Metal should do the same (§7.3).
- [speculative perf option] `MetalBackend.createWindow` could strip `SDL_WINDOW_HIGH_PIXEL_DENSITY` to render at
  1x points (about 4x fewer pixels on Retina). Every size in the game derives from `SDL_GetWindowSizeInPixels`, so
  the game adapts automatically. Text and GUI would look softer.

### 6.3 SDL events the window handles (`Window.handleEvent`, `Window.java:195-246`)

Numeric constants verified against `org.lwjgl.sdl.SDLEvents`:

| Event | Value | Handler |
|---|---|---|
| `SDL_EVENT_QUIT` | 256 | `onQuitRequested()`, which closes unless the quit shortcut is held and `quitShortcuts` is off |
| `SDL_EVENT_TERMINATING` | 257 | `requestClose()` |
| `SDL_EVENT_DISPLAY_ADDED/REMOVED` | 338/339 | `MonitorManager` updates |
| `SDL_EVENT_DISPLAY_CURRENT_MODE_CHANGED` | 342 | monitor refresh, `refreshFramebufferSize()`, `framebufferSizeChanged()` |
| `SDL_EVENT_WINDOW_MOVED` | 517 | position bookkeeping |
| `SDL_EVENT_WINDOW_RESIZED` | 518 | points size, mouse re-grab |
| `SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED` | 519 | `framebufferWidth/Height = data1/data2` (pixels), then `eventHandler.framebufferSizeChanged()` (a throw here becomes the "Window resize" crash) |
| `SDL_EVENT_WINDOW_METAL_VIEW_RESIZED` | 520 | **not handled.** It was also not emitted in the macOS probe |
| `SDL_EVENT_WINDOW_MINIMIZED` | 521 | `onIconified(true)` → `iconified = true`, `invalidateSurfaceConfiguration()` |
| `SDL_EVENT_WINDOW_MAXIMIZED` / `RESTORED` | 522/523 | `onIconified(false)` → invalidate |
| `SDL_EVENT_WINDOW_MOUSE_ENTER/LEAVE` | 524/525 | `cursorEntered()` |
| `SDL_EVENT_WINDOW_FOCUS_GAINED/LOST` | 526/527 | `focused` |
| `SDL_EVENT_WINDOW_CLOSE_REQUESTED` | 528 | `onQuitRequested()` |
| `SDL_EVENT_WINDOW_DISPLAY_CHANGED` | 531 | `framebufferSizeChanged()` only (no size refresh) |
| `SDL_EVENT_WINDOW_ENTER/LEAVE_FULLSCREEN` | 535/536 | `updateFullscreenState()` → `fullscreenStateChanged` (persists the option) |
| `SDL_EVENT_WINDOW_OCCLUDED` | 534 | **not handled** |
| `SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED` | 532 | **not handled**. Moving to a display with a different scale arrives as 519 instead |

Keyboard, mouse and drop events are routed by `SDLEventHandler.pollEvents()` and queued onto the game thread with
`minecraft.execute`. `flushInputEvents()` = `SDL_PumpEvents(); SDL_FlushEvents(768, 4871)`.
`RenderSystem.pollEvents(handler)` is called once per loop iteration at the top of `run()`.
`RenderSystem.pumpEvents(handler)` is called in the waiting loops (world load and save).

### 6.4 `framebufferSizeChanged` / `invalidateSurfaceConfiguration`

`Minecraft.framebufferSizeChanged()` = `invalidateSurfaceConfiguration(); resizeGui();`.
`invalidateSurfaceConfiguration()` = `windowSurfaceNeedsReconfiguring = true`.

Everything that leads to a reconfigure on the next `renderFrame`:
- the initial value `true`, plus the explicit call at `Minecraft.java:732`
- SDL 519 (pixel size changed), 531 (display changed), 342 (display mode changed)
- `Window.updateFullscreenIfChanged()` (fullscreen toggled), `changeFullscreenVideoMode()` (Video Settings closed with
  a new exclusive mode), `setExclusiveFullscreen()` while fullscreen
- `Window.onIconified(true/false)`
- the `enableVsync` option changing
- `windowSurface.isSuboptimal() && !surfaceIsInvalid` (polled every frame)
- a failed `acquireNextTexture` (sets `windowSurfaceNeedsReconfiguring = true`)

### 6.5 Fullscreen paths on macOS

- F11 → `Minecraft.toggleFullscreen()` → `options.fullscreen().set(!v)` (callback `window.setFullscreen`) and
  `options.save()`.
- The next `renderFrame` → `window.updateFullscreenIfChanged()` → `setMode()` → `applyFullscreen()`.
  `useBorderlessFullscreenWindow()` is true **only on Windows**, so macOS goes to `applyFullscreenMode()`:
  - not exclusive: `SDL_SetWindowFullscreenMode(handle, null)` + `SDL_SetWindowFullscreen(handle, true)`. This is
    SDL desktop fullscreen, which uses macOS Spaces by default (`SDL_VIDEO_MAC_FULLSCREEN_SPACES`).
  - exclusive: `SDL_GetClosestFullscreenDisplayMode` + `SDL_SetWindowFullscreenMode(handle, mode)` (a real
    display-mode change), falling back to borderless.
  - then `SDL_SyncWindow`, `updateFullscreenState()`, `refreshFramebufferSize()`, mouse grab update, and
    `eventHandler.framebufferSizeChanged()`.
- Because `updateFullscreenIfChanged()` runs at the top of `renderFrame`, **the surface is reconfigured with the new
  pixel size in the same frame**.

### 6.6 Monitors and video modes

`MonitorManager` (id → `Monitor` record) is built from `SDL_GetDisplays`. `Monitor.tryCreate` collects
`SDL_GetFullscreenDisplayModes` with ≥8 bits per channel, bounds, and the desktop mode. `findBestMonitor(window)` =
`SDL_GetDisplayForWindow`. `Window.getActiveVideoMode()` (asserts the render thread) =
`SDL_GetWindowFullscreenMode` or the display's current mode. None of this involves the backend.

---

## 7. The surface lifecycle inside the frame — `Minecraft.renderFrame` (`Minecraft.java:1229`)

### 7.1 Exact sequence

```java
public void renderFrame(boolean advanceGameTime) {
   if (!this.windowSurface.isAcquired()) {             // re-entrancy guard: nested renderFrame is a no-op
      window.updateFullscreenIfChanged();
      if ((windowSurfaceNeedsReconfiguring || windowSurface.isSuboptimal() && !surfaceIsInvalid) && !window.isIconified()) {
         FramebufferSize fb = window.queryFramebufferSize();                      // SDL_GetWindowSizeInPixels
         PresentMode pm = PresentMode.getSupportedVsyncMode(windowSurface.supportedPresentModes(), options.enableVsync().get());
         Configuration config = new Configuration(fb.width(), fb.height(), pm);
         try { windowSurface.configure(config); surfaceIsInvalid = false; windowSurfaceNeedsReconfiguring = false; }
         catch (SurfaceException e) { LOGGER.warn("Couldn't configure surface to {}", config, e); surfaceIsInvalid = true; }
      }
      if (!surfaceIsInvalid) {
         try { windowSurface.acquireNextTexture(); }
         catch (SurfaceException ex) { LOGGER.warn("Couldn't acquire next surface texture ..."); surfaceIsInvalid = true; windowSurfaceNeedsReconfiguring = true; }
      }
      ... gui.update / level.update / gameRenderer.update / extract ...
      RenderSystem.executePendingTasks();               // polls GpuFence.awaitCompletion(0)
      gameRenderer.render();                            // ALWAYS renders, even with no acquired surface
      if (windowSurface.isAcquired()) {
         windowSurface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), mainRenderTarget().getColorTextureView());
      }
      timerQuery end; tracy upload/capture (reads mainRenderTarget)
      RenderSystem.getDevice().createCommandEncoder().submit();          // SUBMIT
      if (windowSurface.isAcquired()) windowSurface.present();           // PRESENT after submit
      tracy endFrame; RenderSystem.getDynamicUniforms().reset(); levelRenderer.endFrame();
      if (framerateLimit < 260) FramerateLimiter.limitDisplayFPS(framerateLimit);
   }
}
```

Implications:
- `renderFrame` is also called from `setScreenAndShow` (2203), the world-load wait loop (2055), and the disconnect/save
  loop (2154). The `isAcquired()` guard makes nested calls no-ops.
- When the surface is invalid or the window is iconified, the game **still renders and submits** each frame without
  presenting. Submit must work with no drawable.
- `submit()` is called on the single frontend `CommandEncoder` (one per device;
  `FrontendGpuDevice.createCommandEncoder()` always returns the same instance, built once from
  `backend.createCommandEncoder()` in the `FrontendGpuDevice` constructor).
- **Ordering for Metal:** the blit is recorded before `submit()`, and `present()` comes after `submit()`. Choose one:
  (a) in `blitFromTexture`, encode the copy into the frame's command buffer and call
  `[cmdBuf presentDrawable:drawable]` there, so it is committed by `submit()`, and make `present()` bookkeeping only;
  or (b) in `present()`, create a small command buffer, call `presentDrawable:` and commit it (queue order guarantees
  it runs after the frame's work). Metal requires `presentDrawable:` before `commit` of the buffer it is encoded in.
  Both satisfy that. Option (a) avoids an extra command buffer.

### 7.2 Frontend validation (`RP/frontend/FrontendGpuSurface.java`) — the backend can rely on these

- `configure`: throws `IllegalStateException` if acquired. Throws `SurfaceException` if
  `!supportedPresentModes().contains(config.presentMode())`. Then calls `backend.configure(config)` and records
  `currentConfiguration` **only if the backend did not throw**.
- `acquireNextTexture`: `IllegalStateException` if already acquired or never configured. Then calls the backend and
  sets `hasImageAcquired = true` **only if the backend did not throw**.
- `blitFromTexture(encoder, view)`: `encoder` must be a `FrontendCommandEncoder` that is not in a render pass. The
  texture must have a color aspect, `USAGE_COPY_SRC` (bit 2), and exactly 1 layer or depth. The surface must be
  acquired and not already blitted this frame. Then it calls
  `backend.blitFromTexture(frontendCommandEncoder.backend(), view)`, so the backend receives **its own**
  `CommandEncoderBackend` and can cast it to `MetalCommandEncoder`.
- `present`: must be acquired **and** blitted. Then `backend.present()` and `hasImageAcquired = false`.
- `close`: `IllegalStateException` if acquired. Otherwise `backend.close()`.
- `isSuboptimal()` and `supportedPresentModes()` pass straight through. `isSuboptimal()` is polled every frame.

### 7.3 Reference presenter behaviour (to match)

`GlSurface` (`RP/backend/opengl/GlSurface.java`):
- `configure`: make current, set the swap interval (FIFO→1 else 0), store width and height. It never throws.
- `isSuboptimal()` → `false`.
- `acquireNextTexture()`: **`if (isIconified.getAsBoolean()) throw new SurfaceException("Cannot acquire minimized window")`**.
  Otherwise it is a no-op.
- `blitFromTexture` → `GlCommandEncoder.presentTexture`: `glBlitFramebuffer` of `min(swapchain, texture)` to
  `destY = max(0, swapchainH - texH)`. The GL origin is bottom-left, so content is anchored at the top and **not
  flipped**.
- `present()` → `SDL_GL_SwapWindow`. `close()` does nothing.

`VulkanGpuSurface` (`RP/backend/vulkan/VulkanGpuSurface.java`):
- Ignores `isIconified`. Creates the `VkSurfaceKHR` in its constructor via `SDL_Vulkan_CreateSurface(windowHandle,
  …)`. Picks format 37/44 (`R8G8B8A8_UNORM` / `B8G8R8A8_UNORM`) with colorspace 0 (sRGB nonlinear).
- `configure` destroys and recreates the swapchain (waiting for queue idle first). It throws `SurfaceException` if the
  extent is outside `[minImageExtent, maxImageExtent]` and on errors, setting `suboptimal = outOfDate = true` on
  failure. It requests 3 images.
- `acquireNextTexture`: re-throws a deferred present error. `VK_ERROR_OUT_OF_DATE` → suboptimal + out-of-date +
  `SurfaceException`. `VK_SUBOPTIMAL` → suboptimal. A timeout of 5 s → `IllegalStateException`.
- `blitFromTexture`: `vkCmdBlitImage` from the source view's base mip into the swapchain image, copying
  `min(swapchain, texture)` with **dst offsets y from copyHeight to 0, a vertical flip**. The source texture is stored
  bottom-up (GL convention) and Vulkan's swapchain origin is the top-left. It waits on the acquire semaphore and
  signals the present semaphore through the encoder.
- `present()`: `vkQueuePresentKHR`. An out-of-date result is stored and thrown on the next acquire.

**For Metal:**
- The main target is `RGBA8_UNORM`. Whether its rows are top-down or bottom-up in memory depends on the Metal
  backend's own NDC and viewport convention. If it mirrors the Vulkan backend, the present must flip vertically like
  Vulkan does.
- Anchor `min(drawable, texture)` at the top-left.
- `GpuFormat` has **no BGRA format**, and the layer default is `BGRA8Unorm`. Use a small render pass (full-screen
  triangle sampling the RGBA8 view) that swizzles, flips and clips in one step. The main target has
  `USAGE_TEXTURE_BINDING`, so sampling is allowed. With a render pass, `layer.framebufferOnly` can stay `YES`.

### 7.4 Iconified and occluded handling

- `isIconified` is a `BooleanSupplier` bound to `Window::isIconified` (set from SDL 521/522/523). It is passed to
  `createSurface` precisely so the backend can refuse to acquire while minimized.
- With the GL pattern (throw `SurfaceException` when iconified), the flow is:
  1. Minimize → invalidate.
  2. Next frame: configure is skipped (iconified). Acquire still runs once (`surfaceIsInvalid` is still false) → throws
     → one WARN log → `surfaceIsInvalid = true`.
  3. Later frames: no acquire and no warnings, and the frame limiter drops to 10 FPS.
  4. Restore → invalidate → configure → acquire.

  **Copy this for Metal.**
- `[CAMetalLayer nextDrawable]` can block (up to 1 s with `allowsNextDrawableTimeout = YES`, the default) when the
  layer has no free drawable or the window is not visible **[speculative for occlusion; known MoltenVK/game behaviour
  on macOS]**. Recommendations:
  - throw `SurfaceException` when iconified;
  - consider also treating `SDL_GetWindowFlags(handle) & SDL_WINDOW_OCCLUDED (0x4)` as "not acquirable";
  - turn a `nil` drawable into `SurfaceException` (never `IllegalStateException`).
  - In the probe, `nextDrawable` on hidden and shown windows returned in 0-9 ms even though nothing was presented
    (Appendix A). That is not a test of the occluded case.

---

## 8. Shutdown order

`Main` → `minecraft.run()` exits when `running == false`. `runTick` calls `stop()` when `window.shouldClose()`, which
is set by `requestClose()` (SDL 256/257/528). Then `minecraft.exitWorldAndClose()` (disconnect, `screen.removed()`)
and finally `Minecraft.close()` (`Minecraft.java:1112`):

```java
Util.shutdownTimeSource();
try {
   presence offline; remoteFriendListUpdateHandler.close();
   timerQuery.close();                 // GpuQueryPool.close
   telemetryManager.close(); regionalCompliancies.close();
   atlasManager.close(); fontManager.close(); gameRenderer.close(); shaderManager.close();
   levelRenderer.close(); soundManager.destroy(); palettedTextureManager.close(); mapTextureManager.close();
   textureManager.close(); resourceManager.close(); tracyFrameCapture?.close(); narrator.destroy(); FreeTypeUtil.destroy();
   this.windowSurface.close();         // (1) surface  -> backend surface close (Metal: SDL_Metal_DestroyView)
   RenderSystem.shutdownRenderer();    // (2) pipeline caches, shared index buffers, sampler cache, DynamicGpuData,
                                       //     DEVICE.close() -> FrontendGpuDevice.close(): pipelineBuilder.close(); backend.close()
} catch (Throwable t) { LOGGER.error("Shutdown failure!", t); throw t; }
finally {
   this.window.close();                // (3) SDL_DestroyWindow (asserts render thread)
   RenderSystem.unloadTrackedBackendLibrary();   // (4) BACKEND.unloadLibrary()
   SDLInit.SDL_Quit();                 // (5)
   Util.shutdownExecutors();
}
```

Notes:
- Game resources (textures, buffers) are closed **before** the device, possibly while GPU work that references them
  is in flight. The backend must defer real destruction until the GPU has completed (Vulkan uses a
  `DestructionQueue`). `MetalDevice.close()` should drain the queue first (commit an empty buffer and
  `waitUntilCompleted`).
- If anything before `windowSurface.close()` throws, the surface and device are **not** closed, but the window is
  still destroyed in `finally`. `MetalSurface` must tolerate never being closed.
- A window close request starts `ClientShutdownWatchdog` (15 s, report only). After `main` returns, a second watchdog
  with `forceShutdown = true` calls `System.exit(-8)` if the JVM is still alive 15 s later. **Never block
  indefinitely in `close()`**, so use timeouts on GPU waits.
- `unloadLibrary()` runs after the window is gone. For an FFM-loaded `libingot` in `Arena.global()` it can be a no-op,
  because global-arena libraries are never unloaded.
- The crash path (`emergencySaveAndCrash` → `System.exit`) skips all of this.

---

## 9. `NativeLibrariesBootstrap` and macOS specifics

`com/mojang/blaze3d/platform/NativeLibrariesBootstrap.loadLibraries()` (called from `Main` before Fabric
entrypoints):
- Configures the LWJGL extract path `<SharedLibraryExtractPath>/<LWJGL version>/<arch>`, probes directories, and
  captures the LWJGL debug log.
- `Library.initialize()`. Then **`vulkanLoaderAvailable = tryLoadingVulkan()`**: sets
  `Configuration.VULKAN_EXPLICIT_INIT = true` and calls `VK.create()`. On macOS this loads the MoltenVK bundled in
  `lwjgl-vulkan-natives-macos*`, unconditionally.
- Shuffled loads of SDL, **OpenGL** (`GL.getFunctionProvider()`, which loads the OpenGL framework on macOS even if
  Metal is used), OpenAL, STB and FreeType. If Vulkan is available it also loads shaderc, spvc and VMA. Otherwise
  shaderc and spvc load lazily on first use. Both are needed by the frontend shader pipeline regardless of backend,
  and `lwjgl-spvc` is what Ingot uses for SPIR-V→MSL.
- Any failure becomes a crash report ("Loading libraries").

`com/mojang/blaze3d/platform/MacosUtil`: `IS_MACOS = Util.getPlatform() == OSX`,
`disableCloseWindowMenuItem()`, `setFullscreenMenuVisibility(bool)`, `setCtrlClickEmulatesRightClick(bool)`. None
of these are backend-related.

`RenderSystem.initBackendSystem()` sets the hints `SDL_NO_SIGNAL_HANDLERS=1`, `SDL_VIDEO_MINIMIZE_ON_FOCUS_LOSS=0`,
`SDL_QUIT_ON_LAST_WINDOW_CLOSE=0`, `SDL_MOUSE_FOCUS_CLICKTHROUGH=1`, `SDL_ENABLE_SCREEN_KEYBOARD=0` and
`SDL_IME_IMPLEMENTED_UI=composition`, plus app metadata (`identifier = com.mojang.minecraft`). It then calls
`SDL_Init(32)`. SDL log output is routed to SLF4J by `SdlDebug`. **No Metal-related hint is set by vanilla.**

Useful for Ingot on the Java side: LWJGL core ships `org.lwjgl.system.macosx.ObjCRuntime` (`objc_getClass`,
`sel_getUid`, `sel_registerName`, and `getLibrary()` for the `objc_msgSend` address), so small ObjC calls can be made
without the dylib. FFM against `/usr/lib/libobjc.A.dylib` also works (the Appendix A probe does this, including
struct-returning `drawableSize`).

---

## 10. `RenderSystem` — backend-relevant parts (`com/mojang/blaze3d/systems/RenderSystem.java`)

- `initRenderThread()` captures the thread. `assertOnRenderThread()` is used by `Window.close`, `setMode`,
  `MonitorManager`, `MainTarget` allocation and others.
- `getBackendDescription()` → `"LWJGL version " + Version.getVersion()`. This is **not** the GPU backend, and it
  appears in the log and crash report as "Backend library".
- `initBackendSystem()` → see §9. `initRenderer(GpuDevice)` → see §2.2 #14. `shutdownRenderer()` → see §8.
- `trackBackendLibraryForShutdown(GpuBackend)` / `unloadTrackedBackendLibrary()` → see §8.
- `queueFencedTask(Runnable)` → `createCommandEncoder().createFence()`. `executePendingTasks()` polls
  `fence.awaitCompletion(0L)` once per frame, so fences must support non-blocking polling.
- `getDevice()` throws before `initRenderer`. `tryGetDevice()` is nullable (crash report).
- `DEFAULT_DEPTH_CLEAR_VALUE = 0.0`, and `GameRenderer.render()` clears depth to `0.0`. **The game uses reverse-Z.**
  Combined with `DeviceInfo.isZZeroToOne()` (read by `Projection` and `Camera`), the backend must report its clip-space
  depth range correctly. Metal is zero-to-one, so report `true`.
- `isWireframeAvailable()` → `deviceInfo.features().wireframeFillMode()`.

---

## 11. LWJGL 3.4.3 SDL Metal bindings (verified)

### 11.1 Signatures (`javap` on `lwjgl-sdl-3.4.3.jar`)

```text
org.lwjgl.sdl.SDLMetal
  public static long SDL_Metal_CreateView(long window);     // returns SDL_MetalView (an NSView*), 0 on failure
  public static void SDL_Metal_DestroyView(long view);
  public static long SDL_Metal_GetLayer(long view);         // returns void* = CAMetalLayer* (not retained; owned by the view)

org.lwjgl.sdl.SDLVideo (selected)
  SDL_WINDOW_FULLSCREEN=0x1  OPENGL=0x2  OCCLUDED=0x4  HIDDEN=0x8  BORDERLESS=0x10  RESIZABLE=0x20
  MINIMIZED=0x40  MAXIMIZED=0x80  HIGH_PIXEL_DENSITY=0x2000 (8192)  UTILITY=0x20000
  VULKAN=0x10000000 (268435456)  METAL=0x20000000 (536870912)  TRANSPARENT=0x40000000  NOT_FOCUSABLE=0x80000000
  boolean SDL_GetWindowSizeInPixels(long window, IntBuffer w, IntBuffer h)
  float   SDL_GetWindowPixelDensity(long window)
  float   SDL_GetWindowDisplayScale(long window)
  long    SDL_GetWindowFlags(long window)
  int     SDL_GetWindowProperties(long window)
  long    SDL_CreateWindow(CharSequence title, int w, int h, long flags)
  long    SDL_CreateWindowWithProperties(int props)
  boolean SDL_SyncWindow(long window)
  String  SDL_PROP_WINDOW_COCOA_WINDOW_POINTER         = "SDL.window.cocoa.window"         // NSWindow*
  String  SDL_PROP_WINDOW_COCOA_METAL_VIEW_TAG_NUMBER  = "SDL.window.cocoa.metal_view_tag"
  String  SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN          = "SDL.window.create.metal"
  String  SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN = "SDL.window.create.high_pixel_density"

org.lwjgl.sdl.SDLHints (Metal/macOS-related)
  SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE = "SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE"
  SDL_HINT_VIDEO_MAC_FULLSCREEN_SPACES      = "SDL_VIDEO_MAC_FULLSCREEN_SPACES"
  SDL_HINT_VIDEO_MAC_FULLSCREEN_MENU_VISIBILITY, SDL_HINT_MAC_CTRL_CLICK_EMULATE_RIGHT_CLICK, ...
  SDL_HINT_RENDER_METAL_PREFER_LOW_POWER_DEVICE  (SDL_Renderer only; irrelevant)

org.lwjgl.sdl.SDLInit: SDL_INIT_VIDEO = 32
```
The bundled SDL is **3.4.14** (`SDL_GetVersion`, revision `SDL-3.4.14-62f10da`). LWJGL reports `3.4.3+4`.

### 11.2 Empirical behaviour (probe on this Mac, Retina, 2.0 backing scale)

Window created as Minecraft would for Metal: `8224 | SDL_WINDOW_METAL` (plus HIDDEN for the probe), 854x480:
- `SDL_GetCurrentVideoDriver()` = `cocoa`. Flags = `0x20002028` (METAL|HIGH_PIXEL_DENSITY|RESIZABLE|HIDDEN).
- `SDL_GetWindowSizeInPixels` = **1708x960**, `SDL_GetWindowSize` = 854x480, pixel density 2.0, display scale 2.0.
- NSWindow class `SDL3Window`, `backingScaleFactor` 2.0. Its content view is `SDL3View` (`wantsLayer = NO`, no layer).
  `metal_view_tag` property = 255.
- Events after creation: 773, 1028, **519 (1708, 960)**, 532. **Event 519 carries the pixel size.**
- `SDL_Metal_CreateView` → an `SDL3_cocoametalview` added as a subview. `SDL_Metal_GetLayer` → a **`CAMetalLayer`**
  with these initial properties:
  `contentsScale = 2.0`, `drawableSize = 1708 x 960` (already pixel-sized), `pixelFormat = 80 (BGRA8Unorm)`,
  **`device = nil` (must be set)**, `framebufferOnly = YES`, `maximumDrawableCount = 3`,
  `displaySyncEnabled = YES`, `opaque = YES`, `presentsWithTransaction = NO`.
- `SDL_SetWindowSize(1000, 600)` produced events 518 (1000, 600), **519 (2000, 1200)** and 533. By default **SDL
  updated `drawableSize` to 2000x1200 automatically**. No 520 (`METAL_VIEW_RESIZED`) event was seen.
- `SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE = "0"` set **after** view creation had no effect (it kept
  auto-resizing). Set **before** `SDL_Metal_CreateView`, it stopped auto-resizing: `drawableSize` stayed 1708x960
  after the window grew to 2000x1200 pixels. The initial size is set either way.
- Without `HIGH_PIXEL_DENSITY`: pixels = points = 854x480, `contentsScale = 1.0`, drawable 854x480.
- `SDL_Metal_CreateView` **also succeeds on a window created without `SDL_WINDOW_METAL`**, and the window then
  reports the METAL flag.
- `SDL_CreateWindow` with `METAL|OPENGL` fails with `"Conflicting window graphics flags specified: 0x20000002"`.
  `METAL|VULKAN` fails the same way (`0x30000000`).
- A GL-style hidden utility window (`0x80020008 | METAL`) can be created (not needed for Metal).
- With `layer.device = MTLCreateSystemDefaultDevice()` ("Apple M4 Pro", `hasUnifiedMemory = YES`,
  `isLowPower = NO`, `maxBufferLength = 14302248960`), `nextDrawable` returned drawables of the layer's format and
  pixel size (320x240-point window → 640x480 texture).
- **`setPixelFormat: MTLPixelFormatRGBA8Unorm (70)` was accepted and `nextDrawable` returned RGBA8 textures on macOS
  27.2.** Apple's documentation lists only BGRA8Unorm(_sRGB), RGBA16Float, RGB10A2Unorm, BGR10A2Unorm and the XR
  formats for `CAMetalLayer` **[from memory]**, and Ingot's deployment target is macOS 12.0. Treat RGBA8 layers as
  undocumented and use BGRA8Unorm with a shader swizzle.
- Formats 80, 81 (BGRA8 sRGB), 115 (RGBA16Float), 90 (RGB10A2) and 94 (BGR10A2) were also accepted.

### 11.3 Recommended `MetalSurface` wiring (derived from the facts above)

All on the render thread, which is the AppKit main thread on macOS:
```text
createSurface(windowHandle, isIconified):
   (optional) SDL_SetHint(SDL_HINT_VIDEO_METAL_AUTO_RESIZE_DRAWABLE, "0") before CreateView if Ingot owns drawableSize
   view  = SDL_Metal_CreateView(windowHandle)       // 0 -> IllegalStateException with SDL_GetError() (no fallback possible here anyway)
   layer = SDL_Metal_GetLayer(view)
   native: layer.device = mtlDevice; layer.pixelFormat = BGRA8Unorm; layer.framebufferOnly = YES (render-pass present)
           layer.maximumDrawableCount = 3 (or 2 for latency) ; keep opaque = YES
supportedPresentModes(): {FIFO, IMMEDIATE}
configure(cfg): validate 1..maxTextureSize, else SurfaceException; layer.drawableSize = (cfg.width, cfg.height);
                layer.displaySyncEnabled = cfg.presentMode == FIFO;
                if auto-resize was disabled: layer.contentsScale = SDL_GetWindowPixelDensity(window)
isSuboptimal(): false, or true when SDL_GetWindowSizeInPixels != configured size (forces a reconfigure; optional)
acquireNextTexture(): if isIconified -> throw SurfaceException; drawable = [layer nextDrawable] (inside @autoreleasepool,
                      retain it); nil -> throw SurfaceException
blitFromTexture(enc, view): render pass into drawable.texture: sample RGBA8 view, swizzle to BGRA, flip if needed,
                      copy min(sizes) anchored top-left; then [cmdBuf presentDrawable:] (option a in §7.1)
present(): release the drawable reference (or encode+commit presentDrawable here, option b)
close(): (drop the drawable) SDL_Metal_DestroyView(view)
```
Keeping SDL's auto-resize on (the default) is simplest. SDL then maintains `drawableSize` and `contentsScale` on
every pixel-size change, which happens during `SDL_PumpEvents` at the top of the frame. Setting `drawableSize` again
in `configure` to the same pixel size is idempotent and keeps things deterministic.

---

## 12. Diagnostics that surface backend and device info (no mixin needed)

| Where | Code | Fields used |
|---|---|---|
| Startup log | `Minecraft.<init>` 519-521 | `"Using graphics backend {backendName}, using drivers: {driverInfo}"`, `"Using graphics device: {name} ({vendorName})"`, `"Using graphics device extensions: {underlyingExtensions}"` |
| F3 "system" group | `gui/components/debug/DebugEntrySystemSpecs.display` | `"Display: %dx%d (%s)"` = `window.getWidth/Height`, **`vendorName`**; `"Window: %dx%d (%.2fx pixel density)"`; `"%s%s"` = **`name`** + `typeName(type)` (`INTEGRATED` → `" (iGPU)"`, `DISCRETE` → `" (dGPU)"`, `VIRTUAL` → `" (vGPU)"`, `CPU` → `" (software)"`, `OTHER` → `""`); `"%s %s"` = **`backendName`** + first line of **`driverInfo`** |
| F3 FPS line | `DebugEntryFps` | surface present mode, refresh rate |
| F3 GPU % | `DebugEntryGpuUtilization` + `Minecraft.timerQuery` | `createTimestampQueryPool`, `CommandEncoder.writeTimestamp`, `GpuQueryPool.getValues`, `DeviceInfo.timestampPeriod()` (ns per tick) |
| Crash report | `Minecraft.fillSystemReport` (`Minecraft.java:2448-2515`) | "Backend library" (LWJGL version), "Window size", **"Surface Info"** (`acquired`, `suboptimal`, present mode, size, or `unconfigured`), "SDL Platform", "Graphics Device/Backend/Vendor/Drivers/Device Extensions/Device Type/Device Features", "Backend failure message/reason/missing capabilities", **"GL debug messages"** (`device.isDebuggingEnabled() ? getLastDebugMessages() : "<debugging unavailable>"`) |
| Telemetry | `Minecraft.<init>` 734-745 | `BACKEND_NAME`, failure fields |
| GPU warnlist | `GpuWarnlistManager.Preparations.apply` | only acts when `backendName().equals("OpenGL")`, so it is a no-op for Metal |

So `DeviceInfo` should carry, for example, `backendName = "Metal"`, `name = MTLDevice.name` ("Apple M4 Pro"),
`vendorName = "Apple"`, `driverInfo = "Metal <GPU family/version> (Ingot x.y.z)"` (F3 shows only the first line),
`type = INTEGRATED` for unified-memory Apple GPUs (`hasUnifiedMemory`) and `DISCRETE` for Intel-Mac dGPUs
(`!isLowPower && !hasUnifiedMemory`) **[mapping is speculative]**. `underlyingExtensions` (a `Set<String>`) is printed
verbatim, so it is a good place for GPU family and feature-set strings.

---

## 13. Recommended Fabric mixin injection points

The mixin config is `src/main/resources/ingot.mixins.json` (package `dev.ingot.mixin`, `"client": []`,
`defaultRequire: 1`). MixinExtras 0.5.5 is bundled in fabric-loader 0.19.5 (`META-INF/jars/mixinextras-fabric-0.5.5.jar`),
so `@ModifyReturnValue`, `@WrapOperation` and `@Local` are available. Targets use unobfuscated Mojang names.

### 13.1 (a) Getting `MetalBackend` tried first on macOS

**M1 (recommended): `PreferredGraphicsApi#getBackendsToTry` at RETURN**

```java
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
    @ModifyReturnValue(method = "getBackendsToTry", at = @At("RETURN"))
    private GpuBackend[] ingot$prependMetal(GpuBackend[] original) {
        PreferredGraphicsApi self = (PreferredGraphicsApi) (Object) this;
        if (!IngotPlatform.shouldTryMetal(self)) return original;   // IS_MACOS && enabled && (self == DEFAULT || forced by -Dingot.backend=metal)
        GpuBackend[] out = new GpuBackend[original.length + 1];
        out[0] = new MetalBackend();
        System.arraycopy(original, 0, out, 1, original.length);
        return out;                                                  // DEFAULT on macOS -> [Metal, GL, Vulkan]
    }
}
```
Why this point:
- It is called exactly once (`Minecraft.<init>` 476), after the forced-API and `lastStartWasClean` rewrites. `self`
  is therefore the **effective** preference: the forced launch arg if present, otherwise the possibly rewritten
  option.
- With "Metal only when `self == DEFAULT`", vanilla's crash fallback works for free. A crash or kill during startup
  under Metal leaves `startedCleanly = false`, so the next launch rewrites `DEFAULT` to `OPENGL` and Metal is
  skipped. An explicit "Prefer OpenGL" or "Prefer Vulkan" choice by the user is respected.
- Trade-off: after one unclean start the user stays on "Prefer OpenGL" (persisted) until they reset the option to
  Default. If that is unwanted, keep Ingot's own crash sentinel in `config/ingot.json` (written before
  `createDevice`, cleared in a `Minecraft.onGameLoadFinished` RETURN inject) and let `shouldTryMetal` ignore the vanilla
  flip. That is more code and fights vanilla behaviour, so it is not the default recommendation.
- At call time `Minecraft.getInstance().options` is loaded and SDL is initialized, so `shouldTryMetal` may read
  options and call SDL (for example to check `SDL_GetCurrentVideoDriver()`, which is `"cocoa"` on macOS).

**M2 (alternative): wrap the call inside the constructor to see `GameConfig`**

```java
@Mixin(Minecraft.class)
abstract class MinecraftBackendMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/PreferredGraphicsApi;getBackendsToTry()[Lcom/mojang/renderpearl/api/device/GpuBackend;"))
    private GpuBackend[] ingot$backends(PreferredGraphicsApi api, Operation<GpuBackend[]> op,
                                        @Local(argsOnly = true) GameConfig gameConfig) {
        GpuBackend[] vanilla = op.call(api);
        // gameConfig.game.forcedGraphicsApi is visible here (null = not forced)
        return IngotPlatform.shouldTryMetal(api) ? prepend(new MetalBackend(), vanilla) : vanilla;
    }
}
```
Use this only if you need to tell "forced via `--graphicsBackend default`" apart from "option = Default". Otherwise
M1 is simpler and more robust.

**M3 (optional optimization): skip the MoltenVK probe when Metal will be tried**

```java
@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
    target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;checkBackendAvailable()Lcom/mojang/renderpearl/api/device/BackendCreationException;"))
private @Nullable BackendCreationException ingot$skipVkProbe(Operation<BackendCreationException> op) {
    return IngotPlatform.metalWillBeTried() ? null : op.call();
}
```
The result only feeds telemetry and the crash report (§3.3). Skipping it saves MoltenVK instance creation at
startup. It cannot skip `NativeLibrariesBootstrap.tryLoadingVulkan()` (`VK.create()`), which runs in `Main` before
mods. A mixin on `NativeLibrariesBootstrap` could, but not loading Vulkan would also remove the Vulkan fallback, so
leave it.

**Not recommended:**
- Mixing into the `lastStartWasClean` block (`OptionInstance.set` calls inside `<init>`) is fragile: several identical
  invoke targets differ only by ordinal.
- `@ModifyVariable` on the `backendsToTry` local is ordinal and slot fragile.
- Extending the enum (§4.1).

**Override switch:** read `System.getProperty("ingot.backend")` (`metal` | `off`) and/or the `INGOT_BACKEND` env var
in `IngotPlatform`. `metal` forces Metal first regardless of the option or the unclean flag (useful for testing);
`off` disables it. Remember that `--graphicsBackend metal` is impossible (§2.1).

### 13.2 (b) Showing the backend in the UI

- **F3 and crash reports:** automatic through `DeviceInfo` (§12). No mixin.
- **Video Settings value label:** `PreferredGraphicsApi#caption()` at RETURN (`@ModifyReturnValue`). When
  `self == DEFAULT && MacosUtil.IS_MACOS && Ingot enabled`, return
  `Component.translatable("ingot.options.graphicsApi.default_metal")` (for example "Default (Metal)"). Only the enum
  method is touched, so it is low risk. A lang-file override of `options.graphicsApi.default` from Ingot's assets
  also works but applies on every platform.
- **Tooltip:** the tooltip is a lambda inside `Options.<init>` (`Options.java:136-150`). Wrapping it means targeting a
  synthetic lambda method, which is fragile. Prefer the caption, or wrap the
  `OptionInstance` construction with `@ModifyArg` on the `new OptionInstance<>(...)` call for `"options.graphicsApi"`
  if a tooltip is really wanted **[not attempted]**.
- **A dedicated F3 line:** `DebugScreenEntries.register(Identifier, DebugScreenEntry)` is `private static`. Use an
  `@Invoker` accessor on `DebugScreenEntries` to register `ingot:metal` (for example drawable size, frame-in-flight
  count, pipeline cache stats). New entries must still be enabled in the F3 entry configuration
  (`DebugScreenEntryList`). This is optional.

---

## 14. Code paths that assume GL or Vulkan, and what breaks with a third backend

Searched the whole client (`net/**`, `com/mojang/**` outside `renderpearl/backend/**`) for
`renderpearl.backend.opengl|vulkan` imports, `org.lwjgl.opengl`, `org.lwjgl.vulkan`, `SDLVulkan`, `SDL_GL_`,
`instanceof Gl*/Vulkan*`, casts to `Gl*/Vulkan*`, and the string literals `"OpenGL"` and `"Vulkan"`. Results:

| Location | What | Effect with Metal |
|---|---|---|
| `PreferredGraphicsApi.getBackendsToTry()` | hard-codes `new GlBackend()` and `new VulkanBackend()` | the injection point (§13.1) |
| `Minecraft.<init>` 478 | `VulkanBackend.checkBackendAvailable()` when DEFAULT | MoltenVK probe, wasted time; optional skip (M3) |
| `NativeLibrariesBootstrap` | `VK.create()`, `GL.getFunctionProvider()` at startup | loads MoltenVK and OpenGL.framework; harmless |
| `ClientBootstrap.bootstrap()` | `VulkanFeatureSets.bootstrap()` | pure Java; harmless |
| `RP/frontend/FrontendRenderPass` 319/480 | `VkDrawIndexedIndirectCommand.SIZEOF` / `VkDrawIndirectCommand.SIZEOF` (lwjgl-vulkan struct classes) for indirect-buffer size validation | class is on the classpath on macOS; the layout (20 / 16 bytes) matches Metal's `MTLDrawIndexedPrimitivesIndirectArguments` / `MTLDrawPrimitivesIndirectArguments` field order, so indirect buffers can be consumed directly |
| `RP/frontend/FrontendRenderPass` 363/373/519/536/554 | **`((BaseGpuBuffer) buffer).checkCanBeUsed()`** on index, vertex and uniform buffers | **`ClassCastException` unless Metal buffers extend `com.mojang.renderpearl.backend.common.BaseGpuBuffer`** |
| `RP/frontend/FrontendGpuSurface.blitFromTexture` | requires `FrontendCommandEncoder` and passes `.backend()` | the backend surface receives its own encoder type (fine) |
| `GpuWarnlistManager` | acts only when `backendName == "OpenGL"` | no-op |
| `Minecraft` "Recovering from unsupported resolution" and the crash report | label debug output "GL debug messages", via `device.getLastDebugMessages()` / `isDebuggingEnabled()` | cosmetic. Must not throw (the startup call is inside try/catch, the crash-report call is lazy) |
| `Options.glDebugVerbosity`, `--vulkanValidation`, `--renderDebugLabels` | GL/Vk-named knobs packed into `GpuDebugOptions` | interpret as generic debug knobs |
| `Window.createWindow` | always passes `8224L` and lets the backend add its API flag | Metal adds `SDL_WINDOW_METAL` |
| `TimerQuery` (constructor in `Minecraft.<init>`) | unconditional `createTimestampQueryPool(6)` | **must be implemented** (Metal: `MTLCounterSampleBuffer` with the timestamp counter set, or a fallback that returns empty `OptionalLong`s so the F3 GPU line shows 0%) |

No other `net.minecraft` code touches GL or Vulkan. **Third-party mods that call `org.lwjgl.opengl.*` directly**
(rendering mods, overlays, some UI libraries) will fail under Metal, because no GL context exists:
`GL.getCapabilities()` throws "No GLCapabilities instance set for the current thread". Ingot should document this,
and could check for known-incompatible mod IDs at startup and fall back.

---

## 15. Pitfalls checklist for the Metal backend

1. **Exceptions:** only `BackendCreationException` from `loadLibrary`/`createDevice` falls back. Wrap
   `UnsatisfiedLinkError`, `IllegalStateException`, `IllegalCallerException` (FFM) and others. Window creation and
   `createSurface` failures are fatal.
2. **Device before window:** `MetalDevice` must work with no `CAMetalLayer`. The layer is set up in `createSurface`.
   Samplers, two mapped UBO storages, shaders and pipelines (`loadCriticalShaders`) and the timestamp query pool are
   all created before the first `configure`.
3. **Thread:** all SDL window, view and layer calls happen on the render thread, which is the AppKit main thread on
   macOS because of `-XstartOnFirstThread`. Ensure dev runs keep that flag.
4. **`FIFO` must always be in `supportedPresentModes()`**, or vsync-on crashes in `renderFrame`.
5. **Present ordering:** blit, then `submit()`, then `present()`. Put `presentDrawable:` before the commit of the
   buffer that carries it (§7.1).
6. **Iconified:** throw `SurfaceException` from `acquireNextTexture` when `isIconified.getAsBoolean()` (GL pattern).
   Turn `nil` drawables into `SurfaceException`. Never block for long in `nextDrawable` (keep
   `allowsNextDrawableTimeout`).
7. **Rendering without a drawable:** the game renders and submits every frame even when not acquired.
8. **Size mismatch:** surface size = fresh `SDL_GetWindowSizeInPixels`; main target = cached `window.getWidth()`.
   Copy `min()` anchored top-left. Validate config sizes against device limits and throw `SurfaceException`, which
   vanilla handles.
9. **Formats:** the main target is RGBA8_UNORM and the layer default is BGRA8Unorm. RGBA8 layers worked on macOS 27.2
   but are undocumented. Swizzle in a present shader. Mind the vertical flip convention (Vulkan flips, GL doesn't).
10. **`layer.device` is nil** after `SDL_Metal_CreateView`. Set it before the first `nextDrawable`.
11. **drawableSize ownership:** SDL auto-resizes by default (the hint is only honoured if set before
    `SDL_Metal_CreateView`). Either keep SDL in charge and set the same size in `configure`, or disable it and also
    manage `contentsScale`.
12. **Re-entrancy:** `renderFrame` may be called recursively. The frontend's `isAcquired` guard handles it. Do not
    acquire outside the frontend's flow.
13. **Shutdown:** the surface closes before the device, and the device before `SDL_DestroyWindow`. Destroy the view in
    `MetalSurface.close()`. Drain GPU work with a timeout in `MetalDevice.close()`. Tolerate resources closed while in
    flight (deferred destruction). The surface may never be closed if shutdown throws early.
14. **Unclean-start semantics:** under DEFAULT, any startup crash under Metal makes the next launch use OpenGL **and
    persists "Prefer OpenGL"** (§3.2). Communicate this, or add Ingot's own sentinel.
15. **No enum extension.** Use `DEFAULT` plus Ingot config or a system property.
16. **`BaseGpuBuffer`:** buffers must extend it (frontend cast).
17. **Reverse-Z:** depth clears to 0.0. Report `isZZeroToOne = true` for Metal.
18. **Telemetry and crash-report naming:** `backendName` appears in logs, F3, crash reports and Mojang telemetry. Use
    a stable string ("Metal").
19. **`unloadLibrary()`** runs after the window is destroyed and `SDL_Quit` follows it. Do not call SDL there.
    Treat it as a no-op for a global-arena FFM library.
20. **Window max size** = `maxTextureSizeForFormat(RGBA8)`. Report realistic `maxTextureSize` (16384 on Apple GPUs)
    and `maxMemoryAllocationSize` (`MTLDevice.maxBufferLength`, 14302248960 on this M4 Pro). Otherwise the window can
    be capped or `MainTarget` falls back to 854x480.

---

## 16. Open questions / not verified here

- Whether `nextDrawable` stalls for occluded or minimized windows on current macOS. Instrument and decide whether to
  also gate on `SDL_WINDOW_OCCLUDED`.
- Whether Loom's macOS client run config includes `-XstartOnFirstThread` automatically for 26.3 (check
  `.idea`/launch configs or `ps` of the dev client).
- Color management: GL's default framebuffer and Vulkan's swapchain (colorspace 0 = SRGB_NONLINEAR, UNORM format)
  both output unconverted RGBA8 values. Leaving `CAMetalLayer.colorspace` nil, the default, should match. If colors
  look different from GL, try setting the colorspace to sRGB **[speculative]**.
- ProMotion (120 Hz) pacing with `displaySyncEnabled` and the CPU `FramerateLimiter`. Measure.

---

## Appendix A — probe code used for §11.2 (Java 25 FFM + LWJGL 3.4.3)

Run with `-XstartOnFirstThread --enable-native-access=ALL-UNNAMED` and a classpath of `lwjgl-3.4.3.jar`,
`lwjgl-3.4.3-natives-macos-arm64.jar`, `lwjgl-sdl-3.4.3.jar` and `lwjgl-sdl-3.4.3-natives-macos-arm64.jar` (all in
`~/.gradle/caches/modules-2/files-2.1/org.lwjgl/…`).

```java
// objc helpers via FFM
Linker L = Linker.nativeLinker();
SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
MemorySegment MSG = OBJC.find("objc_msgSend").orElseThrow();
MethodHandle SEL = L.downcallHandle(OBJC.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));
StructLayout CGSIZE = MemoryLayout.structLayout(JAVA_DOUBLE.withName("w"), JAVA_DOUBLE.withName("h"));
MethodHandle MSG_SIZE = L.downcallHandle(MSG, FunctionDescriptor.of(CGSIZE, ADDRESS, ADDRESS));   // struct return works on arm64
MethodHandle MSG_D = L.downcallHandle(MSG, FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS));
MethodHandle MSG_P = L.downcallHandle(MSG, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO);
long w = SDLVideo.SDL_CreateWindow("probe", 854, 480, 8224L | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_METAL);
long view  = SDLMetal.SDL_Metal_CreateView(w);
long layer = SDLMetal.SDL_Metal_GetLayer(view);
// query: contentsScale, drawableSize, pixelFormat, device, framebufferOnly, maximumDrawableCount, displaySyncEnabled
// resize: SDL_SetWindowSize(w, 1000, 600); SDL_SyncWindow(w); pump events; re-query drawableSize
// device: MTLCreateSystemDefaultDevice from /System/Library/Frameworks/Metal.framework/Metal; [layer setDevice:]; [layer nextDrawable]
SDLMetal.SDL_Metal_DestroyView(view);
SDLVideo.SDL_DestroyWindow(w);
SDLInit.SDL_Quit();
```
