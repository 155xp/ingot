// CAMetalLayer surface: configuration, drawable acquisition and the window queries the energy policy uses.
#import "ingot.h"
#import "ingot_internal.h"

#import <AppKit/AppKit.h>

@implementation IngotSurface
@end

INGOT_EXPORT void *ingot_surface_create(void *device, void *metal_layer) {
    @autoreleasepool {
        IngotDevice *dev = INGOT_OBJ(IngotDevice *, device);
        id layer_object = INGOT_OBJ(id, metal_layer);
        if (![layer_object isKindOfClass:[CAMetalLayer class]]) {
            ingot_set_error([NSString stringWithFormat:@"surface layer is %@, not a CAMetalLayer", [layer_object class]]);
            return NULL;
        }
        CAMetalLayer *layer = layer_object;
        // The present blit is the only pass that renders into drawables, so the layer format is fixed.
        id<MTLRenderPipelineState> blit = ingot_util_pipeline(dev, IngotUtilKindBlit, MTLPixelFormatBGRA8Unorm, MTLPixelFormatInvalid);
        if (blit == nil) {
            return NULL;
        }

        layer.device = dev->mtl;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.framebufferOnly = YES;
        layer.opaque = YES;
        layer.presentsWithTransaction = NO;
        layer.allowsNextDrawableTimeout = YES;

        IngotSurface *surface = [IngotSurface new];
        surface->device = dev;
        surface->layer = layer;
        surface->blit_pipeline = blit;
        return (void *)CFBridgingRetain(surface);
    }
}

INGOT_EXPORT void ingot_surface_destroy(void *surface) {
    if (surface == NULL) {
        return;
    }
    @autoreleasepool {
        CFBridgingRelease(surface);
    }
}

INGOT_EXPORT void ingot_surface_configure(void *surface, uint32_t width, uint32_t height, uint32_t display_sync, uint32_t max_drawables) {
    @autoreleasepool {
        CAMetalLayer *layer = INGOT_OBJ(IngotSurface *, surface)->layer;
        layer.drawableSize = CGSizeMake(width, height);
        layer.displaySyncEnabled = display_sync != 0;
        // CAMetalLayer accepts only 2 or 3.
        layer.maximumDrawableCount = max_drawables <= 2 ? 2 : 3;
    }
}

INGOT_EXPORT void *ingot_surface_next_drawable(void *surface) {
    @autoreleasepool {
        id<CAMetalDrawable> drawable = [INGOT_OBJ(IngotSurface *, surface)->layer nextDrawable];
        if (drawable == nil) {
            ingot_set_error_cstr("nextDrawable returned nil (no drawable became available within the timeout)");
            return NULL;
        }
        return (void *)CFBridgingRetain(drawable);
    }
}

// The NSWindow showing the layer: SDL's Metal view hosts it, and AppKit makes a view the delegate of its layer.
static NSWindow *surface_window(IngotSurface *surface) {
    for (CALayer *layer = surface->layer; layer != nil; layer = layer.superlayer) {
        id delegate = layer.delegate;
        if ([delegate isKindOfClass:[NSView class]]) {
            return ((NSView *)delegate).window;
        }
    }
    return nil;
}

INGOT_EXPORT int32_t ingot_surface_visible(void *surface) {
    @autoreleasepool {
        NSWindow *window = surface_window(INGOT_OBJ(IngotSurface *, surface));
        // Without a window nothing is known to be hidden; report visible so nothing is throttled by mistake.
        return window == nil || (window.occlusionState & NSWindowOcclusionStateVisible) != 0;
    }
}

INGOT_EXPORT int32_t ingot_surface_max_refresh_rate(void *surface) {
    @autoreleasepool {
        NSScreen *screen = surface_window(INGOT_OBJ(IngotSurface *, surface)).screen;
        NSInteger rate = screen != nil ? screen.maximumFramesPerSecond : 0;
        return rate > 0 ? (int32_t)rate : 60;
    }
}
