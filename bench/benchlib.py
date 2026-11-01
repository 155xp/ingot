#!/usr/bin/env python3
"""Helpers for bench/run.sh: run directory setup, result collection, world template storage."""

import argparse
import bisect
import glob
import json
import os
import platform
import re
import shlex
import shutil
import signal
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone

BENCH = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(BENCH)
RESULTS_RAW = os.path.join(BENCH, "results", "raw")
WORLD_NAME = "bench"
RESULT_SCHEMA = 4  # keep in sync with dev.ingot.bench.Bench.RESULT_SCHEMA
ENERGY_BIN = os.path.join(BENCH, "power", "build", "ingot-energy")  # built by run.sh from bench/power/ingot-energy.c


def load_json(path):
    with open(path) as fh:
        return json.load(fh)


def configs():
    data = load_json(os.path.join(BENCH, "configs.json"))
    return {k: v for k, v in data.items() if not k.startswith("_")}


def config(name):
    all_configs = configs()
    if name not in all_configs:
        raise SystemExit(f"error: unknown config '{name}' (have: {', '.join(all_configs)})")
    return all_configs[name]


def scenario_path(name):
    path = os.path.join(BENCH, "scenarios", name + ".json")
    if not os.path.isfile(path):
        raise SystemExit(f"error: no scenario file {os.path.relpath(path, ROOT)}")
    return path


def world_template(name):
    return os.path.join(BENCH, "worlds", name)


def variant_name(scenario, fps_cap):
    """Folder name under bench/results/raw: the scenario, plus -cap<N> for a capped run."""
    return f"{scenario}-cap{fps_cap}" if fps_cap else scenario


def write_options(path, scenario, overrides):
    lines = []
    with open(os.path.join(BENCH, "options.base.txt")) as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line and ":" in line:
                lines.append(line)
    values = {}
    order = []
    for line in lines:
        key, value = line.split(":", 1)
        if key not in values:
            order.append(key)
        values[key] = value
    values["renderDistance"] = str(scenario["renderDistance"])
    values["simulationDistance"] = str(scenario.get("simulationDistance", 12))
    # The base options are the Fancy preset, and Minecraft applies a preset's own values after reading options.txt:
    # distances other than Fancy's (16 / 12) only stick with the Custom preset.
    if (values["renderDistance"], values["simulationDistance"]) != ("16", "12"):
        values["graphicsPreset"] = '"custom"'
    for key, value in (overrides or {}).items():
        if key not in values:
            order.append(key)
        values[key] = str(value)
    with open(path, "w") as fh:
        for key in order:
            fh.write(f"{key}:{values[key]}\n")


def ingot_props(cfg):
    """Ingot settings for a run, as system properties (they override config/ingot.properties).

    By default two parts of Ingot's power policy are switched off: Auto power mode would switch to Battery mode (frame
    cap, efficiency cores) on battery or in Low Power Mode, so every configuration runs in Performance mode at full
    resolution; and the occlusion throttle drops to 10 FPS while the window is covered. Vanilla and the mainstream mods
    do neither, so leaving them on would measure the policy instead of the renderer. A configuration measures a power
    mode on purpose through "ingotProps" (keys without the "ingot." prefix, e.g. "powerMode": "battery"); compare such
    configurations at an --fps-cap equal to their frame cap, otherwise the cap marks the frames throttled. Ingot's
    replacement frame limiter (it sleeps until the frame deadline where vanilla parks and then spins) has no switch and
    stays on: it is part of the product, and with --fps-cap it is what paces Ingot's frames. With ingot.enabled=false
    none of this applies.
    """
    props = {
        "ingot.enabled": "true" if cfg["ingot"] else "false",
        "ingot.debugLabels": "false",
        "ingot.powerMode": "performance",
        "ingot.performanceFps": "-1",
        "ingot.performanceResolution": "full",
        "ingot.throttleWhenOccluded": "false",
    }
    for key, value in cfg.get("ingotProps", {}).items():
        props["ingot." + key] = str(value)
    return props


def mod_jars(set_name):
    out = subprocess.run([sys.executable, os.path.join(BENCH, "fetch_mods.py"), "--paths", set_name],
                         check=True, capture_output=True, text=True)
    sys.stderr.write(out.stderr)
    return [line for line in out.stdout.splitlines() if line.strip()]


