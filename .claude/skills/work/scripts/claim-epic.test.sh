#!/usr/bin/env bash
# Tests for claim-epic.sh. Stubs `bd` on PATH; every case gets a fresh control
# dir. Exits 0 if all cases pass. Expect "57 passed, 0 failed".
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
    [ -f "$CTRL/update-fail" ] && { echo "Error: write refused" >&2; exit 1; }
    for a in "$@"; do
      [ "$a" = --claim ] && [ -f "$CTRL/refuse-claim" ] \
        && { echo "Error claiming $2: issue already claimed by Other@Machine" >&2; exit 1; }
    done
    exit 0 ;;
  show) [ -f "$CTRL/show-fail" ] && { echo "Error: database locked" >&2; exit 1; }
        cat "$CTRL/show.json" ;;
  list) [ -f "$CTRL/list-fail" ] && { echo "Error: database locked" >&2; exit 1; }
        cat "$CTRL/list.json" 2>/dev/null || echo '[]' ;;
  dolt)
    case "$2" in
      push)
        n=$(cat "$CTRL/pushn" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CTRL/pushn"
        cat "$CTRL/push$n.out" 2>/dev/null || echo "push complete" ;;
      pull) cat "$CTRL/pull.out" 2>/dev/null || echo "pull complete" ;;
    esac ;;
esac
EOF
cat > "$ROOT/bin/sleep" <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$CTRL/sleeps"
exit 0
EOF
chmod +x "$ROOT/bin/bd" "$ROOT/bin/sleep"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE" BD_LOG="$ROOT/c$CASE/bd.log"
            mkdir -p "$CTRL"; : > "$BD_LOG"
            # a readable epic by default: an unreadable one is NOT CHECKED
            printf '[{"id":"computenet-e","status":"open","assignee":"","updated_at":"2020-01-01T00:00:00Z"}]' > "$CTRL/show.json"; }
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

