import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Runs every vanilla Minecraft 26.3 render pipeline through the REAL renderpearl frontend
 * (com.mojang.renderpearl.frontend.shaders.PipelineBuilder: shaderc GLSL->SPIR-V, reflection, binding rewrite),
 * captures the BackendRenderPipeline.CreateInfo a backend would receive, and translates each stage to MSL with
 * MslTranslator. Writes .metal files + a pipelines.json manifest for metalcheck (runtime Metal compile + PSO creation).
 *
 * Flags: --out DIR  --msl 20400|30000  --binding compact|identity|decoration  --no-flip-y  --no-explicit-depth
 *        --no-texture-buffer-native  --no-pad-outputs  --[no-]zero-init  --only REGEX  --test-shaders DIR  --threads N
 */
public final class ShaderXlate {
    // ---- options ------------------------------------------------------------------------------------------
    static Path out = Path.of("out/default");
    static Path testShaderDir = Path.of("test-shaders");
    static boolean explicitDepth = true;   // what both vanilla backends report on Apple silicon (Util.isAppleSiliconMac)
    static boolean drawParameters = true;  // Metal [[instance_id]] / [[vertex_id]] include base instance / base vertex
    static Pattern only = null;
    static MslTranslator.Options mslOptions = MslTranslator.Options.defaults();
    static long wallNanos;

    // ---- results ------------------------------------------------------------------------------------------
    record PipelineRecord(String name, String source, long frontendNanos, BackendRenderPipeline.CreateInfo createInfo,
                          MslTranslator.Result result, String error) {
    }

    static final Map<String, PipelineRecord> captured = java.util.Collections.synchronizedMap(new TreeMap<>());
    static final Map<String, String> sourceByName = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, Long> startByName = new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<String, RenderPipeline> pipelineByName = new java.util.concurrent.ConcurrentHashMap<>();
    static int threads = 1;

    public static void main(String[] args) throws Exception {
        parseArgs(args);
        Files.createDirectories(out.resolve("msl"));
        Files.createDirectories(out.resolve("spv"));

        DeviceInfo deviceInfo = new DeviceInfo(
            "Apple (shader-xlate)", "Apple", "shader-xlate", true /* isZZeroToOne */, "Metal", 1.0F,
            new DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 0, 8, 0),
            new DeviceFeatures(true, drawParameters, false, false, false, false, true, true),
            Set.of(),
            new HintsAndWorkarounds(false, false, explicitDepth, false),
            DeviceType.INTEGRATED);
        installFakeRenderSystemDevice(deviceInfo);

