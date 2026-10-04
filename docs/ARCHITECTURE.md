# Ingot architecture

Ingot is a Fabric mod for Minecraft Java 26.3 that adds a native **Apple Metal** backend to Minecraft's
`renderpearl` GPU abstraction. Vanilla 26.3 ships two backends (`backend/opengl`, `backend/vulkan`) behind the
interfaces in `com.mojang.renderpearl.backend.api`. Ingot adds a third (`dev.ingot.backend.Metal*`) and puts it first
in the list of backends the game tries on macOS. Everything above the backend interfaces (the renderpearl frontend,
`RenderSystem`, the level/GUI renderers, GLSL→SPIR-V compilation) is vanilla code and is reused unchanged.

```
 Minecraft renderers ──► renderpearl frontend (validation, GLSL→SPIR-V via shaderc)
                                 │  GpuDeviceBackend / CommandEncoderBackend / RenderPassBackend / GpuSurfaceBackend
                                 ▼
                Ingot Java backend (dev.ingot.backend)          SPIR-V → MSL (dev.ingot.shader, LWJGL spvc)
                state tracking, lifetimes, transient memory
                                 │  Java FFM downcalls (long handles, generated bindings in dev.ingot.nativ.Mtl)
                                 ▼
                libingot.dylib (native/src/*.m, Objective-C + ARC, ABI in native/include/ingot.h)
                thin wrappers over Metal / QuartzCore / AppKit, plus a few fused helpers
                                 ▼
                         Metal  ·  CAMetalLayer
```

**Reference notes** (read the relevant one before changing a contract): `docs/research/frontend-contracts.md`,
`vulkan-backend-reference.md`, `pipelines-and-shaders.md`, `integration-points.md`, `hot-paths-and-features.md`,
`shader-translation-experiment.md`. The working SPIR-V→MSL prototype is `tools/shader-xlate/src/MslTranslator.java`.

## Goals

1. Render vanilla 26.3 pixel-equivalent to the OpenGL backend on Apple Silicon Macs.
2. Lowest possible CPU and GPU cost per frame, measured as FPS **and** energy (battery/heat matter as much as FPS).
3. Fail safe: any failure while creating the Metal device throws `BackendCreationException`, so the game falls
   back to the next vanilla backend exactly as it does today.
4. Run Sodium 0.9.x (26.3) on top of Ingot: Sodium picks its `OPENGL` draw path for any non-Vulkan device, which
   only needs `RenderPass.multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, int)` (feature
   `multiDrawDirectSeparate`), implemented natively.

## Repository layout

```
build.gradle                 Loom + `compileNative` (xcrun clang → universal libingot.dylib at natives/macos/)
native/include/ingot.h       THE C ABI between Java and native. Every exported function is declared here.
native/src/*.m               Objective-C implementation (ARC). Universal arm64 + x86_64, macOS 12+.
native/test/                 Standalone native smoke test (no Minecraft): device, buffers, pipelines, render, readback.
tools/gen_bindings.py        ingot.h → src/main/java/dev/ingot/nativ/Mtl.java (run after every header change)
src/main/java/dev/ingot/
  Ingot, IngotConfig         constants/logger, config/ingot.properties (+ -Dingot.<key> overrides)
  nativ/                     NativeLibrary (extract + load), Mtl (GENERATED FFM bindings)
  backend/                   MetalBackend, MetalDevice, MetalCaps, MetalCommandEncoder, MetalTransientMemory,
                             MetalRenderPass, MetalSurface, MetalBuffer, MetalTexture, MetalTextureView, MetalSampler,
                             MetalFence, MetalQueryPool, MetalRenderPipeline, MetalPipelineCompiler, MetalFormats
  shader/                    SpirvToMsl (spvc cross-compilation + binding remap)
  energy/                    PowerPolicy (battery / low power / thermal, occlusion, frame-limiter)
  mixin/                     PreferredGraphicsApiMixin, FramerateLimiterMixin, FramerateLimitTrackerMixin, ...
src/bench/                   separate benchmark mod (ingot-bench); bench/ has the runner, see bench/README.md
src/gametest/                Fabric client game tests
```

## Integration

