#!/usr/bin/env bash
# Tests for verify-ready.sh. Stubs `bd` on PATH. Expect "13 passed, 0 failed".
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
  show) [ -f "$CTRL/show.$2" ] || exit 1
        read -r p ty st < "$CTRL/show.$2"
        b=""; [ -f "$CTRL/base.$2" ] && read -r b < "$CTRL/base.$2"
        printf '[{"id":"%s","parent":"%s","issue_type":"%s","status":"%s","metadata":{"base_branch":"%s"}}]\n' \
          "$2" "$p" "$ty" "$st" "$b" ;;
esac
STUB
chmod +x "$ROOT/bin/bd"
# Fixture: CTRL/prstate holds the PR state `gh pr list --head <branch>` returns
# (MERGED / CLOSED / OPEN), or is empty for "no PR state available". PR state,
# not git ancestry: a squash merge leaves the branch tip a non-ancestor of main,
# so an ancestry check reports every landed branch as unmerged.
cat > "$ROOT/bin/gh" <<'STUB'
#!/usr/bin/env bash
cat "$CTRL/prstate"
STUB
chmod +x "$ROOT/bin/gh"
: > "$ROOT/prstate"
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

echo "  t62: x [P2] (closed) via blocks" > "$ROOT/deps.t7"   # no show.t7: its bd show fails
sh "$SCRIPT" t7 >/dev/null 2>&1; rc=$?
[ "$rc" -eq 3 ] && { pass=$((pass+1)); echo "  PASS own bd show failing exits 3, not a false BLOCKED"; } \
  || { fail=$((fail+1)); echo "  FAIL own bd show failing: exit $rc, wanted 3"; }

# --- metadata.base_branch staleness (computenet-osax) ---
note() { # $1 name, $2 id, $3 expected substring
  out=$(sh "$SCRIPT" "$2" 2>&1)
  case "$out" in *"$3"*) pass=$((pass+1)); echo "  PASS $1" ;;
                 *) fail=$((fail+1)); echo "  FAIL $1: $out" ;; esac
}
echo "  u1: x [P2] (closed) via blocks" > "$ROOT/deps.t6"
note "no base_branch emits no base note" t6 "READY t6"
[ "$(sh "$SCRIPT" t6 | wc -l)" -eq 1 ] && { pass=$((pass+1)); echo "  PASS silent when the field is unset"; }   || { fail=$((fail+1)); echo "  FAIL emitted a base note with no base_branch"; }

echo "feature/computenet-lioe" > "$ROOT/base.t6"
echo MERGED > "$ROOT/prstate"
note "a merged base_branch is STALE, not trusted" t6 "STALE-BASE"
# The silent-failure shape: the branch still EXISTS on origin after the merge,
# and its tip is NOT an ancestor of main, because merges here are squashes.
note "still READY despite the stale base" t6 "READY t6"

echo CLOSED > "$ROOT/prstate"
note "a closed-unmerged base_branch is STALE too" t6 "STALE-BASE"

echo OPEN > "$ROOT/prstate"
note "an open base_branch is LIVE" t6 "LIVE-BASE"

: > "$ROOT/prstate"
note "no PR state is UNCHECKED, not assumed either way" t6 "UNCHECKED-BASE"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
