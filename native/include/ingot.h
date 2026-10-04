// The C ABI between Ingot's Java backend and libingot.dylib.
//
// Every INGOT_EXPORT function here is bound by the generated class dev.ingot.nativ.Mtl
// (python3 tools/gen_bindings.py). Keep declarations on the simple form the generator parses:
//   INGOT_EXPORT <type> ingot_<name>(<type> <param>, ...);
// Allowed types: void, (u)int8/16/32/64_t, float, double, and pointers (passed as Java long).
// A declaration preceded by a line containing INGOT_HOT is linked with Linker.Option.critical(false):
// it must not block, must not allocate autoreleased objects, and must not call back into Java.
//
// Conventions (see docs/ARCHITECTURE.md):
// * Object handles are retained Objective-C pointers. Java releases them with ingot_release().
// * Enum-typed parameters carry raw Metal enum values (MTLPixelFormat, MTLVertexFormat, MTLPrimitiveType,
//   MTLCompareFunction, MTLBlendFactor, MTLBlendOperation, MTLColorWriteMask, MTLLoadAction, MTLStoreAction,
//   MTLSamplerAddressMode, MTLSamplerMinMagFilter, MTLSamplerMipFilter, MTLCullMode, MTLWinding,
//   MTLTriangleFillMode, MTLIndexType, MTLTextureType, MTLTextureUsage, MTLStorageMode, MTLResourceOptions,
//   MTLVertexStepFunction). dev.ingot.backend.MetalFormats owns the GpuFormat -> Metal tables.
// * Creation functions return 0 on failure and set a message readable with ingot_last_error().
#pragma once

#include <stdint.h>

#define INGOT_ABI_VERSION 1u
#define INGOT_EXPORT __attribute__((visibility("default")))
#define INGOT_HOT

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------------------------

INGOT_EXPORT uint32_t ingot_abi_version(void);

// Thread-local message describing the most recent failure on this thread ("" if none). Never null.
INGOT_EXPORT const char *ingot_last_error(void);

// CFRelease on a handle returned by any ingot_* creation function. Null is ignored.
INGOT_EXPORT void ingot_release(void *handle);

// ---------------------------------------------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------------------------------------------

#define INGOT_DEVICE_FLAG_DEBUG_LABELS 1u   // keep labels/debug groups (otherwise they are dropped natively)
#define INGOT_DEVICE_FLAG_RETAIN_REFS  2u   // use retained-reference command buffers (debug aid; default is unretained)

// Creates the system default MTLDevice, one MTLCommandQueue and completion tracking. Returns IngotDevice*.
INGOT_EXPORT void *ingot_device_create(uint32_t flags);

// Waits for all committed command buffers, then frees the device. The handle must not be used afterwards.
INGOT_EXPORT void ingot_device_destroy(void *device);

// UTF-8 device name, owned by the device.
INGOT_EXPORT const char *ingot_device_name(void *device);

#define INGOT_QUERY_UNIFIED_MEMORY          1u   // 1 if hasUnifiedMemory
#define INGOT_QUERY_MAX_BUFFER_LENGTH       2u   // maxBufferLength in bytes
#define INGOT_QUERY_WORKING_SET_SIZE        3u   // recommendedMaxWorkingSetSize in bytes
#define INGOT_QUERY_APPLE_FAMILY            4u   // highest N such that supportsFamily(MTLGPUFamilyAppleN), else 0
#define INGOT_QUERY_MAC2_FAMILY             5u   // 1 if supportsFamily(MTLGPUFamilyMac2)
#define INGOT_QUERY_MAX_TEXTURE_SIZE        6u   // max 2D texture dimension (16384 on all Mac2/Apple7+ GPUs)
#define INGOT_QUERY_REGISTRY_ID             7u
#define INGOT_QUERY_LOW_POWER               8u
#define INGOT_QUERY_REMOVABLE               9u
#define INGOT_QUERY_MAX_ANISOTROPY          10u  // 16
#define INGOT_QUERY_BUFFER_OFFSET_ALIGNMENT 11u  // required alignment for constant buffer offsets (256 on macOS)
#define INGOT_QUERY_CURRENT_ALLOCATED_SIZE  12u  // currentAllocatedSize in bytes
#define INGOT_QUERY_ARCH_ARM64              13u  // 1 if this process runs natively on arm64
INGOT_EXPORT uint64_t ingot_device_query(void *device, uint32_t key);

