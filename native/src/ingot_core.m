#import "ingot.h"
#import "ingot_internal.h"

static _Thread_local char ingot_error_buffer[2048];

INGOT_EXPORT uint32_t ingot_abi_version(void) {
    return INGOT_ABI_VERSION;
}

INGOT_EXPORT const char *ingot_last_error(void) {
    return ingot_error_buffer;
}

void ingot_set_error(NSString *message) {
    const char *utf8 = message.UTF8String;
    if (utf8 == NULL) {
        utf8 = "<unrepresentable error>";
    }
    strlcpy(ingot_error_buffer, utf8, sizeof(ingot_error_buffer));
}

INGOT_EXPORT void ingot_release(void *handle) {
    if (handle != NULL) {
        CFRelease(handle);
    }
}
