package dev.ingot.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import dev.ingot.nativ.Mtl;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * An MTLBuffer. Owner: core. Shared storage on unified-memory devices, so {@link #contents()} is a persistent CPU
 * mapping and {@link #map} is pointer arithmetic (no downcall, native byte order, position 0).
 *
 * <p>On non-unified devices CPU-visible buffers are Managed: closing a write map reports the range with
 * didModifyRange, and ranges still mapped for writing are reported before every commit (see
 * {@link MetalDevice#flushManagedWrites}), so CPU writes made before a submit reach every command in it.
 */
public class MetalBuffer extends BaseGpuBuffer {
	/** Serial of the most recent command buffer that referenced this buffer. Written by every user. CONTRACT */
	public long lastUseSerial;

	final MetalDevice device;
	private final long handle;
	private final long contents;
	private final boolean managed;
	private boolean closed;
	private int mapCount;

	// Managed storage only: union of the ranges currently mapped for writing.
	private int managedWriteMaps;
	private long dirtyStart;
	private long dirtyEnd;
	private boolean trackedForFlush;

	// The cached MTLTextureTypeTextureBuffer view (MetalDevice.textureBufferView).
	private long texelView;
	private long texelViewOffset;
	private long texelViewLength;
	private @Nullable GpuFormat texelViewFormat;

	MetalBuffer(MetalDevice device, long handle, long contents, int usage, long size, boolean managed) {
		super(usage, size);
		this.device = device;
		this.handle = handle;
		this.contents = contents;
		this.managed = managed;
	}

	/** id&lt;MTLBuffer&gt; handle. CONTRACT */
	public long handle() {
		return this.handle;
	}

	/** CPU address of the buffer storage, or 0 for GPU-private buffers. CONTRACT */
	public long contents() {
		return this.contents;
	}

	/** True for Managed storage (non-unified devices), whose GPU copy must be synchronized after GPU writes. */
	boolean managed() {
		return this.managed;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		// Like both vanilla backends: the buffer counts as closed even when the close is rejected.
		this.closed = true;
		if (this.mapCount != 0) {
			throw new IllegalStateException("Attempt to close a mapped buffer");
		}
		this.device.releaseLater(this.texelView);
		this.device.releaseLater(this.handle);
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (this.closed) {
			throw new IllegalStateException("Buffer already closed");
		}
		if (!read && !write) {
			throw new IllegalArgumentException("At least read or write must be true");
		}
		if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
			throw new IllegalStateException("Buffer is not readable");
		}
		if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
			throw new IllegalStateException("Buffer is not writable");
		}
		if (offset + length > this.size()) {
			throw new IllegalArgumentException("Cannot map more data than this buffer can hold (attempting to map " + length + " bytes at offset "
				+ offset + " from " + this.size() + " size buffer)");
		}
		if (length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
		}
		if (offset < 0 || length < 0) {
			throw new IllegalArgumentException("Offset or length must be positive integer values");
		}
		this.mapCount++;
		boolean managedWrite = write && this.managed;
		if (managedWrite) {
			this.beginManagedWrite(offset, length);
		}
		ByteBuffer data = MemoryUtil.memByteBuffer(this.contents + offset, (int) length).order(ByteOrder.nativeOrder());
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data, new Unmap(offset, length, managedWrite));
	}

	private void beginManagedWrite(long offset, long length) {
		if (this.managedWriteMaps++ == 0) {
			this.dirtyStart = offset;
			this.dirtyEnd = offset + length;
		} else {
			this.dirtyStart = Math.min(this.dirtyStart, offset);
			this.dirtyEnd = Math.max(this.dirtyEnd, offset + length);
		}
		if (!this.trackedForFlush) {
			this.trackedForFlush = true;
			this.device.trackManagedWrites(this);
		}
	}

	/**
	 * Called before each commit while tracked: reports the ranges still mapped for writing. Returns false (and stops
	 * being tracked) once no write map is open.
	 */
	boolean flushManagedWrites() {
		if (this.managedWriteMaps == 0) {
			this.trackedForFlush = false;
			return false;
		}
		Mtl.bufferDidModifyRange(this.handle, this.dirtyStart, this.dirtyEnd - this.dirtyStart);
		return true;
	}

	/** The cached texture-buffer view of [offset, offset + length); a different range or format replaces it. */
	long textureBufferView(long offset, long length, GpuFormat format) {
		if (this.texelView != 0 && this.texelViewOffset == offset && this.texelViewLength == length && this.texelViewFormat == format) {
			return this.texelView;
		}
		long view = this.createTextureBufferView(offset, length, format);
		// Commands recorded earlier may still read through the old view.
		this.device.releaseLater(this.texelView);
		this.texelView = view;
		this.texelViewOffset = offset;
		this.texelViewLength = length;
		this.texelViewFormat = format;
		return view;
	}

	final long createTextureBufferView(long offset, long length, GpuFormat format) {
		int pixelFormat = MetalFormats.pixelFormat(format);
		if (pixelFormat == MetalFormats.PIXEL_INVALID) {
			throw new IllegalArgumentException("Metal has no texel buffer format for " + format);
		}
		long texelSize = MetalFormats.texelSize(format);
		long elements = length / texelSize;
		long bytesPerRow = Mth.roundToward(elements * texelSize, Mtl.deviceTextureBufferAlignment(this.device.handle(), pixelFormat));
		long view = Mtl.textureBufferCreate(this.handle, pixelFormat, offset, elements, bytesPerRow);
		if (view == 0L) {
			throw new IllegalStateException("Could not create a " + format + " texel buffer view: " + MetalDevice.lastError());
		}
		return view;
	}

	/** MappedView.onClose: idempotent, like both vanilla backends. */
	private final class Unmap implements Runnable {
		private final long offset;
		private final long length;
		private final boolean managedWrite;
		private boolean done;

		Unmap(long offset, long length, boolean managedWrite) {
			this.offset = offset;
			this.length = length;
			this.managedWrite = managedWrite;
		}

		@Override
		public void run() {
			if (this.done) {
				return;
			}
			this.done = true;
			MetalBuffer.this.mapCount--;
			if (this.managedWrite) {
				MetalBuffer.this.managedWriteMaps--;
				Mtl.bufferDidModifyRange(MetalBuffer.this.handle, this.offset, this.length);
			}
		}
	}
}
