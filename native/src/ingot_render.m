// Render command encoder: pass setup from IngotRenderPassDesc, state setters and draws. Everything after
// ingot_render_begin is INGOT_HOT: no allocation, no autoreleased objects, no retain/release of the encoder.
#import "ingot.h"
#import "ingot_internal.h"

#include <stddef.h>

_Static_assert(offsetof(IngotColorAttachment, texture) == 0, "IngotColorAttachment layout");
_Static_assert(offsetof(IngotColorAttachment, level) == 8, "IngotColorAttachment layout");
_Static_assert(offsetof(IngotColorAttachment, slice) == 12, "IngotColorAttachment layout");
_Static_assert(offsetof(IngotColorAttachment, load_action) == 16, "IngotColorAttachment layout");
_Static_assert(offsetof(IngotColorAttachment, store_action) == 20, "IngotColorAttachment layout");
_Static_assert(offsetof(IngotColorAttachment, clear) == 24, "IngotColorAttachment layout");
_Static_assert(sizeof(IngotColorAttachment) == 40, "IngotColorAttachment layout");
_Static_assert(offsetof(IngotRenderPassDesc, colors) == 0, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, depth_texture) == 320, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, depth_level) == 328, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, depth_load_action) == 332, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, depth_store_action) == 336, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, clear_depth) == 340, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, stencil_texture) == 344, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, stencil_load_action) == 352, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, stencil_store_action) == 356, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, clear_stencil) == 360, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, _pad) == 364, "IngotRenderPassDesc layout");
_Static_assert(offsetof(IngotRenderPassDesc, label) == 368, "IngotRenderPassDesc layout");
_Static_assert(sizeof(IngotRenderPassDesc) == 376, "IngotRenderPassDesc layout");

// The indirect draw loops read records with these exact layouts (the renderpearl INDIRECT_*_DRAW_SIZE values).
_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16, "MTLDrawPrimitivesIndirectArguments layout");
_Static_assert(sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "MTLDrawIndexedPrimitivesIndirectArguments layout");

#define ENCODER(pointer) INGOT_OBJ(id<MTLRenderCommandEncoder>, pointer)

static inline NSUInteger index_size(uint32_t index_type) {
    return index_type == MTLIndexTypeUInt16 ? 2 : 4;
}

INGOT_EXPORT void *ingot_render_begin(void *cmd, const void *desc_ptr) {
    @autoreleasepool {
        const IngotRenderPassDesc *desc = desc_ptr;
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor new];
        for (NSUInteger i = 0; i < INGOT_MAX_COLOR_TARGETS; i++) {
            const IngotColorAttachment *color = &desc->colors[i];
            if (color->texture == NULL) {
                continue;
            }
            MTLRenderPassColorAttachmentDescriptor *out = pass.colorAttachments[i];
            out.texture = INGOT_OBJ(id<MTLTexture>, color->texture);
            out.level = color->level;
            out.slice = color->slice;
            out.loadAction = (MTLLoadAction)color->load_action;
            out.storeAction = (MTLStoreAction)color->store_action;
            out.clearColor = MTLClearColorMake(color->clear[0], color->clear[1], color->clear[2], color->clear[3]);
        }
        if (desc->depth_texture != NULL) {
            MTLRenderPassDepthAttachmentDescriptor *depth = pass.depthAttachment;
            depth.texture = INGOT_OBJ(id<MTLTexture>, desc->depth_texture);
            depth.level = desc->depth_level;
            depth.loadAction = (MTLLoadAction)desc->depth_load_action;
            depth.storeAction = (MTLStoreAction)desc->depth_store_action;
            depth.clearDepth = desc->clear_depth;
        }
        if (desc->stencil_texture != NULL) {
            MTLRenderPassStencilAttachmentDescriptor *stencil = pass.stencilAttachment;
            stencil.texture = INGOT_OBJ(id<MTLTexture>, desc->stencil_texture);
            stencil.level = desc->depth_level;
            stencil.loadAction = (MTLLoadAction)desc->stencil_load_action;
            stencil.storeAction = (MTLStoreAction)desc->stencil_store_action;
            stencil.clearStencil = desc->clear_stencil;
        }

        id<MTLRenderCommandEncoder> encoder = [INGOT_OBJ(id<MTLCommandBuffer>, cmd) renderCommandEncoderWithDescriptor:pass];
        if (encoder == nil) {
            ingot_set_error_cstr("renderCommandEncoderWithDescriptor failed");
            return NULL;
        }
        NSString *name = ingot_label(desc->label, ingot_labels_enabled());
        if (name != nil) {
            encoder.label = name;
        }
        return (void *)CFBridgingRetain(encoder);
    }
}

