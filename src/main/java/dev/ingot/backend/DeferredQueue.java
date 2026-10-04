package dev.ingot.backend;

import dev.ingot.nativ.Mtl;
import org.jspecify.annotations.Nullable;

/**
 * Native handles and render-thread tasks waiting for a command buffer serial to complete on the GPU (the Metal
 * equivalent of Vulkan's DestructionQueue, keyed by serial instead of by frame slot).
 *
 * <p>Entries are appended in increasing serial order and drained from the head, so draining stops at the first entry
 * the GPU may still need. A worker thread can append with a serial read just before the render thread advanced it;
 * such an entry only waits a little longer than necessary. Adding is thread-safe (pipeline compile workers release
 * handles too). Draining happens on the render thread and runs tasks outside the lock, so a task may add entries.
 */
final class DeferredQueue {
	private long[] serials = new long[256];
	private long[] handles = new long[256];
	private @Nullable Runnable[] tasks = new Runnable[256];
	private int head;
	private int count;

	/** Queues either a handle to ingot_release or a task to run (exactly one of them is set). */
	synchronized void add(long serial, long handle, @Nullable Runnable task) {
		if (this.count == this.serials.length) {
			this.grow();
		}
		int index = (this.head + this.count) & (this.serials.length - 1);
		this.serials[index] = serial;
		this.handles[index] = handle;
		this.tasks[index] = task;
		this.count++;
	}

	/** Releases the handles and runs the tasks of every entry up to the first one newer than {@code completedSerial}. */
	void drain(long completedSerial) {
		while (true) {
			long handle;
			Runnable task;
			synchronized (this) {
				if (this.count == 0 || this.serials[this.head] > completedSerial) {
					return;
				}
				handle = this.handles[this.head];
				task = this.tasks[this.head];
				this.tasks[this.head] = null;
				this.head = (this.head + 1) & (this.serials.length - 1);
				this.count--;
			}
			if (task != null) {
				task.run();
			} else {
				Mtl.release(handle);
			}
		}
	}

	private void grow() {
		int capacity = this.serials.length;
		long[] newSerials = new long[capacity * 2];
		long[] newHandles = new long[capacity * 2];
		Runnable[] newTasks = new Runnable[capacity * 2];
		for (int i = 0; i < this.count; i++) {
			int from = (this.head + i) & (capacity - 1);
			newSerials[i] = this.serials[from];
			newHandles[i] = this.handles[from];
			newTasks[i] = this.tasks[from];
		}
		this.serials = newSerials;
		this.handles = newHandles;
		this.tasks = newTasks;
		this.head = 0;
	}
}
