#!/usr/bin/env python3
"""Screenshots of the benchmark traces in the Perfetto UI, Compose UI and Livery side by side.

  python3 livery-baselineprofile/traces/screenshots.py <dir holding the traces> <output dir> \\
      --chrome <chromium> --node-modules <dir with playwright-core>

Writes perfetto-<scenario>-{compose-ui,livery}.png for each main benchmark (SCENARIOS: the cold start, the feed fling,
the lazy feed's scrolling, a data change, and the animated feed). For each lane it uses the iteration closest to the
median; the two lanes of a pair share one time span, one pixel size and one set of "ms" flags, so a
millisecond is the same width in both. `--only` picks scenarios by name. Only the images leave this machine: the UI is the official bundle,
downloaded once into a cache and checked against its published SHA-256 manifest, and served with the trace
from 127.0.0.1, so nothing is uploaded. Traces are never copied anywhere else. Needs node, playwright-core,
a Chromium and PIL; the Perfetto UI itself is not needed.
"""
import argparse, base64, csv, functools, hashlib, http.server, io, json, os, pathlib, shutil, statistics
import subprocess, sys, tempfile, threading, urllib.request

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE))
import compare as c  # noqa: E402

UI_VERSION = "v59.1-79997ada9"
UI_URL = f"https://ui.perfetto.dev/{UI_VERSION}/"
WIDTH = 1600


def fetch_ui(cache):
    """The official bundle, every file checked against the manifest's SHA-256."""
    root = cache / UI_VERSION
    if (root / "index.html").exists():
        return root
    root.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(urllib.request.urlopen(UI_URL + "manifest.json", timeout=60).read())["resources"]
    for path, want in manifest.items():
        data = urllib.request.urlopen(UI_URL + path, timeout=120).read()
        got = "sha256-" + base64.b64encode(hashlib.sha256(data).digest()).decode()
        if got != want:
            sys.exit(f"{path}: hash {got} does not match the manifest's {want}")
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_bytes(data)
    (root / "index.html").write_bytes(urllib.request.urlopen(UI_URL, timeout=60).read())
    return root


def rows(tp, trace, sql):
    out = subprocess.run([tp, "-q", "/dev/stdin", str(trace)], input=sql, capture_output=True, text=True).stdout
    return list(csv.DictReader(io.StringIO("\n".join(l for l in out.splitlines() if l and not l.startswith(("[", "Loading", "column "))))))


STARTUP = c.PRELUDE + """
,fd as (select min(ts) ts from slice where name like 'reportFullyDrawn() for {%'),
full as (select ts + dur as e from r where name like 'DrawFrames%' and ts >= (select ts from fd) order by ts limit 1)
select 't0' k, (select t from t0) v union all select 'end', (select e from full)
union all select 'rtid', (select th.tid from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid) where th.upid = (select upid from p) and s.name like 'DrawFrames%' limit 1)
"""
FRAMES = c.PRELUDE + """
,frames as (select f.ts, f.dur from m f where f.name like 'Choreographer#doFrame%' and f.depth = 0
            and exists (select 1 from m x where x.ts >= f.ts and x.ts < f.ts + f.dur and x.name like 'draw-VRI%'))
select 'cost' k, avg(dur) / 1e6 v from frames
union all select 'peakcost', max(dur) / 1e6 from frames
union all select 'mid', (select ts from frames order by ts limit 1 offset (select count(*) / 2 from frames))
union all select 'peak', (select ts from frames order by dur desc limit 1)
union all select 'rtid', (select th.tid from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid) where th.upid = (select upid from p) and s.name like 'DrawFrames%' limit 1)
"""

# name -> (title, class prefix of a lane's traces, kind, key that picks the median iteration, where the window sits)
SCENARIOS = {
    "startup": ("Cold start", ("DocumentBenchmark_composeUiStartup", "DocumentBenchmark_liveryStartup"), "startup", None, None),
    "feed": ("Feed fling", ("FeedScrollBenchmark_composeUi", "FeedScrollBenchmark_livery"), "frames", "cost", "mid"),
    "scrolling": ("Lazy feed, next 8 cards prepared ahead", ("FeedScrollBenchmark_composeUi", "FeedScrollBenchmark_liveryLazyAhead"), "frames", "cost", "mid"),
    "change": ("Changing a document's data", ("DocumentBenchmark_composeUiChange", "DocumentBenchmark_liveryChange"), "frames", "cost", "mid"),
    "animated": ("Animated feed (bars land at once): the longest frame", ("FeedScrollBenchmark_composeUiAnimatedNoEase", "FeedScrollBenchmark_liveryAnimated"), "frames", "peakcost", "peak"),
}


