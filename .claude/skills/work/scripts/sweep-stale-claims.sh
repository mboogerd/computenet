#!/usr/bin/env bash
# Reopen this machine's task claims left in_progress by a run that died.
#
# Why this exists: `bd ready` hides in_progress items, so an abandoned claim is
# invisible to every later session and the work is locked forever. Nothing else
# reopens it. Epics and features are deliberately excluded — epics are released
# explicitly (Finalize, or step-3 startup for a crashed run), and a feature
# claim is the resume marker consumed under the epic claim (5a).
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
