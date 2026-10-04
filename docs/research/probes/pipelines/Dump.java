import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class Dump {
    static Path OUT;
    static boolean FLIP_Y;
    static int MSL_VERSION;
    static Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // captured per compile
    static JsonObject currentCreateInfo;
    static String currentKey;

    public static void main(String[] args) throws Throwable {
        OUT = Path.of(args[0]);
        boolean drawParams = Boolean.parseBoolean(args[1]);
        FLIP_Y = Boolean.parseBoolean(args[2]);
        MSL_VERSION = Integer.parseInt(args[3]);
        String clientJar = args[4];
        Files.createDirectories(OUT.resolve("msl"));

        DeviceInfo info = new DeviceInfo("Apple M (fake)", "Apple", "fake", true, "Metal", 1.0f,
            new DeviceLimits(16, 256, 16384, Long.MAX_VALUE, 0, 8, Integer.MAX_VALUE),
            new DeviceFeatures(true, drawParams, false, false, true, true, true, true),
            Set.of(), new HintsAndWorkarounds(false, false, true, false), DeviceType.INTEGRATED);

        GpuDevice device = (GpuDevice) Proxy.newProxyInstance(Dump.class.getClassLoader(), new Class[]{GpuDevice.class}, (p, m, a) -> {
            if (m.getName().equals("getDeviceInfo")) return info;
            if (m.getName().equals("toString")) return "FakeDevice";
            throw new UnsupportedOperationException(m.getName());
        });
        Field f = RenderSystem.class.getDeclaredField("DEVICE");
        f.setAccessible(true);
        f.set(null, device);

        GpuDeviceBackend backend = (GpuDeviceBackend) Proxy.newProxyInstance(Dump.class.getClassLoader(), new Class[]{GpuDeviceBackend.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getDeviceInfo": return info;
                case "toString": return "FakeBackend";
                case "compilePipeline": {
                    BackendRenderPipeline.CreateInfo ci = (BackendRenderPipeline.CreateInfo) a[0];
                    currentCreateInfo = describeCreateInfo(ci);
                    BackendRenderPipeline brp = (BackendRenderPipeline) Proxy.newProxyInstance(Dump.class.getClassLoader(), new Class[]{BackendRenderPipeline.class}, (p2, m2, a2) -> {
                        if (m2.getName().equals("isClosed")) return false;
                        return null;
                    });
                    return (BackendRenderPipeline.Pending) () -> brp;
                }
            }
            throw new UnsupportedOperationException(m.getName());
        });

        ZipFile zip = new ZipFile(clientJar);
        Map<Identifier, ShaderSource.CachedIncludeSource> includeCache = new HashMap<>();
        ShaderSource source = new ShaderSource() {
            public String getShader(Identifier id, ShaderType type) {
                String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
                return read(zip, path);
            }
            public CachedIncludeSource getInclude(Identifier id) {
                return includeCache.computeIfAbsent(id, i -> {
                    String s = read(zip, "assets/" + i.getNamespace() + "/shaders/include/" + i.getPath());
                    return s == null ? null : CachedIncludeSource.create(i, s);
                });
            }
            public void close() {}
        };

        // collect pipelines
        LinkedHashMap<RenderPipeline, JsonObject> pipelines = new LinkedHashMap<>();
        Set<RenderPipeline> required = Collections.newSetFromMap(new IdentityHashMap<>());
        required.addAll(RenderPipelines.requiredPipelines());
        Set<RenderPipeline> optional = Collections.newSetFromMap(new IdentityHashMap<>());
        optional.addAll(RenderPipelines.optionalPipelines());
        Map<RenderPipeline, List<String>> fieldNames = new IdentityHashMap<>();
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object v = field.get(null);
            if (v instanceof RenderPipeline rp) fieldNames.computeIfAbsent(rp, k -> new ArrayList<>()).add(field.getName());
            if (v instanceof OitPipelineSet set) {
                fieldNames.computeIfAbsent(set.depthBoundsPipeline(), k -> new ArrayList<>()).add(field.getName() + ".depthBounds");
                fieldNames.computeIfAbsent(set.transmittancePipeline(), k -> new ArrayList<>()).add(field.getName() + ".transmittance");
                fieldNames.computeIfAbsent(set.accumulatePipeline(), k -> new ArrayList<>()).add(field.getName() + ".accumulate");
            }
        }
        List<RenderPipeline> all = new ArrayList<>();
        Set<RenderPipeline> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RenderPipeline rp : fieldNames.keySet()) if (seen.add(rp)) all.add(rp);
        for (RenderPipeline rp : required) if (seen.add(rp)) all.add(rp);
        for (RenderPipeline rp : optional) if (seen.add(rp)) all.add(rp);
        // post chains
        Map<RenderPipeline, String> postNames = new IdentityHashMap<>();
        Enumeration<? extends ZipEntry> en = zip.entries();
        List<String> postFiles = new ArrayList<>();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.startsWith("assets/minecraft/post_effect/") && n.endsWith(".json")) postFiles.add(n);
        }
        Collections.sort(postFiles);
        for (String pf : postFiles) {
            JsonObject root = JsonParser.parseString(read(zip, pf)).getAsJsonObject();
            String effect = pf.substring("assets/minecraft/post_effect/".length(), pf.length() - 5);
            JsonArray passes = root.getAsJsonArray("passes");
            for (int i = 0; i < passes.size(); i++) {
                JsonObject pass = passes.get(i).getAsJsonObject();
                RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                    .withFragmentShader(Identifier.parse(pass.get("fragment_shader").getAsString()))
                    .withVertexShader(Identifier.parse(pass.get("vertex_shader").getAsString()))
                    .withLocation(Identifier.withDefaultNamespace(effect + "/" + i));
                BindGroupLayout.Builder lb = BindGroupLayout.builder();
                if (pass.has("inputs")) for (JsonElement in : pass.getAsJsonArray("inputs"))
                    lb.withUniform(in.getAsJsonObject().get("sampler_name").getAsString() + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
                lb.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
                if (pass.has("uniforms")) for (String k : pass.getAsJsonObject("uniforms").keySet()) lb.withUniform(k, UniformType.UNIFORM_BUFFER);
                b.withBindGroupLayout(lb.build());
                b.withColorTargetState(ColorTargetState.DEFAULT);
                RenderPipeline rp = b.build();
                postNames.put(rp, "post_effect/" + effect + ".json pass " + i);
                all.add(rp);
            }
        }

        PipelineBuilder pb = new PipelineBuilder(backend);
        JsonArray outArr = new JsonArray();
        int idx = 0;
        for (RenderPipeline rp : all) {
            JsonObject o = describePipeline(rp);
            String kind = required.contains(rp) ? "required" : optional.contains(rp) ? "optional" : postNames.containsKey(rp) ? "post" : "unregistered(lazy)";
            o.addProperty("registration", kind);
            JsonArray fn = new JsonArray();
            for (String s : fieldNames.getOrDefault(rp, List.of())) fn.add(s);
            if (postNames.containsKey(rp)) fn.add(postNames.get(rp));
            o.add("fields", fn);
            currentCreateInfo = null;
            currentKey = String.format("%03d_%s", idx++, rp.getLocation().getPath().replace('/', '_'));
            o.addProperty("key", currentKey);
            CompiledRenderPipeline.Pending pending = pb.compilePipeline(rp, source, Runnable::run).join();
            CompiledRenderPipeline compiled = pending.finishCompile();
            if (compiled instanceof FrontendRenderPipeline frp) {
                JsonObject ui = new JsonObject();
                frp.uniformIndices().forEach((k, v) -> ui.addProperty(k, v));
                o.add("uniformIndices", ui);
            }
            o.addProperty("compiled", compiled != null);
            if (currentCreateInfo != null) o.add("createInfo", currentCreateInfo);
            outArr.add(o);
        }
        Files.writeString(OUT.resolve("pipelines.json"), GSON.toJson(outArr));
        System.out.println("Wrote " + outArr.size() + " pipelines");
        System.exit(0);
    }

    static String read(ZipFile zip, String path) {
        try {
            ZipEntry e = zip.getEntry(path);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    static JsonElement colorTarget(ColorTargetState c) {
        if (c == null) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.addProperty("format", c.format().name());
        o.addProperty("writeMask", c.writeMask());
        o.addProperty("blend", c.blendFunction().map(Dump::blendName).orElse("none"));
        c.blendFunction().ifPresent(bf -> {
            o.addProperty("srcColor", bf.color().sourceFactor().name());
            o.addProperty("dstColor", bf.color().destFactor().name());
            o.addProperty("colorOp", bf.color().op().name());
            o.addProperty("srcAlpha", bf.alpha().sourceFactor().name());
            o.addProperty("dstAlpha", bf.alpha().destFactor().name());
            o.addProperty("alphaOp", bf.alpha().op().name());
        });
        return o;
    }

    static String blendName(BlendFunction bf) {
        for (Field f : BlendFunction.class.getFields()) {
            try { if (Modifier.isStatic(f.getModifiers()) && bf.equals(f.get(null))) return f.getName(); } catch (Exception ignored) {}
        }
        return bf.toString();
    }

    static JsonElement depth(DepthStencilState d) {
        if (d == null) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.addProperty("test", d.depthTest().name());
        o.addProperty("write", d.writeDepth());
        o.addProperty("biasScale", d.depthBiasScaleFactor());
        o.addProperty("biasConstant", d.depthBiasConstant());
        return o;
    }

    static JsonObject describePipeline(RenderPipeline rp) {
        JsonObject o = new JsonObject();
        o.addProperty("location", rp.getLocation().toString());
        JsonObject sh = new JsonObject();
        rp.getShaders().forEach((t, id) -> sh.addProperty(t.name(), id.toString()));
        o.add("shaders", sh);
        JsonObject defs = new JsonObject();
        rp.getShaderDefines().values().forEach(defs::addProperty);
        for (String fl : rp.getShaderDefines().flags()) defs.addProperty(fl, "");
        o.add("defines", defs);
        JsonArray uni = new JsonArray();
        for (BindGroupLayout.UniformDescription u : BindGroupLayout.flattenUniforms(rp.getBindGroupLayouts())) {
            uni.add(u.name() + ":" + u.type() + (u.gpuFormat() != null ? "(" + u.gpuFormat() + ")" : ""));
        }
        o.add("declaredUniforms", uni);
        JsonArray cts = new JsonArray();
        for (ColorTargetState c : rp.getColorTargetStates()) cts.add(colorTarget(c));
        o.add("colorTargets", cts);
        o.add("depth", depth(rp.getDepthStencilState()));
        o.addProperty("polygonMode", rp.getPolygonMode().name());
        o.addProperty("cull", rp.isCull());
        o.addProperty("topology", rp.getPrimitiveTopology().name());
        o.addProperty("pushConstantSize", rp.pushConstantSize());
        JsonArray vbs = new JsonArray();
        List<VertexFormat> vfs = rp.getVertexFormatBindings();
        for (int i = 0; i < vfs.size(); i++) {
            VertexFormat vf = vfs.get(i);
            if (vf == null) continue;
            JsonObject vo = new JsonObject();
            vo.addProperty("slot", i);
            vo.addProperty("stride", vf.getVertexSize());
            vo.addProperty("stepRate", vf.getStepRate());
            JsonArray els = new JsonArray();
            for (VertexFormatElement e : vf.getElements()) els.add(e.name() + "@" + e.offset() + ":" + e.format());
            vo.add("elements", els);
            vbs.add(vo);
        }
        o.add("vertexFormats", vbs);
        return o;
    }

    static JsonObject describeCreateInfo(BackendRenderPipeline.CreateInfo ci) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("name", ci.name());
        JsonArray vb = new JsonArray();
        for (var v : ci.vertexBuffers()) vb.add(v.bufferSlot() + " stride=" + v.stride() + " step=" + v.stepRate());
        o.add("vertexBuffers", vb);
        JsonArray ab = new JsonArray();
        for (var a : ci.attribBindings()) {
            JsonObject x = new JsonObject();
            x.addProperty("slot", a.bufferSlot());
            x.addProperty("location", a.location());
            x.addProperty("offset", a.offset());
            x.addProperty("format", a.format().name());
            ab.add(x);
        }
        o.add("attribBindings", ab);
        JsonArray un = new JsonArray();
        for (var u : ci.uniforms()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", u.name());
            x.addProperty("type", u.type().name());
            if (u.gpuFormat() != null) x.addProperty("format", u.gpuFormat().name());
            un.add(x);
        }
        o.add("uniforms", un);
        o.addProperty("pushConstantsSize", ci.pushConstantsSize());
        o.addProperty("topology", ci.primitiveTopology().name());
        JsonArray cts = new JsonArray();
        for (ColorTargetState c : ci.colorTargetStates()) cts.add(colorTarget(c));
        o.add("colorTargets", cts);
        o.add("depth", depth(ci.depthStencilState()));
        o.addProperty("cull", ci.cull());
        o.addProperty("polygonMode", ci.polygonMode().name());
        JsonArray shaders = new JsonArray();
        for (var s : ci.shaders()) {
            JsonObject x = new JsonObject();
            x.addProperty("name", s.name());
            x.addProperty("entryPoint", s.entryPoint());
            x.addProperty("stage", s.module().type().name());
            x.addProperty("spvBytes", s.module().spv().remaining());
            SpvModule.Reflection r = s.module().reflect();
            JsonArray ins = new JsonArray();
            for (var iv : r.inputs()) ins.add(iface(iv));
            x.add("inputs", ins);
            JsonArray outs = new JsonArray();
            for (var iv : r.outputs()) outs.add(iface(iv));
            x.add("outputs", outs);
            JsonArray ds = new JsonArray();
            for (var d : r.descriptors()) ds.add(d.name() + " rt=" + d.resourceType() + " set=" + d.descriptorSetIndex() + " binding=" + d.binding() + " dim=" + (d.type().dimensions() == Integer.MAX_VALUE ? "-" : d.type().dimensions()) + " base=" + d.type().baseType());
            x.add("descriptors", ds);
            JsonArray pcs = new JsonArray();
            for (var pc : r.pushConstants()) pcs.add(pc.size());
            x.add("pushConstants", pcs);
            // write spv
            byte[] spv = new byte[s.module().spv().remaining()];
            s.module().spv().duplicate().get(spv);
            Path spvPath = OUT.resolve("msl").resolve(currentKey + "." + s.module().type().name().toLowerCase() + ".spv");
            Files.write(spvPath, spv);
            // MSL
            JsonObject msl = toMsl(s, ci);
            x.add("msl", msl);
            shaders.add(x);
        }
        o.add("shaders", shaders);
        return o;
    }

    static JsonObject iface(SpvModule.Reflection.InterfaceVariable iv) {
        JsonObject o = new JsonObject();
        o.addProperty("name", iv.name());
        o.addProperty("location", iv.location());
        o.addProperty("baseType", iv.type().baseType());
        o.addProperty("vecSize", iv.type().vectorSize());
        int arr = iv.type().arrayDimensions();
        if (arr > 0) o.addProperty("array", iv.type().arrayLength(0));
        if (iv.hasDecoration(14)) o.addProperty("flat", true);
        return o;
    }

    static void check(int r, String msg) { if (r != 0) throw new IllegalStateException(msg + " " + r); }

    static JsonObject toMsl(BackendRenderPipeline.CreateInfo.Shader s, BackendRenderPipeline.CreateInfo ci) {
        JsonObject res = new JsonObject();
        IntBuffer spirv = s.module().spv().asIntBuffer();
        long ctx = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.callocPointer(1);
            check(Spvc.spvc_context_create(pp), "ctx");
            ctx = pp.get(0);
            final long fctx = ctx;
            Spvc.spvc_context_set_error_callback(ctx, SpvcErrorCallback.create((ud, err) -> {
                System.err.println("SPVC error: " + MemoryUtil.memUTF8(err));
            }), 0);
            check(Spvc.spvc_context_parse_spirv(ctx, spirv, spirv.remaining(), pp), "parse");
            long ir = pp.get(0);
            check(Spvc.spvc_context_create_compiler(ctx, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pp), "compiler");
            long comp = pp.get(0);
            check(Spvc.spvc_compiler_create_compiler_options(comp, pp), "opts");
            long opts = pp.get(0);
            Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION);
            Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, ci.primitiveTopology() == PrimitiveTopology.POINTS);
            Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, FLIP_Y);
            int model = s.module().type() == ShaderType.VERTEX ? 0 : 4;
            for (int b = 0; b < ci.uniforms().size(); b++) {
                SpvcMslResourceBinding rb = SpvcMslResourceBinding.calloc(stack);
                Spvc.spvc_msl_resource_binding_init(rb);
                rb.stage(model).desc_set(0).binding(b).msl_buffer(b).msl_texture(b).msl_sampler(b);
                check(Spvc.spvc_compiler_msl_add_resource_binding(comp, rb), "binding");
            }
            if (ci.pushConstantsSize() > 0) {
                SpvcMslResourceBinding rb = SpvcMslResourceBinding.calloc(stack);
                Spvc.spvc_msl_resource_binding_init(rb);
                rb.stage(model).desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET).binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING).msl_buffer(ci.uniforms().size());
                check(Spvc.spvc_compiler_msl_add_resource_binding(comp, rb), "pcbinding");
            }
            check(Spvc.spvc_compiler_install_compiler_options(comp, opts), "install");
            int r = Spvc.spvc_compiler_compile(comp, pp);
            if (r != 0) {
                res.addProperty("error", "compile failed " + r + " " + Spvc.spvc_context_get_last_error_string(ctx));
                return res;
            }
            String src = MemoryUtil.memUTF8(pp.get(0));
            String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(comp, "main", model);
            Path p = OUT.resolve("msl").resolve(currentKey + "." + s.module().type().name().toLowerCase() + ".metal");
            Files.writeString(p, src);
            res.addProperty("file", p.getFileName().toString());
            res.addProperty("entry", entry);
            // UBO declared sizes
            check(Spvc.spvc_compiler_create_shader_resources(comp, pp), "res");
            long resources = pp.get(0);
            PointerBuffer cnt0 = stack.callocPointer(1);
            check(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, pp, cnt0), "ubos");
            SpvcReflectedResource.Buffer ubos = SpvcReflectedResource.create(pp.get(0), (int) cnt0.get(0));
            JsonObject sizes = new JsonObject();
            for (int i = 0; i < ubos.remaining(); i++) {
                SpvcReflectedResource u = ubos.get(i);
                long th = Spvc.spvc_compiler_get_type_handle(comp, u.base_type_id());
                PointerBuffer sz = stack.callocPointer(1);
                Spvc.spvc_compiler_get_declared_struct_size(comp, th, sz);
                sizes.addProperty(u.nameString(), sz.get(0));
            }
            res.add("uboDeclaredSizes", sizes);
            // declared capabilities
            PointerBuffer cnt = stack.callocPointer(1);
            check(Spvc.spvc_compiler_get_declared_capabilities(comp, pp, cnt), "caps");
            IntBuffer caps = MemoryUtil.memIntBuffer(pp.get(0), (int) cnt.get(0));
            JsonArray ca = new JsonArray();
            for (int i = 0; i < caps.remaining(); i++) ca.add(caps.get(i));
            res.add("capabilities", ca);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (ctx != 0) Spvc.spvc_context_destroy(ctx);
        }
        return res;
    }
}
