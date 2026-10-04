// Standalone smoke test of libingot through its C ABI (no Minecraft, no Java). Build and run with native/test/run.sh.
//
// Covers: device queries, buffers, textures and views, samplers, library/pipeline creation (and their error paths),
// a render pass exercising every draw entry point with base vertex/instance variants, region clears, blit copies,
// readback checks against a CPU reference image, serials and GPU times, ingot_wait_until precision, the power state,
// and a CAMetalLayer surface (configure, next drawable, present blit) that is not attached to a window.
// The whole suite runs twice: with default device flags and with DEBUG_LABELS | RETAIN_REFS.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>

#include <pthread.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "ingot.h"

static int failures;

#define CHECK(cond, ...)                                       \
    do {                                                       \
        if (!(cond)) {                                         \
            failures++;                                        \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
            fprintf(stderr, __VA_ARGS__);                      \
            fprintf(stderr, "\n");                             \
        }                                                      \
    } while (0)

#define SHARED ((uint32_t)MTLResourceStorageModeShared)
#define MANAGED ((uint32_t)MTLResourceStorageModeManaged)
#define PRIVATE_TEXTURE ((uint32_t)MTLStorageModePrivate)

enum { RT = 64, CELL = 8, QUADS = 64, INSTANCES = 16 };

static uint64_t serial;

static void submit_and_wait(void *device, void *cmd) {
    uint64_t s = ++serial;
    ingot_cmd_commit(device, cmd, s);
    CHECK(ingot_device_wait_serial(device, s, 5000000000LL) == 1, "serial %llu did not complete", s);
}

// ---------------------------------------------------------------------------------------------------------------
// Test scene: 64 quads in an 8x8 grid of 8x8-pixel cells. Each quad's vertices carry its cell and a color that
// identifies the quad (R, G); per-instance data carries a cell shift and a tag (B) that identifies the instance.
// Every pixel therefore shows which vertices and which instance record a draw used.
// ---------------------------------------------------------------------------------------------------------------

