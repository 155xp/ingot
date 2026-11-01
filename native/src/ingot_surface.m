// CAMetalLayer surface: configuration, drawable acquisition and the window queries the energy policy uses.
#import "ingot.h"
#import "ingot_internal.h"

#import <AppKit/AppKit.h>

@implementation IngotSurface
@end

INGOT_EXPORT void *ingot_view_metal_layer(void *ns_view) {
    @autoreleasepool {
        id view_object = INGOT_OBJ(id, ns_view);
        if (![view_object isKindOfClass:[NSView class]]) {
            ingot_set_error([NSString stringWithFormat:@"window view is %@, not an NSView", [view_object class]]);
            return NULL;
        }
        NSView *view = view_object;
        if ([view.layer isKindOfClass:[CAMetalLayer class]]) {
            return (__bridge void *)view.layer;
        }
        // A layer-hosting view: the layer is set before wantsLayer, so AppKit uses it instead of making its own.
        CAMetalLayer *layer = [CAMetalLayer layer];
        layer.contentsScale = view.window != nil ? view.window.backingScaleFactor : 1.0;
        [view setLayer:layer];
        [view setWantsLayer:YES];
        // surface_window finds the window through the layer's delegate.
        if (layer.delegate == nil) {
            layer.delegate = (id<CALayerDelegate>)view;
        }
        return (__bridge void *)layer;
    }
}

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
        // A drawable smaller than the layer (Ingot's half-resolution mode) is stretched over it. Exactly 2x nearest
        // keeps the pixel-art GUI and text as sharp as native rendering at the matching GUI scale.
        layer.magnificationFilter = kCAFilterNearest;

        IngotSurface *surface = [IngotSurface new];
        surface->device = dev;
        surface->layer = layer;
        surface->blit_pipeline = blit;
        surface->acquire_queue = dispatch_queue_create("dev.ingot.drawable",
            dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_USER_INTERACTIVE, 0));
        surface->ready_lock = OS_UNFAIR_LOCK_INIT;
        return (void *)CFBridgingRetain(surface);
    }
}

INGOT_EXPORT void ingot_surface_destroy(void *surface) {
    if (surface == NULL) {
        return;
    }
    @autoreleasepool {
        IngotSurface *surf = INGOT_OBJ(IngotSurface *, surface);
        // A background acquire still holds the layer; wait for it (nextDrawable times out after ~1 s at most).
        dispatch_sync(surf->acquire_queue, ^{});
        os_unfair_lock_lock(&surf->ready_lock);
        surf->ready = nil;
        os_unfair_lock_unlock(&surf->ready_lock);
        CFBridgingRelease(surface);
    }
}

// ready_lock held, no acquire running: acquires the next drawable on the surface's queue.
static void surface_prefetch(IngotSurface *surface) {
    surface->acquiring = true;
    uint64_t generation = surface->generation;
    dispatch_async(surface->acquire_queue, ^{
        @autoreleasepool {
            id<CAMetalDrawable> drawable = [surface->layer nextDrawable];
            os_unfair_lock_lock(&surface->ready_lock);
            if (drawable != nil && surface->generation == generation && surface->ready == nil) {
                surface->ready = drawable;
            }
            surface->acquiring = false;
            os_unfair_lock_unlock(&surface->ready_lock);
        }
    });
}

// The prefetched drawable (nil if none), taking it out of the slot. Starts the next acquire when prefetch is set.
static id<CAMetalDrawable> surface_take_ready(IngotSurface *surface, bool prefetch) {
    os_unfair_lock_lock(&surface->ready_lock);
    id<CAMetalDrawable> drawable = surface->ready;
    surface->ready = nil;
    if (prefetch && !surface->acquiring) {
        surface_prefetch(surface);
    }
    os_unfair_lock_unlock(&surface->ready_lock);
    return drawable;
}

INGOT_EXPORT void *ingot_surface_try_drawable(void *surface) {
    @autoreleasepool {
        id<CAMetalDrawable> drawable = surface_take_ready(INGOT_OBJ(IngotSurface *, surface), true);
        return drawable != nil ? (void *)CFBridgingRetain(drawable) : NULL;
    }
}

INGOT_EXPORT void ingot_surface_configure(void *surface, uint32_t width, uint32_t height, uint32_t display_sync, uint32_t max_drawables) {
    @autoreleasepool {
        IngotSurface *surf = INGOT_OBJ(IngotSurface *, surface);
        CAMetalLayer *layer = surf->layer;
        // A prefetched drawable has the old size; drop it, and any acquire already running.
        os_unfair_lock_lock(&surf->ready_lock);
        surf->generation++;
        surf->ready = nil;
        os_unfair_lock_unlock(&surf->ready_lock);
        layer.drawableSize = CGSizeMake(width, height);
        layer.displaySyncEnabled = display_sync != 0;
        // CAMetalLayer accepts only 2 or 3.
        layer.maximumDrawableCount = max_drawables <= 2 ? 2 : 3;
    }
}

INGOT_EXPORT void *ingot_surface_next_drawable(void *surface) {
    @autoreleasepool {
        IngotSurface *surf = INGOT_OBJ(IngotSurface *, surface);
        id<CAMetalDrawable> drawable = surface_take_ready(surf, false);
        if (drawable == nil) {
            drawable = [surf->layer nextDrawable];
        }
        if (drawable == nil) {
            ingot_set_error_cstr("nextDrawable returned nil (no drawable became available within the timeout)");
            return NULL;
        }
        return (void *)CFBridgingRetain(drawable);
    }
}

// The NSWindow showing the layer: GLFW's content view hosts it and is the delegate of its layer.
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
