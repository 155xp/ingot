// metalcheck: compiles the MSL produced by ShaderXlate with the *runtime* Metal compiler
// (MTLDevice newLibraryWithSource:, i.e. exactly what the Ingot backend will do on a player's Mac,
// where the offline `metal` toolchain is not installed), then builds an MTLRenderPipelineState for
// every pipeline in pipelines.json so that vertex-descriptor <-> [[attribute(n)]], VS outputs <->
// FS inputs ([[user(locnN)]]), and fragment outputs <-> color attachment formats are all validated.
//
// usage: metalcheck <out-dir> <msl-version e.g. 20400|30000> [--parallel] [--preserve-invariance] [--safe-math]
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>

static double nowMs(void) {
    static mach_timebase_info_data_t tb;
    if (tb.denom == 0) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e6;
}

static MTLVertexFormat vertexFormat(NSString *f) {
    NSDictionary<NSString *, NSNumber *> *m = @{
        @"R32_FLOAT": @(MTLVertexFormatFloat), @"RG32_FLOAT": @(MTLVertexFormatFloat2),
        @"RGB32_FLOAT": @(MTLVertexFormatFloat3), @"RGBA32_FLOAT": @(MTLVertexFormatFloat4),
        @"R16_FLOAT": @(MTLVertexFormatHalf), @"RG16_FLOAT": @(MTLVertexFormatHalf2),
        @"RGB16_FLOAT": @(MTLVertexFormatHalf3), @"RGBA16_FLOAT": @(MTLVertexFormatHalf4),
        @"R8_UNORM": @(MTLVertexFormatUCharNormalized), @"RG8_UNORM": @(MTLVertexFormatUChar2Normalized),
        @"RGB8_UNORM": @(MTLVertexFormatUChar3Normalized), @"RGBA8_UNORM": @(MTLVertexFormatUChar4Normalized),
        @"R8_SNORM": @(MTLVertexFormatCharNormalized), @"RG8_SNORM": @(MTLVertexFormatChar2Normalized),
        @"RGB8_SNORM": @(MTLVertexFormatChar3Normalized), @"RGBA8_SNORM": @(MTLVertexFormatChar4Normalized),
        @"R8_UINT": @(MTLVertexFormatUChar), @"RG8_UINT": @(MTLVertexFormatUChar2),
        @"RGB8_UINT": @(MTLVertexFormatUChar3), @"RGBA8_UINT": @(MTLVertexFormatUChar4),
        @"R8_SINT": @(MTLVertexFormatChar), @"RG8_SINT": @(MTLVertexFormatChar2),
        @"RGB8_SINT": @(MTLVertexFormatChar3), @"RGBA8_SINT": @(MTLVertexFormatChar4),
        @"R16_UNORM": @(MTLVertexFormatUShortNormalized), @"RG16_UNORM": @(MTLVertexFormatUShort2Normalized),
        @"RGB16_UNORM": @(MTLVertexFormatUShort3Normalized), @"RGBA16_UNORM": @(MTLVertexFormatUShort4Normalized),
        @"R16_SNORM": @(MTLVertexFormatShortNormalized), @"RG16_SNORM": @(MTLVertexFormatShort2Normalized),
        @"RGB16_SNORM": @(MTLVertexFormatShort3Normalized), @"RGBA16_SNORM": @(MTLVertexFormatShort4Normalized),
        @"R16_UINT": @(MTLVertexFormatUShort), @"RG16_UINT": @(MTLVertexFormatUShort2),
        @"RGB16_UINT": @(MTLVertexFormatUShort3), @"RGBA16_UINT": @(MTLVertexFormatUShort4),
        @"R16_SINT": @(MTLVertexFormatShort), @"RG16_SINT": @(MTLVertexFormatShort2),
        @"RGB16_SINT": @(MTLVertexFormatShort3), @"RGBA16_SINT": @(MTLVertexFormatShort4),
        @"R32_UINT": @(MTLVertexFormatUInt), @"RG32_UINT": @(MTLVertexFormatUInt2),
        @"RGB32_UINT": @(MTLVertexFormatUInt3), @"RGBA32_UINT": @(MTLVertexFormatUInt4),
        @"R32_SINT": @(MTLVertexFormatInt), @"RG32_SINT": @(MTLVertexFormatInt2),
        @"RGB32_SINT": @(MTLVertexFormatInt3), @"RGBA32_SINT": @(MTLVertexFormatInt4),
        @"RGB10A2_UNORM": @(MTLVertexFormatUInt1010102Normalized),
    };
    NSNumber *n = m[f];
    return n ? (MTLVertexFormat)n.unsignedIntegerValue : MTLVertexFormatInvalid;
}

