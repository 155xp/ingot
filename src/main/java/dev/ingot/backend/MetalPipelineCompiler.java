package dev.ingot.backend;

import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.ingot.Ingot;
import dev.ingot.nativ.Mtl;
import dev.ingot.shader.SpirvToMsl;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Builds a MetalRenderPipeline from a RenderPipeline and its rebound SPIR-V (MetalPipelineCache): SPIR-V → MSL,
 * per-stage MTLLibrary (cached by MSL hash), default PSO (with and without D32 for depth-less pipelines, like Vulkan's
 * pair). Thread-safe. Failures log and yield null. Owner: pipelines.
 */
public final class MetalPipelineCompiler {
	// IngotPipelineDesc (native/include/ingot.h, which _Static_asserts the same offsets).
	static final long DESC_SIZE = 1352;
	private static final long DESC_VERTEX_FUNCTION = 0;
	private static final long DESC_FRAGMENT_FUNCTION = 8;
	private static final long DESC_ATTRIBUTES = 16;       // IngotVertexAttribute[31], indexed by shader location
	private static final long DESC_LAYOUTS = 512;         // IngotVertexLayout[31], indexed by argument-table index
	private static final long DESC_COLORS = 1008;         // IngotColorTarget[8]
	private static final long DESC_DEPTH_FORMAT = 1328;
	private static final long DESC_STENCIL_FORMAT = 1332;
	private static final long DESC_SAMPLE_COUNT = 1336;
	private static final long DESC_TOPOLOGY_CLASS = 1340;
	private static final long DESC_LABEL = 1344;
	private static final long ATTRIBUTE_SIZE = 16;        // format, offset, buffer_index, _pad
	private static final long LAYOUT_SIZE = 16;           // stride, step_function, step_rate, _pad
	private static final long COLOR_SIZE = 40;            // pixel_format, blend_enabled, rgb_op, alpha_op, src_rgb, dst_rgb, src_alpha, dst_alpha, write_mask, _pad
	private static final int MAX_VERTEX_BUFFERS = 16;     // RenderPass vertex buffer slots

	/** GLSL entry point of every stage. */
	private static final String ENTRY_POINT = "main";

	/** MTLLibrary caches, one per device, alive until {@link #releaseLibraries}. */
	private static final ConcurrentHashMap<MetalDevice, LibraryCache> LIBRARIES = new ConcurrentHashMap<>();

	private MetalPipelineCompiler() {
	}

	/**
	 * The pipeline, or null if it cannot be built (logged). {@code bindings} is the list the SPIR-V modules were rebound
	 * to: binding i of descriptor set 0 is bindings[i]. Reads the modules without consuming them. CONTRACT
	 */
	public static @Nullable MetalRenderPipeline compile(MetalDevice device, RenderPipeline renderPipeline, ByteBuffer vertexSpirv,
														ByteBuffer fragmentSpirv, List<SpirvToMsl.Binding> bindings) {
		try {
			return build(device, renderPipeline, vertexSpirv, fragmentSpirv, bindings);
		} catch (PipelineException | SpirvToMsl.TranslationException e) {
			Ingot.LOGGER.error("Ingot: cannot compile pipeline {}: {}", renderPipeline.getLocation(), e.getMessage());
		} catch (Throwable t) {
			Ingot.LOGGER.error("Ingot: cannot compile pipeline {}", renderPipeline.getLocation(), t);
		}
		return null;
	}

	/**
	 * Releases every MTLLibrary compiled for the device. Called once by MetalDevice.close() after the GPU is idle and
	 * before the native device is destroyed. Libraries are never referenced by command buffers. CONTRACT
	 */
	public static void releaseLibraries(MetalDevice device) {
		LibraryCache cache = LIBRARIES.remove(device);
		if (cache != null) {
			cache.release();
		}
	}

