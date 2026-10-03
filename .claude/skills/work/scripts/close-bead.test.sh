#!/usr/bin/env bash
# Tests for close-bead.sh. Stubs `bd` on PATH and CLOSE_BEAD_UNDEFER with a
# fake sweep; every case gets a fresh control dir. Exits 0 if all cases pass.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/close-bead.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/close-bead-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1" in
  close)
    shift
    [ -f "$CTRL/close-fail" ] && { echo "boom" >&2; exit 1; }
    exit 0
    ;;
esac
exit 0
STUB
chmod +x "$ROOT/bin/bd"

cat > "$ROOT/bin/undefer-ok" <<'STUB'
#!/usr/bin/env bash
echo "undefer-ok $*" >> "$UNDEFER_LOG"
exit 0
STUB
cat > "$ROOT/bin/undefer-fail" <<'STUB'
#!/usr/bin/env bash
echo "undefer-fail $*" >> "$UNDEFER_LOG"
echo "undefer-unblocked: bd list failed" >&2
exit 3
STUB
chmod +x "$ROOT/bin/undefer-ok" "$ROOT/bin/undefer-fail"

export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log" \
              UNDEFER_LOG="$ROOT/c$CASE/undefer.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"; : > "$UNDEFER_LOG"; }
run() { CLOSE_BEAD_UNDEFER="$1" "$SCRIPT" "${@:2}" 2>&1; }

# 1. close succeeds -> undefer sweep runs
fixture
out=$(run "$ROOT/bin/undefer-ok" x-1); rc=$?
{ [ "$rc" -eq 0 ] && grep -qx "close x-1" "$BD_LOG" \
  && grep -q "^undefer-ok" "$UNDEFER_LOG"; } \
  && ok "close succeeds: exits 0 and the undefer sweep runs" \
  || bad "case 1: rc=$rc log=$(tr '\n' '|' < "$BD_LOG") undefer=$(tr '\n' '|' < "$UNDEFER_LOG")"

# 2. close succeeds but the undefer sweep fails -> still exits 0, warns on stderr
fixture
out=$(run "$ROOT/bin/undefer-fail" x-2); rc=$?
[ "$rc" -eq 0 ] && ok "close succeeds, sweep fails: still exits 0" \
  || bad "case 2: exits $rc, wanted 0"
has "$out" "undefer sweep FAILED" "warns on the failed sweep"
has "$out" "IS durable" "says the close itself is durable"
grep -qx "close x-2" "$BD_LOG" && ok "close still ran" || bad "close never ran"

# 3. bd close itself fails -> exits non-zero, undefer sweep never invoked
fixture; touch "$CTRL/close-fail"
out=$(run "$ROOT/bin/undefer-ok" x-3); rc=$?
[ "$rc" -ne 0 ] && ok "close failure: exits non-zero" || bad "close failure: exits 0"
has "$out" "boom" "bd's own failure text reaches the caller"
[ -s "$UNDEFER_LOG" ] && bad "undefer sweep ran after a failed close" \
  || ok "undefer sweep never ran after a failed close"

# 4. extra args pass through to bd close unchanged
fixture
out=$(run "$ROOT/bin/undefer-ok" x-4 --reason "breakdown loser of foo"); rc=$?
[ "$rc" -eq 0 ] && ok "extra args: exits 0" || bad "extra args: exits $rc"
grep -qx 'close x-4 --reason breakdown loser of foo' "$BD_LOG" \
  && ok "extra args reached bd close unchanged" \
  || bad "extra args: log=$(tr '\n' '|' < "$BD_LOG")"

# 5. usage: no bead id
fixture
out=$("$SCRIPT" 2>&1); rc=$?
[ "$rc" -eq 2 ] && ok "no args: exits 2" || bad "no args: exits $rc, wanted 2"
has "$out" "usage:" "usage is printed"

# 6. default hook: no CLOSE_BEAD_UNDEFER override resolves the sibling script
fixture
out=$(cd "$ROOT" && "$SCRIPT" x-6 2>&1); rc=$?
[ "$rc" -eq 0 ] && ok "default hook: exits 0" || bad "default hook: exits $rc, wanted 0 -- $out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
