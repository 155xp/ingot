#!/usr/bin/env python3
"""Aggregate benchmark results into bench/results/summary.md and bench/results/summary.json.

    bench/report.py                      all valid runs of the newest world template per scenario
    bench/report.py --latest 3           only the 3 most recent valid runs per configuration
    bench/report.py --scenario standard  one scenario only

Every metric is the median over the repeats of a configuration. Runs are grouped by scenario, FPS cap (run.sh
--fps-cap; uncapped and capped runs get separate tables), measured duration, warmup, framebuffer size, render distance
and world template, and only runs from the same group are compared (a re-prepared world is a different template;
Minecraft worldgen is not bit-for-bit reproducible). Each group has a frame-time table and an energy table; energy is
integrated over the measured frames from the run's .energy.csv (recomputed here, so the report always uses the current
integration in benchlib.energy_summary). Runs marked
disturbed (other processes used more CPU than run.sh --max-background allowed while the frames were timed) are left
out unless --include-disturbed is given; --max-background CORES applies a stricter limit afterwards.
"""

import argparse
import glob
import json
import os
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

BENCH = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BENCH)
from benchlib import disturbance, energy_summary  # noqa: E402  (the same code run.sh used when the run was collected)
RAW = os.path.join(BENCH, "results", "raw")
OUT_MD = os.path.join(BENCH, "results", "summary.md")
OUT_JSON = os.path.join(BENCH, "results", "summary.json")

METRICS = [
    ("avgFps", "avg FPS"),
    ("onePercentLowFps", "1% low"),
    ("pointOnePercentLowFps", "0.1% low"),
    ("p50FrameMs", "p50 ms"),
    ("p99FrameMs", "p99 ms"),
]
# result["energy"] keys aggregated (median over the runs that have energy).
ENERGY_METRICS = ["cpuW", "gpuW", "gpuOverIdleW", "totalW", "mJPerFrame", "cpuMJPerFrame", "gpuMJPerFrame", "cpuJ", "gpuJ",
                  "cpuPCoreShare", "cpuCores", "instructionsPerFrame", "wakeupsPerSecond", "idleGpuW"]


def config_order():
    with open(os.path.join(BENCH, "configs.json")) as fh:
        return [k for k in json.load(fh) if not k.startswith("_")]


def load_runs(scenario_filter):
    runs = []
    for path in sorted(glob.glob(os.path.join(RAW, "*", "*", "*.json"))):
        try:
            with open(path) as fh:
                r = json.load(fh)
        except (OSError, json.JSONDecodeError):
            continue
        if scenario_filter and r.get("scenario") != scenario_filter:
            continue
        r["_path"] = os.path.relpath(path, BENCH)
        csv_path = os.path.splitext(path)[0] + ".energy.csv"
        if not r.get("failed") and r.get("measureWindow") and os.path.isfile(csv_path):
            r["energy"] = energy_summary(r, csv_path, r.get("energy", {}).get("idleBaseline"))
        runs.append(r)
    return runs


def fmt(v, digits=1):
    return "-" if v is None else f"{v:.{digits}f}"


