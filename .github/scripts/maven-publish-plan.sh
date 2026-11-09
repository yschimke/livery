#!/usr/bin/env bash
# Decide which modules a release actually has to publish.
#
# Every Livery release used to upload all of its library coordinates at the tag, whether or not a
# byte of them changed. Each coordinate-publish counts against the org-wide Maven Central file-count
# limit that all of yschimke's publishing repositories share (yschimke/compose-ai-tools#4772), and
# the Kotlin Multiplatform modules here multiply that: `livery-client` alone ships Android, JVM,
# Wasm and two Apple variants.
#
# Ported from yschimke/rc-players (which ported it from compose-preview-daemon); the rules below are
# theirs. What differs is how modules are found: Livery lists its published modules in the root
# build.gradle.kts (`published`), each at `ee.schimke.composeai:<project name>`, and has no
# build-logic. `:livery-bom` is left out: the build publishes it whenever anything else publishes.
#
# A module is published when:
#
#   1. a file under it changed since the tag IT last published at (not since the last release —
#      a module skipped for three releases is compared against its own baseline, so nothing is
#      ever missed by a gap), other than in a test source set (see TEST_SOURCE), or
#   2. a module it depends on is being published, or
#   3. a shared build input changed, which can move every artifact at once, or
#   4. the version catalog changed an entry used by a build script among its inputs (a catalog
#      entry that a shared build file uses is rule 3 instead, and publishes everything).
#
# Rules 3 and 4 follow compose-preview-daemon's copy of this script (#193, #194), measured against
# this repository's releases since the plan landed (v1.64.0..v2.0.3, where 10 of 16 releases
# published every module):
#
#   - `build-logic/src/test/**`, whole-line comment and whitespace edits to shared Kotlin files, and
#     anything in VERIFICATION_ONLY never reach an artifact.
#   - SIBLING COORDINATES ARE FLOORS, NOT INPUTS. A catalog entry naming another
#     `ee.schimke.composeai` coordinate (compose-preview-contracts, compose-preview-daemon) is
#     ignored. A bump changes no byte built here, only the minimum version the POMs name: Gradle
#     resolves the highest one in the graph and consumers align through each repository's BOM. A
#     sibling VERSION a build script reads as a value stays an input, since it can be baked into an
#     artifact. v2.0.3 was a contracts bump and nothing else, and republished every player.
#   - Release wiring lives in `release-tasks.gradle.kts`, outside the shared set: it decides which
#     tasks run, not what they build.
#
# Dependency scans read code, not comments: a `project(":…")` named in prose is not an edge.
#
# "A file under it" means every input the module packages, not just its own directory (#512). A
# module's inputs are the directories of every project in its transitive project-dependency
# closure — followed through UNPUBLISHED projects too — plus any path outside its directory that it
# reads. Two published modules went stale on Central without this:
#
#   - `rc-player-wasm-dist` zips `:rc-player-wasm`, which is unpublished and depends on
#     `:rc-player-compose`. Rule 2 only walks published edges, so a compose-only release (v1.71.0,
#     v1.75.0) left Central's wasm-dist built from the previous compose code.
#   - `remote-compose-player-js-dist` packages `../remote-compose-player/dist`, a sibling directory
#     with no build file, so no project edge reaches it at all.
#
# Paths outside a project's directory are picked up from `"../…"` literals in its build file, and
# anything a build file cannot express that way is declared in EXTRA_INPUTS below. A dependency on
# a project path this script cannot resolve, or an input path that escapes the repository, makes
# every published module that reaches it publish.
#
# Rule 2 is what keeps the POMs honest, and it is deliberately coarser than it needs to be. A
# published POM names its project dependencies at *their* `project.version`, so a module may only
# be skipped while everything it depends on is also skipped; otherwise it would name a sibling
# version that was never uploaded — exactly the break compose-ai-tools shipped in v2.2.1 and paid
# for in yschimke/wear-m3-catalog#350. Propagating on any change rather than only on an ABI change
# costs about 6 percentage points (73% -> 67%) and needs no assumption about binary compatibility;
# tighten it only when the modules carry ABI dumps to prove the surface held.
#
# Every uncertainty resolves to "publish". Central refuses a second upload of a version, so an
# unnecessary publish costs quota while a wrongly-skipped one is unrepairable.
#
# Usage: maven-publish-plan.sh --head <ref> [--manifest <path>]
# Output: one artifact id per line, on stdout. Diagnostics go to stderr.
set -euo pipefail

