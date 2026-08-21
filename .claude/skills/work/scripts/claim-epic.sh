#!/usr/bin/env bash
# Claim an epic for this session — or take over a stale released claim — and
# push the acquisition, so the claim is a LOCK rather than a local record.
# Without the push, two machines starting slots between each other's Finalize
# pushes could both claim the epic and neither would find out all session
# (the computenet-kg7 class).
#
# Encodes step 3's rules:
#   - refuses computenet-wpvy: the SDLC epic is never /work's to claim;
#   - `bd update --claim` refuses any issue that carries an assignee. On an
#     OPEN epic that assignee is residue, not a live claim (Finalize clears it
#     now; older releases and crashes did not): take the epic over IF its
#     updated_at is older than --stale-min (default 15, CLAIM_STALE_MIN
#     overrides). Fresher, or an unparseable timestamp, reads as possibly
#     live: refuse. On an in_progress epic the refusal is correct (this
#     machine's crash leftover — released earlier in step 3 — or the other
#     machine's live run): refuse;
#   - stamps the owner label, skill_version, and metadata.holder — a
#     SESSION-unique identity (session-holder.sh), because `assignee` is
#     BEADS_ACTOR and therefore per-MACHINE: two sessions on one box are
#     indistinguishable in the tracker, so a live sibling's claim and a crash
#     leftover are the same row (computenet-83ay). With a holder the liveness
#     test is exact ("this row's holder is not me, and its process is alive")
#     instead of a 15-minute recency guess;
#   - RE-RUNS the liveness test immediately before it writes, so the window is
#     anchored to the CLAIM rather than to the top of step 3. On a slow host
#     those are not the same moment: one session's step 3 ran from 04:05 to
#     07:13 UTC — a single `bd update` exceeded 400s — and it claimed an epic
#     a live same-actor session was working the whole time (computenet-yurq);
#   - pushes, reading the OUTPUT rather than exit codes (bd dolt push can
#     exit 0 while printing a rejection). A rejected push pulls, re-verifies
#     the epic is still ours, and pushes once more.
#
# Run with a generous timeout (>=300s): a dolt push after a remote merge has
# been measured over 120s.
#
# Usage: claim-epic.sh <epic-id>
# Exit 0: claimed and pushed (took over or fresh — output says which).
# Exit 1: not claimed (reason on stderr) — select another epic, or stop.
# Exit 2: claimed LOCALLY but not published — stop the session and report;
#         an unpushed epic claim is exactly the race this script closes.
set -uo pipefail

: "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"
id=${1:?usage: claim-epic.sh <epic-id>}
STALE_MIN=${CLAIM_STALE_MIN:-15}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if [ "$id" = computenet-wpvy ]; then
  echo "REFUSED: $id is the SDLC epic and never /work's to claim" >&2
  exit 1
fi

out=$(bd update "$id" --claim 2>&1); st=$?
if [ $st -ne 0 ] || grep -qi "already claimed" <<<"$out"; then
  grep -qi "already claimed" <<<"$out" || { echo "claim failed: $out" >&2; exit 1; }

  json=$(bd show "$id" --json)
  status=$(jq -r '.[0].status // empty' <<<"$json")
  assignee=$(jq -r '.[0].assignee // ""' <<<"$json")
  cutoff=$(( $(date +%s) - STALE_MIN * 60 ))
  # tolerate fractional seconds; an unparseable date reads as "possibly live"
  epoch=$(jq -r '.[0].updated_at // empty
                 | sub("\\.[0-9]+"; "") | try fromdateiso8601 catch empty' <<<"$json")

  if [ "$status" != open ]; then
    echo "REFUSED: $id is $status, assignee=$assignee — a crash leftover or a live run, not a takeover case" >&2
    exit 1
  fi
  if [ -z "$epoch" ] || [ "$epoch" -ge "$cutoff" ]; then
    echo "REFUSED: $id is open but touched within ${STALE_MIN}m (assignee=$assignee) — possibly a live run" >&2
    exit 1
  fi

  bd update "$id" --assignee="$BEADS_ACTOR" --status=in_progress \
    || { echo "takeover write failed on $id" >&2; exit 1; }
  echo "took over $id from stale assignee '$assignee' (open, idle > ${STALE_MIN}m)"
fi

# yurq: re-verify AT THE CLAIM, not at the top of step 3. An arbitrarily slow
# step 3 cannot widen the window if the window is re-measured here. Re-read
# from bd rather than trusting anything computed above.
recheck=$(bd show "$id" --json | sed -n '/^[[{]/,$p')
held=$(jq -r '.[0].metadata.holder // ""' <<<"$recheck")
if [ -n "$held" ]; then
  verdict=$("$SCRIPT_DIR/session-holder.sh" --check "$held"); hrc=$?
  case "$verdict" in
    MINE) : ;;                      # already ours, this session — idempotent
    LIVE)
      echo "REFUSED: $id is held by a LIVE session ($held) — not a crash leftover" >&2
      exit 1 ;;
    DEAD) echo "note: $id's previous holder ($held) is dead — taking it over" ;;
    FOREIGN)
      echo "REFUSED: $id is held by a session on ANOTHER machine ($held) — liveness cannot be tested here; it is not this box's leftover (computenet-bz5c)" >&2
      exit 1 ;;
    *)    echo "note: $id's holder ($held) could not be evaluated (rc=$hrc) — proceeding on the recency test above" ;;
  esac
fi

bd update "$id" --add-label="owner:$BEADS_ACTOR" >/dev/null
bd update "$id" --set-metadata "skill_version=$(git hash-object "$SCRIPT_DIR/../SKILL.md")" >/dev/null
holder=$("$SCRIPT_DIR/session-holder.sh" 2>/dev/null) \
  && bd update "$id" --set-metadata "holder=$holder" >/dev/null \
  || echo "note: could not stamp metadata.holder — liveness falls back to the recency test" >&2

push_out=$(bd dolt push 2>&1)
if grep -qiE "rejected|error" <<<"$push_out"; then
  echo "-- push rejected; recovering: pull, re-verify, push --"
  pull_out=$(bd dolt pull 2>&1)
  if grep -qi "conflict" <<<"$pull_out"; then
    printf '%s\n' "$pull_out" >&2
    echo "ESCALATE: pull hit a merge conflict — see .claude/skills/work/references/dolt-conflict.md (an issues-only modify/modify conflict is resolvable here; anything else needs an operator); claim is LOCAL-ONLY" >&2
    exit 2
  fi
  now_assignee=$(bd show "$id" --json | sed -n '/^[[{]/,$p' | jq -r '.[0].assignee // ""')
  if [ "$now_assignee" != "$BEADS_ACTOR" ]; then
    echo "LOST RACE: after the pull, $id is assigned to '$now_assignee' — select another epic" >&2
    exit 1
  fi
  push_out=$(bd dolt push 2>&1)
  if grep -qiE "rejected|error" <<<"$push_out"; then
    printf '%s\n' "$push_out" >&2
    echo "ESCALATE: push failed twice; claim is LOCAL-ONLY — stop the session and report" >&2
    exit 2
  fi
fi
echo "claimed $id (pushed)"
