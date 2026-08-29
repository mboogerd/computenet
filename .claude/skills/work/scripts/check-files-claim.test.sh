#!/usr/bin/env bash
# Tests for check-files-claim.sh. Stubs `bd` on PATH. Expect "28 passed, 0 failed".
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
# qkm8: the mention line is a pointer to verify, not a verdict — the marker
# text must say so, or readers treat every mention as a violation.
check "mention line carries the not-a-verdict marker" 1 "a MENTION, possibly read-only"

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

# A repo guardrail couples file A to file B, and the implied file appears
# NOWHERE in the bead's text — the requirement comes from a test. Two sessions
# added a Gradle module the same day and both got a green :<newmodule>:test
# followed by a red :kernel:test naming doc/ARCHITECTURE.md
# (computenet-d7qn, computenet-m9px).
bead "add include(\":oracle\") to settings.gradle.kts" "" "oracle/,settings.gradle.kts"
check "settings.gradle.kts implies doc/ARCHITECTURE.md" 1 "REQUIRES doc/ARCHITECTURE.md"

bead "add include(\":oracle\") to settings.gradle.kts" "" "oracle/,settings.gradle.kts,doc/ARCHITECTURE.md"
check "coupling satisfied -> silent" 0 ""

# The coupling must not fire on a bead that has nothing to do with it.
bead "change a/b/Step.kt" "" "a/b/Step.kt"
check "coupling is irrelevant when the trigger is absent" 0 ""

# The trigger counts when it is in the CLAIM even if the prose never says it.
bead "wire the new module in" "" "settings.gradle.kts"
check "trigger in the claim alone still implies the coupled file" 1 "REQUIRES doc/ARCHITECTURE.md"

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

# ADD-ONLY couplings (`+` prefix). The census rows over civictech/cell/data/op
# fire only when a claim entry under that package names a path that is not on
# disk yet — an ADD. Without the narrowing they fired on 11 of 12 sampled beads,
# ~9 falsely, and the guardrail line asserts REQUIRES with none of the MENTION
# line's hedge (computenet-y6zv review; computenet-0pd6's lesson).
OPDIR=kernel/src/main/kotlin/civictech/cell/data/op
bead "add a new operator" "" "$OPDIR/BrandNewCell.kt"
check "add-only fires on a claimed file that does not exist yet" 1 "REQUIRES oracle/src/test/resources/operator-inventory.txt"

bead "fix tag semantics" "" "$OPDIR/UntagCell.kt"
check "add-only silent when the claimed file already exists" 0 ""

# A DIRECTORY or glob entry names no new file. Unstripped, the raw string never
# passes `test -e`, so every glob claim fired all three rows.
bead "rework the operators" "" "$OPDIR/**"
check "add-only silent on a glob claim" 0 ""

bead "rework the operators" "" "$OPDIR/"
check "add-only silent on a directory claim" 0 ""

# The existence test is repo-root-relative, not CWD-relative: run from a
# subdirectory it must give the same answer (bd walks up on its own, so a
# CWD-relative check misfires with nothing else in the run to show it).
bead "fix tag semantics" "" "$OPDIR/UntagCell.kt"
sub_out=$(cd "$(git rev-parse --show-toplevel)/kernel" && "$SCRIPT" computenet-x 2>/dev/null); sub_rc=$?
if [ "$sub_rc" = 0 ]; then pass=$((pass+1)); else
  fail=$((fail+1)); echo "FAIL: add-only must be CWD-independent (rc=$sub_rc) out=<$sub_out>"
fi

# A new file in a DIFFERENT package must not trigger the op-package rows.
bead "add a source cell" "" "kernel/src/main/kotlin/civictech/cell/data/BrandNewSource.kt"
check "add-only does not fire outside the trigger package" 0 ""

# The OperatorCatalog row is deliberately NOT add-only: registering an operator
# is an edit to an existing file, so a mention/claim of it is the trigger.
bead "register the operator in OperatorCatalog" "" "oracle/src/main/kotlin/civictech/oracle/bind/OperatorCatalog.kt"
check "OperatorCatalog implies ReferenceModelPurityTest" 1 "REQUIRES oracle/src/test/kotlin/civictech/oracle/model/ReferenceModelPurityTest.kt"

bead "register the operator in OperatorCatalog" "" "oracle/src/main/kotlin/civictech/oracle/bind/OperatorCatalog.kt,oracle/src/test/kotlin/civictech/oracle/model/ReferenceModelPurityTest.kt"
check "OperatorCatalog coupling satisfied -> silent" 0 ""

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