# 2e. x3f5a: a child THIS MACHINE's sweep just released is not another machine's
# activity. Step 3 runs sweep-stale-claims.sh immediately before this, and its
# releases are local-only, so without the discount every resumable epic the
# sweep cleaned is unclaimable for STALE_MIN minutes — exactly the epics the
# resume preference exists for.
fixture
now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"%s"}]' "$now" > "$CTRL/list.json"
echo "$(date +%s) computenet-e.3" > "$CTRL/swept"
out=$(CLAIM_SWEPT_FILE="$CTRL/swept" "$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && grep -q -- "--claim" "$BD_LOG" \
  && ok "a child this run's own sweep released does not make the subtree hot" \
  || bad "self-swept: exit=$st out=$out"

# ... but only within the window, and only for the ids actually recorded.
fixture
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"%s"},{"id":"computenet-e.4","parent":"computenet-e","updated_at":"%s"}]' "$now" "$now" > "$CTRL/list.json"
echo "$(date +%s) computenet-e.3" > "$CTRL/swept"
out=$(CLAIM_SWEPT_FILE="$CTRL/swept" "$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && grep -q "computenet-e.4" <<<"$out" && ! grep -q -- "--claim" "$BD_LOG" \
  && ok "an unrecorded sibling still makes the subtree hot" \
  || bad "unrecorded sibling: exit=$st out=$out"

fixture
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"%s"}]' "$now" > "$CTRL/list.json"
echo "$(( $(date +%s) - 3600 )) computenet-e.3" > "$CTRL/swept"
out=$(CLAIM_SWEPT_FILE="$CTRL/swept" "$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && grep -q "subtree is hot" <<<"$out" \
  && ok "a sweep record older than the window does not license the claim" \
  || bad "stale sweep record: exit=$st out=$out"

fixture
printf '[{"id":"computenet-e.3","parent":"computenet-e","updated_at":"%s"}]' "$now" > "$CTRL/list.json"
out=$(CLAIM_SWEPT_FILE="$CTRL/no-such-file" "$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 1 ] && grep -q "subtree is hot" <<<"$out" \
  && ok "no sweep file at all leaves the hot test exactly as it was" \
  || bad "absent sweep file: exit=$st out=$out"

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

# --- transport faults are not rejections (computenet-ckvu) ------------------
# A DNS failure says nothing about the remote's state, and clears by itself in
# seconds. Two back-to-back attempts sampled one instant of it and ended a
# whole session at step 3 with the epic claimed local-only.
DNS='Error 1105: failed to get remote db; dial tcp: lookup doltremoteapi.dolthub.com: no such host'

# 8b. a transient DNS fault clears on the retry: claimed, no pull, no escalation
fixture; old_show in_progress testbox
printf '%s\n' "$DNS" > "$CTRL/push1.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && [ "$(cat "$CTRL/pushn")" = 2 ] \
  && ok "a transient transport fault is retried, not escalated" \
  || bad "dns retry: exit=$st pushes=$(cat "$CTRL/pushn" 2>/dev/null) out=$out"
grep -q "dolt pull" "$BD_LOG" \
  && bad "a transport fault must NOT trigger the rejection recovery" \
  || ok "no pull: nothing was said about the remote's state"
[ -s "$CTRL/sleeps" ] && ok "it waits between attempts rather than resampling one instant" \
  || bad "retried with no backoff"

# 8c. the fault persists: 3 attempts, then escalate — and say it was transport
fixture; old_show in_progress testbox
printf '%s\n' "$DNS" > "$CTRL/push1.out"
printf '%s\n' "$DNS" > "$CTRL/push2.out"
printf '%s\n' "$DNS" > "$CTRL/push3.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 2 ] && [ "$(cat "$CTRL/pushn")" = 3 ] \
  && ok "a persistent transport fault escalates after 3 attempts" \
  || bad "dns persist: exit=$st pushes=$(cat "$CTRL/pushn" 2>/dev/null) out=$out"
grep -q "^ESCALATE: push failed 3x with a transport fault" <<<"$out" \
  && ok "the ESCALATION LINE itself names the fault class, not just LOCAL-ONLY" \
  || bad "escalation does not distinguish transport from rejection: $out"

# 8c2. a REJECTION whose text happens to contain 502/503/504 — a dolt progress
#      count, a base32 hash — must still take the pull path. Unanchored 5xx
#      codes turned a routinely recoverable non-fast-forward into a mislabelled
#      session-ending escalation, so rejection markers are tested FIRST.
fixture; old_show in_progress testbox
echo '! [rejected] main -> main (non-fast-forward); uploaded 502 chunks' > "$CTRL/push1.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ "$st" = 0 ] && grep -q "dolt pull" "$BD_LOG" \
  && ok "a rejection carrying '502' is a rejection, not a transport fault" \
  || bad "502-in-rejection misrouted: exit=$st out=$out"
[ ! -s "$CTRL/sleeps" ] && ok "and it is not slept on" || bad "backed off on a rejection"

# 8d. a rejection is still answered immediately — retrying it cannot help
fixture; old_show in_progress testbox
echo '! [rejected]  main -> main (non-fast-forward)' > "$CTRL/push1.out"
out=$("$SCRIPT" computenet-e 2>&1); st=$?
[ ! -s "$CTRL/sleeps" ] && ok "a rejection is not slept on" \
  || bad "backed off on a rejection: $(cat "$CTRL/sleeps")"

# --- metadata.holder: a SESSION-unique lock (computenet-83ay, computenet-yurq)
# `assignee` is BEADS_ACTOR and therefore per-MACHINE, so a live sibling and a
# crash leftover are the same row. The holder is what tells them apart.
HOLDER_SH="$(dirname "$SCRIPT")/session-holder.sh"

holder_show() { # status assignee holder [updated_at]
  printf '[{"id":"computenet-e","status":"%s","assignee":"%s","updated_at":"%s","metadata":{"holder":"%s"}}]' \
    "$1" "$2" "${4:-2020-01-01T00:00:00Z}" "$3" > "$CTRL/show.json"
}

# A fresh claim stamps a holder, so the NEXT session has something exact to test.
fixture; old_show open ""
out=$("$SCRIPT" computenet-e 2>&1)
grep -q -- "--set-metadata holder=" "$BD_LOG" \
  && ok "a fresh claim stamps metadata.holder" \
  || bad "no holder stamped — log: $(grep set-metadata "$BD_LOG" | tr '\n' '|')"

# jqxqk: a STALE-aged holder whose EPIC was written moments ago is a
# long-running session, not host residue. The hot-subtree guard cannot catch it
# — it tests descendants, not the epic's own updated_at — so the takeover below
# is the last thing between a live session and a second claimant. End-to-end
# through the REAL session-holder.sh, with HOLDER_MAX_AGE_S forcing the age.
fixture
jq_pid=$$; jq_start=$(ps -o lstart= -p $$ | tr -s ' ' | sed 's/^ *//;s/ *$//')
holder_show in_progress "testbox" "$(hostname -s)/other:$jq_pid:$jq_start" \
            "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
out=$(HOLDER_MAX_AGE_S=1 "$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "LIVE session" <<<"$out" \
  && ! grep -qE -- "--claim|--set-metadata holder=" "$BD_LOG"; } \
  && ok "an old token whose epic was just written is refused, not taken over" \
  || bad "jqxqk takeover: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# The converse must still work, or the guard above has disabled STALE takeover.
fixture
holder_show in_progress "testbox" "$(hostname -s)/other:$jq_pid:$jq_start" \
            "2020-01-01T00:00:00Z"
out=$(HOLDER_MAX_AGE_S=1 "$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "residue, taking over" <<<"$out"; } \
  && ok "an old token with an old write is still taken over" \
  || bad "STALE takeover regressed: rc=$rc out=$out"

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

# hdow: a LIVE refusal is WRITE-FREE. The recheck runs BEFORE the claim write,
# so a refused epic is left exactly as found — the post-write ordering
# claimed-then-disowned two epics per run (computenet-hdow). Stub the holder
# check to a hard LIVE so the refusal is forced regardless of local processes.
fixture
mkdir -p "$ROOT/stubbed"
cp "$SCRIPT" "$ROOT/stubbed/claim-epic.sh"
cat > "$ROOT/stubbed/session-holder.sh" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = --check ]; then echo LIVE; exit 0; fi
echo "stub-host/stub:1:now"
EOF
chmod +x "$ROOT/stubbed/claim-epic.sh" "$ROOT/stubbed/session-holder.sh"
: > "$ROOT/SKILL.md"
holder_show open "Other@Machine" "someone:123:whenever"
out=$("$ROOT/stubbed/claim-epic.sh" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "LIVE session" <<<"$out" \
  && ! grep -qE -- "--claim|--status" "$BD_LOG"; } \
  && ok "a LIVE refusal writes nothing — no --claim, no --status" \
  || bad "hdow: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# nkz3: a RELEASED epic (open, no assignee) carrying a LIVE holder is residue
# from the releasing session, not a live claim — proceed, don't refuse.
fixture
holder_show open "" "someone-else:$live_pid:$live_start"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "residue on a released epic" <<<"$out"; } \
  && ok "a live holder on a released epic is residue, claim proceeds" \
  || bad "released-epic residue: rc=$rc out=$out"

# The re-check reads bd AGAIN at the claim, so a slow step 3 cannot widen the
# window (computenet-yurq). Two `show` calls is the observable of that.
fixture; old_show open ""
"$SCRIPT" computenet-e >/dev/null 2>&1
[ "$(grep -c '^show ' "$BD_LOG")" -ge 1 ] \
  && ok "the claim re-reads state from bd before writing" \
  || bad "no show at claim time — log: $(tr '\n' '|' < "$BD_LOG")"

# ci6c5: a sequencing constraint stated in the epic's BODY refuses the claim
# before any write, and names the two ways out.
fixture
printf '[{"id":"computenet-e","status":"open","assignee":"","updated_at":"2020-01-01T00:00:00Z","description":"## 4. Scheduling\\nIt queues behind KX -> MEM2 and cannot be worked autonomously in parallel with them."}]' > "$CTRL/show.json"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "queues behind" <<<"$out" \
  && grep -q "CLAIM_BLOCKERS_CHECKED=1" <<<"$out" \
  && ! grep -qE -- "--claim|dolt push" "$BD_LOG"; } \
  && ok "a stated sequencing constraint refuses before any write or push" \
  || bad "stated blocker: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# ...and CLAIM_BLOCKERS_CHECKED=1 is the escape once the predecessors are resolved.
fixture
printf '[{"id":"computenet-e","status":"open","assignee":"","updated_at":"2020-01-01T00:00:00Z","description":"It queues behind KX -> MEM2."}]' > "$CTRL/show.json"
out=$(CLAIM_BLOCKERS_CHECKED=1 "$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--claim" "$BD_LOG"; } \
  && ok "CLAIM_BLOCKERS_CHECKED=1 proceeds past the stated-blocker refusal" \
  || bad "blockers-checked escape: rc=$rc out=$out"

# The phrase set is narrow ON PURPOSE: "depends on" matched 27 of 44 open
# epics when it was measured, so it is NOT in the set and must not refuse.
fixture
printf '[{"id":"computenet-e","status":"open","assignee":"","updated_at":"2020-01-01T00:00:00Z","description":"This depends on the operator algebra and is a prerequisite for GOS2; land only after review."}]' > "$CTRL/show.json"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--claim" "$BD_LOG"; } \
  && ok "a body saying only 'depends on'/'prerequisite'/'only after' still claims" \
  || bad "narrowness: rc=$rc out=$out"

# --- tracking umbrellas and live sessions beneath the epic -------------------
# An umbrella is never claimed: a claim would serialize its sub-epics.
fixture
printf '[{"id":"computenet-e","status":"open","assignee":"","updated_at":"2020-01-01T00:00:00Z","labels":["tracking-umbrella"]}]' > "$CTRL/show.json"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "tracking umbrella" <<<"$out" \
  && ! grep -qE -- "--claim|dolt push" "$BD_LOG"; } \
  && ok "a tracking-umbrella epic is refused before any write" \
  || bad "umbrella: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# A LIVE session holding an in_progress grandchild, however quiet (old
