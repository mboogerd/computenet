#!/usr/bin/env bash
# Tests for acceptance-placement.sh (computenet-k1vd). `bd` is stubbed on PATH;
# each case feeds one canned `bd show --json` payload.
#
# The discrimination that matters is MISPLACED vs ABSENT: they demand opposite
# responses (move the text / write it), and collapsing them is how route 4 gets
# applied to a bead whose criteria already exist.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/acceptance-placement.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/acc-place.XXXXXX") || exit 1
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"
cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
# $2 is the bead id; the payload is a file of that name, or nothing.
[ -f "$CANNED/$2.json" ] || { echo "no such issue" >&2; exit 1; }
echo "Warning: dolt noise before the JSON"
cat "$CANNED/$2.json"
EOF
chmod +x "$ROOT/bin/bd"
CANNED="$ROOT/canned"; mkdir -p "$CANNED"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
can() { printf '[{"id":"%s","acceptance_criteria":%s,"description":%s}]\n' "$1" "$2" "$3" > "$CANNED/$1.json"; }
run() { PATH="$ROOT/bin:$PATH" CANNED="$CANNED" "$SCRIPT" "$@" 2>&1; }

can populated '"1. it works"' '"## Acceptance\nignored, the field wins"'
can heading    'null'          '"body\n## Acceptance\n- the thing holds\n"'
can donewhen   '""'            '"body\n\nDone when: the frontier is empty\n"'
can bold       'null'          '"body\n**Acceptance:** the frontier is empty\n"'
can whitespace '"   \n  "'     '"body\n## Acceptance\n- moved here\n"'
can empty      'null'          '"just a description with no criteria anywhere"'
can mention    'null'          '"the acceptance of this design was never in doubt"'

out=$(run populated); rc=$?
[ "$rc" -eq 0 ] && ok "a populated field exits 0" || bad "populated exits $rc"
[ -z "$out" ] && ok "a populated field prints nothing" || bad "populated printed: $out"

for id in heading donewhen bold whitespace; do
  out=$(run $id); rc=$?
  grep -q "^MISPLACED $id" <<<"$out" && ok "$id: MISPLACED" || bad "$id: got '$out'"
  [ "$rc" -eq 1 ] && ok "$id: exits 1" || bad "$id: exits $rc, wanted 1"
done

for id in empty mention; do
  out=$(run $id); rc=$?
  grep -q "^ABSENT    $id" <<<"$out" && ok "$id: ABSENT" || bad "$id: got '$out'"
  [ "$rc" -eq 1 ] && ok "$id: exits 1" || bad "$id: exits $rc, wanted 1"
done
# `mention` is the false-positive guard: prose that merely says "acceptance"
# is not a misfiled section, and a wrong MISPLACED sends the orchestrator
# hunting for a section that is not there.

out=$(run nosuch); rc=$?
[ "$rc" -eq 3 ] && ok "an unreadable bead exits 3, not 0" || bad "unreadable exits $rc, wanted 3"
grep -q "NOTHING was checked" <<<"$out" && ok "and says nothing was checked" || bad "silent on unreadable: $out"

out=$(run heading nosuch); rc=$?
[ "$rc" -eq 3 ] && ok "3 outranks 1 in a mixed batch" || bad "mixed batch exits $rc, wanted 3"

rc=0; run >/dev/null 2>&1 || rc=$?
[ "$rc" -eq 2 ] && ok "no args exits 2" || bad "no args exits $rc, wanted 2"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
