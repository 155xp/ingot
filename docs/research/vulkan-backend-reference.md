# Vulkan backend reference (for the Ingot Metal backend)

Status: research notes, written from the Vineflower-decompiled Minecraft Java 26.3 client.
Audience: engineers implementing `com.mojang.renderpearl.backend.api` on Metal without re-reading the sources.

All paths below are relative to
`scratchpad/mc/src-rp/com/mojang/renderpearl/` (renderpearl) or `scratchpad/mc/src-full/` (whole client).
Decompiled Java uses raw integer constants. Every constant quoted here was decoded by hand, and the SDL, VMA, spvc and
VkFormat values were checked with `javap -constants` against the LWJGL 3.4.3 jars in `~/.gradle`.
Statements marked **[speculation]** or **[recommendation]** are not read from the sources. Everything else is.

---

## 0. One-page summary of the Vulkan execution model

1. **One encoder, one queue, one timeline.** `VulkanDevice` owns exactly one `VulkanCommandEncoder`. `createCommandEncoder()` always returns it. All work goes to the graphics queue. Compute and transfer queues are created but never used for submission.
2. **2 submits in flight.** `MAX_SUBMITS_IN_FLIGHT = 2`. `submit()` submits frame N, then blocks (5 s timeout) until frame N-1 has completed on the GPU (timeline semaphore).
3. **Everything is serialized with full barriers.** After every copy, clear and render pass, the encoder records a global `ALL_COMMANDS -> ALL_COMMANDS, MEMORY_READ|MEMORY_WRITE` memory barrier. There is no fine-grained hazard tracking.
4. **All images stay in `VK_IMAGE_LAYOUT_GENERAL` forever.** They are transitioned once at creation, and swapchain images are the only exception. No per-use layout transitions.
5. **Dynamic rendering, no VkRenderPass objects.** Every color attachment uses `storeOp = STORE`. `loadOp` is `CLEAR` when a clear value is given, otherwise `LOAD`. Unused (null) color slots use `DONT_CARE`/`DONT_CARE`.
6. **Descriptors are push descriptors.** One descriptor set (set 0), and binding `i` = uniform index `i` of the pipeline. The whole set is re-pushed lazily at the next draw after any `setUniform`/`setPipeline`.
7. **Push constants** cover one range `[0, pushConstantsSize)` visible to all stages.
8. **Every pipeline is compiled twice** (when it has no depth state): once against a `D32_SFLOAT` depth attachment and once against no depth attachment. Pipelines with a depth state only get the D32 variant.
9. **Viewport is never flipped.** It is `(0,0,w,h)` with depth `0..1`. `frontFace = CLOCKWISE`, `cullMode = BACK` when `cull`. Render-target memory therefore has NDC y=-1 at row 0, which is the same memory layout GL produces. The swapchain blit flips vertically.
10. **Uploads are staged.** `writeToBuffer`/`writeToTexture` memcpy into per-submit transient staging memory and record a GPU copy *in command order*. They never write the destination directly.
11. **Deferred destruction.** `close()` on buffers, textures, views, samplers, pipelines and query pools pushes them into a 2-slot `DestructionQueue`. The actual destroy happens in the `submit()` that ends the *next* frame, after the GPU has finished the frame in which `close()` was called.
12. **`copyTextureToBuffer` callbacks** ride the same destruction queue. They run on the render thread inside a later `submit()` call, after the GPU finished the submit that contained the copy.
13. **Transient memory** is per-submit bump allocation in 512 KiB blocks. CPU blocks are recycled at the next `submit()`. GPU and staging blocks are recycled through the destruction queue.
14. **Swapchain:** `R8G8B8A8_UNORM` or `B8G8R8A8_UNORM`, `SRGB_NONLINEAR`, so the image is not sRGB-encoded. At least 3 images, `TRANSFER_DST` usage only, and the present path is `vkCmdBlitImage` with a vertical flip and `NEAREST` filtering.
15. **`isIconified` is ignored** by the Vulkan surface. GL throws `SurfaceException` from `acquireNextTexture()` when iconified.

---

## 1. File map

| File | Role |
|---|---|
| `backend/vulkan/VulkanBackend.java` | `GpuBackend` entry point: loader, window flags, instance, physical device choice, `VkDevice` and VMA creation |
| `backend/vulkan/VulkanFeatureSets.java`, `init/FeatureSet.java`, `init/VulkanFeature.java`, `init/VulkanPNextStruct.java` | Declarative feature and extension requirements and pNext-chain plumbing |
| `backend/vulkan/VulkanInstance.java` | `VkInstance` (API 1.2), SDL surface extensions, portability enumeration, debug utils |
| `backend/vulkan/VulkanPhysicalDevice.java` | Properties, queue family selection, vendor and driver strings |
| `backend/vulkan/VulkanDevice.java` | `GpuDeviceBackend` implementation and `DeviceInfo` construction |
| `backend/vulkan/VulkanCommandEncoder.java` | `CommandEncoderBackend`: submission, pacing, copies, clears, fences, timestamps, destroy queue |
| `backend/vulkan/VulkanRenderPass.java` | `RenderPassBackend`: draw recording and push descriptors |
| `backend/vulkan/VulkanRenderPipeline.java` | `BackendRenderPipeline`: `CreateInfo` to `VkPipeline` x2 |
| `backend/vulkan/VulkanTransientMemory.java` | `TransientMemory` implementation |
| `backend/util/TransientBlockAllocator.java` | Generic bump/block allocator shared with GL |
| `backend/vulkan/VulkanGpuBuffer.java` | `GpuBuffer` (`Direct` subclass) and map semantics |
| `backend/vulkan/VulkanGpuTexture.java`, `VulkanGpuTextureView.java`, `VulkanGpuSampler.java`, `VulkanQueryPool.java` | Resources |
| `backend/vulkan/VulkanGpuSurface.java` | `GpuSurfaceBackend`: swapchain, acquire, blit, present |
| `backend/vulkan/VulkanConst.java` | Enum to Vk mappings |
| `backend/vulkan/VulkanQueue.java` | `vkQueueSubmit2` builder (`Submission`) |
| `backend/vulkan/VulkanCommandPool.java` | Two command pools (one per in-flight slot), buffers allocated 32 at a time |
| `backend/vulkan/DestructionQueue.java`, `Destroyable.java` | Deferred destruction ring |
| `backend/vulkan/VulkanDebug.java`, `checkpoints/*` | Debug labels and object names; NV/AMD GPU-crash checkpoints |
| `backend/vulkan/VulkanUtils.java` | Result handling, known-bad device list, `putArgb` |
| `backend/common/BaseGpuBuffer.java`, `BaseGpuTexture.java`, `BaseGpuTextureView.java` | Trivial shared base classes (shown in section 13) |

---

## 2. `VulkanBackend` (`implements GpuBackend`)

`GpuBackend` (`api/device/GpuBackend.java`) is:
```java
String getName();
void loadLibrary() throws BackendCreationException;
void unloadLibrary();
long createWindow(@Nullable String title, int width, int height, long flags);
GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException;
```

### 2.1 How Minecraft drives a backend (`net/minecraft/client/Minecraft.java` ~465-545)
- `PreferredGraphicsApi.getBackendsToTry()` returns `{gl, vulkan}`, or `{vulkan, gl}` if the user picked Vulkan. A Metal backend is not in this list; Ingot must inject it (mixin) **[recommendation]**.
- When the preference is `DEFAULT`, `VulkanBackend.checkBackendAvailable()` is called only to record a `backendCreationException` for UI messaging.
- For each backend: `loadLibrary()`, then `createDevice(new GpuDebugOptions(glDebugVerbosity, DEBUG_SYNCHRONOUS_GL_LOGS, renderDebugLabels, vulkanValidation))`. On `BackendCreationException` the backend is `unloadLibrary()`'d and the next one is tried.
- **The device is created before the main window.** Then `new Window(..., backend, maxSize)` calls `backend.createWindow(title, w, h, 8224L)`. `8224 = 0x2020 = SDL_WINDOW_HIGH_PIXEL_DENSITY | SDL_WINDOW_RESIZABLE`. After that, `device.createSurface(window.handle(), window::isIconified)`.
- `maxSize = deviceInfo.limits().maxTextureSizeForFormat(RGBA8_UNORM)`, which is `highestOneBit(min(maxTextureSize, sqrt(maxMemoryAllocationSize / 4)))`.

### 2.2 Methods
| Method | Behavior |
|---|---|
| `getName()` | `"Vulkan"` |
| `loadLibrary()` | Idempotent and caches the failure. Requires `NativeLibrariesBootstrap.isVulkanLoaderAvailable()` (LWJGL `VK.create()` succeeded at bootstrap), else `Reason.VULKAN_LOADER_MISSING`. Then `SDL_Vulkan_LoadLibrary(<LWJGL VK lib path>)` (failure gives `PLATFORM_ERROR` with `SDL_GetError()`). Then checks that LWJGL's `vkGetInstanceProcAddr` equals SDL's (mismatch unloads and throws `PLATFORM_ERROR`). |
| `unloadLibrary()` | `SDL_Vulkan_UnloadLibrary()` if loaded. |
| `checkBackendAvailable()` (static) | Builds a probe backend and calls `loadLibrary()`. Then `new VulkanInstance(0, false, false)` and `findPhysicalDevice(required, requiredIfExtAvailable)`. Returns `null` on success or the `BackendCreationException`. Always unloads. |
| `createWindow(title,w,h,flags)` | `SDL_CreateWindow(title, w, h, 0x10000000L /*SDL_WINDOW_VULKAN*/ | flags)` |
| `createDevice(debugOptions)` | See 2.3. Returns `new FrontendGpuDevice(new VulkanDevice(...))`. |

For Metal **[recommendation]**: `createWindow` should OR `SDL_WINDOW_METAL = 0x20000000L`. Then `SDLMetal.SDL_Metal_CreateView(window)` and `SDL_Metal_GetLayer(view)` give the `CAMetalLayer*` (both bindings exist in lwjgl-sdl 3.4.3). `loadLibrary` only needs to `dlopen` the Ingot dylib. Note that `shaderc`/`spvc`/`vma` are only *preloaded* by `NativeLibrariesBootstrap` when the Vulkan loader loaded. Otherwise LWJGL loads them lazily on first use **[speculation: standard LWJGL behavior, not verified at runtime]**.

### 2.3 `createDevice` sequence
1. `renderdocAttached = "1".equals(getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"))`.
2. `new VulkanInstance(logLevel, useLabels || renderdocAttached, useValidationLayers)`. Details:
   - API `VK_API_VERSION_1_2`, app name `"Minecraft Java Edition"`, engine `"MinecraftJE"`.
   - Instance extensions come from `SDL_Vulkan_GetInstanceExtensions()`, minus `VK_KHR_portability_enumeration`. That one is re-added (with `flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR`) only on macOS when available.
   - `VK_EXT_debug_utils` is added when `verbosity > 0 || wantsDebugLabels`. Validation layer `VK_LAYER_KHRONOS_validation` is used if requested and present, which also forces labels.
3. `findPhysicalDevice`: enumerate devices and keep the first suitable one. A later suitable *discrete* (`deviceType == 2`) device replaces a non-discrete one, then the loop breaks. If none are suitable, the first failure reason is thrown.
4. `checkDeviceSuitability` (per device):
   - `apiVersion >= 1.1` else `VULKAN_DEVICE_VERSION_TOO_LOW`.
   - `(driverID, vendorID, deviceID)` not in `VulkanUtils.KNOWN_PROBLEMATIC_DEVICES` else `VULKAN_KNOWN_PROBLEMATIC`. The list is 58 entries, all `driverID 14 = VK_DRIVER_ID_MOLTENVK` and `vendorID 0x8086` (Intel). In other words, specific old Intel iGPUs on MoltenVK are blacklisted.
   - All features of required sets, then all extensions, must be supported (`VULKAN_MISSING_FEATURE` / `VULKAN_MISSING_EXTENSION`). The sets are "required" plus "required-if-extensions-available" when the device exposes all of that set's extensions.
   - A queue family with `GRAPHICS|COMPUTE` and SDL presentation support must exist, else `VULKAN_NO_GRAPHICS_QUEUE` ("COMBINED_GRAPHICS_COMPUTE_PRESENT_QUEUE").
   - `apiVersion >= 1.2` else `VULKAN_DEVICE_VERSION_TOO_LOW`.
5. Enabled feature sets = required, plus required-if-ext-available that are supported, plus optional sets that are supported and pass their `condition`.
6. Checkpoint extension: AMD buffer marker preferred, else NV diagnostic checkpoints, else no-op.
7. `vkCreateDevice` with all queue families from `queueFamilyCreateInfoMap` (priorities are all `0.0f` because of `callocFloat`), the enabled extensions and the feature pNext chain.
8. VMA allocator with `vulkanApiVersion = 1.2`.
9. On any `BackendCreationException`, everything created so far is destroyed in reverse order and the exception is rethrown.

### 2.4 Feature requirements (`VulkanFeatureSets`)
| Set | Kind | Extensions | Features |
|---|---|---|---|
| `REQUIRED_FEATURESET` "Minecraft base required" | required | `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain` | `multiDrawIndirect`, `drawIndirectFirstInstance`, `samplerAnisotropy` (1.0); `shaderDrawParameters` (1.1); `timelineSemaphore`, `hostQueryReset` (1.2); `synchronization2`; `dynamicRendering`; `vertexAttributeInstanceRateDivisor` |
| `PORTABILITY_SUBSET_FEATURESET` | required if ext available (that is, MoltenVK) | `VK_KHR_portability_subset` | `triangleFans` |
| `MULTI_DRAW_FEATURESET` | optional | `VK_EXT_multi_draw` | `multiDraw` |
| `AMD_BUFFER_MARKER_FEATURESET` | optional | `VK_AMD_buffer_marker` | none |
| `NV_DIAGNOSTIC_CHECKPOINT_FEATURESET` | optional, condition: AMD marker unsupported | `VK_NV_device_diagnostic_checkpoints` | none |
| `WIREFRAME_FEATURESET` | optional | none | `fillModeNonSolid` |
| `CALIBRATED_TIMESTAMP_FEATURESET` | optional | `VK_EXT_calibrated_timestamps` | none |

