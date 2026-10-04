# SPIR-V to MSL translation experiment (Minecraft 26.3 renderpearl, Ingot Metal backend)

Status: done, all green. Date: 2026-10-04. Machine: Apple M4 Pro (10P+4E cores, 24 GB), macOS 27.2 (build 26B5091g), Xcode SDK 26.5, OpenJDK 25.0.1, LWJGL 3.4.3+4.

Experiment code: `/Users/eric/Code/ingot/tools/shader-xlate/` (see section 3). Committed result snapshot: `tools/shader-xlate/results/`.

## 0. Summary

* Every vanilla 26.3 render pipeline was pushed through the **real** renderpearl frontend (`com.mojang.renderpearl.frontend.shaders.PipelineBuilder` from the client jar, driven headlessly with proxy `GpuDevice`/`GpuDeviceBackend` objects). The `BackendRenderPipeline.CreateInfo` that a backend receives was captured, each stage was translated to MSL with SPIRV-Cross (LWJGL `lwjgl-spvc` 3.4.3), compiled with the **runtime** Metal compiler (`MTLDevice newLibraryWithSource:`) and linked into an `MTLRenderPipelineState` with the real vertex layout, color formats, blend state and depth format.
* Coverage: 220 pipelines = 190 required + 2 optional `RenderPipelines` + 24 post-effect passes (all 5 `post_effect/*.json`) + 4 synthetic test pipelines. That covers all 72 vanilla shader files (63 in `shaders/core`, 9 in `shaders/post`; `core/position.*` is shipped but unreferenced in 26.3 and was added as a synthetic pipeline).
* Result: **220/220 translated, 171/171 unique MSL files compiled, 220/220 PSOs created**, for MSL 2.4 and 3.0, in every binding mode tried. No translation failure occurred at any point; nothing had to be patched in the shaders.
* Runtime draws (with `MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1`) verified the conventions: with `FLIP_VERTEX_Y` + positive viewport + `MTLWindingClockwise`, Metal produces the same memory image as the vanilla Vulkan backend ("GL memory layout"), `gl_FragCoord` matches GL, `gl_FrontFacing` matches GL, depth is 0..1, texture `v=0` is the first uploaded row; the full argument-table layout (vertex buffer at `buffer(30)`, push constants at `buffer(14)` in both stages, UBO, `texture_buffer<float>`/`texture_buffer<uint>`, combined image sampler) and depth-texture sampling through `texture2d<float>` + `[[depth(any)]]` output all work.
* Cost on M4 Pro (171 unique MSL files, 220 PSOs): Java side (shaderc + reflection + spvc) ~0.6 s single-threaded, ~0.25 s on 12 threads. Metal, cold: ~3.2-3.6 s serial / ~0.37 s parallel for `newLibraryWithSource`, ~1.2-1.6 s serial / ~0.17 s parallel for PSOs. Metal, warm (system shader cache hit, i.e. every launch after the first): ~8 ms + ~9 ms total.

## 1. How to reproduce

```
cd /Users/eric/Code/ingot/tools/shader-xlate
./fetch-libs.sh            # LWJGL 3.4.3 core/shaderc/spvc + natives-macos-arm64 from repo1.maven.org (sha1-verified)
./run.sh msl24-compact     # translate + metalcheck (compile + PSO) + metalrender (runtime draws)
./variants.sh              # every option variant listed in section 8.2
build/metalcheck out/msl24-compact 20400 --nonce [--parallel]   # cold-cache Metal timings
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 build/metalrender out/msl24-compact 20400
```

Requirements: a Loom-cached 26.3 (`~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar` and `mojang_minecraft_info.json`; override with `MC_JAR` / `MC_VERSION_JSON`), the 26.3 libraries in the Gradle cache or `~/Library/Application Support/minecraft/libraries` (`classpath.py` resolves them), Xcode command line tools (`xcrun clang`), JDK 25 (`JAVA_HOME`, default is the path in `run.sh`).

Files:

| Path | What |
|---|---|
| `src/MslTranslator.java` | The reusable part: `CreateInfo` -> slot table + MSL per stage. Written to be lifted into the mod. |
| `src/ShaderXlate.java` | Driver: fake device/backend proxies, pipeline enumeration (RenderPipelines, post effects, synthetic), timing, manifest output. |
| `metalcheck/metalcheck.m` | Runtime Metal compile of every unique `.metal` + `MTLRenderPipelineState` per pipeline. Flags `--parallel`, `--nonce`, `--safe-math`, `--preserve-invariance`. |
| `metalcheck/metalrender.m` | Runtime draws that verify coordinate conventions and the binding layout. |
| `test-shaders/shaders/core/*.{vsh,fsh}` | Synthetic shaders (`ingot_test:` namespace): push constants + texel buffers, orientation, early return. |
| `results/` | Snapshot of `translate.tsv`, `pipelines.json` (full per-pipeline manifest incl. slots/used bindings/vertex layout), `metalcheck-*.json`, `variants.txt`, `metalrender-*.txt`, `msl-samples/*.metal`. |
| `out/<variant>/` (gitignored) | `msl/*.metal`, `spv/*.spv` (patched SPIR-V as the backend sees it), manifest, timings. |

Deviation from the original plan: `xcrun -sdk macosx metal` is **not installed** on this machine (`error: cannot execute tool 'metal' due to missing Metal Toolchain; use: xcodebuild -downloadComponent MetalToolchain`). The experiment uses the runtime compiler instead. That is the compiler the mod will actually use on players' Macs (they do not have the offline toolchain), and it additionally allows PSO creation, which validates cross-stage linkage, vertex descriptors and attachment formats. The `.metal` files in `out/` can be fed to `metal -std=macos-metal2.4 -c` once the toolchain component is downloaded.

## 2. What the 26.3 frontend hands a backend

Sources (Vineflower): `scratchpad/mc/src-rp/com/mojang/renderpearl/frontend/shaders/{GlslCompiler,PipelineBuilder,SPIRVModule,SpvUtil}.java`, `src-full/net/minecraft/client/renderer/{ShaderManager,RenderPipelines,PostChain,ShaderDefines}.java`, `src-rp/com/mojang/renderpearl/backend/{opengl/GlPipelineRecompiler,vulkan/VulkanRenderPipeline,vulkan/VulkanRenderPass,vulkan/VulkanGpuSurface,vulkan/VulkanDevice}.java`.

### 2.1 GLSL -> SPIR-V (`GlslCompiler`)

`PipelineBuilder(GpuDeviceBackend)` creates `new GlslCompiler(deviceInfo.isZZeroToOne(), deviceInfo.features().shaderDrawParameters())`. Per stage, `compileToSpv(name, source, type, shaderDefines, shaderSource)` calls

```java
Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, type == FRAGMENT ? 1 : 0, /*input file name*/ "minecraft:core/terrain", /*entry*/ "main", options)
```

with options built in `createBaseShaderOptions()`:

| shaderc call | value | meaning |
|---|---|---|
| `shaderc_compile_options_set_target_env(opts, 0, 4202496)` | `shaderc_target_env_vulkan`, `shaderc_env_version_vulkan_1_2` (`(1<<22)|(2<<12)`) | Vulkan GLSL semantics, output is **SPIR-V 1.5** (verified: header word `0x00010500`, generator glslang) |
| `set_auto_bind_uniforms(opts, true)` | | shaders declare `uniform sampler2D X;` / `layout(std140) uniform Block {..};` without `binding=`; glslang assigns them |
| `set_preserve_bindings(opts, false)` | | |
| `set_generate_debug_info(opts)` | | OpName/OpLine kept, so spvc output keeps GLSL names (`Sampler0`, `fragColor`, ...) |
| `set_optimization_level(opts, 0)` | `shaderc_optimization_level_zero` | unoptimized SPIR-V (all declared resources stay in the module, see 2.4) |
| macro `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE` = "" | if `DeviceInfo.isZZeroToOne()` | only used in `include/oit_common.glsl` `deviceToLinearDepth` |
| macro `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE` = "" | if `RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds().isExplicitDepthRequired()` | OIT shaders write `gl_FragDepth = gl_FragCoord.z` (section 7) |
| macro `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE` = "" | if `features().shaderDrawParameters()` | no vanilla shader reads it |
| then every `ShaderDefines.values()` entry | key -> string value (floats via `String.valueOf(float)`, e.g. `ALPHA_CUTOUT=0.1`, `OIT_COEFF_COUNT=8`) | |
| then every `ShaderDefines.flags()` entry | key -> "" | e.g. `MULTIDRAW_TERRAIN`, `OIT_ACCUMULATE` |
| `set_include_callbacks(opts, resolver, release, 0)` | see 2.2 | |

