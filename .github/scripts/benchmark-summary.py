#!/usr/bin/env python3
"""Summarizes :livery-benchmark's Macrobenchmark results: Livery against Compose UI.

Usage: benchmark-summary.py <directory holding *-benchmarkData.json>

Prints a Markdown table of FrameTimingMetric's frame durations and overruns per test, and the
Livery/Compose UI ratio of each; then, for the start-up tests, initial and full display, and for the
lanes with Livery's startup how long it took (`livery:startup`) and how long the splash screen held
the first frame for it (`host:first-frame-held`, near 0 when the startup's work fits in the activity's
creation). Run on an emulator, so a rough comparison only.
"""
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
files = sorted(root.rglob("*benchmarkData.json"))
if not files:
    print("No benchmark results found.")
    sys.exit(0)

rows = {}
startups = {}
device = None
for f in files:
    data = json.loads(f.read_text())
    ctx = data.get("context", {}).get("build", {})
    device = device or f"{ctx.get('model', '?')} (API {ctx.get('version', {}).get('sdk', '?')})"
    for b in data.get("benchmarks", []):
        sampled = b.get("sampledMetrics", {})
        metrics = b.get("metrics", {})
        if "timeToInitialDisplayMs" in metrics:
            startups[b["name"]] = {k: v.get("median") for k, v in metrics.items() if isinstance(v, dict)}
            continue
        rows[b["name"]] = {
            "cpu": sampled.get("frameDurationCpuMs", {}),
            "overrun": sampled.get("frameOverrunMs", {}),
            "frames": metrics.get("frameCount", {}).get("median"),
        }

def pick(row, metric, p):
    value = row.get(metric, {}).get(p)
    return value if isinstance(value, (int, float)) else None

def ms(v):
    return "–" if v is None else f"{v:.1f} ms"

print("### Feed fling: Livery and the Remote Compose player against Compose UI")
print()
print(f"Emulator: {device}. FrameTimingMetric over 5 runs of 6 flings down the same 200 sample cards; a rough comparison, not a device number.")
print()
print("| Test | Frames | CPU p50 | CPU p90 | CPU p99 | Overrun p50 | Overrun p90 | Overrun p99 |")
print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
for name, row in rows.items():
    frames = row["frames"]
    print(
        f"| `{name}` | {'–' if frames is None else int(frames)} | "
        + " | ".join(ms(pick(row, m, p)) for m in ("cpu", "overrun") for p in ("P50", "P90", "P99"))
        + " |"
    )
compose = rows.get("composeUi")
if compose:
    print()
    print("| CPU frame duration against Compose UI | p50 | p90 | p99 |")
    print("| --- | ---: | ---: | ---: |")
    for name, label in (("livery", "Livery slots"), ("rcPlayer", "Remote Compose player alone")):
        row = rows.get(name)
        if not row:
            continue
        ratios = []
        for p in ("P50", "P90", "P99"):
            a, b = pick(row, "cpu", p), pick(compose, "cpu", p)
            ratios.append("–" if not a or not b else f"{a / b:.2f}×")
        print(f"| {label} | " + " | ".join(ratios) + " |")

if startups:
    print()
    print("### Cold start")
    print()
    print("Median of the runs. `livery:startup` is how long Livery's startup took to prepare the first screen; "
          "`first frame held` is how long the splash screen held the first frame for it (near 0 when it was done "
          "by the time the activity was created).")
    print()
    print("| Test | Initial display | Full display | livery:startup | First frame held |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for name, m in sorted(startups.items()):
        print(
            f"| `{name}` | {ms(m.get('timeToInitialDisplayMs'))} | {ms(m.get('timeToFullDisplayMs'))} | "
            f"{ms(m.get('livery:startupFirstMs'))} | {ms(m.get('host:first-frame-heldFirstMs'))} |"
        )