static const char test_msl[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct VIn {\n"
    "    float2 corner [[attribute(0)]];\n"
    "    float4 color [[attribute(1)]];\n"
    "    int2 cell [[attribute(2)]];\n"
    "    int2 shift [[attribute(3)]];\n"
    "    float4 tag [[attribute(4)]];\n"
    "};\n"
    "struct VOut { float4 position [[position]]; float4 color; };\n"
    "struct Globals { float4 tint; };\n"
    "struct Push { float4 mul; float4 add; };\n"
    "// Both stages read the uniform block and the push constants, so both-stage bindings are exercised.\n"
    "vertex VOut test_vs(VIn in [[stage_in]], constant Globals &g [[buffer(0)]], constant Push &p [[buffer(14)]]) {\n"
    "    float2 px = (float2(in.cell + in.shift) + in.corner) * 8.0;\n"
    "    VOut out;\n"
    "    out.position = float4(px.x / 32.0 - 1.0, 1.0 - px.y / 32.0, 0.5, 1.0);\n"
    "    out.color = float4(in.color.r, in.color.g, in.tag.b, 1.0) * g.tint * p.mul;\n"
    "    return out;\n"
    "}\n"
    "fragment float4 test_fs(VOut in [[stage_in]], constant Globals &g [[buffer(0)]], constant Push &p [[buffer(14)]],\n"
    "                        texture2d<float> tex [[texture(0)]], sampler s [[sampler(0)]]) {\n"
    "    return in.color * tex.sample(s, float2(0.5), level(0)) * g.tint + p.add;\n"
    "}\n";

typedef struct Vertex {
    float corner[2];
    uint8_t color[4];
    int16_t cell[2];
} Vertex;

typedef struct Instance {
    int16_t shift[2];
    uint8_t tag[4];
} Instance;

static const uint16_t quad_indices[6] = {0, 1, 2, 2, 1, 3};

static void quad_color(int quad, int tag, uint8_t out[4]) {
    out[0] = (uint8_t)(4 * quad + 3);
    out[1] = (uint8_t)(255 - 4 * quad);
    out[2] = (uint8_t)(16 * tag + 5);
    out[3] = 255;
}

static void fill_rect(uint8_t *image, int x, int y, int w, int h, const uint8_t color[4]) {
    for (int j = y; j < y + h && j < RT; j++) {
        for (int i = x; i < x + w && i < RT; i++) {
            memcpy(&image[(j * RT + i) * 4], color, 4);
        }
    }
}

static void fill_depth(float *depth, int x, int y, int w, int h, float value) {
    for (int j = y; j < y + h && j < RT; j++) {
        for (int i = x; i < x + w && i < RT; i++) {
            depth[j * RT + i] = value;
        }
    }
}

static void *make_buffer(void *device, const void *data, uint64_t length, uint32_t options) {
    void *buffer = ingot_buffer_create(device, length, options, data, "test buffer");
    CHECK(buffer != NULL, "buffer create (%llu bytes): %s", length, ingot_last_error());
    return buffer;
}

// ---------------------------------------------------------------------------------------------------------------

static void test_queries(void *device) {
    printf("  device: %s\n", ingot_device_name(device));
    static const char *names[] = {"", "unified_memory", "max_buffer_length", "working_set_size", "apple_family", "mac2_family",
                                  "max_texture_size", "registry_id", "low_power", "removable", "max_anisotropy",
                                  "buffer_offset_alignment", "current_allocated_size", "arch_arm64"};
    for (uint32_t key = 1; key <= INGOT_QUERY_ARCH_ARM64; key++) {
        printf("    %-24s %llu\n", names[key], ingot_device_query(device, key));
    }
    CHECK(ingot_device_query(device, INGOT_QUERY_MAX_TEXTURE_SIZE) >= 16384, "max texture size");
    CHECK(ingot_device_query(device, INGOT_QUERY_MAX_BUFFER_LENGTH) > (1u << 28), "max buffer length");
    CHECK(ingot_device_query(device, 9999) == 0, "unknown query key must return 0");
    CHECK(ingot_device_texture_buffer_alignment(device, MTLPixelFormatR8Sint) > 0, "texture buffer alignment");
    CHECK(ingot_device_completed_serial(device) == 0, "fresh device has completed serial 0");
    CHECK(ingot_device_error_code(device) == 0 && ingot_device_error_message(device)[0] == '\0', "fresh device has no error");
}

static void test_buffers(void *device) {
    uint8_t pattern[256];
    for (int i = 0; i < 256; i++) {
        pattern[i] = (uint8_t)(i * 7 + 1);
    }
    void *src = make_buffer(device, pattern, sizeof(pattern), SHARED);
    void *dst = make_buffer(device, NULL, 256, SHARED);
    void *managed = make_buffer(device, NULL, 64, MANAGED);
    void *write_combined = make_buffer(device, NULL, 64, SHARED | (uint32_t)MTLResourceCPUCacheModeWriteCombined);
    uint8_t *src_bytes = ingot_buffer_contents(src);
    uint8_t *dst_bytes = ingot_buffer_contents(dst);
    CHECK(memcmp(src_bytes, pattern, sizeof(pattern)) == 0, "buffer contents match creation data");
    int zeroed = 1;
    for (int i = 0; i < 256; i++) {
        zeroed &= dst_bytes[i] == 0;
    }
    CHECK(zeroed, "buffer created without data is zeroed");

    // CPU writes to a managed buffer, published with didModifyRange; GPU writes back, published with synchronize.
    memset(ingot_buffer_contents(managed), 0x5A, 64);
    ingot_buffer_did_modify_range(managed, 0, 64);
    memset(ingot_buffer_contents(write_combined), 0x11, 64);

    void *cmd = ingot_cmd_create(device, "buffers");
    void *blit = ingot_blit_begin(cmd);
    ingot_blit_copy_buffer(blit, src, 32, dst, 16, 32);
    ingot_blit_fill_buffer(blit, dst, 64, 32, 0xAB);
    ingot_blit_copy_buffer(blit, managed, 0, dst, 128, 64);
    ingot_blit_copy_buffer(blit, write_combined, 0, dst, 192, 16);
    ingot_blit_fill_buffer(blit, managed, 8, 8, 0x77);
    ingot_blit_synchronize(blit, managed);
    ingot_blit_end(blit);
    submit_and_wait(device, cmd);

    CHECK(memcmp(dst_bytes + 16, pattern + 32, 32) == 0, "copy_buffer");
    int filled = 1;
    for (int i = 64; i < 96; i++) {
        filled &= dst_bytes[i] == 0xAB;
    }
    CHECK(filled && dst_bytes[63] == 0 && dst_bytes[96] == 0, "fill_buffer range");
    CHECK(dst_bytes[128] == 0x5A && dst_bytes[191] == 0x5A, "managed buffer CPU writes reach the GPU");
    CHECK(dst_bytes[192] == 0x11, "write-combined buffer");
    const uint8_t *managed_bytes = ingot_buffer_contents(managed);
    CHECK(managed_bytes[8] == 0x77 && managed_bytes[15] == 0x77 && managed_bytes[16] == 0x5A, "synchronize makes GPU writes visible");

    // Texture buffer views at offset 0 and at the minimum alignment.
    uint64_t alignment = ingot_device_texture_buffer_alignment(device, MTLPixelFormatR32Uint);
    void *view0 = ingot_texture_buffer_create(src, MTLPixelFormatR32Uint, 0, 16, 64);
    void *view1 = ingot_texture_buffer_create(src, MTLPixelFormatR32Uint, alignment, 8, 32);
    CHECK(view0 != NULL && view1 != NULL, "texture buffer views: %s", ingot_last_error());
    CHECK(ingot_texture_buffer_create(src, MTLPixelFormatR32Uint, 0, 0, 64) == NULL, "empty texture buffer view fails");

    // Requests beyond the device limits fail with an error instead of tripping Metal's descriptor assertions.
    void *huge = ingot_buffer_create(device, ingot_device_query(device, INGOT_QUERY_MAX_BUFFER_LENGTH) + 1, SHARED, NULL, NULL);
    CHECK(huge == NULL && strstr(ingot_last_error(), "maxBufferLength") != NULL, "oversized buffer fails: %s", ingot_last_error());
    CHECK(ingot_buffer_create(device, 0, SHARED, NULL, NULL) == NULL, "empty buffer fails");

    ingot_release(view0);
    ingot_release(view1);
    ingot_release(src);
    ingot_release(dst);
    ingot_release(managed);
    ingot_release(write_combined);
}

static void test_textures(void *device) {
    // 4x4 pattern uploaded into level 1 of an 8x8 texture, read through a level-1 view.
    uint8_t pattern[4 * 4 * 4];
    for (int i = 0; i < (int)sizeof(pattern); i++) {
        pattern[i] = (uint8_t)(i * 3 + 9);
    }
    void *staging = make_buffer(device, pattern, sizeof(pattern), SHARED);
    void *readback = make_buffer(device, NULL, 256, SHARED);
    void *tex_a = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatRGBA8Unorm, 8, 8, 2, MTLTextureUsageShaderRead,
                                       PRIVATE_TEXTURE, "texture A");
    void *tex_b = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatRGBA8Unorm, 4, 4, 1, MTLTextureUsageShaderRead,
                                       PRIVATE_TEXTURE, NULL);
    void *cube = ingot_texture_create(device, MTLTextureTypeCube, MTLPixelFormatRGBA8Unorm, 16, 16, 5,
                                      MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget, PRIVATE_TEXTURE, "cube");
    CHECK(tex_a != NULL && tex_b != NULL && cube != NULL, "texture create: %s", ingot_last_error());
    void *view_a1 = ingot_texture_view_create(tex_a, MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 1, 1, 0, 1);
    void *cube_view = ingot_texture_view_create(cube, MTLPixelFormatRGBA8Unorm, MTLTextureTypeCube, 2, 3, 0, 6);
    CHECK(view_a1 != NULL && cube_view != NULL, "texture view create: %s", ingot_last_error());

    void *cmd = ingot_cmd_create(device, "textures");
    void *blit = ingot_blit_begin(cmd);
    ingot_blit_copy_buffer_to_texture(blit, staging, 0, 16, 64, tex_a, 0, 1, 0, 0, 4, 4, MTLBlitOptionNone);
    ingot_blit_copy_texture(blit, view_a1, 0, 0, 0, 0, tex_b, 0, 0, 0, 0, 4, 4);
    ingot_blit_copy_texture_to_buffer(blit, tex_b, 0, 0, 0, 0, 4, 4, readback, 0, 16, 64, MTLBlitOptionNone);
    ingot_blit_copy_texture_to_buffer(blit, view_a1, 0, 0, 1, 2, 2, 1, readback, 128, 8, 8, MTLBlitOptionNone);
    ingot_blit_end(blit);
    submit_and_wait(device, cmd);

    const uint8_t *bytes = ingot_buffer_contents(readback);
    CHECK(memcmp(bytes, pattern, sizeof(pattern)) == 0, "buffer -> texture level 1 -> view -> texture -> buffer round trip");
    CHECK(memcmp(bytes + 128, pattern + (2 * 4 + 1) * 4, 8) == 0, "texture -> buffer sub-rectangle from a view");

    void *bad = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatRGBA8Unorm, 32768, 16, 1, MTLTextureUsageShaderRead,
                                     PRIVATE_TEXTURE, NULL);
    CHECK(bad == NULL && strstr(ingot_last_error(), "maximum") != NULL, "oversized texture fails: %s", ingot_last_error());

    ingot_release(cube_view);
    ingot_release(view_a1);
    ingot_release(cube);
    ingot_release(tex_b);
    ingot_release(tex_a);
    ingot_release(readback);
    ingot_release(staging);
}