// Required byte alignment for the offset of a texture buffer view of the given MTLPixelFormat.
INGOT_EXPORT uint64_t ingot_device_texture_buffer_alignment(void *device, uint32_t pixel_format);

// Highest serial whose command buffer has completed on the GPU (0 before any completes).
INGOT_HOT
INGOT_EXPORT uint64_t ingot_device_completed_serial(void *device);

// Blocks until completed_serial >= serial or the timeout elapses (timeout_ns < 0 waits forever).
// Returns 1 when the serial completed, 0 on timeout.
INGOT_EXPORT int32_t ingot_device_wait_serial(void *device, uint64_t serial, int64_t timeout_ns);

// Non-zero once any command buffer finished with MTLCommandBufferStatusError. The value is the
// MTLCommandBufferError code of the first failure; ingot_device_error_message() describes it.
INGOT_EXPORT uint32_t ingot_device_error_code(void *device);
INGOT_EXPORT const char *ingot_device_error_message(void *device);

// GPU execution window of a completed serial, in host-time nanoseconds (same clock as System.nanoTime()).
// Writes {gpuStartNs, gpuEndNs} to out (2 x int64) and returns 1, or returns 0 if the serial is unknown or
// too old (the device keeps the last 256 serials).
INGOT_EXPORT int32_t ingot_device_serial_times(void *device, uint64_t serial, void *out);

// ---------------------------------------------------------------------------------------------------------------
// Resources
// ---------------------------------------------------------------------------------------------------------------

// MTLBuffer with the given MTLResourceOptions. data may be null (contents are then zeroed by Metal).
INGOT_EXPORT void *ingot_buffer_create(void *device, uint64_t length, uint32_t resource_options, const void *data, const char *label);

// CPU address of a shared/managed buffer's storage (null for private buffers).
INGOT_HOT
INGOT_EXPORT void *ingot_buffer_contents(void *buffer);

// Managed storage only: tells Metal which bytes the CPU modified.
INGOT_EXPORT void ingot_buffer_did_modify_range(void *buffer, uint64_t offset, uint64_t length);

// texture_type: MTLTextureType2D or MTLTextureTypeCube. array_length is 1 (cube faces are implied by the type).
INGOT_EXPORT void *ingot_texture_create(void *device, uint32_t texture_type, uint32_t pixel_format, uint32_t width, uint32_t height,
                                        uint32_t mip_levels, uint32_t usage, uint32_t storage_mode, const char *label);

// newTextureViewWithPixelFormat:textureType:levels:slices:
INGOT_EXPORT void *ingot_texture_view_create(void *texture, uint32_t pixel_format, uint32_t texture_type, uint32_t base_level,
                                             uint32_t level_count, uint32_t base_slice, uint32_t slice_count);

// MTLTextureTypeTextureBuffer view over [offset, offset + element_count * element_size) of a buffer.
// offset must satisfy ingot_device_texture_buffer_alignment().
INGOT_EXPORT void *ingot_texture_buffer_create(void *buffer, uint32_t pixel_format, uint64_t offset, uint64_t element_count,
                                               uint64_t bytes_per_row);

INGOT_EXPORT void *ingot_sampler_create(void *device, uint32_t address_u, uint32_t address_v, uint32_t min_filter, uint32_t mag_filter,
                                        uint32_t mip_filter, uint32_t max_anisotropy, float lod_min, float lod_max, const char *label);

// ---------------------------------------------------------------------------------------------------------------
// Shaders and pipelines
// ---------------------------------------------------------------------------------------------------------------

// Compiles MSL source (newLibraryWithSource; MTLCompileOptions: mathMode safe (no fast math), languageVersion 2.4,
// preserveInvariance = YES). Thread-safe; wraps its own @autoreleasepool.
INGOT_EXPORT void *ingot_library_create(void *device, const char *msl_source, const char *label);