	private static MetalRenderPipeline build(MetalDevice device, RenderPipeline renderPipeline, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv,
											 List<SpirvToMsl.Binding> bindings) throws PipelineException, SpirvToMsl.TranslationException {
		List<SpirvToMsl.Slot> slots = SpirvToMsl.assignSlots(bindings);
		SpirvToMsl.Stage vertex = SpirvToMsl.translate(vertexSpirv, true, ENTRY_POINT, slots);
		SpirvToMsl.Stage fragment = SpirvToMsl.translate(fragmentSpirv, false, ENTRY_POINT, slots);

		// The descriptor lives as long as the pipeline: variants only rewrite the attachment fields.
		String name = renderPipeline.getLocation().toString();
		List<@Nullable ColorTargetState> targets = Arrays.asList(renderPipeline.getColorTargetStates());
		Arena arena = Arena.ofAuto();
		MemorySegment desc = arena.allocate(DESC_SIZE, 8);
		writeDescriptor(desc, renderPipeline);
		int depthFormat = defaultDepthFormat(renderPipeline);
		int colorMask = targetMask(targets);
		writeAttachments(desc, targets, depthFormat, MetalFormats.PIXEL_INVALID, colorMask);
		desc.set(JAVA_LONG, DESC_LABEL, arena.allocateFrom(name).address());

		LibraryCache libraries = LIBRARIES.computeIfAbsent(device, d -> new LibraryCache());
		long vertexFunction = 0L;
		long fragmentFunction = 0L;
		boolean built = false;
		try {
			vertexFunction = function(device, libraries, renderPipeline.getVertexShader().toString(), vertex);
			fragmentFunction = function(device, libraries, renderPipeline.getFragmentShader().toString(), fragment);
			desc.set(JAVA_LONG, DESC_VERTEX_FUNCTION, vertexFunction);
			desc.set(JAVA_LONG, DESC_FRAGMENT_FUNCTION, fragmentFunction);
			long state = Mtl.pipelineCreate(device.handle(), desc.address());
			if (state == 0L) {
				throw new PipelineException("render pipeline state: " + lastError());
			}
			MetalRenderPipeline pipeline = new MetalRenderPipeline(device, renderPipeline, targets, bindings, slots, vertex, fragment, vertexFunction,
				fragmentFunction, desc, MetalRenderPipeline.variantKey(depthFormat, MetalFormats.PIXEL_INVALID, colorMask), state);
			built = true;
			if (depthFormat == MetalFormats.PIXEL_INVALID) {
				// Depth-less pipelines also draw into passes with the D32 attachment (the GUI on the main target, sky);
				// build that variant here too, as Vulkan does, rather than on the render thread at first use.
				pipeline.prebuildVariant(MetalFormats.PIXEL_DEPTH32_FLOAT, MetalFormats.PIXEL_INVALID, colorMask);
			}
			return pipeline;
		} finally {
			if (!built) {
				// Never used by the GPU, so no deferral is needed.
				Mtl.release(vertexFunction);
				Mtl.release(fragmentFunction);
			}
		}
	}

	private static long function(MetalDevice device, LibraryCache libraries, String shaderName, SpirvToMsl.Stage stage) throws PipelineException {
		long library = libraries.get(device, stage.msl(), shaderName);
		try (Arena arena = Arena.ofConfined()) {
			long function = Mtl.libraryFunction(library, arena.allocateFrom(stage.entryPoint()).address());
			if (function == 0L) {
				throw new PipelineException(shaderName + ": " + lastError());
			}
			return function;
		}
	}

	/** Depth format of the variant built eagerly: D32 when the pipeline tests or writes depth, none otherwise. */
	static int defaultDepthFormat(RenderPipeline renderPipeline) {
		return renderPipeline.getDepthStencilState() != null ? MetalFormats.PIXEL_DEPTH32_FLOAT : MetalFormats.PIXEL_INVALID;
	}

	/** Bit i set for every non-null color target state (the attachments of the default variant). */
	static int targetMask(List<@Nullable ColorTargetState> targets) {
		int mask = 0;
		for (int i = 0; i < targets.size(); i++) {
			if (targets.get(i) != null) {
				mask |= 1 << i;
			}
		}
		return mask;
	}