INGOT_EXPORT void ingot_render_end(void *encoder) {
    @autoreleasepool {
        id<MTLRenderCommandEncoder> render = CFBridgingRelease(encoder);
        [render endEncoding];
    }
}

INGOT_EXPORT void ingot_render_set_pipeline(void *encoder, void *pipeline_state) {
    [ENCODER(encoder) setRenderPipelineState:INGOT_OBJ(id<MTLRenderPipelineState>, pipeline_state)];
}

INGOT_EXPORT void ingot_render_set_depth_stencil(void *encoder, void *depth_stencil_state) {
    [ENCODER(encoder) setDepthStencilState:INGOT_OBJ(id<MTLDepthStencilState>, depth_stencil_state)];
}

INGOT_EXPORT void ingot_render_set_raster(void *encoder, uint32_t cull_mode, uint32_t winding, uint32_t fill_mode) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    [render setCullMode:(MTLCullMode)cull_mode];
    [render setFrontFacingWinding:(MTLWinding)winding];
    [render setTriangleFillMode:(MTLTriangleFillMode)fill_mode];
}

INGOT_EXPORT void ingot_render_set_depth_bias(void *encoder, float constant, float slope, float clamp) {
    [ENCODER(encoder) setDepthBias:constant slopeScale:slope clamp:clamp];
}

INGOT_EXPORT void ingot_render_set_viewport(void *encoder, double x, double y, double width, double height, double znear, double zfar) {
    [ENCODER(encoder) setViewport:(MTLViewport){x, y, width, height, znear, zfar}];
}

INGOT_EXPORT void ingot_render_set_scissor(void *encoder, uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    [ENCODER(encoder) setScissorRect:(MTLScissorRect){x, y, width, height}];
}

INGOT_EXPORT void ingot_render_set_buffer(void *encoder, uint32_t stages, void *buffer, uint64_t offset, uint32_t index) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLBuffer> mtl_buffer = INGOT_OBJ(id<MTLBuffer>, buffer);
    if ((stages & INGOT_STAGE_VERTEX) != 0) {
        [render setVertexBuffer:mtl_buffer offset:offset atIndex:index];
    }
    if ((stages & INGOT_STAGE_FRAGMENT) != 0) {
        [render setFragmentBuffer:mtl_buffer offset:offset atIndex:index];
    }
}

INGOT_EXPORT void ingot_render_set_buffer_offset(void *encoder, uint32_t stages, uint64_t offset, uint32_t index) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    if ((stages & INGOT_STAGE_VERTEX) != 0) {
        [render setVertexBufferOffset:offset atIndex:index];
    }
    if ((stages & INGOT_STAGE_FRAGMENT) != 0) {
        [render setFragmentBufferOffset:offset atIndex:index];
    }
}

INGOT_EXPORT void ingot_render_set_bytes(void *encoder, uint32_t stages, const void *bytes, uint64_t length, uint32_t index) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    if ((stages & INGOT_STAGE_VERTEX) != 0) {
        [render setVertexBytes:bytes length:length atIndex:index];
    }
    if ((stages & INGOT_STAGE_FRAGMENT) != 0) {
        [render setFragmentBytes:bytes length:length atIndex:index];
    }
}

