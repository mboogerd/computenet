#!/usr/bin/env bash
# Did a CI check EXECUTE the Gradle tasks you are about to cite as evidence?
#
# Usage: ci-executed.sh <pr-url|run-id> <check-name> <:task>...
#   <pr-url>     the job is the <check-name> check run on the PR's CURRENT head
#   <run-id>     the job is the <check-name> job of that workflow run (latest
#                attempt) — for comparing other runs during red-check attribution
#   <:task>...   concrete Gradle tasks, e.g. :kernel:test :demo:shell:test
#
# WHY. A required check can be green while the diff's tests never ran: the
# task was replayed FROM-CACHE or UP-TO-DATE (every kernel-test job for a week
# was a replay — computenet-snjsb), or an assumeTrue class reported SKIPPED
# under a green conclusion. The same blindness invalidates red-vs-green run
# comparisons: of 41 green build-test-fast runs one day, 25 took the flaky
# suite from cache and never ran it (computenet-hfgx3). Only the job log tells
# these apart, and reading it by hand means four commands and a grep that
# silently matches nothing on a JSON error body.
#
# Output: `job <id> <check-name> head <sha>`, then one row per named task:
#   <:task> EXECUTED | FROM-CACHE | UP-TO-DATE | NO-SOURCE | SKIPPED | FAILED | ABSENT
# (a `> Task :x:test` line with no outcome suffix is EXECUTED; ABSENT = the
# task was never in the build graph), then `skipped-tests <n>` when the log
# names skipped tests, then a verdict line:
#   VERIFIED      every named task EXECUTED (for a PR: on its current head)
#   NOT-VERIFIED  some task did not execute, or the job ran an older head
#   QUERY-FAILED  nothing trustworthy was read (no such job, ambiguous name,
#                 an error body instead of a log, an empty log)
# Exit: 0 VERIFIED, 1 NOT-VERIFIED, 2 QUERY-FAILED, 64 usage.
#
# A FAILED task executed, but it is not evidence of a pass, so it never
# verifies. Test stdout is NOT in the job log (Gradle does not echo it): a
# value someone must see on Linux goes in an assertion message.
#
# Env: CI_EXECUTED_GH (default `gh`) — the gh binary, stubbed by the test;
#      CI_EXECUTED_REPO (default from the PR url, else mboogerd/computenet).
set -uo pipefail

usage() { echo "usage: ci-executed.sh <pr-url|run-id> <check-name> <:task>..." >&2; exit 64; }
[ $# -ge 3 ] || usage
TARGET=$1; CHECK=$2; shift 2
for t in "$@"; do case "$t" in :*) ;; *) echo "ci-executed: task must start with ':', got '$t'" >&2; usage ;; esac; done

GH=${CI_EXECUTED_GH:-gh}
ERRF=$(mktemp "${TMPDIR:-/tmp}/ci-executed.XXXXXX"); trap 'rm -f "$ERRF"' EXIT
# qfail runs inside $(...) too, where its stdout would be captured and lost:
# it records the reason in $ERRF, and `die` prints it from the main shell.
qfail() { echo "$*" > "$ERRF"; echo "QUERY-FAILED $*"; exit 2; }
die() { echo "QUERY-FAILED $(cat "$ERRF")"; exit 2; }

PR=""; RUN=""
case "$TARGET" in
  https://github.com/*/pull/[0-9]*)
    rest=${TARGET#https://github.com/}
    REPO=${CI_EXECUTED_REPO:-$(printf '%s' "$rest" | cut -d/ -f1-2)}
    PR=$(printf '%s' "$rest" | cut -d/ -f4 | tr -cd '0-9') ;;
  *[!0-9]*|'') usage ;;
  *) RUN=$TARGET; REPO=${CI_EXECUTED_REPO:-mboogerd/computenet} ;;
esac

# Read one gh api answer; an error exit or a JSON `message` body is a failure,
# never an empty result.
api() {
  local out
  out=$("$GH" api "$@" 2>&1) || qfail "gh api $1: $(printf '%s' "$out" | head -c 300)"
  case "$out" in
    '{"message"'*|*'"documentation_url"'*) qfail "gh api $1 returned an error body: $(printf '%s' "$out" | head -c 300)" ;;
  esac
  printf '%s' "$out"
}

pr_head() { api "repos/$REPO/pulls/$PR" --jq .head.sha; }

if [ -n "$PR" ]; then
  HEAD=$(pr_head) || die
  [ -n "$HEAD" ] || qfail "PR $PR resolved no head sha"
  IDS=$(api "repos/$REPO/commits/$HEAD/check-runs?per_page=100&filter=latest" \
        --jq ".check_runs[] | select(.name==\"$CHECK\") | .id") || die
else
  IDS=$(api "repos/$REPO/actions/runs/$RUN/jobs?per_page=100&filter=latest" \
        --jq ".jobs[] | select(.name==\"$CHECK\") | .id") || die
fi
N=$(printf '%s\n' "$IDS" | grep -c '[0-9]')
[ "$N" -eq 1 ] || qfail "$N jobs named '$CHECK' (need exactly one)"
JOB=$(printf '%s' "$IDS" | tr -cd '0-9')

JOB_SHA=$(api "repos/$REPO/actions/jobs/$JOB" --jq .head_sha) || die
LOG=$(api "repos/$REPO/actions/jobs/$JOB/logs") || die
[ -n "$LOG" ] || qfail "job $JOB log is empty"
echo "job $JOB $CHECK head $JOB_SHA"

bad=0
for t in "$@"; do
  # Lines look like `<timestamp> > Task :x:test[ OUTCOME]`.
  lines=$(printf '%s\n' "$LOG" | grep -aE "> Task ${t}( |\$|\r)" | sed -E 's/\r$//')
  if [ -z "$lines" ]; then st=ABSENT
  elif printf '%s\n' "$lines" | grep -qE "> Task ${t} FAILED"; then st=FAILED
  elif printf '%s\n' "$lines" | grep -qE "> Task ${t}\$"; then st=EXECUTED
  else st=$(printf '%s\n' "$lines" | tail -1 | sed -E "s/.*> Task ${t} ([A-Z-]+).*/\\1/")
  fi
  echo "$t $st"
  [ "$st" = EXECUTED ] || bad=1
done

SK=$(printf '%s\n' "$LOG" | grep -a 'SKIPPED' | grep -vac '> Task ')
[ "$SK" -gt 0 ] && echo "skipped-tests $SK"

if [ -n "$PR" ]; then
  NOW=$(pr_head) || die
  if [ "$JOB_SHA" != "$NOW" ]; then echo "STALE-HEAD job ran $JOB_SHA, PR head is now $NOW"; bad=1; fi
fi

if [ "$bad" -eq 0 ]; then echo VERIFIED; exit 0; fi
echo NOT-VERIFIED; exit 1