# updated_at, so the hot-subtree guard cannot see it), refuses the claim.
desc_rows() { # holder-of-grandchild
  printf '[{"id":"computenet-e.3","parent":"computenet-e","status":"in_progress","updated_at":"2020-01-01T00:00:00Z","metadata":{"holder":"%s"}},{"id":"computenet-e.3.2","parent":"computenet-e.3","status":"in_progress","updated_at":"2020-01-01T00:00:00Z","metadata":{"holder":"%s"}}]' \
    "someone-else:99999:Tue Jan  1 00:00:00 2020" "$1" > "$CTRL/list.json"
}
fixture; old_show in_progress testbox
desc_rows "someone-else:$live_pid:$live_start"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "live session works beneath" <<<"$out" && grep -q "computenet-e.3.2" <<<"$out" \
  && ! grep -qE -- "--claim|dolt push" "$BD_LOG"; } \
  && ok "a live holder on a quiet descendant refuses the claim, write-free" \
  || bad "live descendant: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# ...and descendants whose holders are all dead do not.
fixture; old_show open ""
desc_rows "someone-else:99999:Tue Jan  1 00:00:00 2020"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--claim" "$BD_LOG"; } \
  && ok "dead descendant holders do not block the claim" \
  || bad "dead descendants: rc=$rc out=$out"

