// Compiles every SPIRV-Cross generated MSL file at runtime and builds an MTLRenderPipelineState
// for each vanilla pipeline, using the CreateInfo-derived vertex layout / color formats / blend state.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static MTLVertexFormat vfmt(NSString *f) {
    NSDictionary *m = @{
        @"RGB32_FLOAT": @(MTLVertexFormatFloat3), @"RGBA8_UNORM": @(MTLVertexFormatUChar4Normalized),
        @"RG32_FLOAT": @(MTLVertexFormatFloat2), @"RG16_SINT": @(MTLVertexFormatShort2),
        @"RGBA8_SNORM": @(MTLVertexFormatChar4Normalized), @"R32_FLOAT": @(MTLVertexFormatFloat),
        @"RGB32_SINT": @(MTLVertexFormatInt3)};
    NSNumber *n = m[f];
    if (!n) { fprintf(stderr, "unmapped vertex format %s\n", f.UTF8String); return MTLVertexFormatInvalid; }
    return (MTLVertexFormat)n.unsignedIntegerValue;
}

static MTLPixelFormat pfmt(NSString *f) {
    NSDictionary *m = @{@"RGBA8_UNORM": @(MTLPixelFormatRGBA8Unorm), @"RGBA16_FLOAT": @(MTLPixelFormatRGBA16Float),
                        @"RGBA32_FLOAT": @(MTLPixelFormatRGBA32Float)};
    NSNumber *n = m[f];
    if (!n) { fprintf(stderr, "unmapped pixel format %s\n", f.UTF8String); return MTLPixelFormatInvalid; }
    return (MTLPixelFormat)n.unsignedIntegerValue;
}

static MTLBlendFactor bfac(NSString *f) {
    NSDictionary *m = @{@"ZERO": @(MTLBlendFactorZero), @"ONE": @(MTLBlendFactorOne),
        @"SRC_COLOR": @(MTLBlendFactorSourceColor), @"ONE_MINUS_SRC_COLOR": @(MTLBlendFactorOneMinusSourceColor),
        @"SRC_ALPHA": @(MTLBlendFactorSourceAlpha), @"ONE_MINUS_SRC_ALPHA": @(MTLBlendFactorOneMinusSourceAlpha),
        @"DST_COLOR": @(MTLBlendFactorDestinationColor), @"ONE_MINUS_DST_COLOR": @(MTLBlendFactorOneMinusDestinationColor),
        @"DST_ALPHA": @(MTLBlendFactorDestinationAlpha), @"ONE_MINUS_DST_ALPHA": @(MTLBlendFactorOneMinusDestinationAlpha),
        @"SRC_ALPHA_SATURATE": @(MTLBlendFactorSourceAlphaSaturated),
        @"CONSTANT_COLOR": @(MTLBlendFactorBlendColor), @"ONE_MINUS_CONSTANT_COLOR": @(MTLBlendFactorOneMinusBlendColor),
        @"CONSTANT_ALPHA": @(MTLBlendFactorBlendAlpha), @"ONE_MINUS_CONSTANT_ALPHA": @(MTLBlendFactorOneMinusBlendAlpha)};
    return (MTLBlendFactor)[m[f] unsignedIntegerValue];
}

static MTLBlendOperation bop(NSString *f) {
    NSDictionary *m = @{@"ADD": @(MTLBlendOperationAdd), @"SUBTRACT": @(MTLBlendOperationSubtract),
        @"REVERSE_SUBTRACT": @(MTLBlendOperationReverseSubtract), @"MIN": @(MTLBlendOperationMin), @"MAX": @(MTLBlendOperationMax)};
    return (MTLBlendOperation)[m[f] unsignedIntegerValue];
}