HEAD_REF=""
MANIFEST=""
WRITE_MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --head) HEAD_REF="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --write-manifest) WRITE_MANIFEST="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$HEAD_REF" ] || { echo "--head is required" >&2; exit 2; }
[ -z "$MANIFEST" ] || [ -f "$MANIFEST" ] || { echo "no manifest at $MANIFEST" >&2; exit 2; }

python3 - "$HEAD_REF" "$MANIFEST" "$WRITE_MANIFEST" <<'PY'
import json, os, re, subprocess, sys, collections, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor

GROUP_PATH = "ee/schimke/composeai"
CENTRAL = "https://repo1.maven.org/maven2"

head, manifest_path, write_manifest_path = sys.argv[1], sys.argv[2], sys.argv[3]

def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True).stdout

settings = open("settings.gradle.kts", encoding="utf-8").read()
# `\s*=\s*` rather than a literal " = ": ktfmt wraps a long assignment onto the next line, and
# `:third-party-remote-compose-player-dist` is wrapped. A regex that missed it fell back to
# deriving the directory from the project path, which does not exist on disk — and the module then
# vanished from the plan without a word.
dirs = dict(
    re.findall(r'project\("(:[^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)', settings)
)
paths = re.findall(r'^include\("(:[^"]+)"\)', settings, re.M)
# The published projects: the keys of the root build's `published = mapOf(":livery-…" to (…), …)`.
root_build = open("build.gradle.kts", encoding="utf-8").read()
published_block = re.search(r"val published = mapOf\((.*?)\n\)", root_build, re.S)
if not published_block:
    print("  build.gradle.kts declares no `val published = mapOf(…)`", file=sys.stderr)
    sys.exit(2)
published = set(re.findall(r'^\s*"(:[^"]+)"\s+to\b', published_block.group(1), re.M))
if not published:
    print("  build.gradle.kts `published` lists no project", file=sys.stderr)
    sys.exit(2)

# Paths a published module packages that neither its own directory, its project dependencies nor a
# `"../…"` literal in its build file reveal. Keyed by artifact id; repository-relative directories.
# The js-dist entry is also found by the literal scan — it is declared here as well so the input
# survives a rewrite of that build file into a form the scan cannot read.
EXTRA_INPUTS = {}

