package dev.ingot.energy;

import dev.ingot.nativ.Mtl;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Moves Minecraft's background worker pool ("Worker-Main-N": chunk meshing, section visibility, world generation and
 * other integrated-server work) between the performance and the efficiency cores. On Apple silicon the scheduler picks
 * cores by a thread's QoS class, and only the thread itself can change it, so every task submitted to the pool first
 * brings its worker thread up to date with the requested class. That costs one thread-local read per task; the
 * native call only happens on the first task a thread runs after a change.
 *
 * <p>Loaded from Util's static initializer: nothing here may touch Minecraft classes, and Mtl (libingot) is only
 * loaded once a change is requested.
 */
public final class WorkerQos {
	/** Bumped (after targetQos is written) whenever the requested class changes; 0 = never requested. */
	private static volatile int generation;
	private static volatile int targetQos;
	private static final ThreadLocal<int[]> APPLIED_GENERATION = ThreadLocal.withInitial(() -> new int[1]);

	private WorkerQos() {
	}

	/** Render thread: efficient puts the pool on the efficiency cores (utility QoS), otherwise back to default. */
	public static void setEfficient(boolean efficient) {
		int qos = efficient ? Mtl.QOS_UTILITY : Mtl.QOS_DEFAULT;
		// Until battery mode first asks for the efficiency cores the threads keep the JVM's own QoS.
		if (qos != targetQos && (efficient || generation != 0)) {
			targetQos = qos;
			generation++;
		}
	}

	public static boolean isEfficient() {
		return targetQos == Mtl.QOS_UTILITY;
	}

	private static void sync() {
		int current = generation;
		int[] applied = APPLIED_GENERATION.get();
		if (applied[0] != current) {
			applied[0] = current;
			Mtl.threadSetQos(targetQos);
		}
	}

	/** Wraps the pool behind Util.backgroundExecutor(). */
	public static ExecutorService wrap(ExecutorService pool) {
		return new QosExecutorService(pool);
	}

	private static final class QosExecutorService extends AbstractExecutorService {
		private final ExecutorService pool;

		QosExecutorService(ExecutorService pool) {
			this.pool = pool;
		}

		@Override
		public void execute(Runnable command) {
			this.pool.execute(() -> {
				sync();
				command.run();
			});
		}

		@Override
		public void shutdown() {
			this.pool.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			return this.pool.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return this.pool.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return this.pool.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return this.pool.awaitTermination(timeout, unit);
		}
	}
}
