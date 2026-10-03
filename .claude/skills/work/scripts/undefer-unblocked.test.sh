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
      *--label-pattern*) echo "stub: --label-pattern must not be relied on" >&2; exit 1 ;;
      *--status=closed*) cat "$CTRL/closed.json" 2>/dev/null || echo '[]' ;;
      *--parent*) cat "$CTRL/kids.json" 2>/dev/null || echo '[]' ;;
      *) lbl=""; prev=""
         for a in "$@"; do [ "$prev" = --label ] && lbl=$a; prev=$a; done
         cat "$CTRL/open-$lbl.json" 2>/dev/null || echo '[]' ;;
    esac ;;
  show) printf '[{"id":"%s","status":"%s"}]\n' "$2" "$(cat "$CTRL/status-$2" 2>/dev/null || echo deferred)" ;;
esac
exit 0
STUB
cat > "$ROOT/bin/verify-ready" <<'STUB'
#!/usr/bin/env bash
echo "verify-ready $*" >> "$BD_LOG"
[ -f "$CTRL/vr.out" ] && cat "$CTRL/vr.out"
exit "$(cat "$CTRL/vr.rc" 2>/dev/null || echo 1)"
STUB
chmod +x "$ROOT/bin/bd" "$ROOT/bin/verify-ready"
export UNDEFER_VERIFY_READY="$ROOT/bin/verify-ready"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"; }

# 1. the only blocker closed, the epic still deferred -> undefer, comment, clear
#    marker; an unmarked closed row is ignored (the filter is client-side)
fixture
echo '[{"id":"x-z.9","status":"closed","labels":["other"]},{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^UNDEFERRED x-e" <<<"$out" \
  && grep -q "^undefer x-e" "$BD_LOG" && grep -q "^comment x-e" "$BD_LOG" \
  && grep -q "^update x-b.1 --remove-label undefers:x-e" "$BD_LOG" \
  && ! grep -q "x-z.9" "$BD_LOG" && [ "$(grep -c '^UNDEFERRED' <<<"$out")" = 1 ]; } \
  && ok "a closed sole blocker un-defers its epic and spends the marker" \
  || bad "sole blocker: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# 2. a second blocker still open, no child READY -> wait; nothing written
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
echo '[{"id":"y-c.4","status":"open","labels":["undefers:x-e"]}]' > "$CTRL/open-undefers:x-e.json"
echo '[{"id":"x-e.2","status":"open"}]' > "$CTRL/kids.json"
printf 'BLOCKED x-e.2 by: y-c.4 open\n' > "$CTRL/vr.out"; echo 1 > "$CTRL/vr.rc"
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

# 7. markers remain (Y open) but X closed and a child now reads READY -> undefer;
#    only the closed item's marker is spent
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
echo '[{"id":"y-c.4","status":"open","labels":["undefers:x-e"]}]' > "$CTRL/open-undefers:x-e.json"
echo '[{"id":"x-e.2","status":"open"},{"id":"x-e.3","status":"open"}]' > "$CTRL/kids.json"
printf 'READY x-e.2\nBLOCKED x-e.3 by: y-c.4 open\n' > "$CTRL/vr.out"; echo 0 > "$CTRL/vr.rc"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^UNDEFERRED x-e" <<<"$out" && grep -q "x-e.2" <<<"$out" \
  && grep -q "^verify-ready x-e.2 x-e.3" "$BD_LOG" && grep -q "^undefer x-e" "$BD_LOG" \
  && grep -q "^update x-b.1 --remove-label" "$BD_LOG" && ! grep -q "^update y-c.4" "$BD_LOG"; } \
  && ok "a READY child un-defers the epic although a marker remains open" \
  || bad "ready child: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# 8. verify-ready could not check (exit 3) -> exit 3, no writes
fixture
echo '[{"id":"x-b.1","status":"closed","labels":["undefers:x-e"]}]' > "$CTRL/closed.json"
echo '[{"id":"y-c.4","status":"open","labels":["undefers:x-e"]}]' > "$CTRL/open-undefers:x-e.json"
echo '[{"id":"x-e.2","status":"open"}]' > "$CTRL/kids.json"
echo 3 > "$CTRL/vr.rc"
out=$("$SCRIPT" 2>&1); rc=$?
{ [ "$rc" = 3 ] && ! grep -qE "^(undefer|comment|update) " "$BD_LOG"; } \
  && ok "verify-ready's exit 3 is exit 3, not WAITING, and writes nothing" \
  || bad "vr exit 3: rc=$rc out=$out"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
