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
# status rows at all), not-yet-reporting (rows exist but fewer than the ruleset
# requires), unsettled (all present, something pending) — are one state to
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
#   (default 28) OR the wall-clock budget below, whichever comes first,
#   falling back to `gh pr checks` when REST answers nothing.
#
# WHY 28 AND NOT 40. Every dispatch prompt in this skill runs verification in
# ONE foreground Bash call with an explicit timeout of at most 600000 ms. The
# old default of 40 rounds is ~13m20s, so a dispatched agent invoking this the
# documented way was CUT OFF BY THE CAP before the loop could reach a verdict —
# and a cut-off call looks like nothing at all, not like TIMEOUT-PENDING
# (computenet-ymv4). 28 rounds is ~9m20s of SLEEP, which LOOKED inside the cap
# and was not: the per-round API calls put it at ~602s on a quiet box, and it
# was auto-backgrounded twice. THE BINDING BOUND IS NOW THE WALL-CLOCK DEADLINE
# BELOW, not this count — the count is only an upper limit on rounds. In
# practice the deadline ends the wait first, at ~8m10s over ~23 rounds
# (computenet-tl8q). A caller with a longer budget passes max-rounds AND raises
# WAIT_CHECKS_DEADLINE_SECONDS; raising either alone changes nothing.
#
# TWO CALLS IS THE NORMAL COLD START, NOT A FAULT. The window is ~8m10s and
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
#   have not settled); 5 = NO-RUN (GitHub never started a workflow run for this
#   head — see below); 3 = QUERY-FAILED (the last round produced no
#   recognizable rows — nothing was read); 2 = bad usage.
#
# THE FOURTH STATE: GITHUB NEVER BUILT THIS HEAD (computenet-a5in). Zero rows
# has two causes that demand OPPOSITE responses — checks not created yet, which
# is waited out, and no run existing for this commit at all, which must never
# be. Both look identical: `total_count: 0`, `gh pr checks` saying "no checks
# reported". Measured 2026-08-26 on PR #504: head 5d1177f07 had zero check runs
# because no run was ever started for it, the last green belonged to an earlier
# head, and a session reading "no checks" as "not yet" would have waited and
# then shipped on a green that predated the code. What made it visible was
# mapping `gh run list --json headSha` against the PR head — which nothing in
# this skill prescribed. It does now: after COLD_ROUNDS with no rows, this asks
# whether a run exists for the head, and if none does it stops rather than
# spending the rest of its budget.
#
# COLD START IS NOT A QUERY FAILURE. Registering the first check row took ~4.5
# minutes on that same PR, so the first 13 rounds all printed QUERY FAILED —
# 13 lines that mean nothing is wrong, which is how an agent learns to ignore
# the one line that does. Rounds inside COLD_ROUNDS say COLD START instead.
set -uo pipefail

pr=${1:?usage: wait-checks.sh <pr-url> [max-rounds]}
rounds=${2:-28}
case "$rounds" in
  ''|*[!0-9]*) echo "wait-checks: max-rounds must be a positive integer, got '$rounds'" >&2; exit 2 ;;
esac
[ "$rounds" -ge 1 ] || { echo "wait-checks: max-rounds must be at least 1" >&2; exit 2; }

# THE REQUIRED SET IS READ FROM THE RULESET, NOT CARRIED HERE. This script's
# whole reason to exist over a bare `gh pr checks` is that it requires every
# required row PRESENT — i.e. it is the thing that catches an ABSENT required
# check. A literal list cannot do that: it can only catch the absence of a
# check it already knows about. On 2026-08-31 the ruleset gained a SEVENTH
# context (`iroh-sidecar`); this script printed six green rows and SETTLED, the
# caller ran `gh pr ready`, and the PR sat BLOCKED for eight polling rounds
# because the missing row was not in the table it was told to read
# (computenet-3qdo). The same drift bit AGENTS.md's own list on 2026-08-17
# (computenet-4prd) — twice in a fortnight, in the same direction, which is
# what a literal costs.
#
# WAIT_CHECKS_REQUIRED overrides with a whitespace-separated list (tests).
required_contexts() {
  gh api "repos/{owner}/{repo}/rules/branches/main" \
    --jq '.[] | select(.type == "required_status_checks")
          | .parameters.required_status_checks[].context' 2>/dev/null
}