Implications for Metal (what the frontend relies on):
- Base instance and base vertex in draws (indirect too).
- Per-instance step rate (divisor > 1 possible).
- Anisotropy.
- `gl_InstanceIndex`/`gl_VertexIndex` semantics including base (`shaderDrawParameters`).
- Triangle fans (only the debug pipelines use them).
- Timeline-style completion tracking.
- Host-side query reset.

### 2.5 Queue family selection (`VulkanPhysicalDevice`)
- Graphics family: the first family with `GRAPHICS|COMPUTE` (`queueFlags & 3 == 3`) and `SDL_Vulkan_GetPresentationSupport`.
- Compute and transfer families: the "most specialized" family with spare queues (fewest flag bits). They fall back to graphics or compute queues when absent. **Never used for submission** (grep: only `graphicsQueue()` is used by the encoder, surface and command pools). Metal only needs one `MTLCommandQueue`.

---

## 3. `VulkanDevice` (`implements GpuDeviceBackend`)

Constructor: `(VulkanInstance, VulkanPhysicalDevice, FeatureSet enabledFeatureSet, VkDevice, long vma, CheckpointExtension)`.
It builds `DeviceInfo`, creates queues and sets `isIntegratedIntelMoltenVK`. That flag is `deviceType == INTEGRATED(1) && vendorID == 0x8086 && driverID == 14 (MoltenVK)`. The constructor then closes the physical-device struct holder and creates the single `VulkanCommandEncoder`.

### 3.1 `DeviceInfo` reported (and why)

`DeviceInfo(name, vendorName, driverInfo, isZZeroToOne, backendName, timestampPeriod, limits, features, underlyingExtensions, hintsAndWorkarounds, type)`. The Vulkan, GL and suggested Metal values are:

| Field | Vulkan value | GL value (`GlHeuristics.createDeviceInfo`) | Metal **[recommendation]** |
|---|---|---|---|
| `name` | `VkPhysicalDeviceProperties.deviceName` | `GL_RENDERER` | `MTLDevice.name`. It must start with `"Apple"` on Apple Silicon so `Util.isAppleSiliconMac` works. |
| `vendorName` | vendorID map (`0x106B` gives `"APPLE"`, `0x8086` gives `"INTEL"`, `0x1002` gives `"AMD"`, ...) | `GL_VENDOR` | `"APPLE"` (or `"INTEL"`/`"AMD"` on Intel Macs) |
| `driverInfo` | `"<api major.minor.patch> <driverName> <driverInfo>"` | `GL_VERSION` | e.g. `"Metal 3 / macOS x.y"` |
| `isZZeroToOne` | `true` | `GL_ARB_clip_control` present (then `glClipControl(LOWER_LEFT, ZERO_TO_ONE)`) | `true` |
| `backendName` | `"Vulkan"` | `"OpenGL"` | `"Metal"` |
| `timestampPeriod` | `limits.timestampPeriod` (ns per tick) | `1.0f` | `1.0f` if timestamps are delivered in ns |
| `limits.maxAnisotropy` | `(int) maxSamplerAnisotropy` | `GL_MAX_TEXTURE_MAX_ANISOTROPY` or 1 | 16 |
| `limits.minUniformOffsetAlignment` | `(int) minUniformBufferOffsetAlignment` | `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT` | 256 is safe on all Macs. Apple GPUs allow smaller **[speculation: verify against the Metal feature-set tables]**. |
| `limits.maxTextureSize` | `maxImageDimension2D` | probed via `GL_PROXY_TEXTURE_2D` | 16384 |
| `limits.maxMemoryAllocationSize` | `Vulkan11Properties.maxMemoryAllocationSize` (negative gives `Long.MAX_VALUE`) | `Long.MAX_VALUE` | `MTLDevice.maxBufferLength` |
| `limits.maxMultiDrawDirectInterleavedDrawCount` | `MultiDrawPropertiesEXT.maxMultiDrawCount` (negative gives MAX). **It is 0 when `VK_EXT_multi_draw` is absent**, because the struct is not chained and stays zeroed. | `0` | `0` if not emulated |
| `limits.maxColorAttachments` | `limits.maxColorAttachments` | `GL_MAX_DRAW_BUFFERS` | 8 |
| `limits.maxDrawIndirectDrawCount` | `maxDrawIndirectCount` (unsigned, clamped to `Integer.MAX_VALUE`) | MAX if MDI, 1 if draw_indirect, else 0 | `Integer.MAX_VALUE` if MDI is emulated by a loop |
| `features.wireframeFillMode` | `WIREFRAME_FEATURESET` enabled | `true` | `true` (`setTriangleFillMode:.lines`) |
| `features.shaderDrawParameters` | `true` | `GL_ARB_shader_draw_parameters` | `true` (see section 14, item 13) |
| `features.multiDrawDirectInterleaved` | `MULTI_DRAW_FEATURESET` enabled | `false` | `false` (no vanilla caller found) |
| `features.multiDrawDirectSeparate` | `false` | `true` | `false` (no vanilla caller found) |
| `features.multiDrawIndirect` | `true` | `GL_ARB_multi_draw_indirect` | `true` (loop over draws) |
| `features.drawIndirect` | `true` | `GL_ARB_draw_indirect` | `true` |
| `features.nonZeroFirstInstance` | `true` | `GL_ARB_base_instance` | `true` |
| `features.persistentMapping` | `true` | `GL_ARB_buffer_storage` (forced off on NVIDIA and Intel Gen7) | `true` |
| `underlyingExtensions` | instance exts suffixed `" (I)"` plus device exts suffixed `" (D)"` | enabled GL exts | anything |
| `hints.writeToBufferIsSlow` | `false` | `isGlOnDx12` | `false` |
| `hints.anisotropyHasKnownIssues` | `false` | `isAmd` | `false` |
| `hints.isExplicitDepthRequired` | `Util.isAppleSiliconMac(deviceName)`, i.e. `deviceName.startsWith("Apple")` | same test on `GL_RENDERER` | **mirror: true on Apple GPUs** |
| `hints.multiDrawIndirectHasKnownIssues` | `false` | Intel vendor and not Arc | `false` on Apple. Consider `true` on Intel Macs **[speculation]**. |
| `type` | `VkPhysicalDeviceType` map | guessed | `INTEGRATED` on Apple Silicon |

Where these values are consumed in the client:
- `isZZeroToOne` reaches `Projection.java`, `Camera.java`, and the `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` shader define (`frontend/shaders/GlslCompiler.java`).
- `isExplicitDepthRequired` becomes the `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` define. OIT shaders then write `gl_FragDepth = gl_FragCoord.z` (`assets/minecraft/shaders/include/oit_sample.glsl`, `oit_add_transmittance.glsl`).
- `shaderDrawParameters` becomes the `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` define.
- `LevelRenderer` uses MDI for terrain only if `maxDrawIndirectDrawCount > 0 && nonZeroFirstInstance && !multiDrawIndirectHasKnownIssues`.
- `StagingBuffer.create` uses a persistently-mapped ring only when `writeToBufferIsSlow && persistentMapping`. Otherwise every chunk upload is `CommandEncoder.writeToBuffer`, so `writeToBuffer` is a hot path.
- `minUniformOffsetAlignment` is enforced by `FrontendRenderPass.setUniform(String, GpuBufferSlice)` (throws if `offset % alignment != 0`) and used to pad UBO blocks (`DynamicGpuDataStorage*`, `TextureAtlas`, `Lighting`).

### 3.2 Every `GpuDeviceBackend` method

| Method | Vulkan behavior |
|---|---|
| `createSurface(windowHandle, isIconified)` | `new VulkanGpuSurface(this, windowHandle)`. **`isIconified` is dropped.** |
| `createCommandEncoder()` | Returns the single `VulkanCommandEncoder` (also used internally for `queueForDestroy`). |
| `createSampler(u, v, min, mag, maxAniso, maxLod)` | `new VulkanGpuSampler(...)` (section 5.4). The frontend already checked `1 <= maxAniso <= limits.maxAnisotropy`. |
| `createTexture(label, usage, format, w, h, depthOrLayers, mips)` | `new VulkanGpuTexture(...)`. The label is kept only if debugging is enabled, else `""`. |
| `createTextureView(texture, baseMip, mipCount)` | `new VulkanGpuTextureView(...)` |
| `createBuffer(label, usage, size)` | `new VulkanGpuBuffer.Direct(this, label, usage, size, isIntegratedIntelMoltenVK)` |
| `createBuffer(label, usage, ByteBuffer data)` | Creates with `usage \| USAGE_COPY_DST(8)`, so `usage()` later reports the extra bit. Then `transientMemory().uploadStaging(data, 1, USAGE_COPY_SRC=16)` and `vkCmdCopyBuffer` into **`objectInitCommandBuffer()`** (section 6.2). The data reaches the buffer on the GPU timeline. It is *not* CPU-visible through `map()` until that submit executes. |
| `getLastDebugMessages()` | `List.of()` |
| `isDebuggingEnabled()` | `instance.debug().enabled()`: debug utils enabled (labels requested, log verbosity > 0, or validation) |
| `compilePipeline(createInfo)` | Compiles synchronously via `VulkanRenderPipeline.compile` and returns `() -> pipeline`. **Called on a worker thread** (see 3.3). Failures throw `IllegalStateException` (`crashIfFailure`). It never returns null. |
| `close()` | `checkpointExtension.close()`, `commandEncoder.destroy()` (flush, `vkQueueWaitIdle`, drain destroy queue), `vmaDestroyAllocator`, `vkDestroyDevice`, `instance.close()` |
| `createTimestampQueryPool(size)` | `new VulkanQueryPool(this, size)` |
| `getTimestampCalibrationOffset()` | Returns `hostNanoTime - deviceTimeInNs`. With `VK_EXT_calibrated_timestamps` it uses `vkGetCalibratedTimestampsEXT` with time domains `DEVICE(0)` and `CLOCK_MONOTONIC(1)`. Otherwise it uses `commandEncoder.getTimestampNow()` (a synchronous one-off submit with a `WAIT`ed query) and `System.nanoTime()`. Device ticks are multiplied by `timestampPeriod`. |
| `getDeviceInfo()` | Precomputed `DeviceInfo` |

### 3.3 Threading contract (from `frontend/shaders/PipelineBuilder.compilePipeline`)
- `backend.compilePipeline(createInfo)` runs inside `CompletableFuture.supplyAsync(..., executor)`, so on **worker threads, possibly concurrently**.
- The returned `Pending.finishCompile()` is invoked later by whoever completes the `CompiledRenderPipeline.Pending` **[speculation: the render thread]**.
- The SPIR-V modules (`shader.module()`) are closed on the executor *after* `finishCompile()` returns. Do not keep references to `spv()` buffers past `finishCompile`.
- Everything else (encoder, resource creation, `close()`, destruction queue) is single-threaded render-thread code with no locking. Metal: `newLibraryWithSource`/`newRenderPipelineState` are thread-safe, so do the SPIRV-Cross translation and PSO creation in `compilePipeline()`. `finishCompile()` can just return.

---

## 4. Memory types and buffers

### 4.1 Buffer usage mapping (`VulkanConst.bufferUsageToVk`)
| `GpuBuffer.USAGE_*` | value | Vk buffer usage |
|---|---|---|
| `MAP_READ` | 1 | (memory property only) |
| `MAP_WRITE` | 2 | (memory property only) |
| `HINT_CLIENT_STORAGE` | 4 | (memory property only) |
| `COPY_DST` | 8 | `TRANSFER_DST` (2) |
| `COPY_SRC` | 16 | `TRANSFER_SRC` (1) |
| `VERTEX` | 32 | `VERTEX_BUFFER` (128) |
| `INDEX` | 64 | `INDEX_BUFFER` (64) |
| `UNIFORM` | 128 | `UNIFORM_BUFFER` (16) |
| `UNIFORM_TEXEL_BUFFER` | 256 | `UNIFORM_TEXEL_BUFFER` (4) |
| `INDIRECT_PARAMETERS` | 512 | `INDIRECT_BUFFER` (256) |

### 4.2 `VulkanGpuBuffer.Direct` allocation (VMA)
```java
alloc.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;                 // 8
if (forceHostVisibleAllocation)      // isIntegratedIntelMoltenVK
    alloc.requiredFlags |= HOST_VISIBLE;                            // 2
if (usage & (MAP_READ|MAP_WRITE)) {                                 // & 3
    alloc.requiredFlags |= HOST_VISIBLE | HOST_COHERENT;            // 2|4
    if (usage & MAP_READ) { alloc.preferredFlags |= HOST_CACHED;    // 8
                            alloc.flags |= HOST_ACCESS_RANDOM; }    // 2048
    else                    alloc.flags |= HOST_ACCESS_SEQUENTIAL_WRITE; // 1024
}
if (usage & (MAP_READ|HINT_CLIENT_STORAGE))                         // & 5
    alloc.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;                // 9
```
`sharingMode = EXCLUSIVE`. A debug name is set if a label Supplier is given.

Summary:

| Buffer usage | Memory |
|---|---|
| No map bits | device-local (host-visible on Intel+MoltenVK) |
| `MAP_WRITE` | host-visible coherent, prefers device-local, sequential-write ("BAR"/UMA) |
| `MAP_READ` | host-visible coherent, prefers host and cached, random access |
| `HINT_CLIENT_STORAGE` | prefers host memory |