        GpuDeviceBackend backend = (GpuDeviceBackend) Proxy.newProxyInstance(
            ShaderXlate.class.getClassLoader(), new Class<?>[]{GpuDeviceBackend.class}, (proxy, method, a) -> switch (method.getName()) {
                case "getDeviceInfo" -> deviceInfo;
                case "compilePipeline" -> backendCompile((BackendRenderPipeline.CreateInfo) a[0]);
                case "toString" -> "FakeMetalBackend";
                case "hashCode" -> 0;
                case "equals" -> proxy == a[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });

        Path clientJar = Path.of(RenderPipelines.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (ZipFile jar = new ZipFile(clientJar.toFile()); JarShaderSource shaderSource = new JarShaderSource(jar, testShaderDir);
             PipelineBuilder builder = new PipelineBuilder(backend)) {
            List<Map.Entry<String, RenderPipeline>> pipelines = new ArrayList<>();
            RenderPipelines.requiredPipelines().forEach(p -> pipelines.add(Map.entry("required", p)));
            RenderPipelines.optionalPipelines().forEach(p -> pipelines.add(Map.entry("optional", p)));
            postEffectPipelines(jar).forEach(p -> pipelines.add(Map.entry("post_effect", p)));
            testPipelines().forEach(p -> pipelines.add(Map.entry("ingot_test", p)));
            pipelines.sort(Map.Entry.comparingByValue((x, y) -> x.getLocation().toString().compareTo(y.getLocation().toString())));

            List<RenderPipeline> selected = new ArrayList<>();
            for (Map.Entry<String, RenderPipeline> e : pipelines) {
                String name = e.getValue().getLocation().toString();
                if (only != null && !only.matcher(name).find()) continue;
                sourceByName.put(name, e.getKey());
                pipelineByName.put(name, e.getValue());
                selected.add(e.getValue());
            }
            // threads == 1: inline, in order (clean per-pipeline timings). threads > 1: a worker pool, like the
            // game's reload (ShaderManager.compilePipelines -> device.compilePipeline(p, src, taskExecutor)).
            java.util.concurrent.ExecutorService pool = threads > 1 ? java.util.concurrent.Executors.newFixedThreadPool(threads) : null;
            java.util.concurrent.Executor executor = pool != null ? pool : Runnable::run;
            long wall0 = System.nanoTime();
            List<java.util.concurrent.CompletableFuture<CompiledRenderPipeline.Pending>> futures = new ArrayList<>();
            for (RenderPipeline pipeline : selected) {
                startByName.put(pipeline.getLocation().toString(), System.nanoTime());
                futures.add(builder.compilePipeline(pipeline, shaderSource, executor));
            }
            for (var f : futures) {
                f.join().finishCompile(); // frees the SpvModules, like the real device does
            }
            wallNanos = System.nanoTime() - wall0;
            if (pool != null) pool.shutdown();
            for (RenderPipeline pipeline : selected) {
                String name = pipeline.getLocation().toString();
                captured.putIfAbsent(name, new PipelineRecord(name, sourceByName.get(name), 0L, null, null,
                    "frontend rejected pipeline (see log above)"));
            }
            Map<String, Long> shadercTimes = timeShadercPerStage(pipelines, shaderSource);
            writeOutputs(shadercTimes);
        }
    }

    // ---- the fake backend entry point: this is where the Metal backend's compilePipeline would run -----------
    static BackendRenderPipeline.Pending backendCompile(BackendRenderPipeline.CreateInfo ci) {
        long frontendNanos = System.nanoTime() - startByName.getOrDefault(ci.name(), System.nanoTime());
        MslTranslator.Result result = null;
        String error = null;
        try {
            for (BackendRenderPipeline.CreateInfo.Shader s : ci.shaders()) {
                dumpSpv(ci.name(), s);
            }
            result = MslTranslator.translate(ci, mslOptions);
        } catch (Throwable t) {
            error = t.toString();
        }
        captured.put(ci.name(), new PipelineRecord(ci.name(), sourceByName.get(ci.name()), frontendNanos, ci, result, error));
        return () -> null;
    }

    static void dumpSpv(String pipeline, BackendRenderPipeline.CreateInfo.Shader s) throws IOException {
        var bytes = s.module().spv().duplicate();
        Path p = out.resolve("spv").resolve(sanitize(pipeline) + "." + (s.module().type() == ShaderType.VERTEX ? "vert" : "frag") + ".spv");
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.write(bytes);
        }
    }

    // ---- shaderc timing, per unique (shader, defines) like a shader cache would see -----------------------
    static Map<String, Long> timeShadercPerStage(List<Map.Entry<String, RenderPipeline>> pipelines, ShaderSource source) throws Exception {
        Map<String, Long> times = new TreeMap<>();
        try (GlslCompiler compiler = new GlslCompiler(true, drawParameters)) {
            for (var e : pipelines) {
                RenderPipeline p = e.getValue();
                if (only != null && !only.matcher(p.getLocation().toString()).find()) continue;
                for (var s : p.getShaders().entrySet()) {
                    String key = s.getKey() + " " + s.getValue() + " " + p.getShaderDefines();
                    if (times.containsKey(key)) continue;
                    String src = source.getShader(s.getValue(), s.getKey());
                    if (src == null) continue;
                    // warm-up once for the first entry so JIT/native init isn't attributed to one shader
                    if (times.isEmpty()) compiler.compileToSpv(s.getValue().toString(), src, s.getKey(), p.getShaderDefines(), source).close();
                    long t0 = System.nanoTime();
                    try (SpvModule m = compiler.compileToSpv(s.getValue().toString(), src, s.getKey(), p.getShaderDefines(), source)) {
                        times.put(key, System.nanoTime() - t0);
                    } catch (Exception ex) {
                        times.put(key, -1L);
                    }
                }
            }
        }
        return times;
    }

