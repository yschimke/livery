#!/usr/bin/env bash
# Runs the benchmarks on the connected device straight through `adb shell am instrument`, on the APKs Gradle built, and pulls
# the results. (Gradle's connectedBenchmarkReleaseAndroidTest does the same, but on some machines its daemon is killed
# part-way through a long run.)
#
#   livery-baselineprofile/run-benchmarks.sh routine [out-dir]    spine and completion: the usual run, about 40 minutes
#   livery-baselineprofile/run-benchmarks.sh spine [out-dir]      the spine only, about 25 minutes
#   livery-baselineprofile/run-benchmarks.sh sweeps [out-dir]     the tests in neither tier (window sizes, planning costs)
#   livery-baselineprofile/run-benchmarks.sh Class#test,Class#test [out-dir]   named tests, e.g. FeedScrollBenchmark#liveryLazyAhead
#
# Build first: ./gradlew :livery-baselineprofile-host:assembleBenchmarkRelease :livery-benchmark:assemble
set -euo pipefail
cd "$(dirname "$0")"
what=${1:-routine}
out=${2:-/tmp/livery-benchmarks-$(date +%Y%m%d-%H%M)}
pkg=ee.schimke.livery.benchmark
args=()
case "$what" in
  routine) args=(-e annotation "$pkg.Spine,$pkg.Completion") ;;
  spine) args=(-e annotation "$pkg.Spine") ;;
  sweeps) args=(-e notAnnotation "$pkg.Spine,$pkg.Completion") ;;
  *) args=(-e class "$(echo "$what" | sed "s/\(^\|,\)/\1$pkg./g")") ;;
esac
adb install -r -d host/build/outputs/apk/benchmarkRelease/livery-baselineprofile-host-benchmarkRelease.apk >/dev/null
adb install -r -d benchmark/build/outputs/apk/benchmarkRelease/livery-benchmark-benchmarkRelease.apk >/dev/null
dev=/sdcard/Download/livery-benchmarks
adb shell rm -rf "$dev"; adb shell mkdir -p "$dev"
start=$(date +%s)
adb shell am instrument -w "${args[@]}" -e additionalTestOutputDir "$dev" "$pkg/androidx.test.runner.AndroidJUnitRunner" | tail -5
echo "took $(( ($(date +%s) - start) / 60 )) minutes"
mkdir -p "$out" && adb pull "$dev/." "$out" >/dev/null
echo "results in $out (the *-benchmarkData.json, and a Perfetto trace for each iteration: livery-baselineprofile/traces/)"
