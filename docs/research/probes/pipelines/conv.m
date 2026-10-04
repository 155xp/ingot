// Empirically checks Metal Y orientation / winding / depth-texture-as-texture2d behaviour.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static NSString *SRC = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct VOut { float4 pos [[position]]; };\n"
"vertex VOut vmain(uint vid [[vertex_id]], constant int &flip [[buffer(0)]]) {\n"
"  // GL-style CCW triangle covering the lower-left half in y-up NDC: A(-1,-1) B(1,-1) C(-1,1)\n"
"  float2 p[3] = { float2(-1,-1), float2(1,-1), float2(-1,1) };\n"
"  VOut o; o.pos = float4(p[vid], 0.5, 1);\n"
"  if (flip) o.pos.y = -o.pos.y;\n"
"  return o;\n"
"}\n"
"fragment float4 fmain(bool ff [[front_facing]]) { return ff ? float4(1,0,0,1) : float4(0,1,0,1); }\n"
"struct FOut { float4 c [[color(0)]]; };\n"
"vertex VOut vquad(uint vid [[vertex_id]]) { float2 uv = float2((vid << 1) & 2, vid & 2); VOut o; o.pos = float4(uv * 2 - 1, 0, 1); return o; }\n"
"fragment float4 fdepth(VOut in [[stage_in]], texture2d<float> InSampler [[texture(0)]], sampler s [[sampler(0)]]) {\n"
"  return float4(InSampler.read(uint2(in.pos.xy)).r, InSampler.sample(s, float2(0.5)).r, 0, 1);\n"
"}\n";

static void dump(id<MTLTexture> t, const char *label) {
    uint8_t px[4 * 4 * 4];
    [t getBytes:px bytesPerRow:16 fromRegion:MTLRegionMake2D(0, 0, 4, 4) mipmapLevel:0];
    printf("%s (row 0 = first row in memory):\n", label);
    for (int y = 0; y < 4; y++) {
        printf("  ");
        for (int x = 0; x < 4; x++) {
            uint8_t *p = px + (y * 4 + x) * 4;
            printf("%c", p[0] ? 'F' : (p[1] ? 'B' : '.'));
        }
        printf("\n");
    }
}

int main(void) {
    @autoreleasepool {
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        NSError *err = nil;
        id<MTLLibrary> lib = [dev newLibraryWithSource:SRC options:nil error:&err];
        if (!lib) { printf("%s\n", err.description.UTF8String); return 1; }
        id<MTLCommandQueue> q = [dev newCommandQueue];
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:4 mipmapped:NO];
        td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        td.storageMode = MTLStorageModeShared;
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [lib newFunctionWithName:@"vmain"];
        pd.fragmentFunction = [lib newFunctionWithName:@"fmain"];
        pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        id<MTLRenderPipelineState> ps = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
        struct { const char *name; int flip; int negViewport; MTLWinding winding; } cases[] = {
            {"no flip, winding CW", 0, 0, MTLWindingClockwise},
            {"no flip, winding CCW", 0, 0, MTLWindingCounterClockwise},
            {"shader flip (gl_Position.y = -y), winding CW", 1, 0, MTLWindingClockwise},
            {"shader flip (gl_Position.y = -y), winding CCW", 1, 0, MTLWindingCounterClockwise},
            {"negative viewport height, winding CW", 0, 1, MTLWindingClockwise},
            {"negative viewport height, winding CCW", 0, 1, MTLWindingCounterClockwise},
        };
        for (int i = 0; i < 6; i++) {
            id<MTLTexture> rt = [dev newTextureWithDescriptor:td];
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = rt;
            rp.colorAttachments[0].loadAction = MTLLoadActionClear;
            rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
            rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            id<MTLCommandBuffer> cb = [q commandBuffer];
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:ps];
            [enc setFrontFacingWinding:cases[i].winding];
            [enc setCullMode:MTLCullModeNone];
            if (cases[i].negViewport) {
                MTLViewport vp = {0, 4, 4, -4, 0, 1};
                [enc setViewport:vp];
            }
            int flip = cases[i].flip;
            [enc setVertexBytes:&flip length:4 atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            [cb commit];
            [cb waitUntilCompleted];
            if (cb.error) printf("cb error %s\n", cb.error.description.UTF8String);
            dump(rt, cases[i].name);
        }

        // Depth32Float texture bound to a texture2d<float> parameter.
        MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:4 height:4 mipmapped:NO];
        dd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        dd.storageMode = MTLStorageModePrivate;
        id<MTLTexture> depth = [dev newTextureWithDescriptor:dd];
        id<MTLCommandBuffer> cb = [q commandBuffer];
        MTLRenderPassDescriptor *rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        rpd.depthAttachment.texture = depth;
        rpd.depthAttachment.loadAction = MTLLoadActionClear;
        rpd.depthAttachment.clearDepth = 0.25;
        rpd.depthAttachment.storeAction = MTLStoreActionStore;
        [[cb renderCommandEncoderWithDescriptor:rpd] endEncoding];
        MTLRenderPipelineDescriptor *pd2 = [MTLRenderPipelineDescriptor new];
        pd2.vertexFunction = [lib newFunctionWithName:@"vquad"];
        pd2.fragmentFunction = [lib newFunctionWithName:@"fdepth"];
        pd2.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        id<MTLRenderPipelineState> ps2 = [dev newRenderPipelineStateWithDescriptor:pd2 error:&err];
        id<MTLTexture> rt = [dev newTextureWithDescriptor:td];
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = rt;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
        [enc setRenderPipelineState:ps2];
        [enc setFragmentTexture:depth atIndex:0];
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        [enc setFragmentSamplerState:[dev newSamplerStateWithDescriptor:sd] atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [enc endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        if (cb.error) printf("cb error %s\n", cb.error.description.UTF8String);
        uint8_t px[4];
        [rt getBytes:px bytesPerRow:16 fromRegion:MTLRegionMake2D(1, 1, 1, 1) mipmapLevel:0];
        printf("Depth32Float read through texture2d<float>: read=%.3f sample=%.3f (expected 0.25)\n", px[0] / 255.0, px[1] / 255.0);
    }
    return 0;
}
