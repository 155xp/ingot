// Probe: Metal caps + semantics relevant to renderpearl DeviceInfo choices.
// Build: clang -fobjc-arc -framework Metal -framework Foundation mtlprobe.m -o mtlprobe
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static NSString *src = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct VIn { uint value [[attribute(0)]]; };\n"
"struct VOut { float4 pos [[position]]; float psize [[point_size]]; uint value [[flat]]; };\n"
"vertex VOut vs_inst(VIn in [[stage_in]], uint iid [[instance_id]], uint vid [[vertex_id]], constant uint &bias [[buffer(1)]]) {\n"
"  VOut o; uint slot = iid; o.pos = float4((float(slot) + 0.5) / 4.0 * 2.0 - 1.0, 0.0, 0.5, 1.0);\n"
"  o.psize = 1.0; o.value = in.value + bias; return o; }\n"
"vertex VOut vs_vert(VIn in [[stage_in]], uint vid [[vertex_id]], uint iid [[instance_id]]) {\n"
"  VOut o; o.pos = float4((float(iid) + 0.5) / 4.0 * 2.0 - 1.0, 0.0, 0.5, 1.0);\n"
"  o.psize = 1.0; o.value = in.value * 1000u + vid; return o; }\n"
"fragment uint fs_main(VOut in [[stage_in]]) { return in.value; }\n";

static void readback(id<MTLDevice> dev, id<MTLCommandQueue> q, id<MTLTexture> tex, const char *label) {
  id<MTLBuffer> rb = [dev newBufferWithLength:16 options:MTLResourceStorageModeShared];
  id<MTLCommandBuffer> cb = [q commandBuffer];
  id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
  [bl copyFromTexture:tex sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(4,1,1)
             toBuffer:rb destinationOffset:0 destinationBytesPerRow:16 destinationBytesPerImage:16];
  [bl endEncoding]; [cb commit]; [cb waitUntilCompleted];
  uint32_t *p = (uint32_t *)rb.contents;
  printf("%-55s %u %u %u %u\n", label, p[0], p[1], p[2], p[3]);
}