Not set: source language (default GLSL), forced version/profile (shaders say `#version 330` + `#extension GL_ARB_separate_shader_objects : require`), `auto_map_locations` (every vanilla varying/attribute has an explicit `layout(location=N)`; a mod shader without locations will fail in glslang), HLSL options. Compiler handles are pooled (`LongArrayList`, `synchronized` acquire/release), so compilation is thread-safe and runs on the reload worker pool.

Pitfall: `GlslCompiler` reads `RenderSystem.getDevice()` inside every compile. The Metal device must be installed in `RenderSystem` (it is, via `RenderSystem.initRenderer`) and its `DeviceInfo.hintsAndWorkarounds()` must be final before the first pipeline compiles.

Recommended `DeviceInfo` values for the Metal backend (the experiment used these): `isZZeroToOne = true`, `features.shaderDrawParameters = true` (Metal `[[vertex_id]]`/`[[instance_id]]` include base vertex/instance, section 6.4), `hintsAndWorkarounds.isExplicitDepthRequired = true` on Apple GPUs (matches what both vanilla backends report on the same hardware via `Util.isAppleSiliconMac(name)`, which is `name.startsWith("Apple")`; see section 7). `Projection.java` and `Camera.java` also read `isZZeroToOne()` to build 0..1 projection matrices, so **do not** use spvc's `FIXUP_DEPTH_CONVENTION`.

### 2.2 Includes and shader lookup

* Stage sources: `ShaderSource.getShader(Identifier id, ShaderType type)`; `ShaderManager` registers `assets/<ns>/shaders/<path>.vsh|.fsh` under `<ns>:<path>` (`ShaderType.idConverter()` = `FileToIdConverter("shaders", ext)`). The pipeline names shaders as e.g. `minecraft:core/terrain`, `minecraft:post/box_blur`.
* Includes: shaders use `#include <minecraft:fog.glsl>` (angle form; quoted form would reach the same resolver because the include type is ignored). `ShaderManager` registers `assets/<ns>/shaders/include/<name>.glsl` under `<ns>:<name>.glsl` (`SHADER_INCLUDE_CONVERTER.fileToId(location).withSuffix(".glsl")`). The resolver does `Identifier.tryParse(requested)`; a malformed id returns a `CachedIncludeSource` whose `source_name` is "" and content is "malformed id", a missing one "not found" (shaderc then reports the error). Include guards are the shaders' own `#ifndef MINECRAFT_..._GLSL`. Nested includes work (`oit_common.glsl` includes `projection.glsl`). `#moj_import` is not used by any 26.3 shader; it only survives in stale comments in `core/gui.{vsh,fsh}` and `core/position_tex_color.{vsh,fsh}` ("Can't moj_import in things used during startup"), which inline their UBO blocks instead of including them.
* A second `ShaderSource` exists: `GameRenderer.preloadUiShader` builds one over the raw `ResourceManager` before the first resource reload, installs it as `RenderSystem.setFallbackPipelineCache(new PipelineCache(device, src))` and synchronously compiles `RenderPipelines.GUI`, `GUI_TEXTURED` (and `TRACY_BLIT` when Tracy is attached). These go through the same `PipelineBuilder`, so the Metal backend must be able to compile pipelines immediately after device creation.
* No text preprocessing happens before shaderc: defines go through `add_macro_definition`, not through `ShaderDefines.asSourceDirectives()`.

### 2.3 Reflection (`SPIRVModule`)

`SPIRVModule.reflect()` parses the SPIR-V with spvc (`SPVC_BACKEND_NONE`) and builds: stage inputs (resource type 3), stage outputs (4), descriptors for resource types `SpvUtil.DESCRIPTOR_TYPES = {1 UNIFORM_BUFFER, 2 STORAGE_BUFFER, 6 STORAGE_IMAGE, 7 SAMPLED_IMAGE, 10 SEPARATE_IMAGE, 11 SEPARATE_SAMPLERS}` in that order, and push constants (9) with `spvc_compiler_get_declared_struct_size`. Each descriptor/interface variable remembers the **word offset** of its `DescriptorSet`/`Binding`/`Location` decoration (`spvc_compiler_get_binary_offset_for_decoration`) so that setters patch the SPIR-V words in place.

### 2.4 The binding remap (`PipelineBuilder.generateBackendCreateInfo`)

Exact algorithm:

```java
// shaderCreateInfos is in EnumMap order: VERTEX first, then FRAGMENT
for (Shader shader : shaderCreateInfos) {
    for (Descriptor descriptor : shader.module().reflect().descriptors()) {      // types 1,2,6,7,10,11 in that order
        BindGroupLayout.UniformDescription pipelineUniform = pipelineUniformsByName.get(descriptor.name());
        if (pipelineUniform == null) throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
        int newBinding = uniformBindings.computeIfAbsent(descriptor.name(), k -> uniformBindings.size());
        if (newBinding >= uniformResourceTypes.size()) { /* first sighting: append to `uniforms` */ }
        else { /* must match resource type and image dimension across stages */ }
        descriptor.binding(newBinding);      // writes the SPIR-V word in place
        descriptor.descriptorSetIndex(0);    // writes the SPIR-V word in place
    }
}
```

Consequences for the backend:

1. After the frontend, **every** resource is `DescriptorSet 0, Binding i`, where `i` is a pipeline-wide index in order of first appearance (vertex stage first; within a stage UBOs before sampled images; within a type, spvc's reflection order, which in practice follows glslang's result-id order, roughly order of first use). Example `minecraft:pipeline/cutout_terrain`: `0 ChunkSection, 1 Globals, 2 Projection, 3 TerrainUniform, 4 Fog, 5 Sampler2, 6 Sampler0`. Never hardcode this order; use `CreateInfo.uniforms()`.
2. `CreateInfo.uniforms().get(i)` is the `UniformDescription(name, type, gpuFormat)` for binding `i`. It contains only uniforms that some stage **declares**; bind-group uniforms no shader declares are dropped. `FrontendRenderPipeline.uniformIndices()` maps name -> `i`, and `RenderPassBackend.setUniform(int index, Object value)` receives that `i`.
3. Declared is not the same as used: SPIR-V is unoptimized, so a stage that includes `fog.glsl` but never reads `Fog` still has the `Fog` descriptor and gets a binding. In MSL, spvc drops unused resources from the entry point signature. 60 (pipeline, uniform) pairs in vanilla are declared but unused in both stages (e.g. `oit_composite` declares `Projection`). Use `spvc_compiler_msl_is_resource_used` per stage (recorded as `usedBindings` in `pipelines.json`) to decide which stage(s) to bind each slot to.
4. Because bindings are pipeline-wide, the **same shader+defines produce different SPIR-V (and MSL) in different pipelines**. Cache MSL/`MTLLibrary` by a hash of the final MSL text (or of the patched SPIR-V words), not by shader id + defines. With compact slots (4.2) vanilla needs 171 unique MSL files for 220 pipelines; identity numbering needs 175.
5. The patched words live in `SpvModule.spv()`. A backend must re-parse `spv()` with its own spvc context; the reflection's spvc compiler was built from the pre-patch words.
6. `finishCompile()` of the frontend's pending pipeline closes all `SpvModule`s right after the backend's `Pending.finishCompile()` returns (`executor.execute(() -> shaderCreateInfos.forEach(s -> s.module().close()))`). Finish all SPIR-V work in `GpuDeviceBackend.compilePipeline(CreateInfo)` or in the returned `Pending.finishCompile()`.
7. Threading: `ShaderManager.compilePipelines` calls `device.compilePipeline(pipeline, src, taskExecutor)`; the frontend work and `GpuDeviceBackend.compilePipeline(CreateInfo)` run on worker threads concurrently, `finishCompile` runs on the reload executor. `PipelineCache.get` compiles lazily (post-effect passes, pipelines not in `RenderPipelines`) with `device.compilePipeline(pipeline, shaderSource, Util.backgroundExecutor()).join().finishCompile()`, so the work runs on a background thread while the calling (render) thread blocks. The translator must be thread-safe (one spvc context per call; verified: 12-thread run produces byte-identical output).