On Apple Silicon (UMA) these all collapse to shared memory **[speculation about MoltenVK's memory types]**.

Client usage patterns:
- `MappableRingBuffer` uses `UNIFORM|MAP_WRITE` (130) and `UNIFORM_TEXEL_BUFFER|MAP_WRITE` (258). It is a 3-deep ring with a `GpuFence` per slot. `currentBuffer()` waits with `awaitCompletion(-1)`.
- `DynamicGpuDataStorageMapped` uses `usage | MAP_WRITE`.

### 4.3 `map(offset, length, read, write)` semantics
- Throws if closed, if neither read nor write, if `read` without `MAP_READ` or `write` without `MAP_WRITE`, if out of range, or if `length > 2^31-1`.
- `mappingRefCount++` and `vmaMapMemory(allocation)`. VMA refcounts block mappings, so this is effectively persistent. Returns `MemoryUtil.memByteBuffer(ptr + offset, length)`.
- `MappedView.onClose` (idempotent) does `mappingRefCount--` and `vmaUnmapMemory`.
- **No synchronization, no flush/invalidate** (memory is HOST_COHERENT). The CPU sees whatever is in memory right now. Callers are responsible for waiting on `GpuFence`s before reading GPU-written data or overwriting data the GPU may still read.
- Using a buffer on the GPU *while mapped* is allowed (that is what `persistentMapping = true` advertises; `BaseGpuBuffer.checkCanBeUsed()` is a no-op here). GL non-persistent buffers throw in `checkCanBeUsed()`.
- `close()` sets `closed = true`, **throws if `mappingRefCount != 0`**, then `queueForDestroy(this)`. `destroy()` calls `vmaDestroyBuffer`.

Metal mapping **[recommendation]**: use `MTLStorageModeShared` for every mappable buffer and return `contents() + offset`. Keep the same "no implicit sync" contract. `persistentMapping = true` is natural.

---

## 5. Textures, views, samplers, queries

### 5.1 `VulkanGpuTexture`
- `VkImageCreateInfo`:
  - `imageType = 2D`, `extent = (w,h,1)`, `mipLevels`.
  - **`arrayLayers = depthOrLayers`** (there are no 3D images).
  - `format = VulkanConst.toVk(format)`, `tiling = OPTIMAL`, `initialLayout = UNDEFINED`, `samples = 1`, `EXCLUSIVE`.
  - `usage = textureUsageToVk(usage, format)`.
  - `flags = CUBE_COMPATIBLE(16)` iff `usage & USAGE_CUBEMAP_COMPATIBLE(16)`.
- VMA `AUTO_PREFER_DEVICE`.
- Immediately records into `objectInitCommandBuffer()` a `vkCmdPipelineBarrier` with src `TOP_OF_PIPE`, dst `BOTTOM_OF_PIPE`, image barrier `UNDEFINED -> GENERAL`, dstAccess `MEMORY_READ|MEMORY_WRITE`, all mips and layers. The aspect is `COLOR` if the format has color, else `DEPTH` only.
- Texture usage to image usage (`VulkanConst.textureUsageToVk`):

| `GpuTexture.USAGE_*` | value | Vk image usage |
|---|---|---|
| `COPY_DST` | 1 | `TRANSFER_DST` (2) |
| `COPY_SRC` | 2 | `TRANSFER_SRC` (1) |
| `TEXTURE_BINDING` | 4 | `SAMPLED` (4) |
| `RENDER_ATTACHMENT` | 8 | `COLOR_ATTACHMENT` (16) if color format, `DEPTH_STENCIL_ATTACHMENT` (32) if depth format |
| `CUBEMAP_COMPATIBLE` | 16 | image flag `CUBE_COMPATIBLE` |

- **Lifetime is refcounted by views.** The constructor calls `addViews()`, so the texture itself counts as one reference. `close()` calls `removeViews()` once. When `closed && views == 0`, it is `queueForDestroy(this)`. Each `VulkanGpuTextureView` calls `texture.addViews()` at creation and `removeViews()` on close. A texture closed while views are alive is destroyed only after the last view closes, and then still deferred.
- Frontend restrictions (`FrontendGpuDevice.verifyTextureCreationArgs`):
  - `mipLevels <= log2(max(w,h)) + 1`.
  - Cubemaps must be square with exactly 6 layers. "Array textures are not yet supported".
  - Non-cubemaps must have `depthOrLayers == 1`.
  - **So in practice textures are either 2D (1 layer) or Cube (6 layers).**

### 5.2 `VulkanGpuTextureView`
- `viewType = CUBE(3)` if the texture has `USAGE_CUBEMAP_COMPATIBLE`, else `2D(1)`.
- Same format as the texture (no reinterpretation).
- Aspect `COLOR` or `DEPTH`.
- Mips `[baseMipLevel, baseMipLevel+mipLevels)`, layers `[0, isCube ? 6 : 1)`.
- `close()` queues the view for destroy and calls `texture.removeViews()`.
- `getWidth(m)` = `texture.getWidth(m + baseMip)`, i.e. `width >> (m+baseMip)` (`BaseGpuTextureView`).

Metal **[recommendation]**:
- Texture: `MTLTextureType2D` or `TypeCube`. `usage = ShaderRead | (RENDER_ATTACHMENT ? RenderTarget : 0)`. `storageMode = Private`.
- Views: `newTextureViewWithPixelFormat:textureType:levels:slices:`.
- `MTLTexture` lifetime is retained by in-flight command buffers. The deferred-destroy machinery is still needed for transient memory and callbacks (section 6.13).

### 5.3 Clears and copies touch layer 0 only
Except for `writeToTexture`/`copyBufferToTexture` (which take a layer) and views of cubemaps, every command uses `baseArrayLayer = 0, layerCount = 1`. See section 6.

### 5.4 `VulkanGpuSampler`
```java
magFilter  = toVk(magFilter);            // NEAREST=0, LINEAR=1
minFilter  = toVk(minFilter);
mipmapMode = maxLod.orElse(1000.0) > 0.25 ? LINEAR(1) : NEAREST(0);
addressModeU/V = toVk(...);              // REPEAT=0, CLAMP_TO_EDGE=2 ; W left 0 = REPEAT
mipLodBias = 0;  minLod = 0 (calloc);
maxLod     = max(0.25f, (float) maxLod.orElse(1000.0));
anisotropyEnable = maxAnisotropy > 1;  maxAnisotropy = maxAnisotropy;
compareEnable = false; borderColor = 0; unnormalizedCoordinates = false;
```
The `maxLod = 0.25` + `NEAREST` mip mode is the classic "no mipmapping but still choose min vs mag filter by LOD" trick.

Compare GL (`GlSampler`):
- min `NEAREST` maps to `GL_NEAREST_MIPMAP_LINEAR`, and `LINEAR` to `GL_LINEAR_MIPMAP_LINEAR`, so the mip filter is always linear.
- `GL_TEXTURE_MAX_LOD` is set only if `maxLod` is present.
- GL additionally clamps base/max level per *view* at bind time.

Metal **[recommendation]**: mirror Vulkan. Use `mipFilter = maxLod>0.25 ? .linear : .nearest`, `lodMaxClamp = max(0.25, maxLod)`, `maxAnisotropy`, and `rAddressMode = .repeat`.

### 5.5 `VulkanQueryPool` (timestamps)
- `queryType = TIMESTAMP(2)`, `queryCount = size`, and `vkResetQueryPool(0..size)` (host reset) at creation.
- `getValues(index, count)` calls `vkGetQueryPoolResults(..., stride 16, flags = 64_BIT|WITH_AVAILABILITY (5))`. **It does not wait.** Each value is `OptionalLong.empty()` when unavailable. It throws `IndexOutOfBoundsException` if `index+count > size`.
- `close()` defers destruction (it has no closed flag, so double close double-queues).
- Writing happens via the encoder or render pass (section 6.12).
- Consumers:
  - `TimerQuery` uses 6 queries (3 rotations x begin/end). Delta is multiplied by `timestampPeriod`, and status is polled non-blockingly.
  - `TracyGpuProfiler` converts with `ts * period + getTimestampCalibrationOffset()`.

---

## 6. `VulkanCommandEncoder` (`implements CommandEncoderBackend, Destroyable`)

### 6.1 State
```java
MAX_SUBMITS_IN_FLIGHT = 2
long submitSemaphore;            // TIMELINE semaphore, initialValue = currentSubmitIndex-1 = 1
long currentSubmitIndex = 2;     // index of the submit being recorded
long completedSubmitIndex = 0;   // cache of last observed completion
DestructionQueue<Destroyable> destroyQueue = new DestructionQueue<>(2, Destroyable::destroy);
VulkanCommandPool[2] commandPools;           // pool = commandPools[currentSubmitIndex % 2]
VkCommandBuffer currentInitCommandBuffer;    // "objectInit": lazily begun
VkCommandBuffer currentCommandBuffer;        // main: lazily begun
VulkanRenderPass currentRenderPass;
VulkanQueue.Submission submissionBuilder;    // ordered list of stages: waits -> cmdbufs -> signals
VulkanTransientMemory transientMemory;
```
Command buffers are begun with `ONE_TIME_SUBMIT`. Pools are created with `TRANSIENT` and `reset()` with `vkResetCommandPool(…, 0)` (buffers are kept and re-used).

### 6.2 What goes into one submission, in order
`VulkanQueue.Submission` accumulates *stages*. A new stage starts when a wait is added after cmdbufs or signals, or when a cmdbuf is added after signals. `close()` issues **one** `vkQueueSubmit2KHR` with one `VkSubmitInfo2` per stage and no fence.

Within a frame N:
1. **Transient-memory CB** (`VulkanTransientMemory.beginSubmit()` → `encoder.execute(cb)`). It is always the first CB of the submission. It receives whole-block CPU→GPU copies for "GPU mapped" transient memory on non-UMA devices (section 8) and is ended in `endSubmit()` with a full barrier (if anything was recorded).
2. **Object-init CB** (`objectInitCommandBuffer()`). It receives initial-data buffer copies (`createBuffer(data)`) and image `UNDEFINED -> GENERAL` transitions. It is ended (after appending a full memory barrier) and added to the submission *before* the main CB whenever `endCommandBuffer()` runs. Init work therefore executes before all main-CB work of the same segment, even work recorded earlier in time. This is fine because the resources are new.
3. **Main CB** (`commandBuffer()`): every copy, clear and render pass in API call order.
4. If the surface blits: `waitSemaphore(acquire, 0, ALL_COMMANDS)`. This calls `endCommandBuffer()` and starts a new stage. Then the blit CB is added, then `signalSemaphore(presentSemaphore[img], 0, TRANSFER)`.
5. Any commands after the blit go into a new main CB in a later stage.
6. `submit()` adds the final `signal(submitSemaphore, N, ALL_COMMANDS)`.

`endCommandBuffer()`, `waitSemaphore()`, `signalSemaphore()` and `execute()` throw `IllegalStateException` if called inside a render pass. `commandBuffer()` throws "Cannot start command buffer while inside RenderPass" if no CB is open while a pass is active (cannot happen normally).

### 6.3 `submit()` and frame pacing (exact sequence)
```java
public void submit() {
    endCommandBuffer();                        // flush init + main CBs into submission
    transientMemory.endSubmit();               // rotate transient allocators, end transient CB, submitIndex++
    signalSemaphore(submitSemaphore, currentSubmitIndex, ALL_COMMANDS);
    submissionBuilder.close();                 // vkQueueSubmit2KHR
    submissionBuilder = graphicsQueue.beginSubmit();
    currentSubmitIndex++;                      // now recording N+1
    if (!awaitSubmitCompletion(currentSubmitIndex - 2, 5_000_000_000L))   // wait for N-1
        throw new IllegalStateException("5s timeout reached when waiting for VK semaphore: " + checkpoints);
    currentCommandPool().reset();              // pool[(N+1)%2] == pool[(N-1)%2], safe now
    destroyQueue.rotate();                     // destroys everything queued during N-1
    checkpointStorage.rotate();
    transientMemory.beginSubmit();             // new transient CB, first in submission N+1
}
```
- `awaitSubmitCompletion(idx, timeoutNs)`:
  - Returns `true` if `completedSubmitIndex >= idx`.
  - If `idx == currentSubmitIndex` (not yet submitted), returns `false` when `timeout == 0`, otherwise **throws** `"Cannot wait on a fence for the current submit"`.
  - Otherwise it calls `vkWaitSemaphores(submitSemaphore >= idx, timeoutNs)`. Errors go through `crashIfFailure`; `VK_ERROR_DEVICE_LOST` becomes `GpuDeviceLossException`. `VK_SUCCESS` updates `completedSubmitIndex`, and `VK_TIMEOUT` returns `false`.
- Timeline: first submit signals 2 and waits for 1. The initial value is 1, so it returns immediately.
- **Effective pacing:** the CPU may record frame N+1 while the GPU runs frame N, but never N-1. Combined with swapchain acquire (at least 3 images, FIFO blocks), there is at most ~1 frame of CPU run-ahead.
- Minecraft's frame order (`Minecraft.renderFrame`):
  1. Optional `configure`.
  2. `acquireNextTexture()` (if the surface is valid).
  3. `RenderSystem.executePendingTasks()`.
  4. `gameRenderer.render()`.
  5. `blitFromTexture(encoder, mainTarget color view)` if acquired.
  6. Optional Tracy capture.
  7. **`encoder.submit()`**.
  8. **`surface.present()`** if acquired.
  9. `DynamicUniforms.reset()`, `levelRenderer.endFrame()`, frame limiter.

### 6.4 Barrier model
```java
public static void memoryBarrier(VkCommandBuffer cb, MemoryStack stack) {
    VkMemoryBarrier2: srcStage = dstStage = ALL_COMMANDS (0x10000),
                      srcAccess = dstAccess = MEMORY_READ|MEMORY_WRITE (0x18000);
    vkCmdPipelineBarrier2KHR(cb, depInfo{ pMemoryBarriers });
}
```
It is recorded after:
- every `clear*`
- `writeToBuffer`, `copyToBuffer`
- `writeToTexture`, `copyBufferToTexture`
- `copyTextureToBuffer`, `copyTextureToTexture`
- `submitRenderPass()` (render pass end)
- the end of the init CB
- the end of the transient CB

**No barriers between draws inside a pass.** The contract is that every command outside a render pass is fully ordered and visible to everything after it. Metal's default hazard tracking (`MTLHazardTrackingModeTracked`, one queue) gives equivalent cross-encoder ordering **[recommendation: keep resources tracked; if you use heaps or untracked resources you must add `MTLFence`s to reproduce this]**.

### 6.5 Render passes
`createRenderPass(RenderPassDescriptor d)`. The frontend has already validated:
- not already in a pass
- `colorCount <= maxColorAttachments` and at least 1 attachment
- `renderArea` inside the attachment
- all attachments the same size, `RENDER_ATTACHMENT` usage, `depthOrLayers == 1`, not closed

Steps:
1. `beginDebugGroup(cb, label)`, then a `BEGIN_RENDER_PASS` checkpoint.
2. `width/height` are taken from the color attachment views (`getWidth(0)` of the *view*, i.e. the base mip size; the last non-null wins) or from the depth view if there is no color.
3. `VkRenderingInfo`:
   - `renderArea = d.renderArea()`, `layerCount = 1`, `viewMask = 0`.
   - One `VkRenderingAttachmentInfo` per color slot (including null slots). For a non-null slot: `imageView`, `imageLayout = GENERAL`, `storeOp = STORE`, and `loadOp = CLEAR` with `clearValue.color = (x,y,z,w)` of the `Vector4fc` (RGBA order, `VulkanUtils.putArgb` despite the name), otherwise `LOAD`. A null slot gets `imageView = 0`, `layout = UNDEFINED`, `storeOp = DONT_CARE`, `loadOp = DONT_CARE`.
   - Depth (optional): `GENERAL`, `STORE`, `CLEAR` with `(float) clearDepth` or `LOAD`. **No stencil attachment ever.**
4. `vkCmdBeginRenderingKHR`. Then `new VulkanRenderPass(...)`, whose constructor sets:
   - `vkCmdSetViewport(0, 0, outputWidth, outputHeight, minDepth 0, maxDepth 1)`. **Never changed for the life of the pass. There is no viewport API in `RenderPassBackend`.**
   - `vkCmdSetScissor(renderArea)`.

`submitRenderPass()`: `vkCmdEndRenderingKHR`, `endDebugGroup`, an `END_RENDER_PASS` checkpoint, `currentRenderPass = null`, and a **full memory barrier**.

Metal **[recommendation]**:
- One `MTLRenderCommandEncoder` per pass. `loadAction = .clear/.load` and `storeAction = .store`. For null slots, leave `colorAttachments[i].texture = nil`. Set the viewport and the scissor = render area at encoder start.
- **Pitfall:** Vulkan's `CLEAR` honors `renderArea`, while Metal's `.clear` clears the entire attachment. Vanilla always uses the default full-size render area (`RenderPassDescriptor.Builder.defaultRenderArea`, and no caller in `net/**` sets one). A partial area combined with a clear must be emulated with a scissored clear draw.

### 6.6 Clears (outside render passes)
| Method | Vulkan implementation |
|---|---|
| `clearColorTexture(tex, rgba)` | `vkCmdClearColorImage(image, GENERAL, color, range{aspect COLOR, mips 0..all, layer 0, count 1})`, then barrier |
| `clearColorAndDepthTextures(color, rgba, depth, d)` | the color clear above, plus `vkCmdClearDepthStencilImage(depth, GENERAL, {depth=(float)d}, {aspect DEPTH, all mips, layer 0})`, then barrier |
| `clearDepthTexture(depth, d)` | depth clear only, then barrier |
| `clearColorAndDepthTextures(color, rgba, depth, d, x, y, w, h, mip)` | Creates temp views `createTextureView(tex, mip, 1)` for both. Opens a render pass `"ClearColorDepthTextures"` (no clear values, so `LOAD`; render area = full mip size). Records `vkCmdClearAttachments` with color attachment 0 and depth, rect `(x,y,w,h)`, layer 0. Then `submitRenderPass()` (barrier) and closes the views (deferred destroy). |

The frontend checks the following for these calls:
- color texture: color format, `RENDER_ATTACHMENT|COPY_DST`, 1 layer
- depth texture: depth format, `RENDER_ATTACHMENT|COPY_DST`, 1 layer
- the same mip count for both textures
- the region is in-bounds

Metal **[recommendation]**:
- Whole-texture clears: a render pass per mip with `loadAction = .clear` and no draws.
- Region clear: Metal has no `vkCmdClearAttachments`. Use a pass with `.load`, `setScissorRect(region)`, and a fullscreen-triangle "clear" pipeline. It needs `depthCompare = .always`, `depthWrite = true`, and an output color, with depth written via `[[depth(any)]]` or via a constant-z vertex.

### 6.7 `writeToBuffer(dst, ByteBuffer data)` and `copyToBuffer(src, dst)`
```java
// writeToBuffer
GpuBufferSlice staging = transientMemory.uploadStaging(data, /*alignment*/1L, /*usage COPY_SRC*/16);
vkCmdCopyBuffer(commandBuffer(), staging.vkBuffer, dst.vkBuffer,
                {srcOffset=staging.offset, dstOffset=dst.offset, size=data.remaining()});
memoryBarrier();
```
- The data is snapshotted at call time into transient staging memory. The copy executes **in command order**: commands recorded earlier in the same submit (or still in flight from the previous submit) see the old contents, and later commands see the new contents.
- The copy size is `data.remaining()` (it may be less than `dst.length()`; the frontend rejects larger).
- The frontend checks: not in a pass, buffer not closed, `COPY_DST`, size bounds.
- `copyToBuffer`: `vkCmdCopyBuffer(size = source.length())` plus barrier. The frontend checks `COPY_SRC`/`COPY_DST`, equal lengths and bounds.

GL contrast: `glNamedBufferSubData` (or equivalent DSA) immediately, which is also ordered in the GL command stream.

**Metal pitfall:** `writeToBuffer` must NOT be implemented as a direct `memcpy` into a shared `MTLBuffer` unless no prior command (in this or an in-flight command buffer) can still read the old contents. Mirror Vulkan instead: stage in transient memory, then `MTLBlitCommandEncoder.copyFromBuffer`. Batch consecutive copies into one blit encoder and close it only when a render pass, or something else that needs a different encoder, starts. This preserves order without one encoder per call.

### 6.8 `writeToTexture` and `copyBufferToTexture`
```java
// writeToTexture(dst, src, mip, depthOrLayer, x, y, w, h)
GpuBufferSlice staging = transientMemory.uploadStaging(src, 1L, 16);   // whole src.remaining()
VkBufferImageCopy{ bufferOffset = staging.offset, bufferRowLength = w, bufferImageHeight = h,
                   imageSubresource = {COLOR, mip, baseArrayLayer = depthOrLayer, layerCount 1},
                   imageOffset = (x,y,0), imageExtent = (w,h,1) };
vkCmdCopyBufferToImage(cb, staging, image, GENERAL, region); memoryBarrier();

// copyBufferToTexture(src, srcX, srcY, srcW, srcH, dst, dstX, dstY, copyW, copyH, mip, layer)
skipBytes = (srcX + (long)srcY * srcW) * dst.getFormat().blockSize();
VkBufferImageCopy{ bufferOffset = src.offset + skipBytes, bufferRowLength = srcW, bufferImageHeight = srcH,
                   imageSubresource = {COLOR, mip, layer, 1}, imageOffset = (dstX,dstY,0),
                   imageExtent = (copyW,copyH,1) };
```
- Rows are tightly packed (`rowLength = width` in texels). Aspect is always `COLOR`, so depth textures cannot be uploaded this way.
- Frontend checks:
  - `writeToTexture`: `w*h*blockSize <= remaining`, rect in mip bounds, not closed, `COPY_DST`, `depthOrLayer < depthOrLayers`.
  - The `NativeImage` overload additionally requires `componentType == UNORM_8` and a matching component count.
  - `copyBufferToTexture`: `srcW*copyH*blockSize <= src.length()` and `COPY_SRC` on the buffer.
- **Alignment quirk:** staging uses alignment 1, so `bufferOffset` may not be a multiple of the texel size (strictly a Vulkan validity requirement).

**Metal pitfall:** `copyFromBuffer:...toTexture:` requires `sourceOffset` and `sourceBytesPerRow` to be multiples of the pixel size **[speculation: per Metal docs, verify]**. Allocate staging with `alignment = max(blockSize, 4)` (or 16). Use `bytesPerRow = srcW * blockSize` and `bytesPerImage = srcW * srcH * blockSize`. Cubemap faces go to `destinationSlice = depthOrLayer`.

### 6.9 `copyTextureToBuffer(src, dst, offset, Runnable callback, mip[, x, y, w, h])` and callback timing
```java
VkBufferImageCopy{ bufferOffset = offset, bufferRowLength = w, bufferImageHeight = h,
    imageSubresource = {formatAspectMask(src.format), mip, layer 0, 1},
    imageOffset = (x,y,0), imageExtent = (w,h,1) };
vkCmdCopyImageToBuffer(cb, image, GENERAL, buffer, copy); memoryBarrier();
this.queueForDestroy(callback::run);      // <-- the callback is a "Destroyable"
```
- **When the callback runs:** it is queued into the destruction-queue slot of the submit being recorded (N). It runs inside the `submit()` that ends frame N+1, right after `awaitSubmitCompletion(N)` succeeds (section 6.13). So:
  - It runs on the **render thread**, synchronously inside `CommandEncoder.submit()`.
  - The copy is guaranteed complete and visible (host-coherent memory).
  - Latency is about 1-2 frames.
  - During shutdown, `VulkanCommandEncoder.destroy()` drains the queue, so pending callbacks still run.
- The 5-arg overload copies the full mip. The frontend validates only the 9-arg overload:
  - `w*h*blockSize + offset <= dst.size()`
  - `COPY_SRC` on the texture, `COPY_DST` on the buffer
  - rect in bounds, not closed, `depthOrLayers == 1`
- The aspect mask is computed from the format: D32 gives `DEPTH`. `D24S8`/`D32S8` would produce `DEPTH|STENCIL`, which is invalid for buffer copies, but unused.
- GL contrast: `glReadPixels` into a PBO, then `RenderSystem.queueFencedTask(callback)`. That creates a `GpuFence` for the current submit. `RenderSystem.executePendingTasks()` (called each frame before `gameRenderer.render()`) runs the callbacks whose fences pass `awaitCompletion(0)`, in FIFO order. Also render thread, also after GPU completion.

Metal **[recommendation]**: never run the callback on a Metal completion-handler thread. Queue it with the submit index and run it on the render thread from `submit()` once that submit has completed, like Vulkan. The destination should be a `Shared` buffer.

### 6.10 `copyTextureToTexture(src, dst, mip, dstX, dstY, srcX, srcY, w, h)`
- `vkCmdCopyImage(src GENERAL, dst GENERAL, region {src/dst subresource = {aspect from SOURCE format, mip, layer 0, 1}, offsets, extent (w,h,1)})`, then barrier.
- It is a raw copy: same mip level on both sides, no scaling or format conversion.
- The frontend requires `COPY_SRC`/`COPY_DST`, bounds, and 1 layer on both.
- GL uses `glBlitFramebuffer(..., NEAREST)` between two FBOs.
- Metal: `copyFromTexture:sourceSlice:sourceLevel:...` requires compatible pixel formats **[speculation]**.

### 6.11 `createFence()`
```java
return new GpuFence() {
    final long submitIndex = currentSubmitIndex;   // the submit being recorded
    boolean completed;
    public boolean awaitCompletion(long timeoutNS) {   // param is named timeoutMs in Vulkan but treated as NS
        if (!completed) completed = awaitSubmitCompletion(submitIndex, timeoutNS);
        return completed;
    }
    public void close() { completed = true; }        // no waiting, no resource
};
```
Contract (shared with GL's `GlFence`):
- A fence signals when the *whole submit* in which it was created completes.
- `awaitCompletion(0)` before that submit was submitted returns `false`. Any non-zero timeout before submission **throws `IllegalStateException`**.
- `GpuFence.NO_TIMEOUT = -1` means an infinite wait (`-1` as uint64 is `UINT64_MAX`).
- After `close()`, `awaitCompletion` returns `true`.
- The frontend forbids `createFence()` inside a render pass.

### 6.12 `writeTimestamp(pool, index)` (encoder and render pass alike)
```java
vkResetQueryPool(device, pool, index, 1);                         // host reset NOW (record time)
vkCmdWriteTimestamp2KHR(cb, ALL_COMMANDS /*0x10000*/, pool, index);
```
The frontend only checks `0 <= index <= pool.size()` (sic: `<=`).

`getTimestampNow()` creates a 1-entry pool and a one-off CB with `vkCmdWriteTimestamp2KHR(stage NONE)`. It submits through a *separate* `Submission` on the graphics queue and reads with `vkGetQueryPoolResults(flags = 64_BIT|WAIT)`, which blocks.

Metal **[recommendation]**:
- Apple GPUs only support counter sampling at stage boundaries.
- The simplest faithful option: record CPU-side "timestamp requests" in submit order and resolve them from `MTLCommandBuffer.GPUStartTime`/`GPUEndTime` (seconds, host clock) in the completion handler. Report `timestampPeriod = 1` (ns) and a calibration offset mapping mach time to `System.nanoTime`.
- `getValues` must stay non-blocking.

### 6.13 `DestructionQueue` / `queueForDestroy` semantics
```java
DestructionQueue<T>(internalQueueCount = 2, destroyer)
add(t):   queues[current].add(t)
rotate(): current = (current+1) % 2; swap queues[current] with a fresh list; destroy every element of the old list
close():  rotate until a full pass of rotations finds nothing (loop restarts while items keep appearing)
```
Because `rotate()` runs in `submit()` after waiting for N-1, anything `add`ed while recording submit N (including inside `submit()` before the rotate, e.g. transient-memory rotation runnables) is destroyed in the `submit()` of N+1, after the GPU has finished N. That is exactly "destroy after the last GPU use, assuming the last use was in the submit during which close() was called or earlier".

Users of `queueForDestroy`:
- `VulkanGpuBuffer.Direct.close()`
- `VulkanGpuTexture` (when closed and `views == 0`)
- `VulkanGpuTextureView.close()`
- `VulkanGpuSampler.close()`
- `VulkanRenderPipeline.close()`
- `VulkanQueryPool.close()`
- per-draw texel `VkBufferView`s (`VulkanRenderPass.pushDescriptors`)
- `copyTextureToBuffer` callbacks
- transient staging-allocator rotations
- transient GPU-allocator rotations and frees (UMA path)

`VulkanCommandEncoder.destroy()`:
1. `transientMemory.endSubmit()`
2. `submissionBuilder.close()` (submits pending work, no timeline signal)
3. `graphicsQueue.waitIdle()`
4. `destroyQueue.close()`
5. `transientMemory.destroy()`
6. `destroyQueue.close()` again
7. destroy the pools and the semaphore

Metal note: `MTLCommandBuffer` retains referenced resources by default, so releasing an `MTLBuffer`/`MTLTexture` on `close()` is memory-safe. You still need deferred *recycling* for:
- transient memory blocks (CPU writes into recycled memory would race the GPU)
- `copyTextureToBuffer` callbacks
- anything you sub-allocate yourself

A 2-slot ring keyed on the submit index is the direct port.

---

## 7. `VulkanRenderPass` (`implements RenderPassBackend`)

| Method | Behavior |
|---|---|
| `pushDebugGroup(label)` / `popDebugGroup()` | Debug-utils label. Pop past zero throws. `label.get()` is only evaluated when labels are wanted. |
| `setPipeline(p)` | Requires a `VulkanRenderPipeline`. Sets `anyDescriptorDirty`, **clears the uniform array** and resizes it to `p.uniforms().size()`. Then `vkCmdBindPipeline(GRAPHICS, hasDepth ? withDepthPipeline : withoutDepthPipeline)`. The frontend then re-issues `setUniform(index, value)` for every remembered uniform name that the new pipeline uses (`FrontendRenderPass.setPipeline` → `uniforms.forEach(this::setUniform)`) and resets `constantsPushed`. |
| `setUniform(index, value)` | `uniforms.set(index, value)` and marks dirty. The value is a `GpuBufferSlice` (UBO and texel buffer), a `TextureViewAndSampler`, or `null`. |
| `pushConstants(ByteBuffer)` | `vkCmdPushConstants(layout, stageFlags = 0x7FFFFFFF, offset 0, value)`. The whole `remaining()` is used. The frontend ensures `remaining >= pipeline.pushConstantSize()`. |
| `enableScissor(x,y,w,h)` | `vkCmdSetScissor`. The frontend ensures it lies inside the render area and has w,h > 0. |
| `disableScissor()` | Scissor = render area (or full output if the render area were null). |
| `setVertexBuffer(slot, slice)` | If non-null, `vkCmdBindVertexBuffers(slot, buffer, slice.offset)`. **null is a no-op** (the previous binding stays). The frontend checks slot 0..15 and `USAGE_VERTEX`. |
| `setIndexBuffer(buffer, type)` | `vkCmdBindIndexBuffer(buffer, **offset 0**, SHORT→UINT16, INT→UINT32)`. Callers pass a whole buffer (e.g. `ChunkSectionsToRender` passes `slice.buffer()`), so offsets are folded into `firstIndex`. |
| `drawIndexed(count, inst, firstIndex, vertexOffset, firstInstance)` | `pushDescriptors()`, then `vkCmdDrawIndexed` |
| `draw(vcount, inst, firstVertex, firstInstance)` | `pushDescriptors()`, then `vkCmdDraw` |
| `multiDrawIndexed(IntBuffer params, inst, firstInst, n)` | `vkCmdDrawMultiIndexedEXT`, stride `VkMultiDrawIndexedInfoEXT.SIZEOF` = 12. Layout per draw is `{firstIndex, indexCount, vertexOffset}`. `pVertexOffset = NULL`, so per-draw offsets are used. |
| `multiDraw(IntBuffer params, inst, firstInst, n)` | `vkCmdDrawMultiEXT`, stride 8, layout `{firstVertex, vertexCount}` |
| `multiDrawIndexed(PointerBuffer, IntBuffer, IntBuffer, n)` / `multiDraw(IntBuffer, IntBuffer, n)` ("separate") | **throws** `UnsupportedOperationException` (the feature is reported false) |
| `drawIndexedIndirect(slice, n)` | `vkCmdDrawIndexedIndirect(buffer, slice.offset, n, stride 20)`. Command layout `{indexCount, instanceCount, firstIndex, vertexOffset, firstInstance}` matches `MTLDrawIndexedPrimitivesIndirectArguments`. |
| `drawIndirect(slice, n)` | `vkCmdDrawIndirect(..., stride 16)`. Matches `MTLDrawPrimitivesIndirectArguments`. |
| `writeTimestamp(pool, i)` | Same as the encoder version (section 6.12) |

`pushDescriptors()` runs at each draw if dirty. It writes **all** `pipeline.uniforms()` bindings at once:
```java
for i in uniforms:
  UNIFORM_BUFFER         -> VkDescriptorBufferInfo{buffer, offset=slice.offset, range=slice.length}, type 6
  COMBINED_IMAGE_SAMPLER -> VkDescriptorImageInfo{sampler, imageView, layout GENERAL}, type 1
  TEXEL_BUFFER           -> new VkBufferView{buffer, format=toVk(uniform.gpuFormat()), offset, range=length}
                            (destroyed via queueForDestroy), type 4
  missing value          -> IllegalStateException("Missing uniform <name> (should be <type>)")
vkCmdPushDescriptorSetKHR(cb, GRAPHICS, layout, set 0, writes)
```
- The frontend requires texel-buffer values to be the **entire buffer** (`offset == 0 && length == size`) with `USAGE_UNIFORM_TEXEL_BUFFER`.
- The only vanilla texel buffer is `CloudFaces`, `R8_SINT` (`BindGroupLayouts.java`).

Metal **[recommendation]**:
- UBO: `setVertexBuffer/setFragmentBuffer(buf, offset, index)`.
- Sampler: `setFragmentTexture` + `setFragmentSamplerState` (and vertex too if the shader uses it).
- Texel buffer: an `MTLTexture` of type `TypeTextureBuffer` created from the buffer. **Cache it per (buffer, format)** rather than creating one per draw.
- Push constants: `setVertexBytes`/`setFragmentBytes` (at most 4 KiB). Metal keeps them across pipeline changes within an encoder.

---

## 8. Transient memory (`VulkanTransientMemory` + `TransientBlockAllocator`)

### 8.1 `TransientBlockAllocator<T extends Block>` (`backend/util/TransientBlockAllocator.java`)
Constructor: `(blockSize, maxAlignment, Allocator<T> alloc/free, Consumer<T> onBlockUse = noop)`. State:
- `currentBlock`, `currentOffset`
- `usedBlocks` (blocks handed out this rotation)
- `specialBlocks` (oversized one-offs)
- `freeBlocks` (recycled, ready for reuse)

`allocate(size, alignment, minimumAllocation, elementSize)` returns `Allocation(block, offset, size)`:
1. `alignment > maxAlignment` throws.
2. `size == blockSize`: a fresh whole block (`allocateBlock()`), and `currentBlock` is untouched.
3. `size > blockSize || alignment > maxAlignment` (`!canAllocateInBlock`): a **special block** of exactly `size` (`alloc(size)`, `onBlockUse`, added to `specialBlocks`) at offset 0.
4. If `currentBlock == null`, take a block (`allocateBlock()` pops from `freeBlocks` or allocs) at offset 0.
5. If `size` fits after `roundUp(currentOffset, alignment)`, bump-allocate.
6. Otherwise, if `minimumAllocation` fits: allocate the **rest of the block**, truncated to a multiple of `elementSize`. The returned `size` can be smaller than requested. This is the "partial allocation" API used by callers that can split work.
7. Otherwise take a new block for this allocation at offset 0. If `currentOffset > size`, the new block becomes current (more space left). Otherwise the old block stays current.

`rotate()` returns a `Runnable`:
- Immediately: `currentBlock = null`, `currentOffset = blockSize`, **free every block still in `freeBlocks`** (not reused during the last period), and clear `freeBlocks`.
- It snapshots and clears `usedBlocks`/`specialBlocks`. The returned Runnable (run when the GPU is done) puts used blocks back into `freeBlocks` (or frees them if `suboptimal()`) and frees the special blocks.
- In steady state the pool therefore shrinks to the working set.

`close()` = `rotate().run(); rotate().run();`. `roundToward(x, m)` = `ceil(x/m)*m` (`Mth`).

### 8.2 `VulkanTransientMemory` configuration
- `BLOCK_SIZE = 512 KiB` for all allocators.
- `MAX_CPU_ALIGNMENT = 16`.
- `MAX_GPU_ALIGNMENT = 2^62` (effectively unlimited).
- Every transient `VkBuffer` gets usage `471 = TRANSFER_SRC|TRANSFER_DST|UNIFORM_TEXEL|UNIFORM|INDEX|VERTEX|INDIRECT`.
- **UMA detection:** find the largest `DEVICE_LOCAL` heap. If any memory type in that heap is `HOST_VISIBLE|HOST_COHERENT`, then `useDeviceMemoryForMappedGpuStaging = true` (true on Apple Silicon/MoltenVK and resizable-BAR PCs **[speculation]**). The "expected CPU heap" is that heap in the UMA case, else the first host-visible+coherent non-device-local heap. A block allocated outside its expected heap is marked `suboptimal` and is not recycled.

| Allocator | Backing (`allocateVulkanBlock(size, staging)`) | `free` function | Rotation handling in `endSubmit()` |
|---|---|---|---|
| `cpuBlockAllocator` | `nmemAlloc` (CPU only), align ≤ 16 | `nmemFree` | `rotate().run()` **immediately**. CPU memory is valid only until `submit()`. |
| `stagingBlockAllocator` | VMA `AUTO_PREFER_HOST` (non-UMA) or `AUTO_PREFER_DEVICE` (UMA), `requiredFlags HOST_VISIBLE\|HOST_COHERENT`, `SEQUENTIAL_WRITE`, persistently mapped | `freeVulkanBlock` (immediate `vmaDestroyBuffer`) | `queueForDestroy(rotate()::run)`, i.e. recycled after the GPU finishes |
| `gpuBlockAllocator` | VMA `AUTO_PREFER_DEVICE`. In the UMA case it is also `HOST_VISIBLE\|COHERENT`, sequential write and mapped. | `queueFreeVulkanBlock` (deferred destroy) | UMA: `queueForDestroy(rotate()::run)` (CPU writes into these). Non-UMA: `rotate().run()` immediately (GPU-only memory, reuse ordered by the queue). |
| `gpuMappedBlockAllocator` | `TransferPair(cpu, gpu)`: UMA gives `(gpuBlock, sameBlock)`; non-UMA gives `(stagingBlock, gpuBlock)`, each a whole-block allocation from the allocators above. `onBlockUse = recordGpuMappedCopy`. | no-op (the underlying blocks belong to the other allocators) | `rotate()` and **the returned Runnable is discarded**, so pairs are never recycled. Each period re-carves fresh pairs from the staging and GPU allocators, which do recycle. |

`recordGpuMappedCopy(pair)` (non-UMA only) records a whole-block `vkCmdCopyBuffer(cpu → gpu)` into the transient CB at the moment the block is first used. That CB is first in the submission, so the copy executes before every command of this submit and picks up all CPU writes made before `submit()`.

### 8.3 API methods
| Method | Returns | Notes |
|---|---|---|
| `allocateCpu(size, align, minAlloc, elemSize)` | `ByteBuffer` in a CPU block | Valid until the next `submit()`. Asserts `size <= Integer.MAX_VALUE`. |
| `allocateStaging(size, align, usage, minAlloc, elemSize)` | `MappedView(slice, cpuBuffer, onClose = noop)` | The slice's buffer is a `TransientGpuBuffer` over the whole block. Use it as a copy source. |
| `allocateGpu(...)` | `GpuBufferSlice` | GPU-only scratch, written only by GPU commands |
| `allocateGpuMapped(...)` | `MappedView` | The CPU writes `cpuBuffer`, and the GPU reads `slice` during this submit. Non-UMA does a copy at submit start. UMA uses the same memory. |
| `uploadStaging(List<ByteBuffer>, align, usage, minAlloc, elemSize)` / `uploadGpu(...)` | `GpuBufferSlice` | Goes through `upload(..., staging)`. `totalSize = Σ roundUp(acc + remaining, align)`, i.e. each element is padded to `align`, including the last. It allocates via `allocateStaging`/`allocateGpuMapped` and memcpy's each buffer at an aligned offset. It stops early if a partial allocation came back short. |
| `multiUploadStaging/Gpu(List<ByteBuffer>, align, usage)` | `List<GpuBufferSlice>` in input order | Sorts indices by size ascending. Repeatedly scans from the largest down for one that `canAllocateInCurrentBlock` and allocates it. If none fits, pops the largest and allocates it via **`allocateGpuMapped` even in staging mode** (it is still copy-src capable because every transient buffer has all usage bits). |

`TransientGpuBuffer` (the `GpuBuffer` behind every transient slice):
- `size()` = whole block size, and `usage()` = the usage requested by the caller.
- `isClosed()` becomes `true` once `VulkanTransientMemory.submitIndex` has advanced past its creation index, i.e. after `endSubmit()`. **Transient slices are only valid within the submit they were allocated in.**
- `map()` throws "Cannot map transient buffer".
- `buffer.slice(...)` and `slice()` throw "Cannot slice transient buffer". Slicing the returned `GpuBufferSlice` record itself is fine.
- `destroy()` and `close()` are no-ops (except setting the flag).

GL (`GlTransientMemory`) has the same API surface and the same 512 KiB blocks, with two strategies:
- **`PersistentMapping`** (requires `GL_ARB_buffer_storage`):
  - staging allocator: `CLIENT_STORAGE` + persistent coherent mapped buffers
  - GPU allocator: immutable, unmapped
  - GPU-mapped allocator: persistent coherent mapped
  - Map flags are `WRITE|PERSISTENT|COHERENT|UNSYNCHRONIZED`.
  - The three rotation Runnables are kept per submit slot and run 2 submits later (`rotations[currentSubmitSlot]`). This is the same latency as Vulkan.
- **`Fallback`** (e.g. macOS GL 4.1 **[speculation: no buffer_storage there]**):
  - One allocator of `GlBuffer`s (usage 40) plus a malloc'd host shadow.
  - `allocateStaging` returns the host shadow, and `MappedView.close()` uploads with `glBufferSubData`. **Writes are visible only after `close()`.**
  - `allocateGpuMapped` is the same as `allocateStaging`.
  - `uploadGpu` uses `glMapBufferRange(WRITE|INVALIDATE_RANGE|UNSYNCHRONIZED)`.
  - Rotation runs immediately at every `submit()`.
- GL `allocateCpu` behaves like Vulkan's.

Metal port **[recommendation]**:
- On Apple Silicon every block is one `MTLStorageModeShared` `MTLBuffer` (UMA path: `cpu == gpu`, no copies). Keep the three logical allocators so recycling latencies match:
  - CPU blocks: immediate.
  - Staging and GPU-mapped blocks: after GPU completion.
- Honour `MappedView.close()` as a no-op, and `isClosed()` flipping at submit.
- On Intel Macs with discrete GPUs, either use `Shared` (slower GPU reads) or port the `TransferPair` copy scheme with `Managed`/`Private` buffers.

---

## 9. `VulkanRenderPipeline` (`BackendRenderPipeline.CreateInfo` to `VkPipeline`)

`CreateInfo` fields:
- `name`
- `shaders` (`Shader(name, entryPoint, SpvModule module)`)
- `vertexBuffers` (`VertexBuffer(bufferSlot, stride, stepRate)`)
- `attribBindings` (`AttribBinding(bufferSlot, location, offset, GpuFormat)`)
- `uniforms` (`List<BindGroupLayout.UniformDescription(name, UniformType, @Nullable GpuFormat)>`)
- `pushConstantsSize`
- `@Nullable depthStencilState`
- `polygonMode`
- `cull`
- `colorTargetStates` (`List<@Nullable ColorTargetState>`)
- `primitiveTopology`

Relevant frontend guarantees (`PipelineBuilder.generateBackendCreateInfo`):
- The SPIR-V has already been rewritten so that **every descriptor is in set 0 with binding = index into `uniforms`**. Bindings are assigned by first appearance across stages, and only uniforms referenced by a shader are present.
- Allowed sampler dimensions are 2D, Cube, Rect, or Buffer (texel buffer). 1D and 3D are rejected.
- There is at most one push-constant block, no larger than `pushConstantsSize`.

`compile(device, ci)`:
1. **Descriptor set layout** (flags `PUSH_DESCRIPTOR_BIT_KHR`): for `i in uniforms`, `binding = i`, `descriptorCount = 1`, `stageFlags = VERTEX|FRAGMENT (17)`. The type is `UNIFORM_BUFFER (6)`, `COMBINED_IMAGE_SAMPLER (1)` or `UNIFORM_TEXEL_BUFFER (4)`.
2. **Pipeline layout:** that one set layout. If `pushConstantsSize != 0`, one range `{stageFlags 0x7FFFFFFF, offset 0, size pushConstantsSize}`.
3. **Shader stages:** `vkCreateShaderModule(pCode = module.spv())` per shader. The stage comes from `module.type()` (`VERTEX → 1`, `FRAGMENT → 16`) and `pName = shader.entryPoint()` (always `"main"` from `GlslCompiler`). Modules are kept until pipeline destroy.
4. **Vertex input:**
   - Per `VertexBuffer`: `binding = bufferSlot`, `stride`, `inputRate = stepRate > 0 ? INSTANCE : VERTEX`.
   - If `stepRate > 0` there is also a divisor description `{binding, divisor = stepRate}` chained via `VkPipelineVertexInputDivisorStateCreateInfoEXT` (only when any exist).
   - Per `AttribBinding`: `{location, binding = bufferSlot, offset, format = toVk(GpuFormat)}`.
5. **Input assembly:** `topology = toVk(primitiveTopology)` (section 10). Primitive restart is disabled.
6. **Rasterization:**
   - `polygonMode = FILL(0) | LINE(1)`
   - **`cullMode = cull ? BACK(2) : NONE(0)`, `frontFace = CLOCKWISE(1)`**
   - `lineWidth = 1`
   - `depthClamp` and `rasterizerDiscard` off
   - If a depth state exists: `depthBiasEnable = constant != 0 || scale != 0`, `depthBiasConstantFactor = depthBiasConstant`, `depthBiasSlopeFactor = depthBiasScaleFactor`, and `depthBiasClamp = 0`.
7. **Depth-stencil:** if a depth state exists, `depthTestEnable = true`, `depthWriteEnable = writeDepth` and `depthCompareOp = toVk(depthTest)`. Otherwise everything is zeroed (test off, write off). Stencil and depth-bounds are always off.
8. **Color blend:** one attachment state per entry of `colorTargetStates` (including nulls):
   - `colorWriteMask = state != null ? (R=1,G=2,B=4,A=8 from writeRed/Green/Blue/Alpha) : 0`.
   - If `blendFunction` is present: `blendEnable = true` with color/alpha factors and ops from `BlendFunction.color()`/`.alpha()`.
   - `logicOp` is off. **Blend constants are left at 0, and there is no API to set them**, so `CONSTANT_*` factors use (0,0,0,0).
9. **Viewport state:** 1 viewport and 1 scissor, both **dynamic** (`pDynamicStates = {SCISSOR(1), VIEWPORT(0)}`).
10. **Multisample:** 1 sample, no sample shading.
11. **Dynamic rendering formats** (`VkPipelineRenderingCreateInfoKHR`):
    - `pColorAttachmentFormats[i] = state != null ? toVk(state.format()) : UNDEFINED(0)`
    - **`depthAttachmentFormat = D32_SFLOAT (126)`** for the "with depth" pipeline
    - stencil is `UNDEFINED`
12. `vkCreateGraphicsPipelines(cache = NULL)` gives `withDepthPipeline`.
13. **If `depthStencilState == null`:** set `depthAttachmentFormat = UNDEFINED` and create again, giving `withoutDepthPipeline`. **Otherwise `withoutDepthPipeline = 0`.**

Binding at draw time: `VulkanRenderPass.setPipeline` binds `hasDepth ? withDepthPipeline : withoutDepthPipeline`. Consequences:
- A pipeline **without** depth state works in passes with or without a depth attachment. In a depth pass its depth test and write are off.
- A pipeline **with** depth state in a pass **without** depth binds handle 0, which is invalid Vulkan. The frontend only catches this under `STRICT_VALIDATION` (IDE runs), via `wantsDepthTexture()`. **Only `D32_FLOAT` depth attachments are supported** by any Vulkan pipeline. That is also the only depth format vanilla creates.
- The frontend (`FrontendRenderPass.setPipeline`) requires the pass color attachment count to equal `colorTargetStates.size()`, and each non-null attachment format to equal the target's `format()`.

`close()` sets `closed` and queues the pipeline for destroy. `destroy()` (only if `withDepthPipeline != 0`) destroys both pipelines, the layout, the set layout and the shader modules.

Metal **[recommendation]**:
- Build one `MTLRenderPipelineState` per (pipeline, depth-attachment-present).
  - The "with depth" variant has `depthAttachmentPixelFormat = .depth32Float`.
  - Build the "without depth" variant only when there is no depth state, exactly like Vulkan.
- Use a separate `MTLDepthStencilState`: compare from `depthTest` and write from `writeDepth`, or `always`/no-write when there is no depth state.
- Per-draw state on the render encoder:
  - `setCullMode(cull ? .back : .none)`
  - `setFrontFacingWinding(.clockwise)` (see section 14, item 1)
  - `setTriangleFillMode(WIREFRAME ? .lines : .fill)`
  - `setDepthBias(depthBiasConstant, slopeScale: depthBiasScaleFactor, clamp: 0)`
- Vertex descriptor:
  - `layouts[slot]`: `stride`, with `stepFunction = .perInstance` and `stepRate = stepRate` when `stepRate > 0`.
  - `attributes[location]`: `format`, `offset`, `bufferIndex` = the slot mapped to the Metal buffer table (keep clear of the UBO and push-constant indices chosen in the SPIRV-Cross resource binding map).

---

## 10. `VulkanConst`: complete mappings

### 10.1 `GpuFormat` to `VkFormat` (`toVk(GpuFormat)`). No format is rejected; the switch is exhaustive.
| GpuFormat | VkFormat (value) | Metal pixel format **[recommendation]** | Metal vertex format |
|---|---|---|---|
| R8_UNORM | R8_UNORM (9) | R8Unorm | ucharNormalized |
| R8_SNORM | R8_SNORM (10) | R8Snorm | charNormalized |
| RG8_UNORM | R8G8_UNORM (16) | RG8Unorm | uchar2Normalized |
| RG8_SNORM | R8G8_SNORM (17) | RG8Snorm | char2Normalized |
| RGB8_UNORM | R8G8B8_UNORM (23) | **none (no 3x8-bit textures)** | uchar3Normalized |
| RGB8_SNORM | R8G8B8_SNORM (24) | **none** | char3Normalized |
| RGBA8_UNORM | R8G8B8A8_UNORM (37) | RGBA8Unorm | uchar4Normalized |
| RGBA8_SNORM | R8G8B8A8_SNORM (38) | RGBA8Snorm | char4Normalized |
| R16_UNORM | R16_UNORM (70) | R16Unorm | ushortNormalized |
| R16_SNORM | R16_SNORM (71) | R16Snorm | shortNormalized |
| RG16_UNORM | R16G16_UNORM (77) | RG16Unorm | ushort2Normalized |
| RG16_SNORM | R16G16_SNORM (78) | RG16Snorm | short2Normalized |
| RGB16_UNORM | R16G16B16_UNORM (84) | **none** | ushort3Normalized |
| RGB16_SNORM | R16G16B16_SNORM (85) | **none** | short3Normalized |
| RGBA16_UNORM | R16G16B16A16_UNORM (91) | RGBA16Unorm | ushort4Normalized |
| RGBA16_SNORM | R16G16B16A16_SNORM (92) | RGBA16Snorm | short4Normalized |
| R8_UINT | R8_UINT (13) | R8Uint | uchar |
| R8_SINT | R8_SINT (14) | R8Sint | char |
| RG8_UINT | R8G8_UINT (20) | RG8Uint | uchar2 |
| RG8_SINT | R8G8_SINT (21) | RG8Sint | char2 |
| RGB8_UINT | R8G8B8_UINT (27) | **none** | uchar3 |
| RGB8_SINT | R8G8B8_SINT (28) | **none** | char3 |
| RGBA8_UINT | R8G8B8A8_UINT (41) | RGBA8Uint | uchar4 |
| RGBA8_SINT | R8G8B8A8_SINT (42) | RGBA8Sint | char4 |
| R16_UINT | R16_UINT (74) | R16Uint | ushort |
| R16_SINT | R16_SINT (75) | R16Sint | short |
| RG16_UINT | R16G16_UINT (81) | RG16Uint | ushort2 |
| RG16_SINT | R16G16_SINT (82) | RG16Sint | short2 |
| RGB16_UINT | R16G16B16_UINT (88) | **none** | ushort3 |
| RGB16_SINT | R16G16B16_SINT (89) | **none** | short3 |
| RGBA16_UINT | R16G16B16A16_UINT (95) | RGBA16Uint | ushort4 |
| RGBA16_SINT | R16G16B16A16_SINT (96) | RGBA16Sint | short4 |
| R32_UINT | R32_UINT (98) | R32Uint | uint |
| R32_SINT | R32_SINT (99) | R32Sint | int |
| RG32_UINT | R32G32_UINT (101) | RG32Uint | uint2 |
| RG32_SINT | R32G32_SINT (102) | RG32Sint | int2 |
| RGB32_UINT | R32G32B32_UINT (104) | **none** | uint3 |
| RGB32_SINT | R32G32B32_SINT (105) | **none** | int3 |
| RGBA32_UINT | R32G32B32A32_UINT (107) | RGBA32Uint | uint4 |
| RGBA32_SINT | R32G32B32A32_SINT (108) | RGBA32Sint | int4 |
| R16_FLOAT | R16_SFLOAT (76) | R16Float | half |
| RG16_FLOAT | R16G16_SFLOAT (83) | RG16Float | half2 |
| RGB16_FLOAT | R16G16B16_SFLOAT (90) | **none** | half3 |
| RGBA16_FLOAT | R16G16B16A16_SFLOAT (97) | RGBA16Float | half4 |
| R32_FLOAT | R32_SFLOAT (100) | R32Float | float |
| RG32_FLOAT | R32G32_SFLOAT (103) | RG32Float | float2 |
| RGB32_FLOAT | R32G32B32_SFLOAT (106) | **none** | float3 |
| RGBA32_FLOAT | R32G32B32A32_SFLOAT (109) | RGBA32Float | float4 |
| RGB10A2_UNORM | A2B10G10R10_UNORM_PACK32 (64) | RGB10A2Unorm | uint1010102Normalized |
| RGB10A2_UINT | A2B10G10R10_UINT_PACK32 (68) | RGB10A2Uint | (none) |
| RG11B10_FLOAT | B10G11R11_UFLOAT_PACK32 (122) | RG11B10Float | floatRG11B10 |
| D32_FLOAT | D32_SFLOAT (126) | Depth32Float | n/a |
| D32_FLOAT_S8_UINT | D32_SFLOAT_S8_UINT (130) | Depth32Float_Stencil8 | n/a |
| D24_UNORM_S8_UINT | D24_UNORM_S8_UINT (129) | **Depth24Unorm_Stencil8: not on Apple Silicon** | n/a |
| D16_UNORM | D16_UNORM (124) | Depth16Unorm | n/a |
| S8_UINT | S8_UINT (127) | Stencil8 | n/a |

`GpuFormat.blockSize()` = component bytes x count. The packed formats count as one 4-byte component, `D32_FLOAT_S8_UINT` is 8 bytes and `D16` is 2 bytes. `byteAlignment()` = component byte size.

The Metal format column, and especially the vertex-format column, is my own mapping and is unverified.

Vanilla usage (grep over `net/**`, `com/mojang/blaze3d/**`):
- Textures and targets: `RGBA8_UNORM`, `R8_UNORM` (font), `D32_FLOAT`, `RGBA16_FLOAT`, `RGBA32_FLOAT` (OIT).
- Texel buffer: `R8_SINT`.
- Vertex attributes: `RGB32_FLOAT`, `RG32_FLOAT`, `RG16_SINT`, `RGBA8_SNORM`, `R32_FLOAT`, `RGB32_SINT`, and `RGBA8_UNORM` (color).
- So the unsupported 3-component texture formats do not matter for vanilla, but mods could hit them.

**Verify:** OIT uses `BlendFunction.MAX` on an `RGBA32_FLOAT` render target (`RenderPipelines.OIT_DEPTH_BOUNDS_SNIPPET`). Check that RGBA32Float is blendable on the target Apple GPU families (Metal feature-set tables) **[speculation: possibly not on older families]**.

### 10.2 Other enums
| API enum | Vk | Metal **[recommendation]** |
|---|---|---|
| `AddressMode.REPEAT` / `CLAMP_TO_EDGE` | `REPEAT(0)` / `CLAMP_TO_EDGE(2)` | `.repeat` / `.clampToEdge` |
| `FilterMode.NEAREST` / `LINEAR` | 0 / 1 | `.nearest` / `.linear` |
| `CompareOp` `ALWAYS_PASS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `EQUAL`, `NOT_EQUAL`, `GREATER_THAN_OR_EQUAL`, `GREATER_THAN`, `NEVER_PASS` | 7, 1, 3, 2, 5, 6, 4, 0 | `.always`, `.less`, `.lessEqual`, `.equal`, `.notEqual`, `.greaterEqual`, `.greater`, `.never` |
| `BlendFactor` `ZERO`, `ONE`, `SRC_COLOR`, `ONE_MINUS_SRC_COLOR`, `DST_COLOR`, `ONE_MINUS_DST_COLOR`, `SRC_ALPHA`, `ONE_MINUS_SRC_ALPHA`, `DST_ALPHA`, `ONE_MINUS_DST_ALPHA`, `CONSTANT_COLOR`, `ONE_MINUS_CONSTANT_COLOR`, `CONSTANT_ALPHA`, `ONE_MINUS_CONSTANT_ALPHA`, `SRC_ALPHA_SATURATE` | 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 | the same-named `MTLBlendFactor` (`blendColor`/`blendAlpha` for the constants) |
| `BlendOp` `ADD`, `SUBTRACT`, `REVERSE_SUBTRACT`, `MIN`, `MAX` | 0, 1, 2, 3, 4 | `.add`, `.subtract`, `.reverseSubtract`, `.min`, `.max` |
| `PolygonMode` `FILL` / `WIREFRAME` | `FILL(0)` / `LINE(1)` | `.fill` / `.lines` |
| `ShaderType` `VERTEX` / `FRAGMENT` | 1 / 16 | n/a |
| `GpuSurface.PresentMode` `IMMEDIATE`, `MAILBOX`, `FIFO`, `FIFO_RELAXED` | 0, 1, 2, 3 | `displaySyncEnabled` NO / (none) / YES / (none) |
| `ColorTargetState` write mask | R=1, G=2, B=4, A=8, the same bit values as Vulkan | `MTLColorWriteMask` (R=8, G=4, B=2, A=1: **bit order reversed!**) |
| `IndexType` `SHORT` / `INT` (in `VulkanRenderPass`) | `UINT16(0)` / `UINT32(1)` | `.uint16` / `.uint32` |

`PrimitiveTopology` to Vk:
| Topology | Vk | Note | Metal |
|---|---|---|---|
| `LINES` | `TRIANGLE_LIST (3)` | "Lines" are quads expanded in the vertex shader (`rendertype_lines.vsh` uses `gl_VertexIndex % 2`). `indexCount(v) = v/4*6` with the sequential quad index buffer. | `.triangle` |
| `DEBUG_LINES` | `LINE_LIST (1)` | | `.line` |
| `DEBUG_LINE_STRIP` | `LINE_STRIP (2)` | | `.lineStrip` |
| `POINTS` | `POINT_LIST (0)` | | `.point`. The vertex shader must write `[[point_size]]`, and SPIRV-Cross can force it. |
| `TRIANGLES` | `TRIANGLE_LIST (3)` | | `.triangle` |
| `TRIANGLE_STRIP` | `TRIANGLE_STRIP (4)` | | `.triangleStrip` |
| `TRIANGLE_FAN` | `TRIANGLE_FAN (5)` | Only `DEBUG_TRIANGLE_FAN` / `OIT_DEBUG_TRIANGLE_FAN` and one more pipeline (`RenderPipelines.java` ~1034). Requires `triangleFans` under portability. | **Not supported**. Convert to an indexed triangle list (0,i,i+1). |
| `QUADS` | `TRIANGLE_LIST (3)` | Drawn with `RenderSystem.getSequentialBuffer(QUADS)` indices | `.triangle` |

---

## 11. `VulkanGpuSurface` (`implements GpuSurfaceBackend`)

Interface: `configure(Configuration(width, height, presentMode))`, `isSuboptimal()`, `acquireNextTexture()`, `blitFromTexture(encoder, view)`, `present()`, `supportedPresentModes()`, `close()`.
The frontend wrapper (`FrontendGpuSurface`) enforces:
- no configure or close while acquired
- the present mode must be in `supportedPresentModes()`
- configure before acquire, and acquire before blit
- blit exactly once per acquire, from the frontend encoder outside a render pass
- the source is a color texture with `USAGE_COPY_SRC` and 1 layer
- blit before present

### 11.1 Construction (`createSurface`)
- `SDL_Vulkan_CreateSurface(window, instance)`.
- Present modes are enumerated and converted to the enum set (unknown modes are ignored).
- Surface format: the first entry with `colorSpace == SRGB_NONLINEAR (0)` and format `R8G8B8A8_UNORM (37)` or `B8G8R8A8_UNORM (44)`. Otherwise `IllegalStateException`. **UNORM, not `_SRGB`:** the game's output is written as-is.
- Two binary acquire semaphores are created.

### 11.2 `configure(cfg)`
- `destroySwapchain()` first. If one exists it does `vkQueueWaitIdle`, destroys the swapchain and the per-image present semaphores. **No `oldSwapchain` handoff.**
- Surface capabilities: if `cfg.width/height` is outside `[minImageExtent, maxImageExtent]`, throws `SurfaceException` (e.g. 0x0 while minimized).
- `minImageCount = max(3, caps.minImageCount)`, the chosen format, `colorSpace = SRGB_NONLINEAR`, extent = cfg size, 1 layer, **`imageUsage = TRANSFER_DST (2)` only**, `EXCLUSIVE`, `preTransform = currentTransform`, `compositeAlpha = OPAQUE (1)`, `presentMode = toVk(cfg.presentMode)`, `clipped = true`.
- Fetches the images and creates one binary "present" semaphore per image. Resets `suboptimal`/`outOfDate` to false and stores the swapchain size.
- Any `SurfaceException` sets `suboptimal = outOfDate = true` and rethrows.

### 11.3 `acquireNextTexture()`
- If a previously "eaten" present exception exists, it is thrown now (wrapped in a new `SurfaceException`) and cleared.
- If `outOfDate`, throws `IllegalStateException("Attempt to use out of date swapchain")`.
- Rotates `currentAcquireSemaphore` (2 of them), then `vkAcquireNextImageKHR(timeout 5 s, semaphore, no fence)`.
  - `VK_TIMEOUT (2)`: `IllegalStateException("GPU timeout attempting to acquire next frame")` plus checkpoints.
  - `VK_ERROR_OUT_OF_DATE_KHR`: `suboptimal = outOfDate = true` and `SurfaceException`.
  - `VK_SUBOPTIMAL_KHR`: `suboptimal = true`, continue.
  - Other errors: `crashIfFailure`.

### 11.4 `blitFromTexture(encoder, view)` and the vertical flip
1. A new transient CB from the encoder's current pool.
2. Barrier (sync2): swapchain image `UNDEFINED -> TRANSFER_DST_OPTIMAL`, dst stage and access = `TRANSFER`/`TRANSFER_WRITE`.
3. The blit:
```java
int copyWidth  = min(swapchainWidth,  view.getWidth(0));
int copyHeight = min(swapchainHeight, view.getHeight(0));
srcOffsets = { (0,0,0), (copyWidth, copyHeight, 1) };
dstOffsets = { (0, copyHeight, 0), (copyWidth, 0, 1) };   // <-- Y FLIP
srcSubresource = {COLOR, mipLevel = view.baseMipLevel(), layer 0, 1};
dstSubresource = {COLOR, mip 0, layer 0, 1};
vkCmdBlitImage(cb, srcImage, GENERAL, swapchainImage, TRANSFER_DST_OPTIMAL, region, filter NEAREST(0));
```
   The blit also converts RGBA8 to BGRA8 when needed.
4. Barrier: `TRANSFER_DST_OPTIMAL -> PRESENT_SRC_KHR`, src `TRANSFER`/`TRANSFER_WRITE`, dst `ALL_COMMANDS`/0. A global memory barrier `TRANSFER`/`TRANSFER_READ -> ALL_COMMANDS`/`MEMORY_READ|WRITE` is added too.
5. End the CB. `encoder.waitSemaphore(acquireSemaphore, 0, ALL_COMMANDS)`, which flushes the main CB and starts a new stage. Then `encoder.execute(blitCb)` and `encoder.signalSemaphore(presentSemaphores[imageIndex], 0, TRANSFER)`.
6. Nothing is submitted yet. That happens in the following `encoder.submit()`.

The flip exists because Vulkan render targets have NDC y=-1 at memory row 0 (the viewport is not negated), while the swapchain displays row 0 at the top. GL's `presentTexture` does *not* flip: GL's default framebuffer origin is bottom-left. It aligns the image to the top with `destY = max(0, swapH - texH)`.

### 11.5 `present()`
- If `outOfDate`, throws `IllegalStateException`.
- `vkQueuePresentKHR(graphicsQueue, wait = presentSemaphores[imageIndex])` and resets `currentImageIndex = -1`.
  - `OUT_OF_DATE`: `suboptimal = outOfDate = true`, and the `SurfaceException` is stored in `eatenException`, to be thrown by the next `acquireNextTexture()`.
  - `SUBOPTIMAL`: `suboptimal = true`.
  - Other errors: `crashIfFailure`.
- Called *after* `encoder.submit()`.

### 11.6 Suboptimal and recovery loop (`Minecraft.renderFrame`)
- At the start of each frame, it reconfigures to the current `window.queryFramebufferSize()` (pixels) if `(needsReconfigure || surface.isSuboptimal() && !surfaceIsInvalid) && !window.isIconified()`. The present mode is `getSupportedVsyncMode(supported, vsync)`: FIFO for vsync, else the first of IMMEDIATE, MAILBOX, FIFO.
- A configure failure sets `surfaceIsInvalid = true`.
- An acquire failure (`SurfaceException`) sets `surfaceIsInvalid = needsReconfigure = true`.
- While not acquired, the frame is still rendered and `submit()`ed, but there is no blit or present.

### 11.7 Iconified handling
- **Vulkan ignores `isIconified`.** It relies on Minecraft skipping reconfiguration while iconified, and on the acquire result.
- GL's `acquireNextTexture()` throws `SurfaceException("Cannot acquire minimized window")` when iconified. That flips Minecraft into the invalid/reconfigure path until restored.

### 11.8 Metal port **[recommendation]**
- `supportedPresentModes = {FIFO, IMMEDIATE}`. `configure` sets:
  - `layer.drawableSize = (w,h)` and `layer.displaySyncEnabled = (mode == FIFO)`
  - `layer.pixelFormat = .bgra8Unorm` (not sRGB)
  - `layer.maximumDrawableCount = 3`
  - `layer.colorspace = sRGB` to match `SRGB_NONLINEAR` **[speculation: matches MoltenVK's treatment; verify visually]**
- **The present pass must be a draw, not a blit.** `CAMetalLayer` has no RGBA8Unorm format, a blit encoder copy cannot convert formats or flip, and `framebufferOnly = YES` drawables cannot be blit targets. Draw a fullscreen triangle sampling the source view (mip `baseMipLevel`) with nearest filtering and flipped V.
- **Ordering:** Minecraft calls `blit`, then `submit()`, then `present()`. `[cmdBuf presentDrawable:]` must be registered before `commit`, so call it from `blitFromTexture` and make `present()` bookkeeping only. Alternatively, call `[drawable present]` only after the command buffer has been scheduled.
- Consider acquiring `nextDrawable` lazily in `blitFromTexture` to hold the drawable as briefly as possible. It is a blocking call (up to 1 s). A nil drawable then means skipping present for that frame. Mirror GL's iconified check in `acquireNextTexture()`.

---

## 12. Debug labels and checkpoints (lower priority)
- `VulkanDebug.Enabled`:
  - `setObjectName` on buffers (type 9), images (10), image views (14), shader modules (15), pipeline layouts (17) and pipelines (19).
  - `vkCmdBegin/EndDebugUtilsLabelEXT` for render passes and `pushDebugGroup`. Labels are `Supplier<String>` and only evaluated when labels are wanted.
  - A messenger when verbosity > 0.
- Checkpoints (AMD buffer marker or NV diagnostic checkpoints) record `BEGIN_RENDER_PASS`/`END_RENDER_PASS` markers. They are reported on device loss (`GpuDeviceLossException`) and on the 5 s timeouts.
- The Metal analog is `MTLCommandBufferDescriptor.errorOptions = .encoderExecutionStatus` plus encoder labels **[recommendation]**.

---

## 13. `backend/common/*`
- `BaseGpuBuffer(usage, size)`: `size()`, `usage()`, and `checkCanBeUsed()` (no-op; GL overrides it).
- `BaseGpuTexture(usage, label, format, w, h, depthOrLayers, mips)`: `getWidth(m) = w >> m`, `getHeight(m) = h >> m`. Note: no `max(1, ...)` clamp.
- `BaseGpuTextureView(texture, baseMip, mipCount)`: `getWidth(m) = texture.getWidth(m + baseMip)`.

---

## 14. Metal implementation checklist and pitfalls

1. **Match the GL/Vulkan memory layout (Y convention).**
   - The frontend compiles the same SPIR-V for all backends. Vulkan uses viewport `(0,0,w,h)` with no negative height, so NDC y=-1 lands in memory row 0. GL lands in row 0 too (bottom-left origin). Post passes rely on this identity, e.g. `screenquad.vsh` maps `uv=(0,0)` to NDC (-1,-1) and samples row 0.
   - Metal's viewport maps NDC y=+1 to row 0, so it is the **opposite**. Enable SPIRV-Cross `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y` (16777220) for vertex shaders and keep a normal `(0,0,w,h)` viewport. Window-space positions then equal Vulkan's.
   - Mirror `frontFace = CLOCKWISE` as `MTLWindingClockwise` with back-face culling. MoltenVK uses the same direct mapping together with its default flip-Y. **[speculation: confirm with a culling test scene]**
   - `[[position]]` in fragment shaders then equals Vulkan's `gl_FragCoord` (top-left origin, row index). The present pass must flip vertically, as in `VulkanGpuSurface.blitFromTexture`.
2. **Depth range [0,1]:** report `isZZeroToOne = true`. Do not enable SPIRV-Cross `FIXUP_DEPTH_CONVENTION`.
3. **Present path:** see 11.8. Use a draw-based flip into a BGRA8Unorm drawable, register `presentDrawable` before `commit`, and remember that present happens after `submit()` in Minecraft's frame.
4. **Ordering of uploads:** `writeToBuffer`, `writeToTexture` and `createBuffer(data)` must behave as if executed in command order.
   - Stage the data plus a blit, as in 6.7/6.8.
   - Exception: a freshly created buffer that is not yet referenced by any command can be filled by a direct `memcpy` into `Shared` memory. That is the Vulkan init-CB case, and it is cheaper.
5. **Encoder structure:** Vulkan records everything into one CB per segment with full barriers. On Metal, keep one `MTLCommandBuffer` per `submit()`. Coalesce consecutive non-pass commands (copies, clears via passes) into as few encoders as possible, while never reordering across render passes.
6. **Frame pacing and fences:**
   - Only 2 submits in flight. `submit()` must block until submit N-1 completes. Use an `MTLSharedEvent` signaled with the submit index, or a completion-handler counter plus a condition variable.
   - Throw on a 5 s timeout. `GpuFence` semantics as in 6.11, including the throw when waiting on the unsubmitted current submit with a non-zero timeout.
7. **Deferred work at `submit()`:** run the two-slot ring after waiting. It holds transient-block recycling and `copyTextureToBuffer` callbacks, which run on the render thread, never from Metal completion threads.
8. **Transient memory:**
   - Allocations are valid only for the current submit (`TransientGpuBuffer.isClosed()` flips at submit).
   - CPU blocks recycle at the next submit. Staging and GPU-mapped blocks recycle after GPU completion.
   - 512 KiB blocks, partial-allocation semantics (`minimumAllocation`, `elementSize`), and `upload` padding rules.
   - `map()`/`slice()` on transient buffers must throw.
9. **Render passes:**
   - Always `store`. `clear` when a clear value is present, else `load`.
   - Depth clear uses `(float) depth`. Color clear values are in RGBA order.
   - Viewport = attachment base-mip size, fixed for the pass. Scissor = render area initially, and `disableScissor` returns to the render area.
   - **Render-area clears:** Metal clears whole attachments (see 6.5).
10. **Pipelines:**
    - Two variants: D32 depth or no depth. The no-depth variant exists only when `depthStencilState == null`.
    - Using a depth-state pipeline in a no-depth pass is a caller error. Vulkan binds a null pipeline; throw instead.
    - Blend constants are always zero. Color write mask bits are **reversed** in Metal.
    - `TRIANGLE_FAN` needs index conversion. `LINES` and `QUADS` are triangle lists.
11. **Binding model:**
    - Set 0, binding i = uniform i. Uniforms are re-pushed at draw time when dirty.
    - `setPipeline` clears all uniform slots, and the frontend then re-sets them.
    - Push constants are shared by all stages, size `pushConstantsSize`, offset 0.
    - The index buffer is always bound at offset 0. A null vertex buffer is a no-op.
    - Texel buffers cover the whole buffer. Cache the `MTLTexture` views.
12. **Multi-draw:**
    - Vanilla only uses `drawIndexedIndirect` (terrain MDI), chunked by `maxDrawIndirectDrawCount`, with `firstInstance` per draw and an instance-rate vertex buffer at slot 1. Stride is 20 bytes, which matches Metal's indirect argument layout.
    - Loop `drawIndexedPrimitives(indirectBuffer:offset+20*i)` (or use an ICB) and report MDI support with `maxDrawIndirectDrawCount = Integer.MAX_VALUE`.
    - Report `multiDrawDirect*` as false unless emulated.
13. **Shader draw parameters:** report `shaderDrawParameters = true`, which sets the define `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`. Metal's `[[instance_id]]` includes `base_instance` and `[[vertex_id]]` includes `base_vertex` for indexed draws **[speculation: per MSL spec, verify SPIRV-Cross output]**.
14. **`isExplicitDepthRequired`:** both existing backends set it for `deviceName.startsWith("Apple")`. Mirror it on Apple GPUs. It enables explicit `gl_FragDepth = gl_FragCoord.z` in the OIT shaders.
15. **Formats:** see 10.1. Notes:
    - Only D32 depth is used.
    - 3-component 8/16/32-bit formats are vertex-only on Metal.
    - `D24S8` is unavailable on Apple Silicon.
    - Check RGBA32Float blending for the OIT MAX blend.
16. **Samplers:** the 0.25 max-LOD trick and mip-filter selection (5.4). `maxAnisotropy` is passed as given. Anisotropy is enabled only if > 1.
17. **Surface:**
    - `isSuboptimal()` drives reconfiguration. Return true after a drawable-size mismatch or failure.
    - `configure` should throw `SurfaceException` on an invalid size (e.g. 0x0).
    - Throw `SurfaceException` from `acquireNextTexture()` when iconified (GL behavior), because Vulkan's "ignore" relies on driver errors.
18. **Threading:**
    - `compilePipeline` is called on worker threads, possibly concurrently, so the SPIRV-Cross plus `newRenderPipelineState` work must be thread-safe.
    - SPIR-V buffers are freed after `finishCompile()`.
    - Everything else runs on the render thread with no locks.
19. **Timestamps:** non-blocking `getValues` that returns empty until ready. `writeTimestamp` is allowed both outside and inside passes. Provide `timestampPeriod` and `getTimestampCalibrationOffset()` consistent with `System.nanoTime()`.
20. **Errors:**
    - Vulkan throws `IllegalStateException` for most failures, and `GpuDeviceLossException` on device loss.
    - GL throws `GpuOutOfMemoryException` on OOM. Prefer that when `newBuffer`/`newTexture` returns nil.
    - Map `MTLCommandBufferStatusError` with a device-removed or GPU-hang code to `GpuDeviceLossException` **[recommendation]**.

---

## 15. GL backend: quick contrast table

| Topic | GL (`backend/opengl/*`) | Vulkan |
|---|---|---|
| Window | `SDL_WINDOW_OPENGL` with `SDL_GL_*` attributes: 3.3 core, forward-compatible, `FRAMEBUFFER_SRGB_CAPABLE=1`. A hidden utility window (`0x80020008`: hidden, utility, not-focusable) owns the context. | `SDL_WINDOW_VULKAN` |
| Device init extras | Enables `GL_TEXTURE_CUBE_MAP_SEAMLESS` and `GL_PROGRAM_POINT_SIZE`. `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` if available. | n/a |
| Shaders | SPIR-V decompiled to GLSL 330 by spvc (`GlPipelineRecompiler`): push constants become a UBO, no flip-Y, `SUPPORT_NONZERO_BASE_INSTANCE = shaderDrawParameters` | SPIR-V used directly |
| Push constants | `transientMemory().uploadGpu(value, minUniformOffsetAlignment, UNIFORM)` bound as a UBO | `vkCmdPushConstants` |
| Submit and pacing | `glFenceSync` per submit, 2 in flight, waits for N-1 with no timeout, then `transientMemory.rotate()` | timeline semaphore, 5 s timeout |
| Fence | `GlFence`: the same current-submit rule; `glClientWaitSync` | timeline wait |
| `writeToBuffer` | immediate `bufferSubData` (GL-ordered) | staging plus a copy in CB order |
| `writeToTexture` | `glTexSubImage2D` with `UNPACK_ROW_LENGTH = width`, `UNPACK_ALIGNMENT = format.byteAlignment()`. Cubemap face = `layer % 6`. | staging plus `vkCmdCopyBufferToImage` |
| `copyTextureToBuffer` | `glReadPixels` into a PBO, then `RenderSystem.queueFencedTask(callback)`. The callback runs in `RenderSystem.executePendingTasks()` (start of a later frame) once its fence passes with timeout 0. | callback in the destroy queue, run inside `submit()` of N+1 |
| `copyTextureToTexture` | `glBlitFramebuffer(NEAREST)` | `vkCmdCopyImage` |
| Clears | FBO plus `glClear`. The render-pass clear honors the render area via scissor. All mips for whole-texture clears. | `vkCmdClear*Image` / `loadOp CLEAR` |
| Resource destruction | immediate `glDelete*` | deferred 2 submits |
| Transient memory | `PersistentMapping` or `Fallback` (section 8) | 4 allocators |
| Surface | present modes `{FIFO, IMMEDIATE}` via `SDL_GL_SetSwapInterval`. `isSuboptimal() = false`. Throws on acquire when iconified. Blit via `glBlitFramebuffer` to FB 0 (no flip), then `SDL_GL_SwapWindow` in `present()`. | swapchain (section 11) |
| Pipeline compile | `compilePipeline` decompiles on the worker; `finishCompile` links the GL program (render thread) | full compile on the worker |
| Blend | one representative blend function for all attachments, with per-attachment enable and mask | per attachment |
| Front face | GL default `CCW`, cull `BACK` | `CLOCKWISE`, cull `BACK`, the same visual result because Vulkan's framebuffer Y is the mirror of GL's window Y |
| Depth with no depth state | depth test disabled, depth mask false | the same (`depthTestEnable = false`, `depthWrite = false`) |
| Depth bias | `glPolygonOffset(factor = scaleFactor, units = constant)` | `depthBiasConstantFactor`/`SlopeFactor` |

---

## 16. Open questions / to verify on hardware
- The Metal winding mapping for `frontFace = CLOCKWISE` under SPIRV-Cross flip-Y (expected: `.clockwise`).
- RGBA32Float blendability for the OIT depth-bounds `MAX` blend on M1-and-later and on Intel/AMD Macs.
- The `minUniformOffsetAlignment` to report on Apple GPUs (256 is safe but wastes space in `DynamicGpuDataStorage` padding).
- Whether `CAMetalLayer.colorspace` should be sRGB or nil to visually match the Vulkan (MoltenVK) and GL output.
- MSL `[[instance_id]]` and base-instance semantics as emitted by SPIRV-Cross for `gl_InstanceIndex` when `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` is defined.
- Exact `sourceOffset`/`bytesPerRow` alignment rules for `MTLBlitCommandEncoder` buffer-texture copies on macOS.
