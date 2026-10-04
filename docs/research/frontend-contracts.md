# renderpearl FRONTEND -> BACKEND contract (Minecraft Java 26.3)

Reference for implementing the Ingot Metal backend. Everything below was read from the Vineflower
decompilation of the 26.3 client. Statements about what Metal should do are marked
**Recommendation** or **Speculation**; everything else is sourced, with a file/line citation.

Path abbreviations used throughout:

- `RP/` = `/private/tmp/claude-501/-Users-eric/4989013a-676e-4667-9cd6-defdeb05b1fa/scratchpad/mc/src-rp/com/mojang/renderpearl/`
- `B3D/` = `/private/tmp/claude-501/-Users-eric/4989013a-676e-4667-9cd6-defdeb05b1fa/scratchpad/mc/src-rp/com/mojang/blaze3d/`
- `MC/` = `/private/tmp/claude-501/-Users-eric/4989013a-676e-4667-9cd6-defdeb05b1fa/scratchpad/mc/src-full/net/minecraft/`

Line numbers refer to the decompiled files and may drift by a line or two from the original sources.

---

## 0. Executive summary (read this first)

1. **One device, one encoder, one render thread.** `FrontendGpuDevice` calls `GpuDeviceBackend.createCommandEncoder()` exactly once and hands out the same `FrontendCommandEncoder` forever. Every backend call except pipeline compilation happens on the render thread (the GL backend could not work otherwise: all its calls touch the GL context).
2. **`submit()` is called exactly once per frame**, from `Minecraft.renderFrame` only (`MC/client/Minecraft.java:1321`). Both reference backends keep **2 submits in flight** and block in `submit()` on submit N-2. Fences, transient memory, deferred destruction and readback callbacks are all keyed to this submit index.
3. **Validation is thin in production.** `FrontendGpuDevice.STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE`, which is `false` in shipped builds and is not set anywhere in the client jar. Most draw-time checks (missing uniforms, closed buffers, usage flags, index buffer presence) only run in strict mode. The backend must be robust against bad input or at least fail loudly.
4. **Command-stream ordering.** `writeToBuffer`, `writeToTexture`, `copy*`, `clear*` are ordered GPU-timeline operations (staged + recorded), not immediate CPU writes. `GpuBuffer.map()` is the only immediate, unsynchronized CPU path. Implementing `writeToBuffer` as a `memcpy` into a shared `MTLBuffer` or `writeToTexture` as `replaceRegion` is wrong (corrupts earlier commands in the same or in-flight frames).
5. **Resources are closed while still referenced by in-flight or not-yet-submitted commands, routinely.** All destruction must be deferred until the submits that may reference the object have completed.
6. **Coordinate convention: "row 0 = first row in memory = bottom of the image" (GL convention).** Render area, scissor, clear regions and copy offsets are passed straight through by the Vulkan backend (which addresses rows like Metal does), clip-space y = -1 lands in row 0, and the Vulkan surface blit flips vertically when presenting. A Metal backend must flip clip-space Y in vertex shaders (or use a flipped viewport) and flip again when blitting to the drawable. Screenshots assume rows are bottom-up (`MC/client/Screenshot.java:96`).
7. **Fences:** `createFence()` returns a fence for *the submit currently being recorded*. `awaitCompletion(0)` on such a fence must return `false`; `awaitCompletion(non-zero)` on it throws `IllegalStateException` in both reference backends. `-1` means wait forever. Fences are created several times per frame and must be cheap.
8. **Readback callbacks** (`copyTextureToBuffer(..., Runnable callback, ...)`) must run **once, on the render thread**, after the copy is complete and visible to `map(true, false)`. GL defers them to `RenderSystem.executePendingTasks()` (next frames, before rendering); Vulkan runs them inside the `submit()` call that retires the submit containing the copy. Never run them on a Metal completion-handler thread.
9. **Pipelines:** `GpuDeviceBackend.compilePipeline(CreateInfo)` runs on background worker threads, concurrently with rendering and with other compiles. `Pending.finishCompile()` runs on the render thread (resource reload) or on whatever thread called `RenderSystem.getCompiledPipeline` (also render thread). The SPIR-V `SpvModule`s are freed right after `finishCompile()` returns.
10. **Present ordering:** `acquireNextTexture()` (frame start) -> rendering -> `blitFromTexture()` -> `submit()` -> `present()`. In Metal, `presentDrawable:` must be scheduled on a command buffer before it is committed, but Minecraft calls `present()` after `submit()`. See section 13.

---

## 1. Object graph and who wraps what

`GpuBackend` (API, `RP/api/device/GpuBackend.java`) is the entry point that Minecraft instantiates (`MC/client/PreferredGraphicsApi.java`, `getBackendsToTry()` returns `{GlBackend, VulkanBackend}` or reversed). Its `createDevice` must return a `GpuDevice`; both reference backends return `new FrontendGpuDevice(new XxxDevice(...))` (`RP/backend/opengl/GlBackend.java:69-71`).

Frontend wrappers (validation layer) and the backend interface they delegate to:

| API type (what Minecraft sees) | Frontend class (`RP/frontend/`) | Backend interface (`RP/backend/api/`) |
|---|---|---|
| `GpuDevice` | `FrontendGpuDevice` | `GpuDeviceBackend` |
| `CommandEncoder` | `FrontendCommandEncoder` | `CommandEncoderBackend` |
| `RenderPass` (+ `RenderPass.UniformUploader`) | `FrontendRenderPass` | `RenderPassBackend` |
| `GpuSurface` | `FrontendGpuSurface` | `GpuSurfaceBackend` |
| `CompiledRenderPipeline` | `FrontendRenderPipeline` (record) | `BackendRenderPipeline` |

Objects the backend creates and returns **directly to game code without a frontend wrapper** (so the backend is the only line of defence for their methods):

| API type | Created by | Notes |
|---|---|---|
| `GpuBuffer` | `GpuDeviceBackend.createBuffer`, `TransientMemory.*` | `map()` is implemented by the backend, unvalidated by the frontend. Must extend `RP/backend/common/BaseGpuBuffer` (strict validation casts to it). |
| `GpuTexture` | `GpuDeviceBackend.createTexture` | Conventionally extends `BaseGpuTexture`. |
| `GpuTextureView` | `GpuDeviceBackend.createTextureView` | Conventionally extends `BaseGpuTextureView`. |
| `GpuSampler` | `GpuDeviceBackend.createSampler` | |
| `GpuFence` | `CommandEncoderBackend.createFence` | |
| `GpuQueryPool` | `GpuDeviceBackend.createTimestampQueryPool` | |
| `TransientMemory` | `CommandEncoderBackend.transientMemory()` | Returned verbatim by `FrontendCommandEncoder.transientMemory()` (`FrontendCommandEncoder.java:53-56`). |
| `SpvModule` | frontend (`RP/frontend/shaders/SPIRVModule`) | Backend consumes it. |

`RP/backend/common/`:

- `BaseGpuBuffer(usage, size)`: stores `size()`, `usage()`; `checkCanBeUsed()` is a no-op hook (GL overrides it to throw if a non-persistently-mapped buffer is used while mapped).
- `BaseGpuTexture(usage, label, format, width, height, depthOrLayers, mipLevels)`: `getWidth(mip) = width >> mip`, `getHeight(mip) = height >> mip` (note: can reach 0, no `max(1, ...)`), plus getters.
- `BaseGpuTextureView(texture, baseMipLevel, mipLevels)`: `getWidth(mip) = texture.getWidth(mip + baseMipLevel)`.

`FrontendRenderPass.validateDraw` and `drawMultipleIndexed` cast `GpuBuffer` to `BaseGpuBuffer` (`FrontendRenderPass.java:363, 373, 519, 536, 554`) when `STRICT_VALIDATION` is on. **Every `GpuBuffer` the backend hands out, including transient-memory buffers, must extend `BaseGpuBuffer`.**

---

## 2. Bootstrapping, lifetime and shutdown order

### 2.1 Startup (`MC/client/Minecraft.java:454-540, 731-733`)

1. `RenderSystem.initBackendSystem()` (SDL_Init video, hints). `SDL_Init(32)` = `SDL_INIT_VIDEO`.
2. For each `GpuBackend` from `PreferredGraphicsApi.getBackendsToTry()`:
   - `backend.loadLibrary()` (throws `BackendCreationException`)
   - `backend.createDevice(new GpuDebugOptions(glDebugVerbosity, DEBUG_SYNCHRONOUS_GL_LOGS, renderDebugLabels, vulkanValidation))`
   - On `BackendCreationException`: log, `unloadLibrary()` if loaded, try the next backend. All failures -> `MessageBox.error` + `Window.WindowInitFailed`.
3. `RenderSystem.initRenderer(device)` (stores `DEVICE`, creates `DynamicGpuData`, initializes `SamplerCache` which creates samplers), `RenderSystem.trackBackendLibraryForShutdown(backend)`.
4. Logs `deviceInfo.backendName()`, `driverInfo()`, `name()`, `vendorName()`, `underlyingExtensions()`; uses `limits().maxTextureSizeForFormat(RGBA8_UNORM)`.
5. **Window is created after the device**: `new Window(..., backend, maxSize)` -> `backend.createWindow(title, width, height, 8224L)` (`B3D/platform/Window.java:164-173`). `8224 = SDL_WINDOW_RESIZABLE (0x20) | SDL_WINDOW_HIGH_PIXEL_DENSITY (0x2000)`. GL ORs `SDL_WINDOW_OPENGL (2)`, Vulkan ORs `SDL_WINDOW_VULKAN (0x10000000)`. Metal must OR `SDL_WINDOW_METAL = 0x20000000` (`org.lwjgl.sdl.SDLVideo.SDL_WINDOW_METAL = 536870912`). Return 0 on failure (Window throws with `SDL_GetError()`).
6. `device.createSurface(window.handle(), window::isIconified)`.
7. Many resources are created and uploads recorded during the constructor and the first resource reload, then `this.framebufferSizeChanged()` (sets `windowSurfaceNeedsReconfiguring`) and the first `this.renderFrame(false)` (line 733), which is the first `submit()`.

Consequences for the backend:

- `createDevice` runs with **no window and no surface**. (The GL backend creates its own hidden window for the context.) Device creation must not depend on a `CAMetalLayer`.
- `FrontendGpuDevice`'s constructor (`RP/frontend/FrontendGpuDevice.java:42-52`) calls, in this order:
  1. if `TracyClient.isAvailable()`: `new TracyGpuProfiler(this)` -> `backend.createTimestampQueryPool(1024)`, `backend.getDeviceInfo()`, `backend.getTimestampCalibrationOffset()`;
  2. `backend.createCommandEncoder()` (once, ever);
  3. `new PipelineBuilder(backend)` -> `backend.getDeviceInfo()` (reads `isZZeroToOne`, `features().shaderDrawParameters()`).

  So `getDeviceInfo()`, `createTimestampQueryPool` and `getTimestampCalibrationOffset` must work **before** `createCommandEncoder()` is called. (Vulkan creates its encoder inside the `VulkanDevice` constructor, `VulkanDevice.java:140`.)

### 2.2 Shutdown (`MC/client/Minecraft.java:1111-1147`)

`close()`: closes timerQuery, atlas/font managers, gameRenderer, shaderManager, levelRenderer, texture managers, resource manager, tracyFrameCapture, ..., then `windowSurface.close()`, then `RenderSystem.shutdownRenderer()` (pipeline caches, shared index buffers, sampler cache, `DynamicGpuData`, then `DEVICE.close()`), then in `finally`: `window.close()`, `RenderSystem.unloadTrackedBackendLibrary()` (`GpuBackend.unloadLibrary()`), `SDL_Quit()`.

- **No final `submit()` happens** after the last frame. Device close must flush/submit anything recorded, wait for the GPU to go idle, run or drop pending destructions and pending callbacks. Vulkan's `VulkanCommandEncoder.destroy()` closes (= `vkQueueSubmit2`s) the open submission, `waitIdle()`s, then drains its destruction queue (which also runs queued readback callbacks).
- Not every resource is guaranteed to be closed before `DEVICE.close()`. Device close must release everything it still owns.
- `FrontendGpuDevice.close()` = `pipelineBuilder.close()` (shaderc compilers) then `backend.close()`. The Tracy profiler's query pool is never closed.
- `FrontendGpuSurface.close()` throws `IllegalStateException` if the surface is acquired.

### 2.3 `GpuBackend` contract (`RP/api/device/GpuBackend.java`)

