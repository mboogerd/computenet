#!/usr/bin/env bash
# Tests for check-files-claim.sh. Stubs `bd` on PATH. Expect "15 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-files-claim.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/files-claim-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
# `bd` prints warnings on stdout BEFORE the JSON; the script must survive that.
echo "Warning: beads.role not set"
cat "$CTRL/show.json"
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH" CTRL="$ROOT"

pass=0; fail=0
check() { # name expected_rc expected_substr_or_empty
  local name=$1 want_rc=$2 want=$3 out rc
  out=$("$SCRIPT" computenet-x 2>/dev/null); rc=$?
  if [ "$rc" = "$want_rc" ] && { [ -z "$want" ] || [[ "$out" == *"$want"* ]]; }; then
    pass=$((pass+1))
  else
    fail=$((fail+1)); echo "FAIL: $name (rc=$rc want=$want_rc) out=<$out>"
  fi
}
bead() { # description acceptance files
  printf '[{"id":"computenet-x","description":%s,"acceptance_criteria":%s,"metadata":{"files":%s}}]\n' \
    "$(printf '%s' "$1" | jq -Rs .)" "$(printf '%s' "$2" | jq -Rs .)" "$(printf '%s' "$3" | jq -Rs .)" \
    > "$ROOT/show.json"
}

bead "must change a/b/Step.kt" "" "a/c/Other.kt"
check "names an uncovered path -> reports, exit 1" 1 "names a/b/Step.kt"

bead "must change a/b/Step.kt" "" "a/b/Step.kt,a/c/Other.kt"
check "path is covered -> silent, exit 0" 0 ""

bead "" "RetransmitStep in a/b/Step.kt carries the anchor" "a/c/Other.kt"
check "acceptance is scanned too" 1 "names a/b/Step.kt"

bead "touch the kernel module, see kernel/src" "" ""
check "prose with no extension is not path-shaped" 0 ""

bead "change a/b/Step.kt and a/b/More.kt" "" "a/b/Step.kt"
check "reports only the uncovered one" 1 "names a/b/More.kt"

# Slash-joined prose shorthand is not a path. A breakdown writes
# "Graphs.kt/Deltas.kt" meaning "Graphs.kt and Deltas.kt", and the extraction
# read the slash as a directory separator — 0 of 4 warnings on one feature
# were true, and a check whose output is reliably all-false trains the reader
# to skim it (computenet-0pd6). No repo directory carries a source extension,
# so this can never suppress a genuine gap.
bead "extend Graphs.kt/Deltas.kt for the new op" "" ""
check "slash-joined shorthand is not reported as a path" 0 ""

bead "extend Graphs.kt/Deltas.kt and add a/b/Real.kt" "" ""
check "a real path beside shorthand is still reported" 1 "names a/b/Real.kt"

bead "see a/b/Real.kt" "" ""
check "shorthand filter leaves ordinary paths alone" 1 "names a/b/Real.kt"

bead "change a/b/Step.kt" "" ""
check "empty claim -> reports" 1 "names a/b/Step.kt"

# Live beads claim directories (`.github/workflows`) and globs
# (`kernel/src/.../evolve/**`) as often as they claim files.
bead "change .github/workflows/ci.yml" "" ".github/workflows,buildSrc"
check "directory claim covers a file beneath it" 0 ""

bead "change a/b/evolve/Evolution.kt" "" "a/b/evolve/**,doc/x.md"
check "glob claim covers a file beneath it" 0 ""

bead "change a/b/evolve/Evolution.kt" "" "a/b/other/**"
check "glob claim elsewhere still reports" 1 "names a/b/evolve/Evolution.kt"

# A few beads store metadata.files as a JSON array, which jq -r renders as
# pretty-printed JSON rather than a comma-separated string.
printf '[{"id":"computenet-x","description":"change a/b/Step.kt","metadata":{"files":["a/b/Step.kt","a/c/Other.kt"]}}]\n' > "$ROOT/show.json"
check "array-shaped claim, covered" 0 ""
printf '[{"id":"computenet-x","description":"change a/b/Step.kt","metadata":{"files":["a/c/Other.kt"]}}]\n' > "$ROOT/show.json"
check "array-shaped claim, uncovered" 1 "names a/b/Step.kt"

bead "change a/b/Step.kt" "" "a/b/Step.kt"
cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "bd: no issues found" >&2
exit 1
EOF
chmod +x "$ROOT/bin/bd"
out=$("$SCRIPT" computenet-x 2>&1); rc=$?
if [ "$rc" = 0 ] && [[ "$out" == *"unreadable, skipping"* ]]; then
  pass=$((pass+1))
else
  fail=$((fail+1)); echo "FAIL: a failing bd must say so, not pass silently (rc=$rc) out=<$out>"
fi

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
