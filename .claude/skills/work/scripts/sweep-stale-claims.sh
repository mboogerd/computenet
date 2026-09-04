#!/usr/bin/env bash
# Reopen this machine's task claims left in_progress by a run that died.
#
# Why this exists: `bd ready` hides in_progress items, so an abandoned claim is
# invisible to every later session and the work is locked forever. Nothing else
# reopens it. Epics and features are deliberately excluded — epics are released
# explicitly (Finalize, or step-3 startup for a crashed run), and a feature
# claim is the resume marker consumed under the epic claim (5a).
#
# A LIVE SIBLING's claim is excluded too, by `metadata.holder`. `assignee` is
# BEADS_ACTOR, unique per MACHINE, so two /work sessions on one box are the
# same string and the age rule alone cannot tell a live sibling from a crash
# leftover. Measured 2026-09-04 on MacBoo: a second session's step-3 sweep
# reopened a live session's cross-epic item claim 73 minutes after it was made
# and pushed (computenet-yvdl). It cost nothing that time; the dangerous
# ordering is the other one, because an item that reads open is exactly what
# 5f route 3 tells the other session it may take, and two agents on one
# `files` claim is computenet-sec's measured failure.
#
# skill-friction items are also excluded: their claim is ROUTING (which
# machine's orchestrator lane drains the item — SKILL.md step 7), not a
# work-in-progress marker, so releasing it silently undoes the exclusivity the
# step-7 push established.
#
# Usage: sweep-stale-claims.sh [--hours N] [--dry-run]
#   --hours  age past which a claim counts as abandoned (default 6, comfortably
#            longer than one slot, so a live run's items are never taken)
set -euo pipefail

HOURS=6
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --hours) HOURS="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

: "${BEADS_ACTOR:?BEADS_ACTOR must be set, uniquely, per machine}"

cutoff=$(( $(date +%s) - HOURS * 3600 ))

# --limit 0 = unlimited; the default of 50 would silently strand the rest.
# `bd list --json` is a bare array, except under --skip-labels where it is
# {"issues":[...]}. Accept either (computenet-kr18); and do NOT fold a jq
# failure into '[]' — an unparsable answer is not "nothing is stale".
ROWS='(if type=="array" then . else (.issues // []) end)[]'
aged=$(bd list --status=in_progress --assignee="$BEADS_ACTOR" \
          --exclude-type=epic,feature --limit 0 --json 2>/dev/null \
        | jq -c --argjson cutoff "$cutoff" "
            [$ROWS | select((.updated_at | fromdateiso8601) < \$cutoff)
                   | select(((.labels // []) | index(\"skill-friction\")) | not)]") \
  || { echo "bd list/jq failed — refusing to report a clean sweep" >&2; exit 3; }

# `metadata.holder` decides liveness exactly where the timestamp only guesses,
# which is what SKILL.md step 3 already tells a human reader — and this script,
# which acts on that decision, did not read the field at all. LIVE and FOREIGN
# are leave-alone (a live sibling here; another machine's row, never ours to
# release). DEAD, STALE, UNKNOWN and an absent holder fall through to the age
# rule below, so a claim with no holder behaves exactly as before.
HOLDER_SH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/session-holder.sh"
# Say so when the check is unavailable. Without this the script degrades
# SILENTLY to the exact behaviour this block exists to remove — every live
# claim released, rc=0, no output — and step 3's prose already requires the
# fallback be announced. The write half warns when it cannot stamp; this is
# the matching warning for the read half.
[ -x "$HOLDER_SH" ] || echo "warning: $HOLDER_SH not executable — holder check skipped, falling back to the age rule alone; a live sibling's claim may be released" >&2
held=$(jq -r '.[] | select((.metadata.holder // "") != "") | "\(.id) \(.metadata.holder)"' <<<"$aged")
protected=""
while IFS=' ' read -r hid htok; do
  [ -n "$hid" ] || continue
  verdict=$("$HOLDER_SH" --check "$htok" 2>/dev/null || true)
  case "$verdict" in
    LIVE|MINE|FOREIGN)
      echo "held by a $verdict session (NOT released): $hid"
      protected="$protected $hid" ;;
  esac
done <<<"$held"
if [ -n "$protected" ]; then
  # `. as $r` first: inside the `index(...)` pipe `.` is the SPLIT ARRAY, so a
  # bare `.id` there indexes an array with a string and jq dies mid-filter —
  # caught by the mixed-batch case in the sibling suite, which is the shape
  # that matters (a live sibling's claim must not shield the dead one beside
  # it, nor take the whole sweep down with it).
  aged=$(jq -c --arg ids "$protected" \
           '[ .[] | . as $r | select(($ids | split(" ") | index($r.id)) | not) ]' \
           <<<"$aged")
fi

# An item with review=passed or a pending ship decision is WAITING, not
# abandoned: releasing it to open puts reviewed work back in bd ready as if
# unstarted, and the next session re-implements it. Report, never release.
waiting=$(jq -r '.[] | select((.metadata.review == "passed")
            or (.metadata.ship_decision != null)) | .id' <<<"$aged")
stale=$(jq -r '.[] | select((.metadata.review == "passed")
            or (.metadata.ship_decision != null) | not) | .id' <<<"$aged")

for id in $waiting; do
  echo "complete, awaiting decision (NOT released): $id"
done

if [ -z "$stale" ]; then
  echo "no stale claims"
  exit 0
fi

count=0
for id in $stale; do
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "would release: $id"
  else
    bd update "$id" --status=open >/dev/null
    echo "released: $id"
  fi
  count=$((count + 1))
done

# Released claims are written locally only. They reach the other machine at the
# session's Finalize push (SKILL.md step 6) — this script does not sync, and
# no scheduled job does it for us (doc/ops/beads-sync-runbook.md section 5).
echo "released $count claim(s) older than ${HOURS}h"