    // ---- outputs ------------------------------------------------------------------------------------------
    static void writeOutputs(Map<String, Long> shadercTimes) throws Exception {
        Map<String, String> fileByHash = new HashMap<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        StringBuilder tsv = new StringBuilder("pipeline\tsource\tstatus\tfrontend_ms\tspvc_vs_ms\tspvc_fs_ms\tvs_file\tfs_file\tslots\terror\n");
        int ok = 0, failed = 0;
        long spvcTotal = 0;
        for (PipelineRecord r : captured.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name());
            m.put("source", r.source());
            RenderPipeline rp = pipelineByName.get(r.name());
            if (rp != null) {
                m.put("vertexShader", String.valueOf(rp.getShaders().get(ShaderType.VERTEX)));
                m.put("fragmentShader", String.valueOf(rp.getShaders().get(ShaderType.FRAGMENT)));
                Map<String, Object> defs = new TreeMap<>(rp.getShaderDefines().values());
                rp.getShaderDefines().flags().forEach(f -> defs.put(f, ""));
                m.put("defines", defs);
            }
            String vsFile = "", fsFile = "";
            long vsNs = 0, fsNs = 0;
            if (r.result() != null) {
                ok++;
                for (MslTranslator.StageResult s : r.result().stages()) {
                    String hash = sha1(s.msl()).substring(0, 12);
                    String stage = s.type() == ShaderType.VERTEX ? "vert" : "frag";
                    String file = fileByHash.computeIfAbsent(hash + stage, k -> {
                        String fn = "msl/" + sanitize(r.name()) + "." + stage + ".metal";
                        try {
                            Files.writeString(out.resolve(fn), s.msl());
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                        return fn;
                    });
                    Map<String, Object> st = new LinkedHashMap<>();
                    st.put("file", file);
                    st.put("entry", s.entryPoint());
                    st.put("usedBindings", s.usedBindings());
                    if (s.type() == ShaderType.VERTEX) st.put("usedVertexAttributes", s.usedVertexAttributes());
                    m.put(stage, st);
                    spvcTotal += s.nanos();
                    if (s.type() == ShaderType.VERTEX) { vsFile = file; vsNs = s.nanos(); } else { fsFile = file; fsNs = s.nanos(); }
                }
                List<Map<String, Object>> slots = new ArrayList<>();
                for (MslTranslator.Slot s : r.result().slots()) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("binding", s.binding());
                    sm.put("name", s.name());
                    sm.put("type", s.type().name());
                    sm.put("buffer", s.mslBuffer());
                    sm.put("texture", s.mslTexture());
                    sm.put("sampler", s.mslSampler());
                    slots.add(sm);
                }
                m.put("slots", slots);
                BackendRenderPipeline.CreateInfo ci = r.createInfo();
                List<Map<String, Object>> vbs = new ArrayList<>();
                for (var vb : ci.vertexBuffers()) {
                    vbs.add(Map.of("slot", vb.bufferSlot(), "stride", vb.stride(), "stepRate", vb.stepRate(),
                        "mtlBufferIndex", MslTranslator.vertexBufferIndex(vb.bufferSlot())));
                }
                m.put("vertexBuffers", vbs);
                List<Map<String, Object>> attrs = new ArrayList<>();
                for (var ab : ci.attribBindings()) {
                    attrs.add(Map.of("slot", ab.bufferSlot(), "location", ab.location(), "offset", ab.offset(), "format", ab.format().name()));
                }
                m.put("attributes", attrs);
                List<Map<String, Object>> colors = new ArrayList<>();
                for (ColorTargetState c : ci.colorTargetStates()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    if (c == null) {
                        cm.put("format", "NONE");
                    } else {
                        cm.put("format", c.format().name());
                        cm.put("writeMask", c.writeMask());
                        c.blendFunction().ifPresent(b -> cm.put("blend", blendMap(b)));
                    }
                    colors.add(cm);
                }
                m.put("colors", colors);
                DepthStencilState ds = ci.depthStencilState();
                m.put("depth", ds != null);
                if (ds != null) {
                    m.put("depthCompare", ds.depthTest().name());
                    m.put("depthWrite", ds.writeDepth());
                }
                m.put("topology", ci.primitiveTopology().name());
                m.put("pushConstantsSize", ci.pushConstantsSize());
                tsv.append(String.join("\t", r.name(), r.source(), "ok", ms(r.frontendNanos()), ms(vsNs), ms(fsNs), vsFile, fsFile,
                    slotsSummary(r.result().slots()), "")).append('\n');
            } else {
                failed++;
                m.put("error", r.error());
                tsv.append(String.join("\t", r.name(), r.source(), "FAIL", ms(r.frontendNanos()), "", "", "", "", "",
                    String.valueOf(r.error()).replace('\t', ' ').replace('\n', ' '))).append('\n');
            }
            manifest.add(m);
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(out.resolve("pipelines.json"), gson.toJson(manifest));
        Files.writeString(out.resolve("translate.tsv"), tsv.toString());
        StringBuilder sh = new StringBuilder("stage_key\tshaderc_ms\n");
        long shadercTotal = 0;
        for (var e : shadercTimes.entrySet()) {
            sh.append(e.getKey()).append('\t').append(ms(e.getValue())).append('\n');
            shadercTotal += Math.max(0, e.getValue());
        }
        Files.writeString(out.resolve("shaderc-times.tsv"), sh.toString());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("options", mslOptions.toString());
        summary.put("explicitDepth", explicitDepth);
        summary.put("threads", threads);
        summary.put("frontendPlusSpvcWallMs", ms(wallNanos));
        summary.put("pipelines", captured.size());
        summary.put("translated", ok);
        summary.put("failed", failed);
        summary.put("uniqueMslFiles", fileByHash.size());
        summary.put("uniqueShadercStages", shadercTimes.size());
        summary.put("shadercTotalMs", ms(shadercTotal));
        summary.put("spvcTotalMs", ms(spvcTotal));
        Files.writeString(out.resolve("summary.json"), gson.toJson(summary));
        System.out.println(gson.toJson(summary));
    }

