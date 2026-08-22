#!/usr/bin/env bash
# Tests for claim-epic.sh. Stubs `bd` on PATH; every case gets a fresh control
# dir. Exits 0 if all cases pass. Expect "18 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/claim-epic.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/claim-epic-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"
export BEADS_ACTOR=testbox
# the subtree-hot check reads git refs: point git at an empty repo so the real
# checkout's feature branches cannot leak into the cases
git init -q "$ROOT/git"; export GIT_DIR="$ROOT/git/.git"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$BD_LOG"
case "$1" in
  update)
    for a in "$@"; do
      [ "$a" = --claim ] && [ -f "$CTRL/refuse-claim" ] \
        && { echo "Error claiming $2: issue already claimed by Other@Machine" >&2; exit 1; }
    done
    exit 0 ;;
  show) cat "$CTRL/show.json" ;;
  list) cat "$CTRL/list.json" 2>/dev/null || echo '[]' ;;
  dolt)
    case "$2" in
      push)
        n=$(cat "$CTRL/pushn" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CTRL/pushn"
        cat "$CTRL/push$n.out" 2>/dev/null || echo "push complete" ;;
      pull) cat "$CTRL/pull.out" 2>/dev/null || echo "pull complete" ;;
    esac ;;
esac
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"; }
old_show() { printf '[{"id":"computenet-e","status":"%s","assignee":"%s","updated_at":"2020-01-01T00:00:00Z"}]' "$1" "$2" > "$CTRL/show.json"; }

# 1. the SDLC epic is refused before any bd call
fixture
out=$("$SCRIPT" computenet-wpvy 2>&1); st=$?
[ "$st" = 1 ] && [ ! -s "$BD_LOG" ] && ok "SDLC epic refused, no bd call" \
  || bad "SDLC epic: exit=$st log=$(cat "$BD_LOG")"

# 2. clean claim: label + skill_version + push
fixture
out=$("$SCRIPT" computenet-e 2>&1); st=$?
if [ "$st" = 0 ] && grep -q -- "--claim" "$BD_LOG" \
   && grep -q -- "--add-label=owner:testbox" "$BD_LOG" \
   && grep -q "skill_version=" "$BD_LOG" && grep -q "dolt push" "$BD_LOG"; then
  ok "clean claim runs the full bracket"
else bad "clean claim: exit=$st log=$(tr '\n' '|' < "$BD_LOG")"; fi

# 2b. hl8x: a descendant touched within the window -> SKIP before any write
fixture
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"%s"},{"id":"computenet-e.3.2","parent":"computenet-e.3","updated_at":"%s"}]' "2020-01-01T00:00:00Z" "$now" > "$CTRL/list.json"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && grep -q "subtree is hot" <<<"$out" && grep -q "computenet-e.3.2" <<<"$out" \
  && ! grep -q -- "--claim" "$BD_LOG" \
  && ok "hot grandchild (via explicit parent) skips the epic, no claim written" \
  || bad "hot subtree: exit=$st out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# 2c. a fresh origin/feature/<epic>* tip -> SKIP
fixture
git -C "$ROOT/git" commit -q --allow-empty -m x 2>/dev/null
git -C "$ROOT/git" update-ref refs/remotes/origin/feature/computenet-e.1 HEAD
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && grep -q "feature branch tip" <<<"$out" && ! grep -q -- "--claim" "$BD_LOG" \
  && ok "fresh feature ref skips the epic" || bad "hot ref: exit=$st out=$out"
git -C "$ROOT/git" update-ref -d refs/remotes/origin/feature/computenet-e.1

