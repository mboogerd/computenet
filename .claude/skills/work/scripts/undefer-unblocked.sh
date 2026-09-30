#!/usr/bin/env bash
# Reopen epics that were deferred only because another epic's open work
# blocked them, once that work has closed.
#
# THE MARKER. Step 3 defers an epic whose open children are all blocked by
# open items in OTHER epics — and before deferring, labels each such blocker
# `undefers:<epic-id>`. A deferred epic is hidden from `bd ready` on every
# machine, and nothing about closing the blocker touches it, so without the
# marker "blocked until X lands" silently becomes "blocked until a person
# notices". A human-gated epic gets no marker: a person reopens it.
#
# THIS SCRIPT reads only markers, never the deferred epics themselves: for each
# epic named by a marker on a CLOSED item, it runs `bd undefer` once no
# non-closed item still carries a marker for it (so an epic behind two
# blockers is not reopened into a still-blocked state), comments why, and
# removes the spent markers from the closed items. An epic that is no longer
# deferred (someone reopened it) just loses its spent markers. Writes are
# local; they ride out on the session's publication push.
#
# Usage: undefer-unblocked.sh [--dry-run]
# Prints one line per epic: UNDEFERRED <epic> | WAITING <epic> on <ids> |
# CLEARED <epic> (<status>).
# Exit 0: done (possibly nothing to do). Exit 3: a query failed — nothing
#   was established, and nothing was written.
set -uo pipefail

DRY=0
case "${1:-}" in
  --dry-run) DRY=1 ;;
  "") ;;
  *) echo "usage: undefer-unblocked.sh [--dry-run]" >&2; exit 2 ;;
esac

rows() { # <bd list args...> -> JSON array, or exit 3
  local out
  out=$(bd list "$@" --limit 0 --json 2>/dev/null) || { echo "undefer-unblocked: bd list $* failed" >&2; exit 3; }
  out=$(printf '%s\n' "$out" | sed -n '/^[[{]/,/^[]}]/p')
  [ -n "$out" ] || out='[]'
  jq -c 'if type=="array" then . else (.issues // []) end' <<<"$out" 2>/dev/null \
    || { echo "undefer-unblocked: unreadable bd list $* output" >&2; exit 3; }
}

closed=$(rows --status=closed --label-pattern 'undefers:*') || exit 3
epics=$(jq -r '[.[].labels[]? | select(startswith("undefers:")) | sub("^undefers:"; "")] | unique | .[]' <<<"$closed")
[ -n "$epics" ] || { echo "undefer-unblocked: no spent markers"; exit 0; }

for e in $epics; do
  waiting=$(rows --status=open,in_progress,blocked,deferred --label "undefers:$e") || exit 3
  waiting=$(jq -r '[.[].id] | join(" ")' <<<"$waiting")
  if [ -n "$waiting" ]; then
    echo "WAITING $e on $waiting"
    continue
  fi
  spent=$(jq -r --arg l "undefers:$e" '[.[] | select((.labels // []) | index($l)) | .id] | join(" ")' <<<"$closed")
  status=$(bd show "$e" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].status // ""' 2>/dev/null)
  [ -n "$status" ] || { echo "undefer-unblocked: could not read $e" >&2; exit 3; }
  if [ "$status" = deferred ]; then
    echo "UNDEFERRED $e (blockers closed: $spent)"
    if [ "$DRY" = 0 ]; then
      bd undefer "$e" >/dev/null || { echo "undefer-unblocked: bd undefer $e failed" >&2; exit 3; }
      bd comment "$e" "un-deferred: every blocker marked undefers:$e has closed ($spent)" >/dev/null
    fi
  else
    echo "CLEARED $e ($status)"
  fi
  if [ "$DRY" = 0 ]; then
    for b in $spent; do bd update "$b" --remove-label "undefers:$e" >/dev/null; done
  fi
done
exit 0
