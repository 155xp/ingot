// Library-level entry points: ABI version, errors, handle release, debug-label policy and the host clock.
#import "ingot.h"
#import "ingot_internal.h"

#include <mach/mach_time.h>

// Large enough for a Metal compiler log of a few diagnostics; longer messages are truncated.
static _Thread_local char ingot_error_buffer[8192];

static _Atomic bool ingot_labels;

static mach_timebase_info_data_t ingot_timebase;

__attribute__((constructor)) static void ingot_init_timebase(void) {
    mach_timebase_info(&ingot_timebase);
}

INGOT_EXPORT uint32_t ingot_abi_version(void) {
    return INGOT_ABI_VERSION;
}

INGOT_EXPORT const char *ingot_last_error(void) {
    return ingot_error_buffer;
}

void ingot_set_error_cstr(const char *message) {
    strlcpy(ingot_error_buffer, message != NULL ? message : "", sizeof(ingot_error_buffer));
}

void ingot_set_error(NSString *message) {
    const char *utf8 = message.UTF8String;
    ingot_set_error_cstr(utf8 != NULL ? utf8 : "<unrepresentable error>");
}

INGOT_EXPORT void ingot_release(void *handle) {
    if (handle == NULL) {
        return;
    }
    // Metal objects autorelease internal objects while deallocating, and the Java threads have no pool of their own.
    @autoreleasepool {
        CFRelease(handle);
    }
}

void ingot_set_labels_enabled(BOOL enabled) {
    atomic_store_explicit(&ingot_labels, enabled, memory_order_relaxed);
}

BOOL ingot_labels_enabled(void) {
    return atomic_load_explicit(&ingot_labels, memory_order_relaxed);
}

NSString *ingot_label(const char *label, BOOL enabled) {
    if (!enabled || label == NULL) {
        return nil;
    }
    return [[NSString alloc] initWithUTF8String:label];
}

// The JVM computes System.nanoTime() on macOS as mach_absolute_time() * numer / denom in unsigned 64-bit arithmetic
// (verified against JDK 25: it matches mach_absolute_time, not mach_continuous_time). Doing the same here keeps
// both clocks bit-identical.
int64_t ingot_ticks_to_ns(uint64_t ticks) {
    return (int64_t)(ticks * ingot_timebase.numer / ingot_timebase.denom);
}

// Smallest tick count whose ingot_ticks_to_ns() is >= ns, so waiting until it never returns early.
uint64_t ingot_ns_to_ticks_ceil(int64_t ns) {
    if (ns <= 0) {
        return 0;
    }
    unsigned __int128 scaled = (unsigned __int128)ns * ingot_timebase.denom + ingot_timebase.numer - 1;
    return (uint64_t)(scaled / ingot_timebase.numer);
}
