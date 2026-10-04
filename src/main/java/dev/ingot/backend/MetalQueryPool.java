package dev.ingot.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Timestamp queries. Apple GPUs only sample at encoder boundaries, so a timestamp resolves to the GPU start time of
 * its command buffer (if written before any work in it) or the GPU end time otherwise. Owner: core.
 *
 * <p>Writing an index only records (serial, start or end); reading resolves it once the serial completed through
 * ingot_device_serial_times (host nanoseconds, the System.nanoTime() domain, so DeviceInfo.timestampPeriod is 1 and
 * the calibration offset 0). Reads never block and report empty until the value is known.
 */
public class MetalQueryPool implements GpuQueryPool {
	private final MetalDevice device;
	/** Serial of the command buffer each index was written in; 0 = never written or permanently unavailable. */
	private final long[] serials;
	private final boolean[] atStart;
	/** Resolved values; -1 until resolved. */
	private final long[] values;

	MetalQueryPool(MetalDevice device, int size) {
		this.device = device;
		this.serials = new long[size];
		this.atStart = new boolean[size];
		this.values = new long[size];
	}

	/** Records a timestamp for {@code index} in command buffer {@code serial}; invalidates the previous value. */
	void write(int index, long serial, boolean beforeAnyWork) {
		Objects.checkIndex(index, this.serials.length);
		this.serials[index] = serial;
		this.atStart[index] = beforeAnyWork;
		this.values[index] = -1L;
	}

	@Override
	public int size() {
		return this.serials.length;
	}

	@Override
	public OptionalLong getValue(int index) {
		Objects.checkIndex(index, this.serials.length);
		return this.resolve(index);
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		if (index < 0 || count < 0 || index + count > this.serials.length) {
			throw new IndexOutOfBoundsException("getValues would read out-of-bounds for an array of " + count + " starting at " + index
				+ ", when total size is " + this.serials.length);
		}
		OptionalLong[] result = new OptionalLong[count];
		for (int i = 0; i < count; i++) {
			result[i] = this.resolve(index + i);
		}
		return result;
	}

	private OptionalLong resolve(int index) {
		long serial = this.serials[index];
		if (serial == 0L) {
			return OptionalLong.empty();
		}
		long value = this.values[index];
		if (value < 0L) {
			if (serial > this.device.completedSerial()) {
				return OptionalLong.empty();
			}
			value = this.device.serialTime(serial, this.atStart[index]);
			if (value < 0L) {
				// The device only remembers the last 256 serials; this one is gone for good.
				this.serials[index] = 0L;
				return OptionalLong.empty();
			}
			this.values[index] = value;
		}
		return OptionalLong.of(value);
	}

	/** Queries own no native object, so there is nothing to release. */
	@Override
	public void close() {
	}
}