INGOT_EXPORT void ingot_render_set_texture(void *encoder, uint32_t stages, void *texture, uint32_t index) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLTexture> mtl_texture = INGOT_OBJ(id<MTLTexture>, texture);
    if ((stages & INGOT_STAGE_VERTEX) != 0) {
        [render setVertexTexture:mtl_texture atIndex:index];
    }
    if ((stages & INGOT_STAGE_FRAGMENT) != 0) {
        [render setFragmentTexture:mtl_texture atIndex:index];
    }
}

INGOT_EXPORT void ingot_render_set_sampler(void *encoder, uint32_t stages, void *sampler, uint32_t index) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLSamplerState> mtl_sampler = INGOT_OBJ(id<MTLSamplerState>, sampler);
    if ((stages & INGOT_STAGE_VERTEX) != 0) {
        [render setVertexSamplerState:mtl_sampler atIndex:index];
    }
    if ((stages & INGOT_STAGE_FRAGMENT) != 0) {
        [render setFragmentSamplerState:mtl_sampler atIndex:index];
    }
}

// Direct draws with a zero vertex, index or instance count render nothing and are skipped before reaching Metal.

INGOT_EXPORT void ingot_render_draw(void *encoder, uint32_t primitive, uint32_t vertex_start, uint32_t vertex_count,
                                    uint32_t instance_count, uint32_t base_instance) {
    if (vertex_count == 0 || instance_count == 0) {
        return;
    }
    [ENCODER(encoder) drawPrimitives:(MTLPrimitiveType)primitive
                         vertexStart:vertex_start
                         vertexCount:vertex_count
                       instanceCount:instance_count
                        baseInstance:base_instance];
}

INGOT_EXPORT void ingot_render_draw_indexed(void *encoder, uint32_t primitive, uint32_t index_count, uint32_t index_type,
                                            void *index_buffer, uint64_t index_offset, uint32_t instance_count,
                                            int32_t base_vertex, uint32_t base_instance) {
    if (index_count == 0 || instance_count == 0) {
        return;
    }
    [ENCODER(encoder) drawIndexedPrimitives:(MTLPrimitiveType)primitive
                                 indexCount:index_count
                                  indexType:(MTLIndexType)index_type
                                indexBuffer:INGOT_OBJ(id<MTLBuffer>, index_buffer)
                          indexBufferOffset:index_offset
                              instanceCount:instance_count
                                 baseVertex:base_vertex
                               baseInstance:base_instance];
}

INGOT_EXPORT void ingot_render_multi_draw(void *encoder, uint32_t primitive, const void *params, uint32_t draw_count,
                                          uint32_t instance_count, uint32_t base_instance) {
    if (instance_count == 0) {
        return;
    }
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    const int32_t *draw = params;   // {firstVertex, vertexCount}
    for (uint32_t i = 0; i < draw_count; i++, draw += 2) {
        if (draw[1] <= 0) {
            continue;
        }
        [render drawPrimitives:(MTLPrimitiveType)primitive
                   vertexStart:(uint32_t)draw[0]
                   vertexCount:(uint32_t)draw[1]
                 instanceCount:instance_count
                  baseInstance:base_instance];
    }
}

INGOT_EXPORT void ingot_render_multi_draw_indexed(void *encoder, uint32_t primitive, uint32_t index_type, void *index_buffer,
                                                  uint64_t index_offset, const void *params, uint32_t draw_count,
                                                  uint32_t instance_count, uint32_t base_instance) {
    if (instance_count == 0) {
        return;
    }
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLBuffer> indices = INGOT_OBJ(id<MTLBuffer>, index_buffer);
    NSUInteger stride = index_size(index_type);
    const int32_t *draw = params;   // {firstIndex, indexCount, vertexOffset}
    for (uint32_t i = 0; i < draw_count; i++, draw += 3) {
        if (draw[1] <= 0) {
            continue;
        }
        [render drawIndexedPrimitives:(MTLPrimitiveType)primitive
                           indexCount:(uint32_t)draw[1]
                            indexType:(MTLIndexType)index_type
                          indexBuffer:indices
                    indexBufferOffset:index_offset + (uint32_t)draw[0] * stride
                        instanceCount:instance_count
                           baseVertex:draw[2]
                         baseInstance:base_instance];
    }
}