Other frontend validation that a Metal backend can rely on (all in `PipelineBuilder`): at most one push-constant block per shader and its declared size <= `RenderPipeline.pushConstantSize()` (<= 128, enforced in `RenderPipeline.Builder.build`); no `Component` decoration on interface variables; no 64-bit or struct varyings; VS output / FS input base type, vector length and `flat` must match per location; vertex attribute base type must match the `GpuFormat` component type and have enough components; 1D and 3D textures rejected; image dim must match `UniformType` (2D/Cube/Rect -> `COMBINED_IMAGE_SAMPLER`, Buffer -> `TEXEL_BUFFER`). Texel buffers must be bound as a whole buffer (`FrontendRenderPass`: "Uniform texel buffers do not support a slice of a buffer, must be entire buffer"), so their offset is always 0.

### 2.5 `CreateInfo` contents relevant to translation

`BackendRenderPipeline.CreateInfo(name, shaders, vertexBuffers, attribBindings, uniforms, pushConstantsSize, depthStencilState, polygonMode, cull, colorTargetStates, primitiveTopology)`:

* `shaders`: `Shader(name = "minecraft:core/terrain", entryPoint = "main", module)`.
* `vertexBuffers`: `VertexBuffer(bufferSlot, stride, stepRate)`; `stepRate > 0` means per-instance with that divisor (`CHUNK_DATA_INSTANCED`, slot 1, used by the 7 `*_multidraw` terrain pipelines).
* `attribBindings`: `AttribBinding(bufferSlot, location, offset, GpuFormat)`; `location` is the SPIR-V `Location` of the VS input with the same name as the `VertexFormatElement`.
* `uniforms`: see 2.4.
* `colorTargetStates`: up to 8, entries may be `null` (unused target).
* `depthStencilState == null` means no depth test. The Vulkan backend always builds a variant with a `D32_SFLOAT` depth attachment and, only when `depthStencilState == null`, an additional variant without depth (`VulkanRenderPipeline.compile`, `renderingInfo.depthAttachmentFormat(126)` / `(0)`). Metal needs the same: `depthAttachmentPixelFormat` is baked into the PSO. 56 of the 220 pipelines have no depth state, so plan for ~276 PSOs.

### 2.6 Reference: what the two vanilla backends do

* GL backend (`GlPipelineRecompiler.decompileShader`) uses spvc -> GLSL 330 with `GLSL_VERSION=330`, `GLSL_ENABLE_420PACK_EXTENSION=false`, `GLSL_EMIT_PUSH_CONSTANT_AS_UNIFORM_BUFFER=true`, `GLSL_SUPPORT_NONZERO_BASE_INSTANCE=drawParametersSupported`, and outside RenderDoc mode `FORCE_ZERO_INITIALIZED_VARIABLES=true` and `FLATTEN_MULTIDIMENSIONAL_ARRAYS=true`; it renames interface variables to `_vert_input_%02d`, `_interface_variable_%02d`, `_frag_output_%02d` and descriptors to `_uniform_%02d_%02d` because GL links by name. MSL links by `[[user(locnN)]]`/`[[attribute(N)]]`/`[[color(N)]]`, so **no renaming is needed for Metal**. It does not flip Y or fix depth in the shader.
* Vulkan backend: viewport `x=0, y=0, width=w, height=h` (positive height), `frontFace = VK_FRONT_FACE_CLOCKWISE`, `cullMode = BACK` if `cull`, scissor passed through unchanged, depth format D32_SFLOAT, and the surface present is a `vkCmdBlitImage` with `dstOffsets` y from `copyHeight` to 0 (a vertical flip). In other words it renders in "GL memory layout" (memory row 0 is the bottom of the scene) and flips once at present. Section 6 reproduces this on Metal.

## 3. Experiment design

1. `ShaderXlate` sets `RenderSystem.DEVICE` (private static, via reflection) to a `java.lang.reflect.Proxy` `GpuDevice` whose only implemented method is `getDeviceInfo()`, and creates `new PipelineBuilder(backendProxy)` where `backendProxy` implements `GpuDeviceBackend.getDeviceInfo()` and `compilePipeline(CreateInfo)`. The latter is where the Metal backend's code would run: it dumps the patched SPIR-V and calls `MslTranslator.translate(createInfo, options)`.
2. Pipelines: `RenderPipelines.requiredPipelines()` + `optionalPipelines()` (these load headlessly), plus every pass of every `assets/minecraft/post_effect/*.json` rebuilt exactly like `PostChain.createPass` (`RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)`, one `<name>Sampler` combined image sampler per input, `SamplerInfo` UBO, one UBO per uniform block, `ColorTargetState.DEFAULT`), plus 4 synthetic pipelines in the `ingot_test` namespace.
3. `ShaderSource` reads stage files and includes straight from the client jar with the same id conventions as `ShaderManager`.
4. Defaults matching an Apple-silicon Metal device: `isZZeroToOne=true`, `shaderDrawParameters=true`, `isExplicitDepthRequired=true`.
5. `metalcheck` compiles each unique `.metal` with `MTLCompileOptions.languageVersion` 2.4 (or 3.0) and builds a PSO per pipeline: `MTLVertexDescriptor` from `attribBindings`/`vertexBuffers` (vertex buffer slot s -> buffer index `30 - s`), color attachments from `colorTargetStates` (format, write mask, blend factors/ops), `Depth32Float` when `depthStencilState != null`, `inputPrimitiveTopology` from the topology.
6. `metalrender` performs real draws (section 6 and 8.3).
7. Timing: `--nonce` renames every `main0(` to `main0_<uuid>(` so neither the Metal front-end cache (source -> AIR) nor the back-end cache (AIR -> GPU binary) can serve the request. A comment or an unused global was not enough to defeat the caches reliably.

## 4. The working spvc configuration

### 4.1 Options

All set on a compiler created with `spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ...)`, one context per stage translation.