// newFunctionWithName:. Returns 0 (with error) if the function does not exist.
INGOT_EXPORT void *ingot_library_function(void *library, const char *name);

typedef struct IngotVertexAttribute {
    uint32_t format;        // MTLVertexFormat; 0 (Invalid) = attribute unused
    uint32_t offset;
    uint32_t buffer_index;  // argument-table index (Ingot puts vertex buffer slot s at 30 - s)
    uint32_t _pad;
} IngotVertexAttribute;     // 16 bytes

typedef struct IngotVertexLayout {
    uint32_t stride;        // 0 = layout unused
    uint32_t step_function; // MTLVertexStepFunction
    uint32_t step_rate;
    uint32_t _pad;
} IngotVertexLayout;        // 16 bytes

typedef struct IngotColorTarget {
    uint32_t pixel_format;  // 0 (Invalid) = no attachment at this index
    uint32_t blend_enabled;
    uint32_t rgb_op;        // MTLBlendOperation
    uint32_t alpha_op;
    uint32_t src_rgb;       // MTLBlendFactor
    uint32_t dst_rgb;
    uint32_t src_alpha;
    uint32_t dst_alpha;
    uint32_t write_mask;    // MTLColorWriteMask
    uint32_t _pad;
} IngotColorTarget;         // 40 bytes

#define INGOT_MAX_VERTEX_ATTRIBUTES 31
#define INGOT_MAX_COLOR_TARGETS     8

typedef struct IngotPipelineDesc {
    void *vertex_function;                                       //    0
    void *fragment_function;                                     //    8  (may be null)
    IngotVertexAttribute attributes[INGOT_MAX_VERTEX_ATTRIBUTES]; //   16  indexed by shader location
    IngotVertexLayout layouts[INGOT_MAX_VERTEX_ATTRIBUTES];      //  512  indexed by argument-table index
    IngotColorTarget colors[INGOT_MAX_COLOR_TARGETS];            // 1008
    uint32_t depth_pixel_format;                                 // 1328  0 = none
    uint32_t stencil_pixel_format;                               // 1332  0 = none
    uint32_t sample_count;                                       // 1336
    uint32_t input_primitive_topology;                           // 1340  MTLPrimitiveTopologyClass
    const char *label;                                           // 1344
} IngotPipelineDesc;                                             // 1352 bytes

// newRenderPipelineStateWithDescriptor:error:. Thread-safe. Returns id<MTLRenderPipelineState>.
INGOT_EXPORT void *ingot_pipeline_create(void *device, const void *desc);

// id<MTLDepthStencilState>. compare_function is an MTLCompareFunction.
INGOT_EXPORT void *ingot_depth_stencil_create(void *device, uint32_t compare_function, uint32_t depth_write);

// ---------------------------------------------------------------------------------------------------------------
// Command buffers
// ---------------------------------------------------------------------------------------------------------------

// New command buffer on the device queue (unretained references unless INGOT_DEVICE_FLAG_RETAIN_REFS).
INGOT_EXPORT void *ingot_cmd_create(void *device, const char *label);

// Commits cmd and CONSUMES the handle. When the GPU finishes, completed_serial advances to `serial`
// (serials must be committed in increasing order), GPU times are recorded and waiters are woken.
INGOT_EXPORT void ingot_cmd_commit(void *device, void *cmd, uint64_t serial);

// presentDrawable: on cmd (call before commit). Does not consume the drawable handle.
INGOT_EXPORT void ingot_cmd_present(void *cmd, void *drawable);

INGOT_EXPORT void ingot_cmd_push_debug_group(void *cmd, const char *label);
INGOT_EXPORT void ingot_cmd_pop_debug_group(void *cmd);

// ---------------------------------------------------------------------------------------------------------------
// Blit encoder
// ---------------------------------------------------------------------------------------------------------------

INGOT_EXPORT void *ingot_blit_begin(void *cmd);

// endEncoding and release.
INGOT_EXPORT void ingot_blit_end(void *blit);

INGOT_EXPORT void ingot_blit_copy_buffer(void *blit, void *src, uint64_t src_offset, void *dst, uint64_t dst_offset, uint64_t size);

