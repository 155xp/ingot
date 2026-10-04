package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;

/**
 * Renderpearl enums to raw Metal enum values (values copied from the macOS SDK headers; they are ABI-stable).
 * libingot receives these numbers unchanged, so this class is the only place that knows the mapping.
 */
public final class MetalFormats {
	private MetalFormats() {
	}

	// MTLPixelFormat
	public static final int PIXEL_INVALID = 0;
	public static final int PIXEL_BGRA8_UNORM = 80;
	public static final int PIXEL_DEPTH32_FLOAT = 252;
	public static final int PIXEL_STENCIL8 = 253;
	public static final int PIXEL_DEPTH32_FLOAT_STENCIL8 = 260;

	// MTLPrimitiveType
	public static final int PRIMITIVE_POINT = 0;
	public static final int PRIMITIVE_LINE = 1;
	public static final int PRIMITIVE_LINE_STRIP = 2;
	public static final int PRIMITIVE_TRIANGLE = 3;
	public static final int PRIMITIVE_TRIANGLE_STRIP = 4;

	// MTLPrimitiveTopologyClass
	public static final int TOPOLOGY_CLASS_POINT = 1;
	public static final int TOPOLOGY_CLASS_LINE = 2;
	public static final int TOPOLOGY_CLASS_TRIANGLE = 3;

	// MTLIndexType
	public static final int INDEX_UINT16 = 0;
	public static final int INDEX_UINT32 = 1;

	// MTLCullMode / MTLWinding / MTLTriangleFillMode
	public static final int CULL_NONE = 0;
	public static final int CULL_BACK = 2;
	public static final int WINDING_CLOCKWISE = 0;
	public static final int WINDING_COUNTER_CLOCKWISE = 1;
	public static final int FILL_FILL = 0;
	public static final int FILL_LINES = 1;

	// MTLLoadAction / MTLStoreAction
	public static final int LOAD_DONT_CARE = 0;
	public static final int LOAD_LOAD = 1;
	public static final int LOAD_CLEAR = 2;
	public static final int STORE_DONT_CARE = 0;
	public static final int STORE_STORE = 1;

	// MTLTextureType
	public static final int TEXTURE_2D = 2;
	public static final int TEXTURE_CUBE = 5;
	public static final int TEXTURE_BUFFER = 9;

	// MTLTextureUsage
	public static final int USAGE_SHADER_READ = 0x1;
	public static final int USAGE_SHADER_WRITE = 0x2;
	public static final int USAGE_RENDER_TARGET = 0x4;
	public static final int USAGE_PIXEL_FORMAT_VIEW = 0x10;

	// MTLStorageMode
	public static final int STORAGE_SHARED = 0;
	public static final int STORAGE_MANAGED = 1;
	public static final int STORAGE_PRIVATE = 2;
	public static final int STORAGE_MEMORYLESS = 3;

	// MTLResourceOptions (cpuCacheMode | storageMode << 4 | hazardTrackingMode << 8)
	public static final int RESOURCE_CPU_CACHE_DEFAULT = 0;
	public static final int RESOURCE_CPU_CACHE_WRITE_COMBINED = 1;
	public static final int RESOURCE_STORAGE_SHARED = STORAGE_SHARED << 4;
	public static final int RESOURCE_STORAGE_MANAGED = STORAGE_MANAGED << 4;
	public static final int RESOURCE_STORAGE_PRIVATE = STORAGE_PRIVATE << 4;

	// MTLVertexStepFunction
	public static final int STEP_CONSTANT = 0;
	public static final int STEP_PER_VERTEX = 1;
	public static final int STEP_PER_INSTANCE = 2;

	// MTLSamplerAddressMode / MTLSamplerMinMagFilter / MTLSamplerMipFilter
	public static final int ADDRESS_CLAMP_TO_EDGE = 0;
	public static final int ADDRESS_REPEAT = 2;
	public static final int MINMAG_NEAREST = 0;
	public static final int MINMAG_LINEAR = 1;
	public static final int MIP_NOT_MIPMAPPED = 0;
	public static final int MIP_NEAREST = 1;
	public static final int MIP_LINEAR = 2;

	// MTLBlitOption
	public static final int BLIT_NONE = 0;
	public static final int BLIT_DEPTH_FROM_DEPTH_STENCIL = 1;
	public static final int BLIT_STENCIL_FROM_DEPTH_STENCIL = 2;

