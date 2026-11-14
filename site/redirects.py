#!/usr/bin/env python3
"""Writes a redirect-only copy of a built site: every page of <site> becomes a page at the same path
in <out> that sends the reader to the same page under <base>, keeping the #fragment.

The site moved from GitHub Pages (yschimke.github.io/livery) to livery.coo.ee; Pages now serves this
copy so links into the old address keep working. 404.html covers any path the build no longer has.

  python3 site/redirects.py build/site build/pages https://livery.coo.ee/
"""
import html
import pathlib
import shutil
import sys

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Livery has moved</title>
<link rel="canonical" href="{url}">
<meta http-equiv="refresh" content="0; url={url}">
<meta name="robots" content="noindex">
<script>location.replace({js} + location.hash)</script>
</head>
<body>
<p>The Livery documentation has moved to <a href="{url}">{url}</a>.</p>
</body>
</html>
"""

# GitHub Pages serves 404.html for a path it has no file for, at that path under /livery/.
NOT_FOUND = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Livery has moved</title>
<meta name="robots" content="noindex">
<script>location.replace({js} + location.pathname.replace(/^\\/livery\\//, "") + location.search + location.hash)</script>
</head>
<body>
<p>The Livery documentation has moved to <a href="{url}">{url}</a>.</p>
</body>
</html>
"""


def stub(template, url):
    js = '"' + url.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return template.format(url=html.escape(url), js=js)


def main(site, out, base):
    site, out = pathlib.Path(site), pathlib.Path(out)
    base = base.rstrip("/") + "/"
    if out.exists():
        shutil.rmtree(out)
    count = 0
    for path in sorted(site.rglob("*.html")):
        rel = path.relative_to(site).as_posix()
        url = base + ("" if rel == "index.html" else rel)
        dest = out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(stub(PAGE, url))
        count += 1
    (out / "404.html").write_text(stub(NOT_FOUND, base))
    print(f"{count} redirects -> {base}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
