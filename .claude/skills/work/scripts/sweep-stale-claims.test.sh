#!/usr/bin/env bash
# Tests for sweep-stale-claims.sh. Stubs `bd` and `session-holder.sh` on a
# throwaway PATH/dir, so no real bead is read or written and no network call is
# made. Expect "13 passed, 0 failed".
#
# The script had no suite until computenet-yvdl, which is when it acquired the
# one branch worth testing: a live sibling session's claim must survive the
# sweep. `assignee` is BEADS_ACTOR, unique per MACHINE, so on a box running two
# /work sessions the age rule alone cannot tell a live sibling from a crash
# leftover — and releasing a live claim puts an item back in `bd ready` where
# 5f route 3 invites the other session to take it. Every case below is the
# release/leave-alone decision; nothing here asserts wording.
#
#   .claude/skills/work/scripts/sweep-stale-claims.test.sh
#   .claude/skills/work/scripts/sweep-stale-claims.test.sh /path/to/other.sh
set -uo pipefail

SRC=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sweep-stale-claims.sh"}
[ -x "$SRC" ] || { echo "not executable: $SRC" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/sweep-stale-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

CASE=0
# fixture <rows-json> <holder-verdict> -> a stubbed dir holding a copy of the
# script beside a fake session-holder.sh, plus `bd` on PATH. The script resolves
# session-holder.sh from ITS OWN directory, so the copy is what makes the stub
# take effect.
fixture() {
  CASE=$((CASE+1))
  D="$ROOT/c$CASE"; mkdir -p "$D/bin"
  cp "$SRC" "$D/sweep.sh"; chmod +x "$D/sweep.sh"
  printf '%s' "$1" > "$D/rows.json"
  cat > "$D/session-holder.sh" <<EOS
#!/usr/bin/env bash
[ "\$1" = "--check" ] || exit 3
echo "$2"
EOS
  chmod +x "$D/session-holder.sh"
  cat > "$D/bin/bd" <<EOS
#!/usr/bin/env bash
case "\$1" in
  list) cat "$D/rows.json" ;;
  update) echo "\$@" >> "$D/bd.log" ;;
esac
EOS
  chmod +x "$D/bin/bd"
  : > "$D/bd.log"
}
run() { PATH="$D/bin:$PATH" BEADS_ACTOR=TestBox "$D/sweep.sh" "$@" 2>&1; }

OLD='2020-01-01T00:00:00Z'          # far past any --hours cutoff
row() { printf '{"id":"%s","updated_at":"%s","labels":[],"metadata":%s}' "$1" "$OLD" "$2"; }

# 1. no holder at all -> the age rule alone, exactly as before this change
fixture "[$(row i-1 '{}')]" DEAD
out=$(run); has "$out" "released: i-1" "a claim with no holder is released on age alone"

# 2/3. a LIVE sibling on the same machine survives, and is reported as held
fixture "[$(row i-2 '{"holder":"box/TestBox:99:Fri"}')]" LIVE
out=$(run)
hasnt "$out" "released: i-2"        "a LIVE holder's claim is not released"
has   "$out" "NOT released): i-2"   "a LIVE holder's claim is reported, not silent"

# 4. MINE is this very session -> equally leave-alone
fixture "[$(row i-3 '{"holder":"box/TestBox:1:x"}')]" MINE
out=$(run); hasnt "$out" "released: i-3" "a MINE holder's claim is not released"

# 5. FOREIGN is another machine's row and was never ours to release
fixture "[$(row i-4 '{"holder":"other/Box2:5:x"}')]" FOREIGN
out=$(run); hasnt "$out" "released: i-4" "a FOREIGN holder's claim is not released"

# 6/7. DEAD and STALE are the crash leftovers the sweep exists for
for v in DEAD STALE; do
  fixture "[$(row i-5 '{"holder":"box/TestBox:7:x"}')]" "$v"
  out=$(run); has "$out" "released: i-5" "a $v holder's claim is still released"
done

# 8. UNKNOWN establishes nothing, so the age rule decides — as it did before
fixture "[$(row i-6 '{"holder":"garbage"}')]" UNKNOWN
out=$(run); has "$out" "released: i-6" "an UNKNOWN holder falls through to the age rule"

# 9/10. the protection is per item: a live sibling's claim must not shield the
# dead one beside it, which is the shape a naive early-exit would produce
fixture "[$(row i-7 '{"holder":"box/TestBox:9:x"}'),$(row i-8 '{}')]" LIVE
out=$(run)
hasnt "$out" "released: i-7" "with a mixed batch the LIVE item is kept"
has   "$out" "released: i-8" "with a mixed batch the holderless item is still released"

# 11. --dry-run still releases nothing
fixture "[$(row i-9 '{}')]" DEAD
out=$(run --dry-run)
has "$out" "would release: i-9" "--dry-run reports rather than releases"
[ -s "$D/bd.log" ] && bad "--dry-run wrote to bd" || ok "--dry-run makes no bd update"

# 12. a LIVE claim must not reach `bd update` at all — the report is not enough
fixture "[$(row i-10 '{"holder":"box/TestBox:11:x"}')]" LIVE
run >/dev/null
grep -q 'i-10' "$D/bd.log" && bad "a LIVE claim reached bd update" \
                           || ok "a LIVE claim never reaches bd update"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
