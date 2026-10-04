package dev.ingot.backend;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * shader-xlate hook into the mod: the IngotPipelineDesc bytes MetalPipelineCompiler writes for a pipeline, with the
 * function and label pointers left null. metalcheck --desc reads them through native/include/ingot.h, compares every
 * field with the manifest and builds a PSO from them the way libingot does.
 */
public final class DescProbe {
    public record Variant(int depthFormat, int colorMask, byte[] bytes) {
    }

    private DescProbe() {
    }

    /** The default variant, then the same descriptor rewritten for the other depth attachment (as MetalRenderPipeline does). */
    public static List<Variant> variants(BackendRenderPipeline.CreateInfo createInfo) throws Exception {
        int depth = MetalPipelineCompiler.defaultDepthFormat(createInfo);
        int otherDepth = depth == MetalFormats.PIXEL_INVALID ? MetalFormats.PIXEL_DEPTH32_FLOAT : MetalFormats.PIXEL_INVALID;
        int colorMask = MetalPipelineCompiler.targetMask(createInfo.colorTargetStates());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment desc = arena.allocate(MetalPipelineCompiler.DESC_SIZE, 8);
            MetalPipelineCompiler.writeDescriptor(desc, createInfo);
            MetalPipelineCompiler.writeAttachments(desc, createInfo.colorTargetStates(), depth, MetalFormats.PIXEL_INVALID, colorMask);
            Variant first = new Variant(depth, colorMask, desc.toArray(JAVA_BYTE));
            MetalPipelineCompiler.writeAttachments(desc, createInfo.colorTargetStates(), otherDepth, MetalFormats.PIXEL_INVALID, colorMask);
            return List.of(first, new Variant(otherDepth, colorMask, desc.toArray(JAVA_BYTE)));
        }
    }
}
