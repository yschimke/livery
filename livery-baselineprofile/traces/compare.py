#!/usr/bin/env python3
"""Compares the Perfetto traces :livery-benchmark records, lane against lane.

Macrobenchmark writes one trace per iteration (`<Class>_<test>_iterNNN_<date>.perfetto-trace`). This
reads those of two lanes, takes the median over the iterations, and prints Markdown: what cold start
looks like, and what a frame of the fling costs, phase by phase.

  python3 livery-baselineprofile/traces/compare.py <dir holding the traces> [--a composeUi] [--b livery]

Needs `trace_processor_shell` (see .claude/skills/perfetto-traces/SKILL.md); found on PATH, in
$TRACE_PROCESSOR, or in ~/.local/share/perfetto/prebuilts/. Traces are not committed; they are build output.
"""
import argparse
import csv
import io
import os
import pathlib
import shutil
import statistics
import subprocess
import sys

PACKAGE = "ee.schimke.livery.baselineprofile.host"
# Lane name -> (startup test, fling test): the names the benchmark classes give their tests.
LANES = {
    "composeUi": ("DocumentBenchmark_composeUiStartup", "FeedScrollBenchmark_composeUi"),
    "livery": ("DocumentBenchmark_liveryStartup", "FeedScrollBenchmark_livery"),
    "rcPlayer": ("DocumentBenchmark_rcPlayerStartup", "FeedScrollBenchmark_rcPlayer"),
    "liveryNoDefer": ("DocumentBenchmark_liveryNoDeferStartup", "FeedScrollBenchmark_liveryNoDefer"),
    "liveryNoFlat": ("DocumentBenchmark_liveryNoFlatStartup", "FeedScrollBenchmark_liveryNoFlat"),
}
LABEL = {"composeUi": "Compose UI", "livery": "Livery", "rcPlayer": "Player alone",
         "liveryNoDefer": "Livery, no deferral", "liveryNoFlat": "Livery, no flat layout"}


def find_processor():
    for cand in (os.environ.get("TRACE_PROCESSOR"), shutil.which("trace_processor_shell"), shutil.which("trace_processor")):
        if cand:
            return cand
    found = sorted(pathlib.Path.home().glob(".local/share/perfetto/prebuilts/trace_processor_shell-*"))
    if found:
        return str(found[-1])
    sys.exit("trace_processor_shell not found: set TRACE_PROCESSOR or see .claude/skills/perfetto-traces/SKILL.md")