// options: MTLBlitOption (e.g. DepthFromDepthStencil for combined depth/stencil formats).
INGOT_EXPORT void ingot_blit_copy_buffer_to_texture(void *blit, void *src, uint64_t src_offset, uint64_t bytes_per_row,
                                                    uint64_t bytes_per_image, void *dst, uint32_t slice, uint32_t level,
                                                    uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint32_t options);

INGOT_EXPORT void ingot_blit_copy_texture_to_buffer(void *blit, void *src, uint32_t slice, uint32_t level, uint32_t x, uint32_t y,
                                                    uint32_t width, uint32_t height, void *dst, uint64_t dst_offset,
                                                    uint64_t bytes_per_row, uint64_t bytes_per_image, uint32_t options);

INGOT_EXPORT void ingot_blit_copy_texture(void *blit, void *src, uint32_t src_slice, uint32_t src_level, uint32_t src_x, uint32_t src_y,
                                          void *dst, uint32_t dst_slice, uint32_t dst_level, uint32_t dst_x, uint32_t dst_y,
                                          uint32_t width, uint32_t height);

INGOT_EXPORT void ingot_blit_fill_buffer(void *blit, void *buffer, uint64_t offset, uint64_t length, uint32_t value);

// Managed storage only: makes GPU writes visible to the CPU after the command buffer completes.
INGOT_EXPORT void ingot_blit_synchronize(void *blit, void *resource);

// ---------------------------------------------------------------------------------------------------------------
// Render encoder
// ---------------------------------------------------------------------------------------------------------------

typedef struct IngotColorAttachment {
    void *texture;          //  0  0 = unused slot
    uint32_t level;         //  8
    uint32_t slice;         // 12
    uint32_t load_action;   // 16  MTLLoadAction
    uint32_t store_action;  // 20  MTLStoreAction
    float clear[4];         // 24
} IngotColorAttachment;     // 40 bytes

typedef struct IngotRenderPassDesc {
    IngotColorAttachment colors[INGOT_MAX_COLOR_TARGETS]; //   0
    void *depth_texture;                                  // 320  0 = none
    uint32_t depth_level;                                 // 328
    uint32_t depth_load_action;                           // 332
    uint32_t depth_store_action;                          // 336
    float clear_depth;                                    // 340
    void *stencil_texture;                                // 344  0 = none (same texture as depth for combined formats)
    uint32_t stencil_load_action;                         // 352
    uint32_t stencil_store_action;                        // 356
    uint32_t clear_stencil;                               // 360
    uint32_t _pad;                                        // 364
    const char *label;                                    // 368  may be null
} IngotRenderPassDesc;                                    // 376 bytes

// renderCommandEncoderWithDescriptor:. Returns a retained encoder (released by ingot_render_end).
INGOT_EXPORT void *ingot_render_begin(void *cmd, const void *desc);

// endEncoding and release.
INGOT_EXPORT void ingot_render_end(void *encoder);

INGOT_HOT
INGOT_EXPORT void ingot_render_set_pipeline(void *encoder, void *pipeline_state);
INGOT_HOT
INGOT_EXPORT void ingot_render_set_depth_stencil(void *encoder, void *depth_stencil_state);
// cull_mode: MTLCullMode, winding: MTLWinding, fill_mode: MTLTriangleFillMode.
INGOT_HOT
INGOT_EXPORT void ingot_render_set_raster(void *encoder, uint32_t cull_mode, uint32_t winding, uint32_t fill_mode);
INGOT_HOT
INGOT_EXPORT void ingot_render_set_depth_bias(void *encoder, float constant, float slope, float clamp);
INGOT_HOT
INGOT_EXPORT void ingot_render_set_viewport(void *encoder, double x, double y, double width, double height, double znear, double zfar);
INGOT_HOT
INGOT_EXPORT void ingot_render_set_scissor(void *encoder, uint32_t x, uint32_t y, uint32_t width, uint32_t height);

#define INGOT_STAGE_VERTEX   1u
#define INGOT_STAGE_FRAGMENT 2u

