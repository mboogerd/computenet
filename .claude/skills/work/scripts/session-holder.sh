#!/usr/bin/env bash
# A SESSION-unique holder identity, and a liveness test for one.
#
# WHY THIS EXISTS. A bd claim's holder is the `assignee` string, which is
# BEADS_ACTOR — unique per MACHINE, not per session. So two /work sessions on
# one box are indistinguishable in the tracker: a live sibling's claim and a
# dead session's crash leftover are the same row, and every liveness rule in
# SKILL.md rests on a 15-minute `updated_at` recency guess instead of on
# knowing who holds the claim.
#
# What that costs, measured:
#   - Four sessions ran concurrently on one box; the fourth detected it only
#     because it happened to arrive last, and spent its whole 5h slot writing
#     a report (computenet-83ay).
#   - A session claimed an epic another live run was working, because step 3's
#     check ran ~3h before the claim on a slow host and nothing re-checked at
#     the claim itself (computenet-yurq).
#   - A concurrent session merged a task and removed its worktree while a
#     reviewer was live inside it (computenet-dj9h).
# Widening or narrowing the recency window cannot fix a lock whose identity
# cannot name the holder. This can.
#
# THE IDENTITY. `<actor>:<pid>:<start>` where pid/start describe the nearest
# ancestor process that IS the agent session (the `claude` CLI), so the token
# is alive exactly as long as the session is. `start` is the process start
# timestamp, which is what makes the test survive PID REUSE — a recycled pid
# with a different start time reads as dead, not as live.
#
# Usage:
#   session-holder.sh                 # print this session's holder token
#   session-holder.sh --check <token> # LIVE | DEAD | UNKNOWN | MINE
# Exit: 0 for LIVE/MINE, 1 for DEAD, 3 for UNKNOWN (nothing was established —
#   treat exactly like ready-in-epic.sh's exit 3: not an all-clear).
set -uo pipefail

# Walk up from this shell to the session process. A fixed ancestor hop is
# wrong: the depth differs between a direct Bash call and one nested in a
# script, so match on the command instead.
session_pid() {
  local pid=$$ comm hops=0
  while [ "${pid:-0}" -gt 1 ] && [ "$hops" -lt 20 ]; do
    comm=$(ps -o comm= -p "$pid" 2>/dev/null) || return 1
    case "$comm" in *claude*|*node*) printf '%s' "$pid"; return 0 ;; esac
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
    hops=$((hops + 1))
  done
  return 1
}

# Start time, not elapsed: elapsed changes every second and cannot be compared
# across two readings. `lstart` is stable for the life of the process.
start_of() { ps -o lstart= -p "$1" 2>/dev/null | tr -s ' ' | sed 's/^ *//;s/ *$//'; }

mine() {
  local pid start
  pid=$(session_pid) || return 1
  start=$(start_of "$pid")
  [ -n "$start" ] || return 1
  printf '%s:%s:%s' "${BEADS_ACTOR:-unknown}" "$pid" "$start"
}

if [ "${1:-}" = --check ]; then
  token=${2:?usage: session-holder.sh --check <token>}
  self=$(mine)
  if [ -n "$self" ] && [ "$token" = "$self" ]; then echo MINE; exit 0; fi

  # actor:pid:start — start itself contains colons, so split on the first two.
  rest=${token#*:}
  pid=${rest%%:*}
  start=${rest#*:}
  case "$pid" in ''|*[!0-9]*) echo UNKNOWN; exit 3 ;; esac

  now=$(start_of "$pid")
  if [ -z "$now" ]; then echo DEAD; exit 1; fi
  if [ "$now" = "$start" ]; then echo LIVE; exit 0; fi
  # Same pid, different start time: the original process is gone and the pid
  # was recycled. That is DEAD, and reading it as LIVE would deadlock the
  # epic behind a process that has nothing to do with it.
  echo DEAD; exit 1
fi

self=$(mine)
if [ -z "$self" ]; then
  echo "session-holder: could not identify the session process" >&2
  exit 3
fi
printf '%s\n' "$self"