	/**
	 * Everything except the function handles, the label and the attachments. Attribute locations are numbered across
	 * the vertex formats in buffer slot order, one per element, as the Vulkan backend and IntermediaryShaderModule.rebind
	 * number them.
	 */
	static void writeDescriptor(MemorySegment desc, RenderPipeline renderPipeline) throws PipelineException {
		VertexFormat[] formats = renderPipeline.getVertexFormatBindings();
		int location = 0;
		for (int slot = 0; slot < formats.length; slot++) {
			VertexFormat vertexFormat = formats[slot];
			// Layouts only for buffers that feed an attribute; an unread buffer needs no vertex fetch.
			if (vertexFormat == null || vertexFormat.getElements().isEmpty()) {
				continue;
			}
			if (slot >= MAX_VERTEX_BUFFERS) {
				throw new PipelineException("vertex buffer slot " + slot + " is out of range");
			}
			for (VertexFormatElement element : vertexFormat.getElements()) {
				if (location >= Mtl.MAX_VERTEX_ATTRIBUTES) {
					throw new PipelineException("vertex attribute location " + location + " is out of range");
				}
				int format = MetalFormats.vertexFormat(element.format());
				if (format == 0) {
					throw new PipelineException("vertex format " + element.format() + " has no Metal equivalent");
				}
				long at = DESC_ATTRIBUTES + location * ATTRIBUTE_SIZE;
				desc.set(JAVA_INT, at, format);
				desc.set(JAVA_INT, at + 4, element.offset());
				desc.set(JAVA_INT, at + 8, MetalRenderPipeline.vertexBufferSlot(slot));
				location++;
			}
			int stepRate = vertexFormat.getStepRate();
			long at = DESC_LAYOUTS + MetalRenderPipeline.vertexBufferSlot(slot) * LAYOUT_SIZE;
			desc.set(JAVA_INT, at, vertexFormat.getVertexSize());
			desc.set(JAVA_INT, at + 4, stepRate > 0 ? MetalFormats.STEP_PER_INSTANCE : MetalFormats.STEP_PER_VERTEX);
			desc.set(JAVA_INT, at + 8, Math.max(stepRate, 1));
		}
		desc.set(JAVA_INT, DESC_SAMPLE_COUNT, 1);
		desc.set(JAVA_INT, DESC_TOPOLOGY_CLASS, MetalFormats.topologyClass(renderPipeline.getPrimitiveTopology()));
	}

	/**
	 * Writes the attachment formats of one PSO variant: color attachment i gets target state i when bit i of
	 * {@code colorMask} is set and no attachment otherwise.
	 */
	static void writeAttachments(MemorySegment desc, List<@Nullable ColorTargetState> targets, int depthFormat, int stencilFormat,
								 int colorMask) throws PipelineException {
		if (colorMask >>> Mtl.MAX_COLOR_TARGETS != 0) {
			throw new PipelineException("color attachment mask " + Integer.toBinaryString(colorMask) + " exceeds "
				+ Mtl.MAX_COLOR_TARGETS + " attachments");
		}
		desc.asSlice(DESC_COLORS, Mtl.MAX_COLOR_TARGETS * COLOR_SIZE).fill((byte) 0);
		for (int i = 0; i < Mtl.MAX_COLOR_TARGETS; i++) {
			if ((colorMask >>> i & 1) == 0) {
				continue;
			}
			ColorTargetState target = i < targets.size() ? targets.get(i) : null;
			if (target == null) {
				throw new PipelineException("color attachment " + i + " has no color target state");
			}
			int format = MetalFormats.pixelFormat(target.format());
			if (format == MetalFormats.PIXEL_INVALID) {
				throw new PipelineException("color format " + target.format() + " has no Metal equivalent");
			}
			long at = DESC_COLORS + i * COLOR_SIZE;
			desc.set(JAVA_INT, at, format);
			BlendFunction blend = target.blendFunction().orElse(null);
			if (blend != null) {
				desc.set(JAVA_INT, at + 4, 1);
				writeBlendEquation(desc, at + 8, at + 16, blend.color());
				writeBlendEquation(desc, at + 12, at + 24, blend.alpha());
			}
			desc.set(JAVA_INT, at + 32, MetalFormats.colorWriteMask(target.writeMask()));
		}
		desc.set(JAVA_INT, DESC_DEPTH_FORMAT, depthFormat);
		desc.set(JAVA_INT, DESC_STENCIL_FORMAT, stencilFormat);
	}