# 2d. cold subtree (old child, no refs) claims normally; CLAIM_SKIP_HOT bypasses a hot one
fixture
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"2020-01-01T00:00:00Z"}]' > "$CTRL/list.json"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && grep -q -- "--claim" "$BD_LOG" && ok "cold subtree claims" || bad "cold: exit=$st out=$out"
fixture
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"%s"}]' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$CTRL/list.json"
out=$(CLAIM_SKIP_HOT=1 "$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && ok "CLAIM_SKIP_HOT=1 bypasses the hot check" || bad "bypass: exit=$st out=$out"

# 3. refusal on an open, stale epic -> takeover
fixture; touch "$CTRL/refuse-claim"; old_show open Anva@A0030
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && grep -q -- "--assignee=testbox --status=in_progress" "$BD_LOG" \
  && ok "stale open epic is taken over" || bad "takeover: exit=$st out=$out"

# 4. refusal on an open, FRESH epic -> possibly live, refuse
fixture; touch "$CTRL/refuse-claim"
printf '[{"id":"computenet-e","status":"open","assignee":"x","updated_at":"%s"}]' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$CTRL/show.json"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && ! grep -q -- "--status=in_progress" "$BD_LOG" \
  && ok "fresh open epic is refused" || bad "fresh: exit=$st out=$out"

# 5. refusal on an in_progress epic -> refuse
fixture; touch "$CTRL/refuse-claim"; old_show in_progress Anva@A0030
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && ok "in_progress epic is refused" || bad "in_progress: exit=$st out=$out"

# 6. push rejected once, still ours after pull -> recovered
fixture; old_show in_progress testbox
echo '! [rejected]  main -> main (non-fast-forward)' > "$CTRL/push1.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && grep -q "dolt pull" "$BD_LOG" && [ "$(cat "$CTRL/pushn")" = 2 ] \
  && ok "rejected push recovers via pull+push" || bad "recover: exit=$st out=$out"

# 7. push rejected once, epic lost to the other machine after pull
fixture; old_show in_progress Other@Machine
echo '! [rejected]' > "$CTRL/push1.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && grep -q "LOST RACE" <<<"$out" \
  && ok "lost race after pull is reported, exit 1" || bad "lost: exit=$st out=$out"

# 8. push rejected twice -> local-only, exit 2
fixture; old_show in_progress testbox
echo '! [rejected]' > "$CTRL/push1.out"; echo '! [rejected]' > "$CTRL/push2.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 2 ] && grep -q "LOCAL-ONLY" <<<"$out" \
  && ok "double rejection escalates, exit 2" || bad "double: exit=$st out=$out"

# --- metadata.holder: a SESSION-unique lock (computenet-83ay, computenet-yurq)
# `assignee` is BEADS_ACTOR and therefore per-MACHINE, so a live sibling and a
# crash leftover are the same row. The holder is what tells them apart.
HOLDER_SH="$(dirname "$SCRIPT")/session-holder.sh"

holder_show() { # status assignee holder
  printf '[{"id":"computenet-e","status":"%s","assignee":"%s","updated_at":"2020-01-01T00:00:00Z","metadata":{"holder":"%s"}}]' \
    "$1" "$2" "$3" > "$CTRL/show.json"
}

# A fresh claim stamps a holder, so the NEXT session has something exact to test.
fixture; old_show open ""
out=$("$SCRIPT" computenet-e 2>&1)
grep -q -- "--set-metadata holder=" "$BD_LOG" \
  && ok "a fresh claim stamps metadata.holder" \
  || bad "no holder stamped — log: $(grep set-metadata "$BD_LOG" | tr '\n' '|')"

# A LIVE holder is refused even though the recency test would have allowed the
# takeover: this is the four-concurrent-sessions case, decided exactly.
fixture
live_pid=$$; live_start=$(ps -o lstart= -p $$ | tr -s ' ' | sed 's/^ *//;s/ *$//')
holder_show open "testbox" "someone-else:$live_pid:$live_start"
touch "$CTRL/refuse-claim"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "LIVE session" <<<"$out"; } \
  && ok "a live holder is refused, not taken over" || bad "rc=$rc out=$out"

# A DEAD holder is taken over — otherwise a crashed session deadlocks the epic.
fixture
holder_show open "testbox" "someone-else:99999:Tue Jan  1 00:00:00 2020"
touch "$CTRL/refuse-claim"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "is dead" <<<"$out"; } \
  && ok "a dead holder is taken over" || bad "rc=$rc out=$out"
# bz5c: a holder minted on ANOTHER machine is FOREIGN — refused, never "dead"
fixture
holder_show open "testbox" "other-box/testbox:99999:Tue Jan  1 00:00:00 2020"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
[ "$rc" = 1 ] && grep -q "ANOTHER machine" <<<"$out" && ! grep -q "owner:" "$BD_LOG" \
  && ok "a foreign holder is refused, not taken over" || bad "foreign: rc=$rc out=$out"

# An UNEVALUABLE holder must not become an all-clear NOR a hard block: it falls
# back to the recency test that governed before, and says so.
fixture
holder_show open "testbox" "garbage"
touch "$CTRL/refuse-claim"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "could not be evaluated" <<<"$out"; } \
  && ok "an unevaluable holder falls back, loudly" || bad "rc=$rc out=$out"

# The re-check reads bd AGAIN at the claim, so a slow step 3 cannot widen the
# window (computenet-yurq). Two `show` calls is the observable of that.
fixture; old_show open ""
"$SCRIPT" computenet-e >/dev/null 2>&1
[ "$(grep -c '^show ' "$BD_LOG")" -ge 1 ] \
  && ok "the claim re-reads state from bd before writing" \
  || bad "no show at claim time — log: $(tr '\n' '|' < "$BD_LOG")"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