| Option | Value | Why / what was observed |
|---|---|---|
| `SPVC_COMPILER_OPTION_MSL_VERSION` | `20400` (2.4) | Works. `30000` produces **byte-identical** MSL for all 171 files, but cold `newLibraryWithSource` with `MTLLanguageVersion3_0` took ~25% longer (4.0 s vs 3.2 s serial). Use 2.4 unless a 3.0 feature is needed. |
| `SPVC_COMPILER_OPTION_MSL_PLATFORM` | `SPVC_MSL_PLATFORM_MACOS` | |
| `SPVC_COMPILER_OPTION_FLIP_VERTEX_Y` | `true` on vertex stages | Required for GL memory layout (section 6). spvc appends `out.gl_Position.y = -(out.gl_Position.y);` before **every** return of the entry point (checked with the synthetic early-return shader). Without it everything compiles, but the image is upside down relative to the Vulkan backend and front faces flip (runtime test fails). |
| `SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE` | `true` | `samplerBuffer`/`isamplerBuffer`/`usamplerBuffer` become `texture_buffer<float|int|uint>` and `texelFetch(b, i)` becomes `b.read(uint(i))`. With `false`, spvc emits `texture2d<T>` plus `spvTexelBufferCoord(tc) = uint2(tc % 4096, tc / 4096)` (width from `MSL_TEXEL_BUFFER_TEXTURE_WIDTH`), which needs a 2D linear texture on the Metal side; binding a texture-buffer texture to it fails API validation ("incorrect type of texture (MTLTextureTypeTextureBuffer) ... expect MTLTextureType2D"). |
| `SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN` | `true` | Keeps `gl_PointSize` as `[[point_size]]` (`core/debug_point.vsh`, the 4 `POINTS` pipelines). |
| `SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES` | `true` | Same as the GL backend's non-debug mode. Changes 47 files (e.g. `float4 _418;` -> `float4 _418 = {};`), compile and runtime unaffected. Defends against undefined reads in resource-pack shaders. |
| `SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS` | `true` | No effect on vanilla (byte-identical output with `false`; every vanilla output is `vec4`). Kept for mod shaders that write fewer components than the attachment has. |
| `SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING` | `false` (compact mode) | With `true` and no explicit bindings, spvc uses the SPIR-V `Binding` decoration as the MSL index, which produced byte-identical output to the explicit identity mapping. |
| explicit resource bindings | `spvc_compiler_msl_add_resource_binding_2` per `CreateInfo.uniforms()` entry and stage | See 4.2 / 4.3. |
| push-constant binding | `desc_set = SPVC_MSL_PUSH_CONSTANT_DESC_SET` (`~0u`), `binding = SPVC_MSL_PUSH_CONSTANT_BINDING` (0), `msl_buffer = 14` | spvc's magic pair for the push-constant block. |
| `spvc_compiler_set_entry_point(compiler, "main", model)` | model 0 vertex, 4 fragment | |

Not needed (left at spvc defaults, verified by assertions or output inspection): `FIXUP_DEPTH_CONVENTION` (the game already produces 0..1 depth, section 2.1), argument buffers, swizzle buffer, buffer-size buffer, output buffer (the translator asserts `spvc_compiler_msl_needs_swizzle_buffer`, `_needs_buffer_size_buffer`, `_needs_output_buffer` are all false for every vanilla stage), `ENABLE_BASE_INDEX_ZERO`, `FLATTEN_MULTIDIMENSIONAL_ARRAYS` (GLSL-only concern), `INVARIANT_FP_MATH` (no `invariant` in vanilla), shader input/output overrides, vertex attribute overrides (stage_in is used, section 5.2).

### 4.2 Metal argument-table layout

Per stage (both stages use the same numbers; a slot that a stage does not use is simply not bound for that stage):

| Metal index | Contents |
|---|---|
| `[[buffer(0..13)]]` | uniform buffers (compact: k-th UBO of the pipeline -> `buffer(k)`) |
| `[[buffer(14)]]` | push constants (`setVertexBytes`/`setFragmentBytes`) |
| `[[buffer(15..30)]]` | vertex buffers: renderpearl vertex buffer slot `s` -> `buffer(30 - s)` (only referenced by the `MTLVertexDescriptor`; stage_in means shaders never name these) |
| `[[texture(n)]]` | combined image samplers and texel buffers (compact: j-th texture-ish slot -> `texture(j)`) |
| `[[sampler(n)]]` | the sampler half of combined image samplers (compact: m-th sampler -> `sampler(m)`) |

Why this split: Metal has 31 buffer slots per stage shared between `constant` arguments and vertex fetch; renderpearl allows up to 16 vertex buffer slots (`vertexFormatPerBuffer = new VertexFormat[16]`), so slots 15..30 cover all of them, and 14 UBOs is far above vanilla's maximum of 5. macOS allows 16 samplers per stage; the compact mapping keeps sampler indices dense. Vanilla maxima per pipeline: 12 slots total, 5 UBOs, 7 combined image samplers, 1 texel buffer (2 in the synthetic test), 2 vertex buffers, 7 attributes, 2 color targets. The maximum sampler index under identity numbering is 11 (`oit_accumulate_item_glint`), so identity also fits vanilla, but compact leaves more headroom for mods. The task's suggestion of push constants at `buffer(29)` would collide with vertex buffer slot 1 under this scheme (terrain multidraw uses slot 1), hence 14.

### 4.3 Essential code (from `tools/shader-xlate/src/MslTranslator.java`)

```java
public record Slot(int binding, String name, UniformType type, int mslBuffer, int mslTexture, int mslSampler) {}

public static List<Slot> assignSlots(List<BindGroupLayout.UniformDescription> uniforms, BindingMode mode) {
    List<Slot> slots = new ArrayList<>(uniforms.size());
    int nextBuffer = 0, nextTexture = 0, nextSampler = 0;
    for (int binding = 0; binding < uniforms.size(); binding++) {        // binding == list index (frontend contract)
        BindGroupLayout.UniformDescription u = uniforms.get(binding);
        int buf = -1, tex = -1, smp = -1;
        switch (u.type()) {
            case UNIFORM_BUFFER -> buf = mode == BindingMode.IDENTITY ? binding : nextBuffer++;
            case COMBINED_IMAGE_SAMPLER -> { tex = mode == BindingMode.IDENTITY ? binding : nextTexture++;
                                             smp = mode == BindingMode.IDENTITY ? binding : nextSampler++; }
            case TEXEL_BUFFER -> tex = mode == BindingMode.IDENTITY ? binding : nextTexture++;
        }
        // reject buf >= 14 (reserved range) and smp >= 16 (Metal per-stage sampler limit)
        slots.add(new Slot(binding, u.name(), u.type(), buf, tex, smp));
    }
    return slots;
}

// per stage
int model = type == ShaderType.VERTEX ? 0 /*SpvExecutionModelVertex*/ : 4 /*SpvExecutionModelFragment*/;
IntBuffer words = shader.module().spv().asIntBuffer();               // already patched by the frontend
spvc_context_create(pp); ctx = pp.get(0);
spvc_context_parse_spirv(ctx, words, words.remaining(), pp); long ir = pp.get(0);
spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pp); long compiler = pp.get(0);
spvc_compiler_create_compiler_options(compiler, pp); long opts = pp.get(0);
spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_VERSION, 20400);
spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, type == ShaderType.VERTEX);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, true);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, false);
spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_FORCE_ZERO_INITIALIZED_VARIABLES, true);

SpvcMslResourceBinding2 b = SpvcMslResourceBinding2.calloc(stack);
for (Slot s : slots) {
    spvc_msl_resource_binding_init_2(b);
    b.stage(model).desc_set(0).binding(s.binding()).count(1)
     .msl_buffer(Math.max(s.mslBuffer(), 0)).msl_texture(Math.max(s.mslTexture(), 0)).msl_sampler(Math.max(s.mslSampler(), 0));
    spvc_compiler_msl_add_resource_binding_2(compiler, b);
}
spvc_msl_resource_binding_init_2(b);
b.stage(model).desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(SPVC_MSL_PUSH_CONSTANT_BINDING).count(1).msl_buffer(14);
spvc_compiler_msl_add_resource_binding_2(compiler, b);

spvc_compiler_set_entry_point(compiler, shader.entryPoint() /* "main" */, model);
spvc_compiler_install_compiler_options(compiler, opts);
spvc_compiler_compile(compiler, pp);
String msl = MemoryUtil.memUTF8(pp.get(0));
String entry = spvc_compiler_get_cleansed_entry_point_name(compiler, "main", model);   // "main0"
// after compile: spvc_compiler_msl_is_resource_used(compiler, model, 0, binding) per slot -> per-stage bind mask
spvc_context_destroy(ctx);   // frees everything, including the returned MSL string
```

Every spvc call returns `spvc_result`; on failure `spvc_context_get_last_error_string(ctx)` has the message (the real code checks every call). `spvc_compiler_msl_is_resource_used` only answers for bindings registered with `add_resource_binding_2`, so keep explicit bindings even if the numbering is identity.