def cmd_setup(args):
    cfg = config(args.config) if args.mode == "run" else {"ingot": False, "mods": [], "options": {}}
    scen_file = scenario_path(args.scenario)
    scenario = load_json(scen_file)
    rundir = os.path.abspath(args.rundir)
    if os.path.exists(rundir):
        shutil.rmtree(rundir)
    os.makedirs(os.path.join(rundir, "mods"))
    os.makedirs(os.path.join(rundir, "config"))
    os.makedirs(os.path.join(rundir, "saves"))

    options = dict(cfg.get("options") or {})
    if args.fps_cap:
        # Capped variant: the vanilla Max Framerate option, vsync off, so the frame limiter alone paces every config.
        options.update({"maxFps": args.fps_cap, "enableVsync": "false"})
    write_options(os.path.join(rundir, "options.txt"), scenario, options)
    shutil.copyfile(scen_file, os.path.join(rundir, "bench-scenario.json"))
    # Ingot's own config file. The ingot.* system properties below override it; keep the file consistent anyway.
    with open(os.path.join(rundir, "config", "ingot.properties"), "w") as fh:
        for key, value in ingot_props(cfg).items():
            fh.write(f"{key[len('ingot.'):]}={value}\n")

    for set_name in cfg.get("mods", []):
        for jar in mod_jars(set_name):
            shutil.copy2(jar, os.path.join(rundir, "mods", os.path.basename(jar)))

    overrides = os.path.join(BENCH, "config-overrides", args.config)
    if args.mode == "run" and os.path.isdir(overrides):
        shutil.copytree(overrides, os.path.join(rundir, "config"), dirs_exist_ok=True)

    if args.mode == "run":
        template = world_template(args.scenario)
        if not os.path.isfile(os.path.join(template, "level.dat")):
            raise SystemExit(f"error: no world template for scenario '{args.scenario}'; run: bench/run.sh prepare --scenario {args.scenario}")
        shutil.copytree(template, os.path.join(rundir, "saves", WORLD_NAME))

    props = {
        **ingot_props(cfg),
        "ingot.bench.scenario": scenario["name"],
        "ingot.bench.scenarioFile": os.path.join(rundir, "bench-scenario.json"),
        "ingot.bench.mode": args.mode,
        "ingot.bench.config": args.config,
        "ingot.bench.world": WORLD_NAME,
        "ingot.bench.out": os.path.join(rundir, "bench-result.json"),
    }
    if args.duration:
        props["ingot.bench.duration"] = args.duration
    if args.warmup:
        props["ingot.bench.warmup"] = args.warmup
    if args.survey:
        props["ingot.bench.survey"] = "true"
    if args.fps_cap:
        props["ingot.bench.fpsCap"] = args.fps_cap
    if cfg.get("jvmArgs"):
        props["jvm.args"] = cfg["jvmArgs"]
    if cfg.get("expectBackend"):
        props["ingot.bench.expectBackend"] = cfg["expectBackend"]
    if cfg.get("rejectBackend"):
        props["ingot.bench.rejectBackend"] = cfg["rejectBackend"]
    if args.width and args.height:
        props["ingot.bench.windowWidth"] = args.width
        props["ingot.bench.windowHeight"] = args.height
    with open(os.path.join(rundir, "bench.properties"), "w") as fh:
        for key in sorted(props):
            value = str(props[key]).replace("\\", "\\\\")
            fh.write(f"{key}={value}\n")
    print(rundir)


def sh(*cmd):
    try:
        return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def host_info():
    power = sh("pmset", "-g", "batt") or ""
    low_power = sh("pmset", "-g") or ""
    therm = sh("pmset", "-g", "therm") or ""
    mem = re.search(r"memory free percentage: (\d+)%", sh("memory_pressure", "-Q") or "")
    return {
        "cpu": sh("sysctl", "-n", "machdep.cpu.brand_string"),
        "model": sh("sysctl", "-n", "hw.model"),
        "memoryGB": round(int(sh("sysctl", "-n", "hw.memsize") or 0) / 2 ** 30),
        "macos": sh("sw_vers", "-productVersion"),
        "macosBuild": sh("sw_vers", "-buildVersion"),
        "machine": platform.machine(),
        "onBattery": "Battery Power" in power,
        "lowPowerMode": any(line.split()[-1:] == ["1"] for line in low_power.splitlines() if "lowpowermode" in line),
        "thermalWarning": next((line.strip() for line in therm.splitlines() if "level" in line and "No " not in line), None),
        "memoryFreePercent": int(mem.group(1)) if mem else None,
        # Energy Mode: 0 automatic, 1 low power, 2 high power (System Settings > Battery).
        "powerMode": next((int(line.split()[-1]) for line in low_power.splitlines()
                           if line.split()[:1] == ["powermode"] and line.split()[-1].isdigit()), None),
        "loadAverage": [round(x, 2) for x in os.getloadavg()],
    }


# ---- background sampler -----------------------------------------------------------------------------------------
# Runs beside the game (bench/run.sh starts it). It takes one snapshot of every process's cumulative CPU time (ps) and
# GPU time (the AGX driver's per-client "accumulatedGPUTime" in ioreg; no root needed) when the harness enters the
# settle phase right before the measurement (it writes <rundir>/bench-phase), and one when the result is written.
# Nothing is sampled while frames are timed: an ioreg query of the GPU driver once per second measurably slowed the
# frames that followed it. The difference says who else used the CPU and GPU around the measurement; the harness
# itself measures the background CPU load inside the measured window (counters.otherCpuCores), and that number
# decides whether a run counts as disturbed.

GPU_CLIENT_RE = re.compile(r'"IOUserClientCreator" = "pid (\d+), ([^"]*)"')
GPU_TIME_RE = re.compile(r'"accumulatedGPUTime"=(\d+)')


def parse_ps_time(text):
    days = 0
    if "-" in text:
        d, text = text.split("-", 1)
        days = int(d)
    secs = 0.0
    for part in text.split(":"):
        secs = secs * 60 + float(part)
    return days * 86400 + secs


def cpu_snapshot(names):
    """{pid: cumulative CPU seconds}; process names go into `names`."""
    out = subprocess.run(["ps", "-A", "-o", "pid=,time=,comm="], capture_output=True, text=True).stdout
    procs = {}
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) < 2:
            continue
        try:
            pid = int(parts[0])
            procs[pid] = parse_ps_time(parts[1])
        except ValueError:
            continue
        if pid not in names:
            names[pid] = os.path.basename(parts[2]) if len(parts) > 2 else "?"
    return procs


