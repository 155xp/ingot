// Declarations shared between the native translation units; not part of the Java-facing ABI.
#pragma once

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <os/lock.h>
#include <pthread.h>
#include <stdatomic.h>

#include "ingot.h"

// ---------------------------------------------------------------------------------------------------------------
// Handles. Hot entry points message the bridged pointer directly (or through an __unsafe_unretained local), so ARC
// emits no retain/release pair per call.
// ---------------------------------------------------------------------------------------------------------------

#define INGOT_OBJ(type, pointer) ((__bridge type)(pointer))

// ---------------------------------------------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------------------------------------------

#define INGOT_SERIAL_HISTORY 256u   // power of two; ingot_device_serial_times keeps this many serials

typedef struct IngotSerialTimes {
    uint64_t serial;
    int64_t gpu_start_ns;
    int64_t gpu_end_ns;
} IngotSerialTimes;

typedef NS_ENUM(uint32_t, IngotUtilKind) {
    IngotUtilKindClear = 1,   // ingot_util_clear_region; the attached formats pick the fragment function
    IngotUtilKindBlit = 2,    // ingot_surface_blit
};

@interface IngotDevice : NSObject {
@public
    id<MTLDevice> mtl;
    id<MTLCommandQueue> queue;
    uint32_t flags;
    BOOL labels;                      // INGOT_DEVICE_FLAG_DEBUG_LABELS
    NSUInteger max_buffer_length;     // creation requests beyond these limits fail with an error instead of
    NSUInteger max_texture_size;      // tripping Metal's descriptor assertions (which abort the process)

    // Completion tracking. completed_serial is published with release stores by the completion handlers (under
    // `lock`, so waiters can block on `cond`); error_code is published after error_message is written.
    _Atomic uint64_t completed_serial;
    _Atomic uint64_t committed_serial;
    _Atomic uint32_t error_code;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    IngotSerialTimes times[INGOT_SERIAL_HISTORY];   // guarded by lock, indexed by serial % INGOT_SERIAL_HISTORY
    char error_message[1024];
    char name[256];

    // Internal utility pipelines (clear region, present blit), created on first use.
    os_unfair_lock util_lock;
    id<MTLLibrary> util_library;
    NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *util_pipelines;
    id<MTLDepthStencilState> util_depth_write;      // compare always, depth write on
}
@end

// Called by the completion handler that ingot_cmd_commit installs.
void ingot_device_command_completed(IngotDevice *device, id<MTLCommandBuffer> command_buffer, uint64_t serial);

// MTLCompileOptions shared by ingot_library_create and the utility library: MSL 2.4, safe math, preserveInvariance.
MTLCompileOptions *ingot_compile_options(void);

// Cached utility pipeline for (kind, color format, depth format); nil with ingot_last_error set on failure.
id<MTLRenderPipelineState> ingot_util_pipeline(IngotDevice *device, IngotUtilKind kind, MTLPixelFormat color_format,
                                               MTLPixelFormat depth_format);

// ---------------------------------------------------------------------------------------------------------------
// Surface
// ---------------------------------------------------------------------------------------------------------------

@interface IngotSurface : NSObject {
@public
    IngotDevice *device;
    CAMetalLayer *layer;
    id<MTLRenderPipelineState> blit_pipeline;
}
@end

// ---------------------------------------------------------------------------------------------------------------
// Errors, labels and time
// ---------------------------------------------------------------------------------------------------------------

void ingot_set_error(NSString *message);
void ingot_set_error_cstr(const char *message);

// Debug labels and groups are only forwarded when the most recently created device asked for them
// (INGOT_DEVICE_FLAG_DEBUG_LABELS). Command buffers and encoders do not know their IngotDevice, so this is global.
void ingot_set_labels_enabled(BOOL enabled);
BOOL ingot_labels_enabled(void);

// NSString for a label, or nil when the pointer is null or labels are disabled.
NSString *ingot_label(const char *label, BOOL enabled);

// mach_absolute_time() <-> System.nanoTime() conversions (ticks * numer / denom, exactly like the JVM).
int64_t ingot_ticks_to_ns(uint64_t ticks);
uint64_t ingot_ns_to_ticks_ceil(int64_t ns);