* `PreferredGraphicsApiMixin` prepends `new MetalBackend()` to `getBackendsToTry()` when
  `MetalBackend.shouldTry()`: macOS, `IngotConfig.enabled`, and the in-game graphics API preference is `DEFAULT`.
  Vanilla's own crash fallback (`lastStartWasClean`) switches the preference to OpenGL after a crash during startup,
  which also disables Metal; the Video Settings caption for DEFAULT reads "Default (Metal)" so users can see it.
* `-Dingot.enabled=false` makes the mod completely inert (used by the benchmark baselines).
* When Metal is first in line, `VulkanBackend.checkBackendAvailable()` (which boots MoltenVK just to probe) is skipped.
* Only `BackendCreationException` triggers fallback; `MetalBackend.loadLibrary/createDevice` wrap every failure.

## Native bridge conventions

* **Handles.** Every Metal object crossing the boundary is a retained Objective-C pointer typed `void *` in C and
  `long` in Java. Java owns exactly one reference and gives it back with `ingot_release(handle)` (deferred until
  the GPU is done, see Lifetimes). `0` means null.
* **Calling convention.** `Mtl` binds every function with FFM. Pointers are `long`s (no `MemorySegment`
  allocation on hot paths). `INGOT_HOT` functions use `Linker.Option.critical(false)`: no blocking, no
  autoreleased objects, no upcalls.
* **Structs** (`IngotRenderPassDesc`, `IngotPipelineDesc`) are written by Java into reusable off-heap scratch
  segments at the documented byte offsets; the header has the offsets in comments and native code
  `_Static_assert`s them.
* **Autorelease pools.** Every non-hot entry point that can create autoreleased objects wraps itself in
  `@autoreleasepool`.
* **Errors.** Creation functions return `0` and set `ingot_last_error()` (thread-local).
* **Threading.** Device-level creation (buffers, textures, samplers, libraries, pipelines) is thread-safe and is
  called from pipeline-compile workers. Command buffers/encoders/surface: render thread (= main thread on macOS).
* **No upcalls.** GPU completion is published via an atomic completed serial (`ingot_device_completed_serial`) and
  a condition variable (`ingot_device_wait_serial`).

## Coordinate conventions (verified on the M4 Pro by the shader-translation probes)

Vanilla renders in OpenGL memory order: clip-space y = −1 lands in texel row 0.

| Concern | Metal setting |
|---|---|
| Clip-space Y | SPIRV-Cross `FLIP_VERTEX_Y` in every vertex shader. Viewport = full attachment, positive height. |
| Winding | Front faces are **clockwise**: `setFrontFacingWinding:MTLWindingClockwise`, cull back when `cull`. |
| Depth | `isZZeroToOne = true`. Vanilla uses reverse-Z (`>=`, clear 0) with `D32_FLOAT`. |
| Scissor / render area / copy and clear regions | Passed through unchanged (memory-row coordinates). |
| Present | `ingot_surface_blit` draws the main target into the BGRA8 drawable with a vertical flip. |
| `[[vertex_id]]` / `[[instance_id]]` | Include `vertexStart`/`baseVertex` and `baseInstance` (needed by sprite animation and terrain MDI). |

## Resource binding model (from the validated prototype)

Per stage, both stages use the same table:

| Resource | Metal slot |
|---|---|
| `UNIFORM_BUFFER` uniforms, in `CreateInfo.uniforms()` order | `[[buffer(0..13)]]` packed: k-th UBO → `buffer(k)` |
| `COMBINED_IMAGE_SAMPLER` / `TEXEL_BUFFER`, in order | `[[texture(j)]]` packed; samplers `[[sampler(m)]]` packed (< 16) |
| push constants (≤ 128 B, padded to 128 for `setBytes`) | `[[buffer(14)]]` |
| vertex buffer slot *s* | `[[buffer(30 - s)]]` via `MTLVertexDescriptor.layouts[30 - s]` |

`CreateInfo.uniforms()` index *i* is the binding the frontend rewrote into the SPIR-V and the index passed to
`setUniform(i, value)`. `MetalRenderPipeline` maps *i* → (Metal slot, stage mask from
`spvc_compiler_msl_is_resource_used`). The render pass binds a uniform only to stages that use it, lazily at draw
time, and only when changed (offset-only changes use `setBufferOffset`).

