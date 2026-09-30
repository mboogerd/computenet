#!/usr/bin/env bash
# Reopen epics that were deferred only because another epic's open work
# blocked them, once that work has closed or no longer holds them back.
#
# THE MARKER. Step 3 defers an epic whose open children are all blocked by
# items outside it — and before deferring, labels each such blocker
# `undefers:<epic-id>`. A deferred epic is hidden from `bd ready` on every
# machine, and nothing about closing the blocker touches it, so without the
# marker "blocked until X lands" silently becomes "blocked until a person
# notices". A human-gated epic gets no marker: a person reopens it.
#
# THIS SCRIPT reads only markers, never the deferred epics themselves. For each
# epic named by a marker on a CLOSED item:
#   - no non-closed item still carries its marker -> UNDEFERRED;
#   - markers remain, but an open child of the epic reads READY per
#     verify-ready.sh -> UNDEFERRED anyway (the closed blocker was the one
#     that mattered to that child);
#   - otherwise -> WAITING, nothing written.
# An UNDEFERRED epic gets `bd undefer` and a comment; a no-longer-deferred one
# is CLEARED. Either way the spent markers come off the closed items; markers
# on still-open items stay. Writes are local and ride out on the session's
# publication push.
#
# LIMIT. A blocker DELETED rather than closed never appears here, so its epic
# stays deferred until a person notices. The defer comment step 3 writes
# names the blockers, which is how that person finds the way back.
#
# Usage: undefer-unblocked.sh [--dry-run]
# Prints one line per epic: UNDEFERRED <epic> (...) | WAITING <epic> on <ids>
# | CLEARED <epic> (<status>).
# Exit 0: done (possibly nothing to do). Exit 3: a query failed (bd, or
#   verify-ready.sh's own exit 3) — nothing was established; writes already
#   made for earlier epics stand.
set -uo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
VERIFY_READY=${UNDEFER_VERIFY_READY:-"$DIR/verify-ready.sh"}

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

# Label filtering is client-side on purpose: bd list --label-pattern is
# silently ignored in combination with --status (it returns every closed row),
# so the marker selection cannot be delegated to bd. --label (exact) is only
# ever used below for a named marker, and its result is re-filtered too.
closed=$(rows --status=closed) || exit 3
closed=$(jq -c '[.[] | select(any(.labels[]?; startswith("undefers:")))]' <<<"$closed")
epics=$(jq -r '[.[].labels[]? | select(startswith("undefers:")) | sub("^undefers:"; "")] | unique | .[]' <<<"$closed")
[ -n "$epics" ] || { echo "undefer-unblocked: no spent markers"; exit 0; }

for e in $epics; do
  spent=$(jq -r --arg l "undefers:$e" '[.[] | select((.labels // []) | index($l)) | .id] | join(" ")' <<<"$closed")
  waiting=$(rows --status=open,in_progress,blocked,deferred --label "undefers:$e") || exit 3
  waiting=$(jq -r --arg l "undefers:$e" '[.[] | select((.labels // []) | index($l)) | .id] | join(" ")' <<<"$waiting")
  why="every blocker marked undefers:$e has closed ($spent)"
  if [ -n "$waiting" ]; then
    kids=$(rows --parent "$e" --status=open) || exit 3
    kids=$(jq -r '[.[].id] | join(" ")' <<<"$kids")
    ready=""
    if [ -n "$kids" ]; then
      # shellcheck disable=SC2086
      vr=$("$VERIFY_READY" $kids 2>/dev/null); vrc=$?
      case "$vrc" in
        0) ready=$(printf '%s\n' "$vr" | awk '$1 == "READY" {print $2}' | tr '\n' ' ') ;;
        1) ;;
        *) echo "undefer-unblocked: verify-ready.sh exited $vrc on $e's children — nothing checked" >&2; exit 3 ;;
      esac
    fi
    if [ -z "$ready" ]; then
      echo "WAITING $e on $waiting"
      continue
    fi
    why="blockers closed ($spent) and children READY (${ready% }) although markers remain on $waiting"
  fi
  status=$(bd show "$e" --json 2>/dev/null | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].status // ""' 2>/dev/null)
  [ -n "$status" ] || { echo "undefer-unblocked: could not read $e" >&2; exit 3; }
  if [ "$status" = deferred ]; then
    echo "UNDEFERRED $e ($why)"
    if [ "$DRY" = 0 ]; then
      bd undefer "$e" >/dev/null || { echo "undefer-unblocked: bd undefer $e failed" >&2; exit 3; }
      bd comment "$e" "un-deferred: $why" >/dev/null
    fi
  else
    echo "CLEARED $e ($status)"
  fi
  if [ "$DRY" = 0 ]; then
    for b in $spent; do bd update "$b" --remove-label "undefers:$e" >/dev/null; done
  fi
done
exit 0
