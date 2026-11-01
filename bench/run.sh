#!/usr/bin/env bash
# Ingot benchmark runner. See bench/README.md.
#
#   bench/run.sh prepare  [--scenario standard] [--survey] [--force]
#   bench/run.sh <config> [--scenario standard] [--duration S] [--warmup S] [--repeat N] [--fps-cap off|N|off,N]
#                         [--width W --height H] [--without-ingot] [--max-background CORES] [--retries N]
#                         [--no-energy] [--allow-hidden]
#   bench/run.sh all      [same options]          every config in bench/configs.json
#   bench/run.sh vanilla,mainstream [same options] a comma-separated list
#
# <config> is a key of bench/configs.json: vanilla, mainstream, ingot, ingot-plus. With several configs and --repeat N
# the runs are interleaved (A B A B ...) so slow drift (thermals, background load) hits every config alike.
# --fps-cap 60 sets Max Framerate to 60 (vsync off) so every config renders the same frame rate and the comparison is
# energy per frame; --fps-cap off,60 runs both variants, interleaved. Every run records energy (game CPU, whole GPU)
# with bench/power/ingot-energy, and each batch starts with a 10 s idle GPU baseline; --no-energy skips both.
# --cooldown S sleeps S seconds between runs. Each run waits (up to 2 minutes) for the rest of the Mac to use at most
# --max-background CPU cores (default 5); a run with more background load (on average, or in bursts) is marked
# disturbed and repeated up to --retries times (default 2); report.py leaves disturbed runs out. The screen must be
# unlocked with the display on, because a hidden window renders much faster. --allow-hidden runs anyway (every result
# is then invalid; for testing the harness).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCH="$ROOT/bench"
cd "$ROOT"

usage() { sed -n '2,20p' "$BENCH/run.sh" | sed 's/^# \{0,1\}//'; exit 2; }

[ $# -ge 1 ] || usage
case "$1" in -*) usage ;; esac
TARGET="$1"; shift
SCENARIO=standard
DURATION=""
WARMUP=""
REPEAT=1
WIDTH=1280
HEIGHT=720
WITH_INGOT=true
SURVEY=""
COOLDOWN=0
FORCE=0
MAX_BACKGROUND=5
RETRIES=2
ALLOW_HIDDEN=0
FPS_CAPS=0
ENERGY=1
while [ $# -gt 0 ]; do
	case "$1" in
		--scenario) SCENARIO="$2"; shift 2 ;;
		--duration) DURATION="$2"; shift 2 ;;
		--warmup) WARMUP="$2"; shift 2 ;;
		--repeat) REPEAT="$2"; shift 2 ;;
		--width) WIDTH="$2"; shift 2 ;;
		--height) HEIGHT="$2"; shift 2 ;;
		--without-ingot) WITH_INGOT=false; shift ;;
		--survey) SURVEY="--survey"; shift ;;
		--cooldown) COOLDOWN="$2"; shift 2 ;;
		--force) FORCE=1; shift ;;
		--max-background) MAX_BACKGROUND="$2"; shift 2 ;;
		--retries) RETRIES="$2"; shift 2 ;;
		--allow-hidden) ALLOW_HIDDEN=1; shift ;;
		--fps-cap) FPS_CAPS="$2"; shift 2 ;;
		--no-energy) ENERGY=0; shift ;;
		-h|--help) usage ;;
		*) echo "unknown option: $1" >&2; usage ;;
	esac
done
for n in "$REPEAT" "$WIDTH" "$HEIGHT"; do
	[[ "$n" =~ ^[1-9][0-9]*$ ]] || { echo "error: --repeat/--width/--height need a positive integer, got '$n'" >&2; exit 2; }