req_list=${WAIT_CHECKS_REQUIRED:-$(required_contexts)}
nreq=$(printf '%s\n' $req_list | grep -c .)
if [ "$nreq" -gt 0 ]; then
  echo "wait-checks: $nreq required contexts, read from the ruleset: $(printf '%s ' $req_list)"
else
  # The fallback is a DEGRADED reading and says so: it can miss a context added
  # since this line was written, which is the exact failure it stands in for.
  req_list='build-test-fast build-test-serial concord-full ui-test agora-ui-test kernel-test'
  nreq=6
  echo "wait-checks: WARNING could not read the main ruleset — falling back to a" \
       "HARD-CODED list of $nreq contexts. A required check added since is INVISIBLE" \
       "to this run: a SETTLED verdict here does not mean the PR can merge."
fi
req=$(printf '%s\n' $req_list | paste -sd'|' -)

# How many consecutive query-failed rounds before trying the other transport.
FALLBACK_AFTER=${WAIT_CHECKS_FALLBACK_AFTER:-3}

# Rounds (20s each) in which zero rows is ORDINARY rather than a query failure.
# 15 is ~5m, above the 4.5m cold start measured in computenet-a5in. After this,
# zero rows is interrogated: a head with no workflow run is NO-RUN, terminal.
COLD_ROUNDS=${WAIT_CHECKS_COLD_ROUNDS:-15}

# THE WALL-CLOCK BUDGET, which is what actually bounds this call. Rounds do
# not cost 20s — they cost 20s plus two or three `gh api` round trips, and on a
# loaded box those are seconds each. 28 rounds is ~9m20s of SLEEP but was
# measured crossing the 600s foreground cap and being AUTO-BACKGROUNDED, twice:
# once at 5-minute load 68, and once on an idle 16-core box at load 7 — so the
# margin was never load-dependent, it simply did not exist (computenet-ymv4,
# recurred as computenet-tl8q). A round COUNT cannot bound wall clock; only a
# deadline can. Before each sleep this checks whether the next round would
# cross the budget and stops if it would, so the call returns a verdict inside
# the cap instead of being cut off — a cut-off call looks like nothing at all,
# not like TIMEOUT-PENDING. 500s leaves ~100s for the final round's API calls
# under the 600000 ms cap every dispatch prompt in this skill names.
DEADLINE_SECONDS=${WAIT_CHECKS_DEADLINE_SECONDS:-500}
case "$DEADLINE_SECONDS" in
  # Unvalidated, a non-numeric value makes the arithmetic test error every
  # round and evaluate false — silently restoring the unbounded behaviour this
  # exists to remove. Refuse loudly instead, as max-rounds does.
  ''|*[!0-9]*) echo "wait-checks: WAIT_CHECKS_DEADLINE_SECONDS must be a positive integer, got '$DEADLINE_SECONDS'" >&2; exit 2 ;;
esac
[ "$DEADLINE_SECONDS" -ge 1 ] || { echo "wait-checks: WAIT_CHECKS_DEADLINE_SECONDS must be at least 1" >&2; exit 2; }
started_at_epoch=$(date +%s)

# Minutes a required check may be running before an exhausted wait is called
# STUCK rather than ORDINARY. 15 sits above the slowest measured
# build-test-fast (13m25s) with a little headroom.
STUCK_AFTER_MIN=${WAIT_CHECKS_STUCK_AFTER_MIN:-15}

