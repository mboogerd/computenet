#!/usr/bin/env bash
# Wait for a PR's required checks to settle, classifying on OUTPUT — never on
# `$?`. This is SKILL.md step 2's check-wait loop as a script.
#
# Why the exit status is deliberately never tested: `gh pr checks` exits 8
# while anything is pending, with well-formed rows on stdout, so an `||`
# branch fires on the ordinary pending case and reports a query failure that
# never happened (computenet-15it). The inverse idiom — `gh … 2>/dev/null ||
# echo "?"` — folds a real network failure into a green nobody earned. And the
# rows can be legitimately ABSENT: check runs are created asynchronously after
# a push, so for roughly the first minute the output is just the `auto-merge`
# row — nothing is pending, and a bare `grep -q pending` cannot tell that from
# all-green (computenet-1zhu). A reading is settled only when every required
# check is PRESENT and none of them is pending.
#
# The three non-settled states each round — query failed (no recognizable
# status rows at all), not-yet-reporting (rows exist but fewer than the 6
# required), unsettled (all 6 present, something pending) — are one state to
# any test on `$?`, and two of them look green.
#
# Usage: wait-checks.sh <pr-url> [max-rounds]
#   Polls `gh pr checks <pr-url>` every 20s, up to max-rounds (default 40).
# Stdout: one progress line per round, then the last rows, then the verdict as
#   the FINAL line — exactly one of SETTLED / TIMEOUT-PENDING / QUERY-FAILED —
#   so `tail -1` is the reading. SETTLED means present-and-not-pending, which
#   includes failed checks: read the rows above it for red.
# Exit: 0 = SETTLED; 4 = TIMEOUT-PENDING (rounds exhausted, checks exist but
#   have not settled); 3 = QUERY-FAILED (the last round produced no
#   recognizable rows — nothing was read); 2 = bad usage.
set -uo pipefail

pr=${1:?usage: wait-checks.sh <pr-url> [max-rounds]}
rounds=${2:-40}
case "$rounds" in
  ''|*[!0-9]*) echo "wait-checks: max-rounds must be a positive integer, got '$rounds'" >&2; exit 2 ;;
esac
[ "$rounds" -ge 1 ] || { echo "wait-checks: max-rounds must be at least 1" >&2; exit 2; }

# The six required checks, from the main-branch ruleset (AGENTS.md records the
# command to re-read the authoritative list; kernel-test was missing from this
# very list until 2026-08-17 — computenet-4prd).
req='build-test-fast|build-test-serial|concord-full|ui-test|agora-ui-test|kernel-test'

rows=""
state=query-failed
for i in $(seq 1 "$rounds"); do
  rows=$(gh pr checks "$pr" 2>&1)     # exit status deliberately not tested
  n=$(printf '%s\n' "$rows" | grep -cE "$req")
  if [ "$n" -lt 6 ]; then
    if printf '%s\n' "$rows" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
      state=not-reporting               # normal early state (computenet-1zhu)
      echo "round $i/$rounds: only $n of 6 required rows reporting"
    else
      state=query-failed                # no recognizable status rows at all
      echo "round $i/$rounds: QUERY FAILED: $rows"
    fi
  elif printf '%s\n' "$rows" | grep -q pending; then
    state=unsettled
    echo "round $i/$rounds: all 6 required rows present, something still pending"
  else
    printf '%s\n' "$rows"
    echo SETTLED
    exit 0
  fi
  [ "$i" -lt "$rounds" ] && sleep 20
done

printf '%s\n' "$rows"
if [ "$state" = query-failed ]; then
  echo QUERY-FAILED
  exit 3
fi
# not-reporting at exhaustion is still "checks have not settled": rows exist,
# the required set never completed — the caller's move (diagnose, re-poll) is
# the same as for a pending timeout, so both fold into TIMEOUT-PENDING.
echo TIMEOUT-PENDING
exit 4
