# Hot paths and `DeviceInfo` usage in vanilla 26.3 (input for the Metal backend's feature set)

Scope: how the vanilla 26.3 client actually drives the renderpearl API every frame, every read of
`DeviceInfo.features()`, `limits()`, `hintsAndWorkarounds()` (and the other `DeviceInfo` fields) outside the two
vanilla backends, what each flag changes, and which `DeviceFeatures` / `DeviceLimits` / `HintsAndWorkarounds` Ingot
should advertise on Apple GPUs. Companion to `docs/ARCHITECTURE.md` and `docs/research/integration-points.md`.

Sources and conventions:

- `SRC = scratchpad/mc/src-full/` (whole 26.3 client, Vineflower), `RP = SRC/com/mojang/renderpearl/`.
  `File.java:NNN` line numbers refer to these decompiled files. They are good for orientation only.
- The "Probe" results come from a small Objective-C program run on this Mac (Apple M4 Pro, macOS 27.2). Source:
  `tools/metal-probes/mtlprobe.m` and `tools/metal-probes/idxprobe.m` (build: `clang -fobjc-arc -framework Metal
  -framework Foundation <file>.m -o probe`; results in Appendix B).
- MoltenVK numbers for an M1 Max come from the public Vulkan hardware database
  (vulkan.gpuinfo.org report 39877, MoltenVK 1.2.9). They tell us what Mojang's own Vulkan backend sees on Apple
  Silicon today.
- Anything not verified in source or by probe is marked **[speculative]**. Call-volume numbers are estimates
  derived from the code structure, not measurements, and are marked **[estimate]**.

---

## 0. Summary

### 0.1 Key facts

1. Outside the backends, vanilla reads `DeviceInfo` in about two dozen places (section 2). Only six of them change rendering behaviour
   on a hot path: the terrain multi-draw-indirect gate (`LevelRenderer.<init>`), the indirect split size
   (`ChunkSectionsToRender.DrawIndirect.render`), the chunk staging strategy (`StagingBuffer.create`), UBO block
   padding (`minUniformOffsetAlignment`, 5 readers), and two GLSL macros (`GlslCompiler`).
2. **Vanilla never calls `multiDrawIndexed`, `multiDraw` (either overload) or `drawIndirect`.** The only indirect call is
   `RenderPass.drawIndexedIndirect` from terrain. `multiDrawDirectInterleaved`, `multiDrawDirectSeparate` and
   `maxMultiDrawDirectInterleavedDrawCount` are read only by frontend validation. They have zero effect on vanilla.
3. **`shaderDrawParameters` only defines the GLSL macro `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`, which no
   vanilla shader references.** No vanilla shader uses `gl_DrawID`, `gl_BaseVertex`, `gl_BaseInstance` or
   `gl_InstanceIndex`. Terrain gets per-draw data through an *instance-rate vertex buffer* indexed by `baseInstance`.
4. The terrain MDI path is enabled iff
   `limits.maxDrawIndirectDrawCount > 0 && features.nonZeroFirstInstance && !hints.multiDrawIndirectHasKnownIssues`.
   It does **not** check `features.drawIndirect` or `features.multiDrawIndirect`, but the frontend throws if those are
   false. All four must be consistent.
5. [estimate] With MDI, all terrain is roughly 3 to 10 `drawIndexedIndirect` calls per frame (one per layer per vertex/index
   heap), each covering hundreds to thousands of sub-draws. Without MDI it is one `drawIndexed` plus a UBO rebind plus
   a vertex buffer rebind **per section per layer** (thousands per frame).
6. `writeToBuffer` must be ordered on the GPU timeline. Vanilla rewrites single-buffered GPU buffers every frame
   with it (`DynamicGpuDataStorageNonMapped`, weather vertex buffer, projection and global-settings UBOs) while the
   previous frame may still read them.
7. `map()` is unsynchronized (GL maps with `GL_MAP_UNSYNCHRONIZED_BIT`, Vulkan with `vmaMapMemory`). Callers
   synchronize with `MappableRingBuffer` (3 buffers + `GpuFence`). `map()` is called once per RenderType draw for
   dynamic transforms, so it is on the per-draw path.
8. Fences in both vanilla backends mean "the submit that is current when the fence is created". Waiting with a
   non-zero timeout on a fence of the still-open submit throws `IllegalStateException` in both. Both backends block in
   `submit()` until submit `N-2` is done (2 frames in flight).

### 0.2 Recommended values (Apple Silicon)

| Field | Value | Effect in vanilla |
|---|---|---|
| `features.wireframeFillMode` | `true` | F3+W wireframe; otherwise the optional `WIREFRAME*` pipelines log a compile error at every reload |
| `features.shaderDrawParameters` | `true` | macro only; correct for Metal (`[[instance_id]]` includes `baseInstance`, probe T1) |
| `features.multiDrawDirectInterleaved` | `true` once the native loop exists, else `false` | none (validation only) |
| `features.multiDrawDirectSeparate` | `true` once the native loop exists, else `false` | none (validation only) |
| `features.multiDrawIndirect` | `true` | required by terrain MDI (`drawCount > 1`) |
| `features.drawIndirect` | `true` | required by terrain MDI |
| `features.nonZeroFirstInstance` | `true` | gate for terrain MDI |
| `features.persistentMapping` | `true` | with `writeToBufferIsSlow`, selects persistently mapped chunk staging |
| `limits.maxAnisotropy` | `16` | option clamp, sampler validation |
| `limits.minUniformOffsetAlignment` | `16` (Apple GPU); `256` if the device is Mac2-only | UBO block padding |
| `limits.maxTextureSize` | `16384` | render target and atlas size caps |
| `limits.maxMemoryAllocationSize` | `MTLDevice.maxBufferLength` | only inside `maxTextureSizeForFormat` |
| `limits.maxMultiDrawDirectInterleavedDrawCount` | `Integer.MAX_VALUE` if the feature is on, else `0` | validation only |
| `limits.maxColorAttachments` | `8` | validation; OIT needs 2 |
| `limits.maxDrawIndirectDrawCount` | `Integer.MAX_VALUE` | MDI gate; split size of each `drawIndexedIndirect` |
| `hints.writeToBufferIsSlow` | `true` (see 3.3.1 for the condition) | selects `StagingBuffer.PersistentlyMapped` |
| `hints.anisotropyHasKnownIssues` | `false` | FABULOUS preset picks ANISOTROPIC instead of the 8-tap RGSS shader path |
| `hints.isExplicitDepthRequired` | `true` on Apple GPUs | OIT shaders write `gl_FragDepth = gl_FragCoord.z` |
| `hints.multiDrawIndirectHasKnownIssues` | `false` | MDI gate |
| `isZZeroToOne` | `true` | projection matrices, culling frustum, OIT depth math |
| `backendName` | `"Metal"` | logs, F3, telemetry; GPU warnlist is skipped (it only runs for `"OpenGL"`) |
| `timestampPeriod` | `1.0` (backend returns ns) | `TimerQuery`, Tracy |
| `type` | `INTEGRATED` when `hasUnifiedMemory` | F3 and crash report only |

Justifications are in section 10.

---

## 1. The records (field order matters, constructors are positional)

`RP/api/device/*.java`:

```java
record DeviceInfo(String name, String vendorName, String driverInfo, boolean isZZeroToOne, String backendName,
                  float timestampPeriod, DeviceLimits limits, DeviceFeatures features, Set<String> underlyingExtensions,
                  HintsAndWorkarounds hintsAndWorkarounds, DeviceType type)
record DeviceFeatures(boolean wireframeFillMode, boolean shaderDrawParameters, boolean multiDrawDirectInterleaved,
                      boolean multiDrawDirectSeparate, boolean multiDrawIndirect, boolean drawIndirect,
                      boolean nonZeroFirstInstance, boolean persistentMapping)
record DeviceLimits(int maxAnisotropy, int minUniformOffsetAlignment, int maxTextureSize, long maxMemoryAllocationSize,
                    int maxMultiDrawDirectInterleavedDrawCount, int maxColorAttachments, int maxDrawIndirectDrawCount)
record HintsAndWorkarounds(boolean writeToBufferIsSlow, boolean anisotropyHasKnownIssues,
                           boolean isExplicitDepthRequired, boolean multiDrawIndirectHasKnownIssues)
enum DeviceType { OTHER, INTEGRATED, DISCRETE, VIRTUAL, CPU }
```

`DeviceLimits.maxTextureSizeForFormat(format)` =
`Integer.highestOneBit(min(maxTextureSize, (int) sqrt(maxMemoryAllocationSize / format.blockSize())))`.

Pitfall: eight positional booleans in `DeviceFeatures` and four in `HintsAndWorkarounds`. Build them from named
locals and unit-test the resulting `toString()` (the crash report prints `features().toString()`,
`Minecraft.java:2497`).

Pitfall: `getDeviceInfo()` must return a cached, immutable instance. It is called on hot paths:
`FrontendRenderPass.setUniform(String, GpuBufferSlice)` calls `device.getDeviceInfo().limits()` on **every** UBO
bind (`FrontendRenderPass.java:151`), and `ChunkSectionsToRender.DrawIndirect.render` calls it per layer. Values are
also latched at construction time by several consumers (section 3), so changing them at runtime has no consistent
effect.

Pitfall: `PipelineBuilder` reads `backendDevice.getDeviceInfo()` inside the `FrontendGpuDevice` constructor, and
`GlslCompiler.createBaseShaderOptions` reads `RenderSystem.getDevice().getDeviceInfo()` on pipeline-compile worker
threads. `DeviceInfo` must be complete when `MetalDevice` is constructed and safe to read from any thread.

---

## 2. Every read of `DeviceInfo` outside the backends

Found with `grep -rn 'features()\|limits()\|hintsAndWorkarounds()\|getDeviceInfo\|deviceInfo\.'` plus a grep for each
accessor name across `SRC/net/**` and `SRC/com/mojang/**` (backend packages excluded).

