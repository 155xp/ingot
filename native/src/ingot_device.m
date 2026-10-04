// Device creation, capability queries and GPU completion tracking (serials, GPU times, errors).
#import "ingot.h"
#import "ingot_internal.h"

#include <mach/mach_time.h>

@implementation IngotDevice

- (void)dealloc {
    pthread_cond_destroy(&cond);
    pthread_mutex_destroy(&lock);
}

@end

INGOT_EXPORT void *ingot_device_create(uint32_t flags) {
    @autoreleasepool {
        id<MTLDevice> mtl = MTLCreateSystemDefaultDevice();
        if (mtl == nil) {
            ingot_set_error_cstr("MTLCreateSystemDefaultDevice returned nil (no Metal device)");
            return NULL;
        }
        // 64 command buffers in flight is far more than the backend ever has (two frames, a few buffers each);
        // the queue blocks commandBuffer creation beyond it.
        id<MTLCommandQueue> queue = [mtl newCommandQueueWithMaxCommandBufferCount:64];
        if (queue == nil) {
            ingot_set_error_cstr("newCommandQueueWithMaxCommandBufferCount failed");
            return NULL;
        }

        IngotDevice *device = [IngotDevice new];
        device->mtl = mtl;
        device->queue = queue;
        device->flags = flags;
        device->labels = (flags & INGOT_DEVICE_FLAG_DEBUG_LABELS) != 0;
        device->max_buffer_length = mtl.maxBufferLength;
        // Metal feature set tables: 16384 for Mac2 and Apple3+, 8192 below.
        device->max_texture_size = ([mtl supportsFamily:MTLGPUFamilyMac2] || [mtl supportsFamily:MTLGPUFamilyApple3]) ? 16384 : 8192;
        pthread_mutex_init(&device->lock, NULL);
        pthread_cond_init(&device->cond, NULL);
        device->util_lock = OS_UNFAIR_LOCK_INIT;
        device->util_pipelines = [NSMutableDictionary new];
        strlcpy(device->name, mtl.name.UTF8String ?: "", sizeof(device->name));
        if (device->labels) {
            queue.label = @"Ingot";
        }
        ingot_set_labels_enabled(device->labels);
        return (void *)CFBridgingRetain(device);
    }
}

INGOT_EXPORT void ingot_device_destroy(void *device) {
    if (device == NULL) {
        return;
    }
    @autoreleasepool {
        IngotDevice *dev = CFBridgingRelease(device);
        ingot_device_wait_serial(device, atomic_load_explicit(&dev->committed_serial, memory_order_acquire), -1);
        // Completion handler blocks hold their own reference, so the object outlives any handler still unwinding.
    }
}

INGOT_EXPORT const char *ingot_device_name(void *device) {
    return INGOT_OBJ(IngotDevice *, device)->name;
}

static uint64_t highest_apple_family(id<MTLDevice> mtl) {
    for (NSInteger family = MTLGPUFamilyApple10; family >= MTLGPUFamilyApple1; family--) {
        if ([mtl supportsFamily:(MTLGPUFamily)family]) {
            return (uint64_t)(family - MTLGPUFamilyApple1 + 1);
        }
    }
    return 0;
}

INGOT_EXPORT uint64_t ingot_device_query(void *device, uint32_t key) {
    @autoreleasepool {
        __unsafe_unretained id<MTLDevice> mtl = INGOT_OBJ(IngotDevice *, device)->mtl;
        switch (key) {
            case INGOT_QUERY_UNIFIED_MEMORY:
                return mtl.hasUnifiedMemory ? 1 : 0;
            case INGOT_QUERY_MAX_BUFFER_LENGTH:
                return INGOT_OBJ(IngotDevice *, device)->max_buffer_length;
            case INGOT_QUERY_WORKING_SET_SIZE:
                return mtl.recommendedMaxWorkingSetSize;
            case INGOT_QUERY_APPLE_FAMILY:
                return highest_apple_family(mtl);
            case INGOT_QUERY_MAC2_FAMILY:
                return [mtl supportsFamily:MTLGPUFamilyMac2] ? 1 : 0;
            case INGOT_QUERY_MAX_TEXTURE_SIZE:
                return INGOT_OBJ(IngotDevice *, device)->max_texture_size;
            case INGOT_QUERY_REGISTRY_ID:
                return mtl.registryID;
            case INGOT_QUERY_LOW_POWER:
                return mtl.isLowPower ? 1 : 0;
            case INGOT_QUERY_REMOVABLE:
                return mtl.isRemovable ? 1 : 0;
            case INGOT_QUERY_MAX_ANISOTROPY:
                return 16;
            case INGOT_QUERY_BUFFER_OFFSET_ALIGNMENT:
                // Constant buffer offsets: 4 bytes on Apple-family GPUs, 256 bytes on Mac2-only (Intel/AMD) GPUs.
                return [mtl supportsFamily:MTLGPUFamilyApple1] ? 4 : 256;
            case INGOT_QUERY_CURRENT_ALLOCATED_SIZE:
                return mtl.currentAllocatedSize;
            case INGOT_QUERY_ARCH_ARM64:
#if defined(__arm64__)
                return 1;
#else
                return 0;
#endif
            default:
                return 0;
        }
    }
}

