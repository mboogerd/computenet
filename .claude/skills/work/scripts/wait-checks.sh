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
# THE REST FALLBACK. `gh pr checks` is GraphQL-only. On 2026-08-17 GitHub's
# GraphQL endpoint returned 503 intermittently and then persistently for an
# hour, while githubstatus.com reported every component operational and REST
# stayed healthy throughout. Under that outage this loop's only possible
# outcome is to spin its full 40 rounds and report QUERY-FAILED: it cannot
# make progress and it cannot tell "endpoint down" from "checks not created
# yet" (computenet-fdv9). So after FALLBACK_AFTER consecutive query-failed
# rounds it re-reads the same verdict over REST, from
# `commits/<sha>/check-runs`, and says which transport answered.
#
# The REST form is better in a second, unrelated way: it keys on the COMMIT,
# so it structurally cannot report a verdict for a head other than the one
# asked about — the computenet-qnyn hazard (a PR head observed lagging the
# pushed ref by ~10 minutes, with nothing in the output saying so) removed by
# construction rather than by a sha check the caller must remember to write.
#
# Usage: wait-checks.sh <pr-url> [max-rounds]
#   Polls `gh pr checks <pr-url>` every 20s, up to max-rounds (default 28).
#
# WHY 28 AND NOT 40. Every dispatch prompt in this skill runs verification in
# ONE foreground Bash call with an explicit timeout of at most 600000 ms. The
# old default of 40 rounds is ~13m20s, so a dispatched agent invoking this the
# documented way was CUT OFF BY THE CAP before the loop could reach a verdict —
# and a cut-off call looks like nothing at all, not like TIMEOUT-PENDING
# (computenet-ymv4). 28 rounds is ~9m20s, inside the cap and above the measured
# 9-12 minute settle time governed by build-test-fast (computenet-678u).
# A caller with a longer budget passes max-rounds explicitly; one that needs
# more than ~28 rounds needs a second call, not a bigger number.
# Stdout: one progress line per round, then the last rows, then the verdict as
#   the FINAL line — exactly one of SETTLED / TIMEOUT-PENDING / QUERY-FAILED —
#   so `tail -1` is the reading. SETTLED means present-and-not-pending, which
#   includes failed checks: read the rows above it for red.
# Exit: 0 = SETTLED; 4 = TIMEOUT-PENDING (rounds exhausted, checks exist but
#   have not settled); 3 = QUERY-FAILED (the last round produced no
#   recognizable rows — nothing was read); 2 = bad usage.
set -uo pipefail

pr=${1:?usage: wait-checks.sh <pr-url> [max-rounds]}
rounds=${2:-28}
case "$rounds" in
  ''|*[!0-9]*) echo "wait-checks: max-rounds must be a positive integer, got '$rounds'" >&2; exit 2 ;;
esac
[ "$rounds" -ge 1 ] || { echo "wait-checks: max-rounds must be at least 1" >&2; exit 2; }

# The six required checks, from the main-branch ruleset (AGENTS.md records the
# command to re-read the authoritative list; kernel-test was missing from this
# very list until 2026-08-17 — computenet-4prd).
req='build-test-fast|build-test-serial|concord-full|ui-test|agora-ui-test|kernel-test'

# How many consecutive query-failed rounds before trying the other transport.
FALLBACK_AFTER=${WAIT_CHECKS_FALLBACK_AFTER:-3}

# Re-read the same verdict over REST. Prints rows in `gh pr checks`'s own
# shape (name, status word, conclusion) so every classifier below is unchanged.
# Silent failure here is not a green: it prints nothing, the caller's row count
# stays 0, and the round remains query-failed.
rest_rows() {
  local sha repo
  sha=$(gh api "repos/{owner}/{repo}/pulls/${pr##*/}" --jq .head.sha 2>/dev/null) || return 1
  [ -n "$sha" ] || return 1
  gh api "repos/{owner}/{repo}/commits/$sha/check-runs?per_page=100" \
    --jq '.check_runs[] | "\(.name)\t\(if .status != "completed" then "pending"
                              elif .conclusion == "success" then "pass"
                              elif .conclusion == "skipped" then "skipping"
                              else "fail" end)\t\(.conclusion // "-")"' 2>/dev/null
}

rows=""
state=query-failed
consecutive_failed=0
for i in $(seq 1 "$rounds"); do
  rows=$(gh pr checks "$pr" 2>&1)     # exit status deliberately not tested
  n=$(printf '%s\n' "$rows" | grep -cE "$req")
  # Classify on OUTPUT here too: a GraphQL 503 leaves no recognizable rows.
  if [ "$n" -lt 6 ] && ! printf '%s\n' "$rows" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
    consecutive_failed=$((consecutive_failed + 1))
    if [ "$consecutive_failed" -ge "$FALLBACK_AFTER" ]; then
      rest=$(rest_rows)
      if [ -n "$rest" ]; then
        echo "round $i/$rounds: GraphQL failed ${consecutive_failed}x — answering over REST (commits/<sha>/check-runs)"
        rows=$rest
        n=$(printf '%s\n' "$rows" | grep -cE "$req")
      fi
    fi
  else
    consecutive_failed=0
  fi
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