# --release: step 3's startup release of a dead run's epic.
fixture; desc_rows "someone-else:$live_pid:$live_start"
out=$("$SCRIPT" --release computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^KEPT" <<<"$out" && grep -q "computenet-e.3.2" <<<"$out" \
  && ! grep -q -- "--status=open" "$BD_LOG"; } \
  && ok "--release keeps an epic a live session works beneath" \
  || bad "release kept: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

fixture; desc_rows "someone-else:99999:Tue Jan  1 00:00:00 2020"
out=$("$SCRIPT" --release computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "update computenet-e --status=open --assignee= --unset-metadata holder" "$BD_LOG" \
  && ! grep -qE -- "--claim|dolt push" "$BD_LOG"; } \
  && ok "--release reopens a dead run's epic, locally" \
  || bad "release: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

fixture; touch "$CTRL/list-fail"
out=$("$SCRIPT" --release computenet-e 2>&1); rc=$?
{ [ "$rc" = 3 ] && ! grep -q -- "--status=open" "$BD_LOG"; } \
  && ok "--release with an unlistable subtree checks and writes nothing (exit 3)" \
  || bad "release list-fail: rc=$rc out=$out"

# m090n: no holder anywhere, but a child touched just now -> KEPT, no write.
fixture; now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf '[{"id":"computenet-e.3","parent":"computenet-e","status":"open","updated_at":"%s"}]' "$now" > "$CTRL/list.json"
out=$("$SCRIPT" --release computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^KEPT" <<<"$out" && grep -q "computenet-e.3" <<<"$out" \
  && ! grep -qE -- "--status=open|^comment" "$BD_LOG"; } \
  && ok "--release keeps an epic whose holderless child was touched within the window" \
  || bad "release hot: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# ...unless that touch was this machine's own sweep (x3f5a discount).
fixture
printf '[{"id":"computenet-e.3","parent":"computenet-e","status":"open","updated_at":"%s"}]' "$now" > "$CTRL/list.json"
echo "$(date +%s) computenet-e.3" > "$CTRL/swept"
out=$(CLAIM_SWEPT_FILE="$CTRL/swept" "$SCRIPT" --release computenet-e 2>&1); rc=$?
[ "$rc" = 0 ] && ok "--release discounts a child this run's own sweep touched" \
  || bad "release self-swept: rc=$rc out=$out"

# --- fail closed, and the bounds on FOREIGN ----------------------------------
# An unlistable subtree is NOT CHECKED: the live-descendant refusal could not
# run, so the claim does not proceed.
fixture; touch "$CTRL/list-fail"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^NOT CHECKED" <<<"$out" && ! grep -qE -- "--claim|dolt push" "$BD_LOG"; } \
  && ok "an unlistable subtree fails the claim closed" \
  || bad "list-fail claim: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

fixture; touch "$CTRL/show-fail"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^NOT CHECKED" <<<"$out" && ! grep -qE -- "--claim|dolt push" "$BD_LOG"; } \
  && ok "an unreadable epic fails the claim closed" \
  || bad "show-fail claim: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# The umbrella refusal reads only the epic: it runs before the subtree listing.
fixture; touch "$CTRL/list-fail"
printf '[{"id":"computenet-e","status":"open","assignee":"","updated_at":"2020-01-01T00:00:00Z","labels":["tracking-umbrella"]}]' > "$CTRL/show.json"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "tracking umbrella" <<<"$out" && ! grep -q "^list " "$BD_LOG"; } \
  && ok "the umbrella refusal precedes the subtree listing" \
  || bad "umbrella order: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

desc_one() { # holder updated_at
  printf '[{"id":"computenet-e.3","parent":"computenet-e","status":"in_progress","updated_at":"%s","metadata":{"holder":"%s"}}]' \
    "$2" "$1" > "$CTRL/list.json"
}
FOREIGN_TOK="other-box/testbox:99999:Tue Jan  1 00:00:00 2020"
fixture; desc_one "$FOREIGN_TOK" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
out=$(CLAIM_SKIP_HOT=1 "$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "FOREIGN" <<<"$out" && ! grep -q -- "--claim" "$BD_LOG"; } \
  && ok "a FOREIGN descendant written recently refuses the claim" \
  || bad "foreign fresh: rc=$rc out=$out"

fixture; desc_one "$FOREIGN_TOK" "2020-01-01T00:00:00Z"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--claim" "$BD_LOG"; } \
  && ok "a FOREIGN descendant older than any slot does not block" \
  || bad "foreign old: rc=$rc out=$out"

fixture; desc_one "$("$HOLDER_SH")" "2020-01-01T00:00:00Z"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--claim" "$BD_LOG"; } \
  && ok "a descendant this session holds (MINE) does not block" \
  || bad "mine: rc=$rc out=$out"

# The epic's OWN foreign holder on a released epic is residue, as for LIVE.
fixture
holder_show open "" "$FOREIGN_TOK"
out=$("$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "residue on a released epic" <<<"$out"; } \
  && ok "a foreign holder on a released epic is residue, claim proceeds" \
  || bad "foreign residue: rc=$rc out=$out"

# --release: a failed write is exit 4, distinct from KEPT (1) and NOT CHECKED (3).
fixture; touch "$CTRL/update-fail"
out=$("$SCRIPT" --release computenet-e 2>&1); rc=$?
[ "$rc" = 4 ] && ok "--release with a failed write exits 4" || bad "release write-fail: rc=$rc out=$out"

# --- the slot clock gates the claim (computenet-dfsgn, computenet-fqvhz) -----
# A startup pull stuck for hours, or a host frozen in DarkWake, reached the
# claim with the slot long expired and claimed an epic it could only release.
fixture; mkdir -p "$CTRL/scratch"
echo 18000 > "$CTRL/scratch/slot-seconds"; echo $(( $(date -u +%s) - 40000 )) > "$CTRL/scratch/slot-start"
out=$(SCRATCH="$CTRL/scratch" "$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^EXPIRED: not claimed" <<<"$out" && ! grep -q "update" "$BD_LOG"; } \
  && ok "an EXPIRED slot refuses the claim before any bd write" \
  || bad "expired slot: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

fixture; mkdir -p "$CTRL/scratch"
echo 18000 > "$CTRL/scratch/slot-seconds"; echo $(( $(date -u +%s) - 600 )) > "$CTRL/scratch/slot-start"
out=$(SCRATCH="$CTRL/scratch" "$SCRIPT" computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--claim" "$BD_LOG"; } \
  && ok "a slot within budget claims as before" \
  || bad "open slot: rc=$rc out=$out"

# --- 2jx46: a long-running session writes its epic's CHILDREN, never the epic
# row. Its holder is old (HOLDER_MAX_AGE_S=0 ages every token), its claimed
# child is quiet, the epic row is quiet, and nothing is within the 15m hot
# window — but a closed child it stamped was written 40 minutes ago.
two_jx() { # minutes-ago of the holder's newest child write
  h="someone-else:$live_pid:$live_start"; w=$(date -u -v-"$1"M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "-$1 min" +%Y-%m-%dT%H:%M:%SZ)
  printf '[{"id":"computenet-e.3","parent":"computenet-e","status":"in_progress","updated_at":"2020-01-01T00:00:00Z","metadata":{"holder":"%s"}},{"id":"computenet-e.4","parent":"computenet-e","status":"closed","updated_at":"%s","metadata":{"holder":"%s"}}]' "$h" "$w" "$h" > "$CTRL/list.json"
  printf '[{"id":"computenet-e","status":"in_progress","assignee":"testbox","updated_at":"2020-01-01T00:00:00Z","metadata":{"holder":"%s"}}]' "$h" > "$CTRL/show.json"
}
fixture; two_jx 40
out=$(HOLDER_MAX_AGE_S=0 "$SCRIPT" --release computenet-e --observed "someone-else:$live_pid:$live_start" STALE 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^KEPT" <<<"$out" && ! grep -qE -- "--status=open|^comment" "$BD_LOG"; } \
  && ok "--release keeps an old-token epic whose holder wrote a child 40m ago" \
  || bad "2jx46 live: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

fixture; two_jx 300
out=$(HOLDER_MAX_AGE_S=0 "$SCRIPT" --release computenet-e --observed "someone-else:$live_pid:$live_start" STALE 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q -- "--status=open" "$BD_LOG"; } \
  && ok "--release still frees an old-token epic whose holder has not written for 5h (residue)" \
  || bad "2jx46 residue: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# Between features the session holds NO in_progress child: only the epic's own
# holder, re-judged on its subtree writes, keeps it.
fixture; two_jx 40
sed -i.bak 's/"status":"in_progress","updated_at":"2020/"status":"closed","updated_at":"2020/' "$CTRL/list.json"
out=$(HOLDER_MAX_AGE_S=0 "$SCRIPT" --release computenet-e 2>&1); rc=$?
{ [ "$rc" = 1 ] && grep -q "^KEPT" <<<"$out" && ! grep -qE -- "--status=open|^comment" "$BD_LOG"; } \
  && ok "--release keeps an epic whose own holder wrote a (closed) child 40m ago" \
  || bad "2jx46 epic holder: rc=$rc out=$out log=$(tr '\n' '|' < "$BD_LOG")"

# --- --release records what it saw before it clears it (computenet-60f8) -----
fixture; desc_rows "someone-else:99999:Tue Jan  1 00:00:00 2020"
out=$("$SCRIPT" --release computenet-e --observed "old:1:x" DEAD 2>&1); rc=$?
cl=$(grep -n "^comment computenet-e released by .*: observed holder old:1:x, classified DEAD" "$BD_LOG" | cut -d: -f1)
ul=$(grep -n -- "--status=open --assignee=" "$BD_LOG" | cut -d: -f1)
{ [ "$rc" = 0 ] && [ -n "$cl" ] && [ -n "$ul" ] && [ "$cl" -lt "$ul" ]; } \
  && ok "--release comments the observed holder and verdict before clearing" \
  || bad "release comment: rc=$rc cl=$cl ul=$ul log=$(tr '\n' '|' < "$BD_LOG")"

fixture; desc_rows "someone-else:99999:Tue Jan  1 00:00:00 2020"
printf '[{"id":"computenet-e","status":"in_progress","assignee":"testbox","updated_at":"2020-01-01T00:00:00Z","metadata":{"holder":"gone:7:y"}}]' > "$CTRL/show.json"
out=$("$SCRIPT" --release computenet-e 2>&1); rc=$?
{ [ "$rc" = 0 ] && grep -q "^comment computenet-e released by .*: observed holder gone:7:y, classified unstated" "$BD_LOG"; } \
  && ok "--release without --observed records the epic's own holder" \
  || bad "release default holder: rc=$rc log=$(tr '\n' '|' < "$BD_LOG")"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