// Binds a buffer at `index` for every stage set in `stages`.
INGOT_HOT
INGOT_EXPORT void ingot_render_set_buffer(void *encoder, uint32_t stages, void *buffer, uint64_t offset, uint32_t index);
// Changes only the offset of the buffer already bound at `index` (cheaper than a full bind).
INGOT_HOT
INGOT_EXPORT void ingot_render_set_buffer_offset(void *encoder, uint32_t stages, uint64_t offset, uint32_t index);
// Copies `length` (<= 4096) bytes from `bytes` into the argument table (setVertexBytes/setFragmentBytes).
INGOT_HOT
INGOT_EXPORT void ingot_render_set_bytes(void *encoder, uint32_t stages, const void *bytes, uint64_t length, uint32_t index);
INGOT_HOT
INGOT_EXPORT void ingot_render_set_texture(void *encoder, uint32_t stages, void *texture, uint32_t index);
INGOT_HOT
INGOT_EXPORT void ingot_render_set_sampler(void *encoder, uint32_t stages, void *sampler, uint32_t index);

INGOT_HOT
INGOT_EXPORT void ingot_render_draw(void *encoder, uint32_t primitive, uint32_t vertex_start, uint32_t vertex_count,
                                    uint32_t instance_count, uint32_t base_instance);

// index_offset is in bytes. index_type: MTLIndexType.
INGOT_HOT
INGOT_EXPORT void ingot_render_draw_indexed(void *encoder, uint32_t primitive, uint32_t index_count, uint32_t index_type,
                                            void *index_buffer, uint64_t index_offset, uint32_t instance_count,
                                            int32_t base_vertex, uint32_t base_instance);

// `params` points to draw_count records of {int32 firstVertex, int32 vertexCount} (VkMultiDrawInfoEXT layout).
INGOT_HOT
INGOT_EXPORT void ingot_render_multi_draw(void *encoder, uint32_t primitive, const void *params, uint32_t draw_count,
                                          uint32_t instance_count, uint32_t base_instance);

// `params` points to draw_count records of {int32 firstIndex, int32 indexCount, int32 vertexOffset}
// (VkMultiDrawIndexedInfoEXT layout). index_offset (bytes) is added to every firstIndex * indexSize.
INGOT_HOT
INGOT_EXPORT void ingot_render_multi_draw_indexed(void *encoder, uint32_t primitive, uint32_t index_type, void *index_buffer,
                                                  uint64_t index_offset, const void *params, uint32_t draw_count,
                                                  uint32_t instance_count, uint32_t base_instance);

// Separate-array form: first_index_offsets (int64 byte offsets), index_counts (int32), vertex_offsets (int32).
INGOT_HOT
INGOT_EXPORT void ingot_render_multi_draw_indexed_separate(void *encoder, uint32_t primitive, uint32_t index_type, void *index_buffer,
                                                           const void *first_index_offsets, const void *index_counts,
                                                           const void *vertex_offsets, uint32_t draw_count);

// Separate-array form: first_vertices (int32), vertex_counts (int32).
INGOT_HOT
INGOT_EXPORT void ingot_render_multi_draw_separate(void *encoder, uint32_t primitive, const void *first_vertices,
                                                   const void *vertex_counts, uint32_t draw_count);

// draw_count indirect draws reading MTLDrawPrimitivesIndirectArguments records `stride` bytes apart.
INGOT_HOT
INGOT_EXPORT void ingot_render_draw_indirect(void *encoder, uint32_t primitive, void *indirect_buffer, uint64_t indirect_offset,
                                             uint32_t draw_count, uint32_t stride);

// draw_count indexed indirect draws reading MTLDrawIndexedPrimitivesIndirectArguments records `stride` bytes apart.
INGOT_HOT
INGOT_EXPORT void ingot_render_draw_indexed_indirect(void *encoder, uint32_t primitive, uint32_t index_type, void *index_buffer,
                                                     uint64_t index_offset, void *indirect_buffer, uint64_t indirect_offset,
                                                     uint32_t draw_count, uint32_t stride);

INGOT_EXPORT void ingot_render_push_debug_group(void *encoder, const char *label);
INGOT_EXPORT void ingot_render_pop_debug_group(void *encoder);

