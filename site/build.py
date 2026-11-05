#!/usr/bin/env python3
"""Builds the Livery documentation site: site/pages/**/*.md -> <out>/**/*.html.

Each page starts with `title:` and `order:` lines (and optionally `layout: home`), then a blank line, then Markdown.
Links between pages are written to the .md file and rewritten to .html.

  python3 site/build.py build/site
"""
import html
import pathlib
import re
import shutil
import sys

import markdown

ROOT = pathlib.Path(__file__).parent
PAGES = ROOT / "pages"
REPO = "https://github.com/yschimke/livery"

# The sidebar, in order: (section, [page path without .md]).
NAV = [
    ("Start", ["index", "getting-started", "concepts"]),
    ("Use cases", ["use-cases/ads", "use-cases/promotions", "use-cases/feeds", "use-cases/live-content",
                   "use-cases/launcher-widgets", "use-cases/wear-widgets",
                   "use-cases/notifications", "use-cases/web"]),
    ("Running it", ["hosting", "signing", "images-and-fonts", "ai-workflows", "platforms"]),
    ("Reference", ["protocol"]),
]


def read(path):
    text = path.read_text()
    head, _, body = text.partition("\n\n")
    meta = dict(line.split(": ", 1) for line in head.splitlines())
    return meta, body


def render(md_text):
    # Links to other pages are written to their .md source; the site serves .html.
    md_text = re.sub(r"\]\(([^)\s]+)\)",
                     lambda m: "](" + (m.group(1) if "://" in m.group(1) else re.sub(r"\.md(#|$)", r".html\1", m.group(1))) + ")",
                     md_text)
    out = markdown.markdown(md_text, extensions=["fenced_code", "tables", "toc", "attr_list", "sane_lists"],
                            extension_configs={"toc": {"permalink": "#"}})
    # Deployment checklists: "- [ ] step" renders as a checklist item.
    return out.replace("<li>[ ] ", '<li class="task">')


def main(out):
    out = pathlib.Path(out)
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(ROOT / "assets", out / "assets")
    templates = {name: (ROOT / f"{name}.html").read_text() for name in ("template", "home")}
    pages = {}
    for path in sorted(PAGES.rglob("*.md")):
        rel = path.relative_to(PAGES).with_suffix("").as_posix()
        pages[rel] = read(path)
    listed = [p for _, items in NAV for p in items]
    missing = [p for p in listed if p not in pages] + [p for p in pages if p not in listed]
    if missing:
        sys.exit(f"pages and NAV disagree: {missing}")
    for rel, (meta, body) in pages.items():
        depth = rel.count("/")
        prefix = "../" * depth
        nav = []
        for section, items in NAV:
            nav.append(f'<p class="nav-section">{html.escape(section)}</p><ul>')
            for item in items:
                cls = ' class="current"' if item == rel else ""
                nav.append(f'<li><a{cls} href="{prefix}{item}.html">{html.escape(pages[item][0]["title"])}</a></li>')
            nav.append("</ul>")
        # `layout: home` pages are hand-written HTML in a full-width landing layout, no sidebar.
        template = templates["home" if meta.get("layout") == "home" else "template"]
        content = body if meta.get("layout") == "home" else render(body)
        page = (template
                .replace("{{title}}", html.escape(meta["title"]))
                .replace("{{prefix}}", prefix)
                .replace("{{nav}}", "\n".join(nav))
                .replace("{{repo}}", REPO)
                .replace("{{source}}", f"{REPO}/blob/main/site/pages/{rel}.md")
                .replace("{{content}}", content))
        target = out / f"{rel}.html"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page)
    print(f"{len(pages)} pages -> {out}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/site")