static void test_pipeline_errors(void *device, void *library) {
    void *broken = ingot_library_create(device, "this is not metal", "broken");
    CHECK(broken == NULL && strstr(ingot_last_error(), "error") != NULL, "bad MSL fails with the compiler log: %s", ingot_last_error());
    CHECK(ingot_library_function(library, "missing_function") == NULL && strstr(ingot_last_error(), "missing_function") != NULL,
          "missing function fails");

    // The vertex function reads stage_in attributes, so a pipeline without a vertex descriptor must fail cleanly.
    IngotPipelineDesc desc;
    memset(&desc, 0, sizeof(desc));
    desc.vertex_function = ingot_library_function(library, "test_vs");
    desc.colors[0].pixel_format = MTLPixelFormatRGBA8Unorm;
    desc.colors[0].write_mask = MTLColorWriteMaskAll;
    desc.label = "no vertex descriptor";
    void *pipeline = ingot_pipeline_create(device, &desc);
    CHECK(pipeline == NULL && strstr(ingot_last_error(), "no vertex descriptor") != NULL, "invalid pipeline fails: %s", ingot_last_error());
    ingot_release(desc.vertex_function);
}

static void *make_test_pipeline(void *device, void *library) {
    IngotPipelineDesc desc;
    memset(&desc, 0, sizeof(desc));
    desc.vertex_function = ingot_library_function(library, "test_vs");
    desc.fragment_function = ingot_library_function(library, "test_fs");
    CHECK(desc.vertex_function != NULL && desc.fragment_function != NULL, "functions: %s", ingot_last_error());
    // Vertex buffer slot 0 -> buffer(30) per vertex, slot 1 -> buffer(29) per instance.
    desc.attributes[0] = (IngotVertexAttribute){MTLVertexFormatFloat2, offsetof(Vertex, corner), 30, 0};
    desc.attributes[1] = (IngotVertexAttribute){MTLVertexFormatUChar4Normalized, offsetof(Vertex, color), 30, 0};
    desc.attributes[2] = (IngotVertexAttribute){MTLVertexFormatShort2, offsetof(Vertex, cell), 30, 0};
    desc.attributes[3] = (IngotVertexAttribute){MTLVertexFormatShort2, offsetof(Instance, shift), 29, 0};
    desc.attributes[4] = (IngotVertexAttribute){MTLVertexFormatUChar4Normalized, offsetof(Instance, tag), 29, 0};
    desc.layouts[30] = (IngotVertexLayout){sizeof(Vertex), MTLVertexStepFunctionPerVertex, 1, 0};
    desc.layouts[29] = (IngotVertexLayout){sizeof(Instance), MTLVertexStepFunctionPerInstance, 1, 0};
    desc.colors[0] = (IngotColorTarget){MTLPixelFormatRGBA8Unorm, 0, 0, 0, 0, 0, 0, 0, MTLColorWriteMaskAll, 0};
    desc.depth_pixel_format = MTLPixelFormatDepth32Float;
    desc.sample_count = 0;
    desc.input_primitive_topology = MTLPrimitiveTopologyClassTriangle;
    desc.label = "test pipeline";
    void *pipeline = ingot_pipeline_create(device, &desc);
    CHECK(pipeline != NULL, "pipeline create: %s", ingot_last_error());

    // A blending variant (premultiplied alpha over) must build as well.
    desc.colors[0] = (IngotColorTarget){MTLPixelFormatRGBA8Unorm, 1, MTLBlendOperationAdd, MTLBlendOperationAdd, MTLBlendFactorOne,
                                        MTLBlendFactorOneMinusSourceAlpha, MTLBlendFactorOne, MTLBlendFactorOneMinusSourceAlpha,
                                        MTLColorWriteMaskRed | MTLColorWriteMaskGreen | MTLColorWriteMaskBlue, 0};
    void *blended = ingot_pipeline_create(device, &desc);
    CHECK(blended != NULL, "blended pipeline create: %s", ingot_last_error());
    ingot_release(blended);
    ingot_release(desc.vertex_function);
    ingot_release(desc.fragment_function);
    return pipeline;
}

typedef struct Scene {
    void *color;        // RGBA8 64x64 render target (also the present-blit source)
    uint8_t expected[RT * RT * 4];
} Scene;

