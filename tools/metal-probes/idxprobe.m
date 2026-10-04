#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
static NSString *src = @
"#include <metal_stdlib>\nusing namespace metal;\n"
"struct VOut { float4 pos [[position]]; float psize [[point_size]]; uint value [[flat]]; };\n"
"vertex VOut vs(uint vid [[vertex_id]]) { VOut o; o.pos = float4((float(vid) + 0.5) / 64.0 * 2.0 - 1.0, 0.0, 0.5, 1.0); o.psize = 1.0; o.value = 1000u + vid; return o; }\n"
"fragment uint fs(VOut in [[stage_in]]) { return in.value; }\n";
int main(void) { @autoreleasepool {
  id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); NSError *err = nil;
  id<MTLLibrary> lib = [dev newLibraryWithSource:src options:nil error:&err];
  MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
  pd.vertexFunction = [lib newFunctionWithName:@"vs"]; pd.fragmentFunction = [lib newFunctionWithName:@"fs"];
  pd.colorAttachments[0].pixelFormat = MTLPixelFormatR32Uint; pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassPoint;
  id<MTLRenderPipelineState> ps = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
  MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Uint width:64 height:1 mipmapped:NO];
  td.usage = MTLTextureUsageRenderTarget; id<MTLTexture> rt = [dev newTextureWithDescriptor:td];
  uint16_t idx[64]; for (int i = 0; i < 64; i++) idx[i] = i;
  id<MTLBuffer> ib = [dev newBufferWithBytes:idx length:sizeof(idx) options:MTLResourceStorageModeShared];
  id<MTLCommandQueue> q = [dev newCommandQueue];
  for (int off = 1; off <= 5; off += 2) for (int count = 1; count <= 37; count += 12) {
    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = rt; rp.colorAttachments[0].loadAction = MTLLoadActionClear; rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLCommandBuffer> cb = [q commandBuffer];
    id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
    [e setRenderPipelineState:ps];
    [e drawIndexedPrimitives:MTLPrimitiveTypePoint indexCount:count indexType:MTLIndexTypeUInt16 indexBuffer:ib indexBufferOffset:2 * off instanceCount:1 baseVertex:0 baseInstance:0];
    [e endEncoding];
    id<MTLBuffer> rb = [dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
    [bl copyFromTexture:rt sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(64,1,1) toBuffer:rb destinationOffset:0 destinationBytesPerRow:256 destinationBytesPerImage:256];
    [bl endEncoding]; [cb commit]; [cb waitUntilCompleted];
    uint32_t *p = rb.contents; int drawn = 0, first = -1, last = -1;
    for (int i = 0; i < 64; i++) if (p[i]) { drawn++; if (first < 0) first = i; last = i; }
    printf("u16 firstIndex=%d count=%2d -> drawn=%2d range=[%d..%d] expected=[%d..%d]%s\n", off, count, drawn, first, last, off, off + count - 1,
           (drawn == count && first == off) ? "" : "  MISMATCH");
  }
}}