	private static void writeBlendEquation(MemorySegment desc, long opAt, long factorsAt, BlendEquation equation) {
		BlendOp op = equation.op();
		// GL and Vulkan ignore the factors of MIN/MAX; ONE makes Metal compute the same min/max of the plain values.
		boolean minMax = op == BlendOp.MIN || op == BlendOp.MAX;
		desc.set(JAVA_INT, opAt, MetalFormats.blendOperation(op));
		desc.set(JAVA_INT, factorsAt, MetalFormats.blendFactor(minMax ? BlendFactor.ONE : equation.sourceFactor()));
		desc.set(JAVA_INT, factorsAt + 4, MetalFormats.blendFactor(minMax ? BlendFactor.ONE : equation.destFactor()));
	}

	/** ingot_last_error() of the calling thread. */
	static String lastError() {
		return MemorySegment.ofAddress(Mtl.lastError()).reinterpret(Integer.MAX_VALUE).getString(0);
	}

	/** An unsupported or failed pipeline; the message is logged with the pipeline name. */
	static final class PipelineException extends Exception {
		PipelineException(String message) {
			super(message);
		}
	}

	private record Library(long handle, @Nullable String error) {
	}

	/** SHA-256 of the UTF-8 MSL source. */
	private record LibraryKey(long a, long b, long c, long d) {
		static LibraryKey of(byte[] source) {
			MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
			ByteBuffer hash = ByteBuffer.wrap(digest.digest(source));
			return new LibraryKey(hash.getLong(), hash.getLong(), hash.getLong(), hash.getLong());
		}
	}

	/**
	 * Device-lifetime MTLLibrary cache. The same MSL is common across pipelines (define variants that differ only in
	 * unused code, shared post-effect vertex shaders) and across resource reloads. Concurrent requests for the same
	 * source wait for one compile and share its result. A failure is dropped once those waiters have it, so a later
	 * request (e.g. the next resource reload) compiles again: the Metal compiler service can fail transiently.
	 */
	private static final class LibraryCache {
		private static final Library FAILED = new Library(0L, "MSL compile failed (see the first error for this shader)");

		private final ConcurrentHashMap<LibraryKey, CompletableFuture<Library>> libraries = new ConcurrentHashMap<>();

		long get(MetalDevice device, String msl, String label) throws PipelineException {
			byte[] source = msl.getBytes(StandardCharsets.UTF_8);
			LibraryKey key = LibraryKey.of(source);
			CompletableFuture<Library> future = this.libraries.get(key);
			if (future == null) {
				CompletableFuture<Library> created = new CompletableFuture<>();
				future = this.libraries.putIfAbsent(key, created);
				if (future == null) {
					future = created;
					Library library = FAILED;
					try {
						library = create(device, source, label);
					} finally {
						// Always complete, so threads waiting for this source never hang.
						created.complete(library);
						if (library.handle() == 0L) {
							this.libraries.remove(key, created);
						}
					}
				}
			}
			Library library = future.join();
			if (library.handle() == 0L) {
				throw new PipelineException(label + ": " + library.error());
			}
			return library.handle();
		}

		private static Library create(MetalDevice device, byte[] source, String label) {
			try (Arena arena = Arena.ofConfined()) {
				// Arena memory is zeroed, so the extra byte is the NUL terminator.
				MemorySegment text = arena.allocate(source.length + 1L);
				MemorySegment.copy(source, 0, text, ValueLayout.JAVA_BYTE, 0, source.length);
				long handle = Mtl.libraryCreate(device.handle(), text.address(), arena.allocateFrom(label).address());
				return handle != 0L ? new Library(handle, null) : new Library(0L, lastError());
			}
		}

		void release() {
			for (CompletableFuture<Library> future : this.libraries.values()) {
				Library library = future.getNow(null);
				if (library != null) {
					Mtl.release(library.handle());
				}
			}
			this.libraries.clear();
		}
	}
}
