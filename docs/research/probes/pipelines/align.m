#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
int main(void) { @autoreleasepool {
  id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
  NSString *src = @"#include <metal_stdlib>\nusing namespace metal;\nstruct In { float4 a [[attribute(0)]]; int2 b [[attribute(1)]]; float c [[attribute(2)]]; };\nvertex float4 v(In in [[stage_in]]) { return in.a + float4(in.b.x, in.c, 0, 0); }\nfragment float4 f() { return 1; }\n";
  NSError *e = nil; id<MTLLibrary> lib = [dev newLibraryWithSource:src options:nil error:&e];
  struct { NSUInteger offA, offB, offC, stride; MTLVertexFormat fa; const char *n; } cases[] = {
    {0, 4, 8, 12, MTLVertexFormatUChar4Normalized, "aligned (0,4,8) stride 12"},
    {0, 2, 8, 12, MTLVertexFormatUChar2Normalized, "Short2 at offset 2"},
    {0, 4, 9, 16, MTLVertexFormatUChar4Normalized, "UChar at offset 9"},
    {0, 4, 8, 10, MTLVertexFormatUChar4Normalized, "stride 10"},
    {1, 4, 8, 12, MTLVertexFormatUCharNormalized, "UChar at offset 1"},
  };
  for (int i = 0; i < 5; i++) {
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
    d.vertexFunction = [lib newFunctionWithName:@"v"]; d.fragmentFunction = [lib newFunctionWithName:@"f"];
    d.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    vd.attributes[0].format = cases[i].fa; vd.attributes[0].offset = cases[i].offA; vd.attributes[0].bufferIndex = 30;
    vd.attributes[1].format = MTLVertexFormatShort2; vd.attributes[1].offset = cases[i].offB; vd.attributes[1].bufferIndex = 30;
    vd.attributes[2].format = (i == 2) ? MTLVertexFormatUCharNormalized : MTLVertexFormatFloat; vd.attributes[2].offset = cases[i].offC; vd.attributes[2].bufferIndex = 30;
    vd.layouts[30].stride = cases[i].stride;
    d.vertexDescriptor = vd;
    NSError *err = nil;
    id ps = [dev newRenderPipelineStateWithDescriptor:d error:&err];
    printf("%-28s -> %s %s\n", cases[i].n, ps ? "OK" : "FAIL", err ? err.localizedDescription.UTF8String : "");
  }
}}
