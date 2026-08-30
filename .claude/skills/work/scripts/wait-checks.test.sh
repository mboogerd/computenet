#!/usr/bin/env bash
# Tests for wait-checks.sh. Stubs `gh` and `sleep` on PATH — the stub `gh`
# serves per-round fixtures keyed on a counter file, so a run can go
# pending -> settled without any network call, and the stub `sleep` makes 40
# rounds take milliseconds. Exits 0 if all cases pass.
#
# The load-bearing cases pin the two traps the script exists for:
# `gh pr checks` exiting 8 with well-formed pending rows must NOT read as a
# query failure (computenet-15it), and the early only-auto-merge output must
# NOT read as settled (computenet-1zhu).
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/wait-checks.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/wait-checks-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin"

# gh stub: round N serves $CTRL/roundN.out (falling back to default.out) and
# exits with roundN.exit / default.exit (falling back to 0). The counter file
# doubles as the "how many polls happened" probe.
cat > "$ROOT/bin/gh" <<'EOF'
#!/usr/bin/env bash
# `gh api` is the REST fallback, not a poll: it serves its own fixtures and
# deliberately does NOT advance the round counter, so poll-count probes below
# keep counting polls.
# `gh run list` answers the NO-RUN interrogation (computenet-a5in). It serves
# its own fixture and defaults to 1 — i.e. "a run exists" — so every case that
# predates that state is unaffected by this branch.
if [ "${1:-}" = run ] && [ "${2:-}" = list ]; then
  if [ -f "$CTRL/run-list.out" ]; then cat "$CTRL/run-list.out"; else echo 1; fi
  exit 0
