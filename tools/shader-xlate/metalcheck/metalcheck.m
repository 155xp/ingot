// metalcheck: compiles the MSL produced by ShaderXlate with the *runtime* Metal compiler
// (MTLDevice newLibraryWithSource:, i.e. exactly what the Ingot backend will do on a player's Mac,
// where the offline `metal` toolchain is not installed), then builds an MTLRenderPipelineState for
// every pipeline in pipelines.json so that vertex-descriptor <-> [[attribute(n)]], VS outputs <->
// FS inputs ([[user(locnN)]]), and fragment outputs <-> color attachment formats are all validated.
//
//
// --desc additionally checks the IngotPipelineDesc bytes the mod's Java code writes (pipelines.json "desc", dumped by
// ShaderXlate --desc): every field is compared with the manifest through the SDK enums below, then a PSO is built from
// the struct the way libingot's ingot_pipeline_create interprets it.
//
// usage: metalcheck <out-dir> <msl-version e.g. 20400|30000> [--parallel] [--preserve-invariance] [--safe-math] [--desc]
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>
#include "ingot.h"

_Static_assert(sizeof(IngotPipelineDesc) == 1352, "IngotPipelineDesc size");

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

// LINES (game-expanded quads) and QUADS are drawn as triangle lists; only the DEBUG_* topologies are real lines.
static MTLPrimitiveTopologyClass topologyClass(NSString *topology) {
    if ([topology isEqualToString:@"POINTS"]) return MTLPrimitiveTopologyClassPoint;
    if ([topology hasPrefix:@"DEBUG_LINE"]) return MTLPrimitiveTopologyClassLine;
    return MTLPrimitiveTopologyClassTriangle;
}

static MTLColorWriteMask colorWriteMask(int renderpearlMask) {
    return ((renderpearlMask & 1) ? MTLColorWriteMaskRed : 0) | ((renderpearlMask & 2) ? MTLColorWriteMaskGreen : 0)
         | ((renderpearlMask & 4) ? MTLColorWriteMaskBlue : 0) | ((renderpearlMask & 8) ? MTLColorWriteMaskAlpha : 0);
}

// ---- --desc ------------------------------------------------------------------------------------------------

static void expect(NSMutableArray *problems, NSString *what, uint64_t got, uint64_t want) {
    if (got != want) [problems addObject:[NSString stringWithFormat:@"%@=%llu (expected %llu)", what, got, want]];
}

static BOOL isMinMax(NSString *op) {
    return [op isEqualToString:@"MIN"] || [op isEqualToString:@"MAX"];
}