done
for n in "$DURATION" "$WARMUP" "$COOLDOWN" "$MAX_BACKGROUND"; do
	[ -z "$n" ] || [[ "$n" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "error: --duration/--warmup/--cooldown/--max-background need a number, got '$n'" >&2; exit 2; }
done
[[ "$RETRIES" =~ ^[0-9]+$ ]] || { echo "error: --retries needs a non-negative integer, got '$RETRIES'" >&2; exit 2; }
# --fps-cap: a comma-separated list of variants; off (= 0, Unlimited) or a Max Framerate the video settings slider
# offers (a multiple of 10 from 10 to 250).
caps=""
for cap in $(echo "$FPS_CAPS" | tr ',' ' '); do
	case "$cap" in off|none|unlimited) cap=0 ;; esac
	[[ "$cap" =~ ^[0-9]{1,4}$ ]] && cap=$((10#$cap))
	if ! [[ "$cap" =~ ^[0-9]+$ ]] || { [ "$cap" -ne 0 ] && { [ "$cap" -lt 10 ] || [ "$cap" -gt 250 ] || [ $((cap % 10)) -ne 0 ]; }; }; then
		echo "error: --fps-cap takes off or a multiple of 10 from 10 to 250 (comma-separated for several), got '$cap'" >&2
		exit 2
	fi
	case " $caps " in *" $cap "*) ;; *) caps="$caps $cap" ;; esac
done
[ -n "$caps" ] || { echo "error: --fps-cap needs a value" >&2; exit 2; }
FPS_CAPS="${caps# }"

if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home" ]; then
	export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home"
fi

# ---- one benchmark at a time ------------------------------------------------------------------------------------
# shlock writes our pid into the lock file atomically and only replaces a lock whose pid is dead. A game that outlived
# its runner (the game is a child of the Gradle daemon, not of this script) is caught by the process check below.
mkdir -p "$ROOT/run/bench"
LOCKFILE="$ROOT/run/bench/bench.lock"
if [ -d "$ROOT/run/bench/.lock" ]; then # lock directory of an older version of this script
	old="$(cat "$ROOT/run/bench/.lock/pid" 2>/dev/null || true)"
	if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
		echo "error: another benchmark is running (pid $old); refusing to run two at once" >&2
		exit 1
	fi
	rm -rf "$ROOT/run/bench/.lock"
fi
if ! shlock -f "$LOCKFILE" -p $$; then
	echo "error: another benchmark is running (pid $(cat "$LOCKFILE" 2>/dev/null || echo '?')); refusing to run two at once" >&2
	exit 1
fi

GRADLE_PID=""
SAMPLER_PID=""
ENERGY_PID=""
CURRENT_RUNDIR=""
ENERGY_SRC="$BENCH/power/ingot-energy.c"
ENERGY_BIN="$BENCH/power/build/ingot-energy"
IDLE_JSON="$ROOT/run/bench/idle-baseline.json"

game_pids() { # [rundir]: pids of benchmark game JVMs (all of them, or the one writing into rundir)
	if [ -n "${1:-}" ]; then
		pgrep -f "ingot[.]bench[.]out=$ROOT/$1/bench-result[.]json" || true
	else
		pgrep -f "ingot[.]bench[.]scenario=" || true
	fi
}

kill_game() { # <rundir>
	local pids
	pids="$(game_pids "$1")"
	[ -n "$pids" ] || return 0
	echo "stopping the game (pid $(echo $pids))" >&2
	kill $pids 2>/dev/null || true
	for _ in 1 2 3 4 5 6 7 8 9 10; do
		sleep 1
		pids="$(game_pids "$1")"
		[ -n "$pids" ] || return 0
	done
	kill -9 $pids 2>/dev/null || true
}

cleanup() {
	if [ -n "$CURRENT_RUNDIR" ]; then
		kill_game "$CURRENT_RUNDIR"
	fi
	[ -z "$GRADLE_PID" ] || kill "$GRADLE_PID" 2>/dev/null || true
	[ -z "$SAMPLER_PID" ] || kill "$SAMPLER_PID" 2>/dev/null || true
	[ -z "$ENERGY_PID" ] || kill "$ENERGY_PID" 2>/dev/null || true
	if [ "$(cat "$LOCKFILE" 2>/dev/null)" = "$$" ]; then
		rm -f "$LOCKFILE"
	fi
}
trap cleanup EXIT
trap 'echo "interrupted" >&2; exit 130' INT TERM HUP

others="$(game_pids)"
if [ -n "$others" ]; then
	echo "error: a benchmark game is still running (pid $(echo $others)), probably left over from an interrupted run." >&2
	echo "Wait for it to finish or stop it (kill $(echo $others)), then try again." >&2
	exit 1
fi

# Keep the display and the Mac awake for the whole batch: a sleeping display stops presentation and ruins a run.
caffeinate -dims -w $$ &