def gpu_snapshot(names):
    """{pid: cumulative GPU nanoseconds} over all of the process's GPU clients."""
    try:
        out = subprocess.run(["ioreg", "-r", "-c", "AGXDeviceUserClient", "-w0", "-d", "1"],
                             capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return {}
    per_pid = {}
    for block in out.split("+-o ")[1:]:
        m = GPU_CLIENT_RE.search(block)
        if m:
            pid = int(m.group(1))
            per_pid[pid] = per_pid.get(pid, 0) + sum(int(x) for x in GPU_TIME_RE.findall(block))
            names.setdefault(pid, m.group(2))
    return per_pid


def snapshot(names):
    return {"t": time.time(), "cpu": cpu_snapshot(names), "gpu": gpu_snapshot(names)}


def summarize_background(a, b, names, game):
    dt = b["t"] - a["t"]
    if dt <= 0:
        return {"error": "empty sampling window"}
    # A process's GPU total drops when one of its GPU clients closes; never count that as negative time.
    cpu = {pid: max(0.0, b["cpu"][pid] - a["cpu"][pid]) / dt for pid in set(a["cpu"]) & set(b["cpu"])}
    gpu = {pid: max(0, b["gpu"][pid] - a["gpu"][pid]) / 1e9 / dt for pid in set(a["gpu"]) & set(b["gpu"])}
    others_cpu = {pid: v for pid, v in cpu.items() if pid != game}
    others_gpu = {pid: v for pid, v in gpu.items() if pid != game}
    window_server = [pid for pid, n in names.items() if n == "WindowServer"]

    def top(d, n=5):
        return [{"pid": pid, "name": names.get(pid, "?"), "value": round(v, 3)}
                for pid, v in sorted(d.items(), key=lambda kv: -kv[1])[:n] if v >= 0.005]

    return {
        "note": "from the settle phase before the measurement until the result was written",
        "windowSeconds": round(dt, 2),
        "gameCpuCores": round(cpu.get(game, 0.0), 2),
        "gameGpuBusy": round(gpu.get(game, 0.0), 3),
        "otherCpuCores": round(sum(others_cpu.values()), 2),
        "otherGpuBusy": round(sum(others_gpu.values()), 3),
        "windowServerCpuCores": round(sum(cpu.get(p, 0.0) for p in window_server), 2),
        "windowServerGpuBusy": round(sum(gpu.get(p, 0.0) for p in window_server), 3),
        "topOtherCpu": top(others_cpu),
        "topOtherGpu": top(others_gpu),
    }


def cmd_sample(args):
    rundir = os.path.abspath(args.rundir)
    stop = os.path.join(rundir, ".sampler-stop")
    phase = os.path.join(rundir, "bench-phase")
    result_path = os.path.join(rundir, "bench-result.json")
    out = os.path.join(rundir, "bench-background.json")
    running = [True]
    signal.signal(signal.SIGTERM, lambda *_: running.__setitem__(0, False))
    names = {}
    before = after = None
    deadline = time.time() + args.max_seconds
    while running[0] and not os.path.exists(stop) and time.time() < deadline:
        if before is None and os.path.exists(phase):
            before = snapshot(names)
        if before is not None and os.path.exists(result_path):
            after = snapshot(names)
            break
        time.sleep(0.2)
    try:
        if before is None or after is None:
            raise ValueError("the measurement did not run")
        summary = summarize_background(before, after, names, load_json(result_path).get("pid"))
    except (OSError, ValueError, KeyError) as e:
        summary = {"error": f"no background snapshot: {e}"}
    with open(out, "w") as fh:
        json.dump(summary, fh, indent=2)
        fh.write("\n")


def screen_state():
    """'awake', 'display asleep', 'screen locked' or 'unknown', from CoreGraphics (the harness checks the same)."""
    try:
        import ctypes
        cg = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
        cf = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        cg.CGMainDisplayID.restype = ctypes.c_uint32
        cg.CGDisplayIsAsleep.argtypes = [ctypes.c_uint32]
        cg.CGDisplayIsAsleep.restype = ctypes.c_uint32
        cg.CGSessionCopyCurrentDictionary.restype = ctypes.c_void_p
        cf.CFStringCreateWithCString.restype = ctypes.c_void_p
        cf.CFStringCreateWithCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint32]
        cf.CFDictionaryGetValue.restype = ctypes.c_void_p
        cf.CFDictionaryGetValue.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        cf.CFBooleanGetValue.argtypes = [ctypes.c_void_p]
        cf.CFBooleanGetValue.restype = ctypes.c_bool
        cf.CFRelease.argtypes = [ctypes.c_void_p]
        locked = False
        session = cg.CGSessionCopyCurrentDictionary()
        if session:
            key = cf.CFStringCreateWithCString(None, b"CGSSessionScreenIsLocked", 0x08000100)
            value = cf.CFDictionaryGetValue(session, key)
            locked = bool(value) and cf.CFBooleanGetValue(value)
            cf.CFRelease(key)
            cf.CFRelease(session)
        if locked:
            return "screen locked"
        return "display asleep" if cg.CGDisplayIsAsleep(cg.CGMainDisplayID()) else "awake"
    except (OSError, AttributeError):
        return "unknown"


