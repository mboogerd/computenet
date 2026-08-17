#!/usr/bin/env bash
# Tests for check-files-claim.sh. Stubs `bd` on PATH. Expect "6 passed, 0 failed".
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

bead "change a/b/Step.kt" "" ""
check "empty claim -> reports" 1 "names a/b/Step.kt"

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