| Method | Called | Contract |
|---|---|---|
| `String getName()` | error messages | Human name ("OpenGL", "Vulkan"). |
| `void loadLibrary() throws BackendCreationException` | before `createDevice`; also from `VulkanBackend.checkBackendAvailable()` probe | Idempotent; GL/Vulkan cache the failure exception and rethrow it on later calls. |
| `void unloadLibrary()` | on creation failure, and at shutdown via `RenderSystem.unloadTrackedBackendLibrary()` | Idempotent. |
| `long createWindow(@Nullable String title, int w, int h, long flags)` | once, after `createDevice` | Must return an SDL window handle created with `flags` plus backend-specific flags. |
| `GpuDevice createDevice(GpuDebugOptions)` | once | Return `new FrontendGpuDevice(backendDevice)`. Throw `BackendCreationException` (with a `Reason`; there is no Metal-specific reason, use `PLATFORM_ERROR` or `OTHER`) to fall back to the next backend. |

**Recommendation:** inject the Metal backend by mixin into `PreferredGraphicsApi.getBackendsToTry()` (prepend) so GL/Vulkan remain fallbacks.

---

## 3. Coordinate and depth conventions (critical for Metal)

There is no explicit statement in the API; the convention is defined by what the two backends and the callers do.

Evidence:

- GUI scissor is computed bottom-up: `renderPass.enableScissor((int)left, window.height - (int)bottom, ...)` (`MC/client/gui/render/GuiRenderer.java:415`). GUI item atlas region clear and scissor use `textureSize - bottom` (`MC/client/gui/render/GuiItemAtlas.java:101-112`).
- GL passes these straight to `glScissor`/`glViewport(0,0,w,h)` (bottom-left origin).
- Vulkan also passes them straight through: `vkCmdSetScissor(offset = (x, y))`, viewport `(0, 0, w, h)` with **positive height** (no flip) (`RP/backend/vulkan/VulkanRenderPass.java:80-88, 174-179`). Vulkan framebuffer y is top-down in memory-row terms, exactly like GL row indexing (GL row 0 is the bottom of the window but also the first row in memory).
- The Vulkan surface blit flips vertically: `srcOffsets (0,0)-(w,h)`, `dstOffsets (0,h)-(w,0)` (`RP/backend/vulkan/VulkanGpuSurface.java:410-419`).
- Screenshots read rows bottom-up: `image.setPixelABGR(x, height - y - 1, ...)` (`MC/client/Screenshot.java:96`).
- Full-screen passes compute `uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2)` and place vertices at `uv * 2 - 1` (`assets/minecraft/shaders/core/screenquad.vsh`), so `uv.y = 0` (texture row 0) corresponds to clip y = -1. This only round-trips if clip y = -1 is rendered into row 0.

Therefore the convention is:

- **All integer 2D coordinates in the API** (render area, scissor rect, clear regions, `writeToTexture` destX/destY, `copyBufferToTexture` source/dest offsets, `copyTextureToBuffer` x/y, `copyTextureToTexture` offsets) are **texel memory-row coordinates: y = 0 is the first row in memory.** Metal textures are addressed the same way (origin top-left = first row), so pass them straight through, like Vulkan.
- **Clip space y = -1 must land in memory row 0.** GL and Vulkan both do this natively. Metal does the opposite (NDC y = +1 maps to the top row). The Metal backend must negate `gl_Position.y` in every vertex shader (SPIRV-Cross option `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y = 16777220`, available in LWJGL `Spvc`; note the GL backend does **not** use it, `RP/backend/opengl/GlPipelineRecompiler.java:65-71`) or use a negative-height viewport (**Speculation:** Metal accepts negative viewport heights, but this is less documented than the shader flip).
- **Presentation flips vertically**, anchored top-left: GL copies to `destY = swapchainHeight - texHeight`; Vulkan blits `(0, copyHeight) -> (copyWidth, 0)`. Both copy `min(surface, texture)` width/height. In Metal the drawable's row 0 is the top of the screen, so the blit must flip (a blit encoder cannot flip; use a small render pass, which also handles RGBA8 -> BGRA8 conversion).
- **Winding.** Vulkan uses `cullMode = BACK (2)` when `cull`, `frontFace = VK_FRONT_FACE_CLOCKWISE (1)` with the un-flipped viewport (`RP/backend/vulkan/VulkanRenderPipeline.java:266-271`). That is equivalent to GL's default CCW-front in GL window space. **Derived (verify on device):** with clip-Y negated in Metal, the framebuffer mapping equals Vulkan's, so use `MTLWindingClockwise` + `MTLCullModeBack`. Without the flip it would be `MTLWindingCounterClockwise`.
- **Depth.** `DeviceInfo.isZZeroToOne()` selects 0..1 clip depth: the frontend adds `#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` to every shader (`RP/frontend/shaders/GlslCompiler.java:81-83`) and `Projection`/`Camera` build matrices with `zZeroToOne` (`MC/client/renderer/Projection.java:69`). Vulkan reports `true`; GL reports `GL_ARB_clip_control`. Metal should report `true`. Minecraft uses reverse-Z: `RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE = 0.0`, `DepthStencilState.DEFAULT = (GREATER_THAN_OR_EQUAL, write=true)`, `Projection` swaps near/far. All depth targets in vanilla are `D32_FLOAT`.

---

## 4. What the frontend validates (and what it does not)

`FrontendGpuDevice.STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE` (`FrontendGpuDevice.java:36`). `IS_RUNNING_IN_IDE` has no initializer in `SharedConstants` (`MC/SharedConstants.java:128`) and nothing in the client assigns it, so it is `false` in production and in a normal Fabric dev run unless something sets it before `FrontendGpuDevice` is class-initialized. **Recommendation:** in Ingot dev builds, set `SharedConstants.IS_RUNNING_IN_IDE = true` before device creation to get the strict draw validation (side effect: other IDE-only behaviours turn on, e.g. Tracy zones).

Always-on checks are listed per method in sections 5-8. Draw-time checks that are **strict-only** (`FrontendRenderPass.validateDraw`, lines 504-628): missing push constants, missing/closed/unusable index buffer, missing vertex buffer for a slot that has a vertex format, missing or wrong-type uniform values, closed/wrong-usage UBO/texel buffer/texture view/sampler, texel buffer must be the whole buffer, pipeline wants depth but pass has no depth attachment.

Never validated by the frontend:

- `GpuBuffer.map(...)` (backend object, called directly).
- Everything in `TransientMemory`.
- `RenderPass.setIndexBuffer` (no closed/usage check outside strict draw validation).
- `CommandEncoder.copyTextureToBuffer` 5-arg overload (only the "not in a render pass" check).
- `GpuDeviceBackend.createTimestampQueryPool`, `createSampler` address/filter values, `createTexture` format/usage combinations (only mip/size/layer counts).
- Value types passed to `RenderPassBackend.setUniform` in production.
- Calls on a closed `RenderPass` for: `setPipeline`, `setUniform*`, `pushConstants`, `enableScissor`, `disableScissor`, `setVertexBuffer`, `setIndexBuffer`, `writeTimestamp` (only `push/popDebugGroup` and the `draw*` family check `isClosed`).

---

## 5. `GpuDeviceBackend` (`RP/backend/api/GpuDeviceBackend.java`)

All methods are called on the render thread unless stated.

### `GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified)`
- Frontend: wraps result in `new FrontendGpuSurface(...)`, no validation.
- Called once from `Minecraft` constructor. `isIconified` is `Window::isIconified` (a field read updated by SDL events). GL uses it in `acquireNextTexture` (throws `SurfaceException` when minimized); Vulkan ignores it.
- **Recommendation (Metal):** `SDL_Metal_CreateView(window)` + `SDL_Metal_GetLayer(view)` (LWJGL `org.lwjgl.sdl.SDLMetal`), configure the `CAMetalLayer` (device, pixel format, `framebufferOnly`), destroy the view in `close()`.

### `CommandEncoderBackend createCommandEncoder()`
- Called exactly once, from `FrontendGpuDevice`'s constructor. Must return the backend's single encoder.

### `GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod)`
- Frontend check: `1 <= maxAnisotropy <= getDeviceInfo().limits().maxAnisotropy()` else `IllegalArgumentException` (`FrontendGpuDevice.java:65-79`).
- `AddressMode` = `REPEAT | CLAMP_TO_EDGE`; `FilterMode` = `NEAREST | LINEAR`. There is no explicit mip filter.
- Reference semantics: Vulkan sets `mipmapMode = LINEAR if maxLod.orElse(1000) > 0.25 else NEAREST`, `maxLod = max(0.25, maxLod.orElse(1000))`, `anisotropyEnable = maxAnisotropy > 1` (`RP/backend/vulkan/VulkanGpuSampler.java`). GL uses `GL_NEAREST_MIPMAP_LINEAR`/`GL_LINEAR_MIPMAP_LINEAR` for min filter and `GL_TEXTURE_MAX_LOD` if present (`RP/backend/opengl/GlSampler.java:34-57`).
- Samplers are created at init by `SamplerCache.initialize()` (`B3D/systems/SamplerCache.java:13-24`: every address/filter combination, `maxAnisotropy = 1`, `maxLod = empty` with mipmaps or `OptionalDouble.of(0.0)` without) and cached by Minecraft; few are created afterwards. `maxLod = 0.0` means "sample mip 0 only".

### `GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels)`
- The API has two overloads; the `Supplier<String>` one resolves the label only if `isDebuggingEnabled()` (`FrontendGpuDevice.java:82-92`). The backend label may be `null`; Vulkan substitutes `""`. `getLabel()` is used in frontend error messages, so return non-null.
- Frontend checks (`verifyTextureCreationArgs`, lines 108-149): `mipLevels >= 1`; `mipLevels <= log2(max(w,h)) + 1`; `depthOrLayers >= 1`; if `usage & USAGE_CUBEMAP_COMPATIBLE (16)`: `w == h`, `depthOrLayers % 6 == 0`, `depthOrLayers <= 6` (array textures unsupported); otherwise `depthOrLayers == 1` (3D/array unsupported). No size limit check, no format/usage compatibility check.
- Usage bits (`GpuTexture`): `COPY_DST=1`, `COPY_SRC=2`, `TEXTURE_BINDING=4`, `RENDER_ATTACHMENT=8`, `CUBEMAP_COMPATIBLE=16`. Typical values: render targets `15`, cube map `21`, sprite animation frames `5`, depth for item atlas/PiP `9`, Tracy capture `10`.
- **Must throw `GpuOutOfMemoryException` on allocation failure**: `MainTarget.allocateColorAttachment/DepthAttachment` catch it and retry with smaller fallback dimensions (`B3D/pipeline/MainTarget.java:74-87`). GL throws it (`GlDevice.java:260`).
- Initial contents: undefined (Vulkan transitions to GENERAL without clearing).
- Cubemap: `depthOrLayers = 6`, face index = layer, order +X,-X,+Y,-Y,+Z,-Z (GL uses `CUBEMAP_TARGETS[layer % 6]`).
- **Recommendation:** map `COPY_SRC` to also include `MTLTextureUsageShaderRead` (the surface blit samples the main target color texture, which the frontend only requires to have `COPY_SRC`); choose `MTLStorageModePrivate` (or memoryless where provably transient).

### `GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels)`
- API overload `createTextureView(texture)` = `(texture, 0, texture.getMipLevels())`.
- Frontend checks: `!texture.isClosed()`; `baseMipLevel >= 0 && baseMipLevel + mipLevels <= texture.getMipLevels()`.
- View type is cube if the texture has `CUBEMAP_COMPATIBLE` (Vulkan `viewType = 3`, 6 layers), else 2D. Views cover all layers and the given mip range. GL applies the range at bind time via `GL_TEXTURE_BASE_LEVEL/MAX_LEVEL`.
- **Lifetime:** a view keeps the texture alive. Vulkan refcounts: the texture's storage is destroyed only when the texture is closed *and* all its views are closed (`VulkanGpuTexture.removeViews`). `MainTarget` closes textures *before* their views (`B3D/pipeline/MainTarget.java:35-53`). A view of a closed texture remains usable (the view's own `isClosed()` is what gets checked).

### `GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size)`
- Frontend check: `size > 0`.
- Usage bits (`GpuBuffer`): `MAP_READ=1`, `MAP_WRITE=2`, `HINT_CLIENT_STORAGE=4`, `COPY_DST=8`, `COPY_SRC=16`, `VERTEX=32`, `INDEX=64`, `UNIFORM=128`, `UNIFORM_TEXEL_BUFFER=256`, `INDIRECT_PARAMETERS=512`.
- Common combos seen: staging `22` (MAP_WRITE|CLIENT_STORAGE|COPY_SRC), readback `9` (MAP_READ|COPY_DST), dynamic UBO rings `130` (UNIFORM|MAP_WRITE), persistently mapped staging ring `18`, vertex pool `40`, index pool `72`, uber buffers `usage|8|16`.
- Contents are undefined; a buffer created with `MAP_WRITE` is mapped and written immediately after creation, before any submit (`MC/client/gui/font/providers/BitmapProvider.java:263-271`).
- Throw `GpuOutOfMemoryException` on failure (GL does, `GlDevice.java:286`).
- **Recommendation:** any buffer with `MAP_READ` or `MAP_WRITE` (or `HINT_CLIENT_STORAGE`) -> `MTLStorageModeShared` (never `Managed`: `Managed` needs `didModifyRange`/`synchronizeResource`, which this API has no hook for). Others -> `Private`.