# The primary reading. Prints rows in `gh pr checks`'s own shape (name, status
# word, conclusion) so every classifier below is unchanged.
# Silent failure here is not a green: it prints nothing, the caller's row count
# stays 0, and the round remains query-failed.
# WHY THE CAUSE IS KEPT (computenet-mmzm). Both reads discarded stderr, so a
# real REST outage printed a bare `QUERY FAILED:` with nothing after the colon
# — a 503, a DNS failure, an expired token and a rate limit all look identical,
# at the gate that decides a merge. The stderr goes to $REST_ERR instead of
# /dev/null, and the round line prints it. It is printed only when it CHANGES,
# because the alternative is the same error text 28 times, which is how an
# agent learns to skip the one line that matters (the same reasoning as the
# COLD START label below).
# Refuse rather than continue: with $REST_ERR empty every redirect fails, and
# the exhaustion summary would then say "REST wrote no error — the query
# succeeded", which is the opposite of true.
REST_ERR=$(mktemp "${TMPDIR:-/tmp}/wait-checks-err.XXXXXX") \
  || { echo "wait-checks: cannot create a temp file for REST errors" >&2; exit 2; }
trap 'rm -f "$REST_ERR"' EXIT

head_sha() {
  gh api "repos/{owner}/{repo}/pulls/${pr##*/}" --jq .head.sha 2>>"$REST_ERR"
}

rest_rows() {
  local sha
  : > "$REST_ERR"
  sha=$(head_sha) || return 1
  [ -n "$sha" ] || return 1
  gh api "repos/{owner}/{repo}/commits/$sha/check-runs?per_page=100" \
    --jq '.check_runs[] | "\(.name)\t\(if .status != "completed" then "pending"
                              elif .conclusion == "success" then "pass"
                              elif .conclusion == "skipped" then "skipping"
                              else "fail" end)\t\(.conclusion // "-")"' 2>>"$REST_ERR"
}

