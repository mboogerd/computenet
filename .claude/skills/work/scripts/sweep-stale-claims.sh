#!/usr/bin/env bash
# Reopen this machine's task claims left in_progress by a run that died.
#
# Why this exists: `bd ready` hides in_progress items, so an abandoned claim is
# invisible to every later session and the work is locked forever. Nothing else
# reopens it. Epics and features are deliberately excluded — their claim is what
# keeps the other machine out and must outlive the session.
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
stale=$(bd list --status=in_progress --assignee="$BEADS_ACTOR" \
          --exclude-type=epic,feature --limit 0 --json 2>/dev/null \
        | jq -r --argjson cutoff "$cutoff" '
            .[]
            | select((.updated_at | fromdateiso8601) < $cutoff)
            | .id' || true)

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

[ "$DRY_RUN" -eq 1 ] || bd dolt push >/dev/null 2>&1 || true
echo "released $count claim(s) older than ${HOURS}h"
