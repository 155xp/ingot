// Shader libraries, render pipeline states and depth-stencil states. All entry points are thread-safe (they run on
// the pipeline-compile workers).
#import "ingot.h"
#import "ingot_internal.h"

#include <stddef.h>

_Static_assert(sizeof(IngotVertexAttribute) == 16, "IngotVertexAttribute layout");
_Static_assert(sizeof(IngotVertexLayout) == 16, "IngotVertexLayout layout");
_Static_assert(sizeof(IngotColorTarget) == 40, "IngotColorTarget layout");
_Static_assert(offsetof(IngotPipelineDesc, vertex_function) == 0, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, fragment_function) == 8, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, attributes) == 16, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, layouts) == 512, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, colors) == 1008, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, depth_pixel_format) == 1328, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, stencil_pixel_format) == 1332, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, sample_count) == 1336, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, input_primitive_topology) == 1340, "IngotPipelineDesc layout");
_Static_assert(offsetof(IngotPipelineDesc, label) == 1344, "IngotPipelineDesc layout");
_Static_assert(sizeof(IngotPipelineDesc) == 1352, "IngotPipelineDesc layout");

MTLCompileOptions *ingot_compile_options(void) {
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion2_4;
    // IEEE-conforming math: the GL and Vulkan backends do not relax floating point either.
    if (@available(macOS 15.0, *)) {
        options.mathMode = MTLMathModeSafe;
    } else {
        options.fastMathEnabled = NO;
    }
    options.preserveInvariance = YES;
    return options;
}

INGOT_EXPORT void *ingot_library_create(void *device, const char *msl_source, const char *label) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        NSString *source = msl_source != NULL ? [[NSString alloc] initWithUTF8String:msl_source] : nil;
        if (source == nil) {
            ingot_set_error_cstr("MSL source is null or not valid UTF-8");
            return NULL;
        }
        NSError *error = nil;
        id<MTLLibrary> library = [dev->mtl newLibraryWithSource:source options:ingot_compile_options() error:&error];
        if (library == nil) {
            ingot_set_error([NSString stringWithFormat:@"MSL compilation failed%@%s: %@", label != NULL ? @" for " : @"",
                                                       label != NULL ? label : "", error.localizedDescription ?: @"unknown error"]);
            return NULL;
        }
        NSString *name = ingot_label(label, dev->labels);
        if (name != nil) {
            library.label = name;
        }
        return (void *)CFBridgingRetain(library);
    }
}

INGOT_EXPORT void *ingot_library_function(void *library, const char *name) {
    @autoreleasepool {
        NSString *function_name = name != NULL ? [[NSString alloc] initWithUTF8String:name] : nil;
        id<MTLFunction> function = function_name != nil ? [INGOT_OBJ(id<MTLLibrary>, library) newFunctionWithName:function_name] : nil;
        if (function == nil) {
            ingot_set_error([NSString stringWithFormat:@"function '%s' not found in library", name != NULL ? name : "(null)"]);
            return NULL;
        }
        return (void *)CFBridgingRetain(function);
    }
}

static MTLVertexDescriptor *vertex_descriptor(const IngotPipelineDesc *desc) {
    MTLVertexDescriptor *vertex = nil;
    for (NSUInteger i = 0; i < INGOT_MAX_VERTEX_ATTRIBUTES; i++) {
        const IngotVertexAttribute *attribute = &desc->attributes[i];
        if (attribute->format == MTLVertexFormatInvalid) {
            continue;
        }
        if (vertex == nil) {
            vertex = [MTLVertexDescriptor vertexDescriptor];
        }
        MTLVertexAttributeDescriptor *out = vertex.attributes[i];
        out.format = (MTLVertexFormat)attribute->format;
        out.offset = attribute->offset;
        out.bufferIndex = attribute->buffer_index;
    }
    for (NSUInteger i = 0; i < INGOT_MAX_VERTEX_ATTRIBUTES; i++) {
        const IngotVertexLayout *layout = &desc->layouts[i];
        if (layout->stride == 0) {
            continue;
        }
        if (vertex == nil) {
            vertex = [MTLVertexDescriptor vertexDescriptor];
        }
        MTLVertexBufferLayoutDescriptor *out = vertex.layouts[i];
        MTLVertexStepFunction step = (MTLVertexStepFunction)layout->step_function;
        out.stride = layout->stride;
        out.stepFunction = step;
        // Metal requires a step rate of 0 for constant layouts and >= 1 otherwise.
        out.stepRate = step == MTLVertexStepFunctionConstant ? 0 : (layout->step_rate > 0 ? layout->step_rate : 1);
    }
    return vertex;
}