INGOT_EXPORT void ingot_render_multi_draw_indexed_separate(void *encoder, uint32_t primitive, uint32_t index_type, void *index_buffer,
                                                           const void *first_index_offsets, const void *index_counts,
                                                           const void *vertex_offsets, uint32_t draw_count) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLBuffer> indices = INGOT_OBJ(id<MTLBuffer>, index_buffer);
    const int64_t *offsets = first_index_offsets;   // byte offsets (glMultiDrawElementsBaseVertex semantics)
    const int32_t *counts = index_counts;
    const int32_t *base_vertices = vertex_offsets;
    for (uint32_t i = 0; i < draw_count; i++) {
        if (counts[i] <= 0) {
            continue;
        }
        [render drawIndexedPrimitives:(MTLPrimitiveType)primitive
                           indexCount:(uint32_t)counts[i]
                            indexType:(MTLIndexType)index_type
                          indexBuffer:indices
                    indexBufferOffset:(NSUInteger)offsets[i]
                        instanceCount:1
                           baseVertex:base_vertices[i]
                         baseInstance:0];
    }
}

INGOT_EXPORT void ingot_render_multi_draw_separate(void *encoder, uint32_t primitive, const void *first_vertices,
                                                   const void *vertex_counts, uint32_t draw_count) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    const int32_t *firsts = first_vertices;
    const int32_t *counts = vertex_counts;
    for (uint32_t i = 0; i < draw_count; i++) {
        if (counts[i] <= 0) {
            continue;
        }
        [render drawPrimitives:(MTLPrimitiveType)primitive vertexStart:(uint32_t)firsts[i] vertexCount:(uint32_t)counts[i]];
    }
}

INGOT_EXPORT void ingot_render_draw_indirect(void *encoder, uint32_t primitive, void *indirect_buffer, uint64_t indirect_offset,
                                             uint32_t draw_count, uint32_t stride) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLBuffer> arguments = INGOT_OBJ(id<MTLBuffer>, indirect_buffer);
    for (uint32_t i = 0; i < draw_count; i++) {
        [render drawPrimitives:(MTLPrimitiveType)primitive
                indirectBuffer:arguments
          indirectBufferOffset:indirect_offset + (uint64_t)i * stride];
    }
}

INGOT_EXPORT void ingot_render_draw_indexed_indirect(void *encoder, uint32_t primitive, uint32_t index_type, void *index_buffer,
                                                     uint64_t index_offset, void *indirect_buffer, uint64_t indirect_offset,
                                                     uint32_t draw_count, uint32_t stride) {
    __unsafe_unretained id<MTLRenderCommandEncoder> render = ENCODER(encoder);
    __unsafe_unretained id<MTLBuffer> indices = INGOT_OBJ(id<MTLBuffer>, index_buffer);
    __unsafe_unretained id<MTLBuffer> arguments = INGOT_OBJ(id<MTLBuffer>, indirect_buffer);
    for (uint32_t i = 0; i < draw_count; i++) {
        [render drawIndexedPrimitives:(MTLPrimitiveType)primitive
                            indexType:(MTLIndexType)index_type
                          indexBuffer:indices
                    indexBufferOffset:index_offset
                       indirectBuffer:arguments
                 indirectBufferOffset:indirect_offset + (uint64_t)i * stride];
    }
}

INGOT_EXPORT void ingot_render_push_debug_group(void *encoder, const char *label) {
    if (!ingot_labels_enabled()) {
        return;
    }
    @autoreleasepool {
        [ENCODER(encoder) pushDebugGroup:ingot_label(label, YES) ?: @""];
    }
}

INGOT_EXPORT void ingot_render_pop_debug_group(void *encoder) {
    if (!ingot_labels_enabled()) {
        return;
    }
    @autoreleasepool {
        [ENCODER(encoder) popDebugGroup];
    }
}
