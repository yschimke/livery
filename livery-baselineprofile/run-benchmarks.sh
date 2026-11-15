#!/usr/bin/env bash
# Runs the benchmarks on the connected device straight through `adb shell am instrument`, on the APKs Gradle built, and pulls
# the results. (Gradle's connectedBenchmarkReleaseAndroidTest does the same, but on some machines its daemon is killed
# part-way through a long run.)
#
#   livery-baselineprofile/run-benchmarks.sh routine [out-dir]    spine and completion: the usual run, about 40 minutes
#   livery-baselineprofile/run-benchmarks.sh spine [out-dir]      the spine only
#   livery-baselineprofile/run-benchmarks.sh completion [out-dir] the completion bar only
#   livery-baselineprofile/run-benchmarks.sh sweeps [out-dir]     the tests in neither tier (window sizes, planning costs)
#   livery-baselineprofile/run-benchmarks.sh Class#test,Class#test [out-dir]   named tests, e.g. FeedScrollBenchmark#liveryLazyAhead
#
# Build first: ./gradlew :livery-baselineprofile-host:assembleBenchmarkRelease :livery-benchmark:assemble
set -euo pipefail
cd "$(dirname "$0")"
what=${1:-routine}
out=${2:-/tmp/livery-benchmarks-$(date +%Y%m%d-%H%M)}
pkg=ee.schimke.livery.benchmark
# The runner treats a comma-separated `annotation` list as tests with ALL of them, and a `notAnnotation` list as tests with NONE
# of them (one -e each would keep only the last): so a union of tiers is one run for each.
runs=()
case "$what" in
  routine) runs=("-e annotation $pkg.Spine" "-e annotation $pkg.Completion") ;;
  spine) runs=("-e annotation $pkg.Spine") ;;
  completion) runs=("-e annotation $pkg.Completion") ;;
  sweeps) runs=("-e notAnnotation $pkg.Spine,$pkg.Completion") ;;
  *) runs=("-e class $(echo "$what" | sed "s/\(^\|,\)/\1$pkg./g")") ;;
esac
adb install -r -d host/build/outputs/apk/benchmarkRelease/livery-baselineprofile-host-benchmarkRelease.apk >/dev/null
adb install -r -d benchmark/build/outputs/apk/benchmarkRelease/livery-benchmark-benchmarkRelease.apk >/dev/null
dev=/sdcard/Download/livery-benchmarks
adb shell rm -rf "$dev"; adb shell mkdir -p "$dev"
start=$(date +%s)
n=0
for r in "${runs[@]}"; do
  n=$((n + 1))
  # shellcheck disable=SC2086
  adb shell am instrument -w $r -e additionalTestOutputDir "$dev" "$pkg/androidx.test.runner.AndroidJUnitRunner" | tail -4
  # Each run clears the output directory when it starts and writes the same file name: pull each run's before the next.
  adb shell mv "$dev/$pkg-benchmarkData.json" "$dev/run$n-benchmarkData.json" || true
  mkdir -p "$out" && adb pull "$dev/." "$out" >/dev/null
done
echo "took $(( ($(date +%s) - start) / 60 )) minutes"
echo "results in $out (run<n>-benchmarkData.json, one for each tier run, and a Perfetto trace for each iteration: livery-baselineprofile/traces/)"
