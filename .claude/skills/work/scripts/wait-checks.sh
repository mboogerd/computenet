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
# REST IS THE PRIMARY TRANSPORT; `gh pr checks` IS THE FALLBACK. It was the
# other way round until computenet-00d8. Two reasons, and the second is why
# the order changed:
#
# 1. `gh pr checks` is GraphQL-only. On 2026-08-17 GitHub's GraphQL endpoint
#    returned 503 intermittently and then persistently for an hour, while
#    githubstatus.com reported every component operational and REST stayed
#    healthy throughout. Under that outage a GraphQL-only loop's only possible
#    outcome is to spin its rounds and report QUERY-FAILED: it cannot make
#    progress and cannot tell "endpoint down" from "checks not created yet"
#    (computenet-fdv9).
#
# 2. REST keys on the COMMIT, so it structurally cannot report a verdict for a
#    head other than the one asked about. `gh pr checks` is bound to no sha at
#    all, and on 2026-08-27 it returned SETTLED on the FIRST look after a push
#    — six required rows, all present, all green — belonging to the head the
#    push had just superseded. Every condition SKILL.md states for SETTLED was
#    met and the answer described a commit nobody was shipping; the real checks
#    for the new head were a different run id and settled ~9 minutes later
#    (computenet-00d8). That is a green nobody earned, at the gate that decides
#    a merge, and auto-merge lands a ready PR within a minute of it. The
#    earlier computenet-qnyn hazard — a PR head observed lagging the pushed ref
#    by ~10 minutes, with nothing in the output saying so — is the same defect
#    seen from the other side.
#
# So each round resolves `.head.sha` and reads `commits/<sha>/check-runs`, and
# the sha it answered about is printed with the verdict. After FALLBACK_AFTER
# consecutive rounds that produce no rows it re-reads over `gh pr checks` and
# says which transport answered — a reading NOT bound to a sha, which is why
# it is the fallback and not the default.
#
# Usage: wait-checks.sh <pr-url> [max-rounds]
#   Polls `commits/<head-sha>/check-runs` every 20s, up to max-rounds
#   (default 28), falling back to `gh pr checks` when REST answers nothing.
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
#
# TWO CALLS IS THE NORMAL COLD START, NOT A FAULT. 28 rounds is ~9m20s and
# build-test-fast has measured 8m56s-13m25s across a dozen PRs, so a caller
# that starts waiting when the run starts — every feature reviewer — times out
# on a perfectly healthy PR as a matter of course (computenet-ymv4 fixed the
# opposite complaint; the two constraints cannot both be met inside one 600s
# foreground call). Reported four times before anyone wrote it down
# (computenet-hil5). So on exhaustion this script says WHICH required checks
# are still pending and HOW LONG they have been running, and labels the
# reading ORDINARY (under STUCK_AFTER_MIN, default 15) or STUCK. That is what
# keeps TIMEOUT-PENDING meaningful: it distinguishes "shorter waiter than
# check" from "nothing is moving". The verdict token is unchanged either way,
# so no caller's `tail -1` classification breaks.
# Stdout: one progress line per round, the head sha being judged, then the last
#   rows, then the verdict as the FINAL line — exactly one of SETTLED /
#   TIMEOUT-PENDING / QUERY-FAILED — so `tail -1` is the reading. SETTLED means present-and-not-pending, which
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

# Minutes a required check may be running before an exhausted wait is called
# STUCK rather than ORDINARY. 15 sits above the slowest measured
# build-test-fast (13m25s) with a little headroom.
STUCK_AFTER_MIN=${WAIT_CHECKS_STUCK_AFTER_MIN:-15}

# The primary reading. Prints rows in `gh pr checks`'s own shape (name, status
# word, conclusion) so every classifier below is unchanged.
# Silent failure here is not a green: it prints nothing, the caller's row count
# stays 0, and the round remains query-failed.
head_sha() {
  gh api "repos/{owner}/{repo}/pulls/${pr##*/}" --jq .head.sha 2>/dev/null
}

rest_rows() {
  local sha
  sha=$(head_sha) || return 1
  [ -n "$sha" ] || return 1
  gh api "repos/{owner}/{repo}/commits/$sha/check-runs?per_page=100" \
    --jq '.check_runs[] | "\(.name)\t\(if .status != "completed" then "pending"
                              elif .conclusion == "success" then "pass"
                              elif .conclusion == "skipped" then "skipping"
                              else "fail" end)\t\(.conclusion // "-")"' 2>/dev/null
}

