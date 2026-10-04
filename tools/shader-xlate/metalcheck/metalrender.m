// metalrender: runtime check of the coordinate-system conventions the Ingot backend relies on.
// Draws the translated ingot_test:pipeline/orientation (a GL-convention CCW triangle covering the
// lower-left half of clip space) into a 16x16 RGBA8 texture and reads it back.
//
// Expected for "GL memory layout" (what the Vulkan backend produces, see the research doc):
//   * memory row 0 (first row in the buffer) is the GL *bottom* row: it is almost fully covered,
//     and the last memory row has only column 0 covered;
//   * each covered pixel has G == its memory row index (gl_FragCoord.y == row + 0.5), R == column;
//   * B == 64 (gl_FragCoord.z == 0.25), A == 255 (texture v=0 reads the first uploaded row);
//   * with cullMode=Back the triangle survives only with frontFacingWinding = Clockwise.
//
// usage: metalrender <out-dir> <msl-version>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static const int W = 16, H = 16;

static NSDictionary *findPipeline(NSArray *pipelines, NSString *name) {
    for (NSDictionary *p in pipelines) if ([p[@"name"] isEqualToString:name]) return p;
    return nil;
}

static id<MTLRenderPipelineState> buildPso(id<MTLDevice> dev, NSString *dir, NSDictionary *p, MTLCompileOptions *opts,
                                           MTLVertexDescriptor *vd, MTLPixelFormat color, MTLPixelFormat depth) {
    NSError *err = nil;
    NSString *vsrc = [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:p[@"vert"][@"file"]] encoding:NSUTF8StringEncoding error:nil];
    NSString *fsrc = [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:p[@"frag"][@"file"]] encoding:NSUTF8StringEncoding error:nil];
    id<MTLLibrary> vl = [dev newLibraryWithSource:vsrc options:opts error:&err];
    id<MTLLibrary> fl = [dev newLibraryWithSource:fsrc options:opts error:&err];
    if (!vl || !fl) { fprintf(stderr, "compile failed: %s\n", err.localizedDescription.UTF8String); return nil; }
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
    d.vertexFunction = [vl newFunctionWithName:p[@"vert"][@"entry"]];
    d.fragmentFunction = [fl newFunctionWithName:p[@"frag"][@"entry"]];
    d.vertexDescriptor = vd;
    d.colorAttachments[0].pixelFormat = color;
    d.depthAttachmentPixelFormat = depth;
    id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:d error:&err];
    if (!pso) fprintf(stderr, "pso failed: %s\n", err.localizedDescription.UTF8String);
    return pso;
}

static NSDictionary *slotNamed(NSDictionary *p, NSString *name) {
    for (NSDictionary *s in p[@"slots"]) if ([s[@"name"] isEqualToString:name]) return s;
    return nil;
}