	/**
	 * Pixel format for textures, render targets and texel buffers, or {@link #PIXEL_INVALID} if Metal has no
	 * equivalent (all 3-component formats). D24S8 has no Apple-GPU equivalent and is promoted to D32F_S8.
	 */
	public static int pixelFormat(GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> 10;
			case R8_SNORM -> 12;
			case R8_UINT -> 13;
			case R8_SINT -> 14;
			case R16_UNORM -> 20;
			case R16_SNORM -> 22;
			case R16_UINT -> 23;
			case R16_SINT -> 24;
			case R16_FLOAT -> 25;
			case RG8_UNORM -> 30;
			case RG8_SNORM -> 32;
			case RG8_UINT -> 33;
			case RG8_SINT -> 34;
			case R32_UINT -> 53;
			case R32_SINT -> 54;
			case R32_FLOAT -> 55;
			case RG16_UNORM -> 60;
			case RG16_SNORM -> 62;
			case RG16_UINT -> 63;
			case RG16_SINT -> 64;
			case RG16_FLOAT -> 65;
			case RGBA8_UNORM -> 70;
			case RGBA8_SNORM -> 72;
			case RGBA8_UINT -> 73;
			case RGBA8_SINT -> 74;
			case RGB10A2_UNORM -> 90;
			case RGB10A2_UINT -> 91;
			case RG11B10_FLOAT -> 92;
			case RG32_UINT -> 103;
			case RG32_SINT -> 104;
			case RG32_FLOAT -> 105;
			case RGBA16_UNORM -> 110;
			case RGBA16_SNORM -> 112;
			case RGBA16_UINT -> 113;
			case RGBA16_SINT -> 114;
			case RGBA16_FLOAT -> 115;
			case RGBA32_UINT -> 123;
			case RGBA32_SINT -> 124;
			case RGBA32_FLOAT -> 125;
			case D16_UNORM -> 250;
			case D32_FLOAT -> PIXEL_DEPTH32_FLOAT;
			case S8_UINT -> PIXEL_STENCIL8;
			case D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT -> PIXEL_DEPTH32_FLOAT_STENCIL8;
			case RGB8_UNORM, RGB8_SNORM, RGB8_UINT, RGB8_SINT, RGB16_UNORM, RGB16_SNORM, RGB16_UINT, RGB16_SINT,
				RGB16_FLOAT, RGB32_UINT, RGB32_SINT, RGB32_FLOAT -> PIXEL_INVALID;
		};
	}

	/** Bytes per texel of the Metal pixel format chosen by {@link #pixelFormat} (differs from GpuFormat for D24S8). */
	public static int texelSize(GpuFormat format) {
		return switch (format) {
			case D24_UNORM_S8_UINT -> 8;
			default -> format.blockSize();
		};
	}

	/** MTLVertexFormat for a vertex attribute of the given format, or 0 if unsupported. */
	public static int vertexFormat(GpuFormat format) {
		int n = format.componentCount();
		return switch (format.componentType()) {
			case UNORM_8 -> pick(n, 47, 7, 8, 9);
			case SNORM_8 -> pick(n, 48, 10, 11, 12);
			case UINT_8 -> pick(n, 45, 1, 2, 3);
			case SINT_8 -> pick(n, 46, 4, 5, 6);
			case UNORM_16 -> pick(n, 51, 19, 20, 21);
			case SNORM_16 -> pick(n, 52, 22, 23, 24);
			case UINT_16 -> pick(n, 49, 13, 14, 15);
			case SINT_16 -> pick(n, 50, 16, 17, 18);
			case FLOAT_16 -> pick(n, 53, 25, 26, 27);
			case FLOAT_32 -> pick(n, 28, 29, 30, 31);
			case UINT_32 -> pick(n, 36, 37, 38, 39);
			case SINT_32 -> pick(n, 32, 33, 34, 35);
			case OPAQUE_32 -> switch (format) {
				case RGB10A2_UNORM -> 41;
				case RG11B10_FLOAT -> 54;
				default -> 0;
			};
			default -> 0;
		};
	}

	private static int pick(int components, int one, int two, int three, int four) {
		return switch (components) {
			case 1 -> one;
			case 2 -> two;
			case 3 -> three;
			case 4 -> four;
			default -> 0;
		};
	}

	public static int compareFunction(CompareOp op) {
		return switch (op) {
			case NEVER_PASS -> 0;
			case LESS_THAN -> 1;
			case EQUAL -> 2;
			case LESS_THAN_OR_EQUAL -> 3;
			case GREATER_THAN -> 4;
			case NOT_EQUAL -> 5;
			case GREATER_THAN_OR_EQUAL -> 6;
			case ALWAYS_PASS -> 7;
		};
	}

	public static int blendFactor(BlendFactor factor) {
		return switch (factor) {
			case ZERO -> 0;
			case ONE -> 1;
			case SRC_COLOR -> 2;
			case ONE_MINUS_SRC_COLOR -> 3;
			case SRC_ALPHA -> 4;
			case ONE_MINUS_SRC_ALPHA -> 5;
			case DST_COLOR -> 6;
			case ONE_MINUS_DST_COLOR -> 7;
			case DST_ALPHA -> 8;
			case ONE_MINUS_DST_ALPHA -> 9;
			case SRC_ALPHA_SATURATE -> 10;
			case CONSTANT_COLOR -> 11;
			case ONE_MINUS_CONSTANT_COLOR -> 12;
			case CONSTANT_ALPHA -> 13;
			case ONE_MINUS_CONSTANT_ALPHA -> 14;
		};
	}

	public static int blendOperation(BlendOp op) {
		return switch (op) {
			case ADD -> 0;
			case SUBTRACT -> 1;
			case REVERSE_SUBTRACT -> 2;
			case MIN -> 3;
			case MAX -> 4;
		};
	}

	/** Renderpearl write mask (R=1, G=2, B=4, A=8) to MTLColorWriteMask (R=8, G=4, B=2, A=1). */
	public static int colorWriteMask(@ColorTargetState.WriteMask int mask) {
		int metal = 0;
		if ((mask & ColorTargetState.WRITE_RED) != 0) metal |= 8;
		if ((mask & ColorTargetState.WRITE_GREEN) != 0) metal |= 4;
		if ((mask & ColorTargetState.WRITE_BLUE) != 0) metal |= 2;
		if ((mask & ColorTargetState.WRITE_ALPHA) != 0) metal |= 1;
		return metal;
	}

	/**
	 * MTLPrimitiveType used for draws. QUADS and LINES are already expanded to triangle lists by the caller's
	 * index buffer (see PrimitiveTopology#indexCount). TRIANGLE_FAN has no Metal equivalent: the render pass
	 * converts fan draws to triangle lists.
	 */
	public static int primitiveType(PrimitiveTopology topology) {
		return switch (topology) {
			case POINTS -> PRIMITIVE_POINT;
			case DEBUG_LINES -> PRIMITIVE_LINE;
			case DEBUG_LINE_STRIP -> PRIMITIVE_LINE_STRIP;
			case TRIANGLE_STRIP -> PRIMITIVE_TRIANGLE_STRIP;
			case LINES, TRIANGLES, TRIANGLE_FAN, QUADS -> PRIMITIVE_TRIANGLE;
		};
	}

	public static int topologyClass(PrimitiveTopology topology) {
		return switch (topology) {
			case POINTS -> TOPOLOGY_CLASS_POINT;
			case DEBUG_LINES, DEBUG_LINE_STRIP -> TOPOLOGY_CLASS_LINE;
			case LINES, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS -> TOPOLOGY_CLASS_TRIANGLE;
		};
	}

	public static int indexType(IndexType type) {
		return switch (type) {
			case SHORT -> INDEX_UINT16;
			case INT -> INDEX_UINT32;
		};
	}

	public static int addressMode(AddressMode mode) {
		return switch (mode) {
			case REPEAT -> ADDRESS_REPEAT;
			case CLAMP_TO_EDGE -> ADDRESS_CLAMP_TO_EDGE;
		};
	}

	public static int minMagFilter(FilterMode mode) {
		return switch (mode) {
			case NEAREST -> MINMAG_NEAREST;
			case LINEAR -> MINMAG_LINEAR;
		};
	}

	public static boolean isDepthStencil(GpuFormat format) {
		return format.hasDepthAspect() || format.hasStencilAspect();
	}
}