// Compares an IngotPipelineDesc written by the mod with the manifest entry of the same pipeline.
static void checkIngotDesc(const IngotPipelineDesc *in, NSDictionary *p, uint32_t depth, uint32_t colorMask, NSMutableArray *problems) {
    expect(problems, @"vertex_function", (uint64_t)in->vertex_function, 0);
    expect(problems, @"fragment_function", (uint64_t)in->fragment_function, 0);
    expect(problems, @"label", (uint64_t)in->label, 0);

    uint32_t referencedSlots = 0;
    NSMutableIndexSet *locations = [NSMutableIndexSet indexSet];
    for (NSDictionary *a in p[@"attributes"]) {
        NSUInteger loc = [a[@"location"] unsignedIntegerValue], slot = [a[@"slot"] unsignedIntegerValue];
        const IngotVertexAttribute *x = &in->attributes[loc];
        expect(problems, [NSString stringWithFormat:@"attributes[%lu].format", loc], x->format, vertexFormat(a[@"format"]));
        expect(problems, [NSString stringWithFormat:@"attributes[%lu].offset", loc], x->offset, [a[@"offset"] unsignedIntValue]);
        expect(problems, [NSString stringWithFormat:@"attributes[%lu].buffer_index", loc], x->buffer_index, 30 - slot);
        referencedSlots |= 1u << slot;
        [locations addIndex:loc];
    }
    for (NSUInteger i = 0; i < INGOT_MAX_VERTEX_ATTRIBUTES; i++) {
        const IngotVertexAttribute *x = &in->attributes[i];
        if (![locations containsIndex:i]) {
            expect(problems, [NSString stringWithFormat:@"unused attributes[%lu]", i], x->format | x->offset | x->buffer_index, 0);
        }
        expect(problems, [NSString stringWithFormat:@"attributes[%lu]._pad", i], x->_pad, 0);
    }
    uint32_t describedIndices = 0;
    for (NSDictionary *vb in p[@"vertexBuffers"]) {
        NSUInteger slot = [vb[@"slot"] unsignedIntegerValue], stepRate = [vb[@"stepRate"] unsignedIntegerValue];
        if (!(referencedSlots >> slot & 1)) continue;
        NSUInteger idx = 30 - slot;
        const IngotVertexLayout *l = &in->layouts[idx];
        expect(problems, [NSString stringWithFormat:@"layouts[%lu].stride", idx], l->stride, [vb[@"stride"] unsignedIntValue]);
        expect(problems, [NSString stringWithFormat:@"layouts[%lu].step_function", idx], l->step_function,
               stepRate > 0 ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex);
        expect(problems, [NSString stringWithFormat:@"layouts[%lu].step_rate", idx], l->step_rate, stepRate > 0 ? stepRate : 1);
        describedIndices |= 1u << idx;
    }
    for (NSUInteger i = 0; i < INGOT_MAX_VERTEX_ATTRIBUTES; i++) {
        const IngotVertexLayout *l = &in->layouts[i];
        if (!(describedIndices >> i & 1)) {
            expect(problems, [NSString stringWithFormat:@"unused layouts[%lu]", i], l->stride | l->step_function | l->step_rate, 0);
        }
        expect(problems, [NSString stringWithFormat:@"layouts[%lu]._pad", i], l->_pad, 0);
    }

    NSArray *colors = p[@"colors"];
    static const IngotColorTarget none;
    for (NSUInteger i = 0; i < INGOT_MAX_COLOR_TARGETS; i++) {
        const IngotColorTarget *c = &in->colors[i];
        NSString *w = [NSString stringWithFormat:@"colors[%lu]", i];
        if (!(colorMask >> i & 1)) {
            if (memcmp(c, &none, sizeof none) != 0) [problems addObject:[w stringByAppendingString:@" should be all zero"]];
            continue;
        }
        NSDictionary *json = colors[i], *b = json[@"blend"];
        expect(problems, [w stringByAppendingString:@".pixel_format"], c->pixel_format, pixelFormat(json[@"format"]));
        expect(problems, [w stringByAppendingString:@".write_mask"], c->write_mask, colorWriteMask([json[@"writeMask"] intValue]));
        expect(problems, [w stringByAppendingString:@".blend_enabled"], c->blend_enabled, b != nil);
        if (b) {
            BOOL rgbMinMax = isMinMax(b[@"colorOp"]), alphaMinMax = isMinMax(b[@"alphaOp"]);
            expect(problems, [w stringByAppendingString:@".rgb_op"], c->rgb_op, blendOp(b[@"colorOp"]));
            expect(problems, [w stringByAppendingString:@".alpha_op"], c->alpha_op, blendOp(b[@"alphaOp"]));
            expect(problems, [w stringByAppendingString:@".src_rgb"], c->src_rgb, rgbMinMax ? MTLBlendFactorOne : blendFactor(b[@"srcColor"]));
            expect(problems, [w stringByAppendingString:@".dst_rgb"], c->dst_rgb, rgbMinMax ? MTLBlendFactorOne : blendFactor(b[@"dstColor"]));
            expect(problems, [w stringByAppendingString:@".src_alpha"], c->src_alpha, alphaMinMax ? MTLBlendFactorOne : blendFactor(b[@"srcAlpha"]));
            expect(problems, [w stringByAppendingString:@".dst_alpha"], c->dst_alpha, alphaMinMax ? MTLBlendFactorOne : blendFactor(b[@"dstAlpha"]));
        } else {
            expect(problems, [w stringByAppendingString:@" blend fields"],
                   c->rgb_op | c->alpha_op | c->src_rgb | c->dst_rgb | c->src_alpha | c->dst_alpha, 0);
        }
        expect(problems, [w stringByAppendingString:@"._pad"], c->_pad, 0);
    }
    expect(problems, @"depth_pixel_format", in->depth_pixel_format, depth);
    expect(problems, @"stencil_pixel_format", in->stencil_pixel_format, MTLPixelFormatInvalid);
    expect(problems, @"sample_count", in->sample_count, 1);
    expect(problems, @"input_primitive_topology", in->input_primitive_topology, topologyClass(p[@"topology"]));
}

