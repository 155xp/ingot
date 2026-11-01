// ingot-energy: energy sampler for the Ingot benchmark (bench/run.sh builds and starts it). No root needed.
//
//   ingot-energy --pid PID --out FILE [--interval-ms 100]   sample until PID exits or SIGTERM/SIGINT
//   ingot-energy --seconds S --out FILE [--interval-ms 100] sample for S seconds without a process (idle baseline)
//
// Every interval it writes one CSV row with cumulative counters:
//   t_ns              mach_absolute_time() in ns (numer/denom of mach_timebase_info, the same conversion HotSpot uses
//                     for System.nanoTime() on macOS), the midpoint of the two reads below
//   cpu_energy_nj     proc_pid_rusage(PID, RUSAGE_INFO_V6).ri_energy_nj: CPU energy attributed to the process
//   cpu_penergy_nj    ri_penergy_nj: the part of it spent on P-cores
//   instructions      ri_instructions
//   cycles            ri_cycles
//   user_ns, sys_ns   ri_user_time, ri_system_time (in Mach ticks on Apple Silicon; converted to ns here)
//   idle_wakeups      ri_pkg_idle_wkups (package idle exits caused by the process)
//   interrupt_wakeups ri_interrupt_wkups
//   phys_footprint    ri_phys_footprint: the process's physical memory footprint in bytes (Activity Monitor's Memory)
//   gpu_energy_nj     IOReport group "Energy Model", channel "GPU Energy": energy of the whole GPU (every process),
//                     converted to nJ from the channel's unit label
// Process columns are empty without --pid; the GPU column is empty when the channel is missing.
//
// The CPU channels of the "Energy Model" group ("CPU Energy", the per-cluster ones) and "DRAM" do not change on
// macOS 27, so they are not used. The subscription contains only the "GPU Energy" channel: back to back, a sample of
// all 278 channels of the group costs about 3.3 ms of CPU, a sample of the one channel about 65 us.
//
// Lines starting with '#' are metadata: the header (pid, interval, timebase, unit) and a footer with the sampler's own
// CPU time and energy, so the harness can show that the sampler costs next to nothing.
//
// Build: clang -O2 -Wall -o ingot-energy ingot-energy.c -framework CoreFoundation -lIOReport

#include <CoreFoundation/CoreFoundation.h>
#include <errno.h>
#include <libproc.h>
#include <mach/mach_time.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

// IOReport (libIOReport.dylib): private, but a stable C API used by powermetrics-style tools.
typedef struct IOReportSubscriptionRef *IOReportSubscriptionRef;
extern CFDictionaryRef IOReportCopyChannelsInGroup(CFStringRef group, CFStringRef subgroup, uint64_t a, uint64_t b, uint64_t c);
extern IOReportSubscriptionRef IOReportCreateSubscription(void *a, CFMutableDictionaryRef channels, CFMutableDictionaryRef *subscribed,
	uint64_t b, CFTypeRef c);
extern CFDictionaryRef IOReportCreateSamples(IOReportSubscriptionRef sub, CFMutableDictionaryRef subscribed, CFTypeRef a);
extern CFStringRef IOReportChannelGetChannelName(CFDictionaryRef channel);
extern CFStringRef IOReportChannelGetUnitLabel(CFDictionaryRef channel);
extern int64_t IOReportSimpleGetIntegerValue(CFDictionaryRef channel, int32_t index);
typedef int (^ioreport_iterate_block)(CFDictionaryRef channel);
extern void IOReportIterate(CFDictionaryRef samples, ioreport_iterate_block callback);

static volatile sig_atomic_t stop_signal;
static mach_timebase_info_data_t timebase;

static void on_signal(int sig) {
	stop_signal = sig;
}

static uint64_t ticks_to_ns(uint64_t ticks) {
	return ticks * timebase.numer / timebase.denom;
}

static uint64_t ns_to_ticks(uint64_t ns) {
	return ns * timebase.denom / timebase.numer;
}

static int cfstr(CFStringRef s, char *buf, size_t n) {
	buf[0] = 0;
	return s != NULL && CFStringGetCString(s, buf, (CFIndex)n, kCFStringEncodingUTF8);
}

// ---- GPU energy through IOReport ------------------------------------------------------------------------------------

static IOReportSubscriptionRef gpu_sub;
static CFMutableDictionaryRef gpu_subscribed;
static int64_t gpu_nj_per_unit; // 0 = unknown unit
static char gpu_unit[32];

static int64_t unit_to_nj(const char *unit) {
	if (!strcmp(unit, "nJ")) return 1;
	if (!strcmp(unit, "uJ") || !strcmp(unit, "\xc2\xb5J")) return 1000;
	if (!strcmp(unit, "mJ")) return 1000000;
	if (!strcmp(unit, "J")) return 1000000000;
	return 0;
}