Uniform buffers created through `createBuffer` with `USAGE_UNIFORM` are rounded up to 16 bytes, because MSL struct
sizes exceed what the game allocates for several blocks (Fog 40→48, CloudInfo 44→48, LightmapInfo 92→96).

## Device info reported (`MetalDevice.getDeviceInfo`, cached single instance)

* features: all `true` — `wireframeFillMode`, `shaderDrawParameters`, `multiDrawDirectInterleaved`,
  `multiDrawDirectSeparate` (native loops), `multiDrawIndirect`, `drawIndirect`, `nonZeroFirstInstance`,
  `persistentMapping`.
* limits: `maxAnisotropy 16`, `minUniformOffsetAlignment 16` (256 when not Apple-family), `maxTextureSize 16384`,
  `maxMemoryAllocationSize = maxBufferLength`, `maxMultiDrawDirectInterleavedDrawCount = 1 << 20`,
  `maxColorAttachments 8`, `maxDrawIndirectDrawCount = Integer.MAX_VALUE`.
* hints: `writeToBufferIsSlow = true` (selects persistently-mapped staging for chunk uploads, ideal for unified
  memory), `anisotropyHasKnownIssues = false`, `isExplicitDepthRequired = true` (same as both vanilla backends on
  Apple GPUs; revisit later with `preserveInvariance`), `multiDrawIndirectHasKnownIssues = false`.
* `isZZeroToOne = true`, `timestampPeriod = 1.0` (timestamps are ns), `backendName = "Metal"`.

## Storage modes

* **Buffers**: `Shared` on unified-memory devices (all Apple Silicon): persistently mapped; `map()` is pointer
  arithmetic on a cached `contents` address (no downcall). Write-only upload memory (transient staging blocks)
  uses `WriteCombined`. Not unified (Intel Macs): CPU-visible buffers `Managed` (+ `didModifyRange` on unmap /
  before use, `synchronizeResource` for readbacks), GPU-only `Private`.
* **Textures**: `Private` (lossless compression on Apple GPUs). Uploads and readbacks go through blits.

## Submission model, serials and lifetimes

* One `MTLCommandQueue`. Native **serials** number command buffers (1, 2, 3, ...). The encoder records into the
  current command buffer (`currentSerial`), created lazily, with unretained references.
* `submit()` (once per frame): end open encoders → commit (serial *s*) → wait (kernel block, no spinning) until the
  previous frame's last serial completed — i.e. **2 frames in flight**, matching both vanilla backends (the game's
  ring buffers assume this) → on the render thread: release deferred handles, run readback callbacks and recycle
  transient blocks whose serial completed.
* **Fences** capture `currentSerial`. `awaitCompletion`: closed/completed → `true`; serial still being recorded:
  `timeout == 0` → `false`, otherwise **flush** (commit the partial command buffer and continue recording into a
  new one) and wait — this replaces the reference backends' throw, which the chunk staging ring can otherwise hit.
* **Lifetimes.** `close()` on buffers, textures, views, samplers, pipelines, query pools, texel-buffer views and
  drawables queues the native handle with `currentSerial` (the GPU may still reference it); it is released once
  `completedSerial >= tag`. A texture is released only after all its views are closed.
* **Readback callbacks** (`copyTextureToBuffer`) run once on the render thread inside the first `submit()` after
  their serial completes.

## Command encoding

* **Blit encoder** is opened lazily for copies/uploads and stays open across consecutive copy commands; it is ended
  when a render pass starts, at present, or at submit.
* **writeToBuffer / writeToTexture** always go through transient staging + blit (ordered in the command stream;
  never `memcpy` into a live buffer, never `replaceRegion`).
* **Clears**: whole-texture clears that are immediately followed by a render pass on the same texture are folded into
  that pass's `MTLLoadActionClear` (no extra tile load/store); otherwise a pass with a clear load action. Region
  clears use `ingot_util_clear_region` (scissored quad).
* **Render passes** map 1:1 to render encoders; viewport = full attachment; initial scissor = render area;
  state (pipeline, depth-stencil, raster, depth bias, buffers, textures, samplers) is cached in Java and only sent
  when changed. Topology: `QUADS`/`LINES`/`TRIANGLES` → triangle; `TRIANGLE_FAN` → converted to triangle lists
  (static fan index buffer for non-indexed draws; CPU expansion into transient memory for indexed draws).
