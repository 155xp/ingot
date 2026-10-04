#!/bin/sh
# Usage: ./run.sh [variant-name] [ShaderXlate flags...]
#   e.g. ./run.sh msl24-compact --msl 20400 --binding compact
# Needs: ./fetch-libs.sh once, a Loom-cached Minecraft 26.3 (or MC_JAR / MC_VERSION_JSON), Xcode CLT.
set -eu
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/Users/eric/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home}
VARIANT=${1:-default}; [ $# -gt 0 ] && shift
[ -d lib ] || ./fetch-libs.sh
CP=$(python3 classpath.py)
mkdir -p build/classes
"$JAVA_HOME/bin/javac" -nowarn -d build/classes -cp "$CP" src/*.java
for t in metalcheck metalrender; do
  if [ ! -x build/$t ] || [ metalcheck/$t.m -nt build/$t ]; then
    xcrun clang -O2 -fobjc-arc -framework Metal -framework Foundation metalcheck/$t.m -o build/$t
  fi
done
OUT=out/$VARIANT
rm -rf "$OUT"
"$JAVA_HOME/bin/java" -Dorg.lwjgl.util.NoChecks=false -cp "build/classes:$CP" ShaderXlate --out "$OUT" "$@"
MSLV=$(python3 -c "import json,re;o=json.load(open('$OUT/summary.json'))['options'];print(re.search(r'mslVersion=(\d+)',o).group(1))")
build/metalcheck "$OUT" "$MSLV"
build/metalrender "$OUT" "$MSLV"