def query(tp, trace, sql):
    """Runs one query on one trace. A trace that cannot be read, a query that fails, or an answer with no
    rows stops the run with the trace named: a silent empty result would shrink the sample a median is
    taken over, or leave a table half empty."""
    done = subprocess.run([tp, "-q", "/dev/stdin", str(trace)], input=sql, capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit(f"trace_processor_shell failed (exit {done.returncode}) on {trace}:\n{done.stderr.strip() or done.stdout.strip()}")
    lines = [l for l in done.stdout.splitlines() if l and not l.startswith(("[", "Loading", "column "))]
    rows = list(csv.DictReader(io.StringIO("\n".join(lines))))
    if not rows:
        sys.exit(f"the query returned no rows on {trace}: is {PACKAGE} in the trace?\n{done.stderr.strip()}")
    return rows


# The app's process and its two threads that matter: the main thread (tid == pid) and RenderThread.
# `t0` is when the system received the launch intent, the origin Macrobenchmark's startup times use.
PRELUDE = f"""
with p as (select upid, pid, start_ts from process where name = '{PACKAGE}' order by start_ts desc limit 1),
t0 as (select coalesce((select min(ts) from slice where name like 'MetricsLogger:launchObserverNotifyIntentStarted'), (select start_ts from p)) t),
m as (select s.* from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid) where th.upid = (select upid from p) and th.tid = (select pid from p)),
r as (select s.* from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid) where th.upid = (select upid from p) and th.name = 'RenderThread')
"""


def ms(expr):
    return f"round(({expr} - (select t from t0)) / 1e6, 1)"


STARTUP_SQL = PRELUDE + f"""
,fd as (select min(ts) ts from slice where name like 'reportFullyDrawn() for {{%'),
-- The frame that draws the content is the last RenderThread frame to start before reportFullyDrawn: the host
-- reports from the frame callback after the frame in which the document first composed, then requests one
-- more frame. No duration threshold: that would depend on how fast the device is. If the host moves that
-- call, this rule moves with it.
content as (select ts, dur from r where name like 'DrawFrames%' and ts < (select ts from fd) order by ts desc limit 1),
full as (select ts + dur as e from r where name like 'DrawFrames%' and ts >= (select ts from fd) order by ts limit 1)
select 'bindApplication ends' k, {ms("(select max(ts + dur) from m where name = 'bindApplication')")} v
union all select 'activity resumed', {ms("(select max(ts + dur) from m where name like 'performResume:%')")}
union all select 'first frame starts', {ms("(select min(ts) from m where name like 'Choreographer#doFrame%' and depth = 0 and ts > (select ts from m where name like 'performResume:%' limit 1))")}
union all select 'document drawn on screen (RenderThread frame with content) starts', {ms("(select ts from content)")}
union all select 'reportFullyDrawn', {ms("(select ts from fd)")}
union all select 'full display (end of the first frame drawn after it)', {ms("(select e from full)")}
union all select 'frames drawn before that', (select count(*) from r where name like 'DrawFrames%' and ts < (select e from full))
union all select 'main thread: Compose initializeView', round((select coalesce(sum(dur),0) from m where name = 'Compose:initializeView' and ts < (select e from full)) / 1e6, 1)
union all select 'main thread: recompose', round((select coalesce(sum(dur),0) from m where name = 'Compose:recompose' and ts < (select e from full)) / 1e6, 1)
union all select 'main thread: measure', round((select coalesce(sum(dur),0) from m where name = 'AndroidOwner:onMeasure' and ts < (select e from full)) / 1e6, 1)
union all select 'main thread: text layout', round((select coalesce(sum(dur),0) from m where name like 'TextLayout:%' and ts < (select e from full)) / 1e6, 1)
union all select 'RenderThread: frame that draws the content', round((select dur from content) / 1e6, 1)
union all select 'RenderThread: GPU pipeline compiles', round((select coalesce(sum(dur),0) from r where name like 'CreateGraphicsPipeline-Compile%' and ts < (select e from full)) / 1e6, 1)
union all select 'RenderThread: GPU pipeline compile count', (select count(*) from r where name like 'CreateGraphicsPipeline-Compile%' and ts < (select e from full))
"""

# The fling: frames are the main thread's Choreographer#doFrame slices that drew (they hold a draw-VRI).
# A lazy list also composes and measures upcoming items *between* frames (prefetch), outside any doFrame,
# so the per-frame figures are read together with the whole-window ones: judging a lane by the frame alone
# would credit it with work it moved out of the frame.
FLING_SQL = PRELUDE + """
,frames as (select f.id, f.ts, f.dur from m f where f.name like 'Choreographer#doFrame%' and f.depth = 0
            and exists (select 1 from m c where c.ts >= f.ts and c.ts < f.ts + f.dur and c.name like 'draw-VRI%')),
w as (select min(ts) a, max(ts + dur) b, count(*) n from frames),
parts as (select f.id fid, c.name, c.dur from frames f join m c on c.ts >= f.ts and c.ts < f.ts + f.dur and c.depth >= 1),
win as (select name, dur, depth from m where ts >= (select a from w) and ts <= (select b from w))
select 'frames' k, (select n from w) v
union all select 'in the frame: doFrame ms, mean', round(avg(dur) / 1e6, 2) from frames
union all select 'in the frame: doFrame ms, p90', round((select dur from frames order by dur limit 1 offset (select cast(0.9 * count(*) as int) from frames)) / 1e6, 2)
union all select 'in the frame: doFrame ms, p99', round((select dur from frames order by dur limit 1 offset (select cast(0.99 * count(*) as int) from frames)) / 1e6, 2)
union all select 'in the frame, per frame ms: ' || n, round(sum(dur) / 1e6 / (select n from w), 3) from (
   select 'animation (recomposition, effects)' n, dur from parts where name = 'Recomposer:animation'
   union all select 'measure and layout', dur from parts where name = 'AndroidOwner:measureAndLayout'
   union all select 'draw recording (draw-VRI)', dur from parts where name like 'draw-VRI%') group by n
union all select 'in the frame, per frame ms: RenderThread draw (DrawFrames)', round((select sum(dur) from r where name like 'DrawFrames%' and ts >= (select a from w) and ts <= (select b from w)) / 1e6 / (select n from w), 3)
union all select 'whole window, per frame ms: main thread busy (all top-level slices)', round((select sum(dur) from win where depth = 0) / 1e6 / (select n from w), 3)
union all select 'whole window, per frame ms: of which outside doFrame (prefetch, input, other)', round(((select sum(dur) from win where depth = 0) - (select sum(dur) from frames)) / 1e6 / (select n from w), 3)
union all select 'whole window, per frame ms: Recomposer:recompose', round((select coalesce(sum(dur), 0) from win where name = 'Recomposer:recompose') / 1e6 / (select n from w), 3)
union all select 'whole window, per frame count: Compose:recompose passes', round((select count(*) from win where name = 'Compose:recompose') * 1.0 / (select n from w), 2)
union all select 'whole window, per frame count: text layouts (TextLayout:initLayout)', round((select count(*) from win where name = 'TextLayout:initLayout') * 1.0 / (select n from w), 2)
union all select 'whole window, per frame ms: text layout', round((select coalesce(sum(dur), 0) from win where name = 'TextLayout:initLayout') / 1e6 / (select n from w), 3)
"""


def traces(directory, prefix):
    return sorted(pathlib.Path(directory).rglob(f"{prefix}_iter*.perfetto-trace"))


def medians(tp, files, sql):
    runs = [{r["k"]: float(r["v"]) for r in query(tp, f, sql) if r["v"] not in ("", None)} for f in files]
    keys = list(runs[0])
    for f, run in zip(files, runs):
        missing = [k for k in keys if k not in run]
        if missing:
            sys.exit(f"{f} has no value for {missing}: the trace lacks a slice the query needs")
    return {k: statistics.median(run[k] for run in runs) for k in keys}, len(runs)


def table(title, a_name, b_name, a, b, unit=""):
    print(f"| {title} | {LABEL[a_name]} | {LABEL[b_name]} | Difference |")
    print("| --- | ---: | ---: | ---: |")
    for k in a:
        if k not in b:
            continue
        d = b[k] - a[k]
        fmt = (lambda x: f"{x:.0f}") if k in ("frames", "frames drawn before that", "RenderThread: GPU pipeline compile count") else (lambda x: f"{x:.3f}".rstrip("0").rstrip("."))
        print(f"| {k} | {fmt(a[k])} | {fmt(b[k])} | {'+' if d > 0 else ''}{fmt(d)} |")
    print()


def esc(t):
    return t.replace("&", "&amp;").replace("<", "&lt;")


def startup_svg(path, rows):
    """A timeline per lane: launch to full display, in the four stretches every lane has."""
    stretches = [("process and app start", "bindApplication ends", "#9aa5b1"),
                 ("activity create and resume", "first frame starts", "#6c8ebf"),
                 ("first frame to the content reaching the GPU", "document drawn on screen (RenderThread frame with content) starts", "#e0a030"),
                 ("GPU draw, until full display", "full display (end of the first frame drawn after it)", "#5aa469")]
    top, row_h, left, width = 64, 78, 120, 760
    end = max(v["full display (end of the first frame drawn after it)"] for _, v in rows) * 1.06
    scale = width / end
    h = top + row_h * len(rows) + 70
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {left + width + 30} {h}" width="{left + width + 30}" height="{h}" font-family="system-ui,sans-serif" font-size="13">',
           f'<rect width="100%" height="100%" rx="8" fill="#fafafa" stroke="#ddd"/>',
           '<text x="16" y="26" font-size="15" font-weight="600" fill="#222">Cold start: milliseconds from the launch intent (median of the iterations)</text>']
    for t in range(0, int(end), 50):
        x = left + t * scale
        out.append(f'<line x1="{x:.1f}" y1="{top - 8}" x2="{x:.1f}" y2="{top + row_h * len(rows) - 14}" stroke="#e4e4e4"/><text x="{x:.1f}" y="{top - 14}" text-anchor="middle" fill="#777" font-size="11">{t}</text>')
    for i, (name, v) in enumerate(rows):
        y = top + i * row_h
        out.append(f'<text x="{left - 10}" y="{y + 22}" text-anchor="end" fill="#222" font-weight="600">{esc(LABEL[name])}</text>')
        prev = 0.0
        for label, key, color in stretches:
            cur = v[key]
            x, w = left + prev * scale, max((cur - prev) * scale, 1)
            out.append(f'<rect x="{x:.1f}" y="{y + 4}" width="{w:.1f}" height="30" fill="{color}"><title>{esc(label)}: {prev:.0f} to {cur:.0f} ms</title></rect>')
            if w > 34:
                out.append(f'<text x="{x + w / 2:.1f}" y="{y + 24}" text-anchor="middle" fill="#111" font-size="11">{cur - prev:.0f}</text>')
            prev = cur
        out.append(f'<text x="{left + prev * scale + 6:.1f}" y="{y + 24}" fill="#222" font-weight="600">{prev:.0f} ms</text>')
        out.append(f'<text x="{left}" y="{y + 52}" fill="#555" font-size="11">frames drawn before full display: {v["frames drawn before that"]:.0f}; GPU pipeline compiles: {v["RenderThread: GPU pipeline compiles"]:.0f} ms</text>')
    lx = left
    for label, _, color in stretches:
        out.append(f'<rect x="{lx}" y="{h - 34}" width="12" height="12" fill="{color}"/><text x="{lx + 17}" y="{h - 24}" fill="#333" font-size="11">{esc(label)}</text>')
        lx += 20 + len(label) * 5.6
    out.append("</svg>")
    pathlib.Path(path).write_text("\n".join(out))


