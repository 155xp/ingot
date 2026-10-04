#!/bin/sh
# Downloads LWJGL 3.4.3 (core, shaderc, spvc) + macOS arm64 natives from Maven Central into ./lib.
# These are the exact versions Minecraft 26.3 ships (see the version json).
set -eu
cd "$(dirname "$0")"
mkdir -p lib
V=3.4.3
BASE=https://repo1.maven.org/maven2/org/lwjgl
for a in lwjgl lwjgl-shaderc lwjgl-spvc; do
  for c in "" "-natives-macos-arm64"; do
    f="$a-$V$c.jar"
    [ -f "lib/$f" ] || curl -fsSL -o "lib/$f" "$BASE/$a/$V/$f"
    # verify against the .sha1 published next to the artifact
    want=$(curl -fsSL "$BASE/$a/$V/$f.sha1" | cut -c1-40)
    got=$(shasum -a 1 "lib/$f" | cut -c1-40)
    [ "$want" = "$got" ] || { echo "sha1 mismatch for $f" >&2; exit 1; }
    echo "ok $f"
  done
done