## 5. What the MSL looks like

Samples are in `tools/shader-xlate/results/msl-samples/`. Note that `.metal` files are named after the first pipeline (alphabetically) that produced that exact text, e.g. the shared `core/screenquad.vsh` output is `minecraft_blur_0.vert.metal`.

### 5.1 Entry points

The SPIR-V entry point `main` is renamed by spvc to **`main0`** in both stages (`main` is reserved in Metal C++). Get it from `spvc_compiler_get_cleansed_entry_point_name`. Signatures are `vertex main0_out main0(...)`, `fragment main0_out main0(...)`, or `fragment void main0(...)` for fragment shaders with no outputs (3 OIT transmittance shaders whose `OIT_ADDITIVE` path always discards). Because both stages are called `main0` and both redefine the same UBO structs, compile each stage into its **own `MTLLibrary`**; do not concatenate stages into one source.

### 5.2 Vertex inputs and outputs

```metal
struct main0_in {                      // stage_in, one member per VS input, [[attribute(SPIR-V Location)]]
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];  // RGBA8_UNORM -> MTLVertexFormatUChar4Normalized
    float2 UV0      [[attribute(2)]];
    int2   UV2      [[attribute(3)]];  // RG16_SINT  -> MTLVertexFormatShort2 (Metal widens to int2)
};
struct main0_out {                     // varyings by location, linked to FS inputs by [[user(locnN)]]
    float sphericalVertexDistance [[user(locn0)]];
    float cylindricalVertexDistance [[user(locn1)]];
    float4 vertexColor [[user(locn2)]];
    float2 texCoord0 [[user(locn3)]];
    float chunkVisibility [[user(locn4)]];
    float4 gl_Position [[position]];
};
vertex main0_out main0(main0_in in [[stage_in]], constant ChunkSection& _80 [[buffer(0)]], constant Globals& _87 [[buffer(1)]],
                       constant Projection& _105 [[buffer(2)]], constant TerrainUniform& _111 [[buffer(3)]],
                       texture2d<float> Sampler2 [[texture(0)]], sampler Sampler2Smplr [[sampler(0)]])
{ ...; out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
  return out; }
```

* MTLVertexDescriptor: `attributes[AttribBinding.location()]` = {format, offset, bufferIndex = 30 - bufferSlot}; `layouts[30 - slot]` = {stride, stepFunction = stepRate > 0 ? PerInstance : PerVertex, stepRate = max(stepRate, 1)}. Pipelines without vertex input (43 vanilla ones: screen quads, `animate_sprite`, `clouds`, `lightmap`, post passes) get `vertexDescriptor = nil` and use `[[vertex_id]]`.
* Vertex formats used by vanilla and the Metal formats that the PSOs accepted: `RGB32_FLOAT`->`Float3`, `RG32_FLOAT`->`Float2`, `R32_FLOAT`->`Float`, `RGBA8_UNORM`->`UChar4Normalized`, `RGBA8_SNORM`->`Char4Normalized`, `RG16_SINT`->`Short2`, `RGB32_SINT`->`Int3`. The full table for all `GpuFormat`s is in `metalcheck.m` (`vertexFormat()`).
* Fragment inputs carry interpolation qualifiers (`[[user(locn2), flat]]` in one vanilla shader); vertex outputs do not. Metal matched VS outputs to FS inputs by `locnN` in all 220 PSOs; VS outputs that the FS ignores are fine.

### 5.3 Uniform buffers

```metal
struct Globals {                       // std140 offsets reproduced exactly; vec3 followed by a scalar -> packed_*3
    packed_int3 CameraBlockPos;  float GlintAlpha;
    packed_float3 CameraOffset;  float GameTime;
    float2 ScreenSize;  int MenuBlurRadius;  int UseRgss;
};
struct DynamicTransforms { float4x4 ModelViewMat; float4x4 TextureMat; float4 ColorModulator; float3 ModelOffset; };
... constant Globals& _87 [[buffer(1)]] ...      // instance names are spvc temporaries (_87); irrelevant
```

All 18 vanilla UBO blocks (`BitsConfig`, `BlitConfig`, `BlurConfig`, `ChunkSection`, `CloudInfo`, `ColorConfig`, `DynamicTransforms`, `Fog`, `Globals`, `InvertConfig`, `Lighting`, `LightmapInfo`, `Projection`, `RotScaleConfig`, `SamplerInfo`, `SpiderConfig`, `SpriteAnimationInfo`, `TerrainUniform`) only use scalars, `vec2/3/4`, `ivec2/3`, `mat4`; spvc keeps the SPIR-V `Offset`s, so the CPU-side bytes written by `Std140Builder` can be bound unchanged. No `mat3`, arrays or nested structs appear in vanilla UBOs.

Pitfall (MSL `sizeof` vs std140 size): `Std140SizeCalculator` does not round the block size up to 16, while MSL `sizeof(float3)` is 16. Example: `DynamicTransforms` is 156 bytes in std140 but `sizeof` 160 in MSL. Metal API validation checks that `buffer.length - offset >= sizeof(argument struct)` (known validation behavior, not reproduced in this experiment), so a UBO slice that ends exactly at the end of its `MTLBuffer` can trip validation (and the GPU may load the trailing 4 bytes). Round every uniform-capable `MTLBuffer` allocation up to a multiple of 16. Separately, report `DeviceLimits.minUniformOffsetAlignment = 256`: Apple's `setVertexBuffer:offset:atIndex:` documentation requires 256-byte offsets for `constant` address-space buffers on macOS (the experiment's fake device used 256; no draw with a smaller offset was tried).

### 5.4 Combined image samplers

`uniform sampler2D Sampler0;` becomes `texture2d<float> Sampler0 [[texture(t)]], sampler Sampler0Smplr [[sampler(s)]]`. `texture()` -> `.sample(smp, uv)`, `textureLod` -> `.sample(smp, uv, level(l))`, the RGSS path's explicit gradients -> `gradient2d(du, dv)`, `texelFetch(s, ivec2, lod)` -> `.read(uint2(coord), lod)` (no sampler used; also used in vertex stages, e.g. `item.vsh` reads `Sampler1`). `dFdx/dFdy` -> `dfdx/dfdy`. Integer samplers other than buffers do not occur in vanilla.

Depth textures: vanilla samples depth through plain `sampler2D` (e.g. `core/blit_depth.fsh`, `integrate_depth.fsh`, OIT `DepthBoundsSampler`), so the MSL parameter is `texture2d<float>`. Binding a `Depth32Float` `MTLTexture` to it works and passes API + GPU validation (`metalrender` depth-blit test: sampled 0.375 written through `[[depth(any)]]` read back as 0.375). No `depth2d` / comparison samplers are needed.

### 5.5 Texel buffers

```metal
vertex main0_out main0(constant CloudInfo& _145 [[buffer(0)]], constant Projection& _170 [[buffer(1)]],
                       constant DynamicTransforms& _176 [[buffer(2)]], texture_buffer<int> CloudFaces [[texture(0)]],
                       uint gl_VertexIndex [[vertex_id]])
...  int cellX = CloudFaces.read(uint(index)).x;
```

* Vanilla has one: `CloudFaces` (`isamplerBuffer`, `GpuFormat.R8_SINT`, `BindGroupLayouts.CLOUD_INFO`) in `core/clouds.vsh`. The synthetic test adds `samplerBuffer` (RGBA32F, VS) and `usamplerBuffer` (R32UI, FS).
* Metal side: `[mtlBuffer newTextureWithDescriptor:[MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:fmt width:bufferSize / texelSize resourceOptions:... usage:MTLTextureUsageShaderRead] offset:0 bytesPerRow:...]`, pixel format from `UniformDescription.gpuFormat()` (`R8_SINT` -> `MTLPixelFormatR8Sint`). `minimumTextureBufferAlignmentForPixelFormat` and `minimumLinearTextureAlignmentForPixelFormat` are 16 for R8Sint, R32Uint, RGBA32Float and RGBA8Unorm on M4 Pro; offset is always 0 (whole-buffer rule, 2.4). The Vulkan backend creates a `VkBufferView` per bind; on Metal cache the texture view per (buffer, format) and recreate it when the `GpuBuffer` is reallocated.