| # | Location | Field(s) | When | Effect |
|---|---|---|---|---|
| 1 | `net/minecraft/client/renderer/LevelRenderer.java:174-181` | `limits.maxDrawIndirectDrawCount`, `features.nonZeroFirstInstance`, `hints.multiDrawIndirectHasKnownIssues` | once per `LevelRenderer` | `multiDrawIndirectAvailable` (terrain MDI path) |
| 2 | `net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java:128-129` | `limits.maxDrawIndirectDrawCount` | per terrain layer per render pass | max sub-draws per `drawIndexedIndirect` |
| 3 | `com/mojang/blaze3d/vertex/StagingBuffer.java:17` | `hints.writeToBufferIsSlow`, `features.persistentMapping` | once per `SectionRenderDispatcher` | chunk staging implementation |
| 4 | `com/mojang/blaze3d/systems/RenderSystem.java:356` | `features.wireframeFillMode` | F3+W key | `isWireframeAvailable()` |
| 5 | `net/minecraft/client/renderer/DynamicGpuDataStorageMapped.java:29` | `limits.minUniformOffsetAlignment` | per storage creation | block size when `usage == USAGE_UNIFORM` exactly |
| 6 | `net/minecraft/client/renderer/DynamicGpuDataStorageNonMapped.java:30` | same | same | same |
| 7 | `com/mojang/blaze3d/platform/Lighting.java:29` | same | once | padding of the 5-entry lighting UBO (`Lighting.Entry`) |
| 8 | `net/minecraft/client/renderer/texture/TextureAtlas.java:102,149` | same | atlas upload | sprite animation UBO padding |
| 9 | `net/minecraft/client/renderer/texture/TextureAtlas.java:59` | `limits.maxTextureSizeForFormat(RGBA8)` | per atlas | atlas size cap |
| 10 | `net/minecraft/client/gui/render/GuiItemAtlas.java:61` | same | GUI item atlas (re)creation | atlas size cap |
| 11 | `net/minecraft/client/Minecraft.java:522` | same | startup | passed to `Window` as max framebuffer size |
| 12 | `com/mojang/blaze3d/pipeline/RenderTarget.java:91` | `limits.maxTextureSize` | target (re)creation | rejects oversize targets |
| 13 | `com/mojang/blaze3d/pipeline/MainTarget.java:101` | `limits.maxTextureSize` | main target creation | falls back to default dimensions |
| 14 | `net/minecraft/client/Options.java:1202` | `limits.maxAnisotropy` | option read | `min(1 << maxAnisotropyBit, maxAnisotropy)` |
| 15 | `net/minecraft/client/GraphicsPreset.java:99` | `hints.anisotropyHasKnownIssues` | applying FABULOUS preset | RGSS vs ANISOTROPIC filtering |
| 16 | `net/minecraft/client/renderer/Projection.java:69` | `isZZeroToOne` | when a projection is dirty | JOML `setPerspective/setOrtho(..., zZeroToOne)` |
| 17 | `net/minecraft/client/Camera.java:188` | `isZZeroToOne` | culling frustum | same for culling |
| 18 | `com/mojang/blaze3d/systems/TimerQuery.java:64` | `timestampPeriod` | GPU utilization overlay / metrics | ticks to ns |
| 19 | `net/minecraft/client/renderer/GpuWarnlistManager.java:141-142` | `backendName`, `name`, `driverInfo`, `vendorName` | startup | warnlist applies only when `backendName.equals("OpenGL")` |
| 20 | `net/minecraft/client/Minecraft.java:518-521, 735, 2489-2497` | `backendName`, `driverInfo`, `name`, `vendorName`, `underlyingExtensions`, `type`, `features()` | startup log, telemetry, crash report | text only |
| 21 | `net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs.java:43-53` | `vendorName`, `name`, `type`, `backendName`, `driverInfo` (first line) | F3 | text only |

Frontend readers (`RP/frontend/**`), which run for every backend:

| # | Location | Field(s) | Effect |
|---|---|---|---|
| 22 | `FrontendRenderPass.java:62-63, 241, 255-264, 281, 307-312, 406, 420-429, 446, 468-473` | all draw-related features and `maxMultiDrawDirectInterleavedDrawCount` | throws `UnsupportedOperationException` / `IllegalArgumentException` (section 4.2) |
| 23 | `FrontendRenderPass.java:151` | `minUniformOffsetAlignment` | `setUniform(name, GpuBufferSlice)` throws if `offset % alignment != 0` |
| 24 | `FrontendCommandEncoder.java:105`, `FrontendGpuDevice.java:73`, `PipelineBuilder.java:57,369`, `GlslCompiler.java:85-91`, `TracyGpuProfiler.java:25` | `maxColorAttachments`, `maxAnisotropy`, `isZZeroToOne`, `shaderDrawParameters`, `wireframeFillMode`, `isExplicitDepthRequired`, `timestampPeriod` | validation, GLSL macros, wireframe rejection, Tracy |

Not read anywhere outside backends: `maxMemoryAllocationSize` except through `maxTextureSizeForFormat`;
`underlyingExtensions` except for logging; `DeviceType` except for display.
`Util.isAppleSiliconMac(renderer)` (`net/minecraft/util/Util.java:601`, `renderer.startsWith("Apple")`) is called only
by the GL and Vulkan backends.

---

## 3. What each flag does, in detail

### 3.1 `DeviceFeatures`

**`wireframeFillMode`**
- `RenderSystem.isWireframeAvailable()` (`RenderSystem.java:356`) is checked by the F3+W debug key
  (`KeyboardHandler.java:134-142`); if false the key prints "Unable to enable WireFrame".
- `PipelineBuilder.compilePipeline` (`PipelineBuilder.java:366-370`) returns `CompiledRenderPipeline.Pending.NULL` and
  logs an **error** for any pipeline with `PolygonMode.WIREFRAME`. `RenderPipelines.WIREFRAME` and
  `WIREFRAME_MULTIDRAW` (`RenderPipelines.java:352-362`) are "optional" pipelines but `ShaderManager` compiles optional
  pipelines at every resource reload (`ShaderManager.java:75-90`), so `false` means two error log lines per reload.
- When enabled, terrain is drawn with the wireframe pipelines (`ChunkSectionsToRender.renderGroup`,
  `ChunkSectionsToRender.java:57-58`).
- GL: always `true`. Vulkan: `fillModeNonSolid` feature set enabled.

**`shaderDrawParameters`**
- Only reader: `PipelineBuilder.java:57` → `new GlslCompiler(isZZeroToOne, shaderDrawParameters)`, which adds
  `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` (`GlslCompiler.java:89-91`).
- `grep` over `SRC/assets/minecraft/shaders/**` finds **no** use of that macro and no use of `gl_InstanceIndex`,
  `gl_DrawID`, `gl_BaseVertex` or `gl_BaseInstance`. Vanilla shaders do use `gl_VertexIndex` (`clouds.vsh`,
  `rendertype_lines.vsh`, `animate_sprite.vsh`, `screenquad.vsh`, `post/rotscale.vsh`).
- Semantics, inferred from the GL backend: `GlPipelineRecompiler` passes the flag to SPIRV-Cross option
  `33554437` (`SPVC_COMPILER_OPTION_GLSL_SUPPORT_NONZERO_BASE_INSTANCE`, `GlPipelineRecompiler.java:67`). So the flag
  means "`gl_InstanceIndex` includes the draw's base instance" (Vulkan semantics). Metal's `[[instance_id]]` includes
  `baseInstance` (probe T1), so `true` is the accurate answer for Metal.
- GL: `GL_ARB_shader_draw_parameters`. Vulkan: always `true`.

**`multiDrawDirectInterleaved`** (plus `limits.maxMultiDrawDirectInterleavedDrawCount`)
- Guards `RenderPass.multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount)`
  and `RenderPass.multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount)`
  (`FrontendRenderPass.java:250-273, 415-438`). The frontend also checks `drawCount <= maxMultiDrawDirectInterleavedDrawCount`
  and `firstInstance == 0 || nonZeroFirstInstance`.
- Layout (from the Vulkan backend, `VulkanRenderPass.multiDrawIndexed/multiDraw`, which hands the IntBuffer address
  straight to `vkCmdDrawMultiIndexedEXT` / `vkCmdDrawMultiEXT`):
  indexed = 3 ints per draw `{firstIndex, indexCount, vertexOffset}` (`VkMultiDrawIndexedInfoEXT`, stride 12, frontend
  checks `remaining() >= drawCount * 3`); non-indexed = 2 ints `{firstVertex, vertexCount}` (`VkMultiDrawInfoEXT`, stride 8).
  The data starts at the buffer's current `position()`.
- **No vanilla caller.**
- GL: `false`, limit 0. Vulkan: `VK_EXT_multi_draw` feature set; limit `maxMultiDrawCount`.

**`multiDrawDirectSeparate`**
- Guards `multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount)`
  and `multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount)` (`FrontendRenderPass.java:276-299, 441-460`).
- Layout (from the GL backend, `GlCommandEncoder.executeDraws`, `GlCommandEncoder.java:485-507`):
  `firstIndexOffsets` are **byte offsets into the index buffer** stored as pointers (the `indices` argument of
  `glMultiDrawElementsBaseVertex`), not index counts. `vertexOffsets` are base vertices. For the non-indexed overload
  the GL backend passes `firstVertices` and `vertexCounts` to `glMultiDrawArrays`.
- **No vanilla caller.**
- GL: `true`. Vulkan: `false`.

**`drawIndirect`, `multiDrawIndirect`**
- Guard `drawIndexedIndirect(GpuBufferSlice commands, int drawCount)` and `drawIndirect(...)`
  (`FrontendRenderPass.java:302-329, 463-490`):
  ```java
  if (!deviceFeatures.drawIndirect()) throw new UnsupportedOperationException(...);
  if (drawCount > 1 && !deviceFeatures.multiDrawIndirect()) throw new UnsupportedOperationException(...);
  if ((commands.buffer().usage() & 512) == 0) throw ...;            // USAGE_INDIRECT_PARAMETERS
  if (commands.length() < drawCount * VkDrawIndexedIndirectCommand.SIZEOF /* 20 */) throw ...;
  if (commands.offset() % 4 != 0) throw ...;
  ```
  Non-indexed uses `VkDrawIndirectCommand.SIZEOF` = 16.