modules = {}   # artifactId -> directory
deps = {}      # artifactId -> [artifactId]
path_to_id = {}
proj_dir = {}      # project path -> directory, for EVERY included project, published or not
proj_deps = {}     # project path -> [project path], every `project(":…")` its build file names
proj_inputs = {}   # project path -> [directory], `"../…"` paths outside its own directory
def code_only(text):
    """`text` without `//` line comments and `/* */` block comments.

    A dependency named in a comment is not a dependency: compose-preview-daemon's displayfilter
    connector quoted `api(project(":daemon:core"))` in prose, and this scan read it as an edge that
    republished the connector with every daemon-core change. `//` only counts after whitespace or at
    the start of a line, so a URL in a string survives.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"(^|\s)//[^\n]*", r"\1", text)

for p in paths:
    d = dirs.get(p, p.lstrip(":").replace(":", "/"))
    try:
        text = open(d + "/build.gradle.kts", encoding="utf-8").read()
    except OSError as e:
        # Never skip quietly. A module this script cannot read is a module it cannot classify, and
        # the whole point of the plan is that a wrongly-skipped publish is unrepairable — Central
        # refuses a second upload of a version. Failing here costs a red release job; guessing
        # costs a coordinate that never shipped.
        print(f"  cannot read {p}'s build file at {d}: {e}", file=sys.stderr)
        sys.exit(2)
    proj_dir[p] = d
    code = code_only(text)
    # Every `project(":…")` reference counts, not only dependency declarations: `:rc-player-wasm`
    # reaches `:rc-player-compat-tests`' outputs through `project(...).layout`, and a coarser edge
    # can only over-publish.
    proj_deps[p] = sorted(set(re.findall(r'project\("(:[^"]+)"\)', code)))
    proj_inputs[p] = sorted(
        {os.path.normpath(os.path.join(d, lit)) for lit in re.findall(r'"(\.\./[^"]*)"', code)}
        # `rootProject.file("samples/…")` reads a repository path as directly as a `../` literal.
        | {os.path.normpath(lit) for lit in re.findall(r'rootProject\.file\(\s*"([^"]+)"', code)}
    )
    if p not in published or p == ":livery-bom":
        continue
    # The root build publishes each listed project at `ee.schimke.composeai:<project name>`.
    aid = p.rsplit(":", 1)[-1]
    modules[aid] = d
    path_to_id[p] = aid
    deps[aid] = proj_deps[p]
# Dependencies are collected as PROJECT PATHS above and mapped to artifact ids here, once every
# module has been seen — the same reason the ids are read from the declarations rather than derived.
deps = {a: [path_to_id[d] for d in ds if d in path_to_id] for a, ds in deps.items()}

def module_inputs(project_path):
    """(every directory `project_path`'s artifact can be built from, the project directories among
    them), or (None, None) if one is unresolvable.

    Walks project dependencies transitively through published and unpublished projects alike, so
    an unpublished middle module (`:rc-player-wasm`) cannot hide a change from the published module
    that packages it. None means "cannot tell", which the caller resolves to "publish".
    """
    aid = path_to_id[project_path]
    seen, stack, inputs = set(), [project_path], set(EXTRA_INPUTS.get(aid, ()))
    projects = set()
    while stack:
        q = stack.pop()
        if q in seen:
            continue
        seen.add(q)
        if q not in proj_dir:
            print(f"  {aid}: depends on {q}, which settings.gradle.kts does not include; "
                  "publishing", file=sys.stderr)
            return None, None
        inputs.add(proj_dir[q])
        projects.add(proj_dir[q])
        inputs.update(proj_inputs[q])
        stack.extend(proj_deps[q])
    for i in inputs:
        if i == ".." or i.startswith("../") or os.path.isabs(i):
            print(f"  {aid}: input {i} is outside the repository; publishing", file=sys.stderr)
            return None, None
    return sorted(inputs), sorted(projects)

def central_release(aid):
    """The newest version of `aid` on Central, or None if it has never published there.

    `<release>` rather than `<latest>`: `latest` can name a snapshot on repositories that carry
    them, and a baseline that is not a real release would diff against a tag that does not exist.
    """
    url = f"{CENTRAL}/{GROUP_PATH}/{aid}/maven-metadata.xml"
    try:
        # Central rate-limits bursts (HTTP 429). A 429 would otherwise read as "never published" and
        # republish a module for nothing, so wait and ask again a few times first.
        for attempt in range(5):
            try:
                with urllib.request.urlopen(url, timeout=30) as response:
                    body = response.read().decode("utf-8", "replace")
                break
            except urllib.error.HTTPError as e:
                if e.code != 429 or attempt == 4:
                    raise
                import time
                time.sleep(2 * (attempt + 1))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None  # never published
        print(f"  {aid}: Central said {e.code}; publishing", file=sys.stderr)
        return None
    except Exception as e:  # noqa: BLE001 - any failure resolves to "publish"
        print(f"  {aid}: could not reach Central ({e}); publishing", file=sys.stderr)
        return None
    m = re.search(r"<release>([^<]+)</release>", body)
    return m.group(1) if m else None


if manifest_path:
    recorded = json.load(open(manifest_path, encoding="utf-8"))["modules"]
    print(f"  baseline: {manifest_path} ({len(recorded)} entries)", file=sys.stderr)
else:
    # Eight at a time: 69 sequential round-trips is most of this script's wall clock, and Central
    # serves these as static files.
    with ThreadPoolExecutor(max_workers=8) as pool:
        found = dict(zip(sorted(modules), pool.map(central_release, sorted(modules))))
    recorded = {aid: v for aid, v in found.items() if v}
    print(f"  baseline: Maven Central ({len(recorded)} of {len(modules)} coordinates)",
          file=sys.stderr)

if write_manifest_path:
    with open(write_manifest_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "_comment": "The version each coordinate is published at on Maven Central. "
                            "Resolved at release time by .github/scripts/maven-publish-plan.sh "
                            "and NOT committed - Central is the source of truth.",
                "modules": dict(sorted(recorded.items())),
            },
            f,
            indent=2,
        )
        f.write("\n")


# A shared build input can change any artifact, so it opens the gate for everything — with three
# narrowings, each of which falls back to "everything" whenever it is unsure (compose-ai-tools#5576):
#
#   a. test-only paths under build-logic/ never reach a published artifact;
#   b. an edit to a shared Kotlin file that only adds, removes or re-indents `//` comment lines and
#      blank lines leaves every artifact byte-identical;
#   c. a version-catalog change publishes the modules whose build scripts use a changed entry,
#      rather than all of them — POMs name catalog versions, so those consumers must still publish.
# Verification-only build files: files that register checks and change no artifact. None yet; kept
# so one can say so here rather than republishing every module.
VERIFICATION_ONLY = set()
# The group every sibling repository publishes under. See "SIBLING COORDINATES" in the header.
SIBLING_GROUP = "ee.schimke.composeai"

SHARED = re.compile(r"^(build-logic/|gradle/|gradlew|settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$)")
NOT_SHARED = re.compile(r"^build-logic/src/(test|testFixtures|functionalTest|integrationTest)/")
SHARED_KOTLIN = re.compile(r"^(build-logic/.*\.kts?|settings\.gradle\.kts|build\.gradle\.kts)$")
CATALOG = "gradle/libs.versions.toml"

def show(rev, path):
    """`path` at `rev`, or None when it does not exist there."""
    r = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None

def code_lines(text):
    """The lines of a Kotlin file with blank lines, `//` comment lines and indentation dropped."""
    return [s for s in (line.strip() for line in text.splitlines()) if s and not s.startswith("//")]

def comment_only(tag, path):
    """Did `path` change between `tag` and head in whole-line `//` comments and whitespace only?

    Deliberately narrow. A trailing `// comment` after code, a `/* block */` comment and anything
    else count as a real change. A raw string (`\"\"\"`) is the one place a line starting with `//`
    is not a comment, so a file containing one is never judged comment-only.
    """
    old, new = show(tag, path), show(head, path)
    if old is None or new is None or '"""' in old or '"""' in new:
        return False
    return code_lines(old) == code_lines(new)

def load_catalog(rev):
    raw = show(rev, CATALOG)
    if raw is None:
        return None
    try:
        import tomllib
        data = tomllib.loads(raw)
    except Exception:  # noqa: BLE001 - no tomllib, or a catalog it cannot read: publish everything
        return None
    if set(data) - {"versions", "libraries", "plugins", "bundles"}:
        return None
    if not all(isinstance(v, dict) for v in data.values()):
        return None
    return data

def version_ref(entry):
    if isinstance(entry, dict) and isinstance(entry.get("version"), dict):
        return entry["version"].get("ref")
    return None

def catalog_changes(tag):
    """Every catalog entry that moved between `tag` and head, or None when that is not certain.

    Returned as accessor paths below `libs.`: `foo-bar`, `plugins.foo`, `bundles.foo`,
    `versions.foo`. A changed version ref moves every library and plugin that uses it; a changed
    library moves every bundle that contains it.
    """
    old, new = load_catalog(tag), load_catalog(head)
    if old is None or new is None:
        return None
    ov, nv = old.get("versions", {}), new.get("versions", {})
    refs = {k for k in set(ov) | set(nv) if ov.get(k) != nv.get(k)}
    changed = {f"versions.{k}" for k in refs}
    moved_libs = set()
    for section, prefix in (("libraries", ""), ("plugins", "plugins.")):
        o, n = old.get(section, {}), new.get(section, {})
        for k in set(o) | set(n):
            if o.get(k) != n.get(k) or version_ref(o.get(k)) in refs or version_ref(n.get(k)) in refs:
                changed.add(prefix + k)
                if section == "libraries":
                    moved_libs.add(k)
    o, n = old.get("bundles", {}), new.get("bundles", {})
    for k in set(o) | set(n):
        if o.get(k) != n.get(k) or set(o.get(k) or ()) & moved_libs or set(n.get(k) or ()) & moved_libs:
            changed.add(f"bundles.{k}")
    return changed - sibling_entries(old, new)


def sibling_entries(old, new):
    """Catalog entries that only ever name a sibling coordinate. See "SIBLING COORDINATES".

    A library or plugin is a sibling when its coordinate is in SIBLING_GROUP at both revisions; a
    version is one when every library and plugin that refers to it, at both revisions, is, AND no
    build script reads it as a value (`libs.versions.foo`, `findVersion("foo")`). A version read as
    a value can be baked into an artifact -- compose-ai-tools' Gradle plugin embeds the daemon
    version it launches -- so it stays an input. A version also used by anything else stays an
    input, and so does a bundle.
    """
    def coordinate(section, entry):
        if isinstance(entry, str):
            return entry
        if not isinstance(entry, dict):
            return ""
        if section == "plugins":
            return entry.get("id", "")
        return entry.get("module") or f"{entry.get('group', '')}:{entry.get('name', '')}"

    def is_sibling(section, entry):
        c = coordinate(section, entry)
        return c.startswith(SIBLING_GROUP + ":") or (section == "plugins" and c.startswith(SIBLING_GROUP + "."))

    out = set()
    refs = collections.defaultdict(list)
    for cat in (old, new):
        for section, prefix in (("libraries", ""), ("plugins", "plugins.")):
            for k, e in cat.get(section, {}).items():
                sib = is_sibling(section, e)
                r = version_ref(e)
                if r:
                    refs[r].append(sib)
                if sib:
                    out.add(prefix + k)
    for section, prefix in (("libraries", ""), ("plugins", "plugins.")):
        for k in set(old.get(section, {})) | set(new.get(section, {})):
            if prefix + k in out and not all(
                is_sibling(section, cat.get(section, {}).get(k)) for cat in (old, new) if k in cat.get(section, {})
            ):
                out.discard(prefix + k)
    out |= {f"versions.{r}" for r, sibs in refs.items() if sibs and all(sibs) and not read_as_value(r)}
    return out


def read_as_value(version):
    """Does any build script read catalog version `version` itself, rather than through a library?"""
    dotted = re.sub(r"[-_.]", ".", version)
    accessor = re.compile(r"\blibs\.versions\." + re.escape(dotted) + r"(?![A-Za-z0-9_])", re.I)
    by_name = re.compile(r'findVersion\(\s*"' + r"[-_.]".join(map(re.escape, re.split(r"[-_.]", version))) + '"', re.I)
    for root, dirnames, names in os.walk("."):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", ".git", "node_modules")]
        for n in names:
            if n.endswith(".gradle.kts") or (n.endswith(".kt") and "build-logic" in root):
                text = normalise_script(read(os.path.join(root, n)))
                if accessor.search(text) or by_name.search(text):
                    return True
    return False

def reference_patterns(entries):
    """Regexes that find a use of any of `entries` in a build script.

    Both the generated accessor (`libs.foo.bar`, with `-` and `_` mapped to `.` as Gradle does) and
    the alias as a string (`findLibrary("foo-bar")`, `findVersion("foo")`). Case-insensitive, and a
    longer alias sharing a prefix also matches: both only ever over-publish.
    """
    pats = []
    for e in sorted(entries):
        dotted = re.sub(r"[-_.]", ".", e)
        pats.append(re.compile(r"\blibs\." + re.escape(dotted) + r"(?![A-Za-z0-9_])", re.I))
        name = e.split(".", 1)[1] if e.split(".", 1)[0] in ("versions", "plugins", "bundles") else e
        pats.append(re.compile('"' + r"[-_.]".join(map(re.escape, re.split(r"[-_.]", name))) + '"', re.I))
    return pats

def normalise_script(text):
    # ktfmt may break an accessor chain across lines; `libs\n  .foo` is `libs.foo`.
    return re.sub(r"\s*\.\s*", ".", text)

def references(text, pats):
    text = normalise_script(text)
    return next((p.pattern for p in pats if p.search(text)), None)

def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""

def shared_catalog_use(pats):
    """The shared build file that uses a changed catalog entry, if any. That entry can reach every
    module (a convention plugin's dependency, a plugin on the root classpath), so it publishes all."""
    files = ["settings.gradle.kts", "build.gradle.kts"]
    for root, dirnames, names in os.walk("build-logic"):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
        files += [os.path.join(root, n) for n in names if n.endswith((".kt", ".kts"))]
    for f in files:
        if NOT_SHARED.match(f):
            continue
        text = read(f)
        if f == "settings.gradle.kts":
            # `version("compose-remote", "1.0.0-SNAPSHOT")` in a catalog builder OVERRIDES that
            # entry (snapshot mode only); it is a write, not a use of the catalog's value.
            text = re.sub(r'\bversion\(\s*"[^"]*"\s*,', "version(", text)
        hit = references(text, pats)
        if hit:
            return f"{f} ({hit})"
    return None

script_cache = {}
def module_scripts(directory):
    if directory not in script_cache:
        texts = []
        for root, dirnames, names in os.walk(directory):
            dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", "node_modules")]
            texts += [read(os.path.join(root, n)) for n in names if n.endswith(".gradle.kts")]
        script_cache[directory] = "\n".join(texts)
    return script_cache[directory]

def uses_catalog_change(directory, pats):
    text = module_scripts(directory)
    # A script that reads a TOML file itself (a path in a string literal) is outside what the
    # accessor scan can see. A mention in a comment is not a read, and would dirty every catalog
    # change for no reason.
    return re.search(r'\.toml"', text) is not None or references(text, pats) is not None

def shared_verdict(tag, files):
    """(publish everything?, catalog patterns to test each module against)."""
    pats = None
    for f in files:
        if not SHARED.match(f):
            continue
        if NOT_SHARED.match(f):
            print(f"  {tag}: {f} is test-only; not a shared input", file=sys.stderr)
            continue
        if f in VERIFICATION_ONLY:
            print(f"  {tag}: {f} is verification-only; not a shared input", file=sys.stderr)
            continue
        if f == CATALOG:
            changes = catalog_changes(tag)
            if changes is None:
                print(f"  {tag}: could not diff {CATALOG}; publishing every module", file=sys.stderr)
                return True, None
            print(f"  {tag}: catalog entries changed: {', '.join(sorted(changes)) or '<none>'}",
                  file=sys.stderr)
            pats = reference_patterns(changes)
            hit = shared_catalog_use(pats) if pats else None
            if hit:
                print(f"  {tag}: a changed catalog entry is used by {hit}; publishing every module",
                      file=sys.stderr)
                return True, None
            continue
        if SHARED_KOTLIN.match(f) and comment_only(tag, f):
            print(f"  {tag}: {f} changed only in comments or whitespace", file=sys.stderr)
            continue
        print(f"  {tag}: shared build input {f} changed", file=sys.stderr)
        return True, None
    return False, pats

# A test source set of a Gradle project, matched against a path RELATIVE TO THAT PROJECT'S
# DIRECTORY: `src/test`, `src/functionalTest`, and the KMP `src/commonTest`, `src/jvmTest`,
# `src/iosTest`, `src/wasmJsTest`, `src/androidHostTest`… No artifact carries one, so a change that
# touches only these publishes nothing. Every `…Main` source set is published and never matches.
# `src/testFixtures` IS published (`java-test-fixtures` adds it as a variant), so it still counts.
#
# Anchored at a project directory on purpose. A `"../…"` literal or an EXTRA_INPUTS path is a
# directory a module packages wholesale (`srcDir("../vendored/src")` compiles `vendored/src/test`
# into MAIN), so nothing under one is ever filtered — only a project's own source sets are.
TEST_SOURCE = re.compile(r"^src/(?!testFixtures/)(test[A-Za-z0-9]*|[a-z][A-Za-z0-9]*Test[A-Za-z0-9]*)/")

def test_source(path, projects):
    """Is `path` inside a test source set of one of the project directories `projects`?"""
    return any(path.startswith(d + "/") and TEST_SOURCE.match(path[len(d) + 1:]) for d in projects)

def changed_since(aid, version, inputs, projects):
    """Did anything published from `inputs` move between the tag for `version` and head?"""
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        print(f"  {aid}: no tag {tag}; publishing", file=sys.stderr)
        return True
    # `:(top)` anchors each pathspec at the repository root whatever the working directory.
    # `--no-renames`: a main file moved into a test source set must still show as a deletion from
    # main, not only as an addition the test filter would then drop.
    out = git("diff", "--no-renames", "--name-only", f"{tag}..{head}", "--",
              *(f":(top){i}" for i in inputs))
    changed = [f for f in out.split("\n") if f]
    shipped = [f for f in changed if not test_source(f, projects)]
    if changed and not shipped:
        print(f"  {aid}: only test sources changed since {tag}", file=sys.stderr)
    return bool(shipped)

shared_changed = False
catalog_pats = {}  # baseline version -> patterns for the catalog entries changed since it
for version in sorted(set(recorded.values())):
    tag = f"v{version}"
    if subprocess.run(["git", "rev-parse", "--verify", "-q", tag + "^{commit}"],
                      capture_output=True).returncode != 0:
        shared_changed = True
        break
    files = [f for f in git("diff", "--no-renames", "--name-only", f"{tag}..{head}").split("\n") if f]
    shared_changed, pats = shared_verdict(tag, files)
    if shared_changed:
        break
    if pats:
        catalog_pats[version] = pats

# A module Central already has at the version being released is never in the plan, whatever else
# changed: Central refuses a second upload of a version. That is what lets a release whose publish
# failed part-way be run again (the `publish_tag` recovery) and upload only the rest.
head_version = head[1:] if re.match(r"^v[0-9]", head) else None
already = {a for a, v in recorded.items() if head_version and v == head_version}
for a in sorted(already):
    print(f"  {a}: already on Central at {head_version}; not publishing again", file=sys.stderr)

if shared_changed:
    print("  a shared build input changed; publishing every module", file=sys.stderr)
    for aid in sorted(set(modules) - already):
        print(aid)
    sys.exit(0)

dirty = set()
for p, aid in path_to_id.items():
    if aid not in recorded:
        print(f"  {aid}: never published; publishing", file=sys.stderr)
        dirty.add(aid)
        continue
    inputs, projects = module_inputs(p)
    if inputs is None or changed_since(aid, recorded[aid], inputs, projects):
        dirty.add(aid)
    elif recorded[aid] in catalog_pats and any(
        uses_catalog_change(i, catalog_pats[recorded[aid]]) for i in inputs
    ):
        # Every input directory, not only the module's own: an unpublished project it packages
        # can be the one naming the changed entry.
        print(f"  {aid}: uses a changed catalog entry; publishing", file=sys.stderr)
        dirty.add(aid)

# Rule 2: anything depending on a dirty module is dirty too, transitively.
rev = collections.defaultdict(set)
for aid, ds in deps.items():
    for d in ds:
        rev[d].add(aid)
stack = list(dirty)
while stack:
    m = stack.pop()
    for r in rev.get(m, ()):
        if r not in dirty:
            dirty.add(r)
            stack.append(r)

dirty -= already
print(f"  {len(dirty)} of {len(modules)} modules publish", file=sys.stderr)
for aid in sorted(dirty):
    print(aid)
PY
