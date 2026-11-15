#!/usr/bin/env bash
# Captures the sample catalogs twice, from scratch each time, and checks the two runs wrote the
# same files, byte for byte. A capture is a pure function of its Kotlin source: a run that differs
# is a spurious change in every catalog build and every content-addressed blob.
#
#   scripts/check-capture-determinism.sh
#
# On a difference it prints which files differ; `remote-compose-player --diff a.rc b.rc`
# (rc-players' desktop viewer) explains a pair of `.rc` files structurally.
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/capture-determinism
rm -rf "$out"
mkdir -p "$out"
gradle() { if command -v build-brief >/dev/null; then build-brief ./gradlew "$@"; else ./gradlew "$@"; fi; }
for run in 1 2; do
  gradle :livery-capture-cmp:captureSamples --rerun
  cp -r livery-capture-cmp/build/captured-samples "$out/run$run"
done
if diff -r "$out/run1" "$out/run2" >"$out/diff.txt"; then
  echo "deterministic: $(find "$out/run1" -type f | wc -l) files identical across two captures"
else
  cat "$out/diff.txt"
  echo "captures differ between runs; see $out/run1 and $out/run2" >&2
  exit 1
fi