// ---------------------------------------------------------------------------------------------------------------
// Fused utilities (each opens and closes its own encoder on `cmd`)
// ---------------------------------------------------------------------------------------------------------------

// Clears a rectangle of a color and/or depth attachment (either may be null) by drawing a scissored quad.
// flags: bit 0 = clear color, bit 1 = clear depth.
INGOT_EXPORT void ingot_util_clear_region(void *device, void *cmd, void *color_texture, void *depth_texture, uint32_t level,
                                          uint32_t x, uint32_t y, uint32_t width, uint32_t height,
                                          float r, float g, float b, float a, float depth, uint32_t flags);

// ---------------------------------------------------------------------------------------------------------------
// Surface (CAMetalLayer)
// ---------------------------------------------------------------------------------------------------------------

// Takes a CAMetalLayer* (from SDL_Metal_GetLayer; Java sets SDL_VIDEO_METAL_AUTO_RESIZE_DRAWABLE=0 before creating the
// view) and configures it for the device: BGRA8Unorm, framebufferOnly, opaque, presentsWithTransaction = NO.
INGOT_EXPORT void *ingot_surface_create(void *device, void *metal_layer);
INGOT_EXPORT void ingot_surface_destroy(void *surface);

// drawableSize = width x height pixels; display_sync toggles displaySyncEnabled (vsync); max_drawables is 2 or 3.
// Main thread only.
INGOT_EXPORT void ingot_surface_configure(void *surface, uint32_t width, uint32_t height, uint32_t display_sync, uint32_t max_drawables);

// [layer nextDrawable] as a retained handle, or 0 if none became available within ~1 s (allowsNextDrawableTimeout).
INGOT_EXPORT void *ingot_surface_next_drawable(void *surface);

// Encodes a render pass on cmd that draws src_texture (any float-readable color format) into the drawable 1:1,
// anchored top-left, flipping vertically when flip_y != 0 (Ingot renders in OpenGL row order), then schedules
// presentDrawable: on cmd. Pixels outside the source are cleared to black.
INGOT_EXPORT void ingot_surface_blit(void *surface, void *cmd, void *drawable, void *src_texture, uint32_t src_width,
                                     uint32_t src_height, uint32_t flip_y);

// ---------------------------------------------------------------------------------------------------------------
// Energy: occlusion, power state, low-power waiting
// ---------------------------------------------------------------------------------------------------------------

// 1 if the window that hosts the surface's layer is at least partly visible (NSWindowOcclusionStateVisible),
// 0 if it is fully covered, on another Space, or the display is asleep. Main thread only.
INGOT_EXPORT int32_t ingot_surface_visible(void *surface);

// maximumFramesPerSecond of the screen currently showing the surface's window (60 if unknown). Main thread only.
INGOT_EXPORT int32_t ingot_surface_max_refresh_rate(void *surface);

#define INGOT_POWER_LOW_POWER_MODE 1u      // NSProcessInfo.lowPowerModeEnabled
#define INGOT_POWER_ON_BATTERY     2u      // IOPSGetProvidingPowerSourceType == battery
// bits 8..15: NSProcessInfo.thermalState (0 nominal, 1 fair, 2 serious, 3 critical)
INGOT_EXPORT uint32_t ingot_power_state(void);

// Sleeps the calling thread until System.nanoTime() >= deadline_ns using mach_wait_until (no spinning).
// Returns immediately if the deadline has passed.
INGOT_EXPORT void ingot_wait_until(int64_t deadline_ns);

// Current host time in the System.nanoTime() clock domain (mach_continuous/absolute time in ns).
INGOT_HOT
INGOT_EXPORT int64_t ingot_now_ns(void);

// ---------------------------------------------------------------------------------------------------------------
// Debugging
// ---------------------------------------------------------------------------------------------------------------

// Starts an MTLCaptureManager capture of the device into a .gputrace file (requires MTL_CAPTURE_ENABLED=1).
// Returns 1 on success.
INGOT_EXPORT int32_t ingot_capture_begin(void *device, const char *path);
INGOT_EXPORT void ingot_capture_end(void);

#ifdef __cplusplus
}
#endif
