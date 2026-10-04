// Fused utilities that encode their own render pass: region clears and the present blit. Their pipelines come from
// one internal MSL library per device, compiled on first use and cached by attachment formats.
#import "ingot.h"
#import "ingot_internal.h"

static NSString *const util_source = @
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "\n"
    "// Full-screen triangle; the viewport and scissor select the pixels that are written.\n"
    "vertex float4 ingot_fullscreen_vs(uint vid [[vertex_id]]) {\n"
    "    float2 uv = float2((vid << 1) & 2, vid & 2);\n"
    "    return float4(uv * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n"
    "\n"
    "struct ClearParams { float4 color; float depth; };\n"
    "struct ColorDepthOut { float4 color [[color(0)]]; float depth [[depth(any)]]; };\n"
    "struct DepthOut { float depth [[depth(any)]]; };\n"
    "\n"
    "fragment ColorDepthOut ingot_clear_color_depth_fs(constant ClearParams &p [[buffer(0)]]) { return { p.color, p.depth }; }\n"
    "fragment float4 ingot_clear_color_fs(constant ClearParams &p [[buffer(0)]]) { return p.color; }\n"
    "fragment DepthOut ingot_clear_depth_fs(constant ClearParams &p [[buffer(0)]]) { return { p.depth }; }\n"
    "\n"
    "struct BlitParams { uint flip; uint height; };\n"
    "\n"
    "// Copies the source texel under each drawable pixel; with flip, row y reads row height - 1 - y.\n"
    "fragment float4 ingot_blit_fs(float4 position [[position]], texture2d<float, access::read> source [[texture(0)]],\n"
    "                              constant BlitParams &p [[buffer(0)]]) {\n"
    "    uint2 texel = uint2(position.xy);\n"
    "    if (p.flip != 0) {\n"
    "        texel.y = p.height - 1 - texel.y;\n"
    "    }\n"
    "    return source.read(texel);\n"
    "}\n";

// Mirrors ClearParams in util_source (32 bytes with MSL's float4 alignment).
typedef struct ClearParams {
    float color[4];
    float depth;
    float _pad[3];
} ClearParams;

// Mirrors BlitParams in util_source.
typedef struct BlitParams {
    uint32_t flip;
    uint32_t height;
} BlitParams;

static NSString *clear_fragment(MTLPixelFormat color_format, MTLPixelFormat depth_format) {
    if (color_format != MTLPixelFormatInvalid && depth_format != MTLPixelFormatInvalid) {
        return @"ingot_clear_color_depth_fs";
    }
    return color_format != MTLPixelFormatInvalid ? @"ingot_clear_color_fs" : @"ingot_clear_depth_fs";
}

// Caller holds util_lock.
static id<MTLRenderPipelineState> create_util_pipeline(IngotDevice *device, IngotUtilKind kind, MTLPixelFormat color_format,
                                                       MTLPixelFormat depth_format) {
    NSError *error = nil;
    if (device->util_library == nil) {
        device->util_library = [device->mtl newLibraryWithSource:util_source options:ingot_compile_options() error:&error];
        if (device->util_library == nil) {
            ingot_set_error([NSString stringWithFormat:@"internal utility shaders failed to compile: %@", error.localizedDescription]);
            return nil;
        }
    }
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.vertexFunction = [device->util_library newFunctionWithName:@"ingot_fullscreen_vs"];
    NSString *fragment = kind == IngotUtilKindBlit ? @"ingot_blit_fs" : clear_fragment(color_format, depth_format);
    desc.fragmentFunction = [device->util_library newFunctionWithName:fragment];
    desc.colorAttachments[0].pixelFormat = color_format;
    desc.depthAttachmentPixelFormat = depth_format;
    if (device->labels) {
        desc.label = kind == IngotUtilKindBlit ? @"Ingot present blit" : @"Ingot clear region";
    }
    id<MTLRenderPipelineState> state = [device->mtl newRenderPipelineStateWithDescriptor:desc error:&error];
    if (state == nil) {
        ingot_set_error([NSString stringWithFormat:@"internal %@ pipeline (color %lu, depth %lu) failed: %@", fragment,
                                                   (unsigned long)color_format, (unsigned long)depth_format, error.localizedDescription]);
    }
    return state;
}

id<MTLRenderPipelineState> ingot_util_pipeline(IngotDevice *device, IngotUtilKind kind, MTLPixelFormat color_format,
                                               MTLPixelFormat depth_format) {
    // Pixel formats are well below 2^20, so the key is exact (and a tagged-pointer NSNumber, no allocation).
    NSNumber *key = @(((uint64_t)kind << 40) | ((uint64_t)color_format << 20) | (uint64_t)depth_format);
    os_unfair_lock_lock(&device->util_lock);
    id<MTLRenderPipelineState> state = device->util_pipelines[key];
    if (state == nil) {
        state = create_util_pipeline(device, kind, color_format, depth_format);
        if (state != nil) {
            device->util_pipelines[key] = state;
        }
    }
    os_unfair_lock_unlock(&device->util_lock);
    return state;
}

static id<MTLDepthStencilState> util_depth_write(IngotDevice *device) {
    os_unfair_lock_lock(&device->util_lock);
    if (device->util_depth_write == nil) {
        MTLDepthStencilDescriptor *desc = [MTLDepthStencilDescriptor new];
        desc.depthCompareFunction = MTLCompareFunctionAlways;
        desc.depthWriteEnabled = YES;
        device->util_depth_write = [device->mtl newDepthStencilStateWithDescriptor:desc];
    }
    id<MTLDepthStencilState> state = device->util_depth_write;
    os_unfair_lock_unlock(&device->util_lock);
    return state;
}

