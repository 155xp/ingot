#!/bin/bash
# Runs every translation variant used in docs/research/shader-translation-experiment.md and
# prints one line per variant. Cold-compile timings: build/metalcheck out/<v> <msl> --nonce [--parallel].
# The first two use the mod's SpirvToMsl and require output identical to the prototype; the option variants
# exercise the prototype, which is the only translator with switchable options.
set -u
cd "$(dirname "$0")"
variants=(
  "msl24-compact --compare-prototype --desc"
  "msl24-compact-mt --threads 12 --compare-prototype"
  "msl30-compact --translator prototype --msl 30000"
  "msl24-identity --translator prototype --binding identity"
  "msl24-decoration --translator prototype --binding decoration"
  "msl24-noexplicitdepth --no-explicit-depth --compare-prototype"
  "msl24-notbnative --translator prototype --no-texture-buffer-native"
  "msl24-nopad --translator prototype --no-pad-outputs"
  "msl24-noflip --translator prototype --no-flip-y"
  "msl24-nozeroinit --translator prototype --no-zero-init"
)
for v in "${variants[@]}"; do
  set -- $v; name=$1; shift
  log=$(./run.sh "$name" "$@" 2>&1)
  python3 - "$name" <<PY
import json,sys
n=sys.argv[1]; s=json.load(open(f"out/{n}/summary.json")); m=json.load(open(f"out/{n}/metalcheck.json"))["summary"]
cmp=f" protoMismatch={s['prototypeMismatches']}" if 'prototypeMismatches' in s else ""
desc=f" desc={m['descPsoOk']}/{m['descChecked']} descMismatch={m['descMismatch']}" if m.get('descChecked') else ""
print(f"{n:28s} {s['translator'].split()[0]:9s} translated={s['translated']}/{s['pipelines']} uniqueMsl={s['uniqueMslFiles']} libFail={m['libFail']} psoOk={m['psoOk']} psoFail={m['psoFail']}{cmp}{desc} spvcMs={s['spvcTotalMs']} wallMs={s['frontendPlusSpvcWallMs']}")
PY
  echo "$log" | grep -E "=> |test|MISMATCH" | sed 's/^/    /'
done