# "<name> <minutes>" for every still-running check run on the head commit.
# Read over REST because `gh pr checks` prints elapsed 0 for a pending row —
# the age is only in check-runs' started_at. Called once, on exhaustion.
pending_ages() {
  local sha
  sha=$(gh api "repos/{owner}/{repo}/pulls/${pr##*/}" --jq .head.sha 2>/dev/null) || return 1
  [ -n "$sha" ] || return 1
  gh api "repos/{owner}/{repo}/commits/$sha/check-runs?per_page=100" \
    --jq '.check_runs[] | select(.status != "completed") | select(.started_at)
          | "\(.name) \(((now - (.started_at|fromdateiso8601))/60)|floor)"' 2>/dev/null
}

rows=""
state=query-failed
consecutive_failed=0
judged_sha=""
for i in $(seq 1 "$rounds"); do
  judged_sha=$(head_sha)
  rows=$(rest_rows)                  # sha-bound: computenet-00d8
  n=$(printf '%s\n' "$rows" | grep -cE "$req")
  # Classify on OUTPUT: REST down, or the check suite not created yet, both
  # leave no recognizable rows.
  if [ "$n" -lt 6 ] && ! printf '%s\n' "$rows" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
    consecutive_failed=$((consecutive_failed + 1))
    if [ "$consecutive_failed" -ge "$FALLBACK_AFTER" ]; then
      # exit status deliberately not tested — `gh pr checks` exits 8 while
      # anything is pending, with well-formed rows on stdout (computenet-15it).
      graphql=$(gh pr checks "$pr" 2>&1)
      if printf '%s\n' "$graphql" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
        echo "round $i/$rounds: REST produced no rows ${consecutive_failed}x — answering over gh pr checks (NOT sha-bound)"
        rows=$graphql
        judged_sha="${judged_sha:-unknown} (gh pr checks answer is not sha-bound)"
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
    echo "wait-checks: verdict is for head $judged_sha"
    # SETTLED means no row is PENDING. A failed required check settles exactly
    # like a passing one, and the caller's next move is `gh pr ready`, which on
    # this repo merges itself — so name the red rows rather than leaving them
    # six lines above the word (computenet-2jyq).
    red=$(printf '%s\n' "$rows" | grep -E "^($req)" | grep -E '[[:space:]]fail[[:space:]]' | awk '{print $1}' | tr '\n' ' ')
    if [ -n "$red" ]; then
      echo "wait-checks: RED — required check(s) FAILED: ${red% }"
      echo "wait-checks: SETTLED is not a verdict. Do NOT gh pr ready; go to red-check-attribution.md."
    fi
    echo SETTLED
    exit 0
  fi
  [ "$i" -lt "$rounds" ] && sleep 20
done

printf '%s\n' "$rows"
echo "wait-checks: verdict is for head ${judged_sha:-unknown}"
if [ "$state" = query-failed ]; then
  echo QUERY-FAILED
  exit 3
fi
# not-reporting at exhaustion is still "checks have not settled": rows exist,
# the required set never completed — the caller's move (diagnose, re-poll) is
# the same as for a pending timeout, so both fold into TIMEOUT-PENDING.
#
# Before the verdict, say why. A required check younger than STUCK_AFTER_MIN
# means this waiter is simply shorter than the thing it waits for: re-run the
# same command. Older, or unchanged across two invocations, is a real signal.
ages=$(pending_ages | grep -E "^($req) " || true)
if [ -n "$ages" ]; then
  oldest=$(printf '%s\n' "$ages" | awk '{print $2}' | sort -rn | head -1)
  printf '%s\n' "$ages" | while read -r name mins; do
    echo "wait-checks: $name has been running ${mins}m"
  done
  if [ "$oldest" -lt "$STUCK_AFTER_MIN" ]; then
    echo "wait-checks: ORDINARY — every pending required check is under ${STUCK_AFTER_MIN}m." \
         "A cold-start settle normally takes TWO invocations; re-run this exact command."
  else
    echo "wait-checks: STUCK — a required check has run for ${oldest}m (>= ${STUCK_AFTER_MIN}m)." \
         "Investigate rather than re-running."
  fi
fi
echo TIMEOUT-PENDING
exit 4
