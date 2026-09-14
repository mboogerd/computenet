#!/usr/bin/env bash
# Tests for verify-ready.sh. Stubs `bd` on PATH. Expect "5 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-ready.sh"}
ROOT=$(mktemp -d "${TMPDIR:-/tmp}/verify-ready-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"
# Fixture: CTRL/deps.<id> is `bd dep list` output; CTRL/show.<id> is "parent type status".
cat > "$ROOT/bin/bd" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  dep) cat "$CTRL/deps.$3" ;;
  show) read -r p ty st < "$CTRL/show.$2" 2>/dev/null
        printf '[{"id":"%s","parent":"%s","issue_type":"%s","status":"%s"}]\n' "$2" "$p" "$ty" "$st" ;;
esac
STUB
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH" CTRL="$ROOT"

pass=0; fail=0
check() { # $1 name, $2 id, $3 expected first word
  out=$(sh "$SCRIPT" "$2" 2>&1)
  case "$out" in "$3 $2"*) pass=$((pass+1)); echo "  PASS $1" ;;
                 *) fail=$((fail+1)); echo "  FAIL $1: $out" ;; esac
}
echo "f6 task open" > "$ROOT/show.t6"
echo "f5 task closed" > "$ROOT/show.t51"; echo "e feature in_progress" > "$ROOT/show.f5"
echo "f6 task closed" > "$ROOT/show.t62"
echo "e feature closed" > "$ROOT/show.f4"; echo "f4 task closed" > "$ROOT/show.t41"
echo "e epic in_progress" > "$ROOT/show.e"; echo "e task closed" > "$ROOT/show.u1"

echo "  t51: x [P2] (closed) via blocks" > "$ROOT/deps.t6"
check "closed blocker under another UNMERGED feature blocks" t6 BLOCKED
grep -q "unmerged on f5" <<<"$(sh "$SCRIPT" t6)" && { pass=$((pass+1)); echo "  PASS names the feature"; } \
  || { fail=$((fail+1)); echo "  FAIL does not name f5"; }
echo "  t62: x [P2] (closed) via blocks" > "$ROOT/deps.t6"
check "closed blocker in the same feature is ready" t6 READY
echo "  t41: x [P2] (closed) via blocks" > "$ROOT/deps.t6"
check "closed blocker under a closed feature is ready" t6 READY
echo "  u1: x [P2] (closed) via blocks" > "$ROOT/deps.t6"
check "closed direct child of the epic is ready" t6 READY

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
