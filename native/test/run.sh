#!/bin/sh
# Builds the native smoke test against the built libingot.dylib and runs it twice:
#  1. plainly, with OBJC_DEBUG_MISSING_POOLS=YES: the test calls libingot without an autorelease pool (like the Java
#     render thread), so any object an entry point autoreleases on the calling thread is reported and fails the run.
#     Metal and Core Animation report a few from their own worker threads; those are outside libingot and ignored.
#  2. under the Metal API validation layer with shader validation: any validation message fails the run. (The GPU
#     validation layer autoreleases bookkeeping objects inside hot calls itself, so pools are not checked here.)
#
#   native/test/run.sh                      builds libingot (./gradlew compileNative) first
#   INGOT_SKIP_BUILD=1 native/test/run.sh   uses the existing build/native/natives/macos/libingot.dylib
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LIB_DIR="$ROOT/build/native/natives/macos"
OUT_DIR="$ROOT/build/native/test"
mkdir -p "$OUT_DIR"

if [ "${INGOT_SKIP_BUILD:-0}" != 1 ]; then
    (cd "$ROOT" && ./gradlew -q compileNative)
fi

xcrun -sdk macosx clang -arch "$(uname -m)" -mmacosx-version-min=12.0 -fobjc-arc -O1 -g -Wall -Wextra -Wno-unused-parameter \
    -I "$ROOT/native/include" "$ROOT/native/test/smoke.m" \
    -L "$LIB_DIR" -lingot -Wl,-rpath,"$LIB_DIR" \
    -framework Foundation -framework Metal -framework QuartzCore \
    -o "$OUT_DIR/smoke"

# fail_on <name> <findings>: prints the findings and exits when there are any.
fail_on() {
    if [ -n "$2" ]; then
        echo "$1: findings:"
        echo "$2"
        exit 1
    fi
}

# run <name> <log> [VAR=value...]: runs the test with the given environment and prints its output (without the
# missing-pool reports); fails on a non-zero exit (failed checks, validation-layer assertions, crashes).
run() {
    name=$1
    log=$2
    shift 2
    echo "== $name"
    status=0
    env "$@" "$OUT_DIR/smoke" >"$log" 2>&1 || status=$?
    grep -v "MISSING POOLS" "$log" || true
    if [ "$status" -ne 0 ]; then
        echo "$name failed (exit $status)"
        exit 1
    fi
}

run "smoke test" "$OUT_DIR/smoke.log" OBJC_DEBUG_MISSING_POOLS=YES
thread=$(sed -n 's/^calling thread \(0x[0-9a-f]*\)$/\1/p' "$OUT_DIR/smoke.log")
fail_on "smoke test" "$(grep -F "MISSING POOLS: ($thread)" "$OUT_DIR/smoke.log" || true)"

# Errors abort (the layer's default); warnings are logged and caught here like everything else it prints.
run "smoke test with MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1" "$OUT_DIR/validation.log" \
    MTL_DEBUG_LAYER=1 MTL_DEBUG_LAYER_WARNING_MODE=nslog MTL_SHADER_VALIDATION=1
fail_on "validation run" "$(grep -E -i "validation|MTLDebug|failed assertion|warning|unused|invalid" "$OUT_DIR/validation.log" |
    grep -v "Validation Enabled" || true)"

echo "all runs clean"