static void test_render(void *device, Scene *scene) {
    void *library = ingot_library_create(device, test_msl, "test library");
    CHECK(library != NULL, "library create: %s", ingot_last_error());
    test_pipeline_errors(device, library);
    void *pipeline = make_test_pipeline(device, library);
    void *depth_state = ingot_depth_stencil_create(device, MTLCompareFunctionAlways, 1);
    void *sampler = ingot_sampler_create(device, MTLSamplerAddressModeClampToEdge, MTLSamplerAddressModeRepeat, MTLSamplerMinMagFilterNearest,
                                         MTLSamplerMinMagFilterLinear, MTLSamplerMipFilterNotMipmapped, 1, 0.0f, 0.0f, "nearest");
    void *aniso = ingot_sampler_create(device, MTLSamplerAddressModeRepeat, MTLSamplerAddressModeRepeat, MTLSamplerMinMagFilterLinear,
                                       MTLSamplerMinMagFilterLinear, MTLSamplerMipFilterLinear, 16, 0.0f, 1000.0f, NULL);
    CHECK(depth_state != NULL && sampler != NULL && aniso != NULL, "state objects: %s", ingot_last_error());
    ingot_release(aniso);

    // Geometry.
    Vertex vertices[QUADS * 4];
    static const float corners[4][2] = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};   // triangle-strip order
    for (int q = 0; q < QUADS; q++) {
        for (int c = 0; c < 4; c++) {
            Vertex *v = &vertices[q * 4 + c];
            v->corner[0] = corners[c][0];
            v->corner[1] = corners[c][1];
            quad_color(q, 0, v->color);
            v->cell[0] = (int16_t)(q % 8);
            v->cell[1] = (int16_t)(q / 8);
        }
    }
    Instance instances[INSTANCES];
    for (int i = 0; i < INSTANCES; i++) {
        instances[i] = (Instance){{0, (int16_t)(i == 11 ? 1 : 0)}, {0, 0, (uint8_t)(16 * i + 5), 255}};
    }
    uint16_t indices16[16] = {0};
    memcpy(&indices16[3], quad_indices, sizeof(quad_indices));              // local quad at index 3 (odd byte offset 6)
    for (int i = 0; i < 6; i++) {
        indices16[9 + i] = (uint16_t)(84 + quad_indices[i]);                 // absolute quad 21 at index 9
    }
    uint32_t indices32[16] = {0};
    for (int i = 0; i < 6; i++) {
        indices32[1 + i] = quad_indices[i];                                  // local quad at index 1
        indices32[7 + i] = quad_indices[i];                                  // local quad at index 7
    }

    // Indirect records: draw_indirect @0 (stride 16), indexed @64 (stride 20), draw_indirect @128 (stride 24),
    // indexed @192 (stride 32, index_offset 2 bytes).
    uint8_t indirect[256] = {0};
    MTLDrawPrimitivesIndirectArguments draw0 = {4, 1, 60, 6};
    memcpy(indirect, &draw0, sizeof(draw0));
    MTLDrawIndexedPrimitivesIndirectArguments indexed[3] = {{6, 1, 3, 64, 7}, {6, 1, 3, 68, 8}, {6, 1, 9, 0, 9}};
    memcpy(indirect + 64, indexed, sizeof(indexed));
    MTLDrawPrimitivesIndirectArguments strided[2] = {{4, 1, 76, 12}, {4, 1, 80, 13}};
    memcpy(indirect + 128, &strided[0], sizeof(strided[0]));
    memcpy(indirect + 152, &strided[1], sizeof(strided[1]));
    MTLDrawIndexedPrimitivesIndirectArguments padded[2] = {{6, 1, 2, 88, 14}, {6, 2, 2, 92, 10}};
    memcpy(indirect + 192, &padded[0], sizeof(padded[0]));
    memcpy(indirect + 224, &padded[1], sizeof(padded[1]));

    // Uniforms: offset 0 holds a zero tint (would turn everything black); offsets 256 and 384 hold the real one.
    float uniforms[128] = {0};
    for (int i = 0; i < 4; i++) {
        uniforms[64 + i] = uniforms[96 + i] = 1.0f;
    }
    float push[32] = {1, 1, 1, 1, 0, 0, 0, 0};   // padded to the 128-byte push constant size Java uses

    void *vb = make_buffer(device, vertices, sizeof(vertices), SHARED);
    void *ib = make_buffer(device, instances, sizeof(instances), SHARED);
    void *ib16 = make_buffer(device, indices16, sizeof(indices16), SHARED);
    void *ib32 = make_buffer(device, indices32, sizeof(indices32), SHARED);
    void *args = make_buffer(device, indirect, sizeof(indirect), SHARED);
    void *ubo = make_buffer(device, uniforms, sizeof(uniforms), SHARED);

    // A white 2x2 texture (uploaded by blit) sampled through a view.
    uint8_t white[16];
    memset(white, 255, sizeof(white));
    void *white_staging = make_buffer(device, white, sizeof(white), SHARED);
    void *white_tex = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatRGBA8Unorm, 2, 2, 1, MTLTextureUsageShaderRead,
                                           PRIVATE_TEXTURE, "white");
    void *white_view = ingot_texture_view_create(white_tex, MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 0, 1, 0, 1);

    scene->color = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatRGBA8Unorm, RT, RT, 1,
                                        MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead, PRIVATE_TEXTURE, "color target");
    void *depth = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatDepth32Float, RT, RT, 1, MTLTextureUsageRenderTarget,
                                       PRIVATE_TEXTURE, "depth target");
    void *small_depth = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatDepth32Float, RT / 2, RT / 2, 1,
                                             MTLTextureUsageRenderTarget, PRIVATE_TEXTURE, "small depth target");
    void *color_readback = make_buffer(device, NULL, RT * RT * 4, SHARED);
    void *depth_readback = make_buffer(device, NULL, RT * RT * 4, SHARED);
    CHECK(scene->color != NULL && depth != NULL && small_depth != NULL && white_tex != NULL && white_view != NULL, "render resources: %s",
          ingot_last_error());

    void *cmd = ingot_cmd_create(device, "render");
    ingot_cmd_push_debug_group(cmd, "smoke render");
    void *blit = ingot_blit_begin(cmd);
    ingot_blit_copy_buffer_to_texture(blit, white_staging, 0, 8, 16, white_tex, 0, 0, 0, 0, 2, 2, MTLBlitOptionNone);
    ingot_blit_end(blit);

    IngotRenderPassDesc pass;
    memset(&pass, 0, sizeof(pass));
    pass.colors[0] = (IngotColorAttachment){scene->color, 0, 0, MTLLoadActionClear, MTLStoreActionStore, {0, 0, 0, 0}};
    pass.depth_texture = depth;
    pass.depth_load_action = MTLLoadActionClear;
    pass.depth_store_action = MTLStoreActionStore;
    pass.clear_depth = 1.0f;
    pass.label = "smoke pass";
    void *enc = ingot_render_begin(cmd, &pass);
    CHECK(enc != NULL, "render begin: %s", ingot_last_error());

    ingot_render_push_debug_group(enc, "state");
    ingot_render_set_viewport(enc, 0, 0, RT, RT, 0, 1);
    ingot_render_set_scissor(enc, 0, 0, RT, RT);
    ingot_render_set_raster(enc, MTLCullModeNone, MTLWindingClockwise, MTLTriangleFillModeFill);
    ingot_render_set_depth_bias(enc, 0, 0, 0);
    ingot_render_set_pipeline(enc, pipeline);
    ingot_render_set_depth_stencil(enc, depth_state);
    ingot_render_set_buffer(enc, INGOT_STAGE_VERTEX, vb, 0, 30);
    ingot_render_set_buffer(enc, INGOT_STAGE_VERTEX, ib, 0, 29);
    ingot_render_set_buffer(enc, INGOT_STAGE_VERTEX | INGOT_STAGE_FRAGMENT, ubo, 256, 0);
    ingot_render_set_bytes(enc, INGOT_STAGE_VERTEX | INGOT_STAGE_FRAGMENT, push, sizeof(push), 14);
    ingot_render_set_texture(enc, INGOT_STAGE_FRAGMENT, white_view, 0);
    ingot_render_set_sampler(enc, INGOT_STAGE_FRAGMENT, sampler, 0);
    ingot_render_pop_debug_group(enc);

    // Every expected cell is listed next to the draw that produces it: (cell, quad, instance tag).
    static const int cells[][3] = {
        {0, 0, 1}, {1, 1, 10}, {9, 1, 11}, {2, 2, 2}, {3, 3, 3}, {4, 4, 4}, {5, 5, 4}, {6, 6, 4}, {7, 7, 0}, {8, 8, 0},
        {10, 10, 5}, {12, 12, 5}, {13, 13, 0}, {14, 14, 0}, {15, 15, 6}, {16, 16, 7}, {17, 17, 8}, {21, 21, 9},
        {19, 19, 12}, {20, 20, 13}, {22, 22, 14}, {23, 23, 10}, {31, 23, 11},
    };
    const uint32_t strip = MTLPrimitiveTypeTriangleStrip, list = MTLPrimitiveTypeTriangle;
    // cell 0: vertexStart + baseInstance
    ingot_render_draw(enc, strip, 0, 4, 1, 1);
    // every later draw reads the uniform block through an offset-only rebind
    ingot_render_set_buffer_offset(enc, INGOT_STAGE_VERTEX | INGOT_STAGE_FRAGMENT, 384, 0);
    // cells 1 and 9: two instances from base instance 10 (instance 11 shifts down one row)
    ingot_render_draw(enc, strip, 4, 4, 2, 10);
    // skipped: empty draws
    ingot_render_draw(enc, strip, 4 * 40, 0, 1, 0);
    ingot_render_draw_indexed(enc, list, 6, MTLIndexTypeUInt16, ib16, 6, 0, 0, 0);
    // cell 2: uint16 indices at an odd first index (byte offset 6), base vertex, base instance
    ingot_render_draw_indexed(enc, list, 6, MTLIndexTypeUInt16, ib16, 6, 1, 8, 2);
    // cell 3: uint32 indices
    ingot_render_draw_indexed(enc, list, 6, MTLIndexTypeUInt32, ib32, 4, 1, 12, 3);
    // cells 4-6: interleaved multi-draw (the last record is empty)
    int32_t multi[] = {16, 4, 20, 4, 24, 4, 0, 0};
    ingot_render_multi_draw(enc, strip, multi, 4, 1, 4);
    // cells 7-8: separate multi-draw
    int32_t firsts[] = {28, 32}, counts[] = {4, 4};
    ingot_render_multi_draw_separate(enc, strip, firsts, counts, 2);
    // cells 10 and 12: interleaved indexed multi-draw; index_offset (one uint16) + firstIndex 2 = index 3; the
    // middle record (cell 11) is empty
    int32_t multi_indexed[] = {2, 6, 40, 2, 0, 44, 2, 6, 48};
    ingot_render_multi_draw_indexed(enc, list, MTLIndexTypeUInt16, ib16, 2, multi_indexed, 3, 1, 5);
    // cells 13-14: separate indexed multi-draw with byte offsets into uint32 indices
    int64_t offsets[] = {4, 28};
    int32_t index_counts[] = {6, 6}, vertex_offsets[] = {52, 56};
    ingot_render_multi_draw_indexed_separate(enc, list, MTLIndexTypeUInt32, ib32, offsets, index_counts, vertex_offsets, 2);
    // cell 15: indirect
    ingot_render_draw_indirect(enc, strip, args, 0, 1, sizeof(MTLDrawPrimitivesIndirectArguments));
    // cells 16, 17, 21: indexed indirect, tight stride; the last record uses absolute indices with base vertex 0
    ingot_render_draw_indexed_indirect(enc, list, MTLIndexTypeUInt16, ib16, 0, args, 64, 3, sizeof(MTLDrawIndexedPrimitivesIndirectArguments));
    // cells 19-20: indirect with a padded stride
    ingot_render_draw_indirect(enc, strip, args, 128, 2, 24);
    // cells 22, 23 and 31: indexed indirect, padded stride, index_offset, two instances
    ingot_render_draw_indexed_indirect(enc, list, MTLIndexTypeUInt16, ib16, 2, args, 192, 2, 32);
    ingot_render_end(enc);

    // Region clears: color + depth over parts of cells 0 and 1; color only, clipped at the corner; depth only;
    // color with a half-size depth texture, clipped to the depth texture; and two no-ops (no flags, rectangle outside
    // the texture).
    ingot_util_clear_region(device, cmd, scene->color, depth, 0, 4, 2, 8, 3, 200 / 255.0f, 100 / 255.0f, 50 / 255.0f, 1.0f, 0.25f, 3);
    ingot_util_clear_region(device, cmd, scene->color, depth, 0, 60, 60, 10, 10, 10 / 255.0f, 20 / 255.0f, 30 / 255.0f, 40 / 255.0f, 0.0f, 1);
    ingot_util_clear_region(device, cmd, scene->color, depth, 0, 0, 56, 8, 8, 1.0f, 1.0f, 1.0f, 1.0f, 0.75f, 2);
    ingot_util_clear_region(device, cmd, scene->color, small_depth, 0, 28, 28, 8, 8, 0.0f, 128 / 255.0f, 1.0f, 1.0f, 0.5f, 3);
    ingot_util_clear_region(device, cmd, scene->color, depth, 0, 0, 0, 64, 64, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0);
    ingot_util_clear_region(device, cmd, scene->color, depth, 0, 64, 0, 8, 8, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 3);

    blit = ingot_blit_begin(cmd);
    ingot_blit_copy_texture_to_buffer(blit, scene->color, 0, 0, 0, 0, RT, RT, color_readback, 0, RT * 4, RT * RT * 4, MTLBlitOptionNone);
    ingot_blit_copy_texture_to_buffer(blit, depth, 0, 0, 0, 0, RT, RT, depth_readback, 0, RT * 4, RT * RT * 4, MTLBlitOptionNone);
    ingot_blit_end(blit);
    ingot_cmd_pop_debug_group(cmd);

    int64_t before = ingot_now_ns();
    uint64_t render_serial = serial + 1;
    submit_and_wait(device, cmd);
    int64_t after = ingot_now_ns();

    // CPU reference.
    float expected_depth[RT * RT];
    memset(scene->expected, 0, sizeof(scene->expected));
    fill_depth(expected_depth, 0, 0, RT, RT, 1.0f);
    for (size_t i = 0; i < sizeof(cells) / sizeof(cells[0]); i++) {
        uint8_t color[4];
        quad_color(cells[i][1], cells[i][2], color);
        fill_rect(scene->expected, (cells[i][0] % 8) * CELL, (cells[i][0] / 8) * CELL, CELL, CELL, color);
        fill_depth(expected_depth, (cells[i][0] % 8) * CELL, (cells[i][0] / 8) * CELL, CELL, CELL, 0.5f);
    }
    fill_rect(scene->expected, 4, 2, 8, 3, (const uint8_t[4]){200, 100, 50, 255});
    fill_depth(expected_depth, 4, 2, 8, 3, 0.25f);
    fill_rect(scene->expected, 60, 60, 4, 4, (const uint8_t[4]){10, 20, 30, 40});
    fill_depth(expected_depth, 0, 56, 8, 8, 0.75f);
    fill_rect(scene->expected, 28, 28, 4, 4, (const uint8_t[4]){0, 128, 255, 255});

    const uint8_t *pixels = ingot_buffer_contents(color_readback);
    const float *depths = ingot_buffer_contents(depth_readback);
    int bad_pixels = 0, bad_depths = 0;
    for (int i = 0; i < RT * RT; i++) {
        if (memcmp(&pixels[i * 4], &scene->expected[i * 4], 4) != 0) {
            if (bad_pixels++ < 8) {
                fprintf(stderr, "    pixel (%d, %d) = %u %u %u %u, expected %u %u %u %u\n", i % RT, i / RT, pixels[i * 4],
                        pixels[i * 4 + 1], pixels[i * 4 + 2], pixels[i * 4 + 3], scene->expected[i * 4], scene->expected[i * 4 + 1],
                        scene->expected[i * 4 + 2], scene->expected[i * 4 + 3]);
            }
        }
        if (depths[i] != expected_depth[i]) {
            if (bad_depths++ < 8) {
                fprintf(stderr, "    depth (%d, %d) = %f, expected %f\n", i % RT, i / RT, depths[i], expected_depth[i]);
            }
        }
    }
    CHECK(bad_pixels == 0, "%d color pixels differ from the reference", bad_pixels);
    CHECK(bad_depths == 0, "%d depth values differ from the reference", bad_depths);

    // GPU times of the render serial lie inside the CPU window around commit + wait.
    int64_t times[2] = {0, 0};
    CHECK(ingot_device_serial_times(device, render_serial, times) == 1, "serial times available");
    CHECK(times[0] > 0 && times[0] <= times[1], "GPU start %lld <= end %lld", times[0], times[1]);
    CHECK(times[0] >= before && times[1] <= after, "GPU window [%lld, %lld] inside CPU window [%lld, %lld]", times[0], times[1], before, after);
    printf("  render serial %llu: GPU %.1f us, submit-to-GPU-start %.1f us, wait %.1f us\n", render_serial, (times[1] - times[0]) / 1e3,
           (times[0] - before) / 1e3, (after - before) / 1e3);

    ingot_release(color_readback);
    ingot_release(depth_readback);
    ingot_release(small_depth);
    ingot_release(depth);
    ingot_release(white_view);
    ingot_release(white_tex);
    ingot_release(white_staging);
    ingot_release(ubo);
    ingot_release(args);
    ingot_release(ib32);
    ingot_release(ib16);
    ingot_release(ib);
    ingot_release(vb);
    ingot_release(sampler);
    ingot_release(depth_state);
    ingot_release(pipeline);
    ingot_release(library);
}