# ---- fairness warnings -------------------------------------------------------------------------------------------
preflight() {
	if pmset -g batt 2>/dev/null | grep -q "Battery Power"; then
		echo "warning: running on battery power; results will be lower and noisier" >&2
	fi
	if pmset -g 2>/dev/null | grep -E "lowpowermode\s+1" >/dev/null; then
		echo "warning: Low Power Mode is on" >&2
	fi
	local others load
	others="$(pgrep -f 'net.minecraft.client.main.Main|net.fabricmc.devlaunchinjector.Main|KnotClient' || true)"
	if [ -n "$others" ]; then
		echo "warning: another Minecraft process is running (pids: $(echo $others)); it will skew the results" >&2
	fi
	load="$(sysctl -n vm.loadavg | awk '{print $2}')"
	if awk -v l="$load" 'BEGIN{exit !(l > 3)}'; then
		echo "warning: the Mac is busy (1-minute load $load; top: $(ps -A -o %cpu=,comm= | sort -rn | head -3 | awk '{n=$2; sub(".*/","",n); printf "%s %s%%  ", n, $1}'))" >&2
	fi
}

# The game window has to be on screen: with the display asleep or the screen locked nothing is composited and the
# frame rate roughly doubles. Wake a sleeping display (like touching the trackpad); a locked screen needs the user.
check_screen() {
	local state
	state="$(python3 "$BENCH/benchlib.py" screen-state)"
	if [ "$state" = "display asleep" ]; then
		caffeinate -u -t 2 >/dev/null 2>&1 || true
		sleep 2
		state="$(python3 "$BENCH/benchlib.py" screen-state)"
	fi
	case "$state" in
		awake|unknown) return 0 ;;
	esac
	if [ "$ALLOW_HIDDEN" = 1 ]; then
		echo "warning: $state; running anyway because of --allow-hidden, the result will be marked invalid" >&2
		return 0
	fi
	echo "error: $state. The game window must be visible during a run (a hidden window renders about twice as fast)." >&2
	echo "Unlock the Mac, keep the display on, and run again (--allow-hidden only for testing the harness itself)." >&2
	exit 1
}

# ---- energy ------------------------------------------------------------------------------------------------------
# bench/power/ingot-energy samples the game's CPU energy (proc_pid_rusage) and the GPU's energy (IOReport) every 100 ms;
# benchlib.py integrates both over the measured frames. Built once and cached under bench/power/build (gitignored).
build_energy_sampler() {
	[ "$ENERGY" = 1 ] || return 0
	if [ -x "$ENERGY_BIN" ] && [ "$ENERGY_BIN" -nt "$ENERGY_SRC" ]; then
		return 0
	fi
	mkdir -p "$(dirname "$ENERGY_BIN")"
	if ! clang -O2 -Wall -o "$ENERGY_BIN.tmp" "$ENERGY_SRC" -framework CoreFoundation -lIOReport; then
		echo "error: could not build the energy sampler $ENERGY_SRC (needs the Xcode command line tools: xcode-select --install);" >&2
		echo "run with --no-energy to benchmark without energy measurement" >&2
		exit 1
	fi
	mv "$ENERGY_BIN.tmp" "$ENERGY_BIN"
}

start_energy_sampler() { # <rundir>: once the game JVM exists, sample it until it exits
	local pid
	pid="$(game_pids "$1" | head -1)"
	[ -n "$pid" ] || return 0
	"$ENERGY_BIN" --pid "$pid" --out "$ROOT/$1/energy.csv" --interval-ms 100 &
	ENERGY_PID=$!
}

stop_energy_sampler() {
	[ -n "$ENERGY_PID" ] || return 0
	kill -TERM "$ENERGY_PID" 2>/dev/null || true # it also stops by itself when the game exits
	wait "$ENERGY_PID" 2>/dev/null || true
	ENERGY_PID=""
}

scenario_value() { # <key> <default>
	python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], sys.argv[3]))' \
		"$BENCH/scenarios/$SCENARIO.json" "$1" "$2"
}