static MTLPixelFormat pixelFormat(NSString *f) {
    NSDictionary<NSString *, NSNumber *> *m = @{
        @"NONE": @(MTLPixelFormatInvalid),
        @"R8_UNORM": @(MTLPixelFormatR8Unorm), @"RG8_UNORM": @(MTLPixelFormatRG8Unorm), @"RGBA8_UNORM": @(MTLPixelFormatRGBA8Unorm),
        @"R8_SNORM": @(MTLPixelFormatR8Snorm), @"RG8_SNORM": @(MTLPixelFormatRG8Snorm), @"RGBA8_SNORM": @(MTLPixelFormatRGBA8Snorm),
        @"R16_UNORM": @(MTLPixelFormatR16Unorm), @"RG16_UNORM": @(MTLPixelFormatRG16Unorm), @"RGBA16_UNORM": @(MTLPixelFormatRGBA16Unorm),
        @"R16_FLOAT": @(MTLPixelFormatR16Float), @"RG16_FLOAT": @(MTLPixelFormatRG16Float), @"RGBA16_FLOAT": @(MTLPixelFormatRGBA16Float),
        @"R32_FLOAT": @(MTLPixelFormatR32Float), @"RG32_FLOAT": @(MTLPixelFormatRG32Float), @"RGBA32_FLOAT": @(MTLPixelFormatRGBA32Float),
        @"R32_UINT": @(MTLPixelFormatR32Uint), @"RG32_UINT": @(MTLPixelFormatRG32Uint), @"RGBA32_UINT": @(MTLPixelFormatRGBA32Uint),
        @"R32_SINT": @(MTLPixelFormatR32Sint), @"RG32_SINT": @(MTLPixelFormatRG32Sint), @"RGBA32_SINT": @(MTLPixelFormatRGBA32Sint),
        @"R8_UINT": @(MTLPixelFormatR8Uint), @"R8_SINT": @(MTLPixelFormatR8Sint),
        @"RGB10A2_UNORM": @(MTLPixelFormatRGB10A2Unorm), @"RG11B10_FLOAT": @(MTLPixelFormatRG11B10Float),
    };
    NSNumber *n = m[f];
    if (!n) { fprintf(stderr, "unmapped pixel format %s\n", f.UTF8String); return MTLPixelFormatInvalid; }
    return (MTLPixelFormat)n.unsignedIntegerValue;
}

static MTLBlendFactor blendFactor(NSString *f) {
    NSDictionary<NSString *, NSNumber *> *m = @{
        @"ZERO": @(MTLBlendFactorZero), @"ONE": @(MTLBlendFactorOne),
        @"SRC_COLOR": @(MTLBlendFactorSourceColor), @"ONE_MINUS_SRC_COLOR": @(MTLBlendFactorOneMinusSourceColor),
        @"SRC_ALPHA": @(MTLBlendFactorSourceAlpha), @"ONE_MINUS_SRC_ALPHA": @(MTLBlendFactorOneMinusSourceAlpha),
        @"DST_COLOR": @(MTLBlendFactorDestinationColor), @"ONE_MINUS_DST_COLOR": @(MTLBlendFactorOneMinusDestinationColor),
        @"DST_ALPHA": @(MTLBlendFactorDestinationAlpha), @"ONE_MINUS_DST_ALPHA": @(MTLBlendFactorOneMinusDestinationAlpha),
        @"SRC_ALPHA_SATURATE": @(MTLBlendFactorSourceAlphaSaturated),
        @"CONSTANT_COLOR": @(MTLBlendFactorBlendColor), @"ONE_MINUS_CONSTANT_COLOR": @(MTLBlendFactorOneMinusBlendColor),
        @"CONSTANT_ALPHA": @(MTLBlendFactorBlendAlpha), @"ONE_MINUS_CONSTANT_ALPHA": @(MTLBlendFactorOneMinusBlendAlpha),
    };
    return (MTLBlendFactor)m[f].unsignedIntegerValue;
}

