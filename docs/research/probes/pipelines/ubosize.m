#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
int main(void) { @autoreleasepool {
  id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
  NSString *src = @"#include <metal_stdlib>\nusing namespace metal;\nstruct Fog { float4 FogColor; float a; float b; float c; float d; float e; float f; };\nvertex float4 v(uint vid [[vertex_id]]) { float2 uv = float2((vid << 1) & 2, vid & 2); return float4(uv*2-1,0,1); }\nfragment float4 f(constant Fog& fog [[buffer(3)]]) { return fog.FogColor * fog.f; }\n";
  NSError *e = nil; id<MTLLibrary> lib = [dev newLibraryWithSource:src options:nil error:&e];
  MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
  d.vertexFunction = [lib newFunctionWithName:@"v"]; d.fragmentFunction = [lib newFunctionWithName:@"f"];
  d.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
  id<MTLRenderPipelineState> ps = [dev newRenderPipelineStateWithDescriptor:d error:&e];
  MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:4 mipmapped:NO];
  td.usage = MTLTextureUsageRenderTarget;
  id<MTLTexture> rt = [dev newTextureWithDescriptor:td];
  for (int len = 40; len <= 48; len += 8) {
    id<MTLBuffer> buf = [dev newBufferWithLength:len options:MTLResourceStorageModeShared];
    id<MTLCommandQueue> q = [dev newCommandQueue];
    id<MTLCommandBuffer> cb = [q commandBuffer];
    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = rt; rp.colorAttachments[0].loadAction = MTLLoadActionClear; rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
    [enc setRenderPipelineState:ps];
    [enc setFragmentBuffer:buf offset:0 atIndex:3];
    printf("drawing with %d-byte buffer...\n", len); fflush(stdout);
    [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    printf("  ok (cb error: %s)\n", cb.error ? cb.error.description.UTF8String : "none");
  }
}}
