#!/usr/bin/env bash
# Gate for the reactive SDLC orchestrator.
#
# Fired by launchd whenever .beads/issues.jsonl changes (bd rewrites it on
# every mutation), and hourly as a fallback. Exits in milliseconds when
# nothing is actionable — which is most firings, since the export changes on
# every bd write anywhere. When actionable skill-friction items exist under
# the SDLC epic, launches one headless /remediate-friction session.
#
# The orchestrator's own bd writes re-fire this gate: the lock absorbs
# firings mid-run, and after the run the filter finds nothing open. An
# in_progress item assigned to this machine with no live run is a crashed
# drain — the filter counts it, so the next firing resumes it.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
LOCK="${TMPDIR:-/tmp}/sdlc-orchestrator.lock"
LOG="$HOME/Library/Logs/sdlc-orchestrator.log"
MAX_SECONDS="${SDLC_MAX_SECONDS:-5400}"   # ponytail: 90m watchdog; tune per machine via env
SDLC_EPIC="computenet-wpvy"

: "${BEADS_ACTOR:?BEADS_ACTOR must be set (the installer bakes it into the plist)}"

cd "$REPO"

# Actionable = anything under the SDLC epic, not human-gated, and
# either open+unclaimed or claimed by this machine (open or in_progress —
# an in_progress item of ours with no live run is a crashed drain to resume).
# NO --label filter: scope is parentage (computenet-wpvy.37). Gating on
# skill-friction here made the lane refuse to fire while unlabelled children
# sat open — measured 2026-08-14, 8 labelled vs 15 actual, with wpvy.25/.26/.39
# invisible to this count. The label is provenance, not a gate.
count="$(bd list --parent="$SDLC_EPIC" --json 2>/dev/null |
  jq --arg me "$BEADS_ACTOR" '[ .[]
    | select(((.labels // []) | index("human")) == null)
    | select(
        (.status == "open" and ((.assignee // "") == "" or .assignee == $me))
        or (.status == "in_progress" and .assignee == $me)
      ) ] | length')"
[ "${count:-0}" -gt 0 ] || exit 0

# Single-flight: one orchestrator per machine, with stale-lock recovery.
if ! mkdir "$LOCK" 2>/dev/null; then
  oldpid="$(cat "$LOCK/pid" 2>/dev/null || true)"
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    exit 0                     # a live run owns the lane; it will sweep new items
  fi
  rm -rf "$LOCK"               # stale lock from a dead run
  mkdir "$LOCK" || exit 0      # lost the re-acquire race; the winner handles it
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT

echo "$(date '+%F %T') gate: $count actionable item(s)" >> "$LOG"

if [ "${SDLC_DRY_RUN:-0}" = "1" ]; then
  echo "$(date '+%F %T') gate: dry run, not launching" >> "$LOG"
  echo "dry-run: would launch /remediate-friction for $count item(s)"
  exit 0
fi

claude -p "/remediate-friction" >> "$LOG" 2>&1 &
pid=$!
( sleep "$MAX_SECONDS" && kill "$pid" 2>/dev/null ) &
watchdog=$!
set +e
wait "$pid"
rc=$?
set -e
kill "$watchdog" 2>/dev/null || true
echo "$(date '+%F %T') gate: orchestrator exited rc=$rc" >> "$LOG"