fi
if [ "${1:-}" = api ]; then
  case "${2:-}" in
    *pulls/*) f="$CTRL/api-sha.out" ;;
    *)        f="$CTRL/api-rows.out" ;;
  esac
  [ -f "$f" ] || exit 1
  cat "$f"; exit 0
fi
n=$(cat "$CTRL/round" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CTRL/round"
f="$CTRL/round$n.out"; [ -f "$f" ] || f="$CTRL/default.out"
cat "$f"
rc=0
if [ -f "$CTRL/round$n.exit" ]; then rc=$(cat "$CTRL/round$n.exit")
elif [ -f "$CTRL/default.exit" ]; then rc=$(cat "$CTRL/default.exit"); fi
exit "$rc"
EOF
cat > "$ROOT/bin/sleep" <<'EOF'
#!/usr/bin/env bash
echo "$1" >> "$CTRL/sleeps"
exit 0
EOF
chmod +x "$ROOT/bin/gh" "$ROOT/bin/sleep"
export PATH="$ROOT/bin:$PATH"

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

CASE=0
fixture() { CASE=$((CASE+1)); export CTRL="$ROOT/c$CASE"; mkdir -p "$CTRL"; }
run() { "$SCRIPT" "https://github.com/mboogerd/computenet/pull/1" "$@" 2>&1; }
rounds_polled() { cat "$CTRL/round" 2>/dev/null || echo 0; }

# Fixture rows. Tabs matter: the status word must be followed by whitespace
# for the recognizable-row grep, exactly as gh prints it.
ALL_GREEN=$'build-test-fast\tpass\t1m2s\thttps://x\nbuild-test-serial\tpass\t2m\thttps://x\nconcord-full\tpass\t5m\thttps://x\nui-test\tpass\t1m\thttps://x\nagora-ui-test\tpass\t1m\thttps://x\nkernel-test\tpass\t4m\thttps://x\nauto-merge\tpass\t0s\thttps://x'
ONE_PENDING=${ALL_GREEN/kernel-test$'\t'pass/kernel-test$'\t'pending}
ONE_FAILED=${ALL_GREEN/concord-full$'\t'pass/concord-full$'\t'fail}
EARLY=$'auto-merge\tpass\t0s\thttps://x'
GARBAGE='error connecting to api.github.com: dial tcp: lookup failed'

# 1. all six required rows present, none pending: SETTLED on round 1
echo "settles immediately"
fixture; printf '%s\n' "$ALL_GREEN" > "$CTRL/default.out"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "SETTLED is the final line" \
  || bad "final line is '$(tail -1 <<<"$out")'"
has "$out" "kernel-test" "the last rows are printed"
[ "$(rounds_polled)" = 1 ] && ok "stopped after one poll" || bad "polled $(rounds_polled) times"
[ -f "$CTRL/sleeps" ] && bad "slept after the terminal round" || ok "no sleep on the terminal round"

# 2. THE computenet-15it TRAP: gh exits 8 while pending, rows well-formed.
#    Must read as unsettled (keep polling), never as a query failure.
echo
echo "pending with exit 8 is not a query failure (computenet-15it)"
fixture
printf '%s\n' "$ONE_PENDING" > "$CTRL/round1.out"; echo 8 > "$CTRL/round1.exit"
printf '%s\n' "$ONE_PENDING" > "$CTRL/round2.out"; echo 8 > "$CTRL/round2.exit"
printf '%s\n' "$ALL_GREEN"   > "$CTRL/round3.out"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "settles on round 3, exit 0" || bad "exits $rc, wanted 0"
hasnt "$out" "QUERY FAILED" "exit 8 with rows never prints QUERY FAILED"
has "$out" "still pending" "the pending rounds are narrated"
[ "$(rounds_polled)" = 3 ] && ok "polled exactly 3 rounds" || bad "polled $(rounds_polled)"
[ "$(wc -l < "$CTRL/sleeps" | tr -d ' ')" = 2 ] && ok "slept between rounds, not after" \
  || bad "slept $(wc -l < "$CTRL/sleeps") times, wanted 2"

# 3. a settled reading that includes a FAILED check is still SETTLED — the
#    caller reads the rows for red; gh's exit 1 there must not matter either.
echo
echo "settled includes failed checks"
fixture; printf '%s\n' "$ONE_FAILED" > "$CTRL/default.out"; echo 1 > "$CTRL/default.exit"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0 (settled, not green)" || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "SETTLED final line" || bad "final: $(tail -1 <<<"$out")"
has "$out" $'concord-full\tfail' "the failed row is in the printed rows"

# 4. THE computenet-1zhu TRAP: only the auto-merge row for the first rounds.
#    No pending anywhere, but it must NOT read as settled OR as a failure.
echo
echo "not-yet-reporting is neither settled nor failed (computenet-1zhu)"
fixture
printf '%s\n' "$EARLY" > "$CTRL/round1.out"
printf '%s\n' "$EARLY" > "$CTRL/round2.out"
printf '%s\n' "$ALL_GREEN" > "$CTRL/round3.out"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "waits through the early state and settles" || bad "exits $rc, wanted 0"
has "$out" "only 0 of 6 required rows reporting" "early rounds narrated as not-yet-reporting"
hasnt "$out" "QUERY FAILED" "early state is not a query failure"

# 5. timeout while pending: TIMEOUT-PENDING, exit 4, last rows printed
echo
echo "timeout while pending"
fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.out"; echo 8 > "$CTRL/default.exit"
out=$(run 3); rc=$?
[ "$rc" -eq 4 ] && ok "exits 4" || bad "exits $rc, wanted 4"
[ "$(tail -1 <<<"$out")" = "TIMEOUT-PENDING" ] && ok "TIMEOUT-PENDING final line" \
  || bad "final: $(tail -1 <<<"$out")"
has "$out" $'kernel-test\tpending' "the last rows are printed above the verdict"
[ "$(rounds_polled)" = 3 ] && ok "max-rounds argument respected" || bad "polled $(rounds_polled)"

# 6. timeout while never fully reporting folds into TIMEOUT-PENDING too
echo
echo "timeout while not-yet-reporting"
fixture; printf '%s\n' "$EARLY" > "$CTRL/default.out"
out=$(run 2); rc=$?
[ "$rc" -eq 4 ] && ok "exits 4" || bad "exits $rc, wanted 4"
[ "$(tail -1 <<<"$out")" = "TIMEOUT-PENDING" ] && ok "TIMEOUT-PENDING final line" \
  || bad "final: $(tail -1 <<<"$out")"

# 7. no recognizable rows at all, every round: QUERY-FAILED, exit 3
echo
echo "query failure"
fixture; printf '%s\n' "$GARBAGE" > "$CTRL/default.out"; echo 1 > "$CTRL/default.exit"
out=$(run 2); rc=$?
[ "$rc" -eq 3 ] && ok "exits 3" || bad "exits $rc, wanted 3"
[ "$(tail -1 <<<"$out")" = "QUERY-FAILED" ] && ok "QUERY-FAILED final line" \
  || bad "final: $(tail -1 <<<"$out")"
has "$out" "QUERY FAILED: $GARBAGE" "the gh error text is surfaced per round"

# 8. a transient query failure mid-run recovers; the verdict is the LAST state
echo
echo "transient query failure recovers"
fixture
printf '%s\n' "$GARBAGE" > "$CTRL/round1.out"; echo 1 > "$CTRL/round1.exit"
printf '%s\n' "$ALL_GREEN" > "$CTRL/round2.out"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "settles after a transient failure" || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "SETTLED final line" || bad "final: $(tail -1 <<<"$out")"

# 9. default max-rounds is 40
echo
echo "default rounds"
fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.out"
out=$(run); rc=$?
[ "$rc" -eq 4 ] && ok "times out with exit 4" || bad "exits $rc, wanted 4"
[ "$(rounds_polled)" = 40 ] && ok "polled the default 40 rounds" || bad "polled $(rounds_polled)"

# 10. usage errors
echo
echo "usage"
fixture
out=$("$SCRIPT" 2>&1); rc=$?
[ "$rc" -ne 0 ] && ok "no args is an error" || bad "no args exited 0"
out=$(run nonsense); rc=$?
[ "$rc" -eq 2 ] && ok "non-numeric max-rounds exits 2" || bad "exits $rc, wanted 2"
out=$(run 0); rc=$?
[ "$rc" -eq 2 ] && ok "zero max-rounds exits 2" || bad "exits $rc, wanted 2"

# --- The REST fallback (computenet-fdv9) -----------------------------------
# `gh pr checks` is GraphQL-only. Under a GraphQL outage this loop's only
# possible outcome was to spin every round and report QUERY-FAILED, unable to
# tell "endpoint down" from "checks not created yet".
REST_GREEN=$'build-test-fast\tpass\tsuccess\nbuild-test-serial\tpass\tsuccess\nconcord-full\tpass\tsuccess\nui-test\tpass\tsuccess\nagora-ui-test\tpass\tsuccess\nkernel-test\tpass\tsuccess\nauto-merge\tskipping\tskipped'

echo "REST fallback"
fixture
printf '%s\n' "$GARBAGE" > "$CTRL/default.out"          # GraphQL 503 every round
printf '%s\n' "deadbeefcafe" > "$CTRL/api-sha.out"
printf '%s\n' "$REST_GREEN" > "$CTRL/api-rows.out"
out=$(run 10); rc=$?
[ "$rc" -eq 0 ] && ok "a GraphQL outage no longer blinds the wait (exits 0)" \
  || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "REST answer settles the wait" \
  || bad "final line is '$(tail -1 <<<"$out")'"
has "$out" "answering over REST" "the transport that answered is announced"

# The fallback must not fire early: a transient failure or two is not an outage.
fixture
printf '%s\n' "$GARBAGE" > "$CTRL/round1.out"
printf '%s\n' "$ALL_GREEN" > "$CTRL/default.out"
printf '%s\n' "deadbeefcafe" > "$CTRL/api-sha.out"
printf '%s\n' "$REST_GREEN" > "$CTRL/api-rows.out"
out=$(run 10); rc=$?
hasnt "$out" "answering over REST" "one bad round does not trigger the fallback"
[ "$rc" -eq 0 ] && ok "recovers over GraphQL when it comes back" || bad "exits $rc"

# REST unavailable too: the refusal contract is preserved — never a false green.
fixture
printf '%s\n' "$GARBAGE" > "$CTRL/default.out"          # and no api-*.out fixtures
out=$(run 5); rc=$?
[ "$rc" -eq 3 ] && ok "both transports down still reports QUERY-FAILED" || bad "exits $rc, wanted 3"
[ "$(tail -1 <<<"$out")" = "QUERY-FAILED" ] || bad "final line is '$(tail -1 <<<"$out")'"

# A red check read over REST is still red — the fallback must not launder it.
fixture
printf '%s\n' "$GARBAGE" > "$CTRL/default.out"
printf '%s\n' "deadbeefcafe" > "$CTRL/api-sha.out"
printf '%s\n' "${REST_GREEN/concord-full$'\t'pass$'\t'success/concord-full$'\t'fail$'\t'failure}" > "$CTRL/api-rows.out"
out=$(run 10); rc=$?
[ "$rc" -eq 0 ] && ok "SETTLED includes red, over REST as over GraphQL" || bad "exits $rc"
has "$out" "concord-full" "the red row is printed for the caller to read"

# --- the fourth state: GitHub never built this head (computenet-a5in) -------
# Zero rows has two causes demanding opposite responses. Inside the cold-start
# window it is ordinary; past it, with no run for the head, waiting cannot help.
fixture
: > "$CTRL/default.out"                      # no rows, ever
echo deadbeef > "$CTRL/api-sha.out"          # the head resolves fine...
echo 0 > "$CTRL/run-list.out"                # ...and no run exists for it
out=$(WAIT_CHECKS_COLD_ROUNDS=2 run 6); rc=$?
[ "$rc" -eq 5 ] && ok "no run for the head exits 5, not 3" || bad "exits $rc, wanted 5"
has "$out" "NO-RUN" "the verdict names the fourth state"
has "$out" "NOT 'not yet'" "it says waiting cannot fix it"
hasnt "$out" "TIMEOUT-PENDING" "it does not spend the rest of the budget waiting"
[ "$(rounds_polled)" -le 3 ] \
  && ok "it stops at the cold-start boundary rather than polling on" \
  || bad "polled $(rounds_polled) rounds after the boundary"

fixture
: > "$CTRL/default.out"
echo deadbeef > "$CTRL/api-sha.out"
echo 0 > "$CTRL/run-list.out"
out=$(WAIT_CHECKS_COLD_ROUNDS=9 run 3); rc=$?
[ "$rc" -eq 3 ] && ok "inside the cold window, zero rows is still QUERY-FAILED" || bad "exits $rc, wanted 3"
has "$out" "COLD START — no rows yet" "early empty rounds are labelled, not silently repeated"
hasnt "$out" "QUERY FAILED" "the cold rounds do not LEAD with the words an agent learns to skip"
hasnt "$out" "NO-RUN" "it does not call NO-RUN before the cold window is over"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
