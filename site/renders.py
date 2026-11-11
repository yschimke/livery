#!/usr/bin/env python3
"""Renders the home page's screenshots from the sample catalog with livery-mcp's `render_preview`.

The tile images on the home page are real: the sample template played with its contract's
example data by the same renderer agents use. Re-run after changing a sample design:

  ./gradlew :livery-mcp:installDist :livery-capture-cmp:sampleCatalogs
  python3 site/renders.py

and commit the PNGs in site/assets/home/.
"""
import base64
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
MCP = ROOT / "livery-mcp/build/install/livery-mcp/bin/livery-mcp"
# The built sample catalog: its Compose-authored designs are captured at build time, never committed.
CATALOG = ROOT / "livery-capture-cmp/build/samples/catalog"
OUT = ROOT / "site/assets/home"

# Only real documents can be drawn: the other sample designs are placeholders until an authoring
# tool's output replaces them. (file, design, template, example, width dp, height dp, dark)
SHOTS = [
    ("tile-on", "tile-default", "in-app", "on", 200, 58, False),
    ("tile-off", "tile-default", "in-app", "off", 200, 58, False),
    ("tile-unavailable", "tile-default", "in-app", "unavailable", 200, 58, False),
]


def main():
    if not CATALOG.is_dir():
        raise SystemExit("run ./gradlew :livery-capture-cmp:sampleCatalogs first")
    proc = subprocess.Popen([str(MCP), str(CATALOG)], stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, text=True)
    ids = iter(range(1, 1000))

    def call(method, params):
        msg = {"jsonrpc": "2.0", "id": next(ids), "method": method, "params": params}
        proc.stdin.write(json.dumps(msg) + "\n")
        proc.stdin.flush()
        while True:
            reply = json.loads(proc.stdout.readline())
            if reply.get("id") == msg["id"]:
                if "error" in reply:
                    raise SystemExit(f"{method}: {reply['error']}")
                return reply["result"]

    call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                        "clientInfo": {"name": "livery-site", "version": "1"}})
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
    OUT.mkdir(parents=True, exist_ok=True)
    for name, design, template, example, w, h, dark in SHOTS:
        result = call("tools/call", {"name": "render_preview", "arguments": {
            "design": design, "template": template, "example": example,
            "width_dp": w, "height_dp": h, "density": 2, "dark": dark}})
        if result.get("isError"):
            raise SystemExit(f"{name}: {result['content']}")
        image = next(c for c in result["content"] if c["type"] == "image")
        (OUT / f"{name}.png").write_bytes(base64.b64decode(image["data"]))
        print(f"{name}.png  {w}x{h} dp")
    proc.stdin.close()
    proc.wait()


if __name__ == "__main__":
    main()