int main(void) {
  @autoreleasepool {
    id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
    printf("name=%s\n", dev.name.UTF8String);
    printf("hasUnifiedMemory=%d lowPower=%d removable=%d\n", dev.hasUnifiedMemory, dev.isLowPower, dev.isRemovable);
    printf("maxBufferLength=%lu recommendedMaxWorkingSetSize=%llu\n", (unsigned long)dev.maxBufferLength, dev.recommendedMaxWorkingSetSize);
    for (int f = 1001; f <= 1010; f++) if ([dev supportsFamily:(MTLGPUFamily)f]) printf("supports MTLGPUFamilyApple%d\n", f - 1000);
    if ([dev supportsFamily:MTLGPUFamilyMac2]) printf("supports MTLGPUFamilyMac2\n");
    if ([dev supportsFamily:MTLGPUFamilyMetal3]) printf("supports MTLGPUFamilyMetal3\n");
    printf("argumentBuffersSupport=%ld\n", (long)dev.argumentBuffersSupport);
    printf("minLinearTexAlign(R8Sint)=%lu\n", (unsigned long)[dev minimumLinearTextureAlignmentForPixelFormat:MTLPixelFormatR8Sint]);
    printf("supportsShaderBarycentric=%d supportsPrimitiveMotionBlur=%d\n", dev.supportsShaderBarycentricCoordinates, 0);
    printf("supportsCounterSampling(stage)=%d (draw)=%d (blit)=%d\n",
      [dev supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary],
      [dev supportsCounterSampling:MTLCounterSamplingPointAtDrawBoundary],
      [dev supportsCounterSampling:MTLCounterSamplingPointAtBlitBoundary]);

    NSError *err = nil;
    id<MTLLibrary> lib = [dev newLibraryWithSource:src options:nil error:&err];
    if (!lib) { printf("compile error: %s\n", err.localizedDescription.UTF8String); return 1; }

    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Uint width:4 height:1 mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget; td.storageMode = MTLStorageModePrivate;
    id<MTLTexture> rt = [dev newTextureWithDescriptor:td];
    id<MTLCommandQueue> q = [dev newCommandQueue];

    // Pipeline A: per-instance attribute (step rate 1) at buffer 30.
    MTLVertexDescriptor *vdI = [MTLVertexDescriptor vertexDescriptor];
    vdI.attributes[0].format = MTLVertexFormatUInt; vdI.attributes[0].offset = 0; vdI.attributes[0].bufferIndex = 30;
    vdI.layouts[30].stride = 4; vdI.layouts[30].stepFunction = MTLVertexStepFunctionPerInstance; vdI.layouts[30].stepRate = 1;
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = [lib newFunctionWithName:@"vs_inst"]; pd.fragmentFunction = [lib newFunctionWithName:@"fs_main"];
    pd.vertexDescriptor = vdI; pd.colorAttachments[0].pixelFormat = MTLPixelFormatR32Uint; pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassPoint;
    id<MTLRenderPipelineState> psI = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
    if (!psI) { printf("pso error: %s\n", err.localizedDescription.UTF8String); return 1; }

    // Pipeline B: per-vertex attribute.
    MTLVertexDescriptor *vdV = [MTLVertexDescriptor vertexDescriptor];
    vdV.attributes[0].format = MTLVertexFormatUInt; vdV.attributes[0].offset = 0; vdV.attributes[0].bufferIndex = 30;
    vdV.layouts[30].stride = 4; vdV.layouts[30].stepFunction = MTLVertexStepFunctionPerVertex;
    pd.vertexFunction = [lib newFunctionWithName:@"vs_vert"]; pd.vertexDescriptor = vdV;
    id<MTLRenderPipelineState> psV = [dev newRenderPipelineStateWithDescriptor:pd error:&err];

    uint32_t instData[4] = {100, 101, 102, 103};
    id<MTLBuffer> inst = [dev newBufferWithBytes:instData length:16 options:MTLResourceStorageModeShared];
    uint32_t biasData[16] = {0}; biasData[1] = 7; biasData[4] = 50; // offset 4 -> 7, offset 16 -> 50
    id<MTLBuffer> bias = [dev newBufferWithBytes:biasData length:64 options:MTLResourceStorageModeShared];
    uint32_t vertData[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    id<MTLBuffer> verts = [dev newBufferWithBytes:vertData length:32 options:MTLResourceStorageModeShared];
    uint16_t idx16[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    id<MTLBuffer> ib16 = [dev newBufferWithBytes:idx16 length:16 options:MTLResourceStorageModeShared];

    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    rp.colorAttachments[0].texture = rt; rp.colorAttachments[0].loadAction = MTLLoadActionClear;
    rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0); rp.colorAttachments[0].storeAction = MTLStoreActionStore;

    // Test 1: indirect non-indexed draws, baseInstance = k; does per-instance fetch and [[instance_id]] include baseInstance?
    {
      MTLDrawPrimitivesIndirectArguments args[4];
      for (int k = 0; k < 4; k++) { args[k].vertexCount = 1; args[k].instanceCount = 1; args[k].vertexStart = 0; args[k].baseInstance = k; }
      id<MTLBuffer> ab = [dev newBufferWithBytes:args length:sizeof(args) options:MTLResourceStorageModeShared];
      id<MTLCommandBuffer> cb = [q commandBuffer];
      id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
      [e setRenderPipelineState:psI];
      [e setVertexBuffer:inst offset:0 atIndex:30];
      [e setVertexBuffer:bias offset:0 atIndex:1];
      for (int k = 0; k < 4; k++) [e drawPrimitives:MTLPrimitiveTypePoint indirectBuffer:ab indirectBufferOffset:k * sizeof(MTLDrawPrimitivesIndirectArguments)];
      [e endEncoding]; [cb commit]; [cb waitUntilCompleted];
      readback(dev, q, rt, "T1 indirect baseInstance k (expect 100 101 102 103)");
    }
    // Test 2: indexed indirect with uint16, indexStart = k (odd and even), baseInstance = k, 20-byte stride.
    {
      MTLDrawIndexedPrimitivesIndirectArguments args[4];
      for (int k = 0; k < 4; k++) { args[k].indexCount = 1; args[k].instanceCount = 1; args[k].indexStart = k + 1; args[k].baseVertex = 0; args[k].baseInstance = k; }
      id<MTLBuffer> ab = [dev newBufferWithBytes:args length:sizeof(args) options:MTLResourceStorageModeShared];
      id<MTLCommandBuffer> cb = [q commandBuffer];
      id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
      [e setRenderPipelineState:psV];
      [e setVertexBuffer:verts offset:0 atIndex:30];
      for (int k = 0; k < 4; k++)
        [e drawIndexedPrimitives:MTLPrimitiveTypePoint indexType:MTLIndexTypeUInt16 indexBuffer:ib16 indexBufferOffset:0
                   indirectBuffer:ab indirectBufferOffset:k * sizeof(MTLDrawIndexedPrimitivesIndirectArguments)];
      [e endEncoding]; [cb commit]; [cb waitUntilCompleted];
      readback(dev, q, rt, "T2 indexed indirect u16 indexStart=k+1 (expect 2001 3002 4003 5004)");
    }
    // Test 3: direct indexed draws with uint16 and indexBufferOffset = 2*(k+1) (odd element offsets for k=0,2).
    {
      id<MTLCommandBuffer> cb = [q commandBuffer];
      id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
      [e setRenderPipelineState:psV];
      [e setVertexBuffer:verts offset:0 atIndex:30];
      for (int k = 0; k < 4; k++)
        [e drawIndexedPrimitives:MTLPrimitiveTypePoint indexCount:1 indexType:MTLIndexTypeUInt16 indexBuffer:ib16
                indexBufferOffset:2 * (k + 1) instanceCount:1 baseVertex:0 baseInstance:k];
      [e endEncoding]; [cb commit]; [cb waitUntilCompleted];
      readback(dev, q, rt, "T3 direct u16 indexBufferOffset=2(k+1) (expect 2001 3002 4003 5004)");
    }
    // Test 4: constant buffer bound at offset 4 and 16 (alignment probe; run with MTL_DEBUG_LAYER=1 to see validation).
    {
      id<MTLCommandBuffer> cb = [q commandBuffer];
      id<MTLRenderCommandEncoder> e = [cb renderCommandEncoderWithDescriptor:rp];
      [e setRenderPipelineState:psI];
      [e setVertexBuffer:inst offset:0 atIndex:30];
      [e setVertexBuffer:bias offset:4 atIndex:1];
      [e drawPrimitives:MTLPrimitiveTypePoint vertexStart:0 vertexCount:1 instanceCount:1 baseInstance:0];
      [e setVertexBufferOffset:16 atIndex:1];
      [e drawPrimitives:MTLPrimitiveTypePoint vertexStart:0 vertexCount:1 instanceCount:1 baseInstance:1];
      [e endEncoding]; [cb commit]; [cb waitUntilCompleted];
      readback(dev, q, rt, "T4 const offset 4 then 16 (expect 107 151 0 0)");
    }
  }
  return 0;
}