    static Map<String, String> blendMap(BlendFunction b) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("srcColor", b.color().sourceFactor().name());
        m.put("dstColor", b.color().destFactor().name());
        m.put("colorOp", b.color().op().name());
        m.put("srcAlpha", b.alpha().sourceFactor().name());
        m.put("dstAlpha", b.alpha().destFactor().name());
        m.put("alphaOp", b.alpha().op().name());
        return m;
    }

    static String slotsSummary(List<MslTranslator.Slot> slots) {
        StringBuilder sb = new StringBuilder();
        for (MslTranslator.Slot s : slots) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(s.binding()).append(':').append(s.name()).append("->");
            switch (s.type()) {
                case UNIFORM_BUFFER -> sb.append("b").append(s.mslBuffer());
                case COMBINED_IMAGE_SAMPLER -> sb.append("t").append(s.mslTexture()).append("/s").append(s.mslSampler());
                case TEXEL_BUFFER -> sb.append("tb").append(s.mslTexture());
            }
        }
        return sb.toString();
    }

    // ---- pipeline sources -----------------------------------------------------------------------------------
    /** Mirrors net.minecraft.client.renderer.PostChain.createPass for every vanilla post_effect/*.json. */
    static List<RenderPipeline> postEffectPipelines(ZipFile jar) throws IOException {
        List<RenderPipeline> list = new ArrayList<>();
        for (ZipEntry e : jar.stream().toList()) {
            String n = e.getName();
            if (!n.startsWith("assets/minecraft/post_effect/") || !n.endsWith(".json")) continue;
            String effect = n.substring("assets/minecraft/post_effect/".length(), n.length() - 5);
            String json = new String(jar.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
            for (int i = 0; i < config.passes().size(); i++) {
                PostChainConfig.Pass pass = config.passes().get(i);
                RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withFragmentShader(pass.fragmentShaderId())
                    .withVertexShader(pass.vertexShaderId())
                    .withLocation(Identifier.withDefaultNamespace(effect).withSuffix("/" + i));
                BindGroupLayout.Builder layout = BindGroupLayout.builder();
                for (PostChainConfig.Input input : pass.inputs()) {
                    layout.withUniform(input.samplerName() + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
                }
                layout.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
                for (String uniformGroupName : pass.uniforms().keySet()) {
                    layout.withUniform(uniformGroupName, UniformType.UNIFORM_BUFFER);
                }
                b.withBindGroupLayout(layout.build());
                b.withColorTargetState(ColorTargetState.DEFAULT);
                list.add(b.build());
            }
        }
        return list;
    }

    /** Synthetic pipelines exercising features vanilla does not use: push constants, float/uint texel buffers in both stages. */
    static List<RenderPipeline> testPipelines() {
        return List.of(
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("ingot_test", "pipeline/push_constants_texel_buffers"))
                .withVertexShader(Identifier.fromNamespaceAndPath("ingot_test", "core/push_test"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("ingot_test", "core/push_test"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withPushConstantSize(96)
                .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("Lut", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                    .withUniform("Ids", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                    .withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .build(),
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("ingot_test", "pipeline/orientation"))
                .withVertexShader(Identifier.fromNamespaceAndPath("ingot_test", "core/orientation"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("ingot_test", "core/orientation"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("ingot_test", "pipeline/early_return"))
                .withVertexShader(Identifier.fromNamespaceAndPath("ingot_test", "core/early_return"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("ingot_test", "core/orientation"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build(),
            // vanilla core/position.{vsh,fsh} ship in the jar but no 26.3 RenderPipeline references them
            RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("ingot_test", "pipeline/vanilla_core_position"))
                .withVertexShader("core/position")
                .withFragmentShader("core/position")
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.FOG)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .build());
    }

    // ---- shader source: vanilla shaders from the client jar, test shaders from disk -------------------------
    static final class JarShaderSource implements ShaderSource {
        private final ZipFile jar;
        private final Path testDir;
        private final Map<Identifier, CachedIncludeSource> includes = new HashMap<>();

        JarShaderSource(ZipFile jar, Path testDir) {
            this.jar = jar;
            this.testDir = testDir;
        }

        private String read(String namespace, String path) {
            try {
                if (namespace.equals("ingot_test")) {
                    Path p = testDir.resolve(path);
                    return Files.exists(p) ? Files.readString(p) : null;
                }
                ZipEntry e = jar.getEntry("assets/" + namespace + "/" + path);
                if (e == null) return null;
                try (InputStream in = jar.getInputStream(e)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public String getShader(Identifier id, ShaderType type) {
            // ShaderType.idConverter(): "shaders/<path><.vsh|.fsh>"
            String ext = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
            return read(id.getNamespace(), "shaders/" + id.getPath() + ext);
        }

        @Override
        public synchronized CachedIncludeSource getInclude(Identifier id) {
            // ShaderManager: shaders/include/<name>.glsl is registered as <ns>:<name>.glsl
            return includes.computeIfAbsent(id, k -> {
                String src = read(k.getNamespace(), "shaders/include/" + k.getPath());
                return src == null ? null : CachedIncludeSource.create(k, src);
            });
        }

        @Override
        public void close() {
            includes.values().forEach(CachedIncludeSource::close);
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------------------
    /** GlslCompiler reads RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds(); give it a stub device. */
    static void installFakeRenderSystemDevice(DeviceInfo info) throws Exception {
        GpuDevice device = (GpuDevice) Proxy.newProxyInstance(ShaderXlate.class.getClassLoader(), new Class<?>[]{GpuDevice.class},
            (proxy, method, a) -> switch (method.getName()) {
                case "getDeviceInfo" -> info;
                case "toString" -> "FakeGpuDevice";
                case "hashCode" -> 0;
                case "equals" -> proxy == a[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Field f = RenderSystem.class.getDeclaredField("DEVICE");
        f.setAccessible(true);
        f.set(null, device);
    }

    static void parseArgs(String[] args) {
        MslTranslator.Options o = MslTranslator.Options.defaults();
        int msl = o.mslVersion();
        boolean flip = o.flipVertexY(), tbn = o.textureBufferNative(), pad = o.padFragmentOutputs(), zero = o.forceZeroInit();
        MslTranslator.BindingMode mode = o.bindingMode();
        boolean deco = o.useDecorationBinding();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(args[++i]);
                case "--msl" -> msl = Integer.parseInt(args[++i]);
                case "--binding" -> {
                    String v = args[++i];
                    deco = v.equals("decoration");
                    mode = v.equals("compact") ? MslTranslator.BindingMode.COMPACT : MslTranslator.BindingMode.IDENTITY;
                }
                case "--no-flip-y" -> flip = false;
                case "--no-explicit-depth" -> explicitDepth = false;
                case "--no-texture-buffer-native" -> tbn = false;
                case "--no-pad-outputs" -> pad = false;
                case "--zero-init" -> zero = true;
                case "--no-zero-init" -> zero = false;
                case "--only" -> only = Pattern.compile(args[++i]);
                case "--test-shaders" -> testShaderDir = Path.of(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown arg " + args[i]);
            }
        }
        mslOptions = new MslTranslator.Options(msl, flip, mode, deco, tbn, pad, o.pointSizeBuiltin(), zero);
    }

    static String sanitize(String s) {
        return s.replace(':', '_').replace('/', '_');
    }

    static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1e6);
    }

    static String sha1(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