// Test 2: the full argument-table layout on a real draw: vertex buffer at buffer(30), push constants at
// buffer(14) in both stages via set*Bytes, a UBO at its compact buffer index, texture_buffer<float> (VS) and
// texture_buffer<uint> (FS) backed by MTLBuffers, and a combined image sampler.
static int testBindings(id<MTLDevice> dev, id<MTLCommandQueue> q, NSString *dir, NSArray *pipelines, MTLCompileOptions *opts) {
    NSDictionary *p = findPipeline(pipelines, @"ingot_test:pipeline/push_constants_texel_buffers");
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    NSDictionary *vb = p[@"vertexBuffers"][0];
    NSUInteger vbi = [vb[@"mtlBufferIndex"] unsignedIntegerValue];
    vd.layouts[vbi].stride = [vb[@"stride"] unsignedIntegerValue];
    NSDictionary *fmts = @{ @"RGB32_FLOAT": @(MTLVertexFormatFloat3), @"RG32_FLOAT": @(MTLVertexFormatFloat2), @"RGBA8_UNORM": @(MTLVertexFormatUChar4Normalized) };
    for (NSDictionary *a in p[@"attributes"]) {
        NSUInteger loc = [a[@"location"] unsignedIntegerValue];
        vd.attributes[loc].format = [fmts[a[@"format"]] unsignedIntegerValue];
        vd.attributes[loc].offset = [a[@"offset"] unsignedIntegerValue];
        vd.attributes[loc].bufferIndex = vbi;
    }
    id<MTLRenderPipelineState> pso = buildPso(dev, dir, p, opts, vd, MTLPixelFormatRGBA8Unorm, MTLPixelFormatDepth32Float);
    if (!pso) return 1;

    // full-screen triangle, white vertex color
    struct { float pos[3]; float uv[2]; uint8_t color[4]; } verts[3] = {
        {{-1, -1, 0.5f}, {0, 0}, {255, 255, 255, 255}}, {{3, -1, 0.5f}, {2, 0}, {255, 255, 255, 255}}, {{-1, 3, 0.5f}, {0, 2}, {255, 255, 255, 255}} };
    id<MTLBuffer> vbuf = [dev newBufferWithBytes:verts length:sizeof(verts) options:MTLResourceStorageModeShared];
    struct { float transform[16]; float tint[4]; int32_t mode; int32_t pad[3]; } pc = {
        {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}, {1, 1, 1, 1}, 1, {0, 0, 0} };
    uint8_t globals[64] = {0};
    // texel buffers: Lut = 16 x RGBA32F (1, .5, .25, 1); Ids = 16 x R32Uint, Ids[0] = 128
    NSUInteger lutAlign = [dev minimumLinearTextureAlignmentForPixelFormat:MTLPixelFormatRGBA32Float];
    float lut[64]; for (int i = 0; i < 16; i++) { lut[i*4] = 1; lut[i*4+1] = .5f; lut[i*4+2] = .25f; lut[i*4+3] = 1; }
    id<MTLBuffer> lutBuf = [dev newBufferWithBytes:lut length:sizeof(lut) options:MTLResourceStorageModeShared];
    MTLTextureDescriptor *lutDesc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:MTLPixelFormatRGBA32Float width:16 resourceOptions:MTLResourceStorageModeShared usage:MTLTextureUsageShaderRead];
    id<MTLTexture> lutTex = [lutBuf newTextureWithDescriptor:lutDesc offset:0 bytesPerRow:16 * 16];
    uint32_t ids[16] = {128};
    id<MTLBuffer> idsBuf = [dev newBufferWithBytes:ids length:sizeof(ids) options:MTLResourceStorageModeShared];
    MTLTextureDescriptor *idsDesc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:MTLPixelFormatR32Uint width:16 resourceOptions:MTLResourceStorageModeShared usage:MTLTextureUsageShaderRead];
    id<MTLTexture> idsTex = [idsBuf newTextureWithDescriptor:idsDesc offset:0 bytesPerRow:16 * 4];
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:2 height:2 mipmapped:NO];
    id<MTLTexture> white = [dev newTextureWithDescriptor:td];
    uint8_t w[16]; memset(w, 255, sizeof(w));
    [white replaceRegion:MTLRegionMake2D(0, 0, 2, 2) mipmapLevel:0 withBytes:w bytesPerRow:8];
    id<MTLSamplerState> smp = [dev newSamplerStateWithDescriptor:[MTLSamplerDescriptor new]];

    MTLTextureDescriptor *rd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
    rd.usage = MTLTextureUsageRenderTarget; rd.storageMode = MTLStorageModePrivate;
    id<MTLTexture> rt = [dev newTextureWithDescriptor:rd];
    MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO];
    dd.usage = MTLTextureUsageRenderTarget; dd.storageMode = MTLStorageModePrivate;
    id<MTLTexture> depth = [dev newTextureWithDescriptor:dd];
    MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
    dsd.depthCompareFunction = MTLCompareFunctionGreaterEqual; dsd.depthWriteEnabled = YES;
    id<MTLDepthStencilState> dss = [dev newDepthStencilStateWithDescriptor:dsd];
    id<MTLBuffer> readback = [dev newBufferWithLength:W * H * 4 options:MTLResourceStorageModeShared];

    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = rt; rp.colorAttachments[0].loadAction = MTLLoadActionClear; rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    rp.depthAttachment.texture = depth; rp.depthAttachment.loadAction = MTLLoadActionClear; rp.depthAttachment.clearDepth = 0.0; rp.depthAttachment.storeAction = MTLStoreActionDontCare;
    id<MTLCommandBuffer> cb = [q commandBuffer];
    id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
    [enc setRenderPipelineState:pso];
    [enc setDepthStencilState:dss];
    [enc setFrontFacingWinding:MTLWindingClockwise];
    [enc setCullMode:MTLCullModeBack];
    [enc setVertexBuffer:vbuf offset:0 atIndex:vbi];
    [enc setVertexBytes:&pc length:sizeof(pc) atIndex:14];
    [enc setFragmentBytes:&pc length:sizeof(pc) atIndex:14];
    NSDictionary *g = slotNamed(p, @"Globals"), *l = slotNamed(p, @"Lut"), *s0 = slotNamed(p, @"Sampler0"), *idsSlot = slotNamed(p, @"Ids");
    [enc setVertexBytes:globals length:sizeof(globals) atIndex:[g[@"buffer"] unsignedIntegerValue]];
    [enc setVertexTexture:lutTex atIndex:[l[@"texture"] unsignedIntegerValue]];
    [enc setFragmentTexture:white atIndex:[s0[@"texture"] unsignedIntegerValue]];
    [enc setFragmentSamplerState:smp atIndex:[s0[@"sampler"] unsignedIntegerValue]];
    [enc setFragmentTexture:idsTex atIndex:[idsSlot[@"texture"] unsignedIntegerValue]];
    // draw winding: the full-screen triangle above is CCW in GL terms
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:rt sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(W, H, 1)
                 toBuffer:readback destinationOffset:0 destinationBytesPerRow:W * 4 destinationBytesPerImage:W * H * 4];
    [blit endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    const uint8_t *px = readback.contents;
    int bad = 0;
    for (int i = 0; i < W * H; i++) {
        const uint8_t *c = px + i * 4;
        if (abs(c[0] - 128) > 1 || abs(c[1] - 64) > 1 || abs(c[2] - 32) > 1 || c[3] != 255) bad++;
    }
    printf("bindings test (minimum linear texture alignment RGBA32Float = %lu): pixel[0] = (%d,%d,%d,%d), expected (128,64,32,255) everywhere: %s\n",
           (unsigned long)lutAlign, px[0], px[1], px[2], px[3], bad ? "FAIL" : "PASS");
    if (cb.error) printf("  command buffer error: %s\n", cb.error.localizedDescription.UTF8String);
    return bad ? 1 : 0;
}

