#!/usr/bin/env bash
# Pins maven-publish-plan.sh's rules on a small repository shaped like Livery's: three published
# modules (b depends on a), an unpublished one, and the BOM, listed in the root build's `published`.
# A wrong answer here is a module that never reaches Central, or one uploaded for nothing.
set -euo pipefail
plan="$(cd "$(dirname "$0")" && pwd)/maven-publish-plan.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/repo" && cd "$work/repo"
git init -q -b main
git config user.email test@example.invalid
git config user.name test

cat > settings.gradle.kts <<'EOF'
include(":livery-a")
include(":livery-b")
include(":livery-c")
include(":livery-tool")
include(":livery-bom")
EOF
cat > build.gradle.kts <<'EOF'
// The published modules.
val published = mapOf(
  ":livery-a" to ("A" to "a"),
  ":livery-b" to ("B" to "b"),
  ":livery-c" to ("C" to "c"),
  ":livery-bom" to ("BOM" to "bom"),
)
EOF
mkdir -p gradle livery-a/src/commonMain livery-b/src/commonMain livery-c/src/commonMain livery-c/src/commonTest livery-tool livery-bom
cat > gradle/libs.versions.toml <<'EOF'
[versions]
okio = "3.0.0"

[libraries]
okio = { module = "com.squareup.okio:okio", version.ref = "okio" }
EOF
echo 'dependencies { }' > livery-a/build.gradle.kts
echo 'dependencies { api(project(":livery-a")) }' > livery-b/build.gradle.kts
echo 'dependencies { implementation(libs.okio) }' > livery-c/build.gradle.kts
echo 'dependencies { }' > livery-tool/build.gradle.kts
echo 'plugins { `java-platform` }' > livery-bom/build.gradle.kts
for m in a b c; do echo "val $m = 1" > "livery-$m/src/commonMain/$m.kt"; done
echo "val t = 1" > livery-c/src/commonTest/t.kt
git add -A && git commit -qm base && git tag v1.0.0

manifest() { # a b c versions
  printf '{"modules": {"livery-a": "%s", "livery-b": "%s", "livery-c": "%s"}}\n' "$1" "$2" "$3" > "$work/m.json"
}
failed=0
expect() { # label, expected (space separated), head
  local got
  got="$("$plan" --head "${3:-HEAD}" --manifest "$work/m.json" 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
  if [ "$got" = "$1" ]; then echo "ok - $2"; else echo "FAIL - $2: expected [$1], got [$got]"; failed=1; fi
}
change() { # file, text: commit a change (or a new file) on a branch off v1.0.0
  git checkout -q -B case v1.0.0
  printf '%s\n' "$2" >> "$1"
  git add -A && git commit -qm "case"
}

manifest 1.0.0 1.0.0 1.0.0
expect "" "nothing changed: nothing publishes"
change livery-c/src/commonMain/c.kt "val c2 = 2"
expect "livery-c" "a module's own code: that module"
change livery-a/src/commonMain/a.kt "val a2 = 2"
expect "livery-a livery-b" "a dependency: the module and its dependents"
change livery-c/src/commonTest/t.kt "val t2 = 2"
expect "" "test sources only: nothing"
change livery-tool/build.gradle.kts "// unpublished"
expect "" "an unpublished project nobody packages: nothing"
change build.gradle.kts "val extra = 1"
expect "livery-a livery-b livery-c" "the root build: everything"
change build.gradle.kts "// a comment"
expect "" "a comment in the root build: nothing"
change release-tasks.gradle.kts "val wiring = 1"
expect "" "release wiring: nothing"
git checkout -q -B case v1.0.0
sed -i 's/okio = "3.0.0"/okio = "3.1.0"/' gradle/libs.versions.toml
git commit -qam "bump okio"
expect "livery-c" "a catalog entry one module uses: that module"
# A release whose publish uploaded livery-a at 1.1.0 and then failed, run again on its tag: a shared
# change would publish everything, but Central already has livery-a at 1.1.0.
change build.gradle.kts "val extra = 1"
git tag v1.1.0
manifest 1.1.0 1.0.0 1.0.0
expect "livery-b livery-c" "a re-run of a part-published release: only what Central lacks" v1.1.0
manifest 1.0.0 1.0.0 1.0.0
printf '{"modules": {"livery-a": "1.0.0", "livery-b": "1.0.0"}}\n' > "$work/m.json"
git checkout -q -B case v1.0.0
expect "livery-c" "a module never published: it publishes"
exit $failed
