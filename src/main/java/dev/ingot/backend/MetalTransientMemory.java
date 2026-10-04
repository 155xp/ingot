package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import dev.ingot.nativ.Mtl;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit scratch memory: a pool of large Shared MTLBuffer blocks (WriteCombined for staging), recycled once the
 * serial that last used a block completes. Semantics follow docs/research/frontend-contracts.md section 10.
 * Owner: core.
 *
 * <p>Three pools, each a bump allocator over 512 KiB blocks with the partial-allocation rules of renderpearl's
 * TransientBlockAllocator:
 * <ul>
 * <li>CPU: malloc'd blocks for {@link #allocateCpu}, reusable as soon as the submit ends.</li>
 * <li>Mapped: Shared + WriteCombined MTLBuffers for staging, GPU-mapped memory and internal uploads. On unified memory
 *     it also serves {@link #allocateGpu}, since the GPU reads Shared memory at full speed there.</li>
 * <li>Private: GPU-only blocks for {@link #allocateGpu} on non-unified devices.</li>
 * </ul>
 * GPU blocks used during a frame are tagged with the frame's final serial (allocations stay valid across fence
 * flushes until submit) and become reusable when it completes. Blocks that sit unused in a free list for a few
 * seconds are released, so the pool shrinks back to the working set after a burst (memory is energy too).
 * Oversized requests get a dedicated block that is released once its serial completes.
 */
public final class MetalTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 512L * 1024L;
	private static final long MAX_CPU_ALIGNMENT = 16L;
	private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
	private static final long TRIM_AFTER_NS = 4_000_000_000L;
	private static final Runnable NO_OP = () -> {};

	private final MetalDevice device;
	private final Pool cpu;
	private final Pool mapped;
	private final Pool gpu;
	/** Number of completed frames; transient buffers report closed once it moves past their frame. */
	private long frame;
	private long stagedHandle;
	private long stagedContents;

	MetalTransientMemory(MetalDevice device) {
		this.device = device;
		this.cpu = new Pool(-1, MAX_CPU_ALIGNMENT);
		this.mapped = new Pool(MetalFormats.RESOURCE_STORAGE_SHARED | MetalFormats.RESOURCE_CPU_CACHE_WRITE_COMBINED, MAX_GPU_ALIGNMENT);
		this.gpu = device.caps().unifiedMemory() ? this.mapped : new Pool(MetalFormats.RESOURCE_STORAGE_PRIVATE, MAX_GPU_ALIGNMENT);
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		checkMappable(size);
		Block block = this.cpu.allocate(size, alignment, minimumAllocation, elementSize);
		return byteBuffer(block.contents + this.cpu.lastOffset, this.cpu.lastSize);
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		Block block = this.gpu.allocate(size, alignment, minimumAllocation, elementSize);
		return new GpuBufferSlice(new TransientBuffer(block, usage), this.gpu.lastOffset, this.gpu.lastSize);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return this.upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
		return this.multiUpload(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
		return this.multiUpload(data, alignment, usage);
	}

	/**
	 * Allocation-free staging for the encoder's own uploads: reserves {@code size} bytes of Shared WriteCombined memory
	 * valid for commands of the current frame and returns the offset into {@link #stagedHandle()}, whose CPU address
	 * is {@link #stagedContents()}.
	 */
	long stage(long size, long alignment) {
		Block block = this.mapped.allocate(size, alignment, size, 1L);
		this.stagedHandle = block.handle;
		this.stagedContents = block.contents;
		return this.mapped.lastOffset;
	}

	/** id&lt;MTLBuffer&gt; of the most recent {@link #stage} call. */
	long stagedHandle() {
		return this.stagedHandle;
	}

	/** CPU address of the start of the block of the most recent {@link #stage} call. */
	long stagedContents() {
		return this.stagedContents;
	}

	/** End of a frame (in submit, after its last commit): blocks used by it become reusable once {@code serial} completes. */
	void endFrame(long serial, long nowNs) {
		this.cpu.endFrame(serial, nowNs);
		this.mapped.endFrame(serial, nowNs);
		if (this.gpu != this.mapped) {
			this.gpu.endFrame(serial, nowNs);
		}
		this.frame++;
	}

	/** Recycles blocks whose serial completed and releases blocks that have been idle for a while. */
	void recycle(long completedSerial, long nowNs) {
		this.cpu.recycle(completedSerial, nowNs);
		this.mapped.recycle(completedSerial, nowNs);
		if (this.gpu != this.mapped) {
			this.gpu.recycle(completedSerial, nowNs);
		}
	}

	/** Releases every block. The GPU must be idle. */
	void close() {
		this.cpu.close();
		this.mapped.close();
		if (this.gpu != this.mapped) {
			this.gpu.close();
		}
	}

	private GpuBufferSlice.MappedView allocateMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		checkMappable(size);
		Block block = this.mapped.allocate(size, alignment, minimumAllocation, elementSize);
		long offset = this.mapped.lastOffset;
		long length = this.mapped.lastSize;
		GpuBufferSlice slice = new GpuBufferSlice(new TransientBuffer(block, usage), offset, length);
		return new GpuBufferSlice.MappedView(slice, byteBuffer(block.contents + offset, length), NO_OP);
	}

	/** Copies the buffers back to back into one mapped allocation, each start rounded up to {@code alignment}. */
	private GpuBufferSlice upload(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		long totalSize = 0L;
		for (int i = 0; i < data.size(); i++) {
			totalSize = Mth.roundToward(totalSize + data.get(i).remaining(), alignment);
		}
		checkMappable(totalSize);
		Block block = this.mapped.allocate(totalSize, alignment, minimumAllocation, elementSize);
		long base = this.mapped.lastOffset;
		long length = this.mapped.lastSize;
		long offset = 0L;
		for (int i = 0; i < data.size(); i++) {
			ByteBuffer buffer = data.get(i);
			copy(buffer, block.contents + base + offset, Math.min(length - offset, buffer.remaining()));
			offset = Mth.roundToward(offset + buffer.remaining(), alignment);
			if (offset >= length) {
				break;
			}
		}
		return new GpuBufferSlice(new TransientBuffer(block, usage), base, length);
	}

	/** One slice per input, in input order, packed largest-first into the current block (as the vanilla backends do). */
	private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, long alignment, int usage) {
		GpuBufferSlice[] slices = new GpuBufferSlice[data.size()];
		IntArrayList order = new IntArrayList(data.size());
		for (int i = 0; i < data.size(); i++) {
			order.add(i);
		}
		order.sort((a, b) -> Integer.compare(data.get(a).remaining(), data.get(b).remaining()));
		while (!order.isEmpty()) {
			int chosen = order.size() - 1;
			for (int i = order.size() - 1; i >= 0; i--) {
				if (this.mapped.fitsInCurrentBlock(data.get(order.getInt(i)).remaining(), alignment)) {
					chosen = i;
					break;
				}
			}
			int index = order.removeInt(chosen);
			ByteBuffer buffer = data.get(index);
			Block block = this.mapped.allocate(buffer.remaining(), alignment, buffer.remaining(), 1L);
			copy(buffer, block.contents + this.mapped.lastOffset, buffer.remaining());
			slices[index] = new GpuBufferSlice(new TransientBuffer(block, usage), this.mapped.lastOffset, this.mapped.lastSize);
		}
		return Arrays.asList(slices);
	}

	private static void checkMappable(long size) {
		if (size > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Transient allocations larger than 2GB are not supported");
		}
	}

	private static ByteBuffer byteBuffer(long address, long length) {
		return MemoryUtil.memByteBuffer(address, (int) length).order(ByteOrder.nativeOrder());
	}

	/** Copies {@code bytes} bytes starting at the source's position to a native address. */
	static void copy(ByteBuffer source, long destination, long bytes) {
		if (source.isDirect()) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(source), destination, bytes);
		} else {
			MemorySegment.copy(MemorySegment.ofBuffer(source), 0L, MemorySegment.ofAddress(destination).reinterpret(bytes), 0L, bytes);
		}
	}

	private static final class Block {
		final long handle;
		final long contents;
		final long size;
		final boolean dedicated;
		/** The serial whose completion makes the block reusable (GPU pools). */
		long serial;
		/** System.nanoTime() when the block entered the free list. */
		long idleSince;

		Block(long handle, long contents, long size, boolean dedicated) {
			this.handle = handle;
			this.contents = contents;
			this.size = size;
			this.dedicated = dedicated;
		}
	}

	/** Port of renderpearl's TransientBlockAllocator with serial-based recycling and time-based trimming. */
	private final class Pool {
		/** MTLResourceOptions of the blocks, or -1 for malloc'd CPU blocks. */
		private final int resourceOptions;
		private final long maxAlignment;
		private @Nullable Block current;
		private long currentOffset = BLOCK_SIZE;
		/** Whole blocks taken this frame. */
		private final ArrayList<Block> used = new ArrayList<>();
		/** Oversized one-off blocks taken this frame. */
		private final ArrayList<Block> dedicated = new ArrayList<>();
		/** GPU pools: blocks of finished frames waiting for their serial, oldest first. */
		private final ArrayDeque<Block> retiring = new ArrayDeque<>();
		/** Reusable blocks; taken from the tail (most recently used), trimmed from the head (idle the longest). */
		private final ArrayDeque<Block> free = new ArrayDeque<>();
		/** Result of the last {@link #allocate}: offset into the returned block and the allocated length. */
		long lastOffset;
		long lastSize;

		Pool(int resourceOptions, long maxAlignment) {
			this.resourceOptions = resourceOptions;
			this.maxAlignment = maxAlignment;
		}

		Block allocate(long size, long alignment, long minimumAllocation, long elementSize) {
			if (alignment > this.maxAlignment) {
				throw new IllegalArgumentException("Alignment requirement over maximum supported alignment");
			}
			if (size == BLOCK_SIZE) {
				return this.result(this.takeBlock(), 0L, BLOCK_SIZE);
			}
			if (size > BLOCK_SIZE) {
				Block block = this.createBlock(size, true);
				this.dedicated.add(block);
				return this.result(block, 0L, size);
			}
			if (this.current == null) {
				this.current = this.takeBlock();
				this.currentOffset = 0L;
			}
			long alignedOffset = Mth.roundToward(this.currentOffset, alignment);
			if (size <= BLOCK_SIZE - alignedOffset) {
				this.currentOffset = alignedOffset + size;
				return this.result(this.current, alignedOffset, size);
			}
			if (minimumAllocation <= BLOCK_SIZE - alignedOffset) {
				long allocated = (BLOCK_SIZE - alignedOffset) / elementSize * elementSize;
				this.currentOffset = alignedOffset + allocated;
				return this.result(this.current, alignedOffset, allocated);
			}
			Block block = this.takeBlock();
			if (this.currentOffset > size) {
				// The new block has more room left than the current one.
				this.current = block;
				this.currentOffset = size;
			}
			return this.result(block, 0L, size);
		}

		boolean fitsInCurrentBlock(long size, long alignment) {
			if (alignment > this.maxAlignment) {
				return false;
			}
			if (this.current == null) {
				return size <= BLOCK_SIZE;
			}
			return size <= BLOCK_SIZE - Mth.roundToward(this.currentOffset, alignment);
		}

		private Block result(Block block, long offset, long size) {
			this.lastOffset = offset;
			this.lastSize = size;
			return block;
		}

		private Block takeBlock() {
			Block block = this.free.pollLast();
			if (block == null) {
				block = this.createBlock(BLOCK_SIZE, false);
			}
			this.used.add(block);
			return block;
		}

		private Block createBlock(long size, boolean dedicated) {
			if (this.resourceOptions < 0) {
				long address = MemoryUtil.nmemAlloc(size);
				if (address == 0L) {
					throw new GpuOutOfMemoryException("Could not allocate " + size + " bytes of transient CPU memory");
				}
				return new Block(0L, address, size, dedicated);
			}
			MetalDevice device = MetalTransientMemory.this.device;
			long buffer = Mtl.bufferCreate(device.handle(), size, this.resourceOptions, 0L, device.cLabel("Ingot transient memory"));
			if (buffer == 0L) {
				throw new GpuOutOfMemoryException("Could not allocate a " + size + " byte transient Metal buffer: " + MetalDevice.lastError());
			}
			return new Block(buffer, Mtl.bufferContents(buffer), size, dedicated);
		}

		private void destroyBlock(Block block) {
			if (this.resourceOptions < 0) {
				MemoryUtil.nmemFree(block.contents);
			} else {
				Mtl.release(block.handle);
			}
		}

		void endFrame(long serial, long nowNs) {
			this.current = null;
			this.currentOffset = BLOCK_SIZE;
			if (this.resourceOptions < 0) {
				// CPU memory is only valid until the submit, nothing on the GPU reads it.
				for (int i = 0; i < this.used.size(); i++) {
					Block block = this.used.get(i);
					block.idleSince = nowNs;
					this.free.addLast(block);
				}
				for (int i = 0; i < this.dedicated.size(); i++) {
					this.destroyBlock(this.dedicated.get(i));
				}
			} else {
				for (int i = 0; i < this.used.size(); i++) {
					Block block = this.used.get(i);
					block.serial = serial;
					this.retiring.addLast(block);
				}
				for (int i = 0; i < this.dedicated.size(); i++) {
					Block block = this.dedicated.get(i);
					block.serial = serial;
					this.retiring.addLast(block);
				}
			}
			this.used.clear();
			this.dedicated.clear();
		}

		void recycle(long completedSerial, long nowNs) {
			Block block;
			while ((block = this.retiring.peekFirst()) != null && block.serial <= completedSerial) {
				this.retiring.pollFirst();
				if (block.dedicated) {
					this.destroyBlock(block);
				} else {
					block.idleSince = nowNs;
					this.free.addLast(block);
				}
			}
			while ((block = this.free.peekFirst()) != null && nowNs - block.idleSince > TRIM_AFTER_NS) {
				this.destroyBlock(this.free.pollFirst());
			}
		}

		void close() {
			this.current = null;
			for (Block block : this.used) {
				this.destroyBlock(block);
			}
			for (Block block : this.dedicated) {
				this.destroyBlock(block);
			}
			for (Block block : this.retiring) {
				this.destroyBlock(block);
			}
			for (Block block : this.free) {
				this.destroyBlock(block);
			}
			this.used.clear();
			this.dedicated.clear();
			this.retiring.clear();
			this.free.clear();
		}
	}

	/**
	 * The GpuBuffer behind every transient slice: a whole block, valid only for the frame it was allocated in.
	 * map() and slice() throw, as in both vanilla backends; it extends MetalBuffer so render passes bind it like any
	 * other buffer.
	 */
	private final class TransientBuffer extends MetalBuffer {
		private final long allocationFrame;
		private boolean closed;

		TransientBuffer(Block block, int usage) {
			super(MetalTransientMemory.this.device, block.handle, block.contents, usage, block.size, false);
			this.allocationFrame = MetalTransientMemory.this.frame;
		}

		@Override
		public boolean isClosed() {
			return this.closed || this.allocationFrame != MetalTransientMemory.this.frame;
		}

		@Override
		public void close() {
			this.closed = true;
		}

		@Override
		public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
			throw new IllegalStateException("Cannot map transient buffer");
		}

		@Override
		public GpuBufferSlice slice(long offset, long length) {
			throw new IllegalStateException("Cannot slice transient buffer");
		}

		@Override
		public GpuBufferSlice slice() {
			throw new IllegalStateException("Cannot slice transient buffer");
		}

		/** Not cached: the block is recycled after this frame, so the view only lives as long as the current serial. */
		@Override
		long textureBufferView(long offset, long length, GpuFormat format) {
			long view = this.createTextureBufferView(offset, length, format);
			this.device.releaseLater(view);
			return view;
		}
	}
}