static void test_serials(void *device) {
    uint64_t completed = ingot_device_completed_serial(device);
    CHECK(completed == serial, "completed serial %llu == last committed %llu", completed, serial);
    CHECK(ingot_device_wait_serial(device, serial, 0) == 1, "wait on a completed serial returns 1 immediately");
    CHECK(ingot_device_wait_serial(device, serial + 100, 0) == 0, "zero-timeout wait on a future serial returns 0");
    int64_t start = ingot_now_ns();
    CHECK(ingot_device_wait_serial(device, serial + 100, 2000000) == 0, "timed wait on a future serial returns 0");
    int64_t waited = ingot_now_ns() - start;
    CHECK(waited >= 2000000 && waited < 50000000, "timed wait blocked %lld ns for a 2 ms timeout", waited);
    int64_t times[2];
    CHECK(ingot_device_serial_times(device, serial + 100, times) == 0, "unknown serial has no times");
    CHECK(ingot_device_serial_times(device, 0, times) == 0, "serial 0 has no times");

    // A burst of empty command buffers completes in order; all waits succeed.
    for (int i = 0; i < 8; i++) {
        ingot_cmd_commit(device, ingot_cmd_create(device, NULL), ++serial);
    }
    CHECK(ingot_device_wait_serial(device, serial, -1) == 1, "wait forever on the last of a burst");
    CHECK(ingot_device_completed_serial(device) == serial, "completed serial after the burst");
    CHECK(ingot_device_error_code(device) == 0, "no command buffer errors: %s", ingot_device_error_message(device));
}