INGOT_EXPORT uint64_t ingot_device_texture_buffer_alignment(void *device, uint32_t pixel_format) {
    @autoreleasepool {
        return [INGOT_OBJ(IngotDevice *, device)->mtl minimumTextureBufferAlignmentForPixelFormat:(MTLPixelFormat)pixel_format];
    }
}

INGOT_EXPORT uint64_t ingot_device_completed_serial(void *device) {
    return atomic_load_explicit(&INGOT_OBJ(IngotDevice *, device)->completed_serial, memory_order_acquire);
}

void ingot_device_command_completed(IngotDevice *device, id<MTLCommandBuffer> command_buffer, uint64_t serial) {
    // GPUStartTime/GPUEndTime are host-time seconds (the mach_absolute_time clock, like CACurrentMediaTime), so
    // scaling to nanoseconds lands in the System.nanoTime() domain.
    int64_t start_ns = (int64_t)(command_buffer.GPUStartTime * 1e9);
    int64_t end_ns = (int64_t)(command_buffer.GPUEndTime * 1e9);
    BOOL failed = command_buffer.status == MTLCommandBufferStatusError;

    pthread_mutex_lock(&device->lock);
    if (failed && atomic_load_explicit(&device->error_code, memory_order_relaxed) == 0) {
        NSError *error = command_buffer.error;
        const char *message = error.localizedDescription.UTF8String;
        strlcpy(device->error_message, message != NULL ? message : "unknown command buffer error", sizeof(device->error_message));
        // Code 0 is not a valid MTLCommandBufferError, but keep "non-zero means failed" true regardless.
        uint32_t code = error != nil && error.code != 0 ? (uint32_t)error.code : (uint32_t)MTLCommandBufferErrorInternal;
        atomic_store_explicit(&device->error_code, code, memory_order_release);
    }
    IngotSerialTimes *slot = &device->times[serial & (INGOT_SERIAL_HISTORY - 1)];
    slot->serial = serial;
    slot->gpu_start_ns = start_ns;
    slot->gpu_end_ns = end_ns;
    // Handlers of one queue run in commit order, but keep the published value monotonic regardless.
    if (serial > atomic_load_explicit(&device->completed_serial, memory_order_relaxed)) {
        atomic_store_explicit(&device->completed_serial, serial, memory_order_release);
    }
    pthread_cond_broadcast(&device->cond);
    pthread_mutex_unlock(&device->lock);
}

INGOT_EXPORT int32_t ingot_device_wait_serial(void *device, uint64_t serial, int64_t timeout_ns) {
    __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
    if (atomic_load_explicit(&dev->completed_serial, memory_order_acquire) >= serial) {
        return 1;
    }
    if (timeout_ns == 0) {
        return 0;
    }

    uint64_t deadline = timeout_ns > 0 ? mach_absolute_time() + ingot_ns_to_ticks_ceil(timeout_ns) : 0;
    pthread_mutex_lock(&dev->lock);
    while (atomic_load_explicit(&dev->completed_serial, memory_order_acquire) < serial) {
        if (timeout_ns < 0) {
            pthread_cond_wait(&dev->cond, &dev->lock);
            continue;
        }
        // Relative waits are immune to wall-clock changes (pthread_cond_timedwait takes a CLOCK_REALTIME deadline).
        uint64_t now = mach_absolute_time();
        if (now >= deadline) {
            break;
        }
        int64_t remaining_ns = ingot_ticks_to_ns(deadline - now);
        struct timespec relative = {.tv_sec = remaining_ns / 1000000000, .tv_nsec = remaining_ns % 1000000000};
        pthread_cond_timedwait_relative_np(&dev->cond, &dev->lock, &relative);
    }
    int32_t done = atomic_load_explicit(&dev->completed_serial, memory_order_acquire) >= serial;
    pthread_mutex_unlock(&dev->lock);
    return done;
}

INGOT_EXPORT uint32_t ingot_device_error_code(void *device) {
    return atomic_load_explicit(&INGOT_OBJ(IngotDevice *, device)->error_code, memory_order_acquire);
}

INGOT_EXPORT const char *ingot_device_error_message(void *device) {
    __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
    // The message is written once, before the code is published, and never changes afterwards.
    return atomic_load_explicit(&dev->error_code, memory_order_acquire) != 0 ? dev->error_message : "";
}

INGOT_EXPORT int32_t ingot_device_serial_times(void *device, uint64_t serial, void *out) {
    __unsafe_unretained IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
    int64_t *times = out;
    int32_t found = 0;
    pthread_mutex_lock(&dev->lock);
    const IngotSerialTimes *slot = &dev->times[serial & (INGOT_SERIAL_HISTORY - 1)];
    if (serial != 0 && slot->serial == serial) {
        times[0] = slot->gpu_start_ns;
        times[1] = slot->gpu_end_ns;
        found = 1;
    }
    pthread_mutex_unlock(&dev->lock);
    return found;
}
