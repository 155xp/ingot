#!/bin/sh
# Usage: ./run.sh [variant-name] [ShaderXlate flags...]
#   e.g. ./run.sh msl24-compact --compare-prototype --desc
#        ./run.sh msl30-proto --translator prototype --msl 30000
# Needs: ./fetch-libs.sh once, a Loom-cached Minecraft 26.3 (or MC_JAR / MC_VERSION_JSON), Xcode CLT.
# The default translator is the mod's own dev.ingot.shader.SpirvToMsl, so the mod is compiled first.
# METALCHECK_FLAGS defaults to the MTLCompileOptions libingot uses (ingot_library_create: safe math, preserveInvariance).
set -eu
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/Users/eric/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home}
export JAVA_HOME
VARIANT=${1:-default}; [ $# -gt 0 ] && shift
[ -d lib ] || ./fetch-libs.sh
(cd ../.. && ./gradlew compileJava -q)
CP=$(python3 classpath.py):../../build/classes/java/main
rm -rf build/classes
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -nowarn -d build/classes -cp "$CP" $(find src -name '*.java')
for t in metalcheck metalrender; do
  if [ ! -x build/$t ] || [ metalcheck/$t.m -nt build/$t ] || [ ../../native/include/ingot.h -nt build/$t ]; then
    xcrun clang -O2 -fobjc-arc -I ../../native/include -framework Metal -framework Foundation metalcheck/$t.m -o build/$t
  fi
done
OUT=out/$VARIANT
rm -rf "$OUT"
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -Dorg.lwjgl.util.NoChecks=false -cp "build/classes:$CP" ShaderXlate --out "$OUT" "$@"
MSLV=$(python3 -c "import json,re;o=json.load(open('$OUT/summary.json'))['options'];print(re.search(r'mslVersion=(\d+)',o).group(1))")
FLAGS=${METALCHECK_FLAGS-"--safe-math --preserve-invariance"}
[ -d "$OUT/desc" ] && FLAGS="$FLAGS --desc"
build/metalcheck "$OUT" "$MSLV" $FLAGS
build/metalrender "$OUT" "$MSLV"