def median_or_none(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def pct(a, b):
    return "-" if a is None or not b else f"{100 * (a / b - 1):+.0f}%"


def energy_table(rows, baseline, by_config, fps_cap, group):
    """Markdown lines of a group's energy table (and group["idleGpuW"] etc. for summary.json)."""
    if not any(row["energyRuns"] for row in rows):
        return ["", "Energy: not measured in these runs (run.sh --no-energy, or results from before the energy sampler).", ""]
    # One idle baseline per batch; a batch may have contributed runs of several configurations.
    idle = {}
    for rs in by_config.values():
        for r in rs:
            b = r.get("energy", {}).get("idleBaseline") or {}
            if b.get("gpuW") is not None:
                idle[b.get("measuredAt")] = b
    idle_w = median_or_none(b["gpuW"] for b in idle.values())
    group["idleGpuW"] = idle_w
    group["idleBaselines"] = len(idle)
    lines = ["", "Energy over the measured frames" + (f" (equal {fps_cap} FPS, so W and mJ/frame rank alike):" if fps_cap else
                                                 " (uncapped: a faster config renders more frames, so compare mJ/frame):"), ""]
    if idle_w is not None:
        screens = sorted({"display awake" if b.get("screen") == "awake" else b.get("screen", "screen unknown")
                          for b in idle.values()})
        lines += [f"Idle GPU baseline (10 s with the game not running, before each batch): **{idle_w:.3f} W** "
                  f"(median of {len(idle)} batch{'es' if len(idle) != 1 else ''}; {', '.join(screens)}). "
                  "'GPU over idle' is GPU W minus it: roughly what the game adds.", ""]
        for b in idle.values():
            if b.get("warning"):
                lines += [f"Warning, baseline of {b.get('measuredAt')}: {b['warning']}.", ""]
    lines += ["| config | runs | avg FPS | game CPU W | GPU W | GPU over idle W | total W | mJ/frame | vs vanilla (mJ/frame / total W) "
              "| CPU J | GPU J | P-core share | notes |",
              "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    base = baseline["energy"] if baseline and baseline["energyRuns"] else None
    for row in rows:
        e = row["energy"]
        rel = "-"
        if base and row is not baseline and row["energyRuns"]:
            rel = f"{pct(e['mJPerFrame'], base['mJPerFrame'])} / {pct(e['totalW'], base['totalW'])}"
            row["energyVsVanilla"] = {"mJPerFrame": e["mJPerFrame"] / base["mJPerFrame"], "totalW": e["totalW"] / base["totalW"]}
        share = e.get("cpuPCoreShare")
        lines.append(f"| {row['config']} | {row['energyRuns']} | {fmt(row['avgFps'])} | {fmt(e['cpuW'], 2)} | {fmt(e['gpuW'], 2)} | "
                     f"{fmt(e['gpuOverIdleW'], 2)} | {fmt(e['totalW'], 2)} | {fmt(e['mJPerFrame'], 2)} | {rel} | {fmt(e['cpuJ'])} | "
                     f"{fmt(e['gpuJ'])} | {'-' if share is None else f'{100 * share:.0f}%'} | {'; '.join(row['energyNotes'])} |")
    # The sampler's own cost, so a reader can see it does not change the result.
    runs = [r for rs in by_config.values() for r in rs if r.get("energy", {}).get("sampler")]
    share = median_or_none(r["energy"]["sampler"].get("cpuCoreShare") for r in runs)
    watts = median_or_none(r["energy"]["sampler"].get("watts") for r in runs)
    ratio = median_or_none(r["energy"]["sampler"].get("frameCheck", {}).get("ratio") for r in runs)
    if share is not None:
        lines += ["", f"Energy sampler cost (median): {100 * share:.2f}% of one CPU core, {1000 * (watts or 0):.1f} mW"
                  + (f"; frames during a sample took {100 * (ratio - 1):+.1f}% vs frames half an interval later." if ratio else ".")]
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scenario")
    ap.add_argument("--latest", type=int, default=0, help="use only the N most recent valid runs per configuration")
    ap.add_argument("--include-invalid", action="store_true", help="also aggregate runs marked invalid")
    ap.add_argument("--include-disturbed", action="store_true", help="also aggregate runs marked disturbed (busy machine)")
    ap.add_argument("--max-background", type=float, default=None, metavar="CORES",
                    help="re-screen every run with this background limit instead of the one run.sh used")
    args = ap.parse_args()

    runs = load_runs(args.scenario)
    if not runs:
        raise SystemExit("no results in bench/results/raw yet; run bench/run.sh first")
    # Results written by an older harness lack the background screening and are not comparable; leave them out.
    schema = max(r.get("schema", 1) for r in runs)
    old = [r for r in runs if r.get("schema", 1) < schema]
    runs = [r for r in runs if r.get("schema", 1) == schema]

    # Newest world template per scenario (by the most recent run that used it).
    newest_template = {}
    for r in sorted(runs, key=lambda r: r.get("timestamp", "")):
        if not r.get("failed"):
            newest_template[r["scenario"]] = r.get("world", {}).get("templateId", "unknown")

    groups = defaultdict(lambda: defaultdict(list))
    skipped = Counter()
    if old:
        skipped[f"from an older harness (result schema < {schema})"] += len(old)
    for r in runs:
        if r.get("failed"):
            skipped["failed"] += 1
            continue
        if not r.get("valid") and not args.include_invalid:
            skipped["invalid"] += 1
            continue
        template = r.get("world", {}).get("templateId", "unknown")
        if template != newest_template.get(r["scenario"]):
            skipped["older world template"] += 1
            continue
        limit = args.max_background if args.max_background is not None else r.get("maxBackgroundCores", 5.0)
        if (r.get("disturbed") or disturbance(r, limit)) and not args.include_disturbed:
            skipped["disturbed (busy machine)"] += 1
            continue
        disp = r.get("display", {})
        key = (r["scenario"], r.get("fpsCap", 0), r["durationSeconds"], r.get("warmupSeconds", 0), template,
               f"{disp.get('framebufferWidth')}x{disp.get('framebufferHeight')}", r.get("options", {}).get("renderDistance"))
        groups[key][r["config"]].append(r)

    order = config_order()
    summary = {"generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "skippedRuns": dict(skipped), "groups": []}
    md = ["# Ingot benchmark summary", "",
          f"Generated {summary['generatedAt']} by `bench/report.py`. Each number is the median over the runs of a configuration "
          "(see bench/README.md for how every metric is defined).", "",
          "Energy columns: **game CPU** is the CPU energy of the game process (all its threads, the integrated server too); "
          "**GPU** is the whole GPU, every process (system-wide); **total** is both; the display, DRAM and the rest of "
          "the Mac are not included. **mJ/frame** = (game CPU + GPU energy) / frames. Uncapped and capped (`--fps-cap`) "
          "runs are separate tables.", ""]

    for (scenario, fps_cap, duration, warmup, template, resolution, render_distance), by_config in sorted(
            groups.items(), key=lambda kv: (kv[0][0], kv[0][1], str(kv[0][2:]))):
        configs = sorted(by_config, key=lambda c: (order.index(c) if c in order else len(order), c))
        sample = next(iter(by_config.values()))[0]
        host = sample.get("host", {})
        disp = sample.get("display", {})
        world = sample.get("world", {})
        group = {"scenario": scenario, "fpsCap": fps_cap, "durationSeconds": duration, "warmupSeconds": warmup,
                 "templateId": template, "host": host, "resolution": resolution, "renderDistance": render_distance,
                 "configs": []}
        cap_label = f"capped at {fps_cap} FPS (vsync off)" if fps_cap else "uncapped"
        md += [f"## Scenario `{scenario}`, {cap_label}, {duration:g} s measured ({warmup:g} s warmup)",
               "",
               f"{host.get('cpu')} ({host.get('model')}, {host.get('memoryGB')} GB), macOS {host.get('macos')}; "
               f"framebuffer {group['resolution']}, render distance {group['renderDistance']}, seed {world.get('seed')}, "
               f"world template `{template[:8]}`.",
               "",
               "| config | runs | avg FPS | 1% low | 0.1% low | p50 ms | p99 ms | vs vanilla (avg / 1% low) | backend | notes |",
               "|---|---:|---:|---:|---:|---:|---:|---:|---|---|"]
        baseline = None
        rows = []
        used = {}  # the runs each row aggregates (after --latest)
        for cfg in configs:
            rs = by_config[cfg]
            rs.sort(key=lambda r: r.get("timestamp", ""))
            if args.latest:
                rs = rs[-args.latest:]
            used[cfg] = rs
            med = {key: statistics.median(r["stats"][key] for r in rs) for key, _ in METRICS}
            spread = (min(r["stats"]["avgFps"] for r in rs), max(r["stats"]["avgFps"] for r in rs))
            phases = defaultdict(list)
            for r in rs:
                for p in r.get("phases", []):
                    phases[p["name"]].append(p)
            phase_med = {name: {"avgFps": statistics.median(p["avgFps"] for p in ps),
                                "onePercentLowFps": statistics.median(p["onePercentLowFps"] for p in ps)}
                         for name, ps in phases.items()}
            backend = Counter(r.get("renderer", {}).get("backend", "?") for r in rs).most_common(1)[0][0]
            gpu = Counter(r.get("renderer", {}).get("gpu", "?") for r in rs).most_common(1)[0][0]
            def label(r, m):
                disabled = m["id"] == "ingot" and not r.get("configDefinition", {}).get("ingot", False)
                return f"{m['id']} {m['version']}" + (" (disabled)" if disabled else "")
            top_mods = sorted({label(r, m) for r in rs for m in r.get("mods", [])
                               if "nestedIn" not in m and m["id"] not in ("java", "minecraft", "fabricloader", "mixinextras", "ingot-bench")})
            notes = []
            if len(rs) > 1:
                notes.append(f"avg range {spread[0]:.0f}-{spread[1]:.0f} ({100 * (spread[1] - spread[0]) / med['avgFps']:.0f}%)")
            bg = [r["counters"]["otherCpuCores"] for r in rs if r.get("counters", {}).get("otherCpuCores", -1) >= 0]
            if bg:
                notes.append(f"background {statistics.median(bg):.1f} cores")
            busy = [r for r in rs if r.get("disturbed")]
            if busy:
                notes.append(f"{len(busy)} disturbed run(s) included")
            invalid = sum(1 for r in rs if not r.get("valid"))
            if invalid:
                notes.append(f"{invalid} invalid run(s) included")
            # Per-run background warnings carry numbers that differ every run; they are summarized above instead.
            warn = Counter(w for r in rs for w in r.get("warnings", [])
                           if not w.startswith(("other processes", "disturbed:", "the JIT compiled", "energy:", "did not hold")))
            jit = [r for r in rs if any(w.startswith("the JIT compiled") for w in r.get("warnings", []))]
            if jit:
                notes.append(f"JIT still compiling in {len(jit)} run(s)")
            below_cap = [r for r in rs if any(w.startswith("did not hold") for w in r.get("warnings", []))]
            if below_cap:
                notes.append(f"below the FPS cap in {len(below_cap)} run(s)")
            notes += [f"{w} ({n}x)" for w, n in warn.items()]
            if any(r.get("git", {}).get("dirty") for r in rs):
                notes.append("dirty tree")
            with_energy = [r for r in rs if "mJPerFrame" in r.get("energy", {})]
            energy = {key: median_or_none(r["energy"].get(key) for r in with_energy) for key in ENERGY_METRICS}
            energy_notes = []
            if len(with_energy) < len(rs):
                energy_notes.append(f"no energy in {len(rs) - len(with_energy)} run(s)")
            if len(with_energy) > 1:
                lo, hi = min(r["energy"]["mJPerFrame"] for r in with_energy), max(r["energy"]["mJPerFrame"] for r in with_energy)
                energy_notes.append(f"mJ/frame range {lo:.2f}-{hi:.2f} ({100 * (hi - lo) / energy['mJPerFrame']:.0f}%)")
            energy_warned = [r for r in rs if r.get("energy", {}).get("warnings")]
            if energy_warned:
                energy_notes.append(f"energy warnings in {len(energy_warned)} run(s)")
            if below_cap:
                energy_notes.append(f"below the FPS cap in {len(below_cap)} run(s)")
            limiters = sorted({r["frameLimiter"] for r in rs if r.get("frameLimiter")})
            if fps_cap and limiters:
                energy_notes.append(f"{' / '.join(limiters)} frame limiter")
            row = {"config": cfg, "runs": len(rs), **med, "avgFpsRange": spread, "phases": phase_med,
                   "backend": backend, "gpu": gpu, "mods": top_mods, "notes": notes,
                   "energyRuns": len(with_energy), "energy": energy, "energyNotes": energy_notes,
                   "results": [r["_path"] for r in rs]}
            if cfg == "vanilla":
                baseline = row
            rows.append(row)
        for row in rows:
            rel = "-"
            if baseline and row is not baseline:
                rel = (f"{100 * (row['avgFps'] / baseline['avgFps'] - 1):+.0f}% / "
                       f"{100 * (row['onePercentLowFps'] / baseline['onePercentLowFps'] - 1):+.0f}%")
                row["vsVanilla"] = {"avgFps": row["avgFps"] / baseline["avgFps"], "onePercentLowFps": row["onePercentLowFps"] / baseline["onePercentLowFps"]}
            md.append(f"| {row['config']} | {row['runs']} | {fmt(row['avgFps'])} | {fmt(row['onePercentLowFps'])} | "
                      f"{fmt(row['pointOnePercentLowFps'])} | {fmt(row['p50FrameMs'], 2)} | {fmt(row['p99FrameMs'], 2)} | {rel} | "
                      f"{row['backend']} | {'; '.join(row['notes']) or ''} |")
            group["configs"].append(row)
        phase_names = sorted({n for row in rows for n in row["phases"]}, key=lambda n: [p.get("name") for p in sample.get("phases", [])].index(n)
                             if n in [p.get("name") for p in sample.get("phases", [])] else 99)
        if phase_names:
            md += ["", "Per phase (avg FPS / 1% low):", "",
                   "| config | " + " | ".join(phase_names) + " |", "|---|" + "---:|" * len(phase_names)]
            for row in rows:
                cells = [f"{fmt(row['phases'][n]['avgFps'])} / {fmt(row['phases'][n]['onePercentLowFps'])}" if n in row["phases"] else "-"
                         for n in phase_names]
                md.append(f"| {row['config']} | " + " | ".join(cells) + " |")
        md += energy_table(rows, baseline, used, fps_cap, group)
        md += ["", "Mods per configuration:", ""]
        for row in rows:
            md.append(f"- **{row['config']}**: " + (", ".join(row["mods"]) if row["mods"] else "none besides the harness"))
        md.append("")
        summary["groups"].append(group)

    if not groups:
        md += ["No comparable runs yet: every result was skipped for the reasons below. A run only counts when it is "
               "valid (for example, the game window was on screen) and not disturbed by background load.", ""]
    if skipped:
        md += ["Skipped runs: " + ", ".join(f"{n} {why}" for why, n in skipped.items()) + ".", ""]
    os.makedirs(os.path.dirname(OUT_MD), exist_ok=True)
    with open(OUT_MD, "w") as fh:
        fh.write("\n".join(md))
    with open(OUT_JSON, "w") as fh:
        json.dump(summary, fh, indent=2)
        fh.write("\n")
    print("\n".join(md))
    print(f"\nwrote {os.path.relpath(OUT_MD)} and {os.path.relpath(OUT_JSON)}")


if __name__ == "__main__":
    main()
