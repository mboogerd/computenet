#!/bin/sh
# verify-ready.sh <issue-id> [<issue-id>...]
#
# WHY THIS EXISTS. `bd ready`, `bd blocked`, ready-in-epic.sh and
# next-batch.py all derive blockedness from bd's DENORMALIZED `is_blocked`
# column, and that column goes stale against the live edge set the moment a
# blocker closes — which is the event a work session generates constantly.
# Measured seven times across two sessions on 2026-08-19 (computenet-r79z,
# computenet-38ze): beads whose only blocking edge pointed at a CLOSED bead,
# and beads created minutes earlier carrying NO blocking edge at all, were
# reported blocked. Neither `bd dolt pull` nor `bd dolt push` cleared it, and
# re-running in a separate Bash call returns the same wrong answer — so the
# computenet-2mou same-invocation guard does not cover this.
#
# WHY IT MATTERS BEYOND LOST TIME. SKILL.md step 3 treats an empty readiness
# answer as grounds for `bd defer <epic>`, which hides the epic from BOTH
# machines until a human notices, and 5b's `blocked` verdict parks the
# feature. One session would have deferred an epic holding four ready
# children.
#
# WHAT THIS DOES. Reads each id's ACTUAL edges with `bd dep list`, which
# prints the target's status and the edge type on every line, and applies
# READY-COVERAGE.md section 2's test directly: an edge blocks only if its
# type is blocks/conditional-blocks AND its target is neither closed nor
# pinned. No denormalized column is consulted.
#
# Output: one line per id, `READY <id>` or `BLOCKED <id> by: <lines>`.
# Exit: 0 = at least one READY; 1 = none ready; 2 = bad usage;
#       3 = a `bd dep list` call failed — NOTHING was checked, do not route
#           on this (the ready-in-epic.sh exit-3 class).
set -eu

[ $# -ge 1 ] || { echo "usage: verify-ready.sh <issue-id> [<issue-id>...]" >&2; exit 2; }

any_ready=1
for id in "$@"; do
  deps=$(bd dep list "$id" 2>/dev/null) \
    || { echo "verify-ready: 'bd dep list $id' failed; NOTHING was checked" >&2; exit 3; }
  # Blocking edges whose target is not closed and not pinned.
  live=$(printf '%s\n' "$deps" \
         | grep -E 'via (blocks|conditional-blocks)$' \
         | grep -vE '\((closed|pinned)\) via ' || true)
  if [ -n "$live" ]; then
    echo "BLOCKED $id by:"
    printf '%s\n' "$live" | sed 's/^ */    /'
  else
    echo "READY $id"
    any_ready=0
  fi
done
exit $any_ready
