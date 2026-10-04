# Render pipelines and shaders: `RenderPipeline` → `BackendRenderPipeline.CreateInfo` → Metal

Scope: everything a backend receives when Minecraft 26.3 compiles a render pipeline, exactly how the vanilla
frontend produces it, how the OpenGL and Vulkan backends consume it, what the vanilla shader corpus needs from
SPIR-V→MSL translation, and the Metal mapping. The aim is that `MetalDevice.compilePipeline`,
`MetalRenderPipeline` and `dev.ingot.shader.SpirvToMsl` can be written from this document without re-reading
the client sources.

Sources (Vineflower decompile of the 26.3 client, unobfuscated names):

- `RP/` = `com/mojang/renderpearl/` (`scratchpad/mc/src-rp/...`), `SRC/` = the whole client
  (`scratchpad/mc/src-full/`), shaders under `SRC/assets/minecraft/shaders/`, post chains under
  `SRC/assets/minecraft/post_effect/`.
- `File.java:NNN` line numbers refer to those decompiled files. Use them for orientation only.
- Numeric constants were decoded with `javap -constants` on `lwjgl-spvc-3.4.3.jar` / `lwjgl-shaderc-3.4.3.jar`
  (the artifacts in the 26.3 version JSON).
- **Empirical** results come from probes run on this Mac (Apple M4 Pro, macOS 27.2, JDK 25.0.1, LWJGL 3.4.3).
  The main probe (`docs/research/probes/pipelines/Dump.java`) loads the real 26.3 client jar, instantiates every
  vanilla `RenderPipeline` (including OIT sets and all post-effect passes), runs them through the **real**
  `PipelineBuilder` against a fake `GpuDeviceBackend`, captures every `CreateInfo`, and translates every SPIR-V
  module to MSL with SPIRV-Cross. `validate.m` then compiles every MSL file with `MTLDevice newLibraryWithSource:`
  and builds an `MTLRenderPipelineState` for every pipeline. Machine-readable output:
  `docs/research/data/pipelines-26.3.json`. Appendix B explains how to re-run.
- Anything not verified in source or by probe is marked **[speculative]**.

---

## 0. Key facts

1. **One bind group, dense bindings.** The frontend rewrites the SPIR-V in place so that **every** resource
   (UBO, combined image sampler, texel buffer) is in **descriptor set 0** and its **binding equals its index in
   `CreateInfo.uniforms()`**. Indices are dense `0..n-1`, assigned in first-seen order (vertex stage first,
   then fragment; within a stage UBOs before sampled images, otherwise SPIRV-Cross resource order). A name used in
   both stages gets one index. `RenderPassBackend.setUniform(int index, Object value)` uses the same index.
   The order is *not* declaration order and *not* the `BindGroupLayout` order. Never assume it; read
   `CreateInfo.uniforms()`.
2. **Vertex attribute locations are not remapped.** `AttribBinding.location` is the `layout(location = N)` from the
   GLSL vertex input that has the same *name* as the `VertexFormatElement`. It is unrelated to the element's
   position in the format (`core/text.vsh` reads `Color` at location 1 from offset 20). Elements the shader does
   not declare get no `AttribBinding`; the vertex buffer still appears in `CreateInfo.vertexBuffers()`.
3. **No specialization constants, no storage buffers, no storage images, no separate samplers, no sampler
   arrays.** Only `UNIFORM_BUFFER`, `COMBINED_IMAGE_SAMPLER` (2D, Cube, Rect) and `TEXEL_BUFFER`
   (`samplerBuffer`). All shader variation is via `#define`s. Vanilla uses **no push constants**, but the API
   allows one push-constant block per stage, at most 128 bytes.
4. **Entry point is always `"main"` in SPIR-V; SPIRV-Cross renames it to `main0` in MSL.** Use
   `spvc_compiler_get_cleansed_entry_point_name`.
5. SPIR-V comes from shaderc with **Vulkan 1.2 target (SPIR-V 1.5), `-O0`, debug info on, auto-bind on**. Debug
   names are required: the frontend matches resources and vertex inputs **by name**.
6. **Threading:** `GpuDeviceBackend.compilePipeline(CreateInfo)` is called on worker threads (many at once during a
   resource reload). `Pending.finishCompile()` is called on the render/main thread (reload) or on the caller of
   `RenderSystem.getCompiledPipeline` (lazy compile, also the render thread). **The `SpvModule`s are freed right
   after `finishCompile()` returns** (asynchronously, on the executor). Never keep `module.spv()` or a
   `Reflection` past `finishCompile()`.
7. **Failure contract:** return `BackendRenderPipeline.Pending.NULL` (or a `Pending` that returns `null`) and log.
   Exceptions thrown from `compilePipeline` propagate into the resource-reload future (the Vulkan backend crashes on
   failure; Metal should not).
8. **Topologies arrive raw.** `CreateInfo.primitiveTopology()` can be `QUADS` and `LINES`; both are drawn as
   **triangle lists** with game-generated index buffers. `TRIANGLE_FAN` is used by 6 vanilla pipelines (sky,
   sunrise, debug fans) and **Metal has no fan primitive**: it must be emulated.
9. **Reverse-Z.** `DepthStencilState.DEFAULT` is `GREATER_THAN_OR_EQUAL` + write; the depth clear value is `0.0`
   (`RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE`). The only depth format the game creates is `D32_FLOAT`.
10. **Coordinate convention (verified on Metal):** to match GL/Vulkan memory layout, NDC y = −1 must land on
    texel row 0. Either SPIRV-Cross `FLIP_VERTEX_Y` or a negative-height viewport does this, and then GL's
    counter-clockwise front faces are `MTLWindingClockwise`. This matches `docs/ARCHITECTURE.md`.
11. **Report `isZZeroToOne = true`, `shaderDrawParameters = true`, `wireframeFillMode = true`, and (to match Mojang's
    Apple-GPU workaround) `isExplicitDepthRequired = true` on Apple GPUs.** These change the GLSL macros (§4).
12. **All 219 vanilla pipelines translate and build on Metal (empirical).** 438 SPIR-V modules (175 unique) →
    SPIRV-Cross MSL 2.4 (169 unique sources) → `newLibraryWithSource:` → `MTLRenderPipelineState`: 219/219, zero
    errors, also with `MTL_DEBUG_LAYER=1` and with `MTLLanguageVersion3_0`.
13. **UBO size trap (empirical):** MSL struct sizes are rounded to 16 bytes (`Fog` 40→48, `DynamicTransforms`
    156→160, ...), but the game allocates some UBOs at the exact std140 size (`FogRenderer` ring buffer = 40 bytes,
    `CloudRenderer` 44, `Lightmap` 92, post-effect config buffers).
    Metal API validation **aborts** the draw ("has space for 40 bytes, but argument has a length(48)"). Round
    uniform-buffer allocations up to a multiple of 16 bytes.
14. **Pipeline names are not unique.** Five vanilla pipelines share two locations
    (`pipeline/item_cutout` ×3, `pipeline/item_translucent_glint` ×2). Never key caches on `CreateInfo.name()`.
15. **Encoder state is not pipeline state on Metal.** Depth/stencil state, cull mode, winding, fill mode and depth bias
    are `MTLRenderCommandEncoder` state and must be re-applied on every `setPipeline`, including resetting them when
    the new pipeline has no depth state.

---

## 1. API objects (`RP/api/pipeline/**`, `RP/api/vertex/**`)

### 1.1 `RenderPipeline` (`RP/api/pipeline/RenderPipeline.java`)

Immutable description, built by `RenderPipeline.Builder` / merged from `RenderPipeline.Snippet`s. Getters:

| Getter | Type | Meaning |
|---|---|---|
| `getLocation()` | `Identifier` | Name, e.g. `minecraft:pipeline/solid_terrain`. **Not unique** (see §7.1). |
| `getShaders()` | `Map<ShaderType, Identifier>` (EnumMap, VERTEX before FRAGMENT) | e.g. `VERTEX → minecraft:core/terrain`. |
| `getShaderDefines()` | `net.minecraft.client.renderer.ShaderDefines(Map<String,String> values, Set<String> flags)` | Becomes `-D` macros. |
| `getBindGroupLayouts()` | `List<BindGroupLayout>` | Declared resources. Built from an `ObjectOpenHashSet`, so the order is hash order and meaningless. |
| `getDepthStencilState()` | `@Nullable DepthStencilState` | `null` = no depth test, no depth write. |
| `getPolygonMode()` | `PolygonMode` | default `FILL`. |
| `isCull()` | `boolean` | default `true` (back-face culling). |
| `getColorTargetStates()` | `List<@Nullable ColorTargetState>` | One entry per color attachment slot, may contain `null`; empty if none were set. |
| `getVertexFormatBindings()` | `List<@Nullable VertexFormat>` (size 16) | Vertex buffer slot `i` → format, `null` = unused slot. |
| `getPrimitiveTopology()` | `PrimitiveTopology` | required. |
| `pushConstantSize()` | `int` | 0..128. |
| `wantsDepthTexture()` | `boolean` | `depthStencilState != null`. |
| `getSortKey()` | `int` | Draw ordering only (GUI); irrelevant to backends. |

`Builder.build()` validation (`RenderPipeline.java:358-424`):

- location, vertex shader, fragment shader and topology are required (`IllegalStateException` otherwise).
- `colorTargetStates` is truncated to `activeColorTargetStateCount` (highest index set + 1; max 8 =
  `ColorTargetState.MAX_COLOR_TARGETS`).
