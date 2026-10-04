#!/bin/bash
# Runs every translation variant used in docs/research/shader-translation-experiment.md and
# prints one line per variant. Cold-compile timings: build/metalcheck out/<v> <msl> --nonce [--parallel].
set -u
cd "$(dirname "$0")"
variants=(
  "msl24-compact"
  "msl24-compact-mt --threads 12"
  "msl30-compact --msl 30000"
  "msl24-identity --binding identity"
  "msl24-decoration --binding decoration"
  "msl24-noexplicitdepth --no-explicit-depth"
  "msl24-notbnative --no-texture-buffer-native"
  "msl24-nopad --no-pad-outputs"
  "msl24-noflip --no-flip-y"
  "msl24-nozeroinit --no-zero-init"
)
for v in "${variants[@]}"; do
  set -- $v; name=$1; shift
  log=$(./run.sh "$name" "$@" 2>&1)
  python3 - "$name" <<PY
import json,sys
n=sys.argv[1]; s=json.load(open(f"out/{n}/summary.json")); m=json.load(open(f"out/{n}/metalcheck.json"))["summary"]
print(f"{n:28s} translated={s['translated']}/{s['pipelines']} uniqueMsl={s['uniqueMslFiles']} libFail={m['libFail']} psoOk={m['psoOk']} psoFail={m['psoFail']} spvcMs={s['spvcTotalMs']} wallMs={s['frontendPlusSpvcWallMs']}")
PY
  echo "$log" | grep -E "=> |test" | sed 's/^/    /'
done