### 5.6 Push constants

```metal
struct PushConstants { float4x4 Transform; float4 Tint; int Mode; };   // GLSL: layout(push_constant) uniform PushConstants {...} pc;
vertex   main0_out main0(..., constant PushConstants& pc [[buffer(14)]], ...)
fragment main0_out main0(..., constant PushConstants& pc [[buffer(14)]], ...)
```

No vanilla 26.3 shader uses push constants (`withPushConstantSize` is never called in `net/minecraft`), so this path is exercised only by the synthetic `ingot_test:pipeline/push_constants_texel_buffers`, which passes at runtime. Pitfall: the SPIR-V declared size of that block is 84 bytes but MSL `sizeof(PushConstants)` is 96 (16-byte alignment of `float4x4`). Upload `round_up(pushConstantsSize, 16)` bytes, or simply always copy into a zeroed 128-byte array (the API maximum) and pass 128 to `setVertexBytes`/`setFragmentBytes`, for both stages that use the block. `RenderPassBackend.pushConstants(ByteBuffer)` is the entry point (`VulkanRenderPass` maps it to `vkCmdPushConstants(..., stageFlags = all, offset 0, value)`).

### 5.7 Fragment outputs and built-ins

* `layout(location=N) out vec4 x` -> `float4 x [[color(N)]]`; arrays are split: OIT `out vec4 coeff[2]` -> `coeff_0 [[color(0)]]`, `coeff_1 [[color(1)]]`.
* `gl_FragDepth` -> `float gl_FragDepth [[depth(any)]]` in the output struct (41 of 171 files with explicit depth on, 5 with it off).
* `gl_FragCoord` -> `float4 gl_FragCoord [[position]]`; `gl_FrontFacing` -> `bool gl_FrontFacing [[front_facing]]` (7 entity fragment variants); `gl_VertexIndex` -> `uint gl_VertexIndex [[vertex_id]]`; `gl_InstanceIndex` -> `uint gl_InstanceIndex [[instance_id]]`; `gl_PointSize` -> `float gl_PointSize [[point_size]]` in `main0_out`; `discard` -> `discard_fragment()`.
* Signed `%` becomes the helper `spvSMod`, local arrays become `spvUnsafeArray<T, N>`, `min/max/clamp` on floats use `fast::` variants (spvc default).

## 6. Coordinate conventions (verified at runtime)

Target: reproduce the vanilla Vulkan backend's memory image (section 2.6), i.e. "GL memory layout": memory row 0 is the bottom of the scene, `gl_FragCoord.y = row + 0.5`, texture `v = 0` is the first uploaded row, and the final present flips vertically.

Metal recipe:

