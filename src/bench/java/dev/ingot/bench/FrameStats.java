package dev.ingot.bench;

import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * Frame-time statistics. Definitions (n = number of frames, times in milliseconds):
 * <ul>
 * <li>avgFps = n / (sum of frame times in seconds), i.e. frames per wall-clock second</li>
 * <li>p50/p95/p99 = nearest-rank percentile of the frame times: the value at sorted index ceil(p*n)-1</li>
 * <li>1% low FPS = 1000 / (mean of the slowest ceil(0.01*n) frame times); 0.1% low likewise with ceil(0.001*n)</li>
 * <li>max = slowest single frame</li>
 * </ul>
 */
public record FrameStats(
	int frames,
	double seconds,
	double avgFps,
	double meanMs,
	double stdevMs,
	double minMs,
	double p50Ms,
	double p95Ms,
	double p99Ms,
	double maxMs,
	double onePercentLowFps,
	double pointOnePercentLowFps
) {
	public static FrameStats of(double[] frameMs, int from, int to) {
		int n = to - from;
		if (n <= 0) {
			return new FrameStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		}
		double[] sorted = Arrays.copyOfRange(frameMs, from, to);
		Arrays.sort(sorted);
		double sum = 0;
		for (double v : sorted) {
			sum += v;
		}
		double mean = sum / n;
		double var = 0;
		for (double v : sorted) {
			var += (v - mean) * (v - mean);
		}
		return new FrameStats(
			n,
			sum / 1000.0,
			n / (sum / 1000.0),
			mean,
			Math.sqrt(var / n),
			sorted[0],
			percentile(sorted, 0.50),
			percentile(sorted, 0.95),
			percentile(sorted, 0.99),
			sorted[n - 1],
			lowFps(sorted, 0.01),
			lowFps(sorted, 0.001)
		);
	}

	private static double percentile(double[] sorted, double p) {
		int index = (int) Math.ceil(p * sorted.length) - 1;
		return sorted[Math.clamp(index, 0, sorted.length - 1)];
	}

	private static double lowFps(double[] sorted, double fraction) {
		int count = Math.max(1, (int) Math.ceil(fraction * sorted.length));
		double sum = 0;
		for (int i = sorted.length - count; i < sorted.length; i++) {
			sum += sorted[i];
		}
		return 1000.0 / (sum / count);
	}

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("frames", this.frames);
		o.addProperty("seconds", round(this.seconds, 3));
		o.addProperty("avgFps", round(this.avgFps, 2));
		o.addProperty("onePercentLowFps", round(this.onePercentLowFps, 2));
		o.addProperty("pointOnePercentLowFps", round(this.pointOnePercentLowFps, 2));
		o.addProperty("meanFrameMs", round(this.meanMs, 4));
		o.addProperty("stdevFrameMs", round(this.stdevMs, 4));
		o.addProperty("minFrameMs", round(this.minMs, 4));
		o.addProperty("p50FrameMs", round(this.p50Ms, 4));
		o.addProperty("p95FrameMs", round(this.p95Ms, 4));
		o.addProperty("p99FrameMs", round(this.p99Ms, 4));
		o.addProperty("maxFrameMs", round(this.maxMs, 4));
		return o;
	}

	static double round(double v, int digits) {
		double scale = Math.pow(10, digits);
		return Math.round(v * scale) / scale;
	}
}