// Test 3: vanilla blit_depth (screenquad + blit_depth): a Depth32Float texture bound to the translated
// texture2d<float> parameter, output through [[depth(any)]].
static int testDepthBlit(id<MTLDevice> dev, id<MTLCommandQueue> q, NSString *dir, NSArray *pipelines, MTLCompileOptions *opts) {
    NSDictionary *p = findPipeline(pipelines, @"minecraft:pipeline/blit_depth");
    id<MTLRenderPipelineState> pso = buildPso(dev, dir, p, opts, nil, MTLPixelFormatInvalid, MTLPixelFormatDepth32Float);
    if (!pso) return 1;
    MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO];
    dd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead; dd.storageMode = MTLStorageModePrivate;
    id<MTLTexture> srcDepth = [dev newTextureWithDescriptor:dd];
    id<MTLTexture> dstDepth = [dev newTextureWithDescriptor:dd];
    id<MTLBuffer> readback = [dev newBufferWithLength:W * H * 4 options:MTLResourceStorageModeShared];
    MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
    dsd.depthCompareFunction = MTLCompareFunctionAlways; dsd.depthWriteEnabled = YES;
    id<MTLDepthStencilState> dss = [dev newDepthStencilStateWithDescriptor:dsd];
    id<MTLCommandBuffer> cb = [q commandBuffer];
    // fill the source depth with a clear
    MTLRenderPassDescriptor *fill = [MTLRenderPassDescriptor renderPassDescriptor];
    fill.depthAttachment.texture = srcDepth; fill.depthAttachment.loadAction = MTLLoadActionClear; fill.depthAttachment.clearDepth = 0.375; fill.depthAttachment.storeAction = MTLStoreActionStore;
    [[cb renderCommandEncoderWithDescriptor:fill] endEncoding];
    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.depthAttachment.texture = dstDepth; rp.depthAttachment.loadAction = MTLLoadActionClear; rp.depthAttachment.clearDepth = 1.0; rp.depthAttachment.storeAction = MTLStoreActionStore;
    id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
    [enc setRenderPipelineState:pso];
    [enc setDepthStencilState:dss];
    NSDictionary *s = slotNamed(p, @"InSampler");
    [enc setFragmentTexture:srcDepth atIndex:[s[@"texture"] unsignedIntegerValue]];
    [enc setFragmentSamplerState:[dev newSamplerStateWithDescriptor:[MTLSamplerDescriptor new]] atIndex:[s[@"sampler"] unsignedIntegerValue]];
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:dstDepth sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(W, H, 1)
                 toBuffer:readback destinationOffset:0 destinationBytesPerRow:W * 4 destinationBytesPerImage:W * H * 4];
    [blit endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    const float *d = readback.contents;
    int bad = 0;
    for (int i = 0; i < W * H; i++) if (fabsf(d[i] - 0.375f) > 1e-6f) bad++;
    printf("depth blit test: depth[0] = %f, expected 0.375 everywhere: %s\n", d[0], bad ? "FAIL" : "PASS");
    if (cb.error) printf("  command buffer error: %s\n", cb.error.localizedDescription.UTF8String);
    return bad ? 1 : 0;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc < 3) { fprintf(stderr, "usage: metalrender <out-dir> <msl-version>\n"); return 2; }
        NSString *dir = [NSString stringWithUTF8String:argv[1]];
        int v = atoi(argv[2]);
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        NSArray *pipelines = [NSJSONSerialization JSONObjectWithData:[NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"pipelines.json"]] options:0 error:nil];
        NSDictionary *p = findPipeline(pipelines, @"ingot_test:pipeline/orientation");
        if (!p) { fprintf(stderr, "orientation pipeline missing\n"); return 2; }

        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.languageVersion = v >= 30000 ? MTLLanguageVersion3_0 : MTLLanguageVersion2_4;
        NSError *err = nil;
        NSString *vsrc = [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:p[@"vert"][@"file"]] encoding:NSUTF8StringEncoding error:nil];
        NSString *fsrc = [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:p[@"frag"][@"file"]] encoding:NSUTF8StringEncoding error:nil];
        id<MTLLibrary> vl = [dev newLibraryWithSource:vsrc options:opts error:&err];
        id<MTLLibrary> fl = [dev newLibraryWithSource:fsrc options:opts error:&err];
        if (!vl || !fl) { fprintf(stderr, "compile failed: %s\n", err.localizedDescription.UTF8String); return 1; }

        MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
        d.vertexFunction = [vl newFunctionWithName:p[@"vert"][@"entry"]];
        d.fragmentFunction = [fl newFunctionWithName:p[@"frag"][@"entry"]];
        d.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:d error:&err];
        if (!pso) { fprintf(stderr, "pso failed: %s\n", err.localizedDescription.UTF8String); return 1; }

        int texIndex = 0, smpIndex = 0;
        for (NSDictionary *s in p[@"slots"]) if ([s[@"name"] isEqualToString:@"Sampler0"]) { texIndex = [s[@"texture"] intValue]; smpIndex = [s[@"sampler"] intValue]; }

        // 2x2 source texture: first uploaded row (row 0) G=255, second row G=0.
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:2 height:2 mipmapped:NO];
        id<MTLTexture> src = [dev newTextureWithDescriptor:td];
        uint8_t texels[16] = { 0,255,0,255, 0,255,0,255, 0,0,0,255, 0,0,0,255 };
        [src replaceRegion:MTLRegionMake2D(0, 0, 2, 2) mipmapLevel:0 withBytes:texels bytesPerRow:8];
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        id<MTLSamplerState> smp = [dev newSamplerStateWithDescriptor:sd]; // nearest, clamp

        MTLTextureDescriptor *rd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
        rd.usage = MTLTextureUsageRenderTarget;
        rd.storageMode = MTLStorageModePrivate;
        id<MTLTexture> rt = [dev newTextureWithDescriptor:rd];
        id<MTLBuffer> readback = [dev newBufferWithLength:W * H * 4 options:MTLResourceStorageModeShared];
        id<MTLCommandQueue> q = [dev newCommandQueue];

        int failures = 0;
        // run 0: CW + cull back (expected: drawn, front-facing); run 1: CCW + cull back (expected: culled);
        // run 2: CCW + cull none (expected: drawn, gl_FrontFacing == false)
        MTLWinding windings[3] = { MTLWindingClockwise, MTLWindingCounterClockwise, MTLWindingCounterClockwise };
        MTLCullMode culls[3] = { MTLCullModeBack, MTLCullModeBack, MTLCullModeNone };
        for (int wi = 0; wi < 3; wi++) {
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = rt;
            rp.colorAttachments[0].loadAction = MTLLoadActionClear;
            rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
            rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            id<MTLCommandBuffer> cb = [q commandBuffer];
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:pso];
            [enc setCullMode:culls[wi]];
            [enc setFrontFacingWinding:windings[wi]];
            [enc setViewport:(MTLViewport){0, 0, W, H, 0, 1}]; // positive height, origin top-left: the default
            [enc setFragmentTexture:src atIndex:texIndex];
            [enc setFragmentSamplerState:smp atIndex:smpIndex];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
            [blit copyFromTexture:rt sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(W, H, 1)
                         toBuffer:readback destinationOffset:0 destinationBytesPerRow:W * 4 destinationBytesPerImage:W * H * 4];
            [blit endEncoding];
            [cb commit];
            [cb waitUntilCompleted];
            const uint8_t *px = readback.contents;
            printf("frontFacingWinding=%s, cullMode=%s (memory row 0 printed first; '#' = covered)\n",
                   windings[wi] == MTLWindingClockwise ? "Clockwise" : "CounterClockwise", culls[wi] == MTLCullModeBack ? "Back" : "None");
            int expectB = wi == 0 ? 191 : 64; // 0.25 depth, +0.5 when gl_FrontFacing
            int covered = 0, rowMismatch = 0, colMismatch = 0, depthMismatch = 0, texMismatch = 0;
            for (int y = 0; y < H; y++) {
                printf("  row %2d ", y);
                for (int x = 0; x < W; x++) {
                    const uint8_t *c = px + (y * W + x) * 4;
                    BOOL on = c[3] != 0 || c[0] != 0 || c[1] != 0 || c[2] != 0;
                    putchar(on ? '#' : '.');
                    if (on) {
                        covered++;
                        if (c[0] != x) colMismatch++;
                        if (c[1] != y) rowMismatch++;
                        if (c[2] != expectB) depthMismatch++;
                        if (c[3] != 255) texMismatch++;
                    }
                }
                putchar('\n');
            }
            const uint8_t *r0 = px, *rl = px + (H - 1) * W * 4;
            int row0 = 0, rowLast = 0;
            for (int x = 0; x < W; x++) { row0 += r0[x * 4 + 3] != 0; rowLast += rl[x * 4 + 3] != 0; }
            printf("  covered=%d row0=%d lastRow=%d  mismatches: R(col)=%d G(row)=%d B(depth+facing)=%d A(tex v=0)=%d\n",
                   covered, row0, rowLast, colMismatch, rowMismatch, depthMismatch, texMismatch);
            if (wi == 0) {
                BOOL ok = covered > 0 && row0 > rowLast && !colMismatch && !rowMismatch && !depthMismatch && !texMismatch;
                printf("  => %s\n", ok ? "PASS: GL memory layout, gl_FragCoord == GL, depth 0..1, gl_FrontFacing true, texture v=0 == first row" : "FAIL");
                if (!ok) failures++;
            } else if (wi == 1) {
                printf("  => %s\n", covered == 0 ? "PASS: culled (GL-CCW front faces are Metal-Clockwise after the flip)" : "FAIL: not culled");
                if (covered != 0) failures++;
            } else {
                BOOL ok = covered > 0 && !depthMismatch;
                printf("  => %s\n", ok ? "PASS: with the wrong winding the same triangle reports gl_FrontFacing == false" : "FAIL");
                if (!ok) failures++;
            }
        }
        failures += testBindings(dev, q, dir, pipelines, opts);
        failures += testDepthBlit(dev, q, dir, pipelines, opts);
        return failures ? 1 : 0;
    }
}