- **All non-null color targets that have a blend function must use the *same* `BlendFunction`** ("Blend functions
  must currently be the same for all color targets"). Blend may still be enabled on some targets and disabled on
  others, and write masks may differ per target.
- Total attributes over all bound vertex formats ≤ 16; `pushConstantSize` ≤ 128.
- Defaults: `polygonMode = FILL`, `cull = true`, `depthStencilState = null`, defines empty.

Snippet merge (`withSnippet`): shaders/defines/bind group layouts are added; depth state, cull, polygon mode and
topology are overwritten if the snippet has them; color targets fill only empty slots; vertex bindings overwrite
per slot; push constant size takes the max. `ShaderDefines.Builder` uses `ImmutableMap.builder()`, so defining the
same value key twice throws at build time.

### 1.2 `ShaderType`, `ShaderSource`, includes

- `ShaderType.VERTEX` (`.vsh`), `ShaderType.FRAGMENT` (`.fsh`). No other stages exist (no compute, no geometry).
- `ShaderSource.getShader(Identifier id, ShaderType type)` returns GLSL text for `assets/<ns>/shaders/<path>.vsh|.fsh`
  (implemented by `ShaderManager.Configs` and by an anonymous class in `GameRenderer.preloadUiShader`).
- `ShaderSource.getInclude(Identifier id)` returns a pre-built `shaderc_include_result` (`CachedIncludeSource`, a raw
  pointer). Include ids look like `minecraft:fog.glsl` and map to `assets/minecraft/shaders/include/fog.glsl`.
  Shaders use `#include <minecraft:fog.glsl>`; shaderc's include callback (installed by `GlslCompiler`) resolves it.
  Missing include → shaderc error text "not found"; unparsable id → "malformed id".
- Backends never see `ShaderSource`; they only see SPIR-V.

### 1.3 `BindGroupLayout`, `UniformDescription`, `UniformType`

```java
public record BindGroupLayout(List<UniformDescription> uniforms)
public record UniformDescription(String name, UniformType type, @Nullable GpuFormat gpuFormat)
public enum UniformType { COMBINED_IMAGE_SAMPLER, UNIFORM_BUFFER, TEXEL_BUFFER }
```

- `gpuFormat` is non-null only for `TEXEL_BUFFER` (enforced by constructors/builder). Vanilla uses exactly one texel
  buffer: `CloudFaces`, `R8_SINT`.
- The name is the GLSL **block name** for UBOs (`layout(std140) uniform Fog {...}` → `"Fog"`; an instance name such
  as `} lightmapInfo;` is ignored) and the **variable name** for samplers (`uniform sampler2D Sampler0;` →
  `"Sampler0"`). SPIRV-Cross reports exactly these names (`get_remapped_declared_block_name(id, false)` for blocks).
- `BindGroupLayout.ensureCompatible` (duplicate-name check) exists but is never called.
- Vanilla layouts: `SRC/net/minecraft/client/renderer/BindGroupLayouts.java`. Post-effect passes build one layout
  per pass: `<input sampler_name> + "Sampler"` (COMBINED_IMAGE_SAMPLER) for each input, `"SamplerInfo"` (UBO),
  and one UBO per key of the pass's `"uniforms"` JSON object (`PostChain.createPass`, `PostChain.java:91`).

### 1.4 `ColorTargetState` and blending

```java
public record ColorTargetState(Optional<BlendFunction> blendFunction, GpuFormat format, @WriteMask int writeMask)
// WRITE_RED=1, WRITE_GREEN=2, WRITE_BLUE=4, WRITE_ALPHA=8, WRITE_COLOR=7, WRITE_ALL=15, WRITE_NONE=0
// DEFAULT = (empty, RGBA8_UNORM, 15); new ColorTargetState(blend) = (blend, RGBA8_UNORM, 15)
public record BlendFunction(BlendEquation color, BlendEquation alpha)
public record BlendEquation(BlendFactor sourceFactor, BlendFactor destFactor, BlendOp op)
```

`BlendFactor`: `CONSTANT_ALPHA, CONSTANT_COLOR, DST_ALPHA, DST_COLOR, ONE, ONE_MINUS_CONSTANT_ALPHA,
ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_DST_ALPHA, ONE_MINUS_DST_COLOR, ONE_MINUS_SRC_ALPHA, ONE_MINUS_SRC_COLOR,
SRC_ALPHA, SRC_ALPHA_SATURATE, SRC_COLOR, ZERO`. `BlendOp`: `ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX`.
There is no API to set the blend constant color; GL and Vulkan both leave it at (0,0,0,0) (Metal's default is also
0). Vanilla never uses `CONSTANT_*` factors.

Named constants (color equation / alpha equation; op is `ADD` unless noted) and their vanilla users:

| Constant | color (src, dst) | alpha (src, dst) | Used by (examples) |
|---|---|---|---|
| `TRANSLUCENT` | SRC_ALPHA, ONE_MINUS_SRC_ALPHA | ONE, ONE_MINUS_SRC_ALPHA | translucent terrain/entities/items, GUI, clouds, text, debug |
| `TRANSLUCENT_PREMULTIPLIED_ALPHA` | ONE, ONE_MINUS_SRC_ALPHA | ONE, ONE_MINUS_SRC_ALPHA | `oit_composite`, `gui_textured_premultiplied_alpha` |
| `ADDITIVE` | ONE, ONE | ONE, ONE | all OIT transmittance/accumulate targets (RGBA16F), `energy_swirl`, `gui_nausea_overlay`, `gui_text_highlight` |
| `MAX` | ONE, ONE, op **MAX** | ONE, ONE, op **MAX** | all OIT depth-bounds pipelines (RGBA32F target) |
| `LIGHTNING` | SRC_ALPHA, ONE | SRC_ALPHA, ONE | `lightning`, `dragon_rays`, `mojang_logo` |
| `OVERLAY` | SRC_ALPHA, ONE | ONE, ZERO | `celestial`, `stars`, `world_border` |
| `GLINT` | SRC_COLOR, ONE | ZERO, ONE | `glint` |
| `ENTITY_OUTLINE_BLIT` | SRC_ALPHA, ONE_MINUS_SRC_ALPHA | ZERO, ONE | `entity_outline_blit` (write mask 7) |
| `INVERT` | ONE_MINUS_DST_COLOR, ONE_MINUS_SRC_COLOR | ONE, ZERO | `gui_invert`, `crosshair` |
| (inline) | DST_COLOR, SRC_COLOR | ONE, ZERO | `crumbling` |
| (inline) | ZERO, ONE_MINUS_SRC_COLOR | ZERO, ONE | `vignette` |

### 1.5 `DepthStencilState`, `CompareOp`

```java
public record DepthStencilState(CompareOp depthTest, boolean writeDepth, float depthBiasScaleFactor, float depthBiasConstant)
public static final DepthStencilState DEFAULT = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true); // bias 0,0
public enum CompareOp { ALWAYS_PASS, LESS_THAN, LESS_THAN_OR_EQUAL, EQUAL, NOT_EQUAL, GREATER_THAN_OR_EQUAL, GREATER_THAN, NEVER_PASS }
```

There is **no stencil state** anywhere in the API (despite the name). `depthBiasScaleFactor` is the slope factor
(GL `glPolygonOffset(factor, units)` = `(scale, constant)`; Vulkan `depthBiasSlopeFactor = scale`,
`depthBiasConstantFactor = constant`, bias enabled iff either is non-zero). Vanilla biases: `(1, 10)` crumbling and
`text*_polygon_offset`, `(1, 1)` `lines_depth_bias`, `(3, 3)` `world_border`.

### 1.6 `PolygonMode`, `PrimitiveTopology`, `IndexType`

- `PolygonMode { FILL, WIREFRAME }`. WIREFRAME is only used by the optional `WIREFRAME*` pipelines; the frontend
  refuses to compile it when `DeviceFeatures.wireframeFillMode()` is false (§2.6).
- `PrimitiveTopology(primitiveLength, primitiveStride, connectedPrimitives)`:

| Topology | GL backend | Vulkan backend | How the game draws it | Vanilla pipelines |
|---|---|---|---|---|
| `LINES` (2,2,false) | `GL_TRIANGLES` | `TRIANGLE_LIST` | Indexed with `sharedSequentialLines` (per 4 verts: `i, i+1, i+2, i+3, i+2, i+1`); the vertex shader extrudes the line into a quad using `gl_VertexIndex % 2` | 8 (`lines*`, `secondary_block_outline`, OIT lines) |
| `DEBUG_LINES` (2,2,false) | `GL_LINES` | `LINE_LIST` | true lines | 0 |
| `DEBUG_LINE_STRIP` (2,1,true) | `GL_LINE_STRIP` | `LINE_STRIP` | | 0 |
| `POINTS` (1,1,false) | `GL_POINTS` | `POINT_LIST` | `gl_PointSize` from `LineWidth` attribute | 4 (`debug_points` + OIT) |
| `TRIANGLES` (3,3,false) | `GL_TRIANGLES` | `TRIANGLE_LIST` | | 39 (incl. all fullscreen passes: `draw(3,1,0,0)`) |
| `TRIANGLE_STRIP` (3,1,true) | `GL_TRIANGLE_STRIP` | `TRIANGLE_STRIP` | | 1 (`leash`) |
| `TRIANGLE_FAN` (3,1,true) | `GL_TRIANGLE_FAN` | `TRIANGLE_FAN` | **non-indexed** `draw(10,1,0,0)` (sky discs), `draw(18,1,0,0)` (sunrise); **indexed** with the identity `sharedSequential` buffer via `StagedVertexBuffer` (debug fans) | 6 (`sky`, `sunrise_sunset`, `debug_triangle_fan` + its 3 OIT variants) |
| `QUADS` (4,4,false) | `GL_TRIANGLES` | `TRIANGLE_LIST` | Indexed with `sharedSequentialQuad` (per 4 verts: `i, i+1, i+2, i+2, i+3, i`) or a sorted index buffer | 161 |

  `indexCount(vertexCount)`: `LINES`/`QUADS` → `vertexCount/4*6`, others → `vertexCount`. The shared index
  buffers live in `RenderSystem` (`sharedSequential`, `sharedSequentialQuad`, `sharedSequentialLines`,
  `RenderSystem.getSequentialBuffer(PrimitiveTopology)`).
- `IndexType { SHORT(2), INT(4) }`, `least(n)` picks INT if any bit ≥ 16 is set.

### 1.7 `VertexFormat`, `VertexFormatElement`, `GpuFormat`

```java
VertexFormat.builder(int stepRate)          // 0 = per vertex, N > 0 = per instance with divisor N
  .addAttribute(name, GpuFormat)              // offset = running offset; offset += blockSize
  .addAttribute(name, int stride, GpuFormat)  // offset += stride (explicit padding)
  .addAttribute(name, GpuFormat, columnCount) // matrix-like: columnCount elements with the SAME name
  .addAttribute(name, offset, stride, GpuFormat, columnCount)
  .build()                                     // vertexSize = final offset; must be a multiple of 4
public record VertexFormatElement(String name, int offset, GpuFormat format)
```

Builder rules: ≤ 16 elements per format; each element's offset must be a multiple of
`format.byteAlignment()` (= component byte size); names unique (except the repeated name of a multi-column attribute);
vertex size multiple of 4. `getElements()` keeps declaration order.

`GpuFormat` (`RP/api/GpuFormat.java`): `(ComponentType, componentCount)`; `blockSize = byteSize * count`,
`byteAlignment = byteSize`. Component types: `UNORM_8, SNORM_8, UINT_8, SINT_8, UNORM_16, SNORM_16, UINT_16,
SINT_16, FLOAT_16, UINT_32, SINT_32, FLOAT_32, OPAQUE_8/16/32/64`. Packed/depth formats (`RGB10A2_*`,
`RG11B10_FLOAT`, `D*`, `S8_UINT`) are `OPAQUE_*` with `componentCount = 1` and are **rejected as vertex attributes**
by the frontend (§2.4b).

Vanilla vertex formats (`SRC/com/mojang/blaze3d/vertex/DefaultVertexFormat.java`); attribute formats:
`Position = RGB32_FLOAT`, `Color = RGBA8_UNORM`, `UV0 = RG32_FLOAT`, `UV1 = RG16_SINT`, `UV2 = RG16_SINT`,
`UV3 = RG32_FLOAT`, `Normal = RGBA8_SNORM`, `LineWidth = R32_FLOAT`, `ChunkPosition = RGB32_SINT`,
`ChunkVisibility = R32_FLOAT`.

| Format | Elements (offset) | Stride | Step |
|---|---|---|---|
| `BLOCK` | Position 0, Color 12, UV0 16, UV2 24 | 28 | vertex |
| `CHUNK_DATA_INSTANCED` | ChunkPosition 0, ChunkVisibility 12 | 16 | **instance, divisor 1** |
| `ENTITY` | Position 0, Color 12, UV0 16, UV1 24, UV2 28, Normal 32 | 36 | vertex |
| `ENTITY_GLINT_SPECIAL` | Position 0, Color 12, UV0 16, UV1 24, UV2 28, UV3 32, Normal 40 | 44 | vertex |
| `PARTICLE` | Position 0, UV0 12, Color 20, UV2 24 | 28 | vertex |
| `POSITION` | Position 0 | 12 | vertex |
| `POSITION_COLOR` | Position 0, Color 12 | 16 | vertex |
| `POSITION_COLOR_NORMAL` | Position 0, Color 12, Normal 16 | 20 | vertex (no vanilla pipeline uses it) |
| `POSITION_COLOR_LIGHTMAP` | Position 0, Color 12, UV2 16 | 20 | vertex |
| `POSITION_TEX` | Position 0, UV0 12 | 20 | vertex |
| `POSITION_TEX_COLOR` | Position 0, UV0 12, Color 20 | 24 | vertex |
| `POSITION_COLOR_TEX_LIGHTMAP` | Position 0, Color 12, UV0 16, UV2 24 | 28 | vertex (no vanilla pipeline uses it) |
| `POSITION_TEX_LIGHTMAP_COLOR` | Position 0, UV0 12, UV2 20, Color 24 | 28 | vertex |
| `POSITION_TEX_COLOR_NORMAL` | Position 0, UV0 12, Color 20, Normal 24 | 28 | vertex (no vanilla pipeline uses it) |
| `POSITION_COLOR_LINE_WIDTH` | Position 0, Color 12, LineWidth 16 | 20 | vertex |
| `POSITION_COLOR_NORMAL_LINE_WIDTH` | Position 0, Color 12, Normal 16, LineWidth 20 | 24 | vertex |

All vanilla offsets are 4-byte aligned. Vanilla uses vertex buffer slots 0 and 1 only (slot 1 = per-instance chunk
data for the `*_multidraw` terrain pipelines).

### 1.8 `CompiledRenderPipeline` and `Pending`

```java
public interface CompiledRenderPipeline extends UncheckedAutoCloseable { boolean isClosed();
   @FunctionalInterface interface Pending { Pending NULL = () -> null; @Nullable CompiledRenderPipeline finishCompile(); } }
// backend side (RP/backend/api/BackendRenderPipeline.java):
public interface BackendRenderPipeline extends UncheckedAutoCloseable { boolean isClosed();
   @FunctionalInterface interface Pending { Pending NULL = () -> null; @Nullable BackendRenderPipeline finishCompile(); } }
```

The only implementation of `CompiledRenderPipeline` is `FrontendRenderPipeline` (§2.5).

---

## 2. The frontend compile path

### 2.1 Entry points and threads

`GpuDevice.compilePipeline(RenderPipeline, ShaderSource, Executor)` → `FrontendGpuDevice.compilePipeline` →
`PipelineBuilder.compilePipeline` (`RP/frontend/shaders/PipelineBuilder.java:366`). Callers:

| Caller | `executor` (runs frontend + `backend.compilePipeline`) | Who calls `finishCompile()` |
|---|---|---|
| `ShaderManager.reload` → `compilePipelines` (`SRC/net/minecraft/client/renderer/ShaderManager.java:95`) for `RenderPipelines.requiredPipelines()` and `optionalPipelines()` | `Util.backgroundExecutor().forName("resourceLoad")` (worker pool; all pipelines in parallel) | `thenApplyAsync(Pending::finishCompile, reloadExecutor)`; `reloadExecutor` is the `Minecraft` instance, i.e. the **render/main thread** |
| `PipelineCache.get(pipeline)` (`SRC/com/mojang/blaze3d/pipeline/PipelineCache.java:33`), reached through `RenderSystem.getCompiledPipeline(...)` for any pipeline not precompiled (post-effect passes, the 3 location-colliding pipelines, startup UI) | `Util.backgroundExecutor()` | the caller, right after `.join()`: the **render thread**, synchronously (a frame hitch) |
| `GameRenderer.preloadUiShader` (startup, before resource packs exist) | via fallback `PipelineCache` | render thread; compiles `GUI`, `GUI_TEXTURED`, and `TRACY_BLIT` if Tracy |

Consequences for a backend:

- `GpuDeviceBackend.compilePipeline(CreateInfo)` **must be thread-safe** and is the right place for expensive work
  (SPIR-V→MSL, `newLibraryWithSource:`, and ideally `newRenderPipelineState...`). Metal device creation calls are
  thread-safe.
- `Pending.finishCompile()` should be cheap (it runs on the render thread). GL does all GL calls there; Vulkan does
  everything in `compilePipeline` and returns `() -> pipeline`.
- Required pipelines that fail → `ShaderManager.apply` throws
  `RuntimeException("Failed to load required shader programs: ...")` (resource reload fails). Optional failures are
  only logged. Lazy (`PipelineCache`) failures make `RenderSystem.getCompiledPipeline` throw
  `IllegalStateException("Failed to find or load pipeline ...")`; `PostChain.createPass` uses
  `getCompiledPipelineNullable` and turns `null` into `ShaderManager.CompilationException` (logged, effect disabled).
  A backend *exception* during a lazy compile escapes `join()` as a `CompletionException` instead.

### 2.2 `GlslCompiler` (`RP/frontend/shaders/GlslCompiler.java`)

Constructed once per `PipelineBuilder` with `(deviceInfo.isZZeroToOne(), deviceInfo.features().shaderDrawParameters())`.
Per shader compile (`compileToSpv(name, source, type, defines, shaderSource)`), fresh options
(`createBaseShaderOptions`, lines 74-93):

```java
shaderc_compile_options_set_target_env(opts, shaderc_target_env_vulkan /*0*/, shaderc_env_version_vulkan_1_2 /*4202496*/);
shaderc_compile_options_set_auto_bind_uniforms(opts, true);   // bindings auto-assigned (later overwritten, §2.4d)
shaderc_compile_options_set_preserve_bindings(opts, false);
shaderc_compile_options_set_generate_debug_info(opts);        // keeps OpName: REQUIRED, matching is by name
shaderc_compile_options_set_optimization_level(opts, shaderc_optimization_level_zero);
if (isZeroToOne)                  add_macro("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
if (RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds().isExplicitDepthRequired())
                                  add_macro("RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE", "");
if (shaderDrawParameters)         add_macro("RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE", "");
// then pipeline defines: values first (key, value), then flags (key, "")
shaderc_compile_options_set_include_callbacks(opts, resolver, release, 0);
shaderc_compile_into_spv(compiler, source, kind /*vertex 0, fragment 1*/, name /*e.g. "minecraft:core/terrain"*/, "main", opts);
```

- Non-zero status → `ShaderCompileException("Couldn't parse GLSL: " + shaderc error)`.
- The SPIR-V bytes are copied into a `MemoryUtil.memCalloc` buffer (native byte order) and wrapped in
  `SPIRVModule(ByteBuffer spv, ShaderType)`.
- Compilers (`shaderc_compiler_t`) are pooled; the class is safe to use from many threads.
- Note: `RenderSystem.getDevice()` (the `FrontendGpuDevice`, which delegates `getDeviceInfo()` to the backend) is read
  on the worker thread for the explicit-depth hint.
- Vanilla GLSL is `#version 330` + `#extension GL_ARB_separate_shader_objects : require`, compiled with Vulkan
  semantics (so `gl_VertexIndex` is available, and plain `uniform sampler2D` / `layout(std140) uniform Block`
  without bindings are legal thanks to auto-bind). Output is **SPIR-V 1.5** (header word `0x00010500`).

### 2.3 `SPIRVModule` reflection (`RP/frontend/shaders/SPIRVModule.java`)

`reflect()` (lazy, cached, not synchronized) creates an spvc context, parses the words
(`spvc_context_parse_spirv`), creates a **reflection-only** compiler (`SPVC_BACKEND_NONE`, capture mode COPY) and
`spvc_compiler_create_shader_resources` (**all declared resources, not just active ones**). It builds:

- `inputs()` / `outputs()`: `SPVC_RESOURCE_TYPE_STAGE_INPUT (3)` / `STAGE_OUTPUT (4)`. For each: name, type handle,
  and the **word offset of its `Location` decoration (30)** inside the SPIR-V. `location()` reads that word *live*
  from the buffer; `location(int)` writes it (never called by the frontend). `decoration(n)` queries spvc.
- `descriptors()`: concatenation, in this order, of resource types `SpvUtil.DESCRIPTOR_TYPES = [1 UNIFORM_BUFFER,
  2 STORAGE_BUFFER, 6 STORAGE_IMAGE, 7 SAMPLED_IMAGE, 10 SEPARATE_IMAGE, 11 SEPARATE_SAMPLERS]`. For each: name,
  type, `resourceType()`, and the word offsets of its `DescriptorSet (34)` and `Binding (33)` decorations;
  `descriptorSetIndex()/binding()` read the live words, the setters **patch the SPIR-V buffer in place**.
- `pushConstants()`: `SPVC_RESOURCE_TYPE_PUSH_CONSTANT (9)`, each with `spvc_compiler_get_declared_struct_size`.
- `Type`: `baseType()` (`SPVC_BASETYPE_*`: 7 int32, 8 uint32, 12 fp16, 13 fp32, 15 struct, 16 image,
  17 sampled_image, ...), `dimensions()` (`SpvDim` for image/sampled image, else `Integer.MAX_VALUE`:
  0 1D, 1 2D, 2 3D, 3 Cube, 4 Rect, 5 Buffer, 6 SubpassData), `vectorSize()`, `arrayDimensions()`, `arrayLength(i)`.

`samplerBuffer`/`isamplerBuffer` are reported as `SAMPLED_IMAGE` (7) with dim `Buffer` (5). UBO names are block names.

Pitfall: the spvc IR inside the reflection object was built from a *copy* of the original words, so after the
frontend patches bindings, the reflection compiler's own view is stale; the `Descriptor.binding()` accessor is not
(it reads the buffer). A backend that needs post-patch information must either use the accessors or re-parse
`module.spv()`.

### 2.4 `PipelineBuilder.generateBackendCreateInfo`, step by step (`PipelineBuilder.java:65-364`)

**(a) Shader stages.** For each entry of `pipeline.getShaders()` (EnumMap order: VERTEX, then FRAGMENT): load the
source (missing → `LOGGER.error` and return `null`), `compileToSpv`, and add
`new CreateInfo.Shader(shaderId.toString(), "main", spvModule)`. So `CreateInfo.shaders()` is always
`[vertex, fragment]`, `name` is the shader id (e.g. `minecraft:core/entity`, shared by all define-variants) and
`entryPoint` is always `"main"`.

**(b) Vertex buffers and attributes** (lines 86-174):

```java
for (int i = 0; i < vertexBindings.size(); i++) {           // slot index 0..15
   VertexFormat vb = vertexBindings.get(i);
   if (vb == null) continue;
   vertexBuffers.add(new VertexBuffer(i, vb.getVertexSize(), vb.getStepRate()));   // even if no attribute is used
   for (VertexFormatElement e : vb.getElements()) {
      InterfaceVariable input = vertexShaderInputsByName.get(e.name());   // match BY NAME
      if (input == null) continue;                                        // element not consumed: no AttribBinding
      int loc = e.name().equals(previousElementName) ? previousElementLocation + 1  // 2nd+ column of a matrix attribute
                                                     : input.location();             // GLSL layout(location)
      attribBindings.add(new AttribBinding(i, loc, e.offset(), e.format()));
      attribFormats.put(loc, e.format());
      ...
```

Then every vertex shader input (all declared inputs, used or not) is validated:

- `Component` decoration (31) present → error "has component decoration, this is not permitted".
- No element mapped to its location → error "does not have a matching vertex buffer element".
- Base type must match the format: `UNORM_8, SNORM_8, UNORM_16, SNORM_16, FLOAT_16, FLOAT_32` → `fp32` (13);
  `UINT_*` → `uint32` (8); `SINT_*` → `int32` (7); `OPAQUE_*` → error. So `ivec2 UV2` needs a `*_SINT` format,
  and normalized/half formats feed `float` inputs (no fp16 inputs).
- Shader vector size must be ≤ format component count (a `vec3 Normal` from `RGBA8_SNORM` is fine; a `vec4` from
  `RGB32_FLOAT` is an error, so no implicit `w = 1` ever happens).

Only the base location of a matrix input is checked. Duplicate locations are not detected.

**(c) Stage interface** (lines 176-253): `generateSlotMap` over vertex outputs and fragment inputs: `Component`
decoration forbidden; base types int64/uint64/fp64/struct (9, 10, 14, 15) forbidden; arrays occupy
`product(array lengths)` consecutive locations (matrices are *not* expanded). For each fragment input location there
must be a vertex output at that location with equal base type, vector length and `Flat` decoration (14). Extra
vertex outputs are allowed. Fragment outputs are **not** checked against `colorTargetStates`.

**(d) Uniforms** (lines 255-328):

```java
pipelineUniformsByName = flattenUniforms(pipeline.getBindGroupLayouts()) by name;
for (Shader shader : shaderCreateInfos)                         // vertex, then fragment
   for (Descriptor d : shader.module().reflect().descriptors()) {   // UBO, SSBO, storage img, sampled img, sep img, sep sampler
      UniformDescription u = pipelineUniformsByName.get(d.name());
      if (u == null) throw new ShaderCompileException("Unable to find shader defined uniform (" + d.name() + ")");
      int newBinding = uniformBindings.computeIfAbsent(d.name(), k -> uniformBindings.size());   // dense, first-seen
      if (new) { record d.resourceType(), d.type().dimensions(); uniforms.add(copy of u); }
      else if (resource type or dimensions differ from the first stage) throw "... does not match across all stages";
      d.binding(newBinding);          // PATCHES the SPIR-V word
      d.descriptorSetIndex(0);        // PATCHES the SPIR-V word
   }
for (each name in uniformBindings) {
   expected resource type: UNIFORM_BUFFER → 1, COMBINED_IMAGE_SAMPLER → 7, TEXEL_BUFFER → 7; mismatch → error
   if image: dim 0 (1D) → error "1D textures not supported"; 2 (3D) → error "3D textures not supported";
             1 (2D), 3 (Cube), 4 (Rect) → must be COMBINED_IMAGE_SAMPLER; 5 (Buffer) → must be TEXEL_BUFFER; else error
}
```

Exact semantics:

- `CreateInfo.uniforms()` contains **every resource declared in the SPIR-V of either stage**, even if no code reads it
  (e.g. `Fog` declared by `fog.glsl` in `clouds.vsh`). It does **not** contain layout-declared names the shaders do
  not declare (e.g. `Globals` for most pipelines).
- **Binding index = position in `CreateInfo.uniforms()`; descriptor set = 0, for every resource in both stages.**
  Example (`pipeline/oit_transmittance_clouds`): `0 CloudInfo, 1 Projection, 2 DynamicTransforms, 3 Fog (declared, unused
  in VS), 4 CloudFaces (texel buffer), 5 DepthBoundsSampler (FS only)`; the fragment shader's `Projection` and `Fog`
  reuse bindings 1 and 3.
- Within a stage and resource type, order = SPIRV-Cross's variable (SPIR-V id) order. In the dump it looks like
  "first referenced in the code, unused declarations last" (e.g. `clouds.vsh` declares `Fog`, `DynamicTransforms`,
  `Projection`, then `CloudInfo`, but gets `CloudInfo, Projection, DynamicTransforms, Fog`) **[speculative as a rule]**.
  It is deterministic for given sources and defines; treat it as opaque.
- SSBOs, storage images, separate images and separate samplers are rejected (type mismatch or unknown name).
- `sampler2DArray`, `isampler2D`, `usampler2D`, shadow samplers pass the dimension check (2D) but have no special
  support anywhere (`GpuSampler` has no compare mode). Sampler **arrays** (`uniform sampler2D s[4]`) are reflected as
  one descriptor and get one binding; the Vulkan backend declares `descriptorCount = 1`, so they are effectively
  unsupported. Vanilla uses none of these.
- The sampled type of a texel buffer (`samplerBuffer` vs `isamplerBuffer` vs `usamplerBuffer`) is **not** checked
  against `UniformDescription.gpuFormat()`. A mismatch would be a Metal validation error.

**(e) Push constants** (lines 330-344): per shader, at most one push-constant block ("Shader may define at most one
push_constant block"), and its declared struct size must be ≤ `pipeline.pushConstantSize()`. No offset/binding
remapping happens; both stages address the same byte range `[0, pushConstantSize)`. Vanilla: none.

**(f) Result** (line 346):

```java
new CreateInfo(pipeline.getLocation().toString(), List.copyOf(shaderCreateInfos), List.copyOf(vertexBuffers),
   List.copyOf(attribBindings), List.copyOf(uniforms), pipeline.pushConstantSize(), pipeline.getDepthStencilState(),
   pipeline.getPolygonMode(), pipeline.isCull(), pipeline.getColorTargetStates(), pipeline.getPrimitiveTopology());
```

Any `ShaderCompileException` in (a)-(e) is caught (line 359): `LOGGER.error("Couldn't compile pipeline ({}): ", ...)`,
all modules created so far are closed, and `null` is returned. Other exceptions (e.g. `IllegalStateException` from
`SpvUtil.crashIfError`, a `RuntimeException` in a backend) are **not** caught.

### 2.5 After the backend: `FrontendRenderPipeline` and module lifetime

```java
// PipelineBuilder.compilePipeline, inside supplyAsync(..., executor):
createInfo = generateBackendCreateInfo(...);            if (createInfo == null) return CompiledRenderPipeline.Pending.NULL;
backendPendingPipeline = backendDevice.compilePipeline(createInfo);     // worker thread
return () -> {                                                           // the frontend Pending
   BackendRenderPipeline backendPipeline = backendPendingPipeline.finishCompile();
   executor.execute(() -> shaderCreateInfos.forEach(shader -> shader.module().close()));   // frees SPIR-V + spvc ctx
   return backendPipeline == null ? null : new FrontendRenderPipeline(
      pipeline.getLocation().toString(), backendPipeline,
      unmodifiable copy of pipeline.getVertexFormatBindings(),
      Object2IntMaps.unmodifiable(uniformBindings),                       // name -> binding index
      BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()),    // ALL declared uniforms (not CreateInfo.uniforms)
      unmodifiable copy of pipeline.getColorTargetStates(), pipeline.wantsDepthTexture(), pipeline.pushConstantSize());
};
```

- `SPIRVModule.close()` frees the SPIR-V buffer (`memFree`) and destroys the reflection spvc context. It runs on the
  executor (a worker), possibly concurrently with the caller continuing. **A backend must have finished reading
  `module.spv()` and `module.reflect()` before its `finishCompile()` returns.** Anything needed later (MSL, entry
  name, per-stage usage masks) must be copied out.
- If the backend's `finishCompile()` throws, the modules leak (not closed). Minor.
- `FrontendRenderPipeline.close()` → `backendRenderPipeline.close()`; `isClosed()` delegates.
- Wireframe check happens before anything else: `PolygonMode.WIREFRAME && !features().wireframeFillMode()` →
  `LOGGER.error(...)` and `CompletableFuture.completedFuture(Pending.NULL)`.

### 2.6 Error handling matrix

| Failure | Where | Result |
|---|---|---|
| Wireframe unsupported | `compilePipeline` | log error, `Pending.NULL` (no shader compile) |
| Shader source missing | `generateBackendCreateInfo` | log error, `null` → `Pending.NULL` |
| GLSL error / include missing | shaderc → `ShaderCompileException` | caught, log "Couldn't compile pipeline (loc)", `Pending.NULL` |
| Interface/uniform/attribute/push-constant mismatch | `ShaderCompileException` | caught, same |
| spvc parse error in reflection | `SpvUtil.throwIfError` → `ShaderCompileException` | caught, same |
| Backend `compilePipeline` throws | inside `supplyAsync` | future completes exceptionally → reload fails (or `join()` throws `CompletionException` in `PipelineCache.get`) |
| Backend returns `Pending.NULL` / `finishCompile()` returns `null` | | pipeline is `null` (required → reload `RuntimeException`; lazy → `IllegalStateException`) |

The GL backend returns `Pending.NULL` when SPIR-V→GLSL fails and `null` from `finishCompile` when GLSL
compile/link fails (both logged). The Vulkan backend throws (`VulkanUtils.crashIfFailure`). **Metal should log the
pipeline name, both MSL sources (when debugging) and the Metal error, and return `Pending.NULL` / `null`.**

### 2.7 How the frontend uses a compiled pipeline (contract for the render pass)

`FrontendRenderPass` (`RP/frontend/FrontendRenderPass.java`):

- `setPipeline(CompiledRenderPipeline)`: requires `FrontendRenderPipeline`; **always** checks that
  `colorTargetStates.size() == colorAttachments.size()` and, for each non-null attachment, that the target state is
  non-null with **exactly the attachment texture's format**. Then `backend.setPipeline(backendPipeline)`, then
  re-sends every remembered uniform (`this.uniforms.forEach(this::setUniform)`), then `constantsPushed = false`.
  So the backend sees `setPipeline` followed by `setUniform(index, value)` for each cached name the new pipeline
  uses; a backend should clear its per-index table on `setPipeline` (Vulkan does).
- `setUniform(name, value)`: remembered by name; forwarded as `backend.setUniform(uniformIndices.get(name), value)`
  only if the bound pipeline's shaders declare that name. Values: `GpuBufferSlice` for `UNIFORM_BUFFER` (offset
  aligned to `limits().minUniformOffsetAlignment()`, checked always) and `TEXEL_BUFFER` (must be the whole buffer,
  checked only in strict mode); `TextureViewAndSampler` for `COMBINED_IMAGE_SAMPLER`; `null` to unset.
- `pushConstants(ByteBuffer)`: requires a bound pipeline and `value.remaining() >= pushConstantSize`; forwards the
  buffer as is (Vulkan pushes `value.remaining()` bytes at offset 0).
- `validateDraw` checks (missing uniforms, wrong usages, missing vertex buffers, missing push constants, missing
  depth attachment for a depth pipeline) run **only when `FrontendGpuDevice.STRICT_VALIDATION`**, which is
  `SharedConstants.IS_RUNNING_IN_IDE`. In a normal game the backend gets no such guarantees. Note it validates
  `boundPipeline.uniforms()` = *all declared* uniforms, so in strict mode even unused layout uniforms must be set.

---

## 3. `BackendRenderPipeline.CreateInfo` field reference

```java
record CreateInfo(String name, List<Shader> shaders, List<VertexBuffer> vertexBuffers, List<AttribBinding> attribBindings,
                  List<BindGroupLayout.UniformDescription> uniforms, int pushConstantsSize,
                  @Nullable DepthStencilState depthStencilState, PolygonMode polygonMode, boolean cull,
                  List<@Nullable ColorTargetState> colorTargetStates, PrimitiveTopology primitiveTopology)
record Shader(String name, String entryPoint, SpvModule module)
record VertexBuffer(int bufferSlot, int stride, int stepRate)
record AttribBinding(int bufferSlot, int location, int offset, GpuFormat format)
```

| Field | Contract |
|---|---|
| `name` | `RenderPipeline.getLocation().toString()`. For logs/labels only; **not unique**. |
| `shaders` | Exactly 2 entries, `[VERTEX, FRAGMENT]`. `name` = shader id (not unique per variant), `entryPoint` = `"main"`, `module` = patched SPIR-V (valid until `finishCompile()` returns). |
| `vertexBuffers` | One entry per non-null `VertexFormat` slot, ascending slot order. `stride` = `VertexFormat.getVertexSize()` (multiple of 4). `stepRate` 0 = per-vertex; N > 0 = per-instance, advance every N instances (Vulkan: `INPUT_RATE_INSTANCE` + `VK_EXT_vertex_attribute_divisor` divisor N). |
| `attribBindings` | One per (consumed element, column). `bufferSlot` refers to `VertexBuffer.bufferSlot`; `location` = shader input location; `offset` = element offset within the vertex; `format` = element `GpuFormat` (never `OPAQUE_*`). Ordered by slot, then element order (not by location). |
| `uniforms` | Index *i* = SPIR-V binding *i* in set 0, for both stages. Each entry is a copy of the layout's `UniformDescription` (`name`, `type`, `gpuFormat` for texel buffers). Includes resources declared but unused by the code. |
| `pushConstantsSize` | `RenderPipeline.pushConstantSize()` (0..128), the size of the single shared range at offset 0. Not the shader's struct size. |
| `depthStencilState` | `null` → depth test off and depth write off. Pipelines with `null` are also used in passes that **have** a depth attachment (e.g. `SkyRenderer` opens the "Sky" pass with the depth texture and draws `pipeline/sky`, which has no depth state; Vulkan builds a second pipeline object for this, §5.1). Non-null → test with `depthTest`, write if `writeDepth`, bias `(constant, slope)` enabled iff either is non-zero. Pipelines with a depth state are only used with a `D32_FLOAT` attachment. |
| `polygonMode` | `FILL` or `WIREFRAME` (lines). |
| `cull` | `true` → cull back faces, where the **front face is GL counter-clockwise** (Vulkan backend: `CULL_MODE_BACK`, `FRONT_FACE_CLOCKWISE` because of its Y convention, §6.4). |
| `colorTargetStates` | Same list as the `RenderPipeline` (may contain `null`, may be empty). Size = number of color attachments in every pass this pipeline is used in; non-null entry *i* format == attachment *i* texture format (checked by the frontend); `null` entry *i* = attachment slot must be absent (no format, write mask 0). One `BlendFunction` shared by all targets that blend. |
| `primitiveTopology` | Raw enum. `QUADS` and `LINES` must be treated as triangle lists (indices come from the game). `TRIANGLE_FAN` needs emulation on Metal. |

---

## 4. `RENDERPEARL_*` macros

Defined by `GlslCompiler.createBaseShaderOptions` from `DeviceInfo` of the **active backend**; each is an empty macro
(`#define X`). Grep of the entire 26.3 client (Java + all shader assets) finds them only in the files below.

| Macro | Defined when | GLSL effect in vanilla | Metal value |
|---|---|---|---|
| `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` | `DeviceInfo.isZZeroToOne()` (Vulkan: always `true`; GL: `GL_ARB_clip_control`, then `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`) | `include/oit_common.glsl` `deviceToLinearDepth`: when **not** defined, remaps `deviceDepth = (deviceDepth - 0.5) * 2.0` before `ProjMat[3][2] / (deviceDepth + ProjMat[2][2])`. The projection matrix itself is built for the same convention in Java (`Projection.java:69`, `Camera.java:188` read `isZZeroToOne()`). | **true** (Metal clip z ∈ [0, w]) |
| `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` | `hintsAndWorkarounds().isExplicitDepthRequired()`; GL and Vulkan both set it to `Util.isAppleSiliconMac(rendererOrDeviceName)` = name `startsWith("Apple")` | `include/oit_add_transmittance.glsl` (`addTransmittance`) and `include/oit_sample.glsl` (`sampleColorForAccumulation`) add `gl_FragDepth = gl_FragCoord.z;`. Affects 27 OIT transmittance + 31 OIT accumulate pipelines (the 4 `OIT_ADDITIVE` transmittance variants discard instead). In MSL this becomes a `[[depth(any)]]` output. | **true on Apple GPUs** to match Mojang's workaround. **[speculative]** It costs early-Z/HSR on those passes; a later experiment could try `false` plus `MTLCompileOptions.preserveInvariance = YES`, comparing OIT output. |
| `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` | `features().shaderDrawParameters()` (Vulkan: always `true`; GL: `GL_ARB_shader_draw_parameters`) | **No vanilla shader reads it** (vanilla never uses `gl_InstanceIndex`). It tells shaders whether `gl_InstanceIndex` includes the draw's first instance (on GL 3.3 `gl_InstanceID` does not). | **true**: SPIRV-Cross maps `gl_InstanceIndex` to `[[instance_id]]`, which includes the base instance in Metal, like Vulkan. Do not enable SPIRV-Cross `MSL_ENABLE_BASE_INDEX_ZERO`. `shaderDrawParameters` has no other users in the client. |

Other device-dependent compile behaviour: `features().wireframeFillMode()` gates `WIREFRAME` pipelines (Metal supports
`MTLTriangleFillModeLines`, so report `true`).

---

## 5. How the existing backends consume `CreateInfo`

### 5.1 Vulkan (`RP/backend/vulkan/VulkanRenderPipeline.java`, `VulkanDevice.compilePipeline:263`)

`compilePipeline` builds everything synchronously on the worker thread and returns `() -> pipeline`:

- **Descriptor set layout**: one binding per `uniforms()` entry, `binding = i`, `descriptorCount = 1`,
  `stageFlags = VERTEX|FRAGMENT (17)`, type `UNIFORM_BUFFER (6)` / `COMBINED_IMAGE_SAMPLER (1)` /
  `UNIFORM_TEXEL_BUFFER (4)`; flags `PUSH_DESCRIPTOR_BIT_KHR (1)` (descriptors are pushed per draw).
- **Pipeline layout**: that set + one push-constant range `{stageFlags = 0x7FFFFFFF (all), offset 0, size =
  pushConstantsSize}` if non-zero.
- **Shader modules** from `module.spv()` (the patched words), entry `shader.entryPoint()`.
- **Vertex input**: binding per `VertexBuffer` (`inputRate = stepRate > 0 ? INSTANCE : VERTEX`, divisor struct when
  instanced), attribute per `AttribBinding` (`location, binding = bufferSlot, offset, VulkanConst.toVk(format)`).
- **Input assembly**: `VulkanConst.toVk(topology)` (QUADS/LINES/TRIANGLES → TRIANGLE_LIST, fans/strips native).
- **Rasterization**: `polygonMode` FILL/LINE, `cullMode = cull ? BACK : NONE`, **`frontFace = CLOCKWISE (1)`**,
  `lineWidth 1`, depth bias from the depth state.
- **Depth**: if non-null, `depthTestEnable = true`, write flag, compare op; else all disabled.
- **Blend**: one attachment state per `colorTargetStates` entry; `null` → write mask 0; blend enabled iff the entry
  has a blend function.
- Dynamic state: viewport and scissor. Multisample 1. Dynamic rendering (`VkPipelineRenderingCreateInfoKHR`) with
  color formats from the targets (`null` → `VK_FORMAT_UNDEFINED`) and **depth format hard-coded to `D32_SFLOAT`**.
- **Two pipeline objects** when `depthStencilState == null`: `withDepthPipeline` (depth format D32) and
  `withoutDepthPipeline` (depth format UNDEFINED); `VulkanRenderPass.setPipeline` picks by whether the pass has a
  depth attachment. With a depth state only `withDepthPipeline` exists.
- Errors → `crashIfFailure` (throws). Destruction is deferred through the encoder's destroy queue.

### 5.2 OpenGL (`RP/backend/opengl/GlPipelineRecompiler.java`, `GlProgram.java`, `GlRenderPipeline.java`, `VertexArray.java`)

- `compilePipeline` (worker): SPIR-V → GLSL 330 with SPIRV-Cross (`SPVC_BACKEND_GLSL`): `GLSL_VERSION = 330`,
  `ENABLE_420PACK_EXTENSION = false`, `EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER = true`,
  `SUPPORT_NONZERO_BASE_INSTANCE = drawParametersSupported`; unless RenderDoc is attached also
  `FORCE_ZERO_INITIALIZED_VARIABLES` and `FLATTEN_MULTIDIMENSIONAL_ARRAYS`. It renames everything by location /
  set+binding: vertex inputs `_vert_input_%02d`, fragment outputs `_frag_output_%02d`, varyings
  `_interface_variable_%02d`, resources `_uniform_%02d_%02d` (set, binding) with block instances
  `_uniform_instance_%02d_%02d`, push constants block `_push_constants` / instance `_push_constants_instance`.
  This only works because of the dense set-0 binding contract.
- `finishCompile` (render thread): compile + link; `GlProgram.setupBindGroupLayouts(createInfo.uniforms())` looks each
  `_uniform_00_%02d` up by index: UBOs get sequential block bindings (the push-constant UBO first), samplers and
  texel buffers get sequential texture units; inactive ones become `null`.
- `VertexArray`: per `AttribBinding`, integer formats (`*_UINT`, `*_SINT`) use `glVertexAttribIPointer` /
  `glVertexAttribIFormat`; others use the float path with `normalized = UNORM/SNORM`. Divisor = `stepRate`.
- `GlRenderPipeline.bind()`: depth test/func/mask, polygon offset `(scale, constant)`, cull enable (front face is GL's
  default CCW), per-target color mask (`null` → mask 0) and blend enable, one global `glBlendFuncSeparate` /
  `glBlendEquationSeparate` from the first blending target, `glPolygonMode(GL_FRONT_AND_BACK, ...)`.

---

## 6. Metal mapping

Statements marked **(verified)** were checked by the probes on Apple M4 Pro (§Appendix B).

### 6.1 SPIR-V → MSL with SPIRV-Cross (LWJGL `Spvc`)

Recipe used by the probe (`Dump.toMsl`), which produced valid MSL for all 438 modules **(verified)**:

```java
spvc_context_create(&ctx);
spvc_context_parse_spirv(ctx, module.spv().asIntBuffer() /* duplicate, position 0 */, words, &ir);
spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL /*3*/, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &comp);
spvc_compiler_create_compiler_options(comp, &opts);
spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_VERSION, 20400);          // MSL 2.4 (macOS 12+)
spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true); // samplerBuffer -> texture_buffer<T>
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, topology == POINTS);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);         // see 6.4 (or flip the viewport instead)
int model = stage == VERTEX ? 0 /*SpvExecutionModelVertex*/ : 4 /*SpvExecutionModelFragment*/;
for (int b = 0; b < createInfo.uniforms().size(); b++) {                                 // set 0, binding b
   SpvcMslResourceBinding rb = ...; spvc_msl_resource_binding_init(rb);
   rb.stage(model).desc_set(0).binding(b).msl_buffer(b).msl_texture(b).msl_sampler(b);
   spvc_compiler_msl_add_resource_binding(comp, rb);
}
if (pushConstantsSize > 0) { rb.stage(model).desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET /*-1*/)
   .binding(SPVC_MSL_PUSH_CONSTANT_BINDING /*0*/).msl_buffer(PUSH_CONSTANT_INDEX); add... }
spvc_compiler_install_compiler_options(comp, opts);
spvc_compiler_compile(comp, &src);                                  // MSL text
String entry = spvc_compiler_get_cleansed_entry_point_name(comp, "main", model);   // "main0"
```

Option constants (decoded from `lwjgl-spvc-3.4.3`): `MSL_VERSION = 0x8000011`, `MSL_PLATFORM = 0x800001F`,
`MSL_TEXTURE_BUFFER_NATIVE = 0x8000022`, `MSL_ENABLE_POINT_SIZE_BUILTIN = 0x8000019`, `FLIP_VERTEX_Y = 0x1000004`,
`MSL_ENABLE_BASE_INDEX_ZERO = 0x800002D` (leave false), `MSL_INVARIANT_FP_MATH = 0x800002F`,
`MSL_ENABLE_POINT_SIZE_DEFAULT = 0x800005D`, `MSL_ARGUMENT_BUFFERS = 0x8000020` (leave false: discrete bindings).

What the generated MSL looks like (all observed in the probe output):

- Entry points: `vertex main0_out main0(main0_in in [[stage_in]], constant Projection& _132 [[buffer(0)]], ...,
  texture2d<float> Sampler2 [[texture(4)]], sampler Sampler2Smplr [[sampler(4)]])`. Only **active** resources become
  arguments (unused declared UBOs disappear), so per-stage usage can differ from `CreateInfo.uniforms()`.
- Stage inputs: `[[attribute(location)]]` with the GLSL location. Varyings: `[[user(locnN)]]` by location, so vertex
  outputs and fragment inputs pair up by location. `flat` → `[[flat]]`.
- `samplerBuffer`/`isamplerBuffer` → `texture_buffer<float|int>` + `.read(uint(i))` (needs MSL ≥ 2.1).
- `texelFetch(sampler2D, ivec2, lod)` → `.read(uint2(...), lod)` (no sampler). `textureLod` → `level()`,
  `textureGrad` → `gradient2d()`, `textureProj` → divide + `sample`, `samplerCube` → `texturecube<float>`.
- Fragment output arrays (`layout(location=0) out vec4 coeff[2]`) → `[[color(0)]]`, `[[color(1)]]`.
- `gl_FragDepth` → `float gl_FragDepth [[depth(any)]]`; `discard` → `discard_fragment()`;
  `gl_FrontFacing` → `[[front_facing]]`; `gl_FragCoord` → `[[position]]` (`.w` is 1/w in both APIs);
  `gl_VertexIndex` → `uint [[vertex_id]]`; `gl_PointSize` → `[[point_size]]` when the builtin option is on.
- Every vertex shader ends with `out.gl_Position.y = -(out.gl_Position.y);` when `FLIP_VERTEX_Y` is on.
- std140 blocks keep their GLSL offsets: SPIRV-Cross emits `packed_float3`/`packed_int3` where a scalar follows a
  `vec3` (e.g. `Globals`, `ChunkSection`) and `float3` where the std140 16-byte alignment already matches. Structs
  of UBOs that a shader declares but never uses are emitted without packing analysis; harmless.
- Locally indexed constant/local arrays use the `spvUnsafeArray<T,N>` helper; signed `%` uses `spvSMod`.

Measured cost (single thread, M4 Pro): 438 `newLibraryWithSource:` calls take **3.4-4.1 s cold** (~8-9 ms each);
an immediate re-run in a new process took **5 ms total** (macOS's Metal compiler cache). 219 PSO creations took
17-47 ms total in these runs (the GPU-binary cache was likely warm). There are only **169 distinct MSL sources**
among the 438 modules (variants differ by bindings/defines), so caching `MTLLibrary` by a hash of the MSL text cuts
compilation work by ~60%. **[speculative]** An `MTLBinaryArchive` keyed by the same hash would make first launch of
a new game version fast too.

### 6.2 `MTLRenderPipelineDescriptor` from `CreateInfo`

What `validate.m` did for every pipeline, all accepted by Metal including under `MTL_DEBUG_LAYER=1` **(verified)**:

- `vertexFunction`/`fragmentFunction` = `newFunctionWithName:@"main0"` from the two libraries.
- `vertexDescriptor`: for each `AttribBinding`: `attributes[location].format = vertexFormat(format)`,
  `.offset = offset`, `.bufferIndex = vbIndex(bufferSlot)`; for each `VertexBuffer`:
  `layouts[vbIndex(slot)].stride = stride`, `stepFunction = stepRate > 0 ? PerInstance : PerVertex`,
  `stepRate = max(stepRate, 1)`. The probe used `vbIndex(slot) = 30 - slot` (ARCHITECTURE.md's choice). Attributes
  the function does not declare are tolerated.
- `colorAttachments[i]`: `null` target → leave `pixelFormat = Invalid`; else pixel format, write mask (R=1, G=2,
  B=4, A=8 → `MTLColorWriteMaskRed/Green/Blue/Alpha`), `blendingEnabled` iff a blend function is present, factors and
  ops from the tables below.
- `depthAttachmentPixelFormat`: `Depth32Float` for the "with depth" variant, `Invalid` for the "without depth"
  variant. As in Vulkan, build both when `depthStencilState == null` (the pipeline may be used in passes with and
  without a depth attachment); with a depth state only the D32 variant is needed. Metal requires the PSO's attachment
  formats to equal the pass's, so key the variants on the pass's actual depth format.
- `inputPrimitiveTopology`: `Point` for POINTS, `Line` for DEBUG_LINES/DEBUG_LINE_STRIP, `Triangle` otherwise
  (required only for layered rendering on macOS, harmless to set).
- `rasterSampleCount = 1` (the API has no MSAA). No alpha-to-coverage.

`GpuFormat` → `MTLVertexFormat` (every format the frontend accepts as a vertex attribute):

| GpuFormat | MTLVertexFormat | GpuFormat | MTLVertexFormat |
|---|---|---|---|
| `R8_UNORM` / `R8_SNORM` | `UCharNormalized` / `CharNormalized` | `R8_UINT` / `R8_SINT` | `UChar` / `Char` |
| `RG8_UNORM` / `RG8_SNORM` | `UChar2Normalized` / `Char2Normalized` | `RG8_UINT` / `RG8_SINT` | `UChar2` / `Char2` |
| `RGB8_UNORM` / `RGB8_SNORM` | `UChar3Normalized` / `Char3Normalized` | `RGB8_UINT` / `RGB8_SINT` | `UChar3` / `Char3` |
| `RGBA8_UNORM` / `RGBA8_SNORM` | `UChar4Normalized` / `Char4Normalized` | `RGBA8_UINT` / `RGBA8_SINT` | `UChar4` / `Char4` |
| `R16_UNORM` / `R16_SNORM` | `UShortNormalized` / `ShortNormalized` | `R16_UINT` / `R16_SINT` | `UShort` / `Short` |
| `RG16_UNORM` / `RG16_SNORM` | `UShort2Normalized` / `Short2Normalized` | `RG16_UINT` / `RG16_SINT` | `UShort2` / `Short2` |
| `RGB16_UNORM` / `RGB16_SNORM` | `UShort3Normalized` / `Short3Normalized` | `RGB16_UINT` / `RGB16_SINT` | `UShort3` / `Short3` |
| `RGBA16_UNORM` / `RGBA16_SNORM` | `UShort4Normalized` / `Short4Normalized` | `RGBA16_UINT` / `RGBA16_SINT` | `UShort4` / `Short4` |
| `R16_FLOAT` … `RGBA16_FLOAT` | `Half`, `Half2`, `Half3`, `Half4` | `R32_UINT` … `RGBA32_UINT` | `UInt`, `UInt2`, `UInt3`, `UInt4` |
| `R32_FLOAT` … `RGBA32_FLOAT` | `Float`, `Float2`, `Float3`, `Float4` | `R32_SINT` … `RGBA32_SINT` | `Int`, `Int2`, `Int3`, `Int4` |

Vanilla only needs `Float3, UChar4Normalized, Float2, Short2, Char4Normalized, Float, Int3` (verified: `Short2` →
`int2` and `Char4Normalized` → `float3` inputs are accepted). Unaligned attribute offsets (1, 2, 9) and a stride of
10 were also accepted by PSO creation under API validation **(verified; draws with such layouts were not
exercised)**, so the frontend's alignment rules are at least as strict as Metal's.

Blend mapping: `ZERO→Zero, ONE→One, SRC_COLOR→SourceColor, ONE_MINUS_SRC_COLOR→OneMinusSourceColor,
SRC_ALPHA→SourceAlpha, ONE_MINUS_SRC_ALPHA→OneMinusSourceAlpha, DST_COLOR→DestinationColor,
ONE_MINUS_DST_COLOR→OneMinusDestinationColor, DST_ALPHA→DestinationAlpha,
ONE_MINUS_DST_ALPHA→OneMinusDestinationAlpha, SRC_ALPHA_SATURATE→SourceAlphaSaturated,
CONSTANT_COLOR→BlendColor, ONE_MINUS_CONSTANT_COLOR→OneMinusBlendColor, CONSTANT_ALPHA→BlendAlpha,
ONE_MINUS_CONSTANT_ALPHA→OneMinusBlendAlpha`; ops `ADD→Add, SUBTRACT→Subtract, REVERSE_SUBTRACT→ReverseSubtract,
MIN→Min, MAX→Max`. Blending on `RGBA32Float` with `Max` (OIT depth bounds) and `RGBA16Float` with `Add` was accepted.

Color/depth pixel formats that appear in pipelines: `RGBA8_UNORM → RGBA8Unorm`, `RGBA16_FLOAT → RGBA16Float`,
`RGBA32_FLOAT → RGBA32Float`, depth `D32_FLOAT → Depth32Float`. (Textures additionally use `R8_UNORM` (font atlas,
read with `.rrrr`), cube maps (`USAGE_CUBEMAP_COMPATIBLE`, 6 layers, `panorama`), and the `R8_SINT` texel buffer.)

### 6.3 Encoder state that Metal does not keep in the PSO

Apply on every `setPipeline` (and diff to avoid redundant calls):

| `CreateInfo` | Metal |
|---|---|
| `depthStencilState` non-null | `setDepthStencilState:` with `depthCompareFunction = map(depthTest)` (`ALWAYS_PASS→Always, LESS_THAN→Less, LESS_THAN_OR_EQUAL→LessEqual, EQUAL→Equal, NOT_EQUAL→NotEqual, GREATER_THAN_OR_EQUAL→GreaterEqual, GREATER_THAN→Greater, NEVER_PASS→Never`), `depthWriteEnabled = writeDepth`. Cache the 16 possible objects. |
| `depthStencilState == null` | `setDepthStencilState:` with **Always / no write** (must be set explicitly: the previous pipeline's state persists on the encoder). |
| depth bias | `setDepthBias:depthBiasConstant slopeScale:depthBiasScaleFactor clamp:0` (also reset to 0,0,0 when absent). **[speculative]** units match Vulkan's `depthBiasConstantFactor`/`SlopeFactor` closely enough for these small values; verify z-fighting on crumbling/text/world border. |
| `cull` | `setCullMode: cull ? MTLCullModeBack : MTLCullModeNone` |
| winding | `setFrontFacingWinding:MTLWindingClockwise` (with the Y flip, §6.4) |
| `polygonMode` | `setTriangleFillMode: FILL → Fill, WIREFRAME → Lines` |
| `primitiveTopology` | stored in the pipeline; used at draw time: `POINTS→Point`, `DEBUG_LINES→Line`, `DEBUG_LINE_STRIP→LineStrip`, `TRIANGLES/QUADS/LINES→Triangle`, `TRIANGLE_STRIP→TriangleStrip`, `TRIANGLE_FAN→`emulate (§6.6) |

### 6.4 Coordinate conventions (empirical, `probes/pipelines/conv.m`)

Test: a GL-style counter-clockwise triangle A(−1,−1) B(1,−1) C(−1,1), `cullMode None`, fragment writes red if
`[[front_facing]]` else green, read back the 4×4 target (row 0 = first row in memory):

| Setup | Covered rows | Front-facing with `MTLWindingClockwise` | with `MTLWindingCounterClockwise` |
|---|---|---|---|
| No flip | bottom rows (NDC y = −1 → last row) | back | **front** |
| `gl_Position.y = -y` in the vertex shader | **top rows (NDC y = −1 → row 0)** | **front** | back |
| Viewport `{originY = H, height = −H}` | **top rows** | **front** | back |

The GL backend (lower-left origin) and the Vulkan backend (positive viewport, flip at present:
`VulkanGpuSurface.blitFromTexture` blits with `dstOffsets` y from `copyHeight` to 0) both store NDC y = −1 in row 0.
`Screenshot.java:102` writes readback row `y` to image row `height - y - 1`, i.e. the game relies on that layout. So
Metal must flip Y (either way works; negative viewports passed API validation) and use **`MTLWindingClockwise`**,
matching Vulkan's `FRONT_FACE_CLOCKWISE`. With the flip, scissor rectangles, `gl_FragCoord`-based `texelFetch`, and
`uv = ndc * 0.5 + 0.5` full-screen blits all behave exactly as on GL/Vulkan, and presenting needs one vertical flip.

Also verified: a `Depth32Float` texture bound to a `texture2d<float>` argument can be `.read()` and `.sample()`d
(returns depth in `.r`), with API validation on. `blit_depth.fsh`, `integrate_depth.fsh` and the OIT shaders do
this with `uniform sampler2D`, and SPIRV-Cross emits `texture2d<float>` (never `depth2d`) for them.

### 6.5 Binding resources at draw time

Binding layout (ARCHITECTURE.md, consistent with the probe): uniform index *i* → `[[buffer(i)]]` for UBOs,
`[[texture(i)]] + [[sampler(i)]]` for combined image samplers, `[[texture(i)]]` (`texture_buffer<T>`) for texel
buffers; vertex buffer slot *s* → `[[buffer(30 - s)]]`.

- Vanilla maxima per pipeline (from the dump/Metal reflection): **12 uniforms** (`oit_accumulate_item_glint`);
  per stage ≤ 4 UBOs, ≤ 5 textures + 5 samplers (fragment), ≤ 2 textures (vertex, `Sampler1`/`Sampler2` lookups),
  ≤ 2 vertex buffers. Indices stay far below Metal's limits (31 buffers, 16 samplers, 31+ textures per stage).
- **Index collisions to guard against (latent, not hit by vanilla):** with `vbIndex = 30 - s`, UBO index 15 collides
  with slot 15, and ARCHITECTURE.md's push-constant index 24 collides with vertex slot 6. Assert at pipeline creation
  (`uniforms().size() <= 30 - maxSlot`, push-constant index not in the vertex range) or pick the push-constant
  index per pipeline from the free set.
- Per-stage usage: `SpvModule.Reflection.descriptors()` per shader lists *declared* resources; SPIRV-Cross only
  emits *active* ones. Binding a resource to a stage that does not use it is harmless; for exact masks use
  `spvc_compiler_get_active_interface_variables` + `spvc_compiler_create_shader_resources_for_active_variables`, or
  `MTLRenderPipelineReflection` (`binding.used`). Compute the masks before `finishCompile()` returns.
- **UBO sizes (verified):** Metal's argument size is the MSL struct size, rounded up to the struct alignment (16 for
  anything containing `float3`/`float4`/matrices). With `MTL_DEBUG_LAYER=1`, binding the exact-std140-size buffer
  fails: `argument fog[0] from Buffer(3) with offset(0) and length(40) has space for 40 bytes, but argument has a
  length(48)` (assertion, process abort). Vanilla sizes in §7.2. Allocate `USAGE_UNIFORM` buffers with
  `roundUp(size, 16)` (keep reporting the requested `size()`), and when a slice sits at the end of a buffer make sure
  `offset + mslSize <= length`. Exact-size allocations in vanilla: `FogRenderer` (40), `CloudRenderer` (44),
  `Lightmap` (92), post-effect custom uniform buffers (`BlurConfig` 12, `ColorConfig` 44, `RotScaleConfig` 20).
  Suballocated UBOs (`DynamicGpuDataStorage*`, `Lighting`, sprite animation) round their block size up to
  `DeviceLimits.minUniformOffsetAlignment()`, so reporting an alignment ≥ 16 (e.g. 256) keeps them safe.
- Texel buffers: `MTLBuffer newTextureWithDescriptor:offset:bytesPerRow:` with
  `textureBufferDescriptorWithPixelFormat:` (`R8_SINT → MTLPixelFormatR8Sint`, `texture_buffer<int>`). The frontend
  requires whole-buffer bindings, so offset 0. **[speculative]** respect `minimumTextureBufferAlignmentForPixelFormat:`
  for any non-zero offset.
- Push constants: `setVertexBytes`/`setFragmentBytes` into the push-constant index, at least
  `max(pushConstantsSize, MSL struct size)` bytes (same rounding trap as UBOs). They must be re-pushed after every
  `setPipeline` (the frontend resets `constantsPushed`).

### 6.6 Topology emulation

- `QUADS`, `LINES`, `TRIANGLES` → `MTLPrimitiveTypeTriangle` with the game's index buffer; no backend work.
- `TRIANGLE_FAN` (6 pipelines): Metal has no fan. Two vanilla draw shapes exist: non-indexed `draw(n, 1, first, 0)`
  (sky discs n = 10, sunrise n = 18) and indexed with the identity `sharedSequential` buffer and a base vertex
  (`StagedVertexBuffer`). Emulate by generating a triangle-list index buffer `(f0, fk, fk+1)` for k = 1..n−2 in
  transient memory: for non-indexed draws `fk = first + k`; for indexed draws read the source indices (the index
  buffer is CPU-visible on unified memory) and keep `baseVertex`. **[speculative]** A cache keyed on
  (buffer, offset, count, type) avoids regenerating the static sky meshes every frame.
- `POINTS`: SPIRV-Cross must emit `[[point_size]]` (`MSL_ENABLE_POINT_SIZE_BUILTIN = true` for point pipelines);
  `debug_point.vsh` writes `gl_PointSize = LineWidth`. Points ≥ 1 px are the only portable size.
- `TRIANGLE_STRIP` (`leash`): native; primitive restart is never used by the API.

### 6.7 `MetalDevice.compilePipeline` checklist

1. On the calling worker thread: for both shaders, read `module.spv()` (use `duplicate()`, do not move the position),
   translate to MSL (one spvc context per call, so this is thread-safe), record entry name and per-binding stage usage; compile (or fetch from cache) the `MTLLibrary`;
   get the `MTLFunction`s.
2. Build the vertex descriptor, color attachments, and the depth variants; create the PSO(s) on the worker
   (`newRenderPipelineStateWithDescriptor:error:` is thread-safe), or asynchronously with a completion handler and
   wait in `finishCompile()`.
3. Copy everything needed later (`uniforms` types/formats, push size, topology, depth state, cull, fill mode, bias).
4. Return `() -> pipeline`. On any failure: log (pipeline name, stage, Metal/spvc error) and return `Pending.NULL`.
5. `close()`: Metal command buffers retain the PSO unless they are created with unretained references (ARCHITECTURE.md
   uses unretained references), so release through the device's deferred-release queue.

---

## 7. Vanilla corpus survey

### 7.1 Inventory

| Kind | Count | Notes |
|---|---|---|
| Pipelines registered as required | 190 | `RenderPipelines.requiredPipelines()` (keyed by location) |
| Optional | 2 | `WIREFRAME`, `WIREFRAME_MULTIDRAW` (wireframe fill mode) |
| Compiled lazily because their location collides | 3 | `ITEM_CUTOUT` and `ITEM_CUTOUT_GLINT` share `pipeline/item_cutout` with `ITEM_CUTOUT_GLINT_SPECIAL`; `ITEM_TRANSLUCENT_GLINT` shares `pipeline/item_translucent_glint` with `ITEM_TRANSLUCENT_GLINT_SPECIAL`. Only the last registered one is precompiled; the others compile on first use on the render thread. |
| Post-effect passes | 24 | `blur` 6, `creeper` 2, `entity_outline` 4, `invert` 2, `spider` 10; location `minecraft:<effect>/<pass index>`; compiled lazily |
| **Total** | **219** | 31 OIT pipeline sets × 3 (depth bounds, transmittance, accumulate) = 93 of them |
| Shader files | 89 | `core/` 63 (29 `.vsh`, 34 `.fsh`), `post/` 9 (1 `.vsh`, 8 `.fsh`), `include/` 17 |
| SPIR-V modules | 438 | 175 distinct; 169 distinct MSL outputs |

Pipeline-level state that occurs (from the dump): color target sets: 185 pipelines with 1 target, 31 with 2 (OIT
transmittance, 2× RGBA16F additive), 3 with none (`blit_depth`, `integrate_depth`, `oit_water_mask`). Depth states:
`>= write` 52, `>= no-write` 102, none 54, `always write` 4 (depth blits/composite), `== no-write` 2
(`armor_decal_cutout_no_cull`, `glint`), biased 5. Topologies: QUADS 161, TRIANGLES 39, LINES 8, TRIANGLE_FAN 6,
POINTS 4, TRIANGLE_STRIP 1. Polygon mode WIREFRAME 2. Push constants 0. Instanced vertex buffers: the 7
`*_multidraw` terrain pipelines (slot 1, divisor 1).

### 7.2 Uniform blocks (std140) and sizes

All UBOs are `layout(std140) uniform <Name> { ... };` without bindings. "MSL size" is what Metal reflection
reports as the argument length (`MTLBufferBinding.bufferDataSize`) **(verified)**.

| Block (include) | Members @ std140 offset | std140 size | MSL size | Game allocation |
|---|---|---|---|---|
| `Projection` (`projection.glsl`; inline copy in `gui.vsh`, `position_tex_color.vsh`) | `mat4 ProjMat`@0 | 64 | 64 | `ProjectionMatrixBuffer`, 64 |
| `DynamicTransforms` (`dynamictransforms.glsl`; inline copies in GUI shaders) | `mat4 ModelViewMat`@0, `mat4 TextureMat`@64, `vec4 ColorModulator`@128, `vec3 ModelOffset`@144 | 156 | **160** | `DynamicGpuDataStorage*`: blocks rounded up to `minUniformOffsetAlignment` |
| `Fog` (`fog.glsl`) | `vec4 FogColor`@0, `float FogEnvironmentalStart`@16, `FogEnvironmentalEnd`@20, `FogRenderDistanceStart`@24, `FogRenderDistanceEnd`@28, `FogSkyEnd`@32, `FogCloudsEnd`@36 | 40 | **48** | `FogRenderer` `MappableRingBuffer` of **exactly 40** |
| `Globals` (`globals.glsl`) | `ivec3 CameraBlockPos`@0, `float GlintAlpha`@12, `vec3 CameraOffset`@16, `float GameTime`@28, `vec2 ScreenSize`@32, `int MenuBlurRadius`@40, `int UseRgss`@44 | 48 | 48 | `GlobalSettingsUniform`, 48 |
| `Lighting` (`light.glsl`) | `vec3 Light0_Direction`@0, `vec3 Light1_Direction`@16 | 28 | **32** | `com/mojang/blaze3d/platform/Lighting`: slices of 28 in a buffer padded per entry to `minUniformOffsetAlignment` |
| `ChunkSection` (`chunksection.glsl`) | `ivec3 ChunkPosition`@0, `float ChunkVisibility`@12 | 16 | 16 | `DynamicGpuDataStorage*` (aligned blocks) |
| `TerrainUniform` (`terrainglobals.glsl`) | `mat4 ModelViewMat`@0, `ivec2 TextureSize`@64 | 72 | **80** | `DynamicGpuDataStorage*` (aligned blocks) |
| `CloudInfo` (`clouds.vsh`) | `vec4 CloudColor`@0, `vec3 CloudOffset`@16, `vec3 CellSize`@32 | 44 | **48** | `CloudRenderer` ring of exactly 44 |
| `LightmapInfo` (`lightmap.fsh`, instance `lightmapInfo`) | 6 floats @0..20 (`SkyFactor, BlockFactor, NightVisionFactor, DarknessScale, BossOverlayWorldDarkeningFactor, BrightnessFactor`), `vec3 BlockLightTint`@32, `SkyLightColor`@48, `AmbientColor`@64, `NightVisionColor`@80 | 92 | **96** | `Lightmap` ring of exactly 92 |
| `SpriteAnimationInfo` (`animation_sprite.glsl`) | `mat4 ProjectionMatrix`@0, `mat4 SpriteMatrix`@64, `float UPadding`@128, `float VPadding`@132, `int MipMapLevel`@136 | 140 | **144** | slices of 140 at 256-aligned stride |
| `SamplerInfo` (post) | `vec2 OutSize`@0, `vec2 InSize`@8 | 16 | 16 | `PostPass` ring of `(inputs + 1) * 8` bytes (16 or 24) |
| `BlurConfig` (post) | `vec2 BlurDir`@0, `float Radius`@8 | 12 | **16** | post custom uniforms: `PostPass` constructor creates a buffer of **exactly** the std140 size computed from the JSON (12) |
| `BlitConfig` | `vec4 ColorModulate` | 16 | 16 | |
| `BitsConfig` | `float Resolution`@0, `float MosaicSize`@4 | 8 | 8 | |
| `ColorConfig` | `vec3 RedMatrix`@0, `GreenMatrix`@16, `BlueMatrix`@32 | 44 | **48** | exact size (post) |
| `InvertConfig` | `float InverseAmount` | 4 | 4 | |
| `RotScaleConfig` | `vec2 InScale`@0, `vec2 InOffset`@8, `float InRotation`@16 | 20 | **24** | exact size (post) |
| `SpiderConfig` | `vec4 Scissor`, `vec4 Vignette` | 32 | 32 | |

Sizes in bold differ between std140 and MSL; every one of those needs the allocation rounding from §6.5.

### 7.3 GLSL features that matter for SPIR-V → MSL

Counted over `SRC/assets/minecraft/shaders/**` (files) and over the generated MSL (pipelines). All translated and
compiled **(verified)**.

| Feature | Where | MSL result / note |
|---|---|---|
| `gl_VertexIndex` | `screenquad.vsh`, `post/rotscale.vsh` (fullscreen triangle from `draw(3,1,0,0)`), `rendertype_lines.vsh` (`% 2` to pick the side of the extruded line), `clouds.vsh` (`/4`, `%4`: no vertex buffer, texel-buffer lookup), `animate_sprite.vsh` (`& 7` corner, `>> 3` = frame progress encoded in **firstVertex**: `SpriteContents.java:389` `draw(6, 1, frameProgressAsInt << 3, 0)`) | `[[vertex_id]]`, which includes `vertexStart`/`baseVertex` like Vulkan. **Do not** enable `MSL_ENABLE_BASE_INDEX_ZERO`. |
| `gl_InstanceIndex`, `gl_VertexID`, `gl_InstanceID` | none | |
| `texelFetch(sampler2D, ivec2, 0)` | `entity.vsh`/`item.vsh` (**vertex-stage** overlay lookup `Sampler1` at `UV1`), `integrate_depth.fsh`, `oit_composite.fsh`, `oit_depth_bounds_cull.fsh`, `oit_depth_sample.glsl`, `oit_sample.glsl` (the fragment-stage ones all use `ivec2(gl_FragCoord.xy)`) | `.read(uint2, lod)`; no sampler involved. Out-of-range reads are undefined on Metal. |
| `isamplerBuffer` + `texelFetch` | `clouds.vsh` (`CloudFaces`, `R8_SINT`, vertex stage) | `texture_buffer<int>`, `.read(uint(i))` |
| `samplerCube` | `panorama.fsh` | `texturecube<float>`; needs a cube texture/view |
| `textureLod` | `animate_sprite_*.fsh` (`int MipMapLevel` from UBO), `texture_sampling.glsl` (RGSS) | `sample(s, uv, level(x))` |
| `textureGrad`, `dFdx`, `dFdy`, `round` | `texture_sampling.glsl` (terrain `sampleNearest`/`sampleRGSS`) | `gradient2d`, `dfdx`/`dfdy`; with the Y flip `dFdy` keeps GL's sign relative to texel rows |
| `textureProj(sampler2D, vec4)` | `rendertype_end_portal.fsh` (`PORTAL_LAYERS` = 15 or 16 iterations) | divide + `sample` |
| `gl_FragCoord` (`.xy`, `.z`, `.w`) | `.xy` for texel fetches; `.z` for OIT depth; `1.0 / gl_FragCoord.w` for fog in `rendertype_beacon_beam.fsh` | `[[position]]`; `.w` is 1/w in Metal too |
| `gl_FrontFacing` | `entity.fsh` with `PER_FACE_LIGHTING` (11 pipelines) | `[[front_facing]]`; correct only with the winding from §6.4 |
| `discard` | 17 files; 144 of 219 pipelines | `discard_fragment()` |
| `gl_FragDepth` | `blit_depth.fsh`, `integrate_depth.fsh`, `oit_composite.fsh`, `oit_depth_bounds_cull.fsh`, and OIT includes under `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` | `[[depth(any)]]`; 63 pipelines with the Apple hint on |
| `gl_PointSize` | `debug_point.vsh` | `[[point_size]]` (4 pipelines) |
| Fragment output arrays | `oit_add_transmittance.glsl`: `layout(location = 0) out vec4 coeff[OIT_COEFF_ATTACHMENT_COUNT]` (= 2) | `[[color(0)]]`, `[[color(1)]]` (27 pipelines) |
| Fragment shaders with no color output | `blit_depth.fsh`, `integrate_depth.fsh` (depth only); the `OIT_ADDITIVE` transmittance variants declare `coeff[]` but always `discard` | fine; `blit_depth` is also used with an RGBA32F target with write mask 0 (`blit_depth_during_depth_bounds`), which Metal accepted |
| `flat` varyings | `rendertype_leash.vsh/fsh` (`flat vec4 vertexColor`) | `[[flat]]` |
| Integer vertex inputs | `ivec2 UV1`, `ivec2 UV2` (`RG16_SINT`), `ivec3 ChunkPosition` (`RGB32_SINT`) | `int2`/`int3` attributes from `Short2`/`Int3` |
| Integer math | shifts, `&`, `%` in `clouds.vsh`, `animate_sprite.vsh`, `rotscale.vsh`, `screenquad.vsh`, OIT wavelet code | `spvSMod` for signed `%` |
| Dynamically indexed local/const arrays | `const vec2[] positions` (`animate_sprite.vsh`), `faceColors` (`clouds.vsh`), `COLORS` (`end_portal`), `float coefficients[OIT_COEFF_COUNT]` (OIT, dynamic indices) | `spvUnsafeArray<T, N>` |
| Matrix constructors from smaller matrices / scalar | `mat4(scale * rotate)` with `mat2`, `mat2(float)` (`end_portal`, `matrix.glsl`) | expanded by SPIRV-Cross |
| UBO with instance name | `lightmap.fsh` (`lightmapInfo`) | binding name is still the block name `LightmapInfo` |
| `std140` blocks with `vec3` | many (§7.2) | `packed_float3`/`float3` chosen per offsets |
| Not used anywhere | `textureSize`, `fwidth`, `invariant`, `precise`, `push_constant`, `std430`, `buffer` blocks, images, `usampler*`, sampler arrays, `gl_ClipDistance`, specialization constants, subpass inputs | |

### 7.4 Vertex inputs per shader (CreateInfo `attribBindings`)

Generated from the dump. Note the location ≠ element order cases (`core/text`, `core/rendertype_outline`), formats
whose elements a shader ignores (`rendertype_entity_shadow` reads 3 of `ENTITY`'s 6 elements, the
`ENTITY_GLINT_SPECIAL` transmittance variant ignores `UV3`), and the vertex-buffer-less shaders.

| vertex shader | GLSL inputs (location:name:type) | vertex format(s) | CreateInfo.attribBindings (location ← slot+offset:format) | used by (count) |
|---|---|---|---|---|
| core/screenquad | (none) | (none) | (none) | oit_depth_bounds_cull (+27) |
| core/panorama | 0:Position:float3 | POSITION[slot 0, stride 12] | L0←s0+0:RGB32_FLOAT | panorama |
| core/entity | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV1:int2 4:UV2:int2 5:Normal:float3 | ENTITY[slot 0, stride 36] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT L4←s0+28:RG16_SINT L5←s0+32:RGBA8_SNORM | entity_cutout (+36) |
| core/terrain | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV2:int2 4:ChunkPosition:int3 5:ChunkVisibility:float | BLOCK[slot 0, stride 28] + CHUNK_DATA_INSTANCED[slot 1, stride 16, per-instance/1] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT L4←s1+0:RGB32_SINT L5←s1+12:R32_FLOAT | wireframe_multidraw (+6) |
| core/rendertype_beacon_beam | 0:Position:float3 1:Color:float4 2:UV0:float2 | BLOCK[slot 0, stride 28] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT | oit_depth_bounds_beacon_beam (+4) |
| core/rendertype_lightning | 0:Position:float3 1:Color:float4 | POSITION_COLOR[slot 0, stride 16] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM | oit_accumulate_lightning (+7) |
| core/rendertype_lines | 0:Position:float3 1:Color:float4 2:Normal:float3 3:LineWidth:float | POSITION_COLOR_NORMAL_LINE_WIDTH[slot 0, stride 24] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RGBA8_SNORM L3←s0+20:R32_FLOAT | lines (+7) |
| core/terrain | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV2:int2 | BLOCK[slot 0, stride 28] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT | wireframe (+6) |
| core/position_color | 0:Position:float3 1:Color:float4 | POSITION_COLOR[slot 0, stride 16] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM | oit_accumulate_debug_quads (+12) |
| core/item | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV1:int2 4:UV2:int2 6:Normal:float3 | ENTITY[slot 0, stride 36] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT L4←s0+28:RG16_SINT L6←s0+32:RGBA8_SNORM | item_translucent (+9) |
| core/text | 0:Position:float3 1:Color:float4 2:UV0:float2 | POSITION_TEX_COLOR[slot 0, stride 24] | L0←s0+0:RGB32_FLOAT L1←s0+20:RGBA8_UNORM L2←s0+12:RG32_FLOAT | gui_text_grayscale (+3) |
| core/item | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV1:int2 4:UV2:int2 5:UV3:float2 6:Normal:float3 | ENTITY_GLINT_SPECIAL[slot 0, stride 44] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT L4←s0+28:RG16_SINT L5←s0+32:RG32_FLOAT L6←s0+40:RGBA8_SNORM | item_translucent_glint (+2) |
| core/clouds | (none) | (none) | (none) | oit_transmittance_clouds (+7) |
| core/sky | 0:Position:float3 | POSITION[slot 0, stride 12] | L0←s0+0:RGB32_FLOAT | sky |
| core/position_tex_color | 0:Position:float3 1:UV0:float2 2:Color:float4 | POSITION_TEX_COLOR[slot 0, stride 24] | L0←s0+0:RGB32_FLOAT L1←s0+12:RG32_FLOAT L2←s0+20:RGBA8_UNORM | crosshair (+9) |
| core/particle | 0:Position:float3 1:UV0:float2 2:Color:float4 3:UV2:int2 | PARTICLE[slot 0, stride 28] | L0←s0+0:RGB32_FLOAT L1←s0+12:RG32_FLOAT L2←s0+20:RGBA8_UNORM L3←s0+24:RG16_SINT | oit_accumulate_particle (+8) |
| core/position_tex | 0:Position:float3 1:UV0:float2 | POSITION_TEX[slot 0, stride 20] | L0←s0+0:RGB32_FLOAT L1←s0+12:RG32_FLOAT | celestial |
| core/rendertype_entity_shadow | 0:Position:float3 1:Color:float4 2:UV0:float2 | ENTITY[slot 0, stride 36] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT | oit_depth_bounds_entity_shadow (+3) |
| core/rendertype_end_portal | 0:Position:float3 | POSITION[slot 0, stride 12] | L0←s0+0:RGB32_FLOAT | end_gateway (+1) |
| core/debug_point | 0:Position:float3 1:Color:float4 2:LineWidth:float | POSITION_COLOR_LINE_WIDTH[slot 0, stride 20] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:R32_FLOAT | debug_points (+3) |
| core/block | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV2:int2 | BLOCK[slot 0, stride 28] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT | solid_block (+5) |
| core/world_border | 0:Position:float3 1:UV0:float2 | POSITION_TEX[slot 0, stride 20] | L0←s0+0:RGB32_FLOAT L1←s0+12:RG32_FLOAT | oit_accumulate_world_border (+3) |
| core/text | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV2:int2 | POSITION_TEX_LIGHTMAP_COLOR[slot 0, stride 28] | L0←s0+0:RGB32_FLOAT L1←s0+24:RGBA8_UNORM L2←s0+12:RG32_FLOAT L3←s0+20:RG16_SINT | oit_transmittance_text (+15) |
| core/rendertype_crumbling | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV2:int2 | BLOCK[slot 0, stride 28] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT | crumbling (+3) |
| core/animate_sprite | (none) | (none) | (none) | animate_sprite_interpolate (+1) |
| core/gui | 0:Position:float3 1:Color:float4 | POSITION_COLOR[slot 0, stride 16] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM | gui_text_highlight (+2) |
| core/rendertype_outline | 0:Position:float3 1:Color:float4 2:UV0:float2 | POSITION_TEX_COLOR[slot 0, stride 24] | L0←s0+0:RGB32_FLOAT L1←s0+20:RGBA8_UNORM L2←s0+12:RG32_FLOAT | outline_no_cull (+1) |
| core/item | 0:Position:float3 1:Color:float4 2:UV0:float2 3:UV1:int2 4:UV2:int2 6:Normal:float3 | ENTITY_GLINT_SPECIAL[slot 0, stride 44] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG32_FLOAT L3←s0+24:RG16_SINT L4←s0+28:RG16_SINT L6←s0+40:RGBA8_SNORM | oit_transmittance_item_glint_special (+1) |
| core/rendertype_leash | 0:Position:float3 1:Color:float4 2:UV2:int2 | POSITION_COLOR_LIGHTMAP[slot 0, stride 20] | L0←s0+0:RGB32_FLOAT L1←s0+12:RGBA8_UNORM L2←s0+16:RG16_SINT | leash |
| core/rendertype_water_mask | 0:Position:float3 | POSITION[slot 0, stride 12] | L0←s0+0:RGB32_FLOAT | oit_water_mask (+1) |
| core/glint | 0:Position:float3 1:UV0:float2 | POSITION_TEX[slot 0, stride 20] | L0←s0+0:RGB32_FLOAT L1←s0+12:RG32_FLOAT | glint |
| core/stars | 0:Position:float3 | POSITION[slot 0, stride 12] | L0←s0+0:RGB32_FLOAT | stars |
| post/rotscale | (none) | (none) | (none) | spider/4 (+4) |

### 7.5 GpuFormats in use

| Use | Formats |
|---|---|
| Vertex attributes | `RGB32_FLOAT`, `RGBA8_UNORM`, `RG32_FLOAT`, `RG16_SINT`, `RGBA8_SNORM`, `R32_FLOAT`, `RGB32_SINT` |
| Color render targets (pipelines) | `RGBA8_UNORM` (everything else), `RGBA16_FLOAT` (OIT transmittance + accumulate targets), `RGBA32_FLOAT` (OIT depth bounds, `blit_depth_bounds`, `oit_depth_bounds_cull`, `blit_depth_during_depth_bounds`) |
| Depth | `D32_FLOAT` only (`MainTarget`, `RenderTargetDescriptor` default, GUI atlases, `hud_3d_depth`, OIT) |
| Sampled textures | `RGBA8_UNORM` (atlases, lightmap 16×16, dynamic textures, cube map), `R8_UNORM` (grayscale font pages), `RGBA16_FLOAT` / `RGBA32_FLOAT` (OIT targets read back), `D32_FLOAT` (depth read through `sampler2D`) |
| Texel buffers | `R8_SINT` (`CloudFaces`) |

### 7.6 Post-effect passes

`PostChain.createPass` builds each pass with `POST_PROCESSING_SNIPPET` (Globals layout, TRIANGLES), the JSON's vertex
and fragment shader, one layout with `<sampler_name>Sampler` per input + `SamplerInfo` + one UBO per uniform group,
and `ColorTargetState.DEFAULT` (RGBA8, no blend). No vertex buffers; drawn with `renderPass.draw(3, 1, 0, 0)`
(`PostPass.java:147`). The `SamplerInfo` ring buffer holds `(inputs + 1) * 8` bytes (`PostPass.java:86`: 16 bytes
for one input, 24 for two); the shader block is 16 bytes, so it always fits. Custom uniform groups get a buffer of the
exact std140 size (§7.2). Rows `blur/*` … `spider/*` in Appendix A.

---

## 8. Pitfalls and ambiguities (consolidated)

1. **SPIR-V lifetime**: modules are closed right after `finishCompile()`. Copy what you need first.
2. **Binding order is opaque** (first-seen, VS before FS, UBOs before images, then SPIRV-Cross order). Only
   `CreateInfo.uniforms()` and the `index` passed to `setUniform` are authoritative.
3. **`CreateInfo.uniforms()` includes declared-but-unused resources**; MSL drops them from entry points. Bindings and
   stage masks must tolerate both.
4. **Names are not unique** (pipeline names: 5 vanilla pipelines share 2 names; shader names are shared by all
   define-variants). Key caches on object identity or on the MSL hash.
5. **`QUADS`/`LINES` arrive raw** and must become triangle lists; **`TRIANGLE_FAN` has no Metal equivalent**.
6. **Depth-less pipelines are used in passes with depth attachments**: build both PSO variants, and reset the encoder's
   depth-stencil state to always/no-write.
7. **Encoder state persists** across `setRenderPipelineState:` on Metal (depth-stencil, cull, winding, fill, bias).
8. **Y flip + clockwise winding** are both required; with only one of them, culling or the image orientation is wrong
   (§6.4 table). `gl_FrontFacing` (`PER_FACE_LIGHTING`) also depends on it.
9. **UBO allocation rounding**: std140 sizes like 40/44/92 vs MSL 48/48/96; Metal API validation aborts the draw.
10. **`gl_VertexIndex` must include the draw's first vertex**: the sprite animation encodes data in `firstVertex`.
11. **No strict validation in production**: missing uniforms/vertex buffers/push constants are not caught by the
    frontend outside the IDE. Metal must not crash on a missing binding (e.g. bind a dummy texture/buffer or skip the
    draw and log once). **[speculative]** policy choice.
12. **Explicit depth on Apple GPUs**: with `isExplicitDepthRequired = true`, 58 OIT pipelines write
    `[[depth(any)]]`, which disables early depth testing/HSR for them. Mojang enables it for Apple GPUs on both GL and
    MoltenVK; keep it until proven unnecessary.
13. **Texel-buffer element type is unchecked** against `UniformDescription.gpuFormat()`; `samplerBuffer` with an
    integer format would fail Metal validation. Vanilla is consistent (`isamplerBuffer` + `R8_SINT`).
14. **Sampler arrays, storage resources, 1D/3D textures, SSBOs** are unsupported by the frontend; mods hitting them
    fail in the frontend, not in Metal.
15. **Blend constant color** cannot be set through the API; Metal's default (0,0,0,0) matches GL/Vulkan.
16. **`MIN`/`MAX` blend ops**: vanilla uses `ONE, ONE` factors with `MAX`; Metal accepted them. **[speculative]** for
    arbitrary factors, follow GL/Vulkan semantics (factors ignored) by forcing `One` when the op is Min/Max.
17. **Wireframe** is an encoder fill mode on Metal; it applies to triangles only (fine: the WIREFRAME pipelines use
    QUADS).
18. **`SpvModule.spv()` is a direct, native-order `ByteBuffer`** at position 0; use `duplicate()`/`asIntBuffer()`
    without consuming it (the GL backend and the frontend's reflection read the same buffer).
19. **Lazily compiled pipelines hitch the render thread** (post effects, 3 colliding item pipelines, anything a mod
    adds). Fast MSL→library caching matters for frame pacing, not just startup.
20. **Depth format**: the API has stencil-capable formats but no stencil state; all vanilla depth is `D32_FLOAT`.
    Vulkan hard-codes it in pipelines; Metal should key PSO variants on the actual attachment format instead.

---

## Appendix A: every vanilla pipeline (generated from `pipelines-26.3.json`)

Legend: **reg** R = required (precompiled), O = optional, L = lazily compiled (location collision), P = post-effect
pass. **defines**: `OIT*` stands for `OIT OIT_WAVELET_RANK=2 OIT_COEFF_COUNT=8 OIT_COEFF_ATTACHMENT_COUNT=2`.
**vertex bindings**: `slot:FORMAT`, `(inst/N)` = per-instance divisor N. **CreateInfo.uniforms**: `i` = binding
index, no prefix = UBO, `*` = combined image sampler, `^` = texel buffer; `(+layout-only: ...)` = declared in the
pipeline's bind group layouts but not in its shaders (never forwarded to the backend). **color targets**: format,
blend (lowercase constant name, or `src,dst/srcA,dstA` for inline functions), `mask=` when not 15. **depth**:
compare + `W`/`noW` + `bias(slope, constant)`. **cull**: Y = back-face culling.

| location | reg | field(s) | shaders (vsh+fsh) | defines | vertex bindings | CreateInfo.uniforms (index = binding) | color targets | depth | cull | poly | topology |
|---|---|---|---|---|---|---|---|---|---|---|---|
| pipeline/animate_sprite_blit | R | ANIMATE_SPRITE_BLIT | core/animate_sprite + core/animate_sprite_blit | - | - | 0SpriteAnimationInfo 1*Sprite (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| pipeline/animate_sprite_interpolate | R | ANIMATE_SPRITE_INTERPOLATE | core/animate_sprite + core/animate_sprite_interpolate | - | - | 0SpriteAnimationInfo 1*CurrentSprite 2*NextSprite (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| pipeline/armor_cutout_no_cull | R | ARMOR_CUTOUT_NO_CULL | core/entity | ALPHA_CUTOUT=0.1 NO_OVERLAY PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler0 (+layout-only: Globals) | rgba8 | >= W | N | fill | QUADS |
| pipeline/armor_cutout_no_cull_glint | R | ARMOR_CUTOUT_NO_CULL_GLINT | core/entity | ALPHA_CUTOUT=0.1 GLINT NO_OVERLAY PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5Globals 6*GlintSampler 7*Sampler0 | rgba8 | >= W | N | fill | QUADS |
| pipeline/armor_decal_cutout_no_cull | R | ARMOR_DECAL_CUTOUT_NO_CULL | core/entity | ALPHA_CUTOUT=0.1 NO_OVERLAY PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler0 (+layout-only: Globals) | rgba8 | == noW | N | fill | QUADS |
| pipeline/armor_translucent | R | WOLF_ARMOR_CRACKS | core/entity | ALPHA_CUTOUT=0.1 NO_OVERLAY PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | N | fill | QUADS |
| pipeline/banner_pattern | R | BANNER_PATTERN | core/entity | NO_OVERLAY | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= noW | Y | fill | QUADS |
| pipeline/beacon_beam_opaque | R | BEACON_BEAM_OPAQUE | core/rendertype_beacon_beam | - | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/beacon_beam_translucent | R | BEACON_BEAM_TRANSLUCENT | core/rendertype_beacon_beam | - | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= noW | Y | fill | QUADS |
| pipeline/blit_depth | R | BLIT_DEPTH | core/screenquad + core/blit_depth | - | - | 0*InSampler (+layout-only: Globals) | (none) | always W | Y | fill | TRIANGLES |
| pipeline/blit_depth_bounds | R | BLIT_DEPTH_BOUNDS | core/screenquad + core/blit_screen | - | - | 0*InSampler (+layout-only: Globals) | rgba32f | - | Y | fill | TRIANGLES |
| pipeline/blit_depth_during_depth_bounds | R | BLIT_DEPTH_DURING_DEPTH_BOUNDS | core/screenquad + core/blit_depth | - | - | 0*InSampler (+layout-only: Globals) | rgba32f mask=0 | always W | Y | fill | TRIANGLES |
| pipeline/block_screen_effect | R | BLOCK_SCREEN_EFFECT | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/breeze_wind | R | BREEZE_WIND | core/entity | ALPHA_CUTOUT=0.1 APPLY_TEXTURE_MATRIX NO_OVERLAY NO_CARDINAL_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals,Lighting) | rgba8 translucent | >= W | N | fill | QUADS |
| pipeline/celestial | R | CELESTIAL | core/position_tex | - | 0:POSITION_TEX | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 overlay | - | Y | fill | QUADS |
| pipeline/clouds | R | CLOUDS | core/clouds | - | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/crosshair | R | CROSSHAIR | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 invert | - | Y | fill | QUADS |
| pipeline/crumbling | R | CRUMBLING | core/rendertype_crumbling | - | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba8 dst_color,src_color/one,zero | >= noW bias(1,10) | Y | fill | QUADS |
| pipeline/cutout_block | R | CUTOUT_BLOCK | core/block | ALPHA_CUTOUT=0.5 | 0:BLOCK | 0DynamicTransforms 1Projection 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/cutout_terrain | R | CUTOUT_TERRAIN | core/terrain | ALPHA_CUTOUT=0.5 | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*Sampler2 6*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/cutout_terrain_multidraw | R | CUTOUT_TERRAIN_MULTIDRAW | core/terrain | ALPHA_CUTOUT=0.5 MULTIDRAW_TERRAIN | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*Sampler2 5*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/debug_filled_box | R | DEBUG_FILLED_BOX | core/position_color | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 translucent | >= noW | N | fill | QUADS |
| pipeline/debug_points | R | DEBUG_POINTS | core/debug_point + core/position_color | - | 0:POSITION_COLOR_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals | rgba8 translucent | >= W | N | fill | POINTS |
| pipeline/debug_quads | R | DEBUG_QUADS | core/position_color | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 translucent | >= noW | N | fill | QUADS |
| pipeline/debug_triangle_fan | R | DEBUG_TRIANGLE_FAN | core/position_color | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 translucent | >= noW | N | fill | TRIANGLE_FAN |
| pipeline/dragon_rays | R | DRAGON_RAYS | core/rendertype_lightning | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog (+layout-only: Globals) | rgba8 lightning | >= W | Y | fill | TRIANGLES |
| pipeline/end_crystal_beam | R | END_CRYSTAL_BEAM | core/entity | ALPHA_CUTOUT=0.1 NO_OVERLAY | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler0 (+layout-only: Globals) | rgba8 | >= W | N | fill | QUADS |
| pipeline/end_gateway | R | END_GATEWAY | core/rendertype_end_portal | PORTAL_LAYERS=16 | 0:POSITION | 0Projection 1DynamicTransforms 2Fog 3Globals 4*Sampler0 5*Sampler1 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/end_portal | R | END_PORTAL | core/rendertype_end_portal | PORTAL_LAYERS=15 | 0:POSITION | 0Projection 1DynamicTransforms 2Fog 3Globals 4*Sampler0 5*Sampler1 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/end_sky | R | END_SKY | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/energy_swirl | R | ENERGY_SWIRL | core/entity | ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING APPLY_TEXTURE_MATRIX | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba8 additive | >= W | N | fill | QUADS |
| pipeline/entity_cutout | R | ENTITY_CUTOUT | core/entity | ALPHA_CUTOUT=0.1 PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 | >= W | N | fill | QUADS |
| pipeline/entity_cutout_cull | R | ENTITY_CUTOUT_CULL | core/entity | ALPHA_CUTOUT=0.1 | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/entity_cutout_dissolve | R | ENTITY_CUTOUT_DISSOLVE | core/entity | ALPHA_CUTOUT=0.1 PER_FACE_LIGHTING DISSOLVE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 7*DissolveMaskSampler (+layout-only: Globals) | rgba8 | >= W | N | fill | QUADS |
| pipeline/entity_cutout_z_offset | R | ENTITY_CUTOUT_Z_OFFSET | core/entity | ALPHA_CUTOUT=0.1 PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 | >= W | N | fill | QUADS |
| pipeline/entity_outline_blit | R | ENTITY_OUTLINE_BLIT | core/screenquad + core/blit_screen | - | - | 0*InSampler (+layout-only: Globals) | rgba8 entity_outline_blit mask=7 | - | Y | fill | TRIANGLES |
| pipeline/entity_shadow | R | ENTITY_SHADOW | core/rendertype_entity_shadow | - | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= noW | Y | fill | QUADS |
| pipeline/entity_solid | R | ENTITY_SOLID | core/entity | - | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/entity_solid_glint | R | ENTITY_SOLID_GLINT | core/entity | GLINT | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*GlintSampler 8*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/entity_solid_offset_forward | R | ENTITY_SOLID_Z_OFFSET_FORWARD | core/entity | - | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/entity_translucent | R | ENTITY_TRANSLUCENT | core/entity | ALPHA_CUTOUT=0.1 PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | N | fill | QUADS |
| pipeline/entity_translucent_cull | R | ENTITY_TRANSLUCENT_CULL | core/entity | ALPHA_CUTOUT=0.1 | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/entity_translucent_emissive | R | ENTITY_TRANSLUCENT_EMISSIVE | core/entity | ALPHA_CUTOUT=0.1 EMISSIVE PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler1 5*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= noW | N | fill | QUADS |
| pipeline/eyes | R | EYES | core/entity | EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= noW | Y | fill | QUADS |
| pipeline/fire_screen_effect | R | FIRE_SCREEN_EFFECT | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/flat_clouds | R | FLAT_CLOUDS | core/clouds | - | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces (+layout-only: Globals) | rgba8 translucent | >= W | N | fill | QUADS |
| pipeline/glint | R | GLINT | core/glint | - | 0:POSITION_TEX | 0Projection 1DynamicTransforms 2Fog 3Globals 4*Sampler0 | rgba8 glint | == noW | N | fill | QUADS |
| pipeline/gui | R | GUI | core/gui | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/gui_invert | R | GUI_INVERT | core/gui | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 invert | - | Y | fill | QUADS |
| pipeline/gui_nausea_overlay | R | GUI_NAUSEA_OVERLAY | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 additive | - | Y | fill | QUADS |
| pipeline/gui_opaque_textured_background | R | GUI_OPAQUE_TEXTURED_BACKGROUND | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 | - | Y | fill | QUADS |
| pipeline/gui_text | R | GUI_TEXT | core/text | IS_GUI | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/gui_text_grayscale | R | GUI_TEXT_GRAYSCALE | core/text | IS_GUI IS_GRAYSCALE | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/gui_text_highlight | R | GUI_TEXT_HIGHLIGHT | core/gui | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 additive | - | Y | fill | QUADS |
| pipeline/gui_textured | R | GUI_TEXTURED | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/gui_textured_premultiplied_alpha | R | GUI_TEXTURED_PREMULTIPLIED_ALPHA | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent_premultiplied_alpha | - | Y | fill | QUADS |
| pipeline/integrate_depth | R | INTEGRATE_DEPTH | core/screenquad + core/integrate_depth | - | - | 0*InSampler | (none) | always W | Y | fill | TRIANGLES |
| pipeline/item_cutout | L | ITEM_CUTOUT_GLINT | core/item | ALPHA_CUTOUT=0.1 GLINT | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*GlintSampler 8*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/item_cutout | R | ITEM_CUTOUT_GLINT_SPECIAL | core/item | ALPHA_CUTOUT=0.1 GLINT GLINT_SPECIAL | 0:ENTITY_GLINT_SPECIAL | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*GlintSampler 8*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/item_cutout | L | ITEM_CUTOUT | core/item | ALPHA_CUTOUT=0.1 | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/item_translucent | R | ITEM_TRANSLUCENT | core/item | ALPHA_CUTOUT=0.1 | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/item_translucent_glint | R | ITEM_TRANSLUCENT_GLINT_SPECIAL | core/item | ALPHA_CUTOUT=0.1 GLINT GLINT_SPECIAL | 0:ENTITY_GLINT_SPECIAL | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*GlintSampler 8*Sampler0 | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/item_translucent_glint | L | ITEM_TRANSLUCENT_GLINT | core/item | ALPHA_CUTOUT=0.1 GLINT | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*GlintSampler 8*Sampler0 | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/leash | R | LEASH | core/rendertype_leash | - | 0:POSITION_COLOR_LIGHTMAP | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 (+layout-only: Globals) | rgba8 | >= W | N | fill | TRIANGLE_STRIP |
| pipeline/lightmap | R | LIGHTMAP | core/screenquad + core/lightmap | - | - | 0LightmapInfo (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| pipeline/lightning | R | LIGHTNING | core/rendertype_lightning | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog (+layout-only: Globals) | rgba8 lightning | >= W | Y | fill | QUADS |
| pipeline/lines | R | LINES | core/rendertype_lines | - | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog | rgba8 | >= W | N | fill | LINES |
| pipeline/lines_depth_bias | R | LINES_DEPTH_BIAS | core/rendertype_lines | - | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog | rgba8 | >= W bias(1,1) | N | fill | LINES |
| pipeline/lines_translucent | R | LINES_TRANSLUCENT | core/rendertype_lines | - | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog | rgba8 translucent | >= W | N | fill | LINES |
| pipeline/lines_translucent_no_depth_write | R | LINES_TRANSLUCENT_NO_DEPTH_WRITE | core/rendertype_lines | - | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog | rgba8 translucent | >= noW | N | fill | LINES |
| pipeline/mojang_logo | R | MOJANG_LOGO | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 lightning | - | Y | fill | QUADS |
| pipeline/oit_accumulate_beacon_beam | R | OIT_BEACON_BEAM.accumulate | core/rendertype_beacon_beam | OIT* OIT_ACCUMULATE | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 6*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_breeze_wind | R | OIT_BREEZE_WIND.accumulate | core/entity | OIT* ALPHA_CUTOUT=0.1 APPLY_TEXTURE_MATRIX NO_OVERLAY NO_CARDINAL_LIGHTING OIT_ACCUMULATE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals,Lighting) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_clouds | R | OIT_CLOUDS.accumulate | core/clouds | OIT* OIT_ACCUMULATE | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces 5*DepthBoundsSampler 6*Coeff0 7*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_crumbling | R | OIT_CRUMBLING.accumulate | core/rendertype_crumbling | OIT* OIT_ACCUMULATE | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 6*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_debug_filled_box | R | OIT_DEBUG_FILLED_BOX.accumulate | core/position_color | OIT* OIT_ACCUMULATE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2*DepthBoundsSampler 3*Coeff0 4*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_debug_points | R | OIT_DEBUG_POINTS.accumulate | core/debug_point + core/position_color | OIT* OIT_ACCUMULATE | 0:POSITION_COLOR_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 | rgba16f additive | >= noW | N | fill | POINTS |
| pipeline/oit_accumulate_debug_quads | R | OIT_DEBUG_QUADS.accumulate | core/position_color | OIT* OIT_ACCUMULATE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2*DepthBoundsSampler 3*Coeff0 4*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_debug_triangle_fan | R | OIT_DEBUG_TRIANGLE_FAN.accumulate | core/position_color | OIT* OIT_ACCUMULATE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2*DepthBoundsSampler 3*Coeff0 4*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | TRIANGLE_FAN |
| pipeline/oit_accumulate_dragon_rays | R | OIT_DRAGON_RAYS.accumulate | core/rendertype_lightning | OIT* OIT_ADDITIVE OIT_ACCUMULATE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | TRIANGLES |
| pipeline/oit_accumulate_energy_swirl | R | OIT_ENERGY_SWIRL.accumulate | core/entity | OIT* ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING APPLY_TEXTURE_MATRIX OIT_ADDITIVE OIT_ACCUMULATE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 6*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_entity | R | OIT_ENTITY.accumulate | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE PER_FACE_LIGHTING | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*DepthBoundsSampler 7*Coeff0 8*Coeff1 9*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_entity_cull | R | OIT_ENTITY_CULL.accumulate | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*DepthBoundsSampler 7*Coeff0 8*Coeff1 9*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_entity_emissive | R | OIT_ENTITY_EMISSIVE.accumulate | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE PER_FACE_LIGHTING EMISSIVE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler1 5*DepthBoundsSampler 6*Coeff0 7*Coeff1 8*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_entity_shadow | R | OIT_ENTITY_SHADOW.accumulate | core/rendertype_entity_shadow | OIT* OIT_ACCUMULATE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 6*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_eyes | R | OIT_EYES.accumulate | core/entity | OIT* ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING OIT_ACCUMULATE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 6*Sampler0 (+layout-only: Globals,Lighting) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_flat_clouds | R | OIT_FLAT_CLOUDS.accumulate | core/clouds | OIT* OIT_ACCUMULATE | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces 5*DepthBoundsSampler 6*Coeff0 7*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_item | R | OIT_ITEM.accumulate | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6*DepthBoundsSampler 7*Coeff0 8*Coeff1 9*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_item_glint | R | OIT_ITEM_GLINT.accumulate | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE GLINT | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*DepthBoundsSampler 8*Coeff0 9*Coeff1 10*GlintSampler 11*Sampler0 | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_item_glint_special | R | OIT_ITEM_GLINT_SPECIAL.accumulate | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE GLINT GLINT_SPECIAL | 0:ENTITY_GLINT_SPECIAL | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler2 5*Sampler1 6Globals 7*DepthBoundsSampler 8*Coeff0 9*Coeff1 10*GlintSampler 11*Sampler0 | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_lightning | R | OIT_LIGHTNING.accumulate | core/rendertype_lightning | OIT* OIT_ADDITIVE OIT_ACCUMULATE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Coeff0 5*Coeff1 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_lines_translucent | R | OIT_LINES_TRANSLUCENT.accumulate | core/rendertype_lines | OIT* OIT_ACCUMULATE | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 | rgba16f additive | >= noW | N | fill | LINES |
| pipeline/oit_accumulate_particle | R | OIT_PARTICLE.accumulate | core/particle | OIT* OIT_ACCUMULATE | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_terrain | R | OIT_TERRAIN.accumulate | core/terrain | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*Sampler2 6*DepthBoundsSampler 7*Coeff0 8*Coeff1 9*Sampler0 | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_terrain_multidraw | R | OIT_TERRAIN_MULTIDRAW.accumulate | core/terrain | OIT* ALPHA_CUTOUT=0.1 MULTIDRAW_TERRAIN OIT_ACCUMULATE | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*Sampler2 5*DepthBoundsSampler 6*Coeff0 7*Coeff1 8*Sampler0 | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_text | R | OIT_TEXT.accumulate | core/text | OIT* OIT_ACCUMULATE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_text_grayscale | R | OIT_TEXT_GRAYSCALE.accumulate | core/text | OIT* IS_GRAYSCALE OIT_ACCUMULATE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_text_grayscale_polygon_offset | R | OIT_TEXT_GRAYSCALE_POLYGON_OFFSET.accumulate | core/text | OIT* IS_GRAYSCALE OIT_ACCUMULATE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_text_polygon_offset | R | OIT_TEXT_POLYGON_OFFSET.accumulate | core/text | OIT* OIT_ACCUMULATE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_translucent_block | R | OIT_TRANSLUCENT_BLOCK.accumulate | core/block | OIT* ALPHA_CUTOUT=0.1 OIT_ACCUMULATE | 0:BLOCK | 0DynamicTransforms 1Projection 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_accumulate_weather | R | OIT_WEATHER.accumulate | core/particle | OIT* OIT_ACCUMULATE | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Coeff0 6*Coeff1 7*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_accumulate_world_border | R | OIT_WORLD_BORDER.accumulate | core/world_border | OIT* OIT_ADDITIVE OIT_ACCUMULATE | 0:POSITION_TEX | 0DynamicTransforms 1Projection 2*DepthBoundsSampler 3*Coeff0 4*Coeff1 5*Sampler0 (+layout-only: Globals) | rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_composite | R | OIT_COMPOSITE | core/screenquad + core/oit_composite | OIT* | - | 0Projection 1*Coeff0 2*Coeff1 3*Sampler0 4*DepthBoundsSampler (+layout-only: Globals) | rgba8 translucent_premultiplied_alpha | always W | Y | fill | TRIANGLES |
| pipeline/oit_depth_bounds_beacon_beam | R | OIT_BEACON_BEAM.depthBounds | core/rendertype_beacon_beam | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_breeze_wind | R | OIT_BREEZE_WIND.depthBounds | core/entity | OIT* ALPHA_CUTOUT=0.1 APPLY_TEXTURE_MATRIX NO_OVERLAY NO_CARDINAL_LIGHTING OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals,Lighting) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_clouds | R | OIT_CLOUDS.depthBounds | core/clouds | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces (+layout-only: Globals) | rgba32f max | >= W | Y | fill | QUADS |
| pipeline/oit_depth_bounds_crumbling | R | OIT_CRUMBLING.depthBounds | core/rendertype_crumbling | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_cull | R | OIT_DEPTH_BOUNDS_CULL | core/screenquad + core/oit_depth_bounds_cull | OIT* | - | 0Projection 1*DepthBoundsSampler (+layout-only: Globals) | rgba32f | >= W | Y | fill | TRIANGLES |
| pipeline/oit_depth_bounds_debug_filled_box | R | OIT_DEBUG_FILLED_BOX.depthBounds | core/position_color | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_debug_points | R | OIT_DEBUG_POINTS.depthBounds | core/debug_point + core/position_color | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals | rgba32f max | >= noW | N | fill | POINTS |
| pipeline/oit_depth_bounds_debug_quads | R | OIT_DEBUG_QUADS.depthBounds | core/position_color | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_debug_triangle_fan | R | OIT_DEBUG_TRIANGLE_FAN.depthBounds | core/position_color | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba32f max | >= noW | N | fill | TRIANGLE_FAN |
| pipeline/oit_depth_bounds_dragon_rays | R | OIT_DRAGON_RAYS.depthBounds | core/rendertype_lightning | OIT* OIT_ADDITIVE OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | TRIANGLES |
| pipeline/oit_depth_bounds_energy_swirl | R | OIT_ENERGY_SWIRL.depthBounds | core/entity | OIT* ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING APPLY_TEXTURE_MATRIX OIT_ADDITIVE OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_entity | R | OIT_ENTITY.depthBounds | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_entity_cull | R | OIT_ENTITY_CULL.depthBounds | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_entity_emissive | R | OIT_ENTITY_EMISSIVE.depthBounds | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_entity_shadow | R | OIT_ENTITY_SHADOW.depthBounds | core/rendertype_entity_shadow | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_eyes | R | OIT_EYES.depthBounds | core/entity | OIT* ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 (+layout-only: Globals,Lighting) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_flat_clouds | R | OIT_FLAT_CLOUDS.depthBounds | core/clouds | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_item | R | OIT_ITEM.depthBounds | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_item_glint | R | OIT_ITEM_GLINT.depthBounds | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_item_glint_special | R | OIT_ITEM_GLINT_SPECIAL.depthBounds | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:ENTITY_GLINT_SPECIAL | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_lightning | R | OIT_LIGHTNING.depthBounds | core/rendertype_lightning | OIT* OIT_ADDITIVE OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_lines_translucent | R | OIT_LINES_TRANSLUCENT.depthBounds | core/rendertype_lines | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog | rgba32f max | >= noW | N | fill | LINES |
| pipeline/oit_depth_bounds_particle | R | OIT_PARTICLE.depthBounds | core/particle | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_terrain | R | OIT_TERRAIN.depthBounds | core/terrain | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*Sampler0 | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_terrain_multidraw | R | OIT_TERRAIN_MULTIDRAW.depthBounds | core/terrain | OIT* ALPHA_CUTOUT=0.1 MULTIDRAW_TERRAIN OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*Sampler0 | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_text | R | OIT_TEXT.depthBounds | core/text | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_text_grayscale | R | OIT_TEXT_GRAYSCALE.depthBounds | core/text | OIT* IS_GRAYSCALE OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_text_grayscale_polygon_offset | R | OIT_TEXT_GRAYSCALE_POLYGON_OFFSET.depthBounds | core/text | OIT* IS_GRAYSCALE OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_text_polygon_offset | R | OIT_TEXT_POLYGON_OFFSET.depthBounds | core/text | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_translucent_block | R | OIT_TRANSLUCENT_BLOCK.depthBounds | core/block | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:BLOCK | 0DynamicTransforms 1Projection 2Fog 3*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | Y | fill | QUADS |
| pipeline/oit_depth_bounds_weather | R | OIT_WEATHER.depthBounds | core/particle | OIT* OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_depth_bounds_world_border | R | OIT_WORLD_BORDER.depthBounds | core/world_border | OIT* OIT_ADDITIVE OIT_ALPHA_ONLY OIT_DEPTH_BOUNDS | 0:POSITION_TEX | 0DynamicTransforms 1Projection 2*Sampler0 (+layout-only: Globals) | rgba32f max | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_beacon_beam | R | OIT_BEACON_BEAM.transmittance | core/rendertype_beacon_beam | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_breeze_wind | R | OIT_BREEZE_WIND.transmittance | core/entity | OIT* ALPHA_CUTOUT=0.1 APPLY_TEXTURE_MATRIX NO_OVERLAY NO_CARDINAL_LIGHTING OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Sampler0 (+layout-only: Globals,Lighting) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_clouds | R | OIT_CLOUDS.transmittance | core/clouds | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces 5*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_crumbling | R | OIT_CRUMBLING.transmittance | core/rendertype_crumbling | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:BLOCK | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_debug_filled_box | R | OIT_DEBUG_FILLED_BOX.transmittance | core/position_color | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_debug_points | R | OIT_DEBUG_POINTS.transmittance | core/debug_point + core/position_color | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3*DepthBoundsSampler | rgba16f additive / rgba16f additive | >= noW | N | fill | POINTS |
| pipeline/oit_transmittance_debug_quads | R | OIT_DEBUG_QUADS.transmittance | core/position_color | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_debug_triangle_fan | R | OIT_DEBUG_TRIANGLE_FAN.transmittance | core/position_color | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | TRIANGLE_FAN |
| pipeline/oit_transmittance_dragon_rays | R | OIT_DRAGON_RAYS.transmittance | core/rendertype_lightning | OIT* OIT_ADDITIVE OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | TRIANGLES |
| pipeline/oit_transmittance_energy_swirl | R | OIT_ENERGY_SWIRL.transmittance | core/entity | OIT* ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING APPLY_TEXTURE_MATRIX OIT_ADDITIVE OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*Sampler0 4*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_entity | R | OIT_ENTITY.transmittance | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_entity_cull | R | OIT_ENTITY_CULL.transmittance | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_entity_emissive | R | OIT_ENTITY_EMISSIVE.transmittance | core/entity | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_entity_shadow | R | OIT_ENTITY_SHADOW.transmittance | core/rendertype_entity_shadow | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_eyes | R | OIT_EYES.transmittance | core/entity | OIT* ALPHA_CUTOUT=0.1 EMISSIVE NO_OVERLAY NO_CARDINAL_LIGHTING OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler 4*Sampler0 (+layout-only: Globals,Lighting) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_flat_clouds | R | OIT_FLAT_CLOUDS.transmittance | core/clouds | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | - | 0CloudInfo 1Projection 2DynamicTransforms 3Fog 4^CloudFaces 5*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_item | R | OIT_ITEM.transmittance | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_item_glint | R | OIT_ITEM_GLINT.transmittance | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_item_glint_special | R | OIT_ITEM_GLINT_SPECIAL.transmittance | core/item | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:ENTITY_GLINT_SPECIAL | 0Projection 1DynamicTransforms 2Lighting 3Fog 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_lightning | R | OIT_LIGHTNING.transmittance | core/rendertype_lightning | OIT* OIT_ADDITIVE OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR | 0Projection 1DynamicTransforms 2Fog 3*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_lines_translucent | R | OIT_LINES_TRANSLUCENT.transmittance | core/rendertype_lines | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog 4*DepthBoundsSampler | rgba16f additive / rgba16f additive | >= noW | N | fill | LINES |
| pipeline/oit_transmittance_particle | R | OIT_PARTICLE.transmittance | core/particle | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_terrain | R | OIT_TERRAIN.transmittance | core/terrain | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*DepthBoundsSampler 6*Sampler0 | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_terrain_multidraw | R | OIT_TERRAIN_MULTIDRAW.transmittance | core/terrain | OIT* ALPHA_CUTOUT=0.1 MULTIDRAW_TERRAIN OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*DepthBoundsSampler 5*Sampler0 | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_text | R | OIT_TEXT.transmittance | core/text | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_text_grayscale | R | OIT_TEXT_GRAYSCALE.transmittance | core/text | OIT* IS_GRAYSCALE OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_text_grayscale_polygon_offset | R | OIT_TEXT_GRAYSCALE_POLYGON_OFFSET.transmittance | core/text | OIT* IS_GRAYSCALE OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_text_polygon_offset | R | OIT_TEXT_POLYGON_OFFSET.transmittance | core/text | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_translucent_block | R | OIT_TRANSLUCENT_BLOCK.transmittance | core/block | OIT* ALPHA_CUTOUT=0.1 OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:BLOCK | 0DynamicTransforms 1Projection 2Fog 3*DepthBoundsSampler 4*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | Y | fill | QUADS |
| pipeline/oit_transmittance_weather | R | OIT_WEATHER.transmittance | core/particle | OIT* OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*DepthBoundsSampler 5*Sampler0 (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_transmittance_world_border | R | OIT_WORLD_BORDER.transmittance | core/world_border | OIT* OIT_ADDITIVE OIT_ALPHA_ONLY OIT_TRANSMITTANCE | 0:POSITION_TEX | 0DynamicTransforms 1Projection 2*Sampler0 3*DepthBoundsSampler (+layout-only: Globals) | rgba16f additive / rgba16f additive | >= noW | N | fill | QUADS |
| pipeline/oit_water_mask | R | OIT_WATER_MASK | core/rendertype_water_mask | - | 0:POSITION | 0Projection 1DynamicTransforms (+layout-only: Globals) | (none) | >= W | Y | fill | QUADS |
| pipeline/opaque_particle | R | OPAQUE_PARTICLE | core/particle | - | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/outline_cull | R | OUTLINE_CULL | core/rendertype_outline | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 | - | Y | fill | QUADS |
| pipeline/outline_no_cull | R | OUTLINE_NO_CULL | core/rendertype_outline | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 | - | N | fill | QUADS |
| pipeline/panorama | R | PANORAMA | core/panorama | - | 0:POSITION | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 | - | Y | fill | QUADS |
| pipeline/secondary_block_outline | R | SECONDARY_BLOCK_OUTLINE | core/rendertype_lines | - | 0:POSITION_COLOR_NORMAL_LINE_WIDTH | 0Projection 1DynamicTransforms 2Globals 3Fog | rgba8 | >= W | N | fill | LINES |
| pipeline/sky | R | SKY | core/sky | - | 0:POSITION | 0Projection 1DynamicTransforms 2Fog (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLE_FAN |
| pipeline/solid_block | R | SOLID_BLOCK | core/block | - | 0:BLOCK | 0DynamicTransforms 1Projection 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 | >= W | Y | fill | QUADS |
| pipeline/solid_terrain | R | SOLID_TERRAIN | core/terrain | - | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*Sampler2 6*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/solid_terrain_multidraw | R | SOLID_TERRAIN_MULTIDRAW | core/terrain | MULTIDRAW_TERRAIN | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*Sampler2 5*Sampler0 | rgba8 | >= W | Y | fill | QUADS |
| pipeline/stars | R | STARS | core/stars | - | 0:POSITION | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 overlay | - | Y | fill | QUADS |
| pipeline/sunrise_sunset | R | SUNRISE_SUNSET | core/position_color | - | 0:POSITION_COLOR | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 translucent | - | Y | fill | TRIANGLE_FAN |
| pipeline/text | R | TEXT | core/text | - | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/text_grayscale | R | TEXT_GRAYSCALE | core/text | IS_GRAYSCALE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/text_grayscale_polygon_offset | R | TEXT_GRAYSCALE_POLYGON_OFFSET | core/text | IS_GRAYSCALE | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W bias(1,10) | Y | fill | QUADS |
| pipeline/text_grayscale_see_through | R | TEXT_GRAYSCALE_SEE_THROUGH | core/text | IS_GRAYSCALE IS_SEE_THROUGH | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 | - | Y | fill | QUADS |
| pipeline/text_polygon_offset | R | TEXT_POLYGON_OFFSET | core/text | - | 0:POSITION_TEX_LIGHTMAP_COLOR | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W bias(1,10) | Y | fill | QUADS |
| pipeline/text_see_through | R | TEXT_SEE_THROUGH | core/text | IS_SEE_THROUGH | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 translucent | - | Y | fill | QUADS |
| pipeline/tracy_blit | R | TRACY_BLIT | core/screenquad + core/blit_screen | - | - | 0*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| pipeline/translucent_block | R | TRANSLUCENT_BLOCK | core/block | ALPHA_CUTOUT=0.1 | 0:BLOCK | 0DynamicTransforms 1Projection 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/translucent_particle | R | TRANSLUCENT_PARTICLE | core/particle | - | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/translucent_terrain | R | TRANSLUCENT_TERRAIN | core/terrain | ALPHA_CUTOUT=0.1 | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*Sampler2 6*Sampler0 | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/translucent_terrain_multidraw | R | TRANSLUCENT_TERRAIN_MULTIDRAW | core/terrain | ALPHA_CUTOUT=0.1 MULTIDRAW_TERRAIN | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*Sampler2 5*Sampler0 | rgba8 translucent | >= W | Y | fill | QUADS |
| pipeline/vignette | R | VIGNETTE | core/position_tex_color | - | 0:POSITION_TEX_COLOR | 0Projection 1DynamicTransforms 2*Sampler0 (+layout-only: Globals) | rgba8 zero,one_minus_src_color/zero,one | - | Y | fill | QUADS |
| pipeline/water_mask | R | WATER_MASK | core/rendertype_water_mask | - | 0:POSITION | 0Projection 1DynamicTransforms (+layout-only: Globals) | rgba8 translucent mask=0 | >= W | Y | fill | QUADS |
| pipeline/weather | R | WEATHER | core/particle | - | 0:PARTICLE | 0Projection 1DynamicTransforms 2Fog 3*Sampler2 4*Sampler0 (+layout-only: Globals) | rgba8 translucent | >= noW | N | fill | QUADS |
| pipeline/wireframe | O | WIREFRAME | core/terrain | - | 0:BLOCK | 0ChunkSection 1Globals 2Projection 3TerrainUniform 4Fog 5*Sampler2 6*Sampler0 | rgba8 | >= W | Y | wire | QUADS |
| pipeline/wireframe_multidraw | O | WIREFRAME_MULTIDRAW | core/terrain | MULTIDRAW_TERRAIN | 0:BLOCK 1:CHUNK_DATA_INSTANCED(inst/1) | 0Globals 1Projection 2TerrainUniform 3Fog 4*Sampler2 5*Sampler0 | rgba8 | >= W | Y | wire | QUADS |
| pipeline/world_border | R | WORLD_BORDER | core/world_border | - | 0:POSITION_TEX | 0DynamicTransforms 1Projection 2*Sampler0 (+layout-only: Globals) | rgba8 overlay | >= W bias(3,3) | N | fill | QUADS |
| blur/0 | P | post_effect/blur.json pass 0 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| blur/1 | P | post_effect/blur.json pass 1 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| blur/2 | P | post_effect/blur.json pass 2 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| blur/3 | P | post_effect/blur.json pass 3 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| blur/4 | P | post_effect/blur.json pass 4 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| blur/5 | P | post_effect/blur.json pass 5 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| creeper/0 | P | post_effect/creeper.json pass 0 | core/screenquad + post/color_convolve | - | - | 0SamplerInfo 1ColorConfig 2*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| creeper/1 | P | post_effect/creeper.json pass 1 | core/screenquad + post/bits | - | - | 0SamplerInfo 1BitsConfig 2*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| entity_outline/0 | P | post_effect/entity_outline.json pass 0 | core/screenquad + post/entity_sobel | - | - | 0SamplerInfo 1*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| entity_outline/1 | P | post_effect/entity_outline.json pass 1 | core/screenquad + post/entity_outline_box_blur | - | - | 0SamplerInfo 1BlurConfig 2*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| entity_outline/2 | P | post_effect/entity_outline.json pass 2 | core/screenquad + post/entity_outline_box_blur | - | - | 0SamplerInfo 1BlurConfig 2*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| entity_outline/3 | P | post_effect/entity_outline.json pass 3 | core/screenquad + post/blit | - | - | 0BlitConfig 1*InSampler (+layout-only: SamplerInfo,Globals) | rgba8 | - | Y | fill | TRIANGLES |
| invert/0 | P | post_effect/invert.json pass 0 | core/screenquad + post/invert | - | - | 0SamplerInfo 1InvertConfig 2*InSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| invert/1 | P | post_effect/invert.json pass 1 | core/screenquad + post/blit | - | - | 0BlitConfig 1*InSampler (+layout-only: SamplerInfo,Globals) | rgba8 | - | Y | fill | TRIANGLES |
| spider/0 | P | post_effect/spider.json pass 0 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| spider/1 | P | post_effect/spider.json pass 1 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| spider/2 | P | post_effect/spider.json pass 2 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| spider/3 | P | post_effect/spider.json pass 3 | core/screenquad + post/box_blur | - | - | 0SamplerInfo 1BlurConfig 2Globals 3*InSampler | rgba8 | - | Y | fill | TRIANGLES |
| spider/4 | P | post_effect/spider.json pass 4 | post/rotscale + post/spiderclip | - | - | 0RotScaleConfig 1SamplerInfo 2SpiderConfig 3*InSampler 4*BlurSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| spider/5 | P | post_effect/spider.json pass 5 | post/rotscale + post/spiderclip | - | - | 0RotScaleConfig 1SamplerInfo 2SpiderConfig 3*InSampler 4*BlurSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| spider/6 | P | post_effect/spider.json pass 6 | post/rotscale + post/spiderclip | - | - | 0RotScaleConfig 1SamplerInfo 2SpiderConfig 3*InSampler 4*BlurSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| spider/7 | P | post_effect/spider.json pass 7 | post/rotscale + post/spiderclip | - | - | 0RotScaleConfig 1SamplerInfo 2SpiderConfig 3*InSampler 4*BlurSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| spider/8 | P | post_effect/spider.json pass 8 | post/rotscale + post/spiderclip | - | - | 0RotScaleConfig 1SamplerInfo 2SpiderConfig 3*InSampler 4*BlurSampler (+layout-only: Globals) | rgba8 | - | Y | fill | TRIANGLES |
| spider/9 | P | post_effect/spider.json pass 9 | core/screenquad + post/blit | - | - | 0BlitConfig 1*InSampler (+layout-only: SamplerInfo,Globals) | rgba8 | - | Y | fill | TRIANGLES |

## Appendix B: probes and how to re-run them

Files in `docs/research/probes/pipelines/`:

- `Dump.java` – needs the 26.3 client jar and its libraries (including `*-natives-macos-arm64` jars) on the classpath;
  JDK 25. The probe resolved every `libraries[].name` of the 26.3 version JSON from `~/.gradle/caches/modules-2/files-2.1`
  (falling back to `~/Library/Application Support/minecraft/libraries`). It sets `RenderSystem.DEVICE` (private static) to a proxy `GpuDevice` whose `getDeviceInfo()` returns a
  Metal-like `DeviceInfo` (`isZZeroToOne = true`, `shaderDrawParameters = arg1`, `isExplicitDepthRequired = true`,
  `wireframeFillMode = true`), creates the real `PipelineBuilder` over a proxy `GpuDeviceBackend` whose
  `compilePipeline` captures the `CreateInfo` and runs SPIRV-Cross MSL, collects every static `RenderPipeline` /
  `OitPipelineSet` in `RenderPipelines` plus `requiredPipelines()`/`optionalPipelines()` and rebuilds the post-effect
  pipelines from `assets/minecraft/post_effect/*.json` like `PostChain.createPass`. Usage:
  `java --enable-native-access=ALL-UNNAMED -cp classes:<cp> Dump <outDir> <drawParams:true> <flipY:true> <mslVersion:20400> <client.jar>`.
  Output: `<outDir>/pipelines.json` and `<outDir>/msl/NNN_<location>.{vertex,fragment}.{spv,metal}`.
- `validate.m` – `clang -fobjc-arc -framework Foundation -framework Metal validate.m -o validate`;
  `./validate <outDir> 0x20004` compiles every `.metal` file and builds every PSO (vertex descriptor with
  `30 - slot` buffer indices, D32 depth format), writes `<outDir>/metal_reflection.json`. Run with
  `MTL_DEBUG_LAYER=1` for API validation.
- `conv.m` – Y-orientation/winding/negative-viewport/depth-as-texture2d test (§6.4).
- `align.m` – vertex attribute offset/stride alignment test (§6.2).
- `ubosize.m` – the undersized-UBO validation failure (§6.5).
- `table.py` – regenerates Appendix A from `pipelines.json`.

`docs/research/data/pipelines-26.3.json` is the compact dump (no MSL text): per pipeline the `RenderPipeline` state,
`registration`, `fields` (Java field names), `uniformIndices`, the full `createInfo` (vertex buffers, attribute
bindings, uniforms, per-shader reflection: inputs, outputs, descriptors after patching, push constants, MSL entry
name, std140 UBO sizes, SPIR-V capabilities), and `metalReflection` (per-stage Metal bindings with buffer sizes).
