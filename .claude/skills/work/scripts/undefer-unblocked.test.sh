#!/usr/bin/env bash
# Tests for undefer-unblocked.sh. Stubs `bd` on PATH; every case gets a fresh
# control dir. Exits 0 if all cases pass.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/undefer-unblocked.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/undefer-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

cat > "$ROOT/bin/bd" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1" in
  list)
    [ -f "$CTRL/list-fail" ] && { echo "Error: database locked" >&2; exit 1; }
    case "$*" in
      *--status=closed*) cat "$CTRL/closed.json" 2>/dev/null || echo '[]' ;;
      *) lbl=""; prev=""
         for a in "$@"; do [ "$prev" = --label ] && lbl=$a; prev=$a; done
         cat "$CTRL/open-$lbl.json" 2>/dev/null || echo '[]' ;;
    esac ;;
  show) printf '[{"id":"%s","status":"%s"}]\n' "$2" "$(cat "$CTRL/status-$2" 2>/dev/null || echo deferred)" ;;
esac
exit 0
STUB
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"; }

# 1. the only blocker closed, the epic still deferred -> undefer, comment, clear marker
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^UNDEFERRED x-e" <<<"$out" \
  && grep -q "^undefer x-e" "$BD_LOG" && grep -q "^comment x-e" "$BD_LOG" \
  && grep -q "^update x-b.1 --remove-label undefers:x-e" "$BD_LOG"; } \
  && ok "a closed sole blocker un-defers its epic and spends the marker" \
  || bad "sole blocker: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# 2. a second blocker still open -> wait; nothing written
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
echo '[{"id":"y-c.4","status":"open","labels":["undefers:x-e"]}]' > "$CTRL/open-undefers:x-e.json"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^WAITING x-e on y-c.4" <<<"$out" \
  && ! grep -qE "^(undefer|comment|update) " "$BD_LOG"; } \
  && ok "an epic with a blocker still open stays deferred, markers kept" \
  || bad "waiting: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# 3. the epic was reopened by someone -> no undefer, marker still cleared
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e","other"]}]' > "$CTRL/closed.json"
echo open > "$CTRL/status-x-e"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^CLEARED x-e (open)" <<<"$out" \
  && ! grep -q "^undefer" "$BD_LOG" && grep -q "remove-label undefers:x-e" "$BD_LOG"; } \
  && ok "a no-longer-deferred epic only loses its spent markers" \
  || bad "cleared: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# 4. --dry-run reports and writes nothing
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
out=$("$SCRIPT" --dry-run 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^UNDEFERRED x-e" <<<"$out" \
  && ! grep -qE "^(undefer|comment|update) " "$BD_LOG"; } \
  && ok "--dry-run writes nothing" || bad "dry-run: rc=$rc log=$(tr '\n' '|' < "$BD_LOG")"

# 5. a failed query is exit 3, never an all-clear, and writes nothing
fixture; touch "$CTRL/list-fail"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 3 ] && ! grep -qE "^(undefer|comment|update) " "$BD_LOG"; } \
  && ok "a failed bd list exits 3 with no writes" || bad "list-fail: rc=$rc out=$out"

# 6. no markers at all -> nothing to do
fixture
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 0 ] && ! grep -qE "^(undefer|comment|update) " "$BD_LOG"; } \
  && ok "no markers, no writes" || bad "empty: rc=$rc out=$out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