static const char *gpu_open(void) {
	CFDictionaryRef all = IOReportCopyChannelsInGroup(CFSTR("Energy Model"), NULL, 0, 0, 0);
	if (all == NULL) return "IOReport has no \"Energy Model\" group";
	CFArrayRef channels = CFDictionaryGetValue(all, CFSTR("IOReportChannels"));
	if (channels == NULL) {
		CFRelease(all);
		return "IOReport \"Energy Model\" has no channel list";
	}
	CFMutableArrayRef keep = CFArrayCreateMutable(kCFAllocatorDefault, 0, &kCFTypeArrayCallBacks);
	for (CFIndex i = 0; i < CFArrayGetCount(channels); i++) {
		CFDictionaryRef ch = CFArrayGetValueAtIndex(channels, i);
		char name[128];
		if (cfstr(IOReportChannelGetChannelName(ch), name, sizeof name) && !strcmp(name, "GPU Energy")) {
			CFArrayAppendValue(keep, ch);
		}
	}
	if (CFArrayGetCount(keep) != 1) {
		CFRelease(keep);
		CFRelease(all);
		return "IOReport \"Energy Model\" has no single \"GPU Energy\" channel";
	}
	CFMutableDictionaryRef wanted = CFDictionaryCreateMutableCopy(kCFAllocatorDefault, 0, all);
	CFDictionarySetValue(wanted, CFSTR("IOReportChannels"), keep);
	CFRelease(keep);
	CFRelease(all);
	gpu_sub = IOReportCreateSubscription(NULL, wanted, &gpu_subscribed, 0, NULL);
	CFRelease(wanted);
	return gpu_sub == NULL ? "IOReportCreateSubscription failed" : NULL;
}

// Cumulative GPU energy in nJ, or -1.
static int64_t gpu_read(void) {
	if (gpu_sub == NULL) return -1;
	CFDictionaryRef samples = IOReportCreateSamples(gpu_sub, gpu_subscribed, NULL);
	if (samples == NULL) return -1;
	__block int64_t value = -1;
	IOReportIterate(samples, ^int(CFDictionaryRef ch) {
		if (gpu_nj_per_unit == 0) {
			cfstr(IOReportChannelGetUnitLabel(ch), gpu_unit, sizeof gpu_unit);
			gpu_nj_per_unit = unit_to_nj(gpu_unit);
		}
		if (gpu_nj_per_unit > 0) {
			value = IOReportSimpleGetIntegerValue(ch, 0) * gpu_nj_per_unit;
		}
		return 0;
	});
	CFRelease(samples);
	return value;
}

// ---- main -------------------------------------------------------------------------------------------------------------

static void usage(void) {
	fprintf(stderr, "usage: ingot-energy (--pid PID | --seconds S) --out FILE [--interval-ms MS]\n");
	exit(2);
}

