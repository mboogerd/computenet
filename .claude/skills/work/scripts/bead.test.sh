#!/usr/bin/env bash
# Tests for bead.sh's SPILL branch — output too large for one tool result is
# written to a file and the path printed, rather than being silently elided in
# the middle by the harness (computenet-cjfd).
#
#   .claude/skills/work/scripts/bead.test.sh
#
# Self-contained: puts a fake `bd` first on PATH, so it never touches the real
# beads workspace and the description size is set by the case, not by whatever
# bead happens to be big today.
#
# Exits 0 if all cases pass, 1 otherwise.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bead.sh"}
[ -r "$SCRIPT" ] || { echo "not readable: $SCRIPT" >&2; exit 1; }

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/bead-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }

# Fake bd: `bd show <id> --json` emits one issue whose description is
# BODY_CHARS long. Any other id exits 1 with no JSON, as the real one does.
mkdir -p "$ROOT/bin"
cat > "$ROOT/bin/bd" <<'FAKE'
#!/usr/bin/env bash
[ "$1" = show ] || exit 1
[ "$2" = known ] || { echo "no issue found" >&2; exit 1; }
body=$(head -c "${BODY_CHARS:-100}" /dev/zero | tr '\0' 'x')
printf '[{"id":"known","title":"t","status":"open","description":"%s"}]\n' "$body"
FAKE
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"
export SCRATCH="$ROOT"

echo "case 1: a small bead prints inline, no file written"
out=$(BODY_CHARS=100 bash "$SCRIPT" known 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"
grep -q '"id": "known"' <<<"$out" && ok "the bead itself is on stdout" || bad "no bead -- $out"
[ -z "$(ls "$ROOT"/bead-known.* 2>/dev/null)" ] && ok "no file written" || bad "spilled a small bead"

echo "case 2: a bead over the cap spills to a file and says so"
out=$(BODY_CHARS=40000 bash "$SCRIPT" known 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"
grep -q 'exceeds one tool result' <<<"$out" && ok "names the reason" || bad "silent -- $out"
spilled=$(grep -o "$ROOT/[^ ]*" <<<"$out")
[ -s "$spilled" ] && ok "wrote $spilled" || bad "no file"
grep -q '"id": "known"' "$spilled" && ok "the file holds the projection" || bad "file is not the bead"
[ "$(wc -c <<<"$out")" -lt 500 ] && ok "stdout stayed small" || bad "printed the body anyway"
rm -f "$ROOT"/bead-known.*

echo "case 3: a SCALAR field filter never spills, however big the bead"
out=$(BODY_CHARS=40000 bash "$SCRIPT" known -r '.status' 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"
[ "$out" = open ] && ok "the value, not a path" || bad "got: $out"

echo "case 3b: a description-sized filter DOES spill — 'field filter' is not a"
echo "         size guarantee, and a piping caller that believes it gets a path"
out=$(BODY_CHARS=40000 bash "$SCRIPT" known -r '.description' 2>&1)
grep -q 'exceeds one tool result' <<<"$out" && ok "spilled" || bad "printed 40KB inline -- ${out:0:80}"

echo "case 3c: two spills of one bead do not collide on a filename"
a=$(BODY_CHARS=40000 bash "$SCRIPT" known 2>&1 | grep -o "$ROOT/[^ ]*")
b=$(BODY_CHARS=40000 bash "$SCRIPT" known 2>&1 | grep -o "$ROOT/[^ ]*")
[ -n "$a" ] && [ "$a" != "$b" ] && ok "distinct paths" || bad "same path twice: $a / $b"

echo "case 3d: an empty result stays empty, not a blank line"
# Count BYTES off the pipe, not $( ): command substitution strips the trailing
# newline, so a `$(...)` assertion here is vacuous — it stays green when the
# blank line comes back (measured, computenet-cjfd reviewer round).
n=$(BODY_CHARS=100 bash "$SCRIPT" known -r '.nosuchfield // empty' 2>/dev/null | wc -c)
[ "$n" -eq 0 ] && ok "no output" || bad "printed $n bytes"

echo "case 4: BEAD_SPILL_BYTES moves the cap, so a caller can pipe"
out=$(BODY_CHARS=40000 BEAD_SPILL_BYTES=999999 bash "$SCRIPT" known 2>&1)
grep -q 'exceeds one tool result' <<<"$out" && bad "spilled under a raised cap" || ok "inline"

echo "case 5: an unknown id still exits nonzero"
out=$(BODY_CHARS=100 bash "$SCRIPT" nosuch -r '.status' 2>&1); rc=$?
[ $rc -ne 0 ] && ok "exit $rc" || bad "exit 0 on a missing bead -- $out"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