def pick(tp, files, sql, key):
    """The iteration whose `key` is closest to the median over the iterations."""
    res = [{r["k"]: float(r["v"]) for r in rows(tp, f, sql)} for f in files]
    score = [key(r) for r in res]
    med = statistics.median(score)
    i = min(range(len(score)), key=lambda k: abs(score[k] - med))
    return files[i], res[i]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("directory")
    ap.add_argument("out")
    ap.add_argument("--chrome", required=True)
    ap.add_argument("--node-modules", required=True)
    ap.add_argument("--ui-cache", default=str(pathlib.Path.home() / ".cache/livery/perfetto-ui"))
    ap.add_argument("--only", nargs="*", choices=list(SCENARIOS), help="scenarios to write (default: all)")
    args = ap.parse_args()
    from PIL import Image
    tp = c.find_processor()
    ui = fetch_ui(pathlib.Path(args.ui_cache))
    work = pathlib.Path(tempfile.mkdtemp(prefix="livery-perfetto-"))
    (work / "traces").mkdir()
    (work / "ui").symlink_to(ui.parent)
    cfgs = {}
    for scen in args.only or list(SCENARIOS):
        title, prefixes, kind, keyname, where = SCENARIOS[scen]
        cfgs[scen] = []
        for lane, prefix in zip(("composeUi", "livery"), prefixes):
            files = c.traces(args.directory, prefix)
            if not files:
                sys.exit(f"no {scen} traces for {lane} ({prefix}) under {args.directory}")
            if kind == "startup":
                f, r = pick(tp, files, STARTUP, lambda r: (r["end"] - r["t0"]) / 1e6)
            else:
                f, r = pick(tp, files, FRAMES, lambda r, k=keyname: r[k])
            name = f"{scen}-{lane}.perfetto-trace"
            shutil.copy(f, work / "traces" / name)
            if kind == "startup":
                t0 = int(r["t0"])
                start, end = t0 + 100_000_000, t0 + 250_000_000
                # The ruler shows each trace's own clock; these flags put both lanes on "ms after the launch intent".
                flags = [{"ts": str(t0 + ms * 1_000_000), "text": f"{ms} ms", "color": "#9e9e9e"} for ms in (130, 170, 210)]
                flags.append({"ts": str(int(r["end"])), "text": "full display", "color": "#188038"})
            else:
                start = int(r[where]) - (30_000_000 if where == "peak" else 2_000_000)
                end = start + 100_000_000
                flags = [{"ts": str(start + ms * 1_000_000), "text": f"+{ms} ms", "color": "#9e9e9e"} for ms in (25, 50, 75)]
            print(f"{scen} {lane}: {f.name.split('_iter')[1][:3]}", file=sys.stderr)
            cfgs[scen].append({"trace": name, "visStart": str(start), "visEnd": str(end), "out": str(work / f"{scen}-{lane}.png"),
                               "width": WIDTH, "height": 1250, "renderTid": int(r["rtid"]), "markers": flags, "lane": lane})
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a, **k):
            pass

    handler = functools.partial(Quiet, directory=str(work))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    env = dict(os.environ, CHROME=args.chrome, NODE_PATH=args.node_modules)
    pathlib.Path(args.out).mkdir(parents=True, exist_ok=True)
    for kind, cfg in cfgs.items():
        for item in cfg:
            item.update(baseUrl=base, uiVersion=UI_VERSION)
        path = work / f"{kind}.json"
        path.write_text(json.dumps(cfg))
        done = subprocess.run(["node", str(HERE / "capture.js"), str(path)], env=env, capture_output=True, text=True)
        if done.returncode != 0:
            sys.exit(f"capture failed:\n{done.stderr[-1500:]}")
        shots = {i["lane"]: Image.open(i["out"]).convert("RGB") for i in cfg}
        height = max(s.height for s in shots.values())
        for lane, shot in shots.items():
            # Same height for the pair: the shorter one is padded with the empty track area's own background.
            canvas = Image.new("RGB", (shot.width, height), shot.getpixel((shot.width - 5, shot.height - 5)))
            canvas.paste(shot, (0, 0))
            canvas = canvas.resize((1800, round(1800 * height / shot.width)), Image.LANCZOS).quantize(256, dither=Image.Dither.NONE)
            target = pathlib.Path(args.out) / f"perfetto-{kind}-{'compose-ui' if lane == 'composeUi' else 'livery'}.png"
            canvas.save(target, optimize=True)
            print(target)
    server.shutdown()
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