static void test_surface(void *device, Scene *scene) {
    // The test's own Objective-C calls get their own pools; libingot is called without one (like the Java render
    // thread), so OBJC_DEBUG_MISSING_POOLS reports any autoreleased object an entry point leaks.
    CAMetalLayer *layer;
    CALayer *plain_layer;
    @autoreleasepool {
        layer = [CAMetalLayer layer];
        plain_layer = [CALayer layer];
    }
    void *surface = ingot_surface_create(device, (__bridge void *)layer);
    CHECK(surface != NULL, "surface create: %s", ingot_last_error());
    CHECK(ingot_surface_create(device, (__bridge void *)plain_layer) == NULL, "a plain CALayer is rejected");
    ingot_surface_configure(surface, 48, 40, 1, 3);
    @autoreleasepool {
        CHECK(layer.device != nil && layer.pixelFormat == MTLPixelFormatBGRA8Unorm && layer.framebufferOnly && layer.opaque &&
                  !layer.presentsWithTransaction && layer.allowsNextDrawableTimeout,
              "layer configured for the device");
        CHECK(layer.drawableSize.width == 48 && layer.drawableSize.height == 40 && layer.displaySyncEnabled &&
                  layer.maximumDrawableCount == 3,
              "configure applied");
    }
    ingot_surface_configure(surface, 48, 40, 0, 2);
    @autoreleasepool {
        CHECK(!layer.displaySyncEnabled && layer.maximumDrawableCount == 2, "configure without vsync, 2 drawables");
        // Test-only: let the readback blit copy out of the drawable.
        layer.framebufferOnly = NO;
    }
    CHECK(ingot_surface_visible(surface) == 1, "a layer without a window counts as visible");
    CHECK(ingot_surface_max_refresh_rate(surface) == 60, "a layer without a window reports 60 Hz");

    void *readback = make_buffer(device, NULL, 48 * 40 * 4, SHARED);
    for (int frame = 0; frame < 2; frame++) {
        // Frame 0: full source (64x64, larger than the drawable), flipped. Frame 1: a 20x10 source, not flipped.
        uint32_t src_w = frame == 0 ? RT : 20, src_h = frame == 0 ? RT : 10, flip = frame == 0;
        void *drawable = ingot_surface_next_drawable(surface);
        CHECK(drawable != NULL, "next drawable: %s", ingot_last_error());
        if (drawable == NULL) {
            break;
        }
        void *drawable_texture;
        @autoreleasepool {
            // The drawable owns its texture, so the unretained pointer stays valid until the drawable is released.
            drawable_texture = (__bridge void *)((__bridge id<CAMetalDrawable>)drawable).texture;
        }
        void *cmd = ingot_cmd_create(device, "present");
        ingot_surface_blit(surface, cmd, drawable, scene->color, src_w, src_h, flip);
        void *blit = ingot_blit_begin(cmd);
        ingot_blit_copy_texture_to_buffer(blit, drawable_texture, 0, 0, 0, 0, 48, 40, readback, 0, 48 * 4, 48 * 40 * 4, MTLBlitOptionNone);
        ingot_blit_end(blit);
        submit_and_wait(device, cmd);
        ingot_release(drawable);

        const uint8_t *bgra = ingot_buffer_contents(readback);
        uint32_t copy_w = src_w < 48 ? src_w : 48, copy_h = src_h < 40 ? src_h : 40;
        int bad = 0;
        for (uint32_t y = 0; y < 40; y++) {
            for (uint32_t x = 0; x < 48; x++) {
                uint8_t expected[4] = {0, 0, 0, 255};   // BGRA
                if (x < copy_w && y < copy_h) {
                    const uint8_t *rgba = &scene->expected[((flip ? copy_h - 1 - y : y) * RT + x) * 4];
                    expected[0] = rgba[2];
                    expected[1] = rgba[1];
                    expected[2] = rgba[0];
                    expected[3] = rgba[3];
                }
                if (memcmp(&bgra[(y * 48 + x) * 4], expected, 4) != 0 && bad++ < 4) {
                    const uint8_t *p = &bgra[(y * 48 + x) * 4];
                    fprintf(stderr, "    frame %d drawable (%u, %u) = %u %u %u %u, expected %u %u %u %u\n", frame, x, y, p[0], p[1], p[2],
                            p[3], expected[0], expected[1], expected[2], expected[3]);
                }
            }
        }
        CHECK(bad == 0, "present blit frame %d: %d pixels differ", frame, bad);
    }
    ingot_release(readback);
    ingot_surface_destroy(surface);
}

