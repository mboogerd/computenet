#!/usr/bin/env bash
# Tests for ci-executed.sh. Stubs gh through CI_EXECUTED_GH: the stub answers
# each `gh api <path>` from fixture files in $CTRL, so no case touches the
# network. Exits 0 if all cases pass.
#
# The load-bearing cases are the ones that look green by accident: a task
# replayed FROM-CACHE must not verify, a gh error body must be QUERY-FAILED
# rather than every task reading ABSENT, and a job that ran an older head than
# the PR's current one must not verify.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ci-executed.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/ci-executed-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

cat > "$ROOT/gh" <<'EOF'
#!/usr/bin/env bash
# `gh api <path> [--jq <prog>]`. Fixture per path kind; `<kind>.fail` makes the
# call print its fixture and exit 1, which is what gh does on an HTTP error.
[ "${1:-}" = api ] || exit 9
case "$2" in
  */pulls/*)       k=pr-head; n=$(cat "$CTRL/prn" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CTRL/prn"
                   [ -f "$CTRL/pr-head$n" ] && k=pr-head$n ;;
  */check-runs*)   k=ids ;;
  */runs/*/jobs*)  k=run-ids ;;
  */logs)          k=log ;;
  */jobs/*)        k=job-sha ;;
  *) exit 9 ;;
esac
[ -f "$CTRL/$k" ] && cat "$CTRL/$k"
[ -f "$CTRL/$k.fail" ] && exit 1
exit 0
EOF
chmod +x "$ROOT/gh"

PASS=0; FAIL=0
PR=https://github.com/mboogerd/computenet/pull/42

# setup <case>: fresh fixtures; a job 7 on head aaa, PR head aaa.
setup() {
  CTRL="$ROOT/$1"; mkdir -p "$CTRL"; export CTRL
  echo aaa > "$CTRL/pr-head"; echo 7 > "$CTRL/ids"; echo 7 > "$CTRL/run-ids"
  echo aaa > "$CTRL/job-sha"
}
# run <args>...: captures stdout and exit code.
run() { OUT=$(CI_EXECUTED_GH="$ROOT/gh" "$SCRIPT" "$@" 2>/dev/null); RC=$?; }
check() { # <name> <want-rc> <grep-pattern>...
  local name=$1 want=$2 ok=1; shift 2
  [ "$RC" = "$want" ] || ok=0
  for p in "$@"; do printf '%s\n' "$OUT" | grep -qE -- "$p" || ok=0; done
  if [ $ok = 1 ]; then PASS=$((PASS+1)); echo "  ok   $name"
  else FAIL=$((FAIL+1)); echo "  FAIL $name (rc=$RC, want $want)"; printf '%s\n' "$OUT" | sed 's/^/       /'; fi
}

LOGLINE() { printf '2026-10-03T10:00:00.0000000Z %s\n' "$@"; }

# --- executed: a bare task line, and CRLF endings, are EXECUTED.
setup executed
{ LOGLINE '> Task :kernel:compileKotlin' '> Task :kernel:test' 'FooTest > bar() PASSED'
  printf '2026-10-03T10:00:01.0Z > Task :wire:test\r\n'; } > "$CTRL/log"
run "$PR" kernel-test :kernel:test :wire:test
check "executed tasks verify" 0 '^job 7 kernel-test head aaa$' '^:kernel:test EXECUTED$' '^:wire:test EXECUTED$' '^VERIFIED$'

# --- FROM-CACHE: green check, replayed task — must not verify.
setup cache
LOGLINE '> Task :kernel:test FROM-CACHE' '> Task :wire:test' > "$CTRL/log"
run "$PR" kernel-test :kernel:test :wire:test
check "FROM-CACHE does not verify" 1 '^:kernel:test FROM-CACHE$' '^:wire:test EXECUTED$' '^NOT-VERIFIED$'

# --- other outcomes, ABSENT, and the skipped-test count.
setup outcomes
LOGLINE '> Task :a:test UP-TO-DATE' '> Task :b:test NO-SOURCE' '> Task :c:test SKIPPED' \
        '> Task :d:test' 'XTest > y() SKIPPED' 'XTest > z() SKIPPED' '> Task :e:test FAILED' > "$CTRL/log"
run "$PR" fast :a:test :b:test :c:test :d:test :e:test :f:test
check "each outcome is named" 1 '^:a:test UP-TO-DATE$' '^:b:test NO-SOURCE$' '^:c:test SKIPPED$' \
      '^:d:test EXECUTED$' '^:e:test FAILED$' '^:f:test ABSENT$' '^skipped-tests 2$' '^NOT-VERIFIED$'

# --- a task name that prefixes another is not matched by it.
setup prefix
LOGLINE '> Task :kernel:testClasses' > "$CTRL/log"
run "$PR" kernel-test :kernel:test
check "a longer task name is not this task" 1 '^:kernel:test ABSENT$'

# --- the log fetch returns a JSON error body: QUERY-FAILED, not ABSENT.
setup errbody
echo '{"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}' > "$CTRL/log"
touch "$CTRL/log.fail"
run "$PR" kernel-test :kernel:test
check "error exit is QUERY-FAILED" 2 '^QUERY-FAILED .*Not Found'
printf '%s\n' "$OUT" | grep -q ABSENT && { FAIL=$((FAIL+1)); echo "  FAIL error exit printed ABSENT rows"; }
echo 'gh: HTTP 502: Bad Gateway' > "$CTRL/log"
run "$PR" kernel-test :kernel:test
check "non-JSON error exit is QUERY-FAILED" 2 '^QUERY-FAILED .*502'
echo '{"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}' > "$CTRL/log"
rm -f "$CTRL/log.fail"
run "$PR" kernel-test :kernel:test
check "error body with exit 0 is QUERY-FAILED" 2 '^QUERY-FAILED .*error body'

# --- empty log, missing job, ambiguous job.
setup nolog
: > "$CTRL/log"
run "$PR" kernel-test :kernel:test
check "empty log is QUERY-FAILED" 2 '^QUERY-FAILED .*empty'
setup nojob
: > "$CTRL/ids"
run "$PR" kernel-test :kernel:test
check "no job of that name is QUERY-FAILED" 2 "^QUERY-FAILED 0 jobs named 'kernel-test'"
setup twojobs
printf '7\n8\n' > "$CTRL/ids"
run "$PR" kernel-test :kernel:test
check "two jobs of that name is QUERY-FAILED" 2 '^QUERY-FAILED 2 jobs'

# --- sha mismatch: the PR head moved past the job's head.
setup stale
LOGLINE '> Task :kernel:test' > "$CTRL/log"
echo aaa > "$CTRL/pr-head1"; echo bbb > "$CTRL/pr-head2"
run "$PR" kernel-test :kernel:test
check "a job on an older head does not verify" 1 '^:kernel:test EXECUTED$' '^STALE-HEAD job ran aaa, PR head is now bbb$' '^NOT-VERIFIED$'

# --- run-id mode: no head comparison, jobs read from the run.
setup runid
LOGLINE '> Task :kernel:test' > "$CTRL/log"; : > "$CTRL/ids"; echo zzz > "$CTRL/pr-head"
run 36391699312 kernel-test :kernel:test
check "run-id mode selects from the run" 0 '^:kernel:test EXECUTED$' '^VERIFIED$'

# --- usage.
setup usage
run "$PR" kernel-test
check "too few args is usage" 64
run "$PR" kernel-test kernel:test
check "task without leading colon is usage" 64

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