INGOT_EXPORT void ingot_util_clear_region(void *device, void *cmd, void *color_texture, void *depth_texture, uint32_t level,
                                          uint32_t x, uint32_t y, uint32_t width, uint32_t height,
                                          float r, float g, float b, float a, float depth, uint32_t flags) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        // Only the attachments being cleared are bound: an attachment that is neither written nor needed would
        // still be loaded and stored by the pass.
        __unsafe_unretained id<MTLTexture> color = (flags & 1u) != 0 ? INGOT_OBJ(id<MTLTexture>, color_texture) : nil;
        __unsafe_unretained id<MTLTexture> depth_target = (flags & 2u) != 0 ? INGOT_OBJ(id<MTLTexture>, depth_texture) : nil;
        if (color == nil && depth_target == nil) {
            return;
        }

        // Clip the rectangle to the mip level: scissor rects must lie inside the pass, which is as large as its
        // smallest attachment.
        NSUInteger level_width = NSUIntegerMax;
        NSUInteger level_height = NSUIntegerMax;
        if (color != nil) {
            level_width = MAX(color.width >> level, (NSUInteger)1);
            level_height = MAX(color.height >> level, (NSUInteger)1);
        }
        if (depth_target != nil) {
            level_width = MIN(level_width, MAX(depth_target.width >> level, (NSUInteger)1));
            level_height = MIN(level_height, MAX(depth_target.height >> level, (NSUInteger)1));
        }
        if (x >= level_width || y >= level_height || width == 0 || height == 0) {
            return;
        }
        MTLScissorRect scissor = {x, y, MIN((NSUInteger)width, level_width - x), MIN((NSUInteger)height, level_height - y)};

        MTLPixelFormat color_format = color != nil ? color.pixelFormat : MTLPixelFormatInvalid;
        MTLPixelFormat depth_format = depth_target != nil ? depth_target.pixelFormat : MTLPixelFormatInvalid;
        id<MTLRenderPipelineState> pipeline = ingot_util_pipeline(dev, IngotUtilKindClear, color_format, depth_format);
        if (pipeline == nil) {
            return;
        }

        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor new];
        if (color != nil) {
            pass.colorAttachments[0].texture = color;
            pass.colorAttachments[0].level = level;
            pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        }
        if (depth_target != nil) {
            pass.depthAttachment.texture = depth_target;
            pass.depthAttachment.level = level;
            pass.depthAttachment.loadAction = MTLLoadActionLoad;
            pass.depthAttachment.storeAction = MTLStoreActionStore;
        }
        id<MTLRenderCommandEncoder> encoder = [INGOT_OBJ(id<MTLCommandBuffer>, cmd) renderCommandEncoderWithDescriptor:pass];
        if (encoder == nil) {
            ingot_set_error_cstr("renderCommandEncoderWithDescriptor failed (clear region)");
            return;
        }
        if (dev->labels) {
            encoder.label = @"Ingot clear region";
        }
        [encoder setRenderPipelineState:pipeline];
        if (depth_target != nil) {
            [encoder setDepthStencilState:util_depth_write(dev)];
        }
        [encoder setScissorRect:scissor];
        ClearParams params = {{r, g, b, a}, depth, {0, 0, 0}};
        [encoder setFragmentBytes:&params length:sizeof(params) atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [encoder endEncoding];
    }
}

INGOT_EXPORT void ingot_surface_blit(void *surface, void *cmd, void *drawable, void *src_texture, uint32_t src_width,
                                     uint32_t src_height, uint32_t flip_y) {
    @autoreleasepool {
        __unsafe_unretained IngotSurface *surf = INGOT_OBJ(IngotSurface *, surface);
        __unsafe_unretained id<CAMetalDrawable> target = INGOT_OBJ(id<CAMetalDrawable>, drawable);
        __unsafe_unretained id<MTLCommandBuffer> command_buffer = INGOT_OBJ(id<MTLCommandBuffer>, cmd);
        id<MTLTexture> texture = target.texture;

        // Copy min(source, drawable) per axis, anchored at the top-left like both vanilla presenters; the clear
        // load action blacks out everything else at no extra cost on a tile-based GPU.
        NSUInteger width = MIN((NSUInteger)src_width, texture.width);
        NSUInteger height = MIN((NSUInteger)src_height, texture.height);
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor new];
        pass.colorAttachments[0].texture = texture;
        pass.colorAttachments[0].loadAction = MTLLoadActionClear;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
        id<MTLRenderCommandEncoder> encoder = [command_buffer renderCommandEncoderWithDescriptor:pass];
        if (encoder == nil) {
            ingot_set_error_cstr("renderCommandEncoderWithDescriptor failed (present blit)");
            return;
        }
        if (surf->device->labels) {
            encoder.label = @"Ingot present blit";
        }
        if (src_texture != NULL && width > 0 && height > 0) {
            [encoder setRenderPipelineState:surf->blit_pipeline];
            [encoder setViewport:(MTLViewport){0, 0, (double)width, (double)height, 0, 1}];
            BlitParams params = {flip_y != 0, (uint32_t)height};
            [encoder setFragmentBytes:&params length:sizeof(params) atIndex:0];
            [encoder setFragmentTexture:INGOT_OBJ(id<MTLTexture>, src_texture) atIndex:0];
            [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        }
        [encoder endEncoding];
        [command_buffer presentDrawable:target];
    }
}
