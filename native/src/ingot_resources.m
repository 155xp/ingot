// Buffers, textures, texture views, texture-buffer views and samplers. All creation functions are thread-safe.
#import "ingot.h"
#import "ingot_internal.h"

INGOT_EXPORT void *ingot_buffer_create(void *device, uint64_t length, uint32_t resource_options, const void *data, const char *label) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        if (length == 0 || length > dev->max_buffer_length) {
            ingot_set_error([NSString stringWithFormat:@"buffer length %llu is outside 1..maxBufferLength (%lu)", length,
                                                       (unsigned long)dev->max_buffer_length]);
            return NULL;
        }
        MTLResourceOptions options = (MTLResourceOptions)resource_options;
        id<MTLBuffer> buffer = data != NULL ? [dev->mtl newBufferWithBytes:data length:length options:options]
                                            : [dev->mtl newBufferWithLength:length options:options];
        if (buffer == nil) {
            ingot_set_error([NSString stringWithFormat:@"newBuffer failed (length %llu, options 0x%x): out of memory", length, resource_options]);
            return NULL;
        }
        NSString *name = ingot_label(label, dev->labels);
        if (name != nil) {
            buffer.label = name;
        }
        return (void *)CFBridgingRetain(buffer);
    }
}

// -[MTLBuffer contents] is declared NS_RETURNS_INNER_POINTER, for which ARC retains and autoreleases the receiver on
// every call. The handle is kept alive by its owner, so the message is sent through this unannotated declaration.
@protocol IngotBufferContents
- (void *)contents;
@end

INGOT_EXPORT void *ingot_buffer_contents(void *buffer) {
    return [INGOT_OBJ(id<IngotBufferContents>, buffer) contents];
}

INGOT_EXPORT void ingot_buffer_did_modify_range(void *buffer, uint64_t offset, uint64_t length) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBuffer>, buffer) didModifyRange:NSMakeRange(offset, length)];
    }
}

INGOT_EXPORT void *ingot_texture_create(void *device, uint32_t texture_type, uint32_t pixel_format, uint32_t width, uint32_t height,
                                        uint32_t mip_levels, uint32_t usage, uint32_t storage_mode, const char *label) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        if (width == 0 || height == 0 || width > dev->max_texture_size || height > dev->max_texture_size) {
            ingot_set_error([NSString stringWithFormat:@"texture size %ux%u is outside 1..%lu (the device maximum)", width, height,
                                                       (unsigned long)dev->max_texture_size]);
            return NULL;
        }
        MTLTextureDescriptor *desc = [MTLTextureDescriptor new];
        desc.textureType = (MTLTextureType)texture_type;
        desc.pixelFormat = (MTLPixelFormat)pixel_format;
        desc.width = width;
        desc.height = height;
        desc.depth = 1;
        desc.arrayLength = 1;
        desc.mipmapLevelCount = mip_levels > 0 ? mip_levels : 1;
        desc.sampleCount = 1;
        desc.usage = (MTLTextureUsage)usage;
        desc.storageMode = (MTLStorageMode)storage_mode;
        id<MTLTexture> texture = [dev->mtl newTextureWithDescriptor:desc];
        if (texture == nil) {
            ingot_set_error([NSString stringWithFormat:@"newTexture failed (type %u, format %u, %ux%u, %u levels, usage 0x%x, storage %u): "
                                                       @"out of memory",
                                                       texture_type, pixel_format, width, height, mip_levels, usage, storage_mode]);
            return NULL;
        }
        NSString *name = ingot_label(label, dev->labels);
        if (name != nil) {
            texture.label = name;
        }
        return (void *)CFBridgingRetain(texture);
    }
}

INGOT_EXPORT void *ingot_texture_view_create(void *texture, uint32_t pixel_format, uint32_t texture_type, uint32_t base_level,
                                             uint32_t level_count, uint32_t base_slice, uint32_t slice_count) {
    @autoreleasepool {
        id<MTLTexture> view = [INGOT_OBJ(id<MTLTexture>, texture) newTextureViewWithPixelFormat:(MTLPixelFormat)pixel_format
                                                                                    textureType:(MTLTextureType)texture_type
                                                                                         levels:NSMakeRange(base_level, level_count)
                                                                                         slices:NSMakeRange(base_slice, slice_count)];
        if (view == nil) {
            ingot_set_error([NSString stringWithFormat:@"newTextureView failed (format %u, type %u, levels %u+%u, slices %u+%u)",
                                                       pixel_format, texture_type, base_level, level_count, base_slice, slice_count]);
            return NULL;
        }
        return (void *)CFBridgingRetain(view);
    }
}

INGOT_EXPORT void *ingot_texture_buffer_create(void *buffer, uint32_t pixel_format, uint64_t offset, uint64_t element_count,
                                               uint64_t bytes_per_row) {
    @autoreleasepool {
        // A zero-width descriptor trips a Metal assertion that aborts the process (a slice shorter than one texel).
        if (element_count == 0) {
            ingot_set_error_cstr("texture buffer view has no elements");
            return NULL;
        }
        __unsafe_unretained id<MTLBuffer> source = INGOT_OBJ(id<MTLBuffer>, buffer);
        // The descriptor's resource options must match the buffer's (storage and CPU cache mode).
        MTLTextureDescriptor *desc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:(MTLPixelFormat)pixel_format
                                                                                            width:element_count
                                                                                  resourceOptions:source.resourceOptions
                                                                                            usage:MTLTextureUsageShaderRead];
        id<MTLTexture> view = [source newTextureWithDescriptor:desc offset:offset bytesPerRow:bytes_per_row];
        if (view == nil) {
            ingot_set_error([NSString stringWithFormat:@"texture buffer view failed (format %u, offset %llu, %llu elements, %llu bytes per row)",
                                                       pixel_format, offset, element_count, bytes_per_row]);
            return NULL;
        }
        return (void *)CFBridgingRetain(view);
    }
}

INGOT_EXPORT void *ingot_sampler_create(void *device, uint32_t address_u, uint32_t address_v, uint32_t min_filter, uint32_t mag_filter,
                                        uint32_t mip_filter, uint32_t max_anisotropy, float lod_min, float lod_max, const char *label) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        MTLSamplerDescriptor *desc = [MTLSamplerDescriptor new];
        desc.sAddressMode = (MTLSamplerAddressMode)address_u;
        desc.tAddressMode = (MTLSamplerAddressMode)address_v;
        // The GL and Vulkan backends leave the third coordinate at their default, REPEAT.
        desc.rAddressMode = MTLSamplerAddressModeRepeat;
        desc.minFilter = (MTLSamplerMinMagFilter)min_filter;
        desc.magFilter = (MTLSamplerMinMagFilter)mag_filter;
        desc.mipFilter = (MTLSamplerMipFilter)mip_filter;
        desc.maxAnisotropy = max_anisotropy < 1 ? 1 : (max_anisotropy > 16 ? 16 : max_anisotropy);
        desc.lodMinClamp = lod_min;
        desc.lodMaxClamp = lod_max;
        NSString *name = ingot_label(label, dev->labels);
        if (name != nil) {
            desc.label = name;
        }
        id<MTLSamplerState> sampler = [dev->mtl newSamplerStateWithDescriptor:desc];
        if (sampler == nil) {
            ingot_set_error_cstr("newSamplerStateWithDescriptor failed");
            return NULL;
        }
        return (void *)CFBridgingRetain(sampler);
    }
}