* **Multi-draw** (interleaved, separate, indirect) loops natively inside a single downcall.

## Pipelines

`MetalDevice.compilePipeline(createInfo)` runs on worker threads: SPIR-V → MSL per stage (`SpirvToMsl`, before
`finishCompile` returns, because the frontend frees the modules), `MTLLibrary` per stage cached by MSL hash
(identical sources across pipelines are common), and the default `MTLRenderPipelineState` is created eagerly on the
worker. Pipeline-state **variants** are keyed by (depth pixel format, stencil pixel format, color attachment mask)
because Metal bakes attachment formats into the PSO; variants other than the default are created on first use.
Encoder-level state (cull, winding, fill mode, depth bias, depth-stencil state) is applied per `setPipeline`.
Failures log and return `Pending.NULL` (exceptions would fail the resource reload).

## Surface and present

* `createSurface`: set `SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE=0`, `SDL_Metal_CreateView` → `SDL_Metal_GetLayer`
  → `ingot_surface_create`. Present modes `{FIFO, IMMEDIATE}` ↔ `displaySyncEnabled`.
* `acquireNextTexture`: throws `SurfaceException` while iconified (like GL); otherwise just marks the frame.
* `blitFromTexture`: **late drawable acquire** (`nextDrawable` here, after all CPU work), then `ingot_surface_blit`
  encodes the flip-blit and `presentDrawable:` on the frame's command buffer (Minecraft calls `present()` after
  `submit()`, but Metal needs `presentDrawable:` before commit). A nil drawable skips presentation for the frame and
  marks the surface suboptimal; it never throws (a throw here would freeze the frontend).
* `present()`: hands the drawable handle to deferred release.

## Energy efficiency (battery and heat are first-class goals)

Target machine: MacBook Pro 14" (Mac16,8), M4 Pro 20-core GPU, 3024x1964 ProMotion (120 Hz).

1. **No busy waiting anywhere in Ingot.** GPU waits block in the kernel (condition variable).
2. **Low-power frame limiter.** Vanilla `FramerateLimiter.limitDisplayFPS` parks, then *spins* for the last
   0.5 ms + average overshoot of every frame. Ingot replaces it with an absolute-deadline `mach_wait_until`
   (`ingot_wait_until`), no spinning. Active whenever the mod is enabled (it is backend-independent).
3. **Don't render frames nobody sees.** When the window is fully occluded (`ingot_surface_visible() == 0`),
   `FramerateLimitTracker` reports `WINDOW_ICONIFIED` (10 FPS). Vanilla only does this when minimised.
4. **Battery saver** (`batterySaver=auto|always|never`, default auto): on battery or in Low Power Mode, an
   "Unlimited" frame-rate limit is capped to the display's max refresh rate (frames beyond it are never shown).
5. **Late drawable acquire**, `displaySyncEnabled` for vsync (ProMotion aware), `maximumDrawableCount = 3`.
6. **GPU bandwidth is energy.** Private render targets (lossless compression), clears folded into load actions,
   no redundant passes.
7. **Fewer, cheaper CPU→GPU transitions**: persistent mapping, redundant-state filtering in Java, native multi-draw.

The benchmark measures FPS and energy (process CPU energy via `proc_pid_rusage` `ri_energy_nj`, GPU energy via
IOReport "Energy Model"/"GPU Energy"; both without root) uncapped and at a fixed 60 FPS.

## Java contracts between files

The skeleton classes in `src/main/java/dev/ingot/**` declare every member other files rely on (marked
`// CONTRACT`). Implementers may add private members freely but must not change contract signatures without
updating every user. Ownership:

| Area | Files |
|---|---|
| Native | `native/**` |
| Core | `MetalDevice`, `MetalCaps`, `MetalCommandEncoder`, `MetalTransientMemory`, `MetalBuffer`, `MetalTexture`, `MetalTextureView`, `MetalSampler`, `MetalFence`, `MetalQueryPool` |
| Passes, surface, integration, energy | `MetalBackend`, `MetalRenderPass`, `MetalSurface`, `energy/**`, `mixin/**`, `IngotConfig` |
| Pipelines | `MetalRenderPipeline`, `MetalPipelineCompiler`, `shader/**` |
| Shared tables | `MetalFormats` (append-only) |