// The MTLRenderPipelineDescriptor libingot's ingot_pipeline_create builds from an IngotPipelineDesc.
static MTLRenderPipelineDescriptor *descriptorFromIngot(const IngotPipelineDesc *in) {
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
    d.vertexFunction = (__bridge id<MTLFunction>)in->vertex_function;
    d.fragmentFunction = (__bridge id<MTLFunction>)in->fragment_function;
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    BOOL anyAttribute = NO;
    for (NSUInteger i = 0; i < INGOT_MAX_VERTEX_ATTRIBUTES; i++) {
        const IngotVertexAttribute *a = &in->attributes[i];
        if (a->format == MTLVertexFormatInvalid) continue;
        vd.attributes[i].format = a->format;
        vd.attributes[i].offset = a->offset;
        vd.attributes[i].bufferIndex = a->buffer_index;
        anyAttribute = YES;
    }
    for (NSUInteger i = 0; i < INGOT_MAX_VERTEX_ATTRIBUTES; i++) {
        const IngotVertexLayout *l = &in->layouts[i];
        if (l->stride == 0) continue;
        vd.layouts[i].stride = l->stride;
        vd.layouts[i].stepFunction = l->step_function;
        vd.layouts[i].stepRate = l->step_rate;
    }
    if (anyAttribute) d.vertexDescriptor = vd;
    for (NSUInteger i = 0; i < INGOT_MAX_COLOR_TARGETS; i++) {
        const IngotColorTarget *c = &in->colors[i];
        if (c->pixel_format == MTLPixelFormatInvalid) continue;
        MTLRenderPipelineColorAttachmentDescriptor *ca = d.colorAttachments[i];
        ca.pixelFormat = c->pixel_format;
        ca.writeMask = c->write_mask;
        ca.blendingEnabled = c->blend_enabled != 0;
        if (ca.blendingEnabled) {
            ca.rgbBlendOperation = c->rgb_op;
            ca.alphaBlendOperation = c->alpha_op;
            ca.sourceRGBBlendFactor = c->src_rgb;
            ca.destinationRGBBlendFactor = c->dst_rgb;
            ca.sourceAlphaBlendFactor = c->src_alpha;
            ca.destinationAlphaBlendFactor = c->dst_alpha;
        }
    }
    d.depthAttachmentPixelFormat = in->depth_pixel_format;
    d.stencilAttachmentPixelFormat = in->stencil_pixel_format;
    d.rasterSampleCount = in->sample_count;
    d.inputPrimitiveTopology = in->input_primitive_topology;
    return d;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc < 3) { fprintf(stderr, "usage: metalcheck <out-dir> <msl-version> [--parallel] [--preserve-invariance] [--safe-math]\n"); return 2; }
        NSString *dir = [NSString stringWithUTF8String:argv[1]];
        int mslVersion = atoi(argv[2]);
        BOOL parallel = NO, preserveInvariance = NO, safeMath = NO, checkDesc = NO;
        NSString *nonce = nil;
        for (int i = 3; i < argc; i++) {
            if (!strcmp(argv[i], "--parallel")) parallel = YES;
            if (!strcmp(argv[i], "--preserve-invariance")) preserveInvariance = YES;
            if (!strcmp(argv[i], "--safe-math")) safeMath = YES;
            if (!strcmp(argv[i], "--desc")) checkDesc = YES;
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
                ca.writeMask = colorWriteMask([c[@"writeMask"] intValue]);
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
            d.inputPrimitiveTopology = topologyClass(p[@"topology"]);

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

        // ---- 3. --desc: PSOs from the IngotPipelineDesc bytes the mod writes --------------------------------
        int descChecked = 0, descMismatch = 0, descPsoOk = 0, descPsoFail = 0;
        for (NSDictionary *p in checkDesc ? pipelines : @[]) {
            NSArray *variants = p[@"desc"];
            id<MTLLibrary> vl = libs[p[@"vert"][@"file"]], fl = libs[p[@"frag"][@"file"]];
            if (!variants || !vl || !fl) continue;
            NSString *suffix = nonce ? [@"_" stringByAppendingString:nonce] : @"";
            id<MTLFunction> vf = [vl newFunctionWithName:[p[@"vert"][@"entry"] stringByAppendingString:suffix]];
            id<MTLFunction> ff = [fl newFunctionWithName:[p[@"frag"][@"entry"] stringByAppendingString:suffix]];
            // Expected attachments, derived from the manifest: variant 0 is the default (D32 iff depth state, every
            // non-null color target), variant 1 the same with the other depth attachment.
            uint32_t colorMask = 0;
            NSArray *colors = p[@"colors"];
            for (NSUInteger i = 0; i < colors.count; i++) {
                if (![colors[i][@"format"] isEqualToString:@"NONE"]) colorMask |= 1u << i;
            }
            for (NSUInteger v = 0; v < variants.count; v++) {
                NSDictionary *variant = variants[v];
                BOOL withDepth = [p[@"depth"] boolValue] != (v == 1);
                uint32_t depth = withDepth ? MTLPixelFormatDepth32Float : MTLPixelFormatInvalid;
                NSData *bytes = [NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:variant[@"file"]]];
                NSMutableArray *problems = [NSMutableArray array];
                IngotPipelineDesc desc;
                descChecked++;
                if (bytes.length != sizeof desc) {
                    [problems addObject:[NSString stringWithFormat:@"size %lu", (unsigned long)bytes.length]];
                } else {
                    memcpy(&desc, bytes.bytes, sizeof desc);
                    expect(problems, @"reported depthFormat", [variant[@"depthFormat"] unsignedIntValue], depth);
                    expect(problems, @"reported colorMask", [variant[@"colorMask"] unsignedIntValue], colorMask);
                    checkIngotDesc(&desc, p, depth, colorMask, problems);
                }
                if (problems.count) {
                    descMismatch++;
                    fprintf(stderr, "DESC MISMATCH %s variant %lu: %s\n", [p[@"name"] UTF8String], (unsigned long)v,
                            [[problems componentsJoinedByString:@", "] UTF8String]);
                    continue;
                }
                desc.vertex_function = (__bridge void *)vf;
                desc.fragment_function = (__bridge void *)ff;
                NSError *err = nil;
                id<MTLRenderPipelineState> pso = [device newRenderPipelineStateWithDescriptor:descriptorFromIngot(&desc) error:&err];
                if (pso) descPsoOk++;
                else {
                    descPsoFail++;
                    fprintf(stderr, "DESC PSO FAIL %s variant %lu: %s\n", [p[@"name"] UTF8String], (unsigned long)v,
                            err.localizedDescription.UTF8String);
                }
            }
        }

        NSDictionary *summary = @{
            @"device": device.name,
            @"mslVersion": @(mslVersion),
            @"parallelCompile": @(parallel),
            @"preserveInvariance": @(preserveInvariance),
            @"uniqueFiles": @(n), @"libOk": @(libOk), @"libFail": @(libFail), @"libWithWarnings": @(libWarn),
            @"compileSumMs": @(compileSum), @"compileWallMs": @(compileWall), @"compileMaxMs": @(compileMax),
            @"psoOk": @(psoOk), @"psoFail": @(psoFail), @"psoSkipped": @(psoSkipped),
            @"psoSumMs": @(psoSum), @"psoWallMs": @(psoWall), @"psoMaxMs": @(psoMax),
            @"safeMath": @(safeMath), @"descChecked": @(descChecked), @"descMismatch": @(descMismatch),
            @"descPsoOk": @(descPsoOk), @"descPsoFail": @(descPsoFail),
        };
        NSDictionary *all = @{ @"summary": summary, @"files": fileResults, @"pipelines": psoResults };
        NSString *name = [NSString stringWithFormat:@"metalcheck%@%@.json", parallel ? @"-parallel" : @"", nonce ? @"-nonce" : @""];
        [[NSJSONSerialization dataWithJSONObject:all options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:nil]
            writeToFile:[dir stringByAppendingPathComponent:name] atomically:YES];
        NSData *sj = [NSJSONSerialization dataWithJSONObject:summary options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:nil];
        printf("%s\n", [[NSString alloc] initWithData:sj encoding:NSUTF8StringEncoding].UTF8String);
        free(compileMs);
        return (libFail || psoFail || descMismatch || descPsoFail) ? 1 : 0;
    }
}