1. `FLIP_VERTEX_Y` in every vertex shader (spvc negates `gl_Position.y` at every return).
2. Viewport `{originX 0, originY 0, width w, height h, znear 0, zfar 1}` (positive height, Metal's default). Scissor rects: pass the renderpearl values through unchanged, same as Vulkan (both use row indices from memory row 0).
3. `setFrontFacingWinding:MTLWindingClockwise`, `setCullMode: cull ? MTLCullModeBack : MTLCullModeNone` (same as Vulkan's `VK_FRONT_FACE_CLOCKWISE`).
4. Depth: the game builds 0..1 projections (`isZZeroToOne=true`) and uses reversed-Z compare `GREATER_THAN_OR_EQUAL` in `DepthStencilState.DEFAULT`; nothing to fix in shaders.
5. Present: flip vertically when copying the final color target into the `CAMetalLayer` drawable (a blit encoder cannot flip; use a full-screen draw that samples with `v' = 1 - v`, or render the last pass with the flip disabled). Speculative alternative, not tested: negative-height viewport instead of the shader flip.

`metalrender` draws a GL-convention counter-clockwise triangle covering the lower-left half of clip space into a 16x16 RGBA8 target and encodes `R = gl_FragCoord.x - 0.5`, `G = gl_FragCoord.y - 0.5`, `B = gl_FragCoord.z + (gl_FrontFacing ? 0.5 : 0)`, `A = texture(Sampler0, (0.25, 0.25)).g` with row 0 of a 2x2 texture uploaded as green. Result with the recipe (`results/metalrender-validation.txt`, API + GPU validation on):

```
frontFacingWinding=Clockwise, cullMode=Back (memory row 0 printed first; '#' = covered)
  row  0 ###############.
  row  1 ##############..
  ...
  row 14 #...............
  row 15 ................
  covered=120 row0=15 lastRow=0  mismatches: R(col)=0 G(row)=0 B(depth+facing)=0 A(tex v=0)=0
  => PASS: GL memory layout, gl_FragCoord == GL, depth 0..1, gl_FrontFacing true, texture v=0 == first row
frontFacingWinding=CounterClockwise, cullMode=Back   => PASS: culled
frontFacingWinding=CounterClockwise, cullMode=None   => PASS: with the wrong winding the same triangle reports gl_FrontFacing == false
```

Without `FLIP_VERTEX_Y` (`results/metalrender-noflip.txt`) the triangle lands in the last memory rows, it is culled with Clockwise winding, and the full-screen test triangle in the bindings test is culled too.

### 6.4 Base vertex / base instance

spvc maps SPIR-V `VertexIndex`/`InstanceIndex` (Vulkan semantics: include `firstVertex`/`vertexOffset` and `firstInstance`) directly to `[[vertex_id]]`/`[[instance_id]]`, which in Metal also include `baseVertex`/`vertexStart` and `baseInstance`. So `DeviceFeatures.shaderDrawParameters = true` is correct and no `[[base_instance]]` arithmetic is emitted. Not runtime-tested with a non-zero base instance.

## 7. `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`

Both vanilla backends set `HintsAndWorkarounds.isExplicitDepthRequired = Util.isAppleSiliconMac(rendererName)`. When set, `include/oit_sample.glsl` (`sampleColorForAccumulation`) and `include/oit_add_transmittance.glsl` add `gl_FragDepth = gl_FragCoord.z;`, so every OIT accumulate/transmittance fragment shader writes depth. Counts: 64 of 220 pipelines write `[[depth(any)]]` with the hint on, 6 with it off (the depth blits, `oit_composite`, `oit_depth_bounds_cull`, and the synthetic test). Both variants compile and link. Writing depth from the fragment shader turns off early depth testing and hidden-surface removal on Apple GPUs for those draws, so it costs GPU time in translucent-heavy scenes. Recommendation: report `true` initially (same as vanilla on the same GPU). Speculative follow-up, not tested here: mark `gl_Position` invariant (spvc `[[invariant]]`, e.g. by adding the `Invariant` decoration to the `Position` built-in before compiling) with `MTLCompileOptions.preserveInvariance = YES`, then measure whether the OIT depth equality holds with the hint off. `preserveInvariance=YES` alone compiled everything with no measurable compile-time change.

## 8. Results

### 8.1 Per shader source (MSL 2.4, compact slots, all options of 4.1)

"Define sets" counts distinct `ShaderDefines` among the pipelines using the file; "Distinct MSL" counts unique MSL texts for that stage after binding assignment. Pass = every MSL for that file compiled with `newLibraryWithSource` and every PSO using it was created.

| Shader source | Pipelines using it | Distinct define sets | Distinct MSL outputs | Result |
|---|---|---|---|---|
| `core/animate_sprite.vsh` | 2 | 1 | 1 | pass |
| `core/animate_sprite_blit.fsh` | 1 | 1 | 1 | pass |
| `core/animate_sprite_interpolate.fsh` | 1 | 1 | 1 | pass |
| `core/blit_depth.fsh` | 2 | 1 | 1 | pass |
| `core/blit_screen.fsh` | 3 | 1 | 1 | pass |
| `core/block.fsh` | 6 | 6 | 6 | pass |
| `core/block.vsh` | 6 | 6 | 2 | pass |
| `core/clouds.fsh` | 8 | 4 | 4 | pass |
| `core/clouds.vsh` | 8 | 4 | 1 | pass |
| `core/debug_point.vsh` | 4 | 4 | 1 | pass |
| `core/entity.fsh` | 37 | 27 | 23 | pass |
| `core/entity.vsh` | 37 | 27 | 12 | pass |
| `core/glint.fsh` | 1 | 1 | 1 | pass |
| `core/glint.vsh` | 1 | 1 | 1 | pass |
| `core/gui.fsh` | 3 | 1 | 1 | pass |
| `core/gui.vsh` | 3 | 1 | 1 | pass |
| `core/integrate_depth.fsh` | 1 | 1 | 1 | pass |
| `core/item.fsh` | 12 | 7 | 6 | pass |
| `core/item.vsh` | 12 | 7 | 4 | pass |
| `core/lightmap.fsh` | 1 | 1 | 1 | pass |
| `core/oit_composite.fsh` | 1 | 1 | 1 | pass |
| `core/oit_depth_bounds_cull.fsh` | 1 | 1 | 1 | pass |
| `core/panorama.fsh` | 1 | 1 | 1 | pass |
| `core/panorama.vsh` | 1 | 1 | 1 | pass |
| `core/particle.fsh` | 9 | 4 | 4 | pass |
| `core/particle.vsh` | 9 | 4 | 1 | pass |
| `core/position.fsh` (unreferenced in 26.3, synthetic pipeline) | 1 | 1 | 1 | pass |
| `core/position.vsh` (unreferenced in 26.3, synthetic pipeline) | 1 | 1 | 1 | pass |
| `core/position_color.fsh` | 17 | 4 | 4 | pass |
| `core/position_color.vsh` | 13 | 4 | 1 | pass |
| `core/position_tex.fsh` | 1 | 1 | 1 | pass |
| `core/position_tex.vsh` | 1 | 1 | 1 | pass |
| `core/position_tex_color.fsh` | 10 | 1 | 1 | pass |
| `core/position_tex_color.vsh` | 10 | 1 | 1 | pass |
| `core/rendertype_beacon_beam.fsh` | 5 | 4 | 4 | pass |
| `core/rendertype_beacon_beam.vsh` | 5 | 4 | 1 | pass |
| `core/rendertype_crumbling.fsh` | 4 | 4 | 4 | pass |
| `core/rendertype_crumbling.vsh` | 4 | 4 | 1 | pass |
| `core/rendertype_end_portal.fsh` | 2 | 2 | 2 | pass |
| `core/rendertype_end_portal.vsh` | 2 | 2 | 1 | pass |
| `core/rendertype_entity_shadow.fsh` | 4 | 4 | 4 | pass |
| `core/rendertype_entity_shadow.vsh` | 4 | 4 | 1 | pass |
| `core/rendertype_leash.fsh` | 1 | 1 | 1 | pass |
| `core/rendertype_leash.vsh` | 1 | 1 | 1 | pass |
| `core/rendertype_lightning.fsh` | 8 | 4 | 4 | pass |
| `core/rendertype_lightning.vsh` | 8 | 4 | 1 | pass |
| `core/rendertype_lines.fsh` | 8 | 4 | 4 | pass |
| `core/rendertype_lines.vsh` | 8 | 4 | 1 | pass |
| `core/rendertype_outline.fsh` | 2 | 1 | 1 | pass |
| `core/rendertype_outline.vsh` | 2 | 1 | 1 | pass |
| `core/rendertype_water_mask.fsh` | 2 | 1 | 1 | pass |
| `core/rendertype_water_mask.vsh` | 2 | 1 | 1 | pass |
| `core/screenquad.vsh` | 28 | 2 | 1 | pass |
| `core/sky.fsh` | 1 | 1 | 1 | pass |
| `core/sky.vsh` | 1 | 1 | 1 | pass |
| `core/stars.fsh` | 1 | 1 | 1 | pass |
| `core/stars.vsh` | 1 | 1 | 1 | pass |
| `core/terrain.fsh` | 14 | 12 | 12 | pass |
| `core/terrain.vsh` | 14 | 12 | 4 | pass |
| `core/text.fsh` | 20 | 12 | 10 | pass |
| `core/text.vsh` | 20 | 12 | 2 | pass |
| `core/world_border.fsh` | 4 | 4 | 4 | pass |
| `core/world_border.vsh` | 4 | 4 | 1 | pass |
| `post/bits.fsh` | 1 | 1 | 1 | pass |
| `post/blit.fsh` | 3 | 1 | 1 | pass |
| `post/box_blur.fsh` | 10 | 1 | 1 | pass |
| `post/color_convolve.fsh` | 1 | 1 | 1 | pass |
| `post/entity_outline_box_blur.fsh` | 2 | 1 | 1 | pass |
| `post/entity_sobel.fsh` | 1 | 1 | 1 | pass |
| `post/invert.fsh` | 1 | 1 | 1 | pass |
| `post/rotscale.vsh` | 5 | 1 | 1 | pass |
| `post/spiderclip.fsh` | 5 | 1 | 1 | pass |
| `ingot_test:core/early_return.vsh` | 1 | 1 | 1 | pass |
| `ingot_test:core/orientation.fsh` | 2 | 1 | 1 | pass |
| `ingot_test:core/orientation.vsh` | 1 | 1 | 1 | pass |
| `ingot_test:core/push_test.fsh` | 1 | 1 | 1 | pass |
| `ingot_test:core/push_test.vsh` | 1 | 1 | 1 | pass |

Per-pipeline detail (slot table, frontend and spvc time, file names) is in `results/translate.tsv`; the full manifest (defines, used bindings per stage, used vertex attributes, vertex layout, color formats and blend, depth, topology) is `results/pipelines.json`. Topologies covered: QUADS 158, TRIANGLES 43, LINES 8, TRIANGLE_FAN 6, POINTS 4, TRIANGLE_STRIP 1 (fans and quads need index-buffer emulation on Metal; not a shader concern). Color formats: RGBA8_UNORM, RGBA16_FLOAT, RGBA32_FLOAT; `BlendFunction.MAX` on RGBA32_FLOAT (OIT depth bounds) was accepted by the PSO on M4 Pro (`supports32BitFloatFiltering` = YES; blending of RGBA32Float on older GPU families not checked).

### 8.2 Option variants (`results/variants.txt`)

| Variant | Flags | Translated | Unique MSL | Lib fail | PSO ok | Runtime draws |
|---|---|---|---|---|---|---|
| msl24-compact (baseline) | | 220/220 | 171 | 0 | 220 | all pass |
| msl24-compact-mt | `--threads 12` | 220/220 | 171 (byte-identical) | 0 | 220 | all pass |
| msl30-compact | `--msl 30000` | 220/220 | 171 (byte-identical text) | 0 | 220 | all pass |
| msl24-identity | `--binding identity` | 220/220 | 175 | 0 | 220 | all pass |
| msl24-decoration | `--binding decoration` | 220/220 | 175 (identical to identity) | 0 | 220 | all pass |
| msl24-noexplicitdepth | `--no-explicit-depth` | 220/220 | 171 (36 files differ) | 0 | 220 | all pass |
| msl24-notbnative | `--no-texture-buffer-native` | 220/220 | 171 | 0 | 220 | draws only pass without validation; API validation rejects the texture type |
| msl24-nopad | `--no-pad-outputs` | 220/220 | 171 (identical) | 0 | 220 | all pass |
| msl24-noflip | `--no-flip-y` | 220/220 | 171 (only vertex files differ) | 0 | 220 | orientation and bindings tests FAIL (expected) |
| msl24-nozeroinit | `--no-zero-init` | 220/220 | 171 (47 files differ) | 0 | 220 | all pass |

### 8.3 Runtime binding test

`ingot_test:pipeline/push_constants_texel_buffers` drawn as a full-screen triangle with: vertex buffer at `buffer(30)` (POSITION_TEX_COLOR, stride 24), `Globals` via `setVertexBytes` at its compact index `buffer(0)`, push constants (`Transform = I`, `Tint = 1`, `Mode = 1`) via `setVertexBytes`/`setFragmentBytes` at `buffer(14)`, `Lut` RGBA32F texture buffer at `texture(0)` (VS), `Sampler0` white 2x2 at `texture(1)`/`sampler(0)`, `Ids` R32UI texture buffer at `texture(2)` (FS, `Ids[0] = 128`), depth `GreaterEqual` with write, shader writes `gl_FragDepth`. Expected every pixel `(1, .5, .25, 1) * 128/255` = (128, 64, 32, 255); got exactly that under API + GPU validation.

### 8.4 Failures encountered and fixes

| What | Fix |
|---|---|
| `xcrun metal` missing (Metal Toolchain not installed) | Use runtime compilation (`newLibraryWithSource`), which is what the mod will use anyway, plus PSO creation for linkage checks. |
| Repeated timing runs returned 2-8 ms totals: the macOS Metal shader caches (`$(getconf DARWIN_USER_CACHE_DIR)/com.apple.metal*`) served everything. Prepending a comment, or appending an unused global, did not reliably defeat them. | `--nonce` renames each entry point to `main0_<uuid>`; this misses both the front-end and back-end caches every time. |
| `--no-texture-buffer-native` output binds wrong texture type | Keep `MSL_TEXTURE_BUFFER_NATIVE = true` (or create 2D linear textures, not worth it). |
| `--no-flip-y` renders upside down with flipped winding | Keep `FLIP_VERTEX_Y = true` + `MTLWindingClockwise`. |
| No spvc or Metal compile error occurred for any vanilla shader in any variant. | |

## 9. Timing and startup cost

Measured on M4 Pro; `results/` has the raw JSON. "Serial" = one thread, "parallel" = `dispatch_apply` over all items on the global queue (14 cores). Cold = `--nonce` (no cache hit possible); warm = repeated run (system cache hit).

| Step | Per item | Total for vanilla |
|---|---|---|
| shaderc GLSL->SPIR-V (frontend, unchanged by Ingot) | median 0.47 ms, max 1.5 ms per unique stage (255 unique stage+define combos) | 130 ms serial |
| Frontend per pipeline (2x shaderc + reflection + validation) | median 1.35 ms; the first pipeline pays ~140 ms of native-library load and JIT warm-up | ~0.3 s + warm-up serial |
| spvc SPIR-V -> MSL (new work for Ingot) | median 0.16 ms, mean 0.24 ms, max 4.7 ms (first call) per stage | 106-120 ms serial for 440 stages |
| Frontend + spvc, whole pipeline set | | 0.62-0.70 s serial; 0.23-0.36 s with 12 worker threads |
| `newLibraryWithSource` cold, MSL 2.4 | median 18-20 ms, p90 26-28 ms, max 31-39 ms per file; very first compile in a fresh process/boot up to ~260 ms | 3.2-3.6 s serial, 0.35-0.40 s parallel (171 files) |
| `newLibraryWithSource` cold, MSL 3.0 | ~+25% | 4.0 s serial |
| `newRenderPipelineStateWithDescriptor` cold | median 4.5-5.3 ms, p90 13-16 ms, max 19-23 ms | 1.2-1.6 s serial, 0.16-0.20 s parallel (220 PSOs) |
| both Metal steps, warm (system cache hit) | < 1 ms each | ~8 ms + ~9 ms |

Slowest MSL files are the OIT accumulate terrain fragment shaders and end portal/gateway (~30-33 ms cold each).

Estimate for Ingot on this machine: first launch after install (or after a macOS/driver update that invalidates the Metal cache) adds roughly 0.4 s (libraries) + 0.2 s (PSOs, plus ~25% more for the no-depth variants) of wall time if Metal work runs in parallel inside `compilePipeline` on the reload workers, or about 5 s if it is serialized. Later launches add ~20 ms of Metal work plus ~0.1 s of spvc. The UI preload (`GameRenderer.preloadUiShader`: `GUI`, `GUI_TEXTURED`) is compiled synchronously before the loading screen, so its cold cost (~2 x 50 ms) lands directly on startup. A pipeline compiled lazily on the render thread (`PipelineCache.get`, e.g. the first time a post effect is enabled) costs ~1.5 ms frontend + ~0.5 ms spvc + 2 x ~20 ms library + ~6 ms PSO, about 50 ms cold and ~3 ms warm. Machines with fewer cores (M1/M2 base, Intel) will be slower; scaling was not measured (speculative: 2-3x the parallel numbers on a 4P+4E part).

Options to cut cold cost further (not tested): persist MSL by SPIR-V hash to skip spvc (saves ~0.1 s); `MTLBinaryArchive` to keep PSOs independent of the evictable system cache; `newLibraryWithSource:options:completionHandler:` and `newRenderPipelineStateWithDescriptor:completionHandler:` to overlap with Java work.

## 10. Pitfall checklist for the backend

1. Re-parse `SpvModule.spv()` (patched words) yourself; never use set/binding from the original GLSL.
2. Binding `i` is the index into `CreateInfo.uniforms()` and the `index` in `RenderPassBackend.setUniform(index, value)`. Do not assume an order by type or by stage.
3. A slot may be declared but unused in both stages; use `spvc_compiler_msl_is_resource_used` to build per-stage bind masks.
4. Cache libraries by MSL text hash; the same shader+defines yields different MSL in different pipelines.
5. Entry point name is `main0` in both stages; one `MTLLibrary` per stage source.
6. `FLIP_VERTEX_Y` + positive viewport + `MTLWindingClockwise` + flip at present. Scissor values pass through.
7. `isZZeroToOne = true`; never enable `FIXUP_DEPTH_CONVENTION`.
8. Vertex buffer slot `s` -> `buffer(30 - s)`; UBOs in 0..13; push constants at 14 for both stages; push-constant uploads padded to 16 (or always 128) bytes.
9. Round UBO buffer allocations to 16 bytes so `sizeof(MSL struct)` never exceeds the bound range.
10. Texel buffers: `MSL_TEXTURE_BUFFER_NATIVE`, `MTLTextureTypeTextureBuffer` views with `UniformDescription.gpuFormat()`, offset 0, alignment 16; cache the view.
11. Two PSOs per pipeline without depth state (with and without `Depth32Float`), as Vulkan does.
12. `GlslCompiler` reads `RenderSystem.getDevice()` hints at compile time: set `isExplicitDepthRequired` deliberately (64 pipelines write depth when true).
13. `compilePipeline(CreateInfo)` runs concurrently on worker threads; do spvc + Metal compile there (one spvc context per call), and finish before `finishCompile()` returns, because the frontend closes the `SpvModule`s right after.
14. `PipelineCache.get` blocks the render thread while it compiles pipelines not in `RenderPipelines` (post effects, mods, the startup UI preload): expect ~50 ms cold hitches per pipeline.
15. MSL 2.4 compiles faster than 3.0 for identical text.

## 11. Not verified / open

* Rendering correctness of real vanilla content (only synthetic draws and the vanilla `blit_depth` pipeline were executed).
* Whether OIT depth equality holds on Metal without the explicit-depth hint, and whether `[[invariant]]` + `preserveInvariance` can replace it (section 7).
* Non-zero `baseInstance`/`baseVertex` draws (section 6.4); multi-draw/indirect paths.
* RGBA32Float blending on GPU families older than the M4 (Apple9).
* `MTLMathModeSafe` vs the default fast math: both compile in the same time; no visual comparison was done.
* Offline `metal -c` with the Metal Toolchain component (not installed here).