- Command layouts are byte-identical to Metal's: `VkDrawIndexedIndirectCommand {indexCount, instanceCount, firstIndex,
  int vertexOffset, firstInstance}` = `MTLDrawIndexedPrimitivesIndirectArguments {indexCount, instanceCount, indexStart,
  baseVertex, baseInstance}`; `VkDrawIndirectCommand` = `MTLDrawPrimitivesIndirectArguments`. Stride is tightly packed
  (20 / 16 bytes); Metal accepts any 4-byte aligned `indirectBufferOffset`.
- **Only vanilla caller: terrain** (`ChunkSectionsToRender.java:148`). `drawIndirect` (non-indexed) has no caller.
- Pitfall: `LevelRenderer`'s gate does not look at these two flags (item 4 of 0.1).
- GL: `GL_ARB_draw_indirect` / `GL_ARB_multi_draw_indirect`. Vulkan: both `true`.

**`nonZeroFirstInstance`**
- Gate for terrain MDI (`LevelRenderer.java:179`), because the indirect terrain path passes the section index as
  `baseInstance` (section 6.1.4).
- Frontend: `drawIndexed`, `draw`, `multiDrawIndexed(IntBuffer...)`, `multiDraw(IntBuffer...)` throw if
  `firstInstance != 0` and the feature is off. Vanilla direct draws always pass `firstInstance = 0` and
  `instanceCount = 1`.
- GL: `GL_ARB_base_instance`. Vulkan: `true`.

**`persistentMapping`**
- Only reader: `StagingBuffer.create` (`StagingBuffer.java:16-20`), and only together with
  `hints.writeToBufferIsSlow` (section 3.3.1).
- Meaning, from `StagingBuffer.PersistentlyMapped`: a buffer may stay mapped (`MappedView` open) while GPU copy
  commands that read it are recorded and executed.
- GL: `GL_ARB_buffer_storage` (immutable storage, persistent+coherent map). Vulkan: `true`.

### 3.2 `DeviceLimits`

**`maxDrawIndirectDrawCount`**
- MDI gate (`> 0`) and split size in `ChunkSectionsToRender.DrawIndirect.render`:
  ```java
  while (remainingDrawCount > 0) {
     int passDrawCount = Integer.min(remainingDrawCount, maxDrawIndirectDrawCount);
     long length = passDrawCount * 20L;
     renderPass.drawIndexedIndirect(buffer.slice(startOffset, length), passDrawCount);
     ...
  }
  ```
  A value of 1 is legal and produces one indirect call per section (with `multiDrawIndirect=false`, that is the only
  legal value above 0).
- GL: `Integer.MAX_VALUE` with `GL_ARB_multi_draw_indirect`, `1` with only `GL_ARB_draw_indirect`, else `0`
  (`GlHeuristics.java:83-85`). Vulkan: `limits.maxDrawIndirectCount` clamped to `Integer.MAX_VALUE`. MoltenVK on M1 Max
  reports `1073741824`.

**`minUniformOffsetAlignment`**
- Block padding: `DynamicGpuDataStorageMapped` and `DynamicGpuDataStorageNonMapped` use
  `blockSize = usage == 128 ? roundToward(dataSize, alignment) : dataSize` (note `==`, so only storages whose usage is
  exactly `USAGE_UNIFORM` are padded; the indirect command storage (usage 512) and the instanced section storage
  (usage 32) are tightly packed). `Lighting` and `TextureAtlas` pad their UBO arrays the same way.
- Validation: `FrontendRenderPass.setUniform(String, GpuBufferSlice)` throws if `offset % alignment != 0`.
- `Mth.roundToward` is a general round-up (`positiveCeilDiv * multiple`), so non-powers of two would work, but use a
  power of two.
- Std140 sizes involved (`Std140SizeCalculator.get()` does **not** round the total up to 16):
  `TRANSFORM_UBO_SIZE` = 156, `TERRAIN_TRANSFORM_UBO_SIZE` = 72, `CHUNK_SECTION_UBO_SIZE` = 16, `Lighting.UBO_SIZE` = 28,
  `PROJECTION_MATRIX_UBO_SIZE` = 64, `CloudRenderer.UBO_SIZE` = 44, `GlobalSettingsUniform.UBO_SIZE` = 48.
  The MSL structs SPIRV-Cross generates for 156- and 72-byte std140 blocks are 160 and 80 bytes (16-byte struct
  alignment). With an alignment below 16 the last block in a ring buffer is shorter than the MSL struct
  **[speculative: whether Metal validation flags this; reads past the end would only touch padding]**. 16 avoids it.
- GL: `GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT`. Vulkan: `minUniformBufferOffsetAlignment` (MoltenVK on M1 Max: 16).
  Metal Feature Set Tables: 4 bytes for Apple GPU families, 256 bytes for Mac2. Probe T4: a `constant` buffer bound at
  offsets 4 and 16 on M4 Pro reads correctly and Metal API validation (`MTL_DEBUG_LAYER=1`) reports nothing.

**`maxTextureSize`, `maxMemoryAllocationSize`**
- See items 9 to 13 of section 2. `maxTextureSizeForFormat(RGBA8)` caps the block atlas, the GUI item atlas and the
  window framebuffer (`Window` constructor). Apple GPUs: 16384. `maxBufferLength` on M4 Pro: 14302248960 (probe), so
  the sqrt term never binds.

**`maxColorAttachments`**
- Validation in `FrontendCommandEncoder.createRenderPass` (`FrontendCommandEncoder.java:105-111`). The widest vanilla
  pass is the OIT transmittance pass: `OIT_TRANSMITTANCE_TARGET_COUNT` = 2 RGBA16F color attachments + depth
  (`OitRenderPassProvider.createTransmittancePass`). Metal: 8.

**`maxAnisotropy`**
- `Options.maxAnisotropyValue()` and `FrontendGpuDevice.createSampler` (`maxAnisotropy` must be in `[1, limit]`).
  Metal: 16.

**`maxMultiDrawDirectInterleavedDrawCount`**: validation only (3.1).

### 3.3 `HintsAndWorkarounds`

#### 3.3.1 `writeToBufferIsSlow` (with `features.persistentMapping`)

`StagingBuffer.create("Chunk", device, 102760448)` (`SectionRenderDispatcher.java:69`) picks:

| Condition | Class | Where chunk bytes are copied |
|---|---|---|
| `writeToBufferIsSlow && persistentMapping` | `StagingBuffer.PersistentlyMapped` | worker thread: `memcpy` into a mapped GPU buffer (`MappableRingBuffer`, usage 18 = `MAP_WRITE\|COPY_SRC`, 3 x 49 MiB). Render thread: one `copyToBuffer` per allocation. |
| otherwise | `StagingBuffer.Cpu` | worker thread: `memcpy` into a 98 MiB `memAlloc` buffer. Render thread: one `writeToBuffer(dst slice, cpu slice)` per allocation. |

The GL backend sets it only for GL-on-D3D12; Vulkan sets it `false`. It has no other reader.

For Metal, `Cpu` means every chunk byte is copied twice on the render thread side: once by the backend's
`writeToBuffer` into its staging ring, then by a blit. `PersistentlyMapped` moves the CPU copy entirely to the
worker threads and leaves only blit recording on the render thread. Recommendation: `true` (with
`persistentMapping = true`), **provided** the fence wait described in 8.3 cannot deadlock or throw.

#### 3.3.2 `anisotropyHasKnownIssues`

Only read when the user selects the FABULOUS preset (`GraphicsPreset.java:99-103`): `true` → `TextureFilteringMethod.RGSS`,
`false` → `ANISOTROPIC`. RGSS is a shader path (`Globals.UseRgss`, `include/texture_sampling.glsl`, `sampleRGSS`) that
takes 8 texture samples (4 offsets x 2 mip levels) per fragment. Hardware anisotropic filtering is the cheaper choice
on a fragment-bound tiler **[speculative: not measured]**. GL sets it for AMD; Vulkan `false`. Recommend `false`.

#### 3.3.3 `isExplicitDepthRequired`

`GlslCompiler.createBaseShaderOptions` defines `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`. Used in
`include/oit_add_transmittance.glsl:46-48` and `include/oit_sample.glsl:101-103`:

```glsl
#ifdef RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE
gl_FragDepth = gl_FragCoord.z;
#endif
```

So it only affects the OIT transmittance and accumulate fragment shaders (Improved Transparency / FABULOUS). Writing
depth disables early depth testing for those draws. Both vanilla backends set it to `Util.isAppleSiliconMac(name)`
(`GlHeuristics.java:108-110`, `VulkanDevice.java:116`), i.e. Mojang needed it on Apple GPUs through both Apple's GL and
MoltenVK. The root cause is not documented in the source **[speculative: depth-test inconsistencies between the OIT
passes, which compare translucent fragments against the same depth buffer with `GREATER_THAN_OR_EQUAL` and no depth
write]**. Recommend `true` on Apple GPUs to match the configuration Mojang tests; revisit only with an A/B image
comparison of FABULOUS water/glass.

#### 3.3.4 `multiDrawIndirectHasKnownIssues`

MDI gate only. GL sets it for Intel non-Arc. Recommend `false`.

### 3.4 Other `DeviceInfo` fields

- `isZZeroToOne`: `Projection.getMatrix` and `Camera.createProjectionMatrixForCulling` build reverse-Z projections
  (near/far swapped, depth cleared to 0.0, `GREATER` tests) for `[0,1]` when true. Also defines
  `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` (`oit_common.glsl:17`). Metal clip space is `[0,1]`: `true`.
- `timestampPeriod`: `TimerQuery.getStatus` multiplies tick deltas by it. Report ns and `1.0`. Probe: the M4 Pro
  supports counter sampling only at stage boundaries (`MTLCounterSamplingPointAtStageBoundary` yes, draw and blit
  boundary no), which matters for `writeTimestamp` design, not for this flag.
- `backendName`: `GpuWarnlistManager` skips the warnlist for anything but `"OpenGL"`. Everything else is display.
- `name`: keep Metal's `MTLDevice.name` (`"Apple M4 Pro"`). It begins with `"Apple"`, which is what
  `Util.isAppleSiliconMac` tests (mods may call it).

### 3.5 What the vanilla backends report (reference)

| Field | OpenGL (`GlHeuristics.createDeviceInfo`) | Vulkan (`VulkanDevice.<init>`) |
|---|---|---|
| wireframeFillMode | `true` | wireframe feature set |
| shaderDrawParameters | `GL_ARB_shader_draw_parameters` | `true` |
| multiDrawDirectInterleaved | `false` | `VK_EXT_multi_draw` |
| multiDrawDirectSeparate | `true` | `false` |
| multiDrawIndirect | `GL_ARB_multi_draw_indirect` | `true` |
| drawIndirect | `GL_ARB_draw_indirect` | `true` |
| nonZeroFirstInstance | `GL_ARB_base_instance` | `true` |
| persistentMapping | `GL_ARB_buffer_storage` | `true` |
| maxMultiDrawDirectInterleavedDrawCount | 0 | `maxMultiDrawCount` |
| maxDrawIndirectDrawCount | MAX / 1 / 0 | `maxDrawIndirectCount` |
| minUniformOffsetAlignment | GL query | `minUniformBufferOffsetAlignment` |
| maxMemoryAllocationSize | `Long.MAX_VALUE` | `maxMemoryAllocationSize` |
| writeToBufferIsSlow | GL on D3D12 | `false` |
| anisotropyHasKnownIssues | AMD | `false` |
| isExplicitDepthRequired | `isAppleSiliconMac(renderer)` | `isAppleSiliconMac(deviceName)` |
| multiDrawIndirectHasKnownIssues | Intel non-Arc | `false` |
| isZZeroToOne | `GL_ARB_clip_control` | `true` |
| timestampPeriod | 1.0 | `limits.timestampPeriod` |

On macOS, Apple's OpenGL is 4.1 core. Going by GL core version history (not checked on a device), it lacks
`ARB_base_instance` (4.2), `ARB_buffer_storage` (4.4), `ARB_clip_control` (4.5) and `ARB_multi_draw_indirect` (4.3).
Mac GL users therefore get the per-section terrain path, the `Cpu` chunk staging path and `[-1,1]` depth. A Metal
backend with the recommended values gets the MDI path, persistent staging and `[0,1]` reverse-Z.

---

## 4. The frontend layer: what reaches the backend, and what it costs

### 4.1 General

- `FrontendGpuDevice.STRICT_VALIDATION = SharedConstants.IS_RUNNING_IN_IDE` (`FrontendGpuDevice.java:36`).
  `IS_RUNNING_IN_IDE` is a non-final static that vanilla never sets, so strict validation (per-draw
  `checkCanBeUsed`, missing-uniform and usage-flag checks) is normally off. Benchmarks must make sure it stays off.
- `FrontendGpuDevice.createCommandEncoder()` returns the same `FrontendCommandEncoder` every time; vanilla calls
  `createCommandEncoder()` freely (dozens of times per frame). It is free.
- Only one render pass may be open. `writeToBuffer`, `copyToBuffer`, `clear*`, `writeToTexture`, `copy*`, and
  `createFence` all throw if a render pass is open (`FrontendCommandEncoder.java`). Uploads are therefore always
  between passes, which maps onto Metal blit encoders between render encoders.
- `writeToBuffer` validation: buffer needs `USAGE_COPY_DST` (8); `data.remaining() <= slice.length`. Writing fewer
  bytes than the slice is legal and happens (`Lighting.updateBuffer` writes 28 bytes into a padded slice).
- `copyToBuffer` validation: source `USAGE_COPY_SRC` (16), target `USAGE_COPY_DST` (8), equal lengths.
- `GpuBuffer.map()` is **not** validated by the frontend. It goes straight to the backend buffer.

### 4.2 `FrontendRenderPass` per-call behaviour (`RP/frontend/FrontendRenderPass.java`)

| Frontend call | Backend calls it makes | Notes |
|---|---|---|
| `setPipeline(p)` (104-128) | `backend.setPipeline(p)`, then **`backend.setUniform(index, value)` for every uniform previously set on the pass that the new pipeline uses** (`this.uniforms.forEach(this::setUniform)`, a `HashMap<String,Object>` walk) | resets the push-constant flag; checks color target formats against attachments |
| `setUniform(name, view, sampler)` (131-142) | allocates a `TextureViewAndSampler`, `backend.setUniform(index, pair)` if the bound pipeline uses `name` | stored by name for later pipelines |
| `setUniform(name, GpuBuffer)` | `buffer.slice()` then the slice overload | allocates a `GpuBufferSlice` |
| `setUniform(name, GpuBufferSlice)` (150-157) | `getDeviceInfo().limits()` alignment check, then `backend.setUniform` | |
| `setVertexBuffer(slot, slice)` (212-227) | `backend.setVertexBuffer` | slot `< 16`; needs `USAGE_VERTEX` |
| `setIndexBuffer(buffer, type)` | `backend.setIndexBuffer` | whole buffer, offset 0; `firstIndex` carries the offset |
| `drawIndexed` / `draw` (236-247, 401-412) | `backend.drawIndexed` / `backend.draw` | firstInstance check |
| `drawMultipleIndexed(draws, defIdx, defType, dynamicUniforms, arg)` (332-398) | **per draw**: the draw's uploader lambda (calls `setUniform` and/or `pushConstants`), `setVertexBuffer(slot, draw.vertexBuffer().slice())` (allocates), `setIndexBuffer` only if buffer/type changed, `backend.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0)` | no backend multi-draw entry exists for this; it is a Java loop |
| `drawIndexedIndirect` (302-329) | one `backend.drawIndexedIndirect(slice, drawCount)` | |
| `pushDebugGroup(Supplier)` / `popDebugGroup` | forwarded unconditionally | called per terrain layer, per feature group, per RenderType draw |
| `enableScissor` / `disableScissor` | forwarded | scissor must be inside `renderArea` |
| `pushConstants` | forwarded | **no vanilla caller** (`grep pushConstants(` outside renderpearl finds nothing) |

Consequences for the backend's per-draw path:

1. `RenderPassBackend.setUniform(int, Object)` is called far more often than draws (every `setPipeline` replays all
   stored uniforms). It must be a Java-side array store plus a dirty bit, with Metal binding deferred to the next draw
   and skipped when the bound handle and offset are unchanged. When only the offset changes on the same `MTLBuffer`
   (dynamic transforms, chunk section UBOs), use `setVertexBufferOffset:atIndex:` / `setFragmentBufferOffset:atIndex:`.
2. `setVertexBuffer` / `setIndexBuffer` repeat the same buffer on every draw in `drawMultipleIndexed`; dedupe in Java.
3. `pushDebugGroup` must not call `Supplier.get()` (string concatenation) or cross FFM unless debug labels are on.
4. `setPipeline` is called once per draw in the feature and GUI paths. Cache the `MTLRenderPipelineState` and
   depth-stencil state per (pipeline, attachment formats) and skip redundant binds.

---

## 5. Frame structure

### 5.1 Main loop (`net/minecraft/client/Minecraft.java:1240-1333`)

```
windowSurface.configure(...)          (only when needed)
windowSurface.acquireNextTexture()
textureManager.tick()                  (only when >= 1 game tick elapsed; Minecraft.java:1177) -> atlas animation passes
RenderSystem.executePendingTasks()     polls queued fences with awaitCompletion(0)
gameRenderer.render()                  everything below
windowSurface.blitFromTexture(encoder, mainRenderTarget color)
encoder.submit()                       ONE submit per frame
windowSurface.present()                after submit
RenderSystem.getDynamicUniforms().reset()   rotates 2 to 4 MappableRingBuffers -> createFence() AFTER submit
levelRenderer.endFrame()               cloudRenderer.endFrame() -> cloud UBO ring rotate -> createFence()
```

### 5.2 `GameRenderer.render()` (`GameRenderer.java:465-513`)

1. `clearColorAndDepthTextures(main color, main depth)`.
2. `GlobalSettingsUniform.update` → `writeToBuffer` into a single 48-byte UBO (usage 136).
3. `lightmap.render` (only when `needsUpdate`): map the lightmap UBO ring, one 16x16 render pass, `draw(3,1,0,0)`,
   ring `rotate()` (fence mid-frame).
4. `renderLevel()`: `ProjectionMatrixBuffer.getBuffer(Matrix4f)` (always a `writeToBuffer` into a single 64-byte UBO),
   `FogRenderer.updateBuffer` (map fog ring), `LevelRenderer.render(...)` (5.3), then `render3dHud`:
   a second projection `writeToBuffer` (only if changed), `clearDepthTexture`, "Item in hand" pass (its own
   `FeatureRenderDispatcher.prepareFrame`, so another staged upload), "Screen effects" pass (another prepareFrame),
   optional 3D crosshair and depth integration passes.
5. Entity outline blit, post effects (`PostChain.process`, one render pass + `draw(3,1,0,0)` per `PostPass`, each
   mapping its own `SamplerInfo` ring and calling `rotate()` mid-frame).
6. `fogRenderer.endFrame()` (fog ring rotate), `clearDepthTexture(main depth)`.
7. `guiRenderer.render()` (6.6), `renderBuffers.endFrame()` (`StagedVertexBuffer` pool fences).

### 5.3 `LevelRenderer.render` (`LevelRenderer.java:184-324`)

Builds a frame graph and executes it, then (after the graph) runs section compilation and
`sectionRenderDispatcher.uploadTerrainBuffersToGpu()` (`LevelRenderer.java:296-310`). So terrain uploads recorded in
frame N become visible to frame N+1's draws.

Passes, classic transparency (`useImprovedTransparency() == false`):

| Pass | Contents |
|---|---|
| "clear" graph pass | `clearColorAndDepthTextures(main)` (second clear of the frame, with the fog color) |
| "Sky" (`SkyRenderer.render`) | about 6 to 10 small draws (`draw(10,1,0,0)` triangle fans, quads, stars) |
| "Main" (`LevelRenderer.java:443-457`) | solid terrain, solid features, translucent features, translucent terrain, after-terrain features, clouds, weather, world border |
| "Outline" + entity_outline post chain | only with glowing entities |
| "See through features", "Always on top features" (+ depth integrate) | only when present |

Improved transparency (FABULOUS) adds, per frame: frame-graph target clears (depth bounds RGBA32F cleared to
`-Float.MAX_VALUE`, 2 transmittance RGBA16F, accumulate RGBA16F, extra D32F targets), 0 or 2 "Water mask" passes, and
for each `OitStage` (DEPTH_BOUNDS, TRANSMITTANCE, ACCUMULATE): a "Terrain" pass (translucent terrain replayed), a
"Features, World Border, Weather" pass, a "Clouds" pass; after DEPTH_BOUNDS an "OIT cull Depth Bounds" pass; finally
"OIT Composite". That is about 12 to 14 extra render passes per frame. The translucent terrain indirect draws are
replayed three times from the same command buffer slices.

[estimate] A typical in-world frame records 10 to 15 render passes (classic) or 25 to 30 (FABULOUS), 4 to 8 standalone
clears, 5 to 10 small `writeToBuffer`s, and between 0 and several hundred chunk upload copies.

---

## 6. Hot paths

### 6.1 Terrain

#### 6.1.1 GPU storage (`SectionRenderDispatcher`, `UberGpuBuffer`, `TlsfAllocator`)

`SectionRenderDispatcher.<init>` (`SectionRenderDispatcher.java:65-75`), per `ChunkSectionLayer` (SOLID, CUTOUT,
TRANSLUCENT):
- vertex `UberGpuBuffer`: heaps of 128 MiB (134217728), usage `USAGE_VERTEX | COPY_DST | COPY_SRC` (32|8|16 = 56),
  allocation alignment = vertex size (`DefaultVertexFormat.BLOCK`).
- index `UberGpuBuffer`: heaps of 32 MiB (33554432), usage `USAGE_INDEX | COPY_DST | COPY_SRC` (64|8|16 = 88),
  alignment 8.
- New heaps are created on demand (`UberGpuBufferHeap`, `UberGpuBuffer.java:191-194`) and closed when completely
  free. Buffers are never resized.

`TlsfAllocator.allocate(size, align)` (`TlsfAllocator.java:137-172`) is CPU-only bookkeeping (32-byte granularity,
O(1)). The GPU only sees heap `GpuBuffer`s plus offsets.

Hazard: `UberGpuBuffer.uploadStagedAllocations` (`UberGpuBuffer.java:57-126`) first frees the old allocation of every
key that has new data, then allocates. A range freed this frame can be reallocated and overwritten (by a GPU copy) in
the same frame, while frames N-1 and N-2 may still be reading the old geometry from it. Correctness relies on the
copy being ordered after earlier GPU reads of that buffer, which Metal's default (tracked) hazard tracking gives at
whole-buffer granularity. With untracked resources the backend would need explicit fences between render encoders
that read a heap and blit encoders that write it.

#### 6.1.2 Uploads

- Worker threads (`SectionRenderDispatcher.RenderSection.addSectionBuffersToUberBuffer`, `:368-401`, under
  `copyLock`) call `UberGpuBuffer.addAllocation` → `StagingBuffer.tryAppend` (`StagingBuffer.java:22-38`): a `memcpy` into
  the staging write buffer. If staging is full, the worker spins (`Thread.onSpinWait`); on the render thread
  (`compileSync`, "nearby" sections) it calls `uploadTerrainBuffersToGpu()` immediately.
- Render thread, once per frame after the frame graph (`SectionRenderDispatcher.uploadTerrainBuffersToGpu`,
  `:140-152`): for each layer, `vertexBuffer.uploadStagedAllocations` then `indexBuffer.uploadStagedAllocations`;
  stops early if a heap was created or destroyed. Each staged allocation becomes **one**
  `StagingBuffer.Uploader.copyTo` → either `encoder.writeToBuffer(heapSlice, cpuSlice)` (`Cpu`) or
  `encoder.copyToBuffer(stagingSlice, heapSlice)` (`PersistentlyMapped`).
- `Uploader.close()` → `tryClearAndRotate()`: when every staged handle has been consumed, reset the write offset and
  (PersistentlyMapped) `rotateBuffer()`: close the mapped view, `MappableRingBuffer.rotate()` (creates a fence),
  `currentBuffer()` (waits, with `awaitCompletion(-1)`, for the next buffer's fence), map it again.
- Translucent re-sorts upload index buffers only (no vertex data).
- Volume [estimate]: 0 per frame when standing still; tens to hundreds of copies per frame while moving or loading
  (2 per section-layer: vertex + index where needed), sizes from about 1 KiB to a few hundred KiB.
- Backend implications: batch consecutive copies into one blit encoder; never cross FFM per copy if avoidable (queue
  copies in a Java-side list and flush them in one downcall when the next render pass starts or at submit).

#### 6.1.3 Draw preparation (`LevelRenderer.extractSectionDrawGroups`, `:716-803`)

Per visible section, per layer that has geometry:
- `getRenderSectionSlice` (2 `HashMap` lookups into the uber buffers), one `ChunkSectionInfo(x, y, z, visibility)` per
  section (16 bytes std140: `ivec3 ChunkPosition; float ChunkVisibility`), one
  `DynamicGpuData.IndexedDraw(indexCount, 1, firstIndex, baseVertex, sectionInfoIndex)` per section-layer.
- `baseVertex = vertexBufferOffset / vertexSize`; `firstIndex = indexBufferOffset / indexType.bytes` for sections with
  their own (sorted translucent) index buffer, else 0 with the shared sequential quad index buffer
  (`RenderSystem.getSequentialBuffer(QUADS)`, `IndexType.least(vertexCount)`, so usually `SHORT`).
- Draws are grouped by `(vertex heap, index heap, index type)` (`ChunkDrawGroup`). With one heap per layer that is
  one group per layer. For translucent with classic transparency (`respectTranslucentOrder`), groups are only merged
  with the immediately previous group, then reversed for back-to-front order.

#### 6.1.4 MDI path (`prepareChunkRendersIndirect`, `:893-958`; `ChunkSectionsToRender.DrawIndirect`, `:101-155`)

CPU, once per frame:
- `DynamicGpuData.writeChunkSectionCommands(batchedDraws)` → `DynamicGpuDataStorageMapped("Chunk Sections Command
  Buffer", 20, 512, 2)`: a `MappableRingBuffer` with usage `USAGE_INDIRECT_PARAMETERS | MAP_WRITE` (514), one `map()`
  of the whole range, 20 bytes per sub-draw written with `ByteBuffer.putInt`. Returns one slice per group.
- `DynamicGpuData.writeChunkSectionsInstanced(sectionInfos)` → `DynamicGpuDataStorageNonMapped("Chunk Sections
  Instanced", 16, 32, 2)`: a **single** GPU buffer, usage `USAGE_VERTEX | COPY_DST` (40), filled with one
  `writeToBuffer` per frame. The same buffer region is rewritten every frame (8.2).
- `writeTerrainTransform` (map of the terrain UBO ring, 72 bytes, padded).

Per render pass (`renderLayers`, `ChunkSectionsToRender.java:78-99`):

```
setUniform("TerrainUniform", terrainTransformUbo)
setUniform("Sampler0", blockAtlas, chunkLayerSampler)
setUniform("Sampler2", lightmap, clampLinear)
for layer in group:                                  # OPAQUE = SOLID, CUTOUT; TRANSLUCENT = TRANSLUCENT
  pushDebugGroup("Terrain layer: ...")
  setPipeline(layer MULTIDRAW pipeline)              # replays Projection/Fog/Globals/Lighting/Terrain/Sampler*
  if (!groups.isEmpty()) setVertexBuffer(1, chunkSectionInfos)   # instance-rate, stride 16
  for group:
    setVertexBuffer(0, group.vertexBuffer)
    setIndexBuffer(group.indexBuffer or sequential quad buffer, type)
    drawIndexedIndirect(commands.slice(...), n)      # split by maxDrawIndirectDrawCount
  popDebugGroup()
```

The MULTIDRAW pipelines (`RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET`, `RenderPipelines.java:83-90`) add
`withVertexBinding(1, DefaultVertexFormat.CHUNK_DATA_INSTANCED)` (`VertexFormat.builder(1)` = step rate 1, attributes
`ChunkPosition RGB32_SINT`, `ChunkVisibility R32_FLOAT`) and `#define MULTIDRAW_TERRAIN`, which switches
`core/terrain.vsh` from the `ChunkSection` UBO to vertex inputs at locations 4 and 5. The section index reaches the
shader only through `baseInstance` selecting the instance-rate element. Probe T1 confirms that Metal's per-instance
vertex fetch and `[[instance_id]]` both include `baseInstance` in indirect draws.

Classic: the Main pass runs OPAQUE then TRANSLUCENT. FABULOUS: the Main ("Solid") pass runs OPAQUE; each of the three
OIT "Terrain" passes runs TRANSLUCENT again with OIT pipelines.

Backend calls per frame [estimate]: about 3 to 5 `setPipeline`, 3 to 5 instance-buffer binds, 3 to 10
`drawIndexedIndirect` (classic), plus 2 x 3 more for FABULOUS. Sub-draws: one per section-layer, typically several
thousand at render distance 12 and above. The cost that remains is the backend's expansion of each
`drawIndexedIndirect` into Metal draws (section 10.3).

#### 6.1.5 Fallback path (`prepareChunkRenders`, `:805-856`; `ChunkSectionsToRender.DrawSeparate`, `:157-187`)

Used when the MDI gate is false, or when the user toggles F3+M (`KeyboardHandler.java:100-103`,
`LevelExtractor.java:231` → `levelRenderState.shouldUseMultiDrawIndirectForTerrain`).
- `writeChunkSections(infos...)` → `DynamicGpuDataStorageMapped("Chunk Sections UBO", 16, 128, 2)`: one padded UBO block
  **per section** (16 bytes with alignment 16, 256 bytes with alignment 256).
- One `RenderPass.Draw` record and one capturing lambda allocated per section-layer.
- `drawMultipleIndexed(draws, ..., List.of("ChunkSection"), chunkSectionInfos)`: per section-layer the frontend calls
  the lambda (`setUniform("ChunkSection", slice)` with alignment check and `HashMap.put`), `setVertexBuffer` (with a new
  `GpuBufferSlice`), maybe `setIndexBuffer`, and `backend.drawIndexed`.
- About 3 to 4 backend calls per section-layer, i.e. tens of thousands per frame [estimate].

Both paths must work: F3+M switches at runtime, and `DynamicGpuData.writeChunkSections*` closes and recreates the
section storage when the path changes (`DynamicGpuData.java:87-111`).

### 6.2 Dynamic uniforms and ring buffers

`RenderSystem.getDynamicUniforms()` → `DynamicGpuData` (`net/minecraft/client/renderer/DynamicGpuData.java`), created
by `RenderSystem.initRenderer`, `reset()` once per frame after submit.

| Storage | Class, data size, usage | Writers | Frequency |
|---|---|---|---|
| "Dynamic Transforms UBO" | Mapped, 156 → padded, usage 128 (+2) | `RenderType.prepare` (once per RenderType draw), sky (about 8), clouds, weather, world border, particles, GUI, cube map, crosshair | per draw on feature paths |
| "Terrain UBO" | Mapped, 72 → padded, usage 128 | `LevelRenderer.prepareChunkRenders*` | once per frame |
| "Chunk Sections UBO" | Mapped, 16 → padded, usage 128 | DrawSeparate path | once per frame, one block per section |
| "Chunk Sections Instanced" | **NonMapped**, 16, usage 32 (+8) | MDI path | once per frame |
| "Chunk Sections Command Buffer" | Mapped, 20, usage 512 (+2) | MDI path | once per frame |

`DynamicGpuDataStorageMapped` (`DynamicGpuDataStorageMapped.java`):
- Wraps a `MappableRingBuffer` (3 `GpuBuffer`s of `blockSize * capacity`, usage `usage | MAP_WRITE`).
- `writeData(T)` (`:59-81`): if equal to the previous value (record `equals`), returns the previous slice; otherwise
  `currentBuffer().slice(offset, blockSize).map(false, true)`, writes with `Std140Builder`/`ByteBuffer.put*`, closes the
  view. **One `map()` + close per call.**
- Capacity starts at 2 and doubles (logged at INFO) by allocating a new `MappableRingBuffer` (3 `createBuffer`s);
  the old one is closed at `endFrame()`, i.e. while the GPU may still use it (deferred destruction required).
- `endFrame()` → `ringBuffer.rotate()` → `createFence()` (after submit, so the fence refers to the next submit in
  vanilla's semantics).

`DynamicGpuDataStorageNonMapped` (`DynamicGpuDataStorageNonMapped.java`): one GPU buffer (usage `usage | COPY_DST`),
a CPU mirror, and one `writeToBuffer` per batch. Old buffers are closed at `endFrame()` after a resize. **The GPU
buffer is not multi-buffered**: frame N+1 writes the same bytes frame N's draws read.

`MappableRingBuffer` (`MappableRingBuffer.java`): `currentBuffer()` waits (`awaitCompletion(-1)`) on the fence stored
for the current index, then returns the buffer; `rotate()` stores `createFence()` for the current index and advances.
Users and rotation points:

| Ring | Usage | Rotated |
|---|---|---|
| DynamicGpuData storages | 130 / 514 | `DynamicGpuData.reset()` after submit |
| Fog UBO | 130 | `FogRenderer.endFrame()` mid-frame |
| Lightmap UBO | 130 | after the lightmap pass, mid-frame |
| Cloud UBO | 130 | `CloudRenderer.endFrame()` after submit |
| Cloud UTB | 258 (`UNIFORM_TEXEL_BUFFER \| MAP_WRITE`) | only when the cloud mesh is rebuilt |
| PostPass SamplerInfo | 130 | after each post pass, mid-frame |
| Chunk staging (PersistentlyMapped) | 18 | `Uploader.close()` when all staged data was consumed |

Small single-buffered UBOs updated with `writeToBuffer` (usage 136 = `UNIFORM | COPY_DST`): `GlobalSettingsUniform`
(48 B, every frame), `ProjectionMatrixBuffer` (64 B, every frame for the level, on change for others), `Lighting`
(5 padded entries, written at creation and by `updateLevel` on level change, `GameRenderer.setLevel`). These rely on
GPU-ordered `writeToBuffer` (8.2).

### 6.3 Features: entities, block entities, items, text in the world (`net/minecraft/client/renderer/feature/**`)

Flow:
1. `LevelRenderer.submitFeatures` fills `SubmitNodeStorage`.
2. `FeatureRenderDispatcher.prepareFrame` (`FeatureRenderDispatcher.java:80-113`): sorts submits into groups, each
   `RenderTypeFeatureRenderer` builds vertices into the shared `StagedVertexBuffer` (one `StagedVertexBuffer.Draw` per
   distinct `PreparedRenderType` per group when reordering is allowed), and each new draw calls
   `RenderType.prepare()` → `writeTransform` (6.2). Then `stagedVertexBuffer.upload()`.
3. Execution (`executePhase`, `:307-320`): per group `pushDebugGroup(featureTypeName)`, the renderer's
   `executeGroup`, `popDebugGroup`.

Per draw (`PreparedRenderType.draw`, `rendertype/PreparedRenderType.java:37-56`):

```
pushDebugGroup("Render Type " + name)
setPipeline(compiled pipeline)
enableScissor(...)                    only if scissorState.enabled(); never disabled here
bindDefaultUniforms: setUniform Projection, Fog, Globals, Lighting
setUniform("DynamicTransforms", slice)
setVertexBuffer(0, stagedVertexBuffer.slice())
setUniform(texture) x 1..3            Sampler0 / Sampler1 (overlay) / Sampler2 (lightmap)
setIndexBuffer(sorted or sequential index buffer, type)
drawIndexed(indexCount, 1, firstIndex, baseVertex, 0)
popDebugGroup()
```

About 10 to 14 frontend calls per draw, and `setPipeline` replays every stored uniform. Draw count is about the
number of distinct (render type, texture) combinations per group: tens to a few hundred per frame [estimate].
All draws of a frame share one vertex buffer and at most one sorted index buffer per `upload()`.

`StagedVertexBuffer` (`net/minecraft/client/renderer/StagedVertexBuffer.java`):
- Three `GpuBufferPool`s: staging usage 22 (`MAP_WRITE | HINT_CLIENT_STORAGE | COPY_SRC`), vertex usage 40
  (`COPY_DST | VERTEX`), index usage 72 (`COPY_DST | INDEX`). Sizes rounded up to 256 KiB; reuse within 1x to 4x size.
- `upload()` (`:90-124`): acquire buffers, one `map()` of the staging buffer, `memcpy` all vertex data, write sorted
  indices (only for RenderTypes with `sortOnUpload` in classic transparency), then `copyToBuffer` staging→vertex and
  staging→index.
- `endFrame()` (`:350-363`): fence for the buffers used this frame (`createFence()`), close all still-available
  buffers, recycle buffers whose fence completed (`awaitCompletion(0)`).
- `upload()` happens several times per frame on the shared instance (`RenderBuffers.stagedVertexBuffer()`): level
  features, item in hand, screen effects, each GUI item atlas slot redraw, each picture-in-picture renderer. The GUI
  has its own instance.
- On unified memory the staging→vertex copy is redundant work, but it is a GPU blit of a few MiB per frame
  [estimate]; keep the semantics simple.

### 6.4 Particles (`QuadParticleFeatureRenderer.java`)

One `StagedVertexBuffer.Draw` per particle layer (format `PARTICLE`, topology `QUADS`), one shared
`writeTransform` per frame. Per group: `bindDefaultUniforms`, `DynamicTransforms`, `Sampler2`; per layer:
`setPipeline`, `setVertexBuffer`, `setIndexBuffer` (sequential quads, may be `INT` with many particles),
`setUniform("Sampler0")`, `drawIndexed`. A handful of draws per frame with large vertex counts. FABULOUS repeats
translucent particle layers for each OIT stage.

### 6.5 Text

- In-world text (name tags, signs, text displays) is `TextFeatureRenderer`, a `RenderTypeFeatureRenderer`: same per-draw
  sequence as 6.3, one draw per glyph-page render type per group.
- GUI text is converted to `GlyphRenderState`s (`GuiRenderer.prepareText`) and merged into GUI draws (6.6).
- Glyph atlas updates use `writeToTexture` when new glyphs appear (not per frame).

### 6.6 GUI (`net/minecraft/client/gui/render/GuiRenderer.java`)

- `render()` (`:117`): prepare, `vertexBuffer.upload()` (own `StagedVertexBuffer`, 768 KiB initial), 
  `resizeAllAutoStorageIndexBuffers()`, `draw()`.
- `draw()` (`:182-201`): `setProjectionMatrix` (ortho, written via `writeToBuffer` only when changed), one
  `writeTransform`, then "GUI before blur" render pass; if a blurred screen is open: `clearDepthTexture`,
  `processBlurEffect()` (post chain passes), "GUI after blur" pass.
- Per pass: `bindDefaultUniforms`, `setUniform("DynamicTransforms")`. Per draw (`executeDraw`, `:370-406`):
  `setPipeline`, `setVertexBuffer(0)`, `enableScissor` or `disableScissor`, 0 to 3 sampler `setUniform`s,
  `setIndexBuffer`, `drawIndexed(count, 1, firstIndex, baseVertex, 0)`. Draws are split whenever pipeline, texture
  set or scissor changes after sorting: tens to a few hundred per frame [estimate].
- Scissor values are computed with a bottom-left origin: `enableScissor(left, window.height - bottom, w, h)`
  (`:408-415`). The Vulkan backend passes them through unchanged, which is correct because renderpearl targets use GL
  row order (see the coordinate table in `ARCHITECTURE.md`).
- **GUI item atlas** (`GuiItemAtlas.getOrUpdate/drawToSlot`, `GuiItemAtlas.java:73-125`): items with an enchantment
  glint or an animated texture are `isAnimated()` and get a `discardAfterFrame` slot, so they are redrawn **every
  frame**. Each redraw costs: a region `clearColorAndDepthTextures(..., x, y, w, h, 0)` (a partial clear; Metal has no
  native partial clear), a projection `writeToBuffer` when changed, a full `FeatureRenderDispatcher.prepareFrame`
  (staged upload with 2 copies), and its own "Item to GUI item atlas" render pass over the whole atlas texture with a
  scissor. An inventory full of enchanted items means dozens of render passes per frame on one texture. This is a
  TBDR-relevant hot path: each pass loads and stores the full atlas attachments **[speculative: whether Apple GPUs
  skip load/store for tiles that receive no geometry]**.
- Picture-in-picture renderers (`gui/render/pip/PictureInPictureRenderer.java`) and oversized items follow the same
  pattern (clear + prepareFrame + own pass) when present.

### 6.7 Sky, clouds, weather, world border

- Sky: one "Sky" pass; about 8 `writeTransform`s; `draw(10,1,0,0)` sky discs and `draw(18,...)` sunrise use
  `PrimitiveTopology.TRIANGLE_FAN` (`RenderPipelines.SKY`, `SUNRISE_SUNSET`), which Metal does not have.
- Clouds (`CloudRenderer.java:126-245`): `map` of the cloud UBO every frame, `map` of the cloud UTB ring only when the
  mesh is rebuilt. Draw: `setVertexBuffer(0, null)` (no vertex buffer), `CloudInfo` UBO, `CloudFaces` texel buffer
  (`R8_SINT`, whole buffer required by the frontend), `drawIndexed(6 * quadCount, 1, 0, 0, 0)` with the sequential quad
  index buffer; the vertex shader decodes faces from `gl_VertexIndex`. Metal needs a cached texture-buffer view per
  `MTLBuffer` (probe: `minimumLinearTextureAlignmentForPixelFormat(R8Sint)` = 16; the frontend guarantees offset 0).
- Weather (`WeatherEffectRenderer.java:99-170`): every frame with rain/snow, a CPU-built vertex buffer is uploaded with
  **`writeToBuffer` into the same single GPU buffer** (usage 40, grown when too small), then two `drawIndexed` calls
  with `firstIndex = startColumn * 6`.
- World border (`WorldBorderRenderer.java:157-229`): `drawMultipleIndexed` with up to 4 draws, no dynamic uniforms.

### 6.8 Post effects and OIT

- `PostPass` (`PostPass.java:89-151`): per pass, map the `SamplerInfo` ring, one render pass, `setPipeline`,
  `bindDefaultUniforms`, custom UBOs (immutable, usage 128, created with data), input samplers, `draw(3,1,0,0)`, then
  `rotate()`. Chains: entity outline (when glowing entities are visible), blur (menus), spectator effects.
- OIT: see 5.3. Pipelines: `OIT_DEPTH_BOUNDS_SNIPPET` (RGBA32F target, `BlendFunction.MAX`), transmittance (2 x RGBA16F,
  additive), accumulate (RGBA16F, additive), all with depth test `GREATER_THAN_OR_EQUAL` and no depth write. Full-screen
  helper passes use `draw(3,1,0,0)`.
- `RenderTargetDescriptor.prepare` (`com/mojang/blaze3d/resource/RenderTargetDescriptor.java`) issues standalone
  `clearColorTexture` / `clearDepthTexture` / `clearColorAndDepthTextures` for every frame-graph target with a clear
  color, every frame. Targets are pooled across frames (`CrossFrameResourcePool`), so no per-frame texture creation.

### 6.9 Lightmap and texture animation

- Lightmap: 16x16 RGBA8 target, one pass and `draw(3,1,0,0)` per frame when `needsUpdate`.
- Atlas animation (`TextureAtlas.uploadAnimationFrames`, `:234-250`): when any animated sprite needs drawing, one
  render pass **per mip level** (up to 5) over the full atlas mip with `LOAD`, one `draw(6, 1, frameProgress << 3, 0)`
  per animated sprite (the vertex shader decodes `gl_VertexIndex >> 3`, so `vertex_id` must include `vertexStart`).
  Runs from `textureManager.tick()`, so at most once per frame and at 20 Hz.

---

## 7. Buffer usage catalogue

Flags: `MAP_READ 1`, `MAP_WRITE 2`, `HINT_CLIENT_STORAGE 4`, `COPY_DST 8`, `COPY_SRC 16`, `VERTEX 32`, `INDEX 64`,
`UNIFORM 128`, `UNIFORM_TEXEL_BUFFER 256`, `INDIRECT_PARAMETERS 512`.

| Usage | Flags | Created by | Access pattern |
|---|---|---|---|
| 9 | MAP_READ, COPY_DST | `Screenshot`, `TextureUtil`, `TracyFrameCapture` | `copyTextureToBuffer` then `map(true,false)` in the callback |
| 18 | MAP_WRITE, COPY_SRC | chunk `StagingBuffer.PersistentlyMapped` ring | mapped for its whole ring slot lifetime; written by worker threads; copy source |
| 22 | MAP_WRITE, CLIENT_STORAGE, COPY_SRC | `StagedVertexBuffer` staging pool, `BitmapProvider` | map, memcpy, close, copy source |
| 32 | VERTEX | sky/cube map/crosshair meshes (with data) | immutable |
| 40 | VERTEX, COPY_DST | staged vertex pool, weather, world border, stars, end sky, instanced chunk sections | copy/`writeToBuffer` target; weather and chunk sections rewritten every frame in place |
| 56 | VERTEX, COPY_DST, COPY_SRC | terrain vertex heaps (128 MiB) | partial copies every frame while loading |
| 64 | INDEX | sequential index buffers (`RenderSystem.AutoStorageIndexBuffer`, created with data, grown by replace) | immutable |
| 72 | INDEX, COPY_DST | staged index pool | copy target |
| 88 | INDEX, COPY_DST, COPY_SRC | terrain index heaps (32 MiB) | partial copies |
| 128 | UNIFORM | post-pass custom uniforms, sprite UBOs, empty fog (created with data) | immutable |
| 130 | UNIFORM, MAP_WRITE | all UBO rings | `map` per write, unsynchronized |
| 136 | UNIFORM, COPY_DST | global settings, projection, lighting | `writeToBuffer` in place, every frame |
| 258 | TEXEL, MAP_WRITE | cloud UTB ring | map on rebuild |
| 514 | INDIRECT, MAP_WRITE | MDI command ring | map once per frame |

Notes:
- `createBuffer(label, usage, ByteBuffer data)` must initialize contents even without `COPY_DST` (usages 32, 64, 128).
- `AutoStorageIndexBuffer.ensureStorage` (`RenderSystem.java:439-469`) closes the old index buffer immediately and
  creates a new one; in-flight frames may still reference the old one.
- `map()` callers write with `ByteBuffer.putFloat/putInt` (`Std140Builder`, `IndexedDraw.write`) and read
  `position()` (`CloudRenderer` derives `quadCount` from it). The returned `ByteBuffer` must start at position 0 and use
  **native (little-endian) byte order**. `MemorySegment.asByteBuffer()` returns big-endian by default.
- `writeToBuffer` and `createBuffer(..., data)` receive `MemoryStack` or `memAlloc` buffers that are freed right after
  the call returns (`Lighting`, `GlobalSettingsUniform`, `ProjectionMatrixBuffer`, `AutoStorageIndexBuffer`). Copy the
  bytes before returning.
- Metal storage per `ARCHITECTURE.md`: everything `Shared` on unified memory. That fits every row above.

---

## 8. Synchronization contracts the hot paths rely on

### 8.1 `map()` is unsynchronized

GL maps with `GL_MAP_UNSYNCHRONIZED_BIT` for writes (`GlBuffer.Direct`, mapping flag 32) and persistently when
`ARB_buffer_storage` exists; Vulkan maps with `vmaMapMemory` and no wait. Callers synchronize through
`MappableRingBuffer` fences. The Metal `map()` should return `contents + offset` wrapped in a native-order
`ByteBuffer` with a shared no-op close, no FFM call, no waiting. It is on the per-draw path through
`DynamicGpuDataStorageMapped.writeData`.

### 8.2 `writeToBuffer` / `copyToBuffer` are ordered on the GPU timeline

Vulkan's `writeToBuffer` (`VulkanCommandEncoder.java:587-614`) copies the data into transient staging memory and
records `vkCmdCopyBuffer` followed by a full memory barrier into the frame's command buffer; `copyToBuffer` records a
copy and a barrier. Vanilla depends on this ordering for buffers rewritten in place while earlier frames are in
flight: the instanced chunk-section buffer (6.1.4), the weather vertex buffer (6.7), and the 136-usage UBOs (6.2).

`ARCHITECTURE.md` allows a direct CPU write into a shared buffer when `lastUseSerial < currentSerial &&
lastUseSerial <= completedSerial`. That is correct if `lastUseSerial` is updated by every use: uniform binds, vertex,
index, **indirect** and texel-buffer binds, and both ends of every copy (including copies recorded earlier in the same
command buffer). In vanilla the in-place rewritten buffers are used every frame, so they will normally take the
blit path; the direct write mostly helps first-time initialization.

### 8.3 Fences

- `createFence()` callers [estimate 10 to 20 per frame]: every `MappableRingBuffer.rotate()`, every
  `StagedVertexBuffer.GpuBufferPool.endFrame()` that used buffers, `RenderSystem.queueFencedTask`.
- `awaitCompletion(0)` polling: `RenderSystem.executePendingTasks`, `GpuBufferPool.tryRecycleBuffers`.
- `awaitCompletion(-1)` blocking: `MappableRingBuffer.currentBuffer()`.
- Vanilla semantics: a fence stores the current submit index (`GlFence`, `VulkanCommandEncoder.createFence`,
  `:890-909`); it completes when that whole submit completes. Waiting with a non-zero timeout on a fence of the current
  (open) submit throws `IllegalStateException("Cannot wait on a fence for the current submit")` in both backends;
  with timeout 0 it returns false.
- Consequence for the chunk staging ring: if `StagingBuffer.PersistentlyMapped.rotateBuffer()` runs three or more
  times within one frame (only possible when synchronous section compiles fill the staging buffer and force extra
  `uploadTerrainBuffersToGpu()` calls), the third `currentBuffer()` waits on a fence of the open submit. Vanilla's
  backends would throw there too; GL only selects that path on D3D12, Vulkan never does. The Metal backend should
  handle it instead of throwing: if the fence's serial is the open command buffer and the timeout is non-zero, commit
  the open command buffer (no render pass can be open, because the frontend forbids fence creation inside passes and
  the wait happens on the render thread between passes), start a new one, then wait.
- Fences created right after `submit()` (DynamicGpuData, cloud UBO) refer to the next serial under vanilla
  semantics, which is one frame more conservative than necessary. A Metal fence may instead take the last committed
  serial when nothing has been encoded since the last commit; both are correct.

### 8.4 Frames in flight

GL and Vulkan block in `submit()` until submit `N-2` has completed (`GlCommandEncoder.java:75-83`,
`VulkanCommandEncoder.java:263-279`), so at most 2 frames are in flight. All rings have 3 entries. With vanilla fence
semantics (8.3), a third frame in flight would block inside `MappableRingBuffer.currentBuffer()` in the middle of
recording instead of at frame start. `ARCHITECTURE.md` currently specifies a default of 3; either cap at 2 or adopt the
"last committed serial" fence rule so the rings do not stall mid-frame.

### 8.5 Deferred destruction

Vanilla closes buffers that in-flight frames still use: `DynamicGpuDataStorage*` after resizes, `MappableRingBuffer`s
of old storages, `AutoStorageIndexBuffer` replacements, `StagedVertexBuffer` pools (closes unused buffers every
`endFrame`), `WeatherEffectRenderer` growth, `UberGpuBuffer` heaps. Vulkan queues them (`queueForDestroy`). With
`commandBufferWithUnretainedReferences` the Metal backend must do the same (as `ARCHITECTURE.md` specifies).

---

## 9. Pitfalls checklist

1. Advertise `drawIndirect`, `multiDrawIndirect`, `nonZeroFirstInstance` and `maxDrawIndirectDrawCount > 0`
   together, or the terrain path throws `UnsupportedOperationException` from `FrontendRenderPass`.
2. If `maxDrawIndirectDrawCount` is 1, `multiDrawIndirect` may be false; any value above 1 requires
   `multiDrawIndirect = true`.
3. The MDI terrain path needs instance-rate vertex fetch that honours `baseInstance`, including inside indirect
   draws (probe T1: it does on Metal). Stride is 16, format `RGB32_SINT` + `R32_FLOAT`.
4. The instanced chunk-section buffer is single-buffered and rewritten every frame by `writeToBuffer`. A plain
   immediate `memcpy` into it would race with the previous frame. Same for the weather vertex buffer and the 136 UBOs.
5. `map()` is on the per-draw path. No FFM call, no lock, native byte order, position 0.
6. `getDeviceInfo()` is called per UBO bind. Return a cached record.
7. `setPipeline` replays all stored uniforms into `RenderPassBackend.setUniform`. Bind lazily at draw time.
8. `pushDebugGroup` per draw: do not evaluate the `Supplier` unless labels are enabled.
9. Minimum `minUniformOffsetAlignment` 16 so padded blocks match MSL struct sizes; 256 on Mac2-only GPUs.
10. Allocate `UNIFORM` buffers rounded up to 16 bytes internally. `CloudRenderer`'s UBO ring buffers are 44 bytes and
    the global settings UBO is 48, while the MSL struct for a block ending in `vec3` is a multiple of 16
    **[speculative: whether Metal validation reports a too-small buffer]**.
11. `drawMultipleIndexed` passes `draw.baseVertex()` as `vertexOffset` and always `instanceCount = 1`,
    `firstInstance = 0`.
12. `multiDrawIndexed(PointerBuffer, ...)` offsets are **bytes**, not indices.
13. `vertex_id` must include `vertexStart`/`baseVertex` (sprite animation encodes data in `firstVertex`). Metal does
    this natively.
14. `setVertexBuffer(0, null)` is legal (clouds) and must unbind or be ignored, not crash.
15. Zero-count draws: vanilla avoids them, but skip them defensively before calling Metal.
16. Index buffer offsets: vanilla always produces `firstIndex * indexSize` that is a multiple of 4 (quads use 6
    indices, terrain index allocations are 8-aligned, world border/weather/crosshair offsets are multiples of 6 or
    18). Probe: on M4 Pro, odd `uint16` first indices also draw correctly for direct and indirect draws. A public report
    (solidean/shaped-core PR #185, M4) claims silent truncation for odd `uint16` offsets; the probe did not reproduce
    it. Keep a debug assert.
17. `TRIANGLE_FAN` and `QUADS` topologies are used on per-frame paths (sky, sunrise; all quads go through the
    sequential quad index buffer, but `draw()` with fans does not). Out of scope here, listed because it sits on the
    sky path.
18. Standalone clears are frequent (two full clears of the main target per frame, OIT target clears every frame,
    region clears in the GUI item atlas). On a TBDR GPU, folding a pending clear into the next render pass's load action
    is the obvious optimization **[speculative: no measurement yet]**.
19. `isExplicitDepthRequired` changes shader source, so it must be fixed before the first pipeline compile.
20. Old buffers are closed while in flight (8.5).

---

## 10. Recommended feature and limit set for Metal on Apple Silicon

### 10.1 Values and justification

**Features**

- `drawIndirect = true`, `multiDrawIndirect = true`, `nonZeroFirstInstance = true`, `maxDrawIndirectDrawCount =
  Integer.MAX_VALUE`, `multiDrawIndirectHasKnownIssues = false`. This turns on the only vanilla path that removes
  per-section Java/frontend/FFM work from terrain rendering (6.1.4 vs 6.1.5). Metal supports `baseInstance` and
  `baseVertex` in direct and indirect draws on every Apple GPU and on Mac2, and the argument layouts match byte for
  byte. `Integer.MAX_VALUE` means `ChunkSectionsToRender` never splits a group, so each `drawIndexedIndirect` maps to
  one downcall. (MoltenVK on M1 Max reports 2^30, which also never splits in practice.)
- `shaderDrawParameters = true`. Its only effect is a macro unused by vanilla, and it describes Metal's
  `[[instance_id]]` semantics correctly. Caveat for mods: Metal has no `gl_DrawID` equivalent; SPIRV-Cross cannot map
  `DrawIndex` to MSL **[speculative: check the SPIRV-Cross version shipped with LWJGL 3.4.3]**, so a mod shader using it
  would fail translation regardless of this flag.
- `wireframeFillMode = true`. `MTLTriangleFillModeLines` exists on all Metal devices. Avoids the startup error logs and
  keeps F3+W.
- `persistentMapping = true`. Shared buffers on unified memory are always mapped; keeping a view open while the GPU
  copies from the buffer is free and safe.
- `multiDrawDirectInterleaved`, `multiDrawDirectSeparate`: no vanilla effect. Advertise `true` with
  `maxMultiDrawDirectInterleavedDrawCount = Integer.MAX_VALUE` once the backend implements both as native loops (one
  downcall: pass the `IntBuffer`/`PointerBuffer` addresses and the count). Until then, `false` and `0`; nothing in
  vanilla notices.

**Limits**

- `minUniformOffsetAlignment = 16` when the device supports an Apple GPU family (all Apple Silicon); `256` when it only
  supports Mac2 (Intel/AMD). 16 is the smallest value that keeps every padded std140 block at least as large as its
  MSL struct, it is above Metal's Apple-family requirement of 4, and it is what Mojang's Vulkan backend already sees
  through MoltenVK on Apple Silicon. Compared with 256 it shrinks the DrawSeparate section UBO array 16x and every
  transform block from 256 to 160 bytes.
- `maxTextureSize = 16384`, `maxColorAttachments = 8`, `maxAnisotropy = 16` (Metal limits for Apple GPUs; MoltenVK
  reports the same).
- `maxMemoryAllocationSize = MTLDevice.maxBufferLength` (14.3 GB on the M4 Pro probe). Only feeds
  `maxTextureSizeForFormat`, where it never binds.

**Hints**

- `writeToBufferIsSlow = true` together with `persistentMapping = true`, so chunk meshes are copied once, on worker
  threads, into a shared buffer, and the render thread only records blits (3.3.1). Prerequisite: the fence behaviour of
  8.3 (flush-and-wait instead of throwing). Cost: 3 x 49 MiB of shared staging memory instead of a 98 MiB malloc. If
  the flush-and-wait fence is not ready, use `false`; vanilla behaves exactly like it does on Vulkan.
- `anisotropyHasKnownIssues = false`: FABULOUS uses hardware anisotropic filtering rather than the 8-sample RGSS
  shader path.
- `isExplicitDepthRequired = true` on Apple GPUs (`false` on Intel/AMD Macs), matching both vanilla backends' rule.
- `multiDrawIndirectHasKnownIssues = false`.

**Other `DeviceInfo` fields**: `isZZeroToOne = true`, `backendName = "Metal"`, `name = MTLDevice.name`,
`vendorName = "Apple"` (or the vendor for Intel/AMD Macs), `driverInfo` = a one-line string with macOS version and
highest supported GPU family, `timestampPeriod = 1.0` with ns timestamps, `underlyingExtensions` = supported
`MTLGPUFamily` names (log only), `type = INTEGRATED` when `hasUnifiedMemory`, `DISCRETE` for a non-low-power
non-unified GPU.

### 10.2 Intel and AMD Macs (secondary)

Same as above except `minUniformOffsetAlignment = 256`, `isExplicitDepthRequired = false`, `type` from
`isLowPower`/`isRemovable`. With non-unified memory, `persistentMapping` should only stay `true` if mapped buffers are
`Shared` (system memory; fine as copy sources) rather than `Managed` (which would need `didModifyRange:` before every
GPU use of a still-mapped staging buffer).

### 10.3 Implementing the indirect and multi-draw entry points

- `drawIndexedIndirect(slice, n)`: one downcall carrying the `MTLBuffer` handle, slice offset, `n`, index buffer
  handle and type, primitive type. Native loop:
  `for i in 0..<n: drawIndexedPrimitives:type indexType:t indexBuffer:ib indexBufferOffset:0 indirectBuffer:buf
  indirectBufferOffset:off + 20*i`. Probe T2 shows `indexStart` and `baseInstance` from the arguments are honoured,
  including odd `uint16` starts.
- Optional CPU-side variant **[speculative]**: when the command buffer is a host-mapped ring buffer written with
  `map()` this frame (the vanilla case), the native loop can read the 20-byte records directly and issue direct
  `drawIndexedPrimitives:...instanceCount:baseVertex:baseInstance:` calls, skipping `indexCount == 0` entries.
  Only valid if no GPU-side write to that range is pending; whether it is faster than indirect on Apple GPUs needs
  measuring.
- Further option **[speculative]**: translate the argument array into an `MTLIndirectCommandBuffer` with a compute
  kernel and run `executeCommandsInBuffer:withRange:`, removing the per-sub-draw CPU cost entirely. Requires pipelines
  created with `supportIndirectCommandBuffers = YES`.
- Bindings must be flushed once before the loop; all sub-draws share pipeline, vertex buffers 0 and 1, index buffer
  and uniforms.

### 10.4 Measuring

- F3+M toggles MDI at runtime and F3+W toggles wireframe; both paths of each must render identically.
- Count backend calls per frame by category (draws, sub-draws, binds, passes, copies, clears, fences, maps) in a debug
  build to replace the [estimate] figures in this document.
- Keep `FrontendGpuDevice.STRICT_VALIDATION` off when profiling.

---

## Appendix A. Exact snippets

`LevelRenderer.<init>` (`LevelRenderer.java:174-181`):

```java
DeviceInfo deviceInfo = RenderSystem.getDevice().getDeviceInfo();
DeviceFeatures deviceFeatures = deviceInfo.features();
DeviceLimits deviceLimits = deviceInfo.limits();
HintsAndWorkarounds hintsAndWorkarounds = deviceInfo.hintsAndWorkarounds();
this.multiDrawIndirectAvailable = deviceLimits.maxDrawIndirectDrawCount() > 0
   && deviceFeatures.nonZeroFirstInstance()
   && !hintsAndWorkarounds.multiDrawIndirectHasKnownIssues();
this.usingMultiDrawIndirectForTerrain = this.multiDrawIndirectAvailable;
```

`LevelRenderer.render` (`:264`): `usingMultiDrawIndirectForTerrain = multiDrawIndirectAvailable &&
levelRenderState.shouldUseMultiDrawIndirectForTerrain;` (the F3+M toggle, default true).

`StagingBuffer.create` (`StagingBuffer.java:16-20`):

```java
return gpuDevice.getDeviceInfo().hintsAndWorkarounds().writeToBufferIsSlow() && gpuDevice.getDeviceInfo().features().persistentMapping()
   ? new StagingBuffer.PersistentlyMapped(name, bufferSize)   // MappableRingBuffer(usage 18, 3 x bufferSize/2), mapped(false,true)
   : new StagingBuffer.Cpu(bufferSize);                       // MemoryUtil.memAlloc(bufferSize)
```

`GlslCompiler.createBaseShaderOptions` (`GlslCompiler.java:74-94`): target env Vulkan, auto-bind uniforms,
`-O0` with debug info, plus macros `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` (isZZeroToOne),
`RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` (isExplicitDepthRequired), `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`
(shaderDrawParameters).

`DynamicGpuData.IndexedDraw.write` (`DynamicGpuData.java:128-134`) writes
`indexCount, instanceCount, firstIndex, baseVertex, baseInstance` as five `putInt`s (20 bytes).

## Appendix B. Probe results (Apple M4 Pro, macOS 27.2)

```
name=Apple M4 Pro
hasUnifiedMemory=1 lowPower=0 removable=0
maxBufferLength=14302248960 recommendedMaxWorkingSetSize=19069665280
supports MTLGPUFamilyApple1..Apple9, MTLGPUFamilyMac2, MTLGPUFamilyMetal3
minLinearTexAlign(R8Sint)=16
supportsCounterSampling(stage)=1 (draw)=0 (blit)=0
T1 indirect baseInstance k (expect 100 101 102 103)               100 101 102 103
T2 indexed indirect u16 indexStart=k+1 (expect 2001 3002 4003 5004) 2001 3002 4003 5004
T3 direct u16 indexBufferOffset=2(k+1) (expect 2001 3002 4003 5004) 2001 3002 4003 5004
T4 const offset 4 then 16 (expect 107 151 0 0)                    107 151 0 0
idxprobe: direct uint16 draws with firstIndex 1, 3, 5 and counts 1..37: all ranges drawn exactly
MTL_DEBUG_LAYER=1: no validation messages for any of the above
```

T1: per-instance attribute (`MTLVertexStepFunctionPerInstance`, step 1) fetched via indirect
`drawPrimitives` with `baseInstance = k`; both the fetched value and `[[instance_id]]` include `baseInstance`.
T2/T3: `uint16` index fetch with odd start offsets via indirect `indexStart` and direct `indexBufferOffset`.
T4: `constant` buffer at offsets 4 and 16.