### `GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data)`
- Frontend check: `data.hasRemaining()`.
- Size is `data.remaining()`; data is read from `data.position()`. The caller frees `data` immediately after return (`RenderSystem.AutoStorageIndexBuffer.ensureStorage`, `B3D/systems/RenderSystem.java:450-467`), so **copy synchronously**.
- Called with usages that do **not** include `COPY_DST` (e.g. `64` for the shared quad index buffer). Vulkan silently ORs `COPY_DST` into the usage (`VulkanDevice.java:226`) and records a staging copy into a special "object init" command buffer that executes at the start of the current submit.
- **Recommendation:** for Private storage, encode the upload blit before any command that could reference the buffer (any point before the next recorded command works, since the buffer is new); for small buffers `newBufferWithBytes` into Shared is fine.

### `List<String> getLastDebugMessages()` / `boolean isDebuggingEnabled()`
- `isDebuggingEnabled()` gates texture labels (above) and the crash-report section; `getLastDebugMessages()` is shown in the crash report and in the "Recovering from unsupported resolution" message box (`MC/client/Minecraft.java:675, 2513`). Vulkan returns `List.of()`.

### `BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo)`
- See section 9. **Called on background threads.**

### `void close()`
- Called once from `FrontendGpuDevice.close()` during `RenderSystem.shutdownRenderer()`. See 2.2.

### `GpuQueryPool createTimestampQueryPool(int size)`
- No frontend validation. Callers: `TracyGpuProfiler` (size 1024, only when Tracy is available), `TimerQuery` (size 6, created in `Minecraft` constructor).