int main(int argc, char **argv) {
	pid_t pid = 0;
	double seconds = 0;
	long interval_ms = 100;
	const char *out_path = NULL;
	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--pid") && i + 1 < argc) pid = (pid_t)atol(argv[++i]);
		else if (!strcmp(argv[i], "--seconds") && i + 1 < argc) seconds = atof(argv[++i]);
		else if (!strcmp(argv[i], "--interval-ms") && i + 1 < argc) interval_ms = atol(argv[++i]);
		else if (!strcmp(argv[i], "--out") && i + 1 < argc) out_path = argv[++i];
		else usage();
	}
	if (out_path == NULL || (pid <= 0 && seconds <= 0) || interval_ms < 10 || interval_ms > 10000) usage();

	mach_timebase_info(&timebase);
	signal(SIGTERM, on_signal);
	signal(SIGINT, on_signal);
	signal(SIGHUP, on_signal);
	// Default QoS on purpose: at QOS_CLASS_UTILITY the 100 ms timer was coalesced to about 200 ms. The footer reports what
	// the sampling loop cost (see bench/README.md for measured values).

	FILE *out = fopen(out_path, "w");
	if (out == NULL) {
		fprintf(stderr, "ingot-energy: cannot write %s: %s\n", out_path, strerror(errno));
		return 1;
	}
	static char outbuf[1 << 16];
	setvbuf(out, outbuf, _IOFBF, sizeof outbuf);

	struct rusage_info_v6 ri;
	if (pid > 0 && proc_pid_rusage(pid, RUSAGE_INFO_V6, (rusage_info_t *)&ri) != 0) {
		fprintf(stderr, "ingot-energy: proc_pid_rusage(%d): %s\n", (int)pid, strerror(errno));
		fclose(out);
		return 1;
	}
	const char *gpu_error = gpu_open();
	int64_t first_gpu = gpu_read(); // also resolves the unit label
	if (gpu_error == NULL && first_gpu < 0) gpu_error = "the \"GPU Energy\" channel has no readable value or an unknown unit";

	fprintf(out, "# ingot-energy 1\n");
	fprintf(out, "# pid=%d interval_ms=%ld timebase=%u/%u gpu_unit=%s\n", (int)pid, interval_ms, timebase.numer, timebase.denom,
		gpu_unit[0] ? gpu_unit : "-");
	if (gpu_error != NULL) fprintf(out, "# warning: %s\n", gpu_error);
	fprintf(out, "t_ns,cpu_energy_nj,cpu_penergy_nj,instructions,cycles,user_ns,sys_ns,idle_wakeups,interrupt_wakeups,gpu_energy_nj,phys_footprint\n");
	fflush(out);

	uint64_t interval_ticks = ns_to_ticks((uint64_t)interval_ms * 1000000ull);
	uint64_t start = mach_absolute_time();
	uint64_t end = seconds > 0 ? start + ns_to_ticks((uint64_t)(seconds * 1e9)) : UINT64_MAX;
	uint64_t next = start;
	long samples = 0;
	uint64_t loop_cpu_ns = 0, loop_energy_nj = 0; // the sampler's own CPU time and energy after setup
	struct rusage_info_v6 self;
	const char *reason = "done";
	for (;;) {
		if (stop_signal) {
			reason = "signal";
			break;
		}
		uint64_t t0 = mach_absolute_time();
		int have_proc = 0;
		if (pid > 0) {
			if (proc_pid_rusage(pid, RUSAGE_INFO_V6, (rusage_info_t *)&ri) != 0) {
				reason = "process exited";
				break;
			}
			have_proc = 1;
		}
		int64_t gpu = gpu_read();
		uint64_t t1 = mach_absolute_time();
		uint64_t t_ns = ticks_to_ns(t0 + (t1 - t0) / 2);
		if (have_proc) {
			fprintf(out, "%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,", (unsigned long long)t_ns,
				(unsigned long long)ri.ri_energy_nj, (unsigned long long)ri.ri_penergy_nj,
				(unsigned long long)ri.ri_instructions, (unsigned long long)ri.ri_cycles,
				(unsigned long long)ticks_to_ns(ri.ri_user_time), (unsigned long long)ticks_to_ns(ri.ri_system_time),
				(unsigned long long)ri.ri_pkg_idle_wkups, (unsigned long long)ri.ri_interrupt_wkups);
		} else {
			fprintf(out, "%llu,,,,,,,,,", (unsigned long long)t_ns);
		}
		if (gpu >= 0) fprintf(out, "%lld", (long long)gpu);
		if (have_proc) fprintf(out, ",%llu\n", (unsigned long long)ri.ri_phys_footprint);
		else fputs(",\n", out);
		samples++;
		if (samples == 1 && proc_pid_rusage(getpid(), RUSAGE_INFO_V6, (rusage_info_t *)&self) == 0) {
			loop_cpu_ns = ticks_to_ns(self.ri_user_time) + ticks_to_ns(self.ri_system_time);
			loop_energy_nj = self.ri_energy_nj;
		}
		if (samples % 10 == 0) fflush(out);
		if (t1 >= end) break;
		next += interval_ticks;
		uint64_t now = mach_absolute_time();
		if (next <= now) next = now + interval_ticks / 2; // fell behind (machine busy): do not burst to catch up
		if (next > end) next = end;
		mach_wait_until(next);
	}

	// Footer: the sampler's own cost. self_* covers everything including setup (reading the 278-channel list once);
	// loop_* only the sampling after the first row, which is what runs while the game is measured.
	if (proc_pid_rusage(getpid(), RUSAGE_INFO_V6, (rusage_info_t *)&self) == 0) {
		uint64_t cpu_ns = ticks_to_ns(self.ri_user_time) + ticks_to_ns(self.ri_system_time);
		double wall = (double)ticks_to_ns(mach_absolute_time() - start) / 1e9;
		fprintf(out, "# end reason=\"%s\" samples=%ld wall_s=%.3f self_cpu_ns=%llu self_energy_nj=%llu loop_cpu_ns=%llu loop_energy_nj=%llu\n",
			reason, samples, wall, (unsigned long long)cpu_ns, (unsigned long long)self.ri_energy_nj,
			(unsigned long long)(samples > 0 ? cpu_ns - loop_cpu_ns : 0),
			(unsigned long long)(samples > 0 ? self.ri_energy_nj - loop_energy_nj : 0));
	}
	fclose(out);
	return 0;
}