static int compare_i64(const void *a, const void *b) {
    int64_t x = *(const int64_t *)a, y = *(const int64_t *)b;
    return (x > y) - (x < y);
}

static void test_wait_until(void) {
    // ingot_now_ns must be the System.nanoTime() clock, which is CLOCK_UPTIME_RAW (mach_absolute_time) in ns.
    int64_t ours = ingot_now_ns();
    int64_t uptime = (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    CHECK(llabs(uptime - ours) < 1000000, "ingot_now_ns %lld vs CLOCK_UPTIME_RAW %lld", ours, uptime);
    int64_t past = ingot_now_ns() - 1000000;
    int64_t t0 = ingot_now_ns();
    ingot_wait_until(past);
    CHECK(ingot_now_ns() - t0 < 1000000, "a past deadline returns immediately");

    static const int64_t periods[] = {1000000, 8000000};
    static const int counts[] = {200, 100};
    for (int p = 0; p < 2; p++) {
        int64_t errors[200];
        for (int i = 0; i < counts[p]; i++) {
            int64_t deadline = ingot_now_ns() + periods[p];
            ingot_wait_until(deadline);
            errors[i] = ingot_now_ns() - deadline;
        }
        qsort(errors, (size_t)counts[p], sizeof(int64_t), compare_i64);
        int n = counts[p];
        printf("  wait_until %2lld ms x%d: late by min %.1f us, median %.1f us, p90 %.1f us, p99 %.1f us, max %.1f us\n",
               periods[p] / 1000000, n, errors[0] / 1e3, errors[n / 2] / 1e3, errors[n * 9 / 10] / 1e3, errors[n * 99 / 100] / 1e3,
               errors[n - 1] / 1e3);
        CHECK(errors[0] >= 0, "ingot_wait_until returned %lld ns before its deadline", -errors[0]);
    }
}

static void test_power(void) {
    uint32_t state = ingot_power_state();
    static const char *thermal[] = {"nominal", "fair", "serious", "critical"};
    uint32_t level = (state >> 8) & 0xFF;
    printf("  power: low power mode %s, on battery %s, thermal %s\n", (state & INGOT_POWER_LOW_POWER_MODE) ? "yes" : "no",
           (state & INGOT_POWER_ON_BATTERY) ? "yes" : "no", level < 4 ? thermal[level] : "?");
    CHECK(level <= 3 && (state & ~0xFF03u) == 0, "power state bits 0x%x", state);
    CHECK(ingot_power_state() == state, "cached power state is stable");
}

static void test_capture(void *device) {
    char path[512];
    @autoreleasepool {
        snprintf(path, sizeof(path), "%s/ingot-smoke-%d.gputrace", NSTemporaryDirectory().UTF8String, getpid());
    }
    int32_t started = ingot_capture_begin(device, path);
    if (getenv("MTL_CAPTURE_ENABLED") == NULL) {
        CHECK(started == 0 && strstr(ingot_last_error(), "MTL_CAPTURE_ENABLED") != NULL, "capture refused without MTL_CAPTURE_ENABLED");
    } else {
        CHECK(started == 1, "capture begin: %s", ingot_last_error());
        void *cmd = ingot_cmd_create(device, "captured");
        submit_and_wait(device, cmd);
        ingot_capture_end();
        @autoreleasepool {
            [[NSFileManager defaultManager] removeItemAtPath:@(path) error:nil];
        }
    }
    ingot_capture_end();
}

// Device-level creation runs on the pipeline-compile workers: libraries, pipelines and resources from 8 threads at
// once, each with its own MSL source (so nothing is shared by accident), plus the shared utility-pipeline cache.
static void test_concurrent_creation(void *device) {
    __block int created = 0;
    dispatch_apply(8, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t worker) {
        char source[512];
        snprintf(source, sizeof(source),
                 "#include <metal_stdlib>\nusing namespace metal;\n"
                 "vertex float4 vs_%zu(uint vid [[vertex_id]]) { return float4(float(vid & 1), float(vid >> 1), 0.0, 1.0); }\n"
                 "fragment float4 fs_%zu() { return float4(%zu.0 / 8.0); }\n",
                 worker, worker, worker);
        char vs_name[32], fs_name[32];
        snprintf(vs_name, sizeof(vs_name), "vs_%zu", worker);
        snprintf(fs_name, sizeof(fs_name), "fs_%zu", worker);
        void *library = ingot_library_create(device, source, "concurrent");
        void *vs = library != NULL ? ingot_library_function(library, vs_name) : NULL;
        void *fs = library != NULL ? ingot_library_function(library, fs_name) : NULL;
        IngotPipelineDesc desc;
        memset(&desc, 0, sizeof(desc));
        desc.vertex_function = vs;
        desc.fragment_function = fs;
        desc.colors[0].pixel_format = worker % 2 == 0 ? MTLPixelFormatRGBA8Unorm : MTLPixelFormatRGBA16Float;
        desc.colors[0].write_mask = MTLColorWriteMaskAll;
        desc.depth_pixel_format = worker % 4 < 2 ? MTLPixelFormatDepth32Float : MTLPixelFormatInvalid;
        desc.input_primitive_topology = MTLPrimitiveTopologyClassTriangle;
        void *pipeline = vs != NULL && fs != NULL ? ingot_pipeline_create(device, &desc) : NULL;
        void *buffer = ingot_buffer_create(device, 1024 * (worker + 1), SHARED, NULL, NULL);
        void *texture = ingot_texture_create(device, MTLTextureType2D, MTLPixelFormatRGBA8Unorm, 32, 32, 6, MTLTextureUsageShaderRead,
                                             PRIVATE_TEXTURE, NULL);
        void *sampler = ingot_sampler_create(device, 0, 0, 1, 1, 2, 4, 0, 1000, NULL);
        void *depth_state = ingot_depth_stencil_create(device, (uint32_t)(worker % 8), worker % 2);
        if (pipeline != NULL && buffer != NULL && texture != NULL && sampler != NULL && depth_state != NULL) {
            __atomic_add_fetch(&created, 1, __ATOMIC_RELAXED);
        } else {
            fprintf(stderr, "    worker %zu: %s\n", worker, ingot_last_error());
        }
        void *handles[] = {depth_state, sampler, texture, buffer, pipeline, fs, vs, library};
        for (size_t i = 0; i < sizeof(handles) / sizeof(handles[0]); i++) {
            ingot_release(handles[i]);
        }
    });
    CHECK(created == 8, "concurrent creation: %d of 8 workers succeeded", created);
}