static MTLBlendOperation blendOp(NSString *f) {
    NSDictionary<NSString *, NSNumber *> *m = @{
        @"ADD": @(MTLBlendOperationAdd), @"SUBTRACT": @(MTLBlendOperationSubtract),
        @"REVERSE_SUBTRACT": @(MTLBlendOperationReverseSubtract), @"MIN": @(MTLBlendOperationMin), @"MAX": @(MTLBlendOperationMax),
    };
    return (MTLBlendOperation)m[f].unsignedIntegerValue;
}

static MTLLanguageVersion languageVersion(int v) {
    switch (v) {
        case 20300: return MTLLanguageVersion2_3;
        case 20400: return MTLLanguageVersion2_4;
        case 30000: return MTLLanguageVersion3_0;
        case 30100: return MTLLanguageVersion3_1;
        default: return MTLLanguageVersion3_2;
    }
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc < 3) { fprintf(stderr, "usage: metalcheck <out-dir> <msl-version> [--parallel] [--preserve-invariance] [--safe-math]\n"); return 2; }
        NSString *dir = [NSString stringWithUTF8String:argv[1]];
        int mslVersion = atoi(argv[2]);
        BOOL parallel = NO, preserveInvariance = NO, safeMath = NO;
        NSString *nonce = nil;
        for (int i = 3; i < argc; i++) {
            if (!strcmp(argv[i], "--parallel")) parallel = YES;
            if (!strcmp(argv[i], "--preserve-invariance")) preserveInvariance = YES;
            if (!strcmp(argv[i], "--safe-math")) safeMath = YES;
            // --nonce: rename every entry point main0 -> main0_<random> so neither the system Metal front-end
            // (source -> AIR) cache nor the back-end (AIR -> GPU binary) cache can serve it: cold-compile timing.
            if (!strcmp(argv[i], "--nonce")) nonce = [[NSUUID UUID].UUIDString stringByReplacingOccurrencesOfString:@"-" withString:@""];
        }
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        NSData *json = [NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"pipelines.json"]];
        NSArray<NSDictionary *> *pipelines = [NSJSONSerialization JSONObjectWithData:json options:0 error:nil];

        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.languageVersion = languageVersion(mslVersion);
        if (@available(macOS 11.0, *)) opts.preserveInvariance = preserveInvariance;
        if (safeMath) {
            opts.mathMode = MTLMathModeSafe; // macOS 15+
        }

        // ---- 1. compile every unique .metal file ---------------------------------------------------------
        NSMutableOrderedSet<NSString *> *files = [NSMutableOrderedSet orderedSet];
        for (NSDictionary *p in pipelines) {
            if (p[@"vert"]) [files addObject:p[@"vert"][@"file"]];
            if (p[@"frag"]) [files addObject:p[@"frag"][@"file"]];
        }
        NSUInteger n = files.count;
        __block NSMutableDictionary<NSString *, id<MTLLibrary>> *libs = [NSMutableDictionary dictionary];
        double *compileMs = calloc(n, sizeof(double));
        NSMutableArray *errors = [NSMutableArray arrayWithCapacity:n];
        for (NSUInteger i = 0; i < n; i++) [errors addObject:[NSNull null]];
        NSLock *lock = [NSLock new];
        double t0 = nowMs();
        void (^compileOne)(size_t) = ^(size_t i) {
            NSString *f = files[i];
            NSString *src = [NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:f] encoding:NSUTF8StringEncoding error:nil];
            if (nonce) src = [src stringByReplacingOccurrencesOfString:@"main0(" withString:[NSString stringWithFormat:@"main0_%@(", nonce]];
            NSError *err = nil;
            double s = nowMs();
            id<MTLLibrary> lib = [device newLibraryWithSource:src options:opts error:&err];
            compileMs[i] = nowMs() - s;
            [lock lock];
            if (lib) libs[f] = lib;
            if (!lib || err) errors[i] = err ? err.localizedDescription : @"nil library";
            [lock unlock];
        };
        if (parallel) dispatch_apply(n, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), compileOne);
        else for (size_t i = 0; i < n; i++) compileOne(i);
        double compileWall = nowMs() - t0;

        int libOk = 0, libFail = 0, libWarn = 0;
        double compileSum = 0, compileMax = 0;
        NSMutableArray *fileResults = [NSMutableArray array];
        for (NSUInteger i = 0; i < n; i++) {
            compileSum += compileMs[i];
            if (compileMs[i] > compileMax) compileMax = compileMs[i];
            BOOL ok = libs[files[i]] != nil;
            id e = errors[i];
            if (ok) libOk++; else libFail++;
            if (ok && e != [NSNull null]) libWarn++;
            [fileResults addObject:@{ @"file": files[i], @"ok": @(ok), @"ms": @(compileMs[i]), @"message": e }];
            if (!ok) fprintf(stderr, "COMPILE FAIL %s\n%s\n", [files[i] UTF8String], [[e description] UTF8String]);
        }

        // ---- 2. build an MTLRenderPipelineState per pipeline -------------------------------------------
        int psoOk = 0, psoFail = 0, psoSkipped = 0;
        double psoSum = 0, psoMax = 0;
        NSMutableArray *psoResults = [NSMutableArray array];
        NSMutableArray<MTLRenderPipelineDescriptor *> *descs = [NSMutableArray array];
        for (NSDictionary *p in pipelines) {
            if (p[@"error"]) { psoSkipped++; continue; }
            id<MTLLibrary> vl = libs[p[@"vert"][@"file"]], fl = libs[p[@"frag"][@"file"]];
            if (!vl || !fl) { psoSkipped++; continue; }
            MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
            d.label = p[@"name"];
            NSString *suffix = nonce ? [@"_" stringByAppendingString:nonce] : @"";
            d.vertexFunction = [vl newFunctionWithName:[p[@"vert"][@"entry"] stringByAppendingString:suffix]];
            d.fragmentFunction = [fl newFunctionWithName:[p[@"frag"][@"entry"] stringByAppendingString:suffix]];
            if (!d.vertexFunction || !d.fragmentFunction) { psoSkipped++; fprintf(stderr, "missing entry point in %s\n", [p[@"name"] UTF8String]); continue; }
            NSArray *attrs = p[@"attributes"], *vbs = p[@"vertexBuffers"];
            if (attrs.count > 0) {
                MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
                NSMutableDictionary<NSNumber *, NSNumber *> *slotToIndex = [NSMutableDictionary dictionary];
                for (NSDictionary *vb in vbs) {
                    NSUInteger idx = [vb[@"mtlBufferIndex"] unsignedIntegerValue];
                    slotToIndex[vb[@"slot"]] = @(idx);
                    vd.layouts[idx].stride = [vb[@"stride"] unsignedIntegerValue];
                    NSUInteger step = [vb[@"stepRate"] unsignedIntegerValue];
                    vd.layouts[idx].stepFunction = step > 0 ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex;
                    vd.layouts[idx].stepRate = step > 0 ? step : 1;
                }
                for (NSDictionary *a in attrs) {
                    NSUInteger loc = [a[@"location"] unsignedIntegerValue];
                    vd.attributes[loc].format = vertexFormat(a[@"format"]);
                    vd.attributes[loc].offset = [a[@"offset"] unsignedIntegerValue];
                    vd.attributes[loc].bufferIndex = [slotToIndex[a[@"slot"]] unsignedIntegerValue];
                }
                d.vertexDescriptor = vd;
            }
            NSArray *colors = p[@"colors"];
            for (NSUInteger i = 0; i < colors.count; i++) {
                NSDictionary *c = colors[i];
                MTLRenderPipelineColorAttachmentDescriptor *ca = d.colorAttachments[i];
                ca.pixelFormat = pixelFormat(c[@"format"]);
                if (ca.pixelFormat == MTLPixelFormatInvalid) continue;
                int wm = [c[@"writeMask"] intValue];
                ca.writeMask = ((wm & 1) ? MTLColorWriteMaskRed : 0) | ((wm & 2) ? MTLColorWriteMaskGreen : 0)
                             | ((wm & 4) ? MTLColorWriteMaskBlue : 0) | ((wm & 8) ? MTLColorWriteMaskAlpha : 0);
                NSDictionary *b = c[@"blend"];
                if (b) {
                    ca.blendingEnabled = YES;
                    ca.sourceRGBBlendFactor = blendFactor(b[@"srcColor"]);
                    ca.destinationRGBBlendFactor = blendFactor(b[@"dstColor"]);
                    ca.rgbBlendOperation = blendOp(b[@"colorOp"]);
                    ca.sourceAlphaBlendFactor = blendFactor(b[@"srcAlpha"]);
                    ca.destinationAlphaBlendFactor = blendFactor(b[@"dstAlpha"]);
                    ca.alphaBlendOperation = blendOp(b[@"alphaOp"]);
                }
            }
            if ([p[@"depth"] boolValue]) d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
            NSString *topo = p[@"topology"];
            if ([topo isEqualToString:@"POINTS"]) d.inputPrimitiveTopology = MTLPrimitiveTopologyClassPoint;
            else if ([topo containsString:@"LINE"]) d.inputPrimitiveTopology = MTLPrimitiveTopologyClassLine;
            else d.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;

            [descs addObject:d];
        }
        NSUInteger np = descs.count;
        double *psoMs = calloc(np, sizeof(double));
        NSMutableArray *psoErr = [NSMutableArray arrayWithCapacity:np];
        BOOL *psoGood = calloc(np, sizeof(BOOL));
        for (NSUInteger i = 0; i < np; i++) [psoErr addObject:[NSNull null]];
        double p0 = nowMs();
        void (^makePso)(size_t) = ^(size_t i) {
            NSError *err = nil;
            double s = nowMs();
            id<MTLRenderPipelineState> pso = [device newRenderPipelineStateWithDescriptor:descs[i] error:&err];
            psoMs[i] = nowMs() - s;
            psoGood[i] = pso != nil;
            if (err) { [lock lock]; psoErr[i] = err.localizedDescription; [lock unlock]; }
        };
        if (parallel) dispatch_apply(np, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), makePso);
        else for (size_t i = 0; i < np; i++) makePso(i);
        double psoWall = nowMs() - p0;
        for (NSUInteger i = 0; i < np; i++) {
            psoSum += psoMs[i];
            if (psoMs[i] > psoMax) psoMax = psoMs[i];
            if (psoGood[i]) psoOk++; else { psoFail++; fprintf(stderr, "PSO FAIL %s: %s\n", descs[i].label.UTF8String, [[psoErr[i] description] UTF8String]); }
            [psoResults addObject:@{ @"name": descs[i].label, @"ok": @(psoGood[i]), @"ms": @(psoMs[i]), @"message": psoErr[i] }];
        }
        free(psoMs);
        free(psoGood);

        NSDictionary *summary = @{
            @"device": device.name,
            @"mslVersion": @(mslVersion),
            @"parallelCompile": @(parallel),
            @"preserveInvariance": @(preserveInvariance),
            @"uniqueFiles": @(n), @"libOk": @(libOk), @"libFail": @(libFail), @"libWithWarnings": @(libWarn),
            @"compileSumMs": @(compileSum), @"compileWallMs": @(compileWall), @"compileMaxMs": @(compileMax),
            @"psoOk": @(psoOk), @"psoFail": @(psoFail), @"psoSkipped": @(psoSkipped),
            @"psoSumMs": @(psoSum), @"psoWallMs": @(psoWall), @"psoMaxMs": @(psoMax),
        };
        NSDictionary *all = @{ @"summary": summary, @"files": fileResults, @"pipelines": psoResults };
        NSString *name = [NSString stringWithFormat:@"metalcheck%@%@.json", parallel ? @"-parallel" : @"", nonce ? @"-nonce" : @""];
        [[NSJSONSerialization dataWithJSONObject:all options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:nil]
            writeToFile:[dir stringByAppendingPathComponent:name] atomically:YES];
        NSData *sj = [NSJSONSerialization dataWithJSONObject:summary options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:nil];
        printf("%s\n", [[NSString alloc] initWithData:sj encoding:NSUTF8StringEncoding].UTF8String);
        free(compileMs);
        return (libFail || psoFail) ? 1 : 0;
    }
}