INGOT_EXPORT void *ingot_pipeline_create(void *device, const void *desc_ptr) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        const IngotPipelineDesc *desc = desc_ptr;
        if (desc->vertex_function == NULL) {
            ingot_set_error_cstr("pipeline has no vertex function");
            return NULL;
        }

        MTLRenderPipelineDescriptor *pipeline = [MTLRenderPipelineDescriptor new];
        pipeline.vertexFunction = INGOT_OBJ(id<MTLFunction>, desc->vertex_function);
        if (desc->fragment_function != NULL) {
            pipeline.fragmentFunction = INGOT_OBJ(id<MTLFunction>, desc->fragment_function);
        }
        pipeline.vertexDescriptor = vertex_descriptor(desc);
        for (NSUInteger i = 0; i < INGOT_MAX_COLOR_TARGETS; i++) {
            const IngotColorTarget *target = &desc->colors[i];
            if (target->pixel_format == MTLPixelFormatInvalid) {
                continue;
            }
            MTLRenderPipelineColorAttachmentDescriptor *out = pipeline.colorAttachments[i];
            out.pixelFormat = (MTLPixelFormat)target->pixel_format;
            out.writeMask = (MTLColorWriteMask)target->write_mask;
            out.blendingEnabled = target->blend_enabled != 0;
            if (target->blend_enabled != 0) {
                out.rgbBlendOperation = (MTLBlendOperation)target->rgb_op;
                out.alphaBlendOperation = (MTLBlendOperation)target->alpha_op;
                out.sourceRGBBlendFactor = (MTLBlendFactor)target->src_rgb;
                out.destinationRGBBlendFactor = (MTLBlendFactor)target->dst_rgb;
                out.sourceAlphaBlendFactor = (MTLBlendFactor)target->src_alpha;
                out.destinationAlphaBlendFactor = (MTLBlendFactor)target->dst_alpha;
            }
        }
        pipeline.depthAttachmentPixelFormat = (MTLPixelFormat)desc->depth_pixel_format;
        pipeline.stencilAttachmentPixelFormat = (MTLPixelFormat)desc->stencil_pixel_format;
        pipeline.rasterSampleCount = desc->sample_count > 0 ? desc->sample_count : 1;
        pipeline.inputPrimitiveTopology = (MTLPrimitiveTopologyClass)desc->input_primitive_topology;
        NSString *name = ingot_label(desc->label, dev->labels);
        if (name != nil) {
            pipeline.label = name;
        }

        NSError *error = nil;
        id<MTLRenderPipelineState> state = [dev->mtl newRenderPipelineStateWithDescriptor:pipeline error:&error];
        if (state == nil) {
            ingot_set_error([NSString stringWithFormat:@"render pipeline creation failed%@%s: %@", desc->label != NULL ? @" for " : @"",
                                                       desc->label != NULL ? desc->label : "", error.localizedDescription ?: @"unknown error"]);
            return NULL;
        }
        return (void *)CFBridgingRetain(state);
    }
}

INGOT_EXPORT void *ingot_depth_stencil_create(void *device, uint32_t compare_function, uint32_t depth_write) {
    @autoreleasepool {
        MTLDepthStencilDescriptor *desc = [MTLDepthStencilDescriptor new];
        desc.depthCompareFunction = (MTLCompareFunction)compare_function;
        desc.depthWriteEnabled = depth_write != 0;
        id<MTLDepthStencilState> state = [INGOT_OBJ(IngotDevice *, device)->mtl newDepthStencilStateWithDescriptor:desc];
        if (state == nil) {
            ingot_set_error_cstr("newDepthStencilStateWithDescriptor failed");
            return NULL;
        }
        return (void *)CFBridgingRetain(state);
    }
}