def busy_cores(seconds):
    """System-wide busy CPU cores over the next `seconds` (host CPU ticks via iostat, same basis as the harness)."""
    out = subprocess.run(["iostat", "-n", "0", "-c", str(int(seconds) + 1), "-w", "1"],
                         capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3 and all(p.isdigit() for p in parts[:3]):
            rows.append(int(parts[2]))
    idle = rows[1:] or rows  # the first row is the average since boot
    return (100 - sum(idle) / len(idle)) / 100 * os.cpu_count() if idle else 0.0


def top_processes(n=3):
    out = sh("ps", "-A", "-o", "%cpu=,comm=") or ""
    rows = []
    for line in out.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2:
            try:
                rows.append((float(parts[0]), os.path.basename(parts[1])))
            except ValueError:
                pass
    return ", ".join(f"{name} {pct:.0f}%" for pct, name in sorted(rows, reverse=True)[:n])


def cmd_wait_quiet(args):
    """Wait until the rest of the Mac uses at most --max-cores CPU cores (averaged over --window seconds).

    Background load (Photos media analysis, Spotlight, a build in another terminal) changes the CPU and GPU clocks the
    game gets, so a run started during a burst is an outlier. Gives up after --timeout seconds and says so.
    """
    start = time.time()
    reported = False
    while True:
        cores = busy_cores(args.window)
        if cores <= args.max_cores:
            if reported:
                print(f"the machine is quiet now ({cores:.1f} cores busy)", file=sys.stderr)
            return 0
        if time.time() - start >= args.timeout:
            print(f"warning: the rest of the Mac still uses {cores:.1f} CPU cores after {args.timeout:.0f} s "
                  f"({top_processes()}); starting anyway, the run may be marked disturbed", file=sys.stderr)
            return 0
        if not reported:
            print(f"waiting for the machine to calm down: {cores:.1f} CPU cores busy ({top_processes()}); "
                  f"waiting for <= {args.max_cores:g}, at most {args.timeout:.0f} s", file=sys.stderr)
            reported = True


def disturbance(result, limit):
    """Why the background load makes this run an outlier, or None.

    Both tests use the harness's per-second measurement of the CPU the rest of the Mac used (host CPU ticks minus the
    game). In the audit runs a steady 4 to 5 cores of Spotlight and Photos analysis left vanilla within 3% of the other
    runs, but a 4-second jump from 3.7 to 6 cores cut the frame rate in half for those seconds: the frame loop mostly
    waits for the GPU, and the clocks the game gets drop when the load changes. So a run is disturbed when
      - the average is above `limit` cores, or
      - the load jumps: at least 2 seconds (or 5% of the seconds, whichever is more) are more than 1.5 cores above the
        run's own quiet level (its 10th percentile).
    """
    counters = result.get("counters", {})
    mean = counters.get("otherCpuCores", -1)
    if mean > limit:
        return f"the rest of the Mac used {mean:.1f} CPU cores on average during the measurement (limit {limit:g})"
    seconds = sorted(row[4] for row in result.get("timeline", []) if len(row) > 4 and row[4] >= 0)
    if not seconds:
        return None
    quiet = seconds[int(0.1 * (len(seconds) - 1))]
    bursts = [v for v in seconds if v > quiet + 1.5]
    if len(bursts) >= max(2, 0.05 * len(seconds)):
        return (f"background load jumped from {quiet:.1f} to as much as {max(bursts):.1f} CPU cores "
                f"({len(bursts)} of {len(seconds)} seconds more than 1.5 cores above the quiet level)")
    return None


# ---- energy -------------------------------------------------------------------------------------------------------
# bench/power/ingot-energy samples cumulative counters every 100 ms into a CSV (see the comment at the top of
# ingot-energy.c): the game process's CPU energy from proc_pid_rusage and the whole GPU's energy from IOReport. Its
# timestamps are mach_absolute_time in ns, the clock System.nanoTime() reads on macOS, so the harness's measureWindow
# (start and end nanoTime of the measured frames) selects exactly the measured frames. Between two samples a counter
# is interpolated linearly.

def _parse_kv(text):
    out = {}
    for token in shlex.split(text):
        if "=" in token:
            key, value = token.split("=", 1)
            out[key] = value
    return out


def read_energy_csv(path):
    """{"meta", "footer", "warnings", "t": [ns], "<column>": [value or None]} from an ingot-energy CSV."""
    meta, footer, warnings, header, rows = {}, {}, [], None, []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            if line.startswith("#"):
                body = line[1:].strip()
                if body.startswith("warning:"):
                    warnings.append(body[len("warning:"):].strip())
                elif body.startswith("end "):
                    footer = _parse_kv(body[4:])
                else:
                    meta.update(_parse_kv(body))
                continue
            parts = line.split(",")
            if header is None:
                header = parts
                continue
            if len(parts) != len(header):
                continue  # a row cut short when the sampler was killed
            try:
                rows.append([int(p) if p else None for p in parts])
            except ValueError:
                continue
    data = {"meta": meta, "footer": footer, "warnings": warnings}
    for i, name in enumerate(header or []):
        data[name] = [row[i] for row in rows]
    data["t"] = data.pop("t_ns", [])
    return data


def _series(data, *columns):
    """(timestamps, cumulative values) for the rows where all columns are present, summed over the columns. A counter
    that goes backwards (a reset) contributes nothing for that interval; the number of resets is returned too."""
    ts, values, resets = [], [], 0
    total = prev = None
    for i, t in enumerate(data.get("t", [])):
        parts = [data[c][i] for c in columns]
        if any(p is None for p in parts):
            continue
        v = sum(parts)
        if prev is not None:
            if v >= prev:
                total += v - prev
            else:
                resets += 1
        else:
            total = 0
        prev = v
        if ts and t <= ts[-1]:
            continue
        ts.append(t)
        values.append(total)
    return ts, values, resets


def _at(ts, values, t):
    """Cumulative value at time t, linear between the two samples around it; None outside the sampled range."""
    if not ts or t < ts[0] or t > ts[-1]:
        return None
    i = bisect.bisect_left(ts, t)
    if ts[i] == t:
        return values[i]
    t0, t1 = ts[i - 1], ts[i]
    return values[i - 1] + (values[i] - values[i - 1]) * (t - t0) / (t1 - t0)


def _delta(ts, values, start, end):
    a, b = _at(ts, values, start), _at(ts, values, end)
    return None if a is None or b is None else b - a


def sampler_frame_check(result, sample_times):
    """Whether the sampler slows the frames it lands on.

    Compares the frames during which a sample was taken (and the frame after) with the frames half an interval later.
    Both sets are picked by a point in time, so both favour long frames equally; only an effect of the sampling itself
    makes the ratio differ from 1.
    """
    frame_ms = result.get("frameTimesMs")
    win = result.get("measureWindow") or {}
    start = win.get("startNanoTime")
    if not frame_ms or start is None or len(sample_times) < 20:
        return None
    bounds = [start]
    for ms in frame_ms:
        bounds.append(bounds[-1] + ms * 1e6)
    half = statistics.median(b - a for a, b in zip(sample_times, sample_times[1:])) / 2

    def frames_at(times):
        picked = []
        for t in times:
            i = bisect.bisect_right(bounds, t) - 1
            if 0 <= i < len(frame_ms):
                picked.append(frame_ms[i])
                if i + 1 < len(frame_ms):
                    picked.append(frame_ms[i + 1])
        return picked

    inside = [t for t in sample_times if bounds[0] <= t < bounds[-1] - half]
    near, placebo = frames_at(inside), frames_at([t + half for t in inside])
    if len(near) < 20 or len(placebo) < 20:
        return None
    return {"samples": len(inside), "meanMsAtSample": round(statistics.mean(near), 3),
            "meanMsHalfIntervalLater": round(statistics.mean(placebo), 3),
            "ratio": round(statistics.mean(near) / statistics.mean(placebo), 3)}


def energy_summary(result, csv_path, idle=None):
    """Energy over exactly the measured frames (result["measureWindow"]) from an ingot-energy CSV."""
    win = result.get("measureWindow") or {}
    start, end, frames = win.get("startNanoTime"), win.get("endNanoTime"), win.get("frames")
    if start is None or end is None or not frames or end <= start:
        return {"error": "the result has no measureWindow"}
    try:
        data = read_energy_csv(csv_path)
    except OSError as e:
        return {"error": f"no energy samples ({e.strerror})"}
    pid = data["meta"].get("pid")
    if result.get("pid") is not None and pid is not None and str(result["pid"]) != pid:
        return {"error": f"the sampler watched pid {pid} but the game was pid {result['pid']}"}
    seconds = (end - start) / 1e9
    ts = data.get("t", [])
    out = {"windowSeconds": round(seconds, 3), "frames": frames}
    errors, warnings = [], list(data["warnings"])

    # Physical footprint (what Activity Monitor shows as Memory) over the measurement and over the whole run.
    foot = [(t, v) for t, v in zip(ts, data.get("phys_footprint", [])) if v is not None]
    if foot:
        inside = [v for t, v in foot if start <= t <= end] or [v for _, v in foot]
        out["memoryMB"] = {"mean": round(sum(inside) / len(inside) / 2**20), "peakMeasured": round(max(inside) / 2**20),
                           "peakRun": round(max(v for _, v in foot) / 2**20)}

    cpu_ts, cpu, cpu_resets = _series(data, "cpu_energy_nj")
    cpu_j = _delta(cpu_ts, cpu, start, end)
    gpu_ts, gpu, gpu_resets = _series(data, "gpu_energy_nj")
    gpu_j = _delta(gpu_ts, gpu, start, end)
    if cpu_j is None:
        errors.append("the CPU energy samples do not cover the measurement")
    if gpu_j is None:
        errors.append("the GPU energy samples do not cover the measurement")
    if cpu_resets or gpu_resets:
        warnings.append(f"energy counter went backwards ({cpu_resets} CPU, {gpu_resets} GPU); those intervals count as 0")
    # Sampling quality inside the window (including the samples right before and after it).
    lo = max(0, bisect.bisect_right(ts, start) - 1)
    hi = min(len(ts), bisect.bisect_left(ts, end) + 1)
    gaps = [(b - a) / 1e6 for a, b in zip(ts[lo:hi], ts[lo + 1:hi])]
    if gaps:
        out["samples"] = hi - lo
        out["sampleIntervalMs"] = round(statistics.median(gaps), 1)
        out["maxGapMs"] = round(max(gaps), 1)
        if max(gaps) > 1000:
            warnings.append(f"the energy sampler paused for {max(gaps) / 1000:.1f} s during the measurement")
    if errors:
        out["error"] = "; ".join(errors)
        out["warnings"] = warnings
        return out

    cpu_j /= 1e9
    gpu_j /= 1e9
    out.update({
        "cpuJ": round(cpu_j, 3),
        "gpuJ": round(gpu_j, 3),
        "cpuW": round(cpu_j / seconds, 3),
        "gpuW": round(gpu_j / seconds, 3),
        "totalW": round((cpu_j + gpu_j) / seconds, 3),
        "mJPerFrame": round(1000 * (cpu_j + gpu_j) / frames, 3),
        "cpuMJPerFrame": round(1000 * cpu_j / frames, 3),
        "gpuMJPerFrame": round(1000 * gpu_j / frames, 3),
    })
    p_ts, p_values, _ = _series(data, "cpu_penergy_nj")
    p_j = _delta(p_ts, p_values, start, end)
    if p_j is not None and cpu_j > 0:
        out["cpuPCoreShare"] = round(p_j / 1e9 / cpu_j, 3)
    t_ts, t_values, _ = _series(data, "user_ns", "sys_ns")
    cpu_s = _delta(t_ts, t_values, start, end)
    if cpu_s is not None:
        out["cpuCores"] = round(cpu_s / 1e9 / seconds, 3)
        # Cross-check of the clocks: the game measured its own CPU time over the same window (getProcessCpuTime).
        own = result.get("counters", {}).get("processCpuCores", -1)
        if own and own > 0:
            out["cpuCoresVsGame"] = round(out["cpuCores"] / own, 3)
            if abs(out["cpuCoresVsGame"] - 1) > 0.05:
                warnings.append(f"the sampler saw {out['cpuCores']:.2f} CPU cores in the window, the game {own:.2f}; "
                                "the window may not line up with the samples")
    i_ts, i_values, _ = _series(data, "instructions")
    c_ts, c_values, _ = _series(data, "cycles")
    instructions, cycles = _delta(i_ts, i_values, start, end), _delta(c_ts, c_values, start, end)
    if instructions is not None:
        out["instructionsPerFrame"] = round(instructions / frames)
        if cycles:
            out["instructionsPerCycle"] = round(instructions / cycles, 3)
    w_ts, w_values, _ = _series(data, "idle_wakeups", "interrupt_wakeups")
    wakeups = _delta(w_ts, w_values, start, end)
    if wakeups is not None:
        out["wakeupsPerSecond"] = round(wakeups / seconds)
    if idle and idle.get("gpuW") is not None:
        out["idleGpuW"] = idle["gpuW"]
        out["gpuOverIdleW"] = round(out["gpuW"] - idle["gpuW"], 3)
        out["idleBaseline"] = idle
        if idle.get("warning"):
            warnings.append(f"idle baseline: {idle['warning']}")

    # Per second, on the same buckets as result["timeline"]: [start s, game CPU W, GPU W].
    rows = result.get("timeline", [])
    marks = [row[0] for row in rows] + [seconds]
    timeline = []
    for a, b in zip(marks, marks[1:]):
        ta, tb = start + a * 1e9, start + b * 1e9
        dc, dg = _delta(cpu_ts, cpu, ta, tb), _delta(gpu_ts, gpu, ta, tb)
        if b > a and dc is not None and dg is not None:
            timeline.append([a, round(dc / 1e9 / (b - a), 2), round(dg / 1e9 / (b - a), 2)])
    out["timeline"] = timeline

    # What the sampler itself cost (its sampling loop, from its own rusage).
    foot = data["footer"]
    try:
        wall = float(foot["wall_s"])
        out["sampler"] = {"samples": int(foot["samples"]), "cpuCoreShare": round(int(foot["loop_cpu_ns"]) / 1e9 / wall, 4),
                          "watts": round(int(foot["loop_energy_nj"]) / 1e9 / wall, 4)}
    except (KeyError, ValueError, ZeroDivisionError):
        pass
    check = sampler_frame_check(result, ts)
    if check:
        out.setdefault("sampler", {})["frameCheck"] = check
        if check["ratio"] > 1.1:
            warnings.append(f"frames during an energy sample took {100 * (check['ratio'] - 1):.0f}% longer than frames half "
                            "an interval later; the sampler may disturb the game")
    if warnings:
        out["warnings"] = warnings
    return out


def cmd_idle_baseline(args):
    """Measure the GPU's power with the game not running (bench/run.sh does this before every batch)."""
    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    csv_path = os.path.splitext(os.path.abspath(args.out))[0] + ".csv"
    state = screen_state()
    # Who used the GPU during the baseline (the driver's per-process GPU time; no game runs, so querying it is harmless).
    # Another game or a video left running would inflate the baseline and shrink "GPU over idle".
    names = {}
    gpu_before, t_before = gpu_snapshot(names), time.time()
    sampler = subprocess.Popen([args.bin, "--seconds", str(args.seconds), "--out", csv_path])
    cores = busy_cores(args.seconds)  # what the rest of the Mac did meanwhile, for context
    rc = sampler.wait()
    gpu_after, t_after = gpu_snapshot(names), time.time()
    busy = {pid: max(0, gpu_after[pid] - gpu_before[pid]) / 1e9 / (t_after - t_before)
            for pid in set(gpu_before) & set(gpu_after)}
    baseline = {"measuredAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "seconds": args.seconds,
                "screen": state, "busyCpuCores": round(cores, 2),
                "gpuBusy": round(sum(busy.values()), 3),
                "topGpu": [{"pid": pid, "name": names.get(pid, "?"), "value": round(v, 3)}
                           for pid, v in sorted(busy.items(), key=lambda kv: -kv[1])[:5] if v >= 0.005]}
    others = [p for p in baseline["topGpu"] if p["name"] != "WindowServer" and p["value"] >= 0.05]
    if others:
        baseline["warning"] = ("other processes used the GPU during the idle baseline ("
                               + ", ".join(f"{p['name']} {100 * p['value']:.0f}%" for p in others) + ")")
        print(f"warning: {baseline['warning']}; the baseline is too high", file=sys.stderr)
    try:
        data = read_energy_csv(csv_path)
        ts, gpu, _ = _series(data, "gpu_energy_nj")
        if rc != 0 or len(ts) < 2:
            raise ValueError("; ".join(data["warnings"]) or f"ingot-energy exited with {rc}")
        baseline["gpuW"] = round((gpu[-1] - gpu[0]) / 1e9 / ((ts[-1] - ts[0]) / 1e9), 3)
        baseline["samples"] = len(ts)
    except (OSError, ValueError) as e:
        baseline["error"] = f"no idle baseline: {e}"
    with open(args.out, "w") as fh:
        json.dump(baseline, fh, indent=2)
        fh.write("\n")
    if "error" in baseline:
        print(f"warning: {baseline['error']}", file=sys.stderr)
        return 0
    top = ", ".join(f"{p['name']} {100 * p['value']:.0f}%" for p in baseline["topGpu"][:3]) or "nothing"
    print(f"idle GPU baseline: {baseline['gpuW']:.3f} W over {args.seconds:g} s with the game not running "
          f"({'display awake' if state == 'awake' else state}, {cores:.1f} CPU cores busy elsewhere, GPU busy: {top})")
    return 0


def git_info():
    rev = sh("git", "-C", ROOT, "rev-parse", "--short", "HEAD")
    dirty = sh("git", "-C", ROOT, "status", "--porcelain", "--", "src", "native", "build.gradle")
    return {"rev": rev, "dirty": bool(dirty)}


def cmd_finalize(args):
    rundir = os.path.abspath(args.rundir)
    result_path = os.path.join(rundir, "bench-result.json")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.path.join(RESULTS_RAW, variant_name(args.scenario, args.fps_cap), args.config)
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, stamp)

    if os.path.isfile(result_path):
        result = load_json(result_path)
    else:
        crash = sorted(glob.glob(os.path.join(rundir, "crash-reports", "*.txt")))
        gradle_log = os.path.join(rundir, "gradle-run.log")
        try:
            with open(gradle_log, errors="replace") as fh:
                gradle_text = fh.read()
        except OSError:
            gradle_text = ""
        if "> Task :runBench" not in gradle_text and ("BUILD FAILED" in gradle_text or "FAILURE:" in gradle_text):
            error = (f"the Gradle build failed before the game started (see {os.path.relpath(gradle_log, ROOT)}); if src/main "
                     "does not compile right now, vanilla and mainstream can run with --without-ingot")
        else:
            error = "the game exited without writing a result" + (f"; crash report: {crash[-1]}" if crash else
                                                                  f"; see {os.path.relpath(os.path.join(rundir, 'logs', 'latest.log'), ROOT)}")
        result = {
            "schema": RESULT_SCHEMA, "config": args.config, "scenario": args.scenario, "fpsCap": args.fps_cap or 0,
            "valid": False, "failed": True, "error": error, "timestamp": datetime.now(timezone.utc).isoformat(),
        }
    result["exitCode"] = args.rc
    result["host"] = host_info()
    result["git"] = git_info()
    result["configDefinition"] = config(args.config)
    lock_path = os.path.join(BENCH, "mods.lock.json")
    if os.path.isfile(lock_path):
        lock = load_json(lock_path)
        result["modsLock"] = {s: [f"{m}@{lock['mods'][m]['versionNumber']}" for m in lock["sets"][s]]
                              for s in result["configDefinition"].get("mods", [])}
    if not result.get("failed") and not any(m.get("id") == "ingot" for m in result.get("mods", [])):
        result.setdefault("warnings", []).append("Ingot was not on the classpath (--without-ingot)")
    background_path = os.path.join(rundir, "bench-background.json")
    if os.path.isfile(background_path) and not result.get("failed"):
        result["background"] = load_json(background_path)
    if not result.get("failed"):
        bg = result.get("background", {})
        names = ", ".join(f"{p['name']} {p['value']:.1f}" for p in bg.get("topOtherCpu", [])[:3]) or "no attribution"
        reason = disturbance(result, args.max_background)
        result["maxBackgroundCores"] = args.max_background
        if reason:
            # Not invalid (nothing about the game was wrong), but not comparable either: report.py leaves it out.
            result["disturbed"] = True
            result.setdefault("warnings", []).append(f"disturbed: {reason} (top: {names})")
        if bg.get("otherGpuBusy", 0) - bg.get("windowServerGpuBusy", 0) >= 0.05:
            names = ", ".join(f"{p['name']} {100 * p['value']:.0f}%" for p in bg.get("topOtherGpu", [])[:3])
            result.setdefault("warnings", []).append(
                f"other processes kept the GPU {100 * bg['otherGpuBusy']:.0f}% busy during the measurement ({names})")
    energy_csv = os.path.join(rundir, "energy.csv")
    if not result.get("failed"):
        idle = None
        if args.idle and os.path.isfile(args.idle):
            idle = load_json(args.idle)
        if os.path.isfile(energy_csv):
            result["energy"] = energy_summary(result, energy_csv, idle)
        else:
            result["energy"] = {"error": "not measured (run.sh --no-energy, or the sampler did not start)"}
            if idle:
                result["energy"]["idleBaseline"] = idle
        if result["energy"].get("error"):
            result.setdefault("warnings", []).append(f"energy: {result['energy']['error']}")
        for w in result["energy"].get("warnings", []):
            result.setdefault("warnings", []).append(f"energy: {w}")
    if result.get("host", {}).get("onBattery"):
        result.setdefault("warnings", []).append("ran on battery power")
    if result.get("host", {}).get("lowPowerMode"):
        result.setdefault("warnings", []).append("Low Power Mode was on")

    with open(base + ".json", "w") as fh:
        json.dump(result, fh, indent=2)
        fh.write("\n")
    for name, src in (("log", os.path.join(rundir, "logs", "latest.log")), ("gradle.log", os.path.join(rundir, "gradle-run.log")),
                      ("energy.csv", energy_csv)):
        if os.path.isfile(src):
            shutil.copyfile(src, f"{base}.{name}")
    crash = sorted(glob.glob(os.path.join(rundir, "crash-reports", "*.txt")))
    if crash:
        shutil.copyfile(crash[-1], base + ".crash.txt")

    rel = os.path.relpath(base + ".json", ROOT)
    if result.get("failed"):
        print(f"FAILED  {args.config}: {result.get('error')}  ({rel})")
        return 1
    s = result["stats"]
    r = result.get("renderer", {})
    d = result.get("display", {})
    c = result.get("counters", {})
    bg = result.get("background", {})
    status = ("ok     " if not result.get("disturbed") else "BUSY   ") if result.get("valid") else "INVALID"
    print(f"{status} {args.config:<11} avg {s['avgFps']:8.1f} fps | 1% low {s['onePercentLowFps']:7.1f} | 0.1% low "
          f"{s['pointOnePercentLowFps']:7.1f} | p50 {s['p50FrameMs']:6.2f} ms | p99 {s['p99FrameMs']:6.2f} ms | "
          f"{s['frames']} frames | {r.get('backend')} | {d.get('framebufferWidth')}x{d.get('framebufferHeight')}  ({rel})")
    print(f"        render thread busy {c.get('renderThreadBusy', -1):.2f} | game CPU {c.get('processCpuCores', -1):.1f} cores, "
          f"GPU {100 * bg.get('gameGpuBusy', 0):.0f}% | rest of the Mac: CPU {c.get('otherCpuCores', -1):.1f} cores "
          f"(top: {', '.join(p['name'] for p in bg.get('topOtherCpu', [])[:3]) or '-'}), GPU {100 * bg.get('otherGpuBusy', 0):.0f}% | "
          f"JIT {c.get('jitMsDuringMeasurement', -1)} ms")
    e = result.get("energy", {})
    if "mJPerFrame" in e:
        idle = f" (idle {e['idleGpuW']:.2f} W)" if "idleGpuW" in e else ""
        sampler = e.get("sampler", {})
        print(f"        energy: game CPU {e['cpuW']:.2f} W + GPU {e['gpuW']:.2f} W{idle} = {e['totalW']:.2f} W | "
              f"{e['mJPerFrame']:.2f} mJ/frame | CPU {e['cpuJ']:.1f} J, GPU {e['gpuJ']:.1f} J over {e['windowSeconds']:.0f} s | "
              f"sampler {100 * sampler.get('cpuCoreShare', 0):.2f}% of a core, {1000 * sampler.get('watts', 0):.1f} mW")
    for reason in result.get("invalidReasons", []):
        print(f"        invalid: {reason}")
    for warning in result.get("warnings", []):
        print(f"        warning: {warning}")
    if not result.get("valid"):
        return 2
    return 3 if result.get("disturbed") else 0