run_game() { # <rundir-relative-to-root> <time limit in seconds> <sample: 0|1>
	local rundir="$1" limit="$2" sample="$3" rc=0 start=$SECONDS
	CURRENT_RUNDIR="$rundir"
	rm -f "$rundir/.sampler-stop" "$rundir/energy.csv"
	if [ "$sample" = 1 ]; then
		# Snapshots who else uses the CPU and GPU, just before and just after the measurement (never during it).
		python3 "$BENCH/benchlib.py" sample --rundir "$rundir" &
		SAMPLER_PID=$!
	fi
	./gradlew runBench --console=plain \
		-Pbench.runDir="$rundir" \
		-Pbench.sysprops="$ROOT/$rundir/bench.properties" \
		-Pbench.width="$WIDTH" -Pbench.height="$HEIGHT" \
		-Pbench.withIngot="$WITH_INGOT" > "$rundir/gradle-run.log" 2>&1 &
	GRADLE_PID=$!
	while kill -0 "$GRADLE_PID" 2>/dev/null; do
		if [ $((SECONDS - start)) -ge "$limit" ]; then
			echo "error: the run did not end within ${limit} s; stopping it" >&2
			kill_game "$rundir"
			kill "$GRADLE_PID" 2>/dev/null || true
			break
		fi
		# The game JVM appears after the build (the measurement starts a minute or more later, after world load,
		# settle and warmup), so polling every second is early enough.
		if [ "$sample" = 1 ] && [ "$ENERGY" = 1 ] && [ -z "$ENERGY_PID" ]; then
			start_energy_sampler "$rundir"
		fi
		sleep 1
	done
	wait "$GRADLE_PID" || rc=$?
	GRADLE_PID=""
	# A game can outlive a failed Gradle client; never leave one behind for the next run.
	kill_game "$rundir"
	stop_energy_sampler
	if [ -n "$SAMPLER_PID" ]; then
		touch "$rundir/.sampler-stop"
		wait "$SAMPLER_PID" 2>/dev/null || true
		SAMPLER_PID=""
	fi
	CURRENT_RUNDIR=""
	return $rc
}

# ---- prepare -----------------------------------------------------------------------------------------------------
if [ "$TARGET" = "prepare" ]; then
	RUNDIR="run/bench/prepare-$SCENARIO"
	WITH_INGOT=false # world generation is server-side; the template must not depend on the state of src/main
	if [ -z "$SURVEY" ] && [ -f "$BENCH/worlds/$SCENARIO/level.dat" ] && [ $FORCE -eq 0 ]; then
		echo "error: bench/worlds/$SCENARIO already exists. Re-preparing creates a new world template (worldgen is not" >&2
		echo "bit-for-bit reproducible), so older results stop being comparable. Pass --force to do it anyway." >&2
		exit 1
	fi
	python3 "$BENCH/benchlib.py" setup --config prepare --scenario "$SCENARIO" --rundir "$RUNDIR" --mode prepare \
		--width "$WIDTH" --height "$HEIGHT" $SURVEY >/dev/null
	echo "preparing scenario '$SCENARIO' in $RUNDIR (a Minecraft window will open; this generates the world)..."
	rc=0
	run_game "$RUNDIR" 4200 0 || rc=$?
	if [ -n "$SURVEY" ]; then
		cat "$RUNDIR/bench-result.json"
		exit $rc
	fi
	if [ $rc -ne 0 ]; then
		echo "prepare failed (exit $rc); see $RUNDIR/gradle-run.log and $RUNDIR/logs/latest.log" >&2
		exit $rc
	fi
	python3 "$BENCH/benchlib.py" store-world --scenario "$SCENARIO" --rundir "$RUNDIR"
	exit 0
fi

# ---- measurement runs --------------------------------------------------------------------------------------------
if [ "$TARGET" = "all" ]; then
	CONFIGS="$(python3 -c 'import json,sys; print(" ".join(k for k in json.load(open(sys.argv[1])) if not k.startswith("_")))' "$BENCH/configs.json")"
else
	CONFIGS="$(echo "$TARGET" | tr ',' ' ')"
	for c in $CONFIGS; do python3 "$BENCH/benchlib.py" check-config "$c"; done
fi
if [ "$WITH_INGOT" = false ]; then
	for c in $CONFIGS; do
		if python3 -c 'import json,sys; sys.exit(0 if json.load(open(sys.argv[1]))[sys.argv[2]]["ingot"] else 1)' "$BENCH/configs.json" "$c"; then
			echo "error: --without-ingot leaves Ingot off the classpath, so config '$c' cannot run" >&2
			exit 2
		fi
	done
