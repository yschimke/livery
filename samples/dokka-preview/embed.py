#!/usr/bin/env python3
"""Add pinned RC iframe previews after explicitly mapped links in generated Dokka HTML.

The input HTML is copied without reserialization. Original links, Kotlin source and
Dokka assets remain intact. No publication, editor or network is consulted.
"""

import argparse
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlsplit, urlunsplit

SLUG = re.compile(r"[a-z0-9][a-z0-9_-]{0,63}\Z")
MOVING_REVISIONS = {"latest", "current", "main", "master", "head"}
MARKER = ".livery-dokka-preview.json"
CSS = """.rc-preview-embed { display: block; margin: 1rem 0 2rem; }
.rc-preview-embed iframe { display: block; width: 100%; border: 1px solid #8886;
  border-radius: .5rem; margin-top: .75rem; background: Canvas; }
.rc-preview-fallback { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; }
.rc-preview-fallback img { width: 160px; max-width: 100%; height: auto; }
"""


def local_origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
            or parsed.username or parsed.password or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment):
        raise ValueError("The development override must be a loopback HTTP(S) origin")
    parsed.port  # Validate malformed ports as well.
    return urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


def read_mapping(path, version, development_origin=None):
    value = json.loads(Path(path).read_text())
    if (not isinstance(value, dict) or type(value.get("schemaVersion")) is not int
            or value.get("schemaVersion") != 1 or value.get("documentationVersion") != version):
        raise ValueError("Preview mapping schema/documentation version does not match this build")
    entries = value.get("previews")
    if not isinstance(entries, list) or not entries:
        raise ValueError("At least one explicit preview mapping is required")
    override = local_origin(development_origin) if development_origin else None
    result = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Each preview mapping must be an object")
        publication, revision = entry.get("publicationId"), entry.get("revision")
        if (not isinstance(publication, str) or not SLUG.fullmatch(publication)
                or not isinstance(revision, str) or not SLUG.fullmatch(revision)
                or revision in MOVING_REVISIONS):
            raise ValueError("A preview must name a pinned publication and revision")
        url = entry.get("url")
        if not isinstance(url, str):
            raise ValueError("Preview URL must be a string")
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment
                or parsed.path != f"/p/{publication}/r/{revision}/" or urlunsplit(parsed) != url
                or re.search(r"[\x00-\x20\x7f\\]", url)):
            raise ValueError("Preview URL must be a canonical HTTPS publication revision URL")
        parsed.port
        title, height, count = entry.get("title"), entry.get("height", 420), entry.get("occurrences", 1)
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise ValueError("An accessible preview title is required")
        if type(height) is not int or not 180 <= height <= 1200 or type(count) is not int or not 1 <= count <= 100:
            raise ValueError("Invalid preview height or expected occurrence count")
        if url in result:
            raise ValueError("Duplicate preview mapping")
        canonical = override + parsed.path if override else url
        result[url] = {"canonical": canonical, "title": title, "height": height, "count": count}
    return result


def embed_markup(entry):
    canonical = html.escape(entry["canonical"], quote=True)
    title = html.escape(entry["title"], quote=True)
    return (
        f'<span class="rc-preview-embed" role="group" aria-label="{title}">'
        '<span class="rc-preview-fallback">'
        f'<a href="{canonical}" target="_blank" rel="noopener">'
        f'<img src="{canonical}thumbnail.png" alt="Static preview: {title}" loading="lazy" width="160"></a>'
        f'<a href="{canonical}" target="_blank" rel="noopener">Open interactive preview</a>'
        '</span>'
        f'<iframe src="{canonical}embed/" title="{title}" loading="lazy" '
        f'height="{entry["height"]}" referrerpolicy="no-referrer" '
        'sandbox="allow-scripts allow-same-origin allow-popups"></iframe></span>'
    )


class MappedLinks(HTMLParser):
    def __init__(self, source, mapping):
        super().__init__(convert_charrefs=False)
        self.source, self.mapping = source, mapping
        self.lines = [0]
        for match in re.finditer("\n", source):
            self.lines.append(match.end())
        self.active = None
        self.patches, self.found = [], []
        self.head_end = None

    def position(self):
        line, column = self.getpos()
        return self.lines[line - 1] + column

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "rc-preview-embed" in attrs.get("class", "").split():
            raise ValueError("Input is already transformed; start from the original Dokka HTML")
        if tag == "a":
            href = attrs.get("href", "")
            self.active = href if href in self.mapping else None

    def handle_endtag(self, tag):
        if tag == "head":
            self.head_end = self.position()
        if tag == "a" and self.active:
            end = self.source.index(">", self.position()) + 1
            self.patches.append((end, embed_markup(self.mapping[self.active])))
            self.found.append(self.active)
            self.active = None


def transform(source, mapping, style_href="livery-preview.css"):
    parsed = MappedLinks(source, mapping)
    parsed.feed(source)
    parsed.close()
    if parsed.found:
        if parsed.head_end is None:
            raise ValueError("Mapped page has no HTML head")
        parsed.patches.append((parsed.head_end, f'<link rel="stylesheet" href="{style_href}">'))
    for offset, text in sorted(parsed.patches, reverse=True):
        source = source[:offset] + text + source[offset:]
    return source, parsed.found


def generate(source, destination, mapping_path, version, development_origin=None):
    if Path(source).is_symlink() or Path(destination).is_symlink():
        raise ValueError("Input and output must not be symlinks")
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if source == destination or source in destination.parents or destination in source.parents:
        raise ValueError("Input and output must be separate, non-nested directories")
    if not source.is_dir():
        raise ValueError("Generate Dokka HTML before embedding previews")
    if destination.exists() and not (destination / MARKER).is_file():
        raise ValueError("Refusing to replace a directory not generated by this adapter")
    files = list(source.rglob("*"))
    if any(path.is_symlink() for path in files):
        raise ValueError("Generated documentation input must not contain symlinks")
    mapping = read_mapping(mapping_path, version, development_origin)
    counts = dict.fromkeys(mapping, 0)
    changed = {}
    for path in sorted(source.rglob("*.html")):
        relative = path.relative_to(source)
        style_href = "../" * len(relative.parent.parts) + "livery-preview.css"
        transformed, found = transform(path.read_text(), mapping, style_href)
        for url in found:
            counts[url] += 1
        if found:
            changed[relative] = transformed
    for url, entry in mapping.items():
        if counts[url] != entry["count"]:
            raise ValueError(f"Expected {entry['count']} KDoc links for {url}, found {counts[url]}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if (source / "livery-preview.css").exists() or (source / MARKER).exists():
        raise ValueError("Start from original Dokka HTML, without adapter output files")
    stage = Path(tempfile.mkdtemp(prefix=".dokka-preview-", dir=destination.parent))
    try:
        shutil.copytree(source, stage, dirs_exist_ok=True)
        for relative, text in changed.items():
            (stage / relative).write_text(text)
        (stage / "livery-preview.css").write_text(CSS)
        (stage / MARKER).write_text(json.dumps({"schemaVersion": 1, "documentationVersion": version}) + "\n")
        if destination.exists():
            shutil.rmtree(destination)
        stage.rename(destination)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    return {"pages": len(changed), "embeds": sum(counts.values())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--mapping", required=True, type=Path)
    parser.add_argument("--documentation-version", required=True)
    parser.add_argument("--development-origin", help="Explicit loopback-only override for local browser verification")
    args = parser.parse_args()
    try:
        print(json.dumps(generate(args.input, args.output, args.mapping, args.documentation_version, args.development_origin)))
    except (ValueError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"Cannot embed previews: {error}\n")