### `long getTimestampCalibrationOffset()`
- Called by `TracyGpuProfiler` (constructor and every 5 s from `endFrame()`), via package-private `FrontendGpuDevice.getTimestampCalibrationOffset()`. Only matters when Tracy is attached.
- Meaning: `hostTimeNs - deviceTimestampTicks * timestampPeriod`, where host time is in the `System.nanoTime()` domain. Tracy converts a query value with `(long)(ticks * period) + calibrationOffset` and the Tracy GPU context is created with `System.nanoTime()` (`RP/frontend/TracyGpuProfiler.java:24, 62`). GL: `System.nanoTime() - glGetInteger64(GL_TIMESTAMP)`. Vulkan: calibrated timestamps if available, else submits a timestamp-only command buffer synchronously.
- **Speculation (Metal):** `-[MTLDevice sampleTimestamps:gpuTimestamp:]` gives a correlated CPU (mach_absolute_time domain) / GPU pair; convert the CPU value to the `System.nanoTime()` domain (on macOS the JVM's `nanoTime` is mach-based but scaling to ns must be verified).

### `DeviceInfo getDeviceInfo()`
- Must be valid from construction, immutable, and **thread-safe** (read from background compile threads: `GlslCompiler.createBaseShaderOptions` calls `RenderSystem.getDevice().getDeviceInfo()`, `PipelineBuilder.compilePipeline` reads `features()`).

#### DeviceInfo fields and their consumers

`DeviceInfo(name, vendorName, driverInfo, isZZeroToOne, backendName, timestampPeriod, limits, features, underlyingExtensions, hintsAndWorkarounds, type)` (`RP/api/device/DeviceInfo.java`).

| Field | Consumer(s) | Notes / recommended Metal value |
|---|---|---|
| `name`, `vendorName`, `driverInfo`, `backendName`, `type`, `underlyingExtensions` | logs, F3 screen, crash report, telemetry | `backendName` e.g. `"Metal"`. `type` used only for display. |
| `isZZeroToOne` | GlslCompiler define, `Projection`, `Camera` culling frustum | `true`. |
| `timestampPeriod` (ns per tick) | `TimerQuery` (`delta * timestampPeriod`), Tracy | GL `1.0`; Vulkan `limits.timestampPeriod`. |
| `limits.maxAnisotropy` | `createSampler` validation, `Options` anisotropy clamp | e.g. 16. |
| `limits.minUniformOffsetAlignment` | always-on `setUniform(slice)` check; block size of `DynamicGpuDataStorageMapped`, `Lighting`, `TextureAtlas` sprite UBOs | Must satisfy Metal constant-buffer offset alignment. **Speculation:** 256 on Intel/AMD Macs; Apple GPUs allow much smaller (verify against the Metal feature-set tables before going below 256; smaller saves UBO ring memory). |
| `limits.maxTextureSize` | `MainTarget`, `RenderTarget`, `maxTextureSizeForFormat` (atlases, window max size) | 16384 on all current Mac GPUs (**verify per family**). |
| `limits.maxMemoryAllocationSize` | `maxTextureSizeForFormat = highestOneBit(min(maxTextureSize, sqrt(maxAlloc / blockSize)))` | `MTLDevice.maxBufferLength` or similar. |
| `limits.maxMultiDrawDirectInterleavedDrawCount` | frontend `multiDraw*` interleaved check | only relevant if that feature is `true`. |
| `limits.maxColorAttachments` | frontend `createRenderPass` check | 8. |
| `limits.maxDrawIndirectDrawCount` | `LevelRenderer` enables the indirect terrain path iff `> 0 && features.nonZeroFirstInstance && !hints.multiDrawIndirectHasKnownIssues`; `ChunkSectionsToRender.DrawIndirect` splits draws into chunks of at most this many | Metal has no native multi-draw-indirect; if the backend loops on CPU, `Integer.MAX_VALUE` is fine. |
| `features.wireframeFillMode` | `PipelineBuilder` rejects WIREFRAME pipelines if false; `RenderSystem.isWireframeAvailable()` | `true` (`setTriangleFillMode`). |
| `features.shaderDrawParameters` | `#define RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` | `true` (Metal `[[instance_id]]` includes base instance; Vulkan reports `true`). |
| `features.multiDrawDirectInterleaved` / `multiDrawDirectSeparate` | frontend gates `multiDraw*`; vanilla never calls these | Either `false`, or `true` implemented as CPU loops. |
| `features.multiDrawIndirect`, `drawIndirect` | frontend gates `draw*Indirect` | `true` (loop per command). |
| `features.nonZeroFirstInstance` | frontend gates `firstInstance != 0`; terrain indirect path | `true`. |
| `features.persistentMapping` | `StagingBuffer.create`; GL `checkCanBeUsed` semantics | `true` (shared buffers can be used by the GPU while mapped). |
| `hints.writeToBufferIsSlow` | `StagingBuffer.create`: `writeToBufferIsSlow && persistentMapping` -> persistently mapped ring + `copyToBuffer`, else CPU staging + `writeToBuffer` | Either works; see 7.6. |
| `hints.anisotropyHasKnownIssues` | `GraphicsPreset` | `false`. |
| `hints.isExplicitDepthRequired` | `#define RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` (OIT shaders write `gl_FragDepth = gl_FragCoord.z`) | GL and Vulkan both set `Util.isAppleSiliconMac(deviceName)` = `name.startsWith("Apple")`. Mirror that. |
| `hints.multiDrawIndirectHasKnownIssues` | disables terrain indirect path | `false`. |

---

## 6. Backend resource objects and lifetime rules

### 6.1 `GpuBuffer` (`RP/api/buffers/GpuBuffer.java`)

API: `size()`, `usage()`, `isClosed()`, `close()`, default `slice(offset, length)` (bounds-checked, throws `IllegalArgumentException`), default `slice()` = `(0, size)`, default `map(read, write)` = `map(0, size, read, write)`, abstract `map(offset, length, read, write)`.

`GpuBufferSlice(buffer, offset, length)` is a record; `slice.slice(o, l)` re-bases; `slice.map(read, write)` = `buffer.map(offset, length, read, write)`.

`map` contract (backend-implemented; both reference backends perform the same checks, in this order, `RP/backend/vulkan/VulkanGpuBuffer.java`, `RP/backend/opengl/GlBuffer.java`):

1. closed -> `IllegalStateException("Buffer already closed")`
2. `!read && !write` -> `IllegalArgumentException`
3. `read` without `MAP_READ` -> `IllegalStateException("Buffer is not readable")`; `write` without `MAP_WRITE` -> `IllegalStateException("Buffer is not writable")`
4. `offset + length > size` -> `IllegalArgumentException`; `length > Integer.MAX_VALUE` -> `IllegalArgumentException`; negative offset/length -> `IllegalArgumentException`
5. increment a mapping refcount; return `new MappedView(this.slice(offset, length), ByteBuffer covering exactly [offset, offset+length), onClose)`.

`MappedView(slice, data, onClose)` is an `UncheckedAutoCloseable` record whose `close()` runs `onClose`. Both backends make `onClose` **idempotent** (inner `closed` flag) and decrement the refcount; GL unmaps when it reaches 0. After close the `ByteBuffer` must not be used.

Semantics that callers depend on:

- **No implicit GPU synchronization.** Vulkan maps without waiting. GL uses `MAP_UNSYNCHRONIZED` for write maps (except GL-on-DX12) and persistent+coherent mapping when available. Callers synchronize with fences (`MappableRingBuffer`) or readback callbacks.
- **Multiple simultaneous maps of the same buffer are allowed** (refcount).
- **Mapped buffers may be used by the GPU** when `features.persistentMapping` is true: `StagingBuffer.PersistentlyMapped` keeps a `MappedView` open while recording `copyToBuffer` from it (`B3D/vertex/StagingBuffer.java:123-147`).
- **CPU writes made through a map before `submit()` are visible to every command in that submit**, including commands recorded earlier (they all execute after commit). `DynamicGpuDataStorageMapped.writeData` relies on this (writes a UBO block, returns a slice, the slice is bound in a later pass of the same frame).
- **`close()` while mapped throws** `IllegalStateException("Attempt to close a mapped buffer")` in both backends (Vulkan sets `closed = true` first, then throws).
- Map with `read = true` is only meaningful after a completed copy (see section 15).

### 6.2 `GpuTexture`, `GpuTextureView`, `GpuSampler`

Plain getters plus `close()`/`isClosed()`. `isClosed()` must turn `true` immediately on `close()`.

### 6.3 Deferred destruction (mandatory)

Reference behaviour:

| Object | Vulkan `close()` | GL `close()` |
|---|---|---|
| `GpuBuffer` | `queueForDestroy(this)` | `glDeleteBuffers` (driver defers) |
| `GpuTexture` | refcount with views; queued when closed and view count is 0 | `glDeleteTextures` |
| `GpuTextureView` | `queueForDestroy`, then `texture.removeViews()` | |
| `GpuSampler` | `queueForDestroy` | |
| `BackendRenderPipeline` | `queueForDestroy` | |
| `GpuQueryPool` | `queueForDestroy` | `glDeleteQueries` |
| `GpuFence` | marks completed, no GPU object | same |

Vulkan's `DestructionQueue(2, ...)` (`RP/backend/vulkan/DestructionQueue.java`) holds two lists; `add` appends to the current list; `submit()` waits for submit N-2, then `rotate()` advances the index and destroys the list it lands on, which holds items queued during the previous-but-one recording. Net effect: **an object closed while recording submit S is destroyed during the `submit()` call that ends submit S+1, after submit S has completed on the GPU.**

Real callers that close objects still referenced by recorded or in-flight work:

- `RenderSystem.AutoStorageIndexBuffer.ensureStorage` closes the old shared index buffer mid-frame when growing (`B3D/systems/RenderSystem.java:461-465`).
- `DynamicGpuDataStorageMapped.endFrame` closes old ring buffers that the just-submitted frame used (`MC/client/renderer/DynamicGpuDataStorageMapped.java:36-47`).
- `MainTarget.resize` / `allocateAttachments`, `TracyFrameCapture.resize`, `CrossFrameResourcePool.endFrame`, `StagedVertexBuffer.GpuBufferPool.endFrame` (closes pooled buffers each frame), `PipelineCache.clear()` on shader reload, the panorama path `target.resize(...)` right after six captures.

**Recommendation:** Metal command buffers created with `-[MTLCommandQueue commandBuffer]` retain referenced resources, which makes Java-side `close()` safe as long as the Java wrapper only drops its own reference. If Ingot uses `commandBufferWithUnretainedReferences` (lower CPU cost), it must implement a destruction queue keyed by submit index, like Vulkan.

---

## 7. `CommandEncoderBackend` (`RP/backend/api/CommandEncoderBackend.java`)

The API `CommandEncoder` (`RP/api/commands/CommandEncoder.java`) public methods: `submit`, `transientMemory`, `createRenderPass` (4 overloads, 3 are defaults building a `RenderPassDescriptor`), `clearColorTexture`, `clearColorAndDepthTextures` (2 overloads), `clearDepthTexture`, `writeToBuffer`, `copyToBuffer`, `writeToTexture` (3 overloads; 2 take a `NativeImage` and are frontend-only), `copyBufferToTexture`, `copyTextureToBuffer` (2 overloads), `copyTextureToTexture`, `createFence`, `writeTimestamp`. There is **no** present/blit method on the encoder; presentation goes through `GpuSurface.blitFromTexture(CommandEncoder, GpuTextureView)` (section 13). (GL has a package-private `GlCommandEncoder.presentTexture` used only by `GlSurface`.)

Common frontend precondition: almost every method throws `IllegalStateException` if a render pass is open (`isInRenderPass`). Exceptions: `submit()`, `transientMemory()`, and `writeTimestamp()` have **no** render-pass check.

### 7.1 `void submit()`
- Frontend: `backend.submit()`, then `profiler.endFrame()` if Tracy. No render-pass check (a pass left open would be a caller bug).
- Called once per frame (section 14).
- Reference backend duties:
  - GL: `glFenceSync` into slot `index % 2`, `currentSubmitIndex++`, wait (infinite) for submit `index - 2`, `transientMemory.rotate()` (`GlCommandEncoder.java:74-83`).
  - Vulkan: end command buffers, `transientMemory.endSubmit()`, signal the timeline semaphore with the submit index, queue-submit, `currentSubmitIndex++`, wait up to 5 s for submit N-2 (throws on timeout with checkpoint info), reset the command pool for the reused slot, rotate the destruction queue (runs deferred destroys and readback callbacks), rotate checkpoints, `transientMemory.beginSubmit()` (`VulkanCommandEncoder.java:submit`).
- Both start with `currentSubmitIndex = 2` and treat index 0/1 as already complete.
- **Metal must:** commit the frame's command buffer(s) (including the drawable blit), bound frames in flight (2 to match references; waiting on N-2 is also what makes fences and transient memory safe), recycle transient memory of completed submits, run deferred destroys and completed readback callbacks on this thread, and **drain an autorelease pool** (Java threads have none; objects returned autoreleased by Metal, e.g. `nextDrawable`, encoders, will otherwise leak; **Recommendation:** wrap each frame and each background compile in an `@autoreleasepool` in the ObjC dylib).
- Frames where nothing was presented (minimized, surface invalid) still call `submit()`. Pacing must be tied to `submit()`, not to `present()`.

### 7.2 `TransientMemory transientMemory()`
- See section 10.

### 7.3 `RenderPassBackend createRenderPass(RenderPassDescriptor descriptor)` / `void submitRenderPass()`
- See section 8.

### 7.4 Clears (outside render passes)

`clearColorTexture(GpuTexture color, Vector4fc clearColor)`
- Frontend `verifyColorTexture`: format has color aspect; not closed; usage has `RENDER_ATTACHMENT` **and** `COPY_DST`; `depthOrLayers == 1`.
- Semantics: clears **all mip levels** (GL loops mips; Vulkan subresource range `0..mipLevels`). Ignores any scissor.

`clearColorAndDepthTextures(GpuTexture color, Vector4fc clearColor, GpuTexture depth, double clearDepth)`
- Frontend: `verifyColorTexture`, `verifyDepthTexture` (depth aspect, not closed, `RENDER_ATTACHMENT|COPY_DST`, 1 layer), equal mip counts.
- Clears all mips of both.

`clearColorAndDepthTextures(color, clearColor, depth, clearDepth, regionX, regionY, regionWidth, regionHeight, mipLevel)`
- Frontend: as above plus `verifyRegion` against the **color** texture at `mipLevel`: `mipLevel < mips`, `0 <= regionX < w`, `0 <= regionY < h`, `w,h > 0`, region inside. The depth texture region is not checked.
- Semantics: clears only that rectangle (memory-row coordinates) at that mip. Vulkan creates temporary views, opens a dynamic-rendering pass and uses `vkCmdClearAttachments`. Used by `GuiItemAtlas` for per-slot clears.
- **Metal pitfall:** load-action clears are whole-attachment. A sub-rect clear requires a draw (quad + scissor, fragment shader writing color and `[[depth(any)]]`) inside a render pass with `loadAction = load`.

`clearDepthTexture(GpuTexture depth, double clearDepth)`
- Frontend `verifyDepthTexture`. Clears all mips.

Clear values: `Vector4fc` is RGBA float; depth is `double` (Vulkan casts to float).

**Recommendation (perf, TBDR):** Minecraft typically clears a target and then immediately opens a render pass on it. Deferring a clear and folding it into the next pass's `loadAction = clear` for the same texture avoids an extra load/store round trip. Must still honour ordering if any other command touches the texture in between.

### 7.5 `void writeToBuffer(GpuBufferSlice destination, ByteBuffer data)`
- Frontend: not in pass; buffer not closed; `COPY_DST`; `data.remaining() <= destination.length()`; `destination.offset + destination.length <= buffer.size()`.
- Semantics: writes `data.remaining()` bytes (from `data.position()`) at `destination.offset()`. **Ordered in the command stream** and **copies `data` before returning** (Vulkan: `transientMemory.uploadStaging(data, 1, COPY_SRC)` + `vkCmdCopyBuffer` + full barrier; GL: `glBufferSubData`). The caller reuses its CPU staging memory immediately (`StagingBuffer.Cpu.copyTo`, `B3D/vertex/StagingBuffer.java:107-109`).

### 7.6 `void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target)`
- Frontend: not in pass; source not closed and has `COPY_SRC`; target not closed and has `COPY_DST`; equal lengths; both in bounds.
- Semantics: GPU buffer-to-buffer copy, stream-ordered. Source may be currently mapped (persistent mapping) and may be a transient staging slice.

### 7.7 `writeToTexture(GpuTexture dst, ByteBuffer src, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height)`
- Frontend (`FrontendCommandEncoder.java:456-519`): not in pass; `0 <= mipLevel < mips`; `width * height * blockSize <= src.remaining()`; rect inside mip; not closed; `COPY_DST`; `depthOrLayer < depthOrLayers` (else `UnsupportedOperationException`).
- Frontend-only overloads: `writeToTexture(dst, NativeImage)` (image must equal mip-0 size; writes at 0,0), and `writeToTexture(dst, NativeImage, mip, layer, x, y)` which additionally requires `dst.getFormat().componentType() == UNORM_8` and component count == `NativeImage.format().components()`, then forwards `source.getPixelBytes()` with the image's width/height.
- Semantics: source rows are tightly packed (`rowLength = width`, `blockSize` bytes per texel), starting at `src.position()`; row 0 goes to `destY`. Copied before return (Vulkan stages it); stream-ordered. For cube maps `depthOrLayer` selects the face.
- Callers update textures that earlier commands in the same frame may still sample (dynamic textures, font glyph pages, `PalettedTextureManager`, sprite mip uploads). **Do not implement with `-[MTLTexture replaceRegion:]`** unless the texture is provably idle.

### 7.8 `copyBufferToTexture(GpuBufferSlice src, int srcX, int srcY, int srcWidth, int srcHeight, GpuTexture dst, int dstX, int dstY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer)`
- Frontend (`FrontendCommandEncoder.java:521-616`): not in pass; mip in range; `srcX + copyWidth <= srcWidth`, `srcY + copyHeight <= srcHeight`; `srcWidth * copyHeight * blockSize <= src.length()` (**quirk:** ignores `srcX/srcY`, so the copy may read past `src.length()` but stays within the backing buffer in practice); dst rect inside mip; src buffer not closed + `COPY_SRC`; dst not closed + `COPY_DST`; `arrayLayer < depthOrLayers`.
- Semantics: the source is a 2D image of `srcWidth x srcHeight` texels, row pitch `srcWidth * blockSize`; copy starts at byte `src.offset + (srcX + srcY * srcWidth) * blockSize` (both backends compute exactly this). Stream-ordered.
- Callers: `CubeMapTexture` (six faces from one tall staging image, `srcY = height * i`, `arrayLayer = i`), `SpriteContents.createAnimationState` (frames from `multiUploadStaging` slices, per mip), `BitmapProvider` glyph upload (from a mapped `22`-usage buffer).
- **Metal note:** `copyFromBuffer:sourceOffset:sourceBytesPerRow:...` has alignment rules on `sourceOffset`/`bytesPerRow` relative to the pixel size; offsets here are always multiples of `blockSize` (**verify** Metal's exact requirements for 1- and 2-byte formats).

### 7.9 `copyTextureToBuffer(GpuTexture src, GpuBuffer dst, long offset, Runnable callback, int mipLevel[, int x, int y, int width, int height])`
- 5-arg overload: frontend checks only "not in pass". Both backends forward to the 9-arg form with `(0, 0, src.getWidth(mip), src.getHeight(mip))`.
- 9-arg overload frontend checks (`FrontendCommandEncoder.java:627-700`): mip in range; `width * height * blockSize + offset <= dst.size()`; src has `COPY_SRC`; dst has `COPY_DST`; rect inside mip; neither closed; `src.getDepthOrLayers() == 1`.
- Semantics: rows tightly packed (`rowLength = width`), row 0 = texel row `y`, written at byte `offset`. Depth formats are allowed by the API (Vulkan picks the aspect via `formatAspectMask`; GL reads `GL_DEPTH_COMPONENT`).
- **Callback contract:** run exactly once, on the render thread, after the copied bytes are visible to `dst.map(true, false)`. Reference timing:
  - GL: `RenderSystem.queueFencedTask(callback)` -> polled with `awaitCompletion(0)` in `RenderSystem.executePendingTasks()`, FIFO, stops at the first incomplete fence (`B3D/systems/RenderSystem.java:323-344`). That runs early in `renderFrame` of a later frame, before `gameRenderer.render()`.
  - Vulkan: `queueForDestroy(callback::run)` -> runs inside `submit()` of the next frame, after submit N-1 completes.
- Callbacks do real work: map the buffer, decode, `buffer.close()`, set fields (see section 15).
- **Metal:** never run callbacks from a `MTLCommandBuffer` completion handler (Metal's thread). Mark completion atomically there; dispatch the `Runnable` from `submit()` (Vulkan style) or via `RenderSystem.queueFencedTask` (GL style).

### 7.10 `copyTextureToTexture(GpuTexture src, GpuTexture dst, int mipLevel, int dstX, int dstY, int srcX, int srcY, int width, int height)`
- Frontend: not in pass; mip in range for both; dst and src rects inside the mip; neither closed; src `COPY_SRC`; dst `COPY_DST`; both single-layer.
- Same mip level on both sides; same region size; no scaling. Used by `RenderTarget.copyDepthFrom` / color copy (`B3D/pipeline/RenderTarget.java:72, 85`) for both color and **depth** (`D32_FLOAT`) textures. Formats are expected to match (GL blits with `GL_NEAREST`, Vulkan `vkCmdCopyImage`).

### 7.11 `GpuFence createFence()`
- Frontend: throws if a render pass is open.
- See section 11.

### 7.12 `void writeTimestamp(GpuQueryPool pool, int index)`
- Frontend: `index >= 0 && index <= pool.size()` else `IllegalStateException`. **Off-by-one:** `index == size` passes; the backend must bounds-check. No render-pass check.
- See section 12.

---

## 8. Render pass lifecycle and `RenderPassBackend` mapping

### 8.1 Creating a pass (`FrontendCommandEncoder.createRenderPass`, lines 99-217)

API overloads build a `RenderPassDescriptor` (`RP/api/commands/RenderPassDescriptor.java`): `label: Supplier<String>`, `colorAttachments: List<@Nullable Attachment<Optional<Vector4fc>>>` (copied into an unmodifiable list in the record constructor), `depthAttachment: @Nullable Attachment<OptionalDouble>`, `renderArea: RenderArea(x, y, width, height)`. `Attachment(textureView, clearValue)`; empty Optional = load existing contents, present = clear. `Builder.withUnusedColorAttachment()` adds a `null` slot. Default render area = `(0, 0, w, h)` of the **last** non-null color attachment, else of the depth attachment.

Frontend checks, in order:
1. no pass already open (`isInRenderPass`);
2. `colorAttachments.size() <= limits.maxColorAttachments`;
3. at least one attachment (color list non-empty or depth present; note a list containing only `null`s counts as non-empty);
4. reference size from `colorAttachments.getFirst()` if the list is non-empty, else from depth. **Pitfall:** if the first color slot is `null`, this is an `assert` then a `NullPointerException`;
5. render area inside the reference size;
6. each non-null color view: not closed, texture has `RENDER_ATTACHMENT`, single layer, mip-0 size of the *view* equals reference size;
7. depth view: same checks.

Then: `isInRenderPass = true`; Tracy `pushZone` (which calls `this.writeTimestamp(...)` on the encoder, i.e. **before** the backend pass is begun); `backend.createRenderPass(descriptor)`; wrap in `new FrontendRenderPass(backendPass, device, colorAttachments, hasDepth, this::submitRenderPass, renderArea)`.

Backend responsibilities at pass begin (both reference backends agree):

- Bind attachments: color slot `i` -> view `i` (null slots present but unused; Vulkan sets `imageView = 0`, GL draw buffer `GL_NONE`).
- For each attachment with a clear value: clear **within the render area only** (Vulkan `renderArea`; GL with scissor test set to the render area). Otherwise preserve contents.
- **Viewport = full attachment size** `(0, 0, w, h)` taken from the last non-null color attachment (or depth). The viewport is **not** the render area and there is no API to change it.
- **Initial scissor = render area.**
- Begin a debug group with `descriptor.label()` (Vulkan/GL), ended in `submitRenderPass()`. `label` is a `Supplier`; evaluate only when debugging (it allocates).
- **Metal pitfall:** a Metal load-action clear affects the entire attachment. If the render area is smaller than the attachment and a clear is requested, emulate (load + clear-quad). In vanilla every pass uses the default full-size render area (no caller of `withRenderArea` or the 6-arg overload exists in `net/minecraft`), so this is only for mods.
- Mip-level views as attachments: the attachment size is the view's mip-0 size (`view.getWidth(0)`), i.e. render into `baseMipLevel`.

Vanilla pass shapes that matter: depth-only passes with zero color attachments ("Integrate always on top depth", "Water mask", `MC/client/renderer/LevelRenderer.java:499, 668`); MRT with `OIT_TRANSMITTANCE_TARGET_COUNT` color attachments plus depth (`MC/client/renderer/oit/OitRenderPassProvider.java:34-46`); passes that sample one depth texture while rendering to another.

### 8.2 Ending a pass

`RenderPass.close()` (`FrontendRenderPass.java:492-502`): if not closed: `isClosed = true`; if `pushedDebugGroups > 0` throw `IllegalStateException` **before** finishing (the encoder then stays "in render pass" forever; caller bug); else `onFinish.run()` -> `FrontendCommandEncoder.submitRenderPass()` -> `isInRenderPass = false`, `backend.submitRenderPass()`, Tracy `popZone` (encoder `writeTimestamp` **after** the backend pass ended). Double close is a no-op.

`CommandEncoderBackend.submitRenderPass()` takes no arguments: the backend tracks its current pass. Vulkan: `vkCmdEndRenderingKHR`, end debug group, full memory barrier. After it returns, the next operation may read anything the pass wrote.

### 8.3 Per-method mapping

`FrontendRenderPass` state: `boundPipeline`, `vertexBuffers[16]`, `indexBuffer`, `uniforms: HashMap<String,Object>`, `constantsPushed`, `pushedDebugGroups`, `isClosed`.

| API method | Frontend checks (always) | Backend call(s) |
|---|---|---|
| `pushDebugGroup(Supplier<String>)` | not closed | `pushDebugGroup(label)` |
| `popDebugGroup()` | not closed; count > 0 | `popDebugGroup()` |
| `writeTimestamp(pool, idx)` | `0 <= idx <= size` (off-by-one) | `writeTimestamp(pool, idx)` |
| `setPipeline(CompiledRenderPipeline)` | must be `FrontendRenderPipeline`; `colorTargetStates.size() == colorAttachments.size()`; for each **non-null** attachment the target state is non-null and `format == attachment texture format` | `setPipeline(backendPipeline)`, then for **every** stored uniform `(name, value)`: if the pipeline's `uniformIndices` contains `name`, `setUniform(index, value)`; `constantsPushed = false` |
| `setUniform(name, GpuTextureView, GpuSampler)` | both null or both non-null | value `new TextureViewAndSampler(view, sampler)` or `null` (see below) |
| `setUniform(name, GpuBuffer)` | | `setUniform(name, buffer.slice())` (throws for transient buffers, whose `slice()` throws) |
| `setUniform(name, GpuBufferSlice)` (also `UniformUploader.setUniform`) | `slice.offset() % minUniformOffsetAlignment == 0` | |
| (common path) | | `uniforms.put(name, value)` or `remove(name)` if null; if a pipeline is bound and uses `name`: `setUniform(index, value)` (value may be `null`) |
| `pushConstants(ByteBuffer)` (also `UniformUploader.pushConstants`) | pipeline bound; `value.remaining() >= pushConstantSize` | `pushConstants(value)`; `constantsPushed = true` |
| `enableScissor(x, y, w, h)` | `w > 0 && h > 0`; rect inside the render area | `enableScissor(x, y, w, h)` |
| `disableScissor()` | none | `disableScissor()` (backend resets scissor to the render area) |
| `setVertexBuffer(slot, @Nullable slice)` | `0 <= slot < 16`; if non-null: not closed, has `VERTEX` | `setVertexBuffer(slot, slice)` |
| `setIndexBuffer(GpuBuffer, IndexType)` | none | `setIndexBuffer(buffer, type)` |
| `drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)` | not closed; `firstInstance == 0` unless `nonZeroFirstInstance`; pipeline bound | `drawIndexed(...)` |
| `multiDrawIndexed(IntBuffer drawParameters, instanceCount, firstInstance, drawCount)` | not closed; feature `multiDrawDirectInterleaved`; firstInstance rule; `drawCount <= maxMultiDrawDirectInterleavedDrawCount`; `remaining >= drawCount * 3` | `multiDrawIndexed(...)` |
| `multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, drawCount)` | not closed; feature `multiDrawDirectSeparate`; each buffer has `>= drawCount` | `multiDrawIndexed(...)` |
| `drawIndexedIndirect(GpuBufferSlice commands, drawCount)` | not closed; feature `drawIndirect`; `drawCount > 1` needs `multiDrawIndirect`; buffer has `INDIRECT_PARAMETERS (512)`; `length >= drawCount * 20`; `offset % 4 == 0` | `drawIndexedIndirect(commands, drawCount)` |
| `draw(vertexCount, instanceCount, firstVertex, firstInstance)` | not closed; firstInstance rule; pipeline bound | `draw(...)` |
| `multiDraw(IntBuffer drawParameters, instanceCount, firstInstance, drawCount)` | interleaved feature; firstInstance rule; count limit; `remaining >= drawCount * 2` | `multiDraw(...)` |
| `multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, drawCount)` | separate feature; `remaining >= drawCount` each | `multiDraw(...)` |
| `drawIndirect(commands, drawCount)` | as indexed, with `length >= drawCount * 16` | `drawIndirect(commands, drawCount)` |
| `drawMultipleIndexed(draws, defaultIndexBuffer, defaultIndexType, dynamicUniforms, uniformArgument)` | not closed; `validateDraw` once (strict part skips vertex-buffer checks and the named dynamic uniforms) | expanded per draw, see 8.6 |
| `close()` | see 8.2 | `submitRenderPass()` via encoder |

"Pipeline bound" (`Can't draw without a render pipeline`) is the only `validateDraw` check that is always on. There is no `bindSampler` in 26.3; textures are bound through `setUniform(name, view, sampler)`.

### 8.4 State persistence rules inside one pass

- **Vertex and index buffer bindings persist across `setPipeline`** (GL re-applies them to the new VAO; Vulkan command-buffer bindings persist).
- **Uniforms persist in the frontend map** across pipelines and are re-sent after every `setPipeline`. The backend receives only the uniforms the new pipeline actually uses; any slot the frontend never sets stays whatever the backend initialized it to (Vulkan clears its array to `null` on `setPipeline` and throws `Missing uniform/sampler` at draw time if a used slot is null).
- **Scissor persists** across pipelines.
- **Push constants:** frontend resets `constantsPushed` on `setPipeline`; the backend is not told. Vulkan push constants survive pipeline changes if layouts are compatible.
- Vertex/index bindings, uniforms and scissor **do not carry over** between passes (new backend pass object each time; Vulkan dynamic rendering starts fresh).

### 8.5 Values, indices and layouts

**`setUniform(int index, @Nullable Object value)`** - `index` is the binding number assigned by `PipelineBuilder` (section 9.3), i.e. the position in `CreateInfo.uniforms()`. The type of `value` is determined by `CreateInfo.uniforms().get(index).type()`:

| `UniformType` | value type | notes |
|---|---|---|
| `UNIFORM_BUFFER` | `GpuBufferSlice` | `offset % minUniformOffsetAlignment == 0` (always checked); bind range = `[offset, offset + length)`. Buffer may be a transient slice. |
| `TEXEL_BUFFER` | `GpuBufferSlice` | Format from `UniformDescription.gpuFormat()` (non-null for texel buffers). Strict mode requires the slice to cover the whole buffer. Only vanilla user: `"CloudFaces"` with `R8_SINT` (`MC/client/renderer/BindGroupLayouts.java:33, 87`). Vulkan creates a `VkBufferView` per descriptor push and queues it for destruction. |
| `COMBINED_IMAGE_SAMPLER` | `com.mojang.renderpearl.util.TextureViewAndSampler(view, sampler)` | A new record instance per `setUniform` call. View carries the mip range; cube views for cube textures. |
| any | `null` | Caller unbound it (`setUniform(name, null, null)`). |

In production nothing checks the runtime type; the backend should `instanceof`-check and fail clearly.

**`pushConstants(ByteBuffer value)`** - copy `value.remaining()` bytes from `value.position()` at call time (GL uploads to transient memory immediately; Vulkan `vkCmdPushConstants` at offset 0 for all stages). Size is at most 128 (`RenderPipeline.Builder.build` rejects > 128). Vanilla 26.3 never uses push constants (no `withPushConstantSize` call and no `push_constant` in `assets/minecraft/shaders`), so this is for mods. **Recommendation:** `setVertexBytes`/`setFragmentBytes`.

**`setVertexBuffer(int slot, @Nullable GpuBufferSlice)`** - `slot` = `CreateInfo.VertexBuffer.bufferSlot` (0..15); the slice offset is the base offset for that slot. `null`: Vulkan ignores the call (keeps the old binding); GL stores null. Vanilla calls `setVertexBuffer(0, null)` in `CloudRenderer` (vertex-pulling via `gl_VertexIndex` and a texel buffer). Non-zero offsets occur (terrain indirect path binds slices).

**`setIndexBuffer(GpuBuffer, IndexType)`** - whole buffer, implicit offset 0. `IndexType.SHORT` = 2 bytes (uint16), `INT` = 4 bytes (uint32). In the terrain indirect path the slice is discarded (`indexBuffer().buffer()`), so `firstIndex` values are absolute.

**`drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)`** - `firstIndex` in index units (GL byte offset = `firstIndex * type.bytes`), `vertexOffset` = base vertex added to each index, `firstInstance` = base instance (affects instance-rate attributes). **Metal:** `indexBufferOffset = firstIndex * bytes`. **Verify:** Metal's alignment rule for `indexBufferOffset`; `StagedVertexBuffer` can produce odd `firstIndex` with `SHORT` indices (offset 2 mod 4) (`MC/client/renderer/StagedVertexBuffer.java:106, 193`).

**`draw(vertexCount, instanceCount, firstVertex, firstInstance)`** - `gl_VertexIndex` must include `firstVertex`: `SpriteContents` encodes animation progress as `draw(6, 1, frameProgressAsInt << 3, 0)` and the shader decodes `gl_VertexIndex >> 3` (`MC/client/renderer/texture/SpriteContents.java:389`, `assets/minecraft/shaders/core/animate_sprite.vsh:19-20`). Metal's `[[vertex_id]]` includes `vertexStart` (non-indexed) and `baseVertex` (indexed), matching Vulkan.

**Interleaved multi-draw parameter layouts** (Vulkan `EXT_multi_draw` structs, tightly packed `int`s):
- indexed: `{firstIndex, indexCount, vertexOffset}` per draw (`VkMultiDrawIndexedInfoEXT`);
- non-indexed: `{firstVertex, vertexCount}` per draw (`VkMultiDrawInfoEXT`).

**Separate multi-draw** (GL `glMultiDrawElementsBaseVertex` semantics): `firstIndexOffsets` is a `PointerBuffer` of **byte offsets** into the index buffer; `indexCounts`, `vertexOffsets` (base vertices). Non-indexed: `firstVertices`, `vertexCounts`.

**Indirect command layouts** (tightly packed, stride = size): indexed 20 bytes `{indexCount, instanceCount, firstIndex, int vertexOffset, firstInstance}` (`RenderPass.INDIRECT_INDEXED_DRAW_SIZE = 20`); non-indexed 16 bytes `{vertexCount, instanceCount, firstVertex, firstInstance}` (`INDIRECT_DRAW_SIZE = 16`). These are byte-identical to `MTLDrawIndexedPrimitivesIndirectArguments` / `MTLDrawPrimitivesIndirectArguments`. Metal draws one indirect command per call, so loop `drawCount` times advancing `indirectBufferOffset` by the stride. Vanilla user: `ChunkSectionsToRender.DrawIndirect` (terrain), with slot 1 bound to per-instance `CHUNK_DATA_INSTANCED` data indexed by `firstInstance`.

**Scissor** - memory-row coordinates (section 3). `disableScissor()` restores the render-area scissor, not "no scissor".

### 8.6 `drawMultipleIndexed` expansion (`FrontendRenderPass.java:331-398`)

```java
this.validateDraw(dynamicUniforms, true, false);          // strict parts only
for (Draw<T> draw : draws) {
   if (draw.uniformUploaderConsumer() != null)
      draw.uniformUploaderConsumer().accept(uniformArgument, this); // -> setUniform(name, slice) / pushConstants
   IndexType indexType = draw.indexType() == null ? defaultIndexType : draw.indexType();
   GpuBuffer indexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();
   this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());   // whole buffer, offset 0
   ... strict checks ...
   if (indexBuffer != lastIndexBuffer || indexType != lastIndexType) this.setIndexBuffer(indexBuffer, indexType);
   if (pipeline.pushConstantSize() > 0 && !constantsPushed) throw ...;   // always on
   backend.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
}
```

So the backend sees an interleaving of `setUniform(idx, slice)` / `setVertexBuffer` / `setIndexBuffer` / `drawIndexed` calls, potentially thousands per pass (terrain `DrawSeparate` path rebinds the `"ChunkSection"` UBO per draw). Rebinding cost per draw matters. **Recommendation:** when only the offset changes, use `setVertexBufferOffset:atIndex:` / `setFragmentBufferOffset:atIndex:`.

### 8.7 Timestamps inside passes

`RenderPassBackend.writeTimestamp(pool, index)` writes mid-pass (Vulkan `vkCmdWriteTimestamp2` on the same command buffer). Vanilla never calls `RenderPass.writeTimestamp`. **Metal pitfall:** Apple-silicon GPUs only support counter sampling at stage boundaries (`MTLCounterSamplingPointAtStageBoundary`), so mid-pass timestamps cannot be exact; see section 12.

---

## 9. Pipelines

### 9.1 Flow

```
GpuDevice.compilePipeline(RenderPipeline, ShaderSource, Executor)          [caller thread]
  -> PipelineBuilder.compilePipeline                                         RP/frontend/shaders/PipelineBuilder.java:366-465
     if WIREFRAME && !features.wireframeFillMode -> completedFuture(Pending.NULL)
     CompletableFuture.supplyAsync(() -> {                                   [executor thread]
        createInfo = generateBackendCreateInfo(...)   // GLSL->SPIR-V (shaderc), reflection, validation, binding rewrite
        if (createInfo == null) return CompiledRenderPipeline.Pending.NULL;
        backendPending = backendDevice.compilePipeline(createInfo);          [executor thread]
        return () -> {                                                       [whoever calls finishCompile]
           backendPipeline = backendPending.finishCompile();
           executor.execute(() -> shaderCreateInfos.forEach(s -> s.module().close()));   // frees SPIR-V
           return backendPipeline == null ? null : new FrontendRenderPipeline(...);
        };
     }, executor)
```

Callers:
- `ShaderManager.reload` (`MC/client/renderer/ShaderManager.java:95-104`): `device.compilePipeline(pipeline, shaderSource, taskExecutor)` for every required and optional pipeline, then `.thenApplyAsync(CompiledRenderPipeline.Pending::finishCompile, reloadExecutor)`. `taskExecutor` = `Util.backgroundExecutor().forName("resourceLoad")`; `reloadExecutor` = the `Minecraft` instance, i.e. the **render thread** (`MC/client/Minecraft.java:710, 1028`). Many compiles run concurrently while the game keeps rendering frames (loading overlay).
- `PipelineCache.get` (`B3D/pipeline/PipelineCache.java:27-40`): on cache miss, `compilePipeline(pipeline, shaderSource, Util.backgroundExecutor()).join().finishCompile()` on the calling thread (render thread, via `RenderSystem.getCompiledPipeline*`), blocking.

### 9.2 Backend obligations

- `GpuDeviceBackend.compilePipeline(CreateInfo)`: runs on a background worker, possibly many in parallel, concurrently with render-thread command recording. Do the heavy work here (SPIR-V -> MSL via spvc, `newLibraryWithSource`, `newRenderPipelineState...`; Metal device calls are thread-safe). Any Java-side shared caches need synchronization. Wrap in an autorelease pool.
- `Pending.finishCompile()`: runs on the render thread; keep it cheap. Vulkan does everything in `compilePipeline` and returns `() -> pipeline` (`VulkanDevice.java:263-266`); GL decompiles SPIR-V to GLSL in `compilePipeline` and links the program in `finishCompile` (needs the context; `GlDevice.java:355-367`).
- **SPIR-V lifetime:** after `finishCompile()` returns, the frontend frees the `SpvModule`s (and their spvc contexts) on the executor. The backend must not retain `SpvModule`, `spv()` buffers, or `Reflection` objects. Copy what you need.
- **Failure handling:** return `BackendRenderPipeline.Pending.NULL` (or a Pending whose `finishCompile()` returns `null`) and log. An exception thrown from `compilePipeline` fails the `CompletableFuture`, which `Util.sequence` propagates and which fails the whole shader reload. GL returns `Pending.NULL` when decompilation fails.
- `BackendRenderPipeline.isClosed()/close()`: render thread; defer destruction (pipelines can be in flight when `PipelineCache.clear()` runs).

### 9.3 `BackendRenderPipeline.CreateInfo` contents

```java
record CreateInfo(String name,                          // pipeline Identifier, e.g. "minecraft:pipeline/solid"
   List<Shader> shaders,                               // Shader(name = shader Identifier string, entryPoint = "main", SpvModule module)
   List<VertexBuffer> vertexBuffers,                   // VertexBuffer(bufferSlot, stride = VertexFormat.getVertexSize(), stepRate)
   List<AttribBinding> attribBindings,                 // AttribBinding(bufferSlot, location, offset, GpuFormat format)
   List<BindGroupLayout.UniformDescription> uniforms,  // index == binding number
   int pushConstantsSize,
   @Nullable DepthStencilState depthStencilState,      // null => no depth test, no depth write
   PolygonMode polygonMode, boolean cull,
   List<@Nullable ColorTargetState> colorTargetStates, // may be empty (depth-only) or contain nulls (unused slots)
   PrimitiveTopology primitiveTopology)
```

How `PipelineBuilder.generateBackendCreateInfo` (lines 65-364) fills it:

- **Shaders** iterate `pipeline.getShaders()` (an `EnumMap`, so VERTEX then FRAGMENT). GLSL is compiled by shaderc with target env Vulkan 1.2 (`set_target_env(0, 4202496)`), `auto_bind_uniforms = true`, `preserve_bindings = false`, debug info on, optimization level 0, macros `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`, `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`, `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` per DeviceInfo, plus the pipeline's `ShaderDefines` (`GlslCompiler.java:74-111`). Entry point is always `"main"`.
- **Vertex inputs:** for each non-null `VertexFormat` at binding index `i` (0..15): one `VertexBuffer(i, vertexSize, stepRate)`; for each element whose name matches a vertex shader input, an `AttribBinding(i, location, element.offset(), element.format())`. Repeated element names (matrix columns) get consecutive locations. Inputs with no matching element fail compilation. `stepRate == 0` = per-vertex; `> 0` = per-instance with that divisor (Vulkan `inputRate = INSTANCE` + divisor; `DefaultVertexFormat.CHUNK_DATA_INSTANCED` uses 1). Element types are checked against the shader input base type (float/uint/int) and component count.
- **Interface matching:** vertex outputs vs fragment inputs by location, base type, vector length and `flat`; `Component` decorations are rejected; 64-bit and struct interface types rejected.
- **Uniforms and bindings:** for each shader (vertex first) and each reflected descriptor (resource types 1 uniform buffer, 2 storage buffer, 6 storage image, 7 sampled image, 10 separate image, 11 separate sampler), the name must exist in the pipeline's bind group layouts; `binding = uniformBindings.computeIfAbsent(name, size())` (first-seen order), then **the SPIR-V is rewritten in place**: `descriptor.binding(newBinding)` and `descriptor.descriptorSetIndex(0)`. A name used in both stages gets the same binding. `CreateInfo.uniforms` lists the `UniformDescription` per binding in that order. Checks: resource type matches (`COMBINED_IMAGE_SAMPLER`/`TEXEL_BUFFER` -> 7, `UNIFORM_BUFFER` -> 1), dimensions (2D/Cube/Rect -> sampler, Buffer -> texel buffer, 1D/3D rejected).
- **Only uniforms referenced by the shaders are in `CreateInfo.uniforms` and in `FrontendRenderPipeline.uniformIndices`.** `FrontendRenderPipeline.uniforms()` (used by strict validation) is the full flattened bind group layout list, which can be larger.
- **Push constants:** at most one block per stage, size `<=` pipeline `pushConstantSize`.
- `FrontendRenderPipeline(name, backendPipeline, vertexFormats (16 entries, nullable), uniformIndices (name -> binding), uniforms, colorTargetStates, wantsDepthTexture = depthStencilState != null, pushConstantSize)`.

Consequences for Metal translation (**Recommendation**):
- Read bindings from the rewritten `spv()` (all set 0, binding = uniform index). With spvc MSL, add explicit resource bindings per stage (`spvc_compiler_msl_add_resource_binding`) mapping (set 0, binding i) to buffer/texture/sampler indices, and keep vertex buffers in a disjoint Metal buffer-index range (e.g. high indices) since uniforms and up to 16 vertex buffers share the 31-entry buffer table.
- A combined image sampler becomes a texture + sampler pair in MSL; texel buffers become `texture_buffer<T>`.
- Apply `FLIP_VERTEX_Y` (section 3). `POINTS` needs the point-size builtin (`SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN`); `debug_point.vsh` is the vanilla user.
- **Render pipeline state must match the pass's attachment formats exactly in Metal**, but the frontend only checks color formats of non-null attachments, and a pipeline may be used with or without a depth attachment (Vulkan builds `withDepthPipeline` and `withoutDepthPipeline` variants and picks per pass, `VulkanRenderPass.java:133`). Build PSO variants keyed by (color formats including nulls, depth format), lazily, and depth-stencil state objects separately.
- Topologies (`VulkanConst.toVk`): `LINES` -> triangle list (lines are expanded to quads; index pattern from `RenderSystem.sharedSequentialLines`), `QUADS` -> triangle list (with `sharedSequentialQuad`), `DEBUG_LINES` -> line list, `DEBUG_LINE_STRIP` -> line strip, `POINTS` -> point list, `TRIANGLES`, `TRIANGLE_STRIP`, `TRIANGLE_FAN` -> triangle fan. **Metal has no triangle fan.** Vanilla uses it for the sky disc (`SkyRenderer`, non-indexed `draw(10,...)`, `draw(18,...)`) and the `DEBUG_TRIANGLE_FAN` render type; these need conversion (e.g. a generated fan index buffer for non-indexed draws).
- Blend: `ColorTargetState(Optional<BlendFunction>, GpuFormat format, writeMask)`; all non-null targets must share one blend function (`RenderPipeline.Builder.build`). Blend constants have no API (factors `CONSTANT_*` see the default blend color).
- `DepthStencilState(depthTest, writeDepth, depthBiasScaleFactor, depthBiasConstant)`: Vulkan enables bias iff either is non-zero (slope = scale factor). Metal depth bias is encoder state; set it when binding the pipeline.

---

## 10. `TransientMemory` (`RP/api/buffers/TransientMemory.java`)

Per-submit scratch allocator owned by the encoder. Obtained via `CommandEncoder.transientMemory()` (no frontend involvement). All methods: render thread only. Vanilla callers: `CubeMapTexture.doLoad` (`uploadStaging`), `SpriteContents.createAnimationState` (`multiUploadStaging`); backends use it internally (Vulkan `writeToBuffer`, `writeToTexture`, `createBuffer(data)`; GL `pushConstants`).

Parameters: `size` (bytes), `alignment` (byte alignment of the returned offset), `usage` (`GpuBuffer.Usage` bits describing intended use; the reference backends ignore it for allocation since their transient buffers carry all usages), `minimumAllocation` and `elementSize` (partial allocation control, see below).

| Method | Returns | Semantics (from `VulkanTransientMemory` / `TransientBlockAllocator`) |
|---|---|---|
| `allocateCpu(size, alignment[, minimumAllocation, elementSize])` | `ByteBuffer` | Plain CPU scratch, valid until the end of the current submit (recycled at `submit()`). Max alignment 16. |
| `allocateStaging(size, alignment, usage[, min, elem])` | `MappedView` | Host-visible GPU-readable memory: write via `data()`, use `slice()` as a copy source (or other usage) in commands of the current submit. `onClose` is a no-op. |
| `allocateGpu(size, alignment, usage[, min, elem])` | `GpuBufferSlice` | Device-local, not CPU-accessible, valid for the current submit. |
| `allocateGpuMapped(size, alignment, usage[, min, elem])` | `MappedView` | CPU-writable view of memory the GPU reads as device-local. Vulkan either writes device-local host-visible memory directly or records a staging -> device copy into a command buffer that executes at the **start** of the submit. Either way, all CPU writes must be done before `submit()`. |
| `uploadStaging(data | List<ByteBuffer>, alignment, usage[, min, elem])` | `GpuBufferSlice` | One allocation; buffers copied back to back, each start offset rounded up to `alignment`. Copies immediately. |
| `uploadGpu(...)` | `GpuBufferSlice` | Same via `allocateGpuMapped`. |
| `multiUploadStaging(List<ByteBuffer>, alignment, usage)` / `multiUploadGpu(...)` | `List<GpuBufferSlice>` | One slice per input, same order as input (packed largest-first into the current block). |

Rules:

- Lifetime: valid for commands recorded **in the current submit**; memory is reused after the submit completes (Vulkan frees staging blocks via the 2-deep destruction queue).
- The returned `GpuBuffer` objects (`slice.buffer()`) are special: `map()`, `slice()` and `slice(o,l)` **throw** `IllegalStateException`; `isClosed()` becomes `true` once the submit index advances; `close()` marks closed. They must still extend `BaseGpuBuffer` and must be accepted anywhere a `GpuBufferSlice` is accepted (copy sources, uniform buffers, vertex/index data).
- **Partial allocation:** if `size` does not fit in the current block but `minimumAllocation` does, the allocator returns the rest of the block rounded down to a multiple of `elementSize` (so `slice.length() < size` is possible). With the default overloads `minimumAllocation == size`, so callers always get `size`. Requests larger than the block size (512 KiB in both backends) get a dedicated block.
- Alignment above the allocator's maximum throws `IllegalArgumentException`.

**Recommendation (Metal):** a per-frame ring of `Shared` `MTLBuffer` blocks; `allocateGpu`/`allocateGpuMapped` can simply return Shared memory on Apple Silicon (unified memory).

---

## 11. Fences, submit indices and frames in flight

`GpuFence` (`RP/api/commands/GpuFence.java`): `NO_TIMEOUT = -1L`, `boolean awaitCompletion(long timeoutNS)`, `close()`.

Reference semantics (GL `GlFence` + `GlCommandEncoder.awaitSubmit`, Vulkan anonymous fence + `awaitSubmitCompletion`):

- `createFence()` captures `currentSubmitIndex`, the index of the submit **being recorded**. The fence completes when that whole submit completes on the GPU (coarse: it also covers commands recorded after `createFence()`).
- `awaitCompletion(t)`:
  - already closed/completed -> `true`;
  - index older than or equal to the last known completed -> `true`;
  - **index == current (not yet submitted)**: `t == 0` -> `false`; otherwise **throws** `IllegalStateException("Cannot wait on a fence for the current submit")`;
  - otherwise wait up to `t` ns (`-1` = forever) and return whether it completed.
- `close()` just marks the fence completed; later `awaitCompletion` returns `true`.
- GL keeps only 2 sync objects (slot `index % 2`), relying on `submit()` having already waited for N-2 (`if (currentSubmitIndex > index + 2) return true`).

Callers and their exact timing:

| Caller | When the fence is created | How it is awaited |
|---|---|---|
| `MappableRingBuffer.rotate()` / `currentBuffer()` (`MC/client/renderer/MappableRingBuffer.java:36-54`) | on `rotate()`; often **after** `submit()` (e.g. `DynamicGpuData.reset()` -> `DynamicGpuDataStorageMapped.endFrame()`), so the fence belongs to the *next* submit; sometimes before submit (`FogRenderer.endFrame`, `CloudRenderer.endFrame`) | `currentBuffer()` calls `awaitCompletion(-1)` on the fence of the slot it is about to reuse (3 slots). Normally that submit has been submitted; if a ring rotated 3+ times within one submit this would hit the "current submit" exception. |
| `StagedVertexBuffer.GpuBufferPool.endFrame()` (`MC/client/renderer/StagedVertexBuffer.java:350-363`) | at end of `GameRenderer.render()` (before `submit()`), so the fence is for the current submit | immediately `tryRecycleBuffers()` -> `awaitCompletion(0)` on it, which **must return `false`, not throw** |
| `RenderSystem.queueFencedTask(Runnable)` (`B3D/systems/RenderSystem.java:323-344`) | only GL's `copyTextureToBuffer` calls it | `executePendingTasks()` each frame: FIFO, `awaitCompletion(0)`, run callback, `fence.close()` |

Cost: several fences per frame. **Recommendation:** implement a fence as a small Java object `{submitIndex}` checked against an atomic "completed submit index" updated from Metal completion handlers (or an `MTLSharedEvent` signaled with the submit index); waiting with a timeout can use `MTLSharedEvent` notifications or `waitUntilCompleted` on the stored command buffer.

---

## 12. Queries and timestamps

`GpuQueryPool` (`RP/api/commands/GpuQueryPool.java`): `size()`, `OptionalLong getValue(int)`, `OptionalLong[] getValues(int index, int count)`, `close()`.

- `getValues` throws `IndexOutOfBoundsException` if `index + count > size` (both backends). Values are raw device ticks; empty if not available yet. **Non-blocking** (Vulkan `VK_QUERY_RESULT_64_BIT | WITH_AVAILABILITY`; GL `GL_QUERY_RESULT_AVAILABLE` check).
- Re-writing an index invalidates its old value: Vulkan host-resets the query (`vkResetQueryPool`) at record time, so `getValue` reports empty until the new value lands. `TimerQuery` relies on not reading stale pairs.
- `writeTimestamp` is recorded in stream order: Vulkan `vkCmdWriteTimestamp2(ALL_COMMANDS)`, GL `glQueryCounter`.

Callers:

- `TimerQuery` (`B3D/systems/TimerQuery.java`): pool of 6 (3 rotations x begin/end). `beginProfile()` writes at frame start (in `renderFrame` after acquire, before any GPU work of the frame); `endProfile()` after the surface blit, before `submit()`; `getStatus()` polls `getValues(rot*2, 2)` on later frames. Delta x `timestampPeriod` = ns. Active only when the GPU-utilization debug entry is shown or metrics are recording.
- `TracyGpuProfiler` (only if Tracy is available): ring of 1024; `pushZone` writes before each backend pass begins, `popZone` after it ends (both on the encoder, outside the backend pass); `endFrame()` (right after `backend.submit()`) reads values in order and stops at the first empty one. No overflow protection.

**Metal pitfall:** Apple-silicon GPUs support counter sampling only at stage boundaries (`MTLCounterSamplingPointAtStageBoundary`), not at arbitrary points or blit boundaries. **Recommendation:** implement `CommandEncoderBackend.writeTimestamp` by attaching the sample to the next (or previous) encoder's `sampleBufferAttachments` start/end index, resolve values after completion, and report empty until resolved. Exact per-call precision is not achievable; that is acceptable for both callers.

---

## 13. `GpuSurface` (present path)

API: `configure(Configuration) throws SurfaceException`, `currentConfiguration()`, `supportedPresentModes()`, `isSuboptimal()`, `isAcquired()`, `acquireNextTexture() throws SurfaceException`, `blitFromTexture(CommandEncoder, GpuTextureView)`, `present()`, `close()`. `Configuration(width, height, presentMode)`; `PresentMode {IMMEDIATE, MAILBOX, FIFO, FIFO_RELAXED}`; `getSupportedVsyncMode(supported, vsync)`: vsync -> `FIFO` only; no vsync -> first of `IMMEDIATE, MAILBOX, FIFO`; throws `IllegalStateException` if none.

Frontend state machine (`RP/frontend/FrontendGpuSurface.java`):

| Method | Frontend checks | Backend call | Frontend state change |
|---|---|---|---|
| `configure(config)` | not acquired (`IllegalStateException`); `supportedPresentModes().contains(mode)` else `SurfaceException` | `configure(config)` | on success `currentConfiguration = config` |
| `acquireNextTexture()` | not acquired; configured | `acquireNextTexture()` | on success `acquired = true`, `blitted = false` |
| `blitFromTexture(enc, view)` | `enc` is `FrontendCommandEncoder` and not in a pass; view texture has color aspect, `COPY_SRC`, 1 layer; acquired; not already blitted this frame | `blitFromTexture(enc.backend(), view)` | `blitted = true` |
| `present()` | acquired and blitted | `present()` | `acquired = false` (only after the backend returns) |
| `close()` | not acquired | `close()` | |

Minecraft's handling (`MC/client/Minecraft.java:1229-1262, 1302-1326`):

- At frame start, if `(windowSurfaceNeedsReconfiguring || (isSuboptimal() && !surfaceIsInvalid)) && !window.isIconified()`: `configure(new Configuration(queryFramebufferSize() /*SDL_GetWindowSizeInPixels, min 1*/, getSupportedVsyncMode(supportedPresentModes(), vsyncOption)))`. Success clears both flags; `SurfaceException` sets `surfaceIsInvalid = true` (needsReconfiguring stays true -> retried every frame).
- If `!surfaceIsInvalid`: `acquireNextTexture()`; `SurfaceException` -> `surfaceIsInvalid = true; windowSurfaceNeedsReconfiguring = true` (logged as a warning, the frame continues without presenting).
- Reconfiguration triggers: initial state, `framebufferSizeChanged()` (window resize / fullscreen), vsync option change (`MC/client/Options.java:516`).
- After `gameRenderer.render()`: if acquired, `blitFromTexture(encoder, gameRenderer.mainRenderTarget().getColorTextureView())`; then `submit()`; then if acquired, `present()`.
- The entire `renderFrame` body is skipped if the surface is still acquired at entry (`if (!this.windowSurface.isAcquired())`). **If backend `present()` or `blitFromTexture()` throws after acquire, the frontend stays "acquired" and rendering stops for good.** Vulkan swallows out-of-date at present time (`eatenException`) and rethrows it as `SurfaceException` from the next `acquireNextTexture()`.
- `isSuboptimal()` must reset to `false` after a successful `configure`.
- The main render target can be smaller than the surface: in borderless fullscreen the window framebuffer width is `pixels - 1` (`B3D/platform/Window.java:393-400`), while the surface is configured with the full pixel size. Both backends copy `min(surface, texture)` per axis, anchored at the top-left of the screen, from the view's `baseMipLevel`.
- Reference present modes: GL `{FIFO, IMMEDIATE}` (swap interval); Vulkan from the surface.
- Surface accessors (`isAcquired`, `isSuboptimal`, `currentConfiguration`) are also read while building crash reports (`MC/client/Minecraft.java:2463-2480`), possibly from a watchdog thread; keep them non-blocking.

**Metal design notes (Recommendation):**
- `configure`: set `CAMetalLayer.drawableSize = (width, height)`, `displaySyncEnabled = (mode == FIFO)`, `maximumDrawableCount` (2 or 3); report `{FIFO, IMMEDIATE}`.
- `acquireNextTexture`: throw `SurfaceException` when `isIconified.getAsBoolean()` (like GL), because `nextDrawable` blocks up to 1 s when no drawable is available. Consider deferring the real `nextDrawable` call to `blitFromTexture` (late acquire) to minimize drawable hold time; then a nil drawable must be handled without throwing (skip the blit, set suboptimal).
- `blitFromTexture`: encode a render pass on the drawable texture into the frame's command buffer that samples the view with a vertical flip (also converts RGBA8 -> the layer's BGRA8 format), and call `[cb presentDrawable:]` on that same command buffer before `submit()` commits it. `present()` then only clears per-frame state. (Alternative: commit a tiny command buffer with `presentDrawable:` in `present()`; ordering on the same queue keeps it after the frame.)

---

## 14. How Minecraft drives a frame

`RenderSystem` (`B3D/systems/RenderSystem.java`) in 26.3 has no `flipFrame`/present logic. Relevant members: `initRenderThread()`/`assertOnRenderThread()`, `initRenderer(GpuDevice)`, `shutdownRenderer()`, `getDevice()`, `queueFencedTask`, `executePendingTasks`, `trackBackendLibraryForShutdown`/`unloadTrackedBackendLibrary`, shared auto index buffers, `bindDefaultUniforms(RenderPass)` (sets `"Projection"`, `"Fog"`, `"Globals"`, `"Lighting"` UBO uniforms if present).

The main loop: `Minecraft.run()` -> per iteration `RenderSystem.pollEvents(...)`, `runTick(advanceGameTime)` -> ticks, then `renderFrame(advanceGameTime)`.

`Minecraft.renderFrame` (`MC/client/Minecraft.java:1229-1350`), in order:

1. Skip everything if the surface is still acquired.
2. `window.updateFullscreenIfChanged()`; optional `configure`; `acquireNextTexture` (section 13).
3. If GPU-utilization profiling: `timerQuery.beginProfile()` -> `writeTimestamp`.
4. CPU work: `gui.update`, `level.update`, `gameRenderer.update`, `pick`, `gameRenderer.extract`.
5. `RenderSystem.executePendingTasks()` (fenced callbacks, GL readbacks).
6. `gameRenderer.render()`: all render passes, clears, copies, uploads. Ends with `fogRenderer.endFrame()` (ring rotate -> `createFence`), GUI render, `renderBuffers.endFrame()` (`StagedVertexBuffer` pools: `createFence` + `awaitCompletion(0)`), `resourcePool.endFrame()` (closes unused cross-frame targets).
7. If acquired: `windowSurface.blitFromTexture(encoder, mainRenderTarget color view)`.
8. `timerQuery.endProfile()` if profiling.
9. Tracy frame capture if enabled: `upload()` (maps a readback buffer), `capture(mainRenderTarget)` (render pass + `copyTextureToBuffer`).
10. **`RenderSystem.getDevice().createCommandEncoder().submit()`** (line 1321), the only `submit()` call site in the client.
11. If acquired: `windowSurface.present()`.
12. `tracyFrameCapture.endFrame()`; `RenderSystem.getDynamicUniforms().reset()` (UBO ring rotation, `createFence` for the **next** submit, closes outgrown ring buffers); `levelRenderer.endFrame()` (`cloudRenderer.endFrame()` -> ring rotate).
13. `FramerateLimiter.limitDisplayFPS(limit)` if `limit < 260` (sleep/spin until the target frame time); FPS bookkeeping.

`renderFrame` is also called outside the main loop: in the constructor (first frame, line 733), during world load (`while (!server.isReady() || overlay != null)` at ~60 Hz), during disconnect while the integrated server shuts down (**tight loop** `while (!server.isShutdown()) { pumpEvents; renderFrame(false); }`, line 2152), and from `setScreenAndShow` (line 2203). So: **exactly one `submit()` per `renderFrame`, and `renderFrame` can be called back-to-back without a frame limiter**; with `IMMEDIATE` present mode only the 2-in-flight wait in `submit()` bounds GPU queue depth.

Work recorded outside `renderFrame` (resource reload "apply" tasks, `runAllTasks()`, key handlers like F2 screenshots, the panorama capture which records six `renderLevel()` + `copyTextureToBuffer` sequences with no submit in between) is folded into the next submit. Large reloads produce very large submits.

---

## 15. Readback callers (copyTextureToBuffer, map(read), fences)

`copyTextureToBuffer` callers:

| Caller | Source / dest | Callback does | Expected timing |
|---|---|---|---|
| `Screenshot.takeScreenshot(target, downscale, consumer)` (`MC/client/Screenshot.java:76-128`): F2 (`Minecraft.java:2278`), auto world-icon screenshot (`GameRenderer.java:579`), panorama (`Minecraft.java:2828`, downscale 4) | main target color (RGBA8) -> new buffer `usage 9`, size `w*h*4`, 5-arg overload, mip 0 | `buffer.map(true,false)`, reads rows **bottom-up** (`height - y - 1`), builds `NativeImage`, invokes consumer (which hands off to `Util.ioPool()`), `buffer.close()` | any later render-thread point after completion; one call per copy |
| `TextureUtil.writeAsPNG(dir, prefix, texture, maxMip, op)` (`B3D/platform/TextureUtil.java:54-120`), from `TextureAtlas` debug dump | each mip of an RGBA8 texture -> one buffer `usage 9` at increasing offsets | each callback increments a counter; the last one maps the buffer, writes PNGs, closes buffer | callbacks may run in any order but each exactly once |
| `TracyFrameCapture.capture` (`B3D/TracyFrameCapture.java:73-94`) | 320x180-ish RGBA8 texture -> persistent buffer `usage 9` | sets `status = WAITING_FOR_UPLOAD`; the next frame's `upload()` maps the buffer with `map(true, false)` | any later frame |
| `GlCommandEncoder` internally | | | |

`map(read = true)` callers: only the three callbacks/consumers above (`Screenshot`, `TextureUtil`, `TracyFrameCapture.upload`).

`createFence` callers: `RenderSystem.queueFencedTask` (GL only), `MappableRingBuffer.rotate`, `StagedVertexBuffer.GpuBufferPool.endFrame` (section 11).

`map(write = true)` callers (for completeness): `StagedVertexBuffer.uploadDrawsToBuffers`, `PostPass`, `CloudRenderer`, `Lightmap`, `FogRenderer`, `DynamicGpuDataStorageMapped` (several), `BitmapProvider`, `StagingBuffer.PersistentlyMapped`. All write either into a freshly created buffer or into a fence-protected ring slot, then reference it in commands of the current submit.

Ordering requirement: the copy must capture the texture contents at its position in the command stream (panorama: six captures of the same target in one submit, each after its own `renderLevel()`).

---

## 16. Threading summary

| Call | Thread |
|---|---|
| `GpuBackend.*`, device creation, `createSurface`, surface methods | render (main) thread |
| All `GpuDevice` resource creation, `CommandEncoder`, `RenderPass`, `TransientMemory`, `GpuBuffer.map`, fences, query reads, `close()` of anything | render thread (the GL backend requires it; many callers `RenderSystem.assertOnRenderThread()`) |
| `GpuDeviceBackend.compilePipeline(CreateInfo)` | **background** worker threads (`resourceLoad` pool or `Util.backgroundExecutor()`), concurrently |
| `SpvModule.spv()/reflect()` | background (inside the frontend compile, and in the backend compile) |
| `SpvModule.close()` | background executor, after `finishCompile()` |
| `GpuDeviceBackend.getDeviceInfo()` | any thread (background compile reads it) |
| `BackendRenderPipeline.Pending.finishCompile()` | render thread (`ShaderManager` reload executor = `Minecraft`; `PipelineCache.get` caller) |
| `BackendRenderPipeline.close()/isClosed()` | render thread |
| `GpuSurface.isAcquired/isSuboptimal/currentConfiguration` | render thread, plus crash-report construction (could be another thread) |
| readback callbacks, fenced tasks | must run on render thread |
| Metal `addCompletedHandler` blocks | Metal-owned threads: only update atomics/queues there |

No evidence was found of any other off-thread renderpearl call in `net/minecraft` or `com/mojang/blaze3d`.

---

## 17. Pitfalls checklist for the Metal backend

1. Every `GpuBuffer` (including transient ones) must extend `BaseGpuBuffer`; textures/views should extend `BaseGpuTexture`/`BaseGpuTextureView`.
2. `getDeviceInfo`, `createTimestampQueryPool`, `getTimestampCalibrationOffset` are called before `createCommandEncoder` (Tracy case).
3. Device is created before the window exists; window must be created with `SDL_WINDOW_METAL`.
4. Flip clip-space Y (shader or viewport) so clip y = -1 lands in memory row 0; pass scissor/render-area/clear/copy coordinates straight through; flip once more when blitting to the drawable. Winding: Clockwise front with the flip (derived, verify). Report `isZZeroToOne = true`.
5. `writeToBuffer`, `writeToTexture`, `copy*`, `clear*` are stream-ordered and must copy caller data before returning. Never `replaceRegion`/`memcpy` into resources that may be referenced by pending commands.
6. `map()` is immediate and unsynchronized; must allow concurrent maps and GPU use while mapped (`persistentMapping = true`); `close()` while mapped throws. Use `Shared` storage for mappable buffers (never `Managed`).
7. Defer all destruction until submits that may reference the object complete; textures stay alive while views exist.
8. `awaitCompletion(0)` on a current-submit fence returns `false`; non-zero timeout on it throws (match references, or flush, but never deadlock). `-1` waits forever. Fences must be cheap.
9. Readback callbacks: exactly once, on the render thread, after completion; never from completion handlers.
10. `submit()` is the frame boundary even when nothing is presented; bound frames in flight there; drain autorelease pools there and in each background compile.
11. `present()` and `blitFromTexture()` must not throw on recoverable surface problems (the frontend would stay "acquired" forever); report via `SurfaceException` from the next `acquireNextTexture()` or via `isSuboptimal()`. Throw `SurfaceException` from `acquireNextTexture()` when iconified.
12. Minecraft calls `present()` after `submit()`; schedule `presentDrawable:` on the frame command buffer during `blitFromTexture`, or commit a separate command buffer in `present()`.
13. Surface and main target sizes can differ (borderless fullscreen 1 px); blit `min()` region anchored top-left; drawable format differs from RGBA8, so blit with a render pass (blit encoders need matching formats and cannot flip).
14. Load-action clears are full-attachment; render-area-limited clears (mods) and `clearColorAndDepthTextures(..., region, mip)` (vanilla GUI item atlas) need a quad-based clear.
15. Metal PSOs must match pass attachment formats exactly; the same `CompiledRenderPipeline` is used with and without depth attachments and with `null` color slots. Build variants per attachment signature.
16. No triangle fans in Metal (vanilla sky uses them, non-indexed). No native multi-draw-indirect: loop per 20/16-byte command.
17. `setUniform(index, value)` values: `GpuBufferSlice` (UBO and texel buffer), `TextureViewAndSampler`, or `null`; uniforms are re-sent after every `setPipeline`; vertex/index bindings persist across `setPipeline`.
18. `drawMultipleIndexed` produces per-draw UBO rebinds; make rebinding cheap (offset-only updates).
19. `minUniformOffsetAlignment` is enforced by the frontend on every UBO slice; pick a value that satisfies Metal's constant-buffer offset alignment on the running GPU.
20. `compilePipeline` runs concurrently on background threads; return `Pending.NULL` instead of throwing; finish all use of SPIR-V before `finishCompile()` returns; keep `finishCompile()` cheap.
21. Timestamps on Apple GPUs are only available at encoder stage boundaries; `getValue` must stay non-blocking and report empty until resolved; invalidate an index when it is re-written; bounds-check `index == size`.
22. `createTexture`/`createBuffer` should throw `GpuOutOfMemoryException` when Metal returns nil (`MainTarget` retries smaller sizes).
23. `IndexType.SHORT` with odd `firstIndex` yields 2-byte-aligned index buffer offsets; check Metal's index-offset alignment rule.
24. `POINTS` pipelines need the MSL point-size builtin.
25. Strict validation is off in production; consider enabling `SharedConstants.IS_RUNNING_IN_IDE` in dev builds.

---

## 18. Open questions (not resolvable from the decompiled sources)

- Exact Metal alignment requirements for `indexBufferOffset`, constant-buffer offsets on Apple vs Mac2 GPU families, and `copyFromBuffer`/`copyFromTexture` `bytesPerRow`/offset rules for 1- and 2-byte formats. Check the Metal feature-set tables and API validation.
- Whether `MTLWindingClockwise` is right after the Y flip (derived from Vulkan parity; confirm by rendering terrain with culling on).
- The best mapping of `getTimestampCalibrationOffset` and `timestampPeriod` to Metal's GPU timestamp domain.
- Whether `isExplicitDepthRequired` (forces `gl_FragDepth` writes in OIT accumulate, which disables early-Z) is still needed on native Metal or was only a GL/MoltenVK workaround. The references key it purely on "device name starts with Apple".