# Sets $cause to the round's report of what REST said. NOT a command
# substitution: it has to remember the last cause across rounds, and a $(...)
# runs in a subshell whose assignment is discarded — which printed the same
# error on every one of 28 rounds. Full text the first time it is seen, a
# back-reference afterwards, so no round line is a colon and nothing.
first_err_round=""
set_cause() {
  cause=$(tr '\n' ' ' < "$REST_ERR" | tr -s ' ')
  cause=${cause% }
  if [ -z "${cause// /}" ]; then
    cause=""
  elif [ "$cause" = "$last_cause" ]; then
    cause="same REST error as round $first_err_round"
  else
    last_cause=$cause
    first_err_round=$i
    cause="REST said: $cause"
  fi
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

# Does ANY workflow run exist for this head? Distinguishes "not created yet"
# from "never built" — the two causes of zero rows (computenet-a5in). Empty
# output means no run; a failed query also prints nothing, so the caller only
# treats it as NO-RUN after the cold-start window AND with rows still absent.
runs_for_head() {
  local sha=$1
  [ -n "$sha" ] || return 1
  gh run list --limit 100 --json headSha --jq \
    "[.[] | select(.headSha == \"$sha\")] | length" 2>/dev/null
}

rows=""
state=query-failed
last_cause=""
consecutive_failed=0
judged_sha=""
for i in $(seq 1 "$rounds"); do
  judged_sha=$(head_sha)
  rows=$(rest_rows)                  # sha-bound: computenet-00d8
  # DISTINCT names, not matching lines: duplicate check-run names on one commit
  # are real (`auto-merge` appears twice on every recent PR), so a line count
  # could reach 6 with a required check absent.
  n=$(printf '%s\n' "$rows" | grep -oE "^($req)" | sort -u | grep -c .)
  # Classify on OUTPUT: REST down, or the check suite not created yet, both
  # leave no recognizable rows.
  if [ "$n" -lt "$nreq" ] && ! printf '%s\n' "$rows" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
    consecutive_failed=$((consecutive_failed + 1))
    if [ "$consecutive_failed" -ge "$FALLBACK_AFTER" ]; then
      # exit status deliberately not tested — `gh pr checks` exits 8 while
      # anything is pending, with well-formed rows on stdout (computenet-15it).
      graphql=$(gh pr checks "$pr" 2>&1)
      if printf '%s\n' "$graphql" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
        echo "round $i/$rounds: REST produced no rows ${consecutive_failed}x — answering over gh pr checks (NOT sha-bound)"
        rows=$graphql
        judged_sha="${judged_sha:-unknown} (gh pr checks answer is not sha-bound)"
        n=$(printf '%s\n' "$rows" | grep -oE "^($req)" | sort -u | grep -c .)
      fi
    fi
  else
    consecutive_failed=0
  fi
  if [ "$n" -lt "$nreq" ]; then
    if printf '%s\n' "$rows" | grep -qE '(pass|fail|pending|skipping)[[:space:]]'; then
      state=not-reporting               # normal early state (computenet-1zhu)
      echo "round $i/$rounds: only $n of $nreq required rows reporting"
    elif [ "$i" -le "$COLD_ROUNDS" ]; then
      state=query-failed                # no recognizable status rows at all
      # The LEADING WORDS are what an agent skims, so inside the window they
      # are not "QUERY FAILED" — 13 of those in a row during a ~4.5m cold
      # start is how the one line that matters gets skipped. `$rows` is still
      # printed: it is empty today (rest_rows discards stderr, and the
      # fallback only assigns rows that already contain status words), but
      # printing it costs nothing and stops this line from lying if that
      # changes.
      set_cause
      echo "round $i/$rounds: COLD START — no rows yet, ordinary for the" \
           "first ${COLD_ROUNDS} rounds: $rows${cause:+ [$cause]}"
    else
      state=query-failed
      # Past the cold-start window, zero rows is INTERROGATED EACH ROUND rather
      # than waited out: a head GitHub never built never acquires rows, and
      # every remaining round would be spent confirming that (computenet-a5in).
      # SCOPE: this asks whether a RUN EXISTS, which is narrower than whether
      # the head was BUILT — `cancel-in-progress` can leave a run record with
      # no check-runs, and that diagnoses as TIMEOUT-PENDING. Safe either way:
      # neither is SETTLED, so nothing ships on it.
      nruns=$(runs_for_head "$judged_sha")
      if [ "${nruns:-x}" = 0 ]; then
        printf '%s\n' "$rows"
        echo "wait-checks: verdict is for head ${judged_sha:-unknown}"
        echo "wait-checks: NO workflow run exists for this head after $i rounds." \
             "This is NOT 'not yet' — waiting cannot fix it. PUSH AGAIN (an empty" \
             "commit is enough); ci.yml has no workflow_dispatch, so there is no" \
             "run to re-run and no trigger to fire. Never ship on a green that" \
             "belongs to a different head."
        echo NO-RUN
        exit 5
      fi
      set_cause
      # `$rows` is empty on this branch by construction, so without the cause
      # this line is a colon and nothing else (computenet-mmzm).
      echo "round $i/$rounds: QUERY FAILED: ${rows}${cause:-no REST error either — the query returned no rows}"
    fi
  elif printf '%s\n' "$rows" | grep -q pending; then
    state=unsettled
    echo "round $i/$rounds: all $nreq required rows present, something still pending"
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
  if [ "$i" -lt "$rounds" ]; then
    # +20 is the sleep about to be taken: stop when the NEXT round would cross
    # the budget, not after it already has.
    if [ $(( $(date +%s) - started_at_epoch + 20 )) -ge "$DEADLINE_SECONDS" ]; then
      echo "wait-checks: wall-clock budget ${DEADLINE_SECONDS}s reached after $i of" \
           "$rounds rounds — stopping short of the 600s foreground cap rather than" \
           "being auto-backgrounded. Rounds are slower than 20s here. This is a" \
           "TIMEOUT, not a fault: call again (two calls is the normal cold start)."
      break
    fi
    sleep 20
  fi
done

printf '%s\n' "$rows"
echo "wait-checks: verdict is for head ${judged_sha:-unknown}"
if [ "$state" = query-failed ]; then
  # The exhaustion summary carries the cause even if an identical earlier round
  # already printed it: the acceptance is that a session can name the outage
  # from this output WITHOUT running a second command, and the last line is
  # where it looks (computenet-mmzm).
  final_cause=$(tr '\n' ' ' < "$REST_ERR" | tr -s ' ')
  [ -n "${final_cause// /}" ] \
    && echo "wait-checks: nothing was read. The last REST error was: ${final_cause% }" \
    || echo "wait-checks: nothing was read, and REST wrote no error — the query" \
            "succeeded and returned no rows this head recognises."
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