static void run_suite(uint32_t flags) {
    printf("suite flags=0x%x\n", flags);
    serial = 0;
    void *device = ingot_device_create(flags);
    CHECK(device != NULL, "device create: %s", ingot_last_error());
    if (device == NULL) {
        return;
    }
    Scene *scene = calloc(1, sizeof(Scene));
    test_queries(device);
    test_buffers(device);
    test_textures(device);
    test_render(device, scene);
    test_serials(device);
    test_surface(device, scene);
    test_capture(device);
    test_concurrent_creation(device);
    CHECK(ingot_device_error_code(device) == 0, "device error: %s", ingot_device_error_message(device));
    ingot_release(scene->color);
    free(scene);
    ingot_device_destroy(device);
}

// No autorelease pool here on purpose: see test_surface.
int main(void) {
    // run.sh matches OBJC_DEBUG_MISSING_POOLS reports against this thread (Metal's own threads report some too).
    printf("calling thread %p\n", (void *)pthread_self());
    CHECK(ingot_abi_version() == INGOT_ABI_VERSION, "ABI version");
    CHECK(ingot_last_error() != NULL, "last error is never null");
    ingot_release(NULL);
    run_suite(0);
    run_suite(INGOT_DEVICE_FLAG_DEBUG_LABELS | INGOT_DEVICE_FLAG_RETAIN_REFS);
    printf("timing\n");
    test_wait_until();
    test_power();
    if (failures != 0) {
        printf("FAILED: %d check(s)\n", failures);
        return 1;
    }
    printf("OK\n");
    return 0;
}
