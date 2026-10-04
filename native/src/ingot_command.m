// Command buffers (creation, commit with completion tracking, present, debug groups) and the blit encoder.
#import "ingot.h"
#import "ingot_internal.h"

INGOT_EXPORT void *ingot_cmd_create(void *device, const char *label) {
    @autoreleasepool {
        __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        // Unretained references skip a retain/release per bound resource; Java keeps resources alive until the
        // serial that used them completes (deferred release).
        id<MTLCommandBuffer> cmd = (dev->flags & INGOT_DEVICE_FLAG_RETAIN_REFS) != 0 ? [dev->queue commandBuffer]
                                                                                    : [dev->queue commandBufferWithUnretainedReferences];
        if (cmd == nil) {
            ingot_set_error_cstr("command buffer creation failed");
            return NULL;
        }
        NSString *name = ingot_label(label, dev->labels);
        if (name != nil) {
            cmd.label = name;
        }
        return (void *)CFBridgingRetain(cmd);
    }
}

INGOT_EXPORT void ingot_cmd_commit(void *device, void *cmd, uint64_t serial) {
    @autoreleasepool {
        IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        id<MTLCommandBuffer> command_buffer = CFBridgingRelease(cmd);
        atomic_store_explicit(&dev->committed_serial, serial, memory_order_release);
        // The block keeps the device alive until the handler has run (ingot_device_destroy waits for it anyway).
        [command_buffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
            @autoreleasepool {
                ingot_device_command_completed(dev, done, serial);
            }
        }];
        [command_buffer commit];
    }
}

INGOT_EXPORT void ingot_cmd_present(void *cmd, void *drawable) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLCommandBuffer>, cmd) presentDrawable:INGOT_OBJ(id<MTLDrawable>, drawable)];
    }
}

INGOT_EXPORT void ingot_cmd_push_debug_group(void *cmd, const char *label) {
    if (!ingot_labels_enabled()) {
        return;
    }
    @autoreleasepool {
        [INGOT_OBJ(id<MTLCommandBuffer>, cmd) pushDebugGroup:ingot_label(label, YES) ?: @""];
    }
}

INGOT_EXPORT void ingot_cmd_pop_debug_group(void *cmd) {
    if (!ingot_labels_enabled()) {
        return;
    }
    @autoreleasepool {
        [INGOT_OBJ(id<MTLCommandBuffer>, cmd) popDebugGroup];
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Blit encoder
// ---------------------------------------------------------------------------------------------------------------

INGOT_EXPORT void *ingot_blit_begin(void *cmd) {
    @autoreleasepool {
        id<MTLBlitCommandEncoder> blit = [INGOT_OBJ(id<MTLCommandBuffer>, cmd) blitCommandEncoder];
        if (blit == nil) {
            ingot_set_error_cstr("blitCommandEncoder failed");
            return NULL;
        }
        return (void *)CFBridgingRetain(blit);
    }
}

INGOT_EXPORT void ingot_blit_end(void *blit) {
    @autoreleasepool {
        id<MTLBlitCommandEncoder> encoder = CFBridgingRelease(blit);
        [encoder endEncoding];
    }
}

INGOT_EXPORT void ingot_blit_copy_buffer(void *blit, void *src, uint64_t src_offset, void *dst, uint64_t dst_offset, uint64_t size) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBlitCommandEncoder>, blit) copyFromBuffer:INGOT_OBJ(id<MTLBuffer>, src)
                                                      sourceOffset:src_offset
                                                          toBuffer:INGOT_OBJ(id<MTLBuffer>, dst)
                                                 destinationOffset:dst_offset
                                                              size:size];
    }
}

INGOT_EXPORT void ingot_blit_copy_buffer_to_texture(void *blit, void *src, uint64_t src_offset, uint64_t bytes_per_row,
                                                    uint64_t bytes_per_image, void *dst, uint32_t slice, uint32_t level,
                                                    uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint32_t options) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBlitCommandEncoder>, blit) copyFromBuffer:INGOT_OBJ(id<MTLBuffer>, src)
                                                      sourceOffset:src_offset
                                                 sourceBytesPerRow:bytes_per_row
                                               sourceBytesPerImage:bytes_per_image
                                                        sourceSize:MTLSizeMake(width, height, 1)
                                                         toTexture:INGOT_OBJ(id<MTLTexture>, dst)
                                                  destinationSlice:slice
                                                  destinationLevel:level
                                                 destinationOrigin:MTLOriginMake(x, y, 0)
                                                           options:(MTLBlitOption)options];
    }
}

INGOT_EXPORT void ingot_blit_copy_texture_to_buffer(void *blit, void *src, uint32_t slice, uint32_t level, uint32_t x, uint32_t y,
                                                    uint32_t width, uint32_t height, void *dst, uint64_t dst_offset,
                                                    uint64_t bytes_per_row, uint64_t bytes_per_image, uint32_t options) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBlitCommandEncoder>, blit) copyFromTexture:INGOT_OBJ(id<MTLTexture>, src)
                                                        sourceSlice:slice
                                                        sourceLevel:level
                                                       sourceOrigin:MTLOriginMake(x, y, 0)
                                                         sourceSize:MTLSizeMake(width, height, 1)
                                                           toBuffer:INGOT_OBJ(id<MTLBuffer>, dst)
                                                  destinationOffset:dst_offset
                                             destinationBytesPerRow:bytes_per_row
                                           destinationBytesPerImage:bytes_per_image
                                                            options:(MTLBlitOption)options];
    }
}

INGOT_EXPORT void ingot_blit_copy_texture(void *blit, void *src, uint32_t src_slice, uint32_t src_level, uint32_t src_x, uint32_t src_y,
                                          void *dst, uint32_t dst_slice, uint32_t dst_level, uint32_t dst_x, uint32_t dst_y,
                                          uint32_t width, uint32_t height) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBlitCommandEncoder>, blit) copyFromTexture:INGOT_OBJ(id<MTLTexture>, src)
                                                        sourceSlice:src_slice
                                                        sourceLevel:src_level
                                                       sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                                                         sourceSize:MTLSizeMake(width, height, 1)
                                                          toTexture:INGOT_OBJ(id<MTLTexture>, dst)
                                                   destinationSlice:dst_slice
                                                   destinationLevel:dst_level
                                                  destinationOrigin:MTLOriginMake(dst_x, dst_y, 0)];
    }
}

INGOT_EXPORT void ingot_blit_fill_buffer(void *blit, void *buffer, uint64_t offset, uint64_t length, uint32_t value) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBlitCommandEncoder>, blit) fillBuffer:INGOT_OBJ(id<MTLBuffer>, buffer)
                                                         range:NSMakeRange(offset, length)
                                                         value:(uint8_t)value];
    }
}

INGOT_EXPORT void ingot_blit_synchronize(void *blit, void *resource) {
    @autoreleasepool {
        [INGOT_OBJ(id<MTLBlitCommandEncoder>, blit) synchronizeResource:INGOT_OBJ(id<MTLResource>, resource)];
    }
}
