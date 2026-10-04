// GPU frame capture into .gputrace documents (Xcode can open them).
#import "ingot.h"
#import "ingot_internal.h"

INGOT_EXPORT int32_t ingot_capture_begin(void *device, const char *path) {
    @autoreleasepool {
        MTLCaptureManager *manager = [MTLCaptureManager sharedCaptureManager];
        if (![manager supportsDestination:MTLCaptureDestinationGPUTraceDocument]) {
            ingot_set_error_cstr("GPU trace capture is not available (launch with MTL_CAPTURE_ENABLED=1)");
            return 0;
        }
        if (path == NULL) {
            ingot_set_error_cstr("capture path is null");
            return 0;
        }
        MTLCaptureDescriptor *desc = [MTLCaptureDescriptor new];
        desc.captureObject = INGOT_OBJ(IngotDevice *, device)->mtl;
        desc.destination = MTLCaptureDestinationGPUTraceDocument;
        desc.outputURL = [NSURL fileURLWithPath:[[NSString alloc] initWithUTF8String:path]];
        NSError *error = nil;
        if (![manager startCaptureWithDescriptor:desc error:&error]) {
            ingot_set_error([NSString stringWithFormat:@"startCapture failed: %@", error.localizedDescription]);
            return 0;
        }
        return 1;
    }
}

INGOT_EXPORT void ingot_capture_end(void) {
    @autoreleasepool {
        MTLCaptureManager *manager = [MTLCaptureManager sharedCaptureManager];
        if (manager.isCapturing) {
            [manager stopCapture];
        }
    }
}