def cmd_store_world(args):
    rundir = os.path.abspath(args.rundir)
    src = os.path.join(rundir, "saves", WORLD_NAME)
    meta = os.path.join(src, "ingot-bench-world.json")
    if not os.path.isfile(os.path.join(src, "level.dat")) or not os.path.isfile(meta):
        raise SystemExit("error: prepare did not produce a finished world (see " + os.path.relpath(os.path.join(rundir, "logs", "latest.log"), ROOT) + ")")
    dst = world_template(args.scenario)
    if os.path.exists(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst, ignore=shutil.ignore_patterns("session.lock"))
    info = load_json(meta)
    size = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(dst) for f in fs)
    print(f"stored world template {os.path.relpath(dst, ROOT)} ({size / 2 ** 20:.0f} MB, {info['region']['chunks']} chunks)")
    comp = info.get("composition", {})
    if comp:
        print("  surface mix within render distance: " + ", ".join(f"{k} {v}%" for k, v in comp.get("categories", {}).items()))
        print(f"  surface height {comp.get('surfaceMin')}..{comp.get('surfaceMax')} (mean {comp.get('surfaceMean', 0):.0f}); "
              f"camera feet at y={info['start']['y']}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("setup")
    s.add_argument("--config", required=True)
    s.add_argument("--scenario", required=True)
    s.add_argument("--rundir", required=True)
    s.add_argument("--mode", choices=["run", "prepare"], default="run")
    s.add_argument("--duration")
    s.add_argument("--warmup")
    s.add_argument("--survey", action="store_true")
    s.add_argument("--width")
    s.add_argument("--height")
    s.add_argument("--fps-cap", type=int, default=0)
    sm = sub.add_parser("sample")
    sm.add_argument("--rundir", required=True)
    sm.add_argument("--max-seconds", type=float, default=7200)
    f = sub.add_parser("finalize")
    f.add_argument("--config", required=True)
    f.add_argument("--scenario", required=True)
    f.add_argument("--rundir", required=True)
    f.add_argument("--rc", type=int, default=0)
    f.add_argument("--max-background", type=float, default=5.0)
    f.add_argument("--fps-cap", type=int, default=0)
    f.add_argument("--idle", help="idle GPU baseline JSON of this batch (benchlib.py idle-baseline)")
    q = sub.add_parser("wait-quiet")
    q.add_argument("--max-cores", type=float, default=5.0)
    q.add_argument("--window", type=float, default=5.0)
    q.add_argument("--timeout", type=float, default=180.0)
    w = sub.add_parser("store-world")
    w.add_argument("--scenario", required=True)
    w.add_argument("--rundir", required=True)
    sub.add_parser("screen-state")
    ib = sub.add_parser("idle-baseline")
    ib.add_argument("--out", required=True)
    ib.add_argument("--seconds", type=float, default=10.0)
    ib.add_argument("--bin", default=ENERGY_BIN)
    ee = sub.add_parser("energy", help="recompute the energy summary of a collected result from its .energy.csv")
    ee.add_argument("result")
    c = sub.add_parser("check-config")
    c.add_argument("config")
    args = ap.parse_args()
    if args.cmd == "setup":
        cmd_setup(args)
    elif args.cmd == "finalize":
        sys.exit(cmd_finalize(args))
    elif args.cmd == "store-world":
        cmd_store_world(args)
    elif args.cmd == "check-config":
        config(args.config)
    elif args.cmd == "sample":
        cmd_sample(args)
    elif args.cmd == "screen-state":
        print(screen_state())
    elif args.cmd == "wait-quiet":
        sys.exit(cmd_wait_quiet(args))
    elif args.cmd == "idle-baseline":
        sys.exit(cmd_idle_baseline(args))
    elif args.cmd == "energy":
        result = load_json(args.result)
        summary = energy_summary(result, os.path.splitext(args.result)[0] + ".energy.csv",
                                 result.get("energy", {}).get("idleBaseline"))
        summary.pop("timeline", None)
        print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