def fling_svg(path, rows):
    """Grouped bars, per frame of the fling, in ms: where each lane spends it."""
    groups = [("main thread busy, whole window", "whole window, per frame ms: main thread busy (all top-level slices)"),
              ("of which in the frame (doFrame)", "in the frame: doFrame ms, mean"),
              ("of which outside it (prefetch, input)", "whole window, per frame ms: of which outside doFrame (prefetch, input, other)"),
              ("RenderThread draw", "in the frame, per frame ms: RenderThread draw (DrawFrames)")]
    colors = ["#6c8ebf", "#e0a030", "#5aa469", "#b56cbf"]
    left, bar_h, gap, width = 250, 18, 26, 520
    top = 56
    peak = max(v[k] for _, v in rows for _, k in groups) * 1.12
    h = top + len(groups) * (len(rows) * bar_h + gap) + 40
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {left + width + 90} {h}" width="{left + width + 90}" height="{h}" font-family="system-ui,sans-serif" font-size="13">',
           '<rect width="100%" height="100%" rx="8" fill="#fafafa" stroke="#ddd"/>',
           '<text x="16" y="26" font-size="15" font-weight="600" fill="#222">Fling: milliseconds per frame (median of the iterations)</text>']
    y = top
    for gi, (label, key) in enumerate(groups):
        out.append(f'<text x="{left - 10}" y="{y + bar_h}" text-anchor="end" fill="#222">{esc(label)}</text>')
        for ri, (name, v) in enumerate(rows):
            val = v[key]
            w = val / peak * width
            shade = colors[gi] if ri == 0 else colors[gi] + "aa"
            out.append(f'<rect x="{left}" y="{y + ri * bar_h}" width="{w:.1f}" height="{bar_h - 3}" fill="{colors[gi]}" opacity="{1 if ri else 0.55}"><title>{esc(LABEL[name])}: {val:.2f} ms</title></rect>')
            out.append(f'<text x="{left + w + 6:.1f}" y="{y + ri * bar_h + 12}" fill="#333" font-size="11">{val:.2f} · {esc(LABEL[name])}</text>')
        y += len(rows) * bar_h + gap
    out.append("</svg>")
    pathlib.Path(path).write_text("\n".join(out))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("directory")
    ap.add_argument("--a", default="composeUi", choices=LANES)
    ap.add_argument("--b", default="livery", choices=LANES)
    ap.add_argument("--only", choices=["startup", "fling"])
    ap.add_argument("--svg", help="a directory to write startup-timeline.svg and fling-per-frame.svg into")
    args = ap.parse_args()
    tp = find_processor()
    for which, idx, sql, title in (("startup", 0, STARTUP_SQL, "Cold start, ms from the launch intent"),
                                   ("fling", 1, FLING_SQL, "Fling, per frame")):
        if args.only and args.only != which:
            continue
        a_files, b_files = traces(args.directory, LANES[args.a][idx]), traces(args.directory, LANES[args.b][idx])
        if not a_files or not b_files:
            sys.exit(f"no {which} traces for {args.a} / {args.b} under {args.directory}")
        a, na = medians(tp, a_files, sql)
        b, nb = medians(tp, b_files, sql)
        print(f"### {title} (median of {na} and {nb} iterations)\n")
        table(which, args.a, args.b, a, b)
        if args.svg:
            pathlib.Path(args.svg).mkdir(parents=True, exist_ok=True)
            (startup_svg if which == "startup" else fling_svg)(pathlib.Path(args.svg) / ("startup-timeline.svg" if which == "startup" else "fling-per-frame.svg"), [(args.a, a), (args.b, b)])


if __name__ == "__main__":
    main()