fi
check_screen
python3 "$BENCH/fetch_mods.py" >/dev/null
[ -f "$BENCH/worlds/$SCENARIO/level.dat" ] || { echo "error: no world template; run: bench/run.sh prepare --scenario $SCENARIO" >&2; exit 1; }
build_energy_sampler
rm -f "$IDLE_JSON"
if [ "$ENERGY" = 1 ]; then
	# Idle GPU baseline for this batch: 10 s with no game running (the checks above made sure of that), after the rest
	# of the Mac has calmed down. GPU energy is system-wide, so this is what the desktop alone costs.
	python3 "$BENCH/benchlib.py" wait-quiet --max-cores "$MAX_BACKGROUND" --timeout 120
	python3 "$BENCH/benchlib.py" idle-baseline --out "$IDLE_JSON" --seconds 10 --bin "$ENERGY_BIN"
fi
EFFECTIVE_DURATION="${DURATION:-$(scenario_value durationSeconds 60)}"
EFFECTIVE_WARMUP="${WARMUP:-$(scenario_value warmupSeconds 15)}"
SETTLE_TIMEOUT="$(scenario_value settleTimeoutSeconds 120)"
# Outer limit for one run: the in-game watchdog's budget (300 + 2 x settle timeout + warmup + duration) plus time to
# build, launch and shut down. The in-game watchdog normally fires first and leaves a failure result behind.
RUN_LIMIT="$(awk -v d="$EFFECTIVE_DURATION" -v w="$EFFECTIVE_WARMUP" -v s="$SETTLE_TIMEOUT" 'BEGIN{printf "%d", 300 + 2 * s + w + d + 600}')"

QUIET_CORES="$MAX_BACKGROUND"

failures=0
disturbed=0
first=1
for i in $(seq 1 "$REPEAT"); do
	for CAP in $FPS_CAPS; do
	for CONFIG in $CONFIGS; do
		attempt=0
		while :; do
			if [ $first -eq 0 ] && [ "$COOLDOWN" != "0" ]; then sleep "$COOLDOWN"; fi
			first=0
			preflight
			check_screen
			python3 "$BENCH/benchlib.py" wait-quiet --max-cores "$QUIET_CORES" --timeout 120
			RUNDIR="run/bench/$CONFIG"
			python3 "$BENCH/benchlib.py" setup --config "$CONFIG" --scenario "$SCENARIO" --rundir "$RUNDIR" --fps-cap "$CAP" \
				--width "$WIDTH" --height "$HEIGHT" ${DURATION:+--duration "$DURATION"} ${WARMUP:+--warmup "$WARMUP"} >/dev/null
			label="$CONFIG run $i/$REPEAT"
			[ "$CAP" = 0 ] || label="$label, ${CAP} FPS cap"
			[ $attempt -eq 0 ] || label="$label, retry $attempt"
			echo "[$label] scenario=$SCENARIO duration=${EFFECTIVE_DURATION}s warmup=${EFFECTIVE_WARMUP}s (a Minecraft window will open; do not touch it)"
			rc=0
			run_game "$RUNDIR" "$RUN_LIMIT" 1 || rc=$?
			frc=0
			python3 "$BENCH/benchlib.py" finalize --config "$CONFIG" --scenario "$SCENARIO" --rundir "$RUNDIR" --rc "$rc" \
				--max-background "$MAX_BACKGROUND" --fps-cap "$CAP" --idle "$IDLE_JSON" || frc=$?
			if [ $frc -eq 3 ] && [ $attempt -lt "$RETRIES" ]; then
				attempt=$((attempt + 1))
				echo "        repeating the run: the machine was busy during the measurement" >&2
				continue
			fi
			case $frc in
				0) ;;
				3) disturbed=$((disturbed + 1)) ;;
				*) failures=$((failures + 1)) ;;
			esac
			break
		done
	done
	done
done
[ $disturbed -eq 0 ] || echo "warning: $disturbed run(s) stayed disturbed after $RETRIES retries; report.py leaves them out" >&2
[ $failures -eq 0 ] || { echo "$failures run(s) failed or were invalid" >&2; exit 1; }