static MTLPrimitiveTopologyClass topoClass(NSString *t) {
    if ([t isEqualToString:@"POINTS"]) return MTLPrimitiveTopologyClassPoint;
    if ([t isEqualToString:@"DEBUG_LINES"] || [t isEqualToString:@"DEBUG_LINE_STRIP"]) return MTLPrimitiveTopologyClassLine;
    return MTLPrimitiveTopologyClassTriangle;
}

static NSString *bindingTypeName(MTLBindingType t) {
    switch (t) {
        case MTLBindingTypeBuffer: return @"buffer";
        case MTLBindingTypeTexture: return @"texture";
        case MTLBindingTypeSampler: return @"sampler";
        default: return [NSString stringWithFormat:@"type%ld", (long)t];
    }
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        NSString *dir = [NSString stringWithUTF8String:argv[1]];
        NSUInteger langVersion = (NSUInteger)strtoul(argv[2], NULL, 0); // e.g. 0x20004 for MTLLanguageVersion2_4
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        printf("device: %s\n", device.name.UTF8String);
        NSData *jd = [NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"pipelines.json"]];
        NSArray *pipes = [NSJSONSerialization JSONObjectWithData:jd options:0 error:nil];
        NSMutableDictionary *libCache = [NSMutableDictionary dictionary];
        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.languageVersion = (MTLLanguageVersion)langVersion;
        int okCount = 0, failCount = 0;
        NSMutableArray *report = [NSMutableArray array];
        double totalCompileMs = 0, totalPsoMs = 0;
        for (NSDictionary *p in pipes) {
            NSDictionary *ci = p[@"createInfo"];
            NSString *key = p[@"key"];
            NSMutableDictionary *rep = [NSMutableDictionary dictionary];
            rep[@"key"] = key;
            id<MTLFunction> vfn = nil, ffn = nil;
            BOOL failed = NO;
            for (NSDictionary *s in ci[@"shaders"]) {
                NSString *file = s[@"msl"][@"file"];
                NSString *src = [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:[@"msl/" stringByAppendingString:file]] encoding:NSUTF8StringEncoding error:nil];
                NSError *err = nil;
                NSDate *t0 = [NSDate date];
                id<MTLLibrary> lib = [device newLibraryWithSource:src options:opts error:&err];
                totalCompileMs += -[t0 timeIntervalSinceNow] * 1000.0;
                if (!lib) {
                    printf("LIB FAIL %s %s\n%s\n", key.UTF8String, file.UTF8String, err.localizedDescription.UTF8String);
                    failed = YES; continue;
                } else if (err) {
                    // warnings
                    rep[[@"warn_" stringByAppendingString:s[@"stage"]]] = err.localizedDescription;
                }
                id<MTLFunction> fn = [lib newFunctionWithName:s[@"msl"][@"entry"]];
                if ([s[@"stage"] isEqualToString:@"VERTEX"]) vfn = fn; else ffn = fn;
            }
            if (failed) { failCount++; continue; }
            MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
            d.label = key;
            d.vertexFunction = vfn;
            d.fragmentFunction = ffn;
            MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
            for (NSDictionary *a in ci[@"attribBindings"]) {
                NSUInteger loc = [a[@"location"] unsignedIntegerValue];
                vd.attributes[loc].format = vfmt(a[@"format"]);
                vd.attributes[loc].offset = [a[@"offset"] unsignedIntegerValue];
                vd.attributes[loc].bufferIndex = 30 - [a[@"slot"] unsignedIntegerValue];
            }
            for (NSString *vb in ci[@"vertexBuffers"]) {
                NSArray *parts = [vb componentsSeparatedByString:@" "];
                NSUInteger slot = [parts[0] integerValue];
                NSUInteger stride = [[parts[1] substringFromIndex:7] integerValue];
                NSUInteger step = [[parts[2] substringFromIndex:5] integerValue];
                vd.layouts[30 - slot].stride = stride;
                if (step > 0) { vd.layouts[30 - slot].stepFunction = MTLVertexStepFunctionPerInstance; vd.layouts[30 - slot].stepRate = step; }
                else { vd.layouts[30 - slot].stepFunction = MTLVertexStepFunctionPerVertex; vd.layouts[30 - slot].stepRate = 1; }
            }
            d.vertexDescriptor = vd;
            NSArray *cts = ci[@"colorTargets"];
            for (NSUInteger i = 0; i < cts.count; i++) {
                id c = cts[i];
                if (c == [NSNull null]) continue;
                MTLRenderPipelineColorAttachmentDescriptor *ca = d.colorAttachments[i];
                ca.pixelFormat = pfmt(c[@"format"]);
                int wm = [c[@"writeMask"] intValue];
                MTLColorWriteMask m = 0;
                if (wm & 1) m |= MTLColorWriteMaskRed;
                if (wm & 2) m |= MTLColorWriteMaskGreen;
                if (wm & 4) m |= MTLColorWriteMaskBlue;
                if (wm & 8) m |= MTLColorWriteMaskAlpha;
                ca.writeMask = m;
                if (c[@"srcColor"]) {
                    ca.blendingEnabled = YES;
                    ca.sourceRGBBlendFactor = bfac(c[@"srcColor"]);
                    ca.destinationRGBBlendFactor = bfac(c[@"dstColor"]);
                    ca.rgbBlendOperation = bop(c[@"colorOp"]);
                    ca.sourceAlphaBlendFactor = bfac(c[@"srcAlpha"]);
                    ca.destinationAlphaBlendFactor = bfac(c[@"dstAlpha"]);
                    ca.alphaBlendOperation = bop(c[@"alphaOp"]);
                }
            }
            d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
            d.inputPrimitiveTopology = topoClass(ci[@"topology"]);
            NSError *err = nil;
            MTLAutoreleasedRenderPipelineReflection refl = nil;
            NSDate *tp = [NSDate date];
            id<MTLRenderPipelineState> ps = [device newRenderPipelineStateWithDescriptor:d options:MTLPipelineOptionBindingInfo | MTLPipelineOptionBufferTypeInfo reflection:&refl error:&err];
            totalPsoMs += -[tp timeIntervalSinceNow] * 1000.0;
            if (!ps) {
                printf("PSO FAIL %s: %s\n", key.UTF8String, err.localizedDescription.UTF8String);
                failCount++;
                continue;
            }
            okCount++;
            NSMutableArray *vb = [NSMutableArray array], *fb = [NSMutableArray array];
            for (id<MTLBinding> b in refl.vertexBindings) {
                NSString *extra = @"";
                if (b.type == MTLBindingTypeBuffer) extra = [NSString stringWithFormat:@" size=%lu", (unsigned long)((id<MTLBufferBinding>)b).bufferDataSize];
                [vb addObject:[NSString stringWithFormat:@"%@ %@[%lu]%@%@", b.name, bindingTypeName(b.type), (unsigned long)b.index, extra, b.used ? @"" : @" (unused)"]];
            }
            for (id<MTLBinding> b in refl.fragmentBindings) {
                NSString *extra = @"";
                if (b.type == MTLBindingTypeBuffer) extra = [NSString stringWithFormat:@" size=%lu", (unsigned long)((id<MTLBufferBinding>)b).bufferDataSize];
                [fb addObject:[NSString stringWithFormat:@"%@ %@[%lu]%@%@", b.name, bindingTypeName(b.type), (unsigned long)b.index, extra, b.used ? @"" : @" (unused)"]];
            }
            rep[@"vertexBindings"] = vb;
            rep[@"fragmentBindings"] = fb;
            [report addObject:rep];
        }
        printf("pipelines ok=%d fail=%d, total MSL compile %.0f ms, total PSO creation %.0f ms\n", okCount, failCount, totalCompileMs, totalPsoMs);
        NSData *out = [NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted error:nil];
        [out writeToFile:[dir stringByAppendingPathComponent:@"metal_reflection.json"] atomically:YES];
    }
    return 0;
}
