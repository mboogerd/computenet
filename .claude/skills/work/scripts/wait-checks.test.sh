#!/usr/bin/env bash
# Tests for wait-checks.sh. Stubs `gh` and `sleep` on PATH — the stub `gh`
# serves per-round fixtures keyed on a counter file, so a run can go
# pending -> settled without any network call, and the stub `sleep` makes 28
# rounds take milliseconds. Exits 0 if all cases pass.
#
# THE POLL IS THE REST CALL, NOT `gh pr checks`. This suite counted `gh pr
# checks` invocations as rounds, which was right until computenet-00d8 made
# REST the primary transport; after it, the counter advanced only when the
# FALLBACK fired, and 10 of 40 assertions went red on main and stayed red with
# no gate to say so (computenet-el2w). So the stub now counts
# `commits/<sha>/check-runs` — the once-per-round primary read — and serves
# `roundN.rest`; `gh pr checks` keeps its own counter (`gqlN.out`) because it
# is the fallback and fires zero or many times per round.
#
# Two `gh api` calls are deliberately NOT polls: `.head.sha` (twice in some
# rounds) and the exhaustion-time ages probe, recognized by `started_at` in its
# jq program.
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

cat > "$ROOT/bin/gh" <<'EOF'
#!/usr/bin/env bash
# `gh run list` answers the NO-RUN interrogation (computenet-a5in). It serves
# its own fixture and defaults to 1 — i.e. "a run exists" — so every case that
# predates that state is unaffected by this branch.
if [ "${1:-}" = run ] && [ "${2:-}" = list ]; then
  if [ -f "$CTRL/run-list.out" ]; then cat "$CTRL/run-list.out"; else echo 1; fi
  exit 0
fi
if [ "${1:-}" = api ]; then
  # The exhaustion-time ages probe, not a poll. Recognized by its jq program.
  case "$*" in
    *started_at*) [ -f "$CTRL/ages.out" ] && cat "$CTRL/ages.out"; exit 0 ;;
  esac
  # The required-context query (computenet-3qdo), not a poll. Defaults to the
  # six legacy contexts so every fixture predating the ruleset read is
  # unaffected; `req.out` overrides, and `req-fail` makes the query answer
  # nothing, which is the degraded-fallback case.
  case "${2:-}" in
    *rules/branches*)
      [ -f "$CTRL/req-fail" ] && exit 1
      if [ -f "$CTRL/req.out" ]; then cat "$CTRL/req.out"; else
        printf '%s\n' build-test-fast build-test-serial concord-full ui-test agora-ui-test kernel-test
      fi
      exit 0 ;;
  esac
  # Head resolution, not a poll. Defaults to a sha so the primary read is
  # reachable without every fixture declaring one.
  case "${2:-}" in
    *pulls/*)
      if [ -f "$CTRL/api-sha.out" ]; then cat "$CTRL/api-sha.out"; else echo deadbeefcafe; fi
      exit 0 ;;
  esac
  # commits/<sha>/check-runs — THE poll. Missing fixture = REST answered nothing.
  n=$(cat "$CTRL/round" 2>/dev/null || echo 0); n=$((n+1)); echo "$n" > "$CTRL/round"
  f="$CTRL/round$n.rest"; [ -f "$f" ] || f="$CTRL/default.rest"
  # A REST outage writes to STDERR and exits non-zero — what `gh api` really
  # does, and what the script used to send to /dev/null (computenet-mmzm).
  [ -f "$f" ] || { [ -f "$CTRL/rest-stderr" ] && cat "$CTRL/rest-stderr" >&2; exit 1; }
  cat "$f"; exit 0
fi
# `gh pr checks` — the fallback transport, counted separately.
g=$(cat "$CTRL/gql" 2>/dev/null || echo 0); g=$((g+1)); echo "$g" > "$CTRL/gql"
f="$CTRL/gql$g.out"; [ -f "$f" ] || f="$CTRL/default.out"
[ -f "$f" ] || exit 1
cat "$f"
rc=0
if [ -f "$CTRL/gql$g.exit" ]; then rc=$(cat "$CTRL/gql$g.exit")
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

# REST rows: name, status word, conclusion — the shape rest_rows() prints.
# Tabs matter: the status word must be followed by whitespace for the
# recognizable-row grep.
GREEN=$'build-test-fast\tpass\tsuccess\nbuild-test-serial\tpass\tsuccess\nconcord-full\tpass\tsuccess\nui-test\tpass\tsuccess\nagora-ui-test\tpass\tsuccess\nkernel-test\tpass\tsuccess\nauto-merge\tskipping\tskipped'
ONE_PENDING=${GREEN/kernel-test$'\t'pass$'\t'success/kernel-test$'\t'pending$'\t'-}
ONE_FAILED=${GREEN/concord-full$'\t'pass$'\t'success/concord-full$'\t'fail$'\t'failure}
EARLY=$'auto-merge\tskipping\tskipped'
# `gh pr checks` shape, for the fallback cases only.
GQL_GREEN=$'build-test-fast\tpass\t1m2s\thttps://x\nbuild-test-serial\tpass\t2m\thttps://x\nconcord-full\tpass\t5m\thttps://x\nui-test\tpass\t1m\thttps://x\nagora-ui-test\tpass\t1m\thttps://x\nkernel-test\tpass\t4m\thttps://x\nauto-merge\tpass\t0s\thttps://x'
GQL_PENDING=${GQL_GREEN/kernel-test$'\t'pass/kernel-test$'\t'pending}
GARBAGE='error connecting to api.github.com: dial tcp: lookup failed'

# 1. all six required rows present, none pending: SETTLED on round 1
echo "settles immediately"
fixture; printf '%s\n' "$GREEN" > "$CTRL/default.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "SETTLED is the final line" \
  || bad "final line is '$(tail -1 <<<"$out")'"
has "$out" "kernel-test" "the last rows are printed"
has "$out" "verdict is for head deadbeefcafe" "the verdict names the head it judged"
[ "$(rounds_polled)" = 1 ] && ok "stopped after one poll" || bad "polled $(rounds_polled) times"
[ -f "$CTRL/sleeps" ] && bad "slept after the terminal round" || ok "no sleep on the terminal round"

# 2. THE computenet-15it TRAP: gh exits 8 while pending, rows well-formed.
#    Must read as unsettled (keep polling), never as a query failure. Reached
#    over the FALLBACK, which is the only path `gh pr checks`'s exit status
#    can still be seen on.
echo
echo "pending with exit 8 is not a query failure (computenet-15it)"
fixture                                        # no *.rest: REST answers nothing
printf '%s\n' "$GQL_PENDING" > "$CTRL/gql1.out"; echo 8 > "$CTRL/gql1.exit"
printf '%s\n' "$GQL_GREEN"   > "$CTRL/gql2.out"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "settles once the fallback answers green, exit 0" || bad "exits $rc, wanted 0"
hasnt "$out" "QUERY FAILED" "exit 8 with rows never prints QUERY FAILED"
has "$out" "still pending" "the pending rounds are narrated"
has "$out" "answering over gh pr checks" "the transport that answered is announced"
[ "$(rounds_polled)" = 4 ] && ok "polled 4 rounds (3 before the fallback fires)" \
  || bad "polled $(rounds_polled)"
[ "$(wc -l < "$CTRL/sleeps" | tr -d ' ')" = 3 ] && ok "slept between rounds, not after" \
  || bad "slept $(wc -l < "$CTRL/sleeps" | tr -d ' ') times, wanted 3"

# 3. a settled reading that includes a FAILED check is still SETTLED — the
#    caller reads the rows for red, and the script names them.
echo
echo "settled includes failed checks"
fixture; printf '%s\n' "$ONE_FAILED" > "$CTRL/default.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0 (settled, not green)" || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "SETTLED final line" || bad "final: $(tail -1 <<<"$out")"
has "$out" $'concord-full\tfail' "the failed row is in the printed rows"
has "$out" "RED — required check(s) FAILED: concord-full" "the red row is named, not left to be read"
has "$out" "Do NOT gh pr ready" "SETTLED-with-red says not to ship"

# 4. THE computenet-1zhu TRAP: only the auto-merge row for the first rounds.
#    No pending anywhere, but it must NOT read as settled OR as a failure.
echo
echo "not-yet-reporting is neither settled nor failed (computenet-1zhu)"
fixture
printf '%s\n' "$EARLY" > "$CTRL/round1.rest"
printf '%s\n' "$EARLY" > "$CTRL/round2.rest"
printf '%s\n' "$GREEN" > "$CTRL/round3.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "waits through the early state and settles" || bad "exits $rc, wanted 0"
has "$out" "only 0 of 6 required rows reporting" "early rounds narrated as not-yet-reporting"
hasnt "$out" "QUERY FAILED" "early state is not a query failure"

# 5. timeout while pending: TIMEOUT-PENDING, exit 4, last rows printed
echo
echo "timeout while pending"
fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.rest"
out=$(run 3); rc=$?
[ "$rc" -eq 4 ] && ok "exits 4" || bad "exits $rc, wanted 4"
[ "$(tail -1 <<<"$out")" = "TIMEOUT-PENDING" ] && ok "TIMEOUT-PENDING final line" \
  || bad "final: $(tail -1 <<<"$out")"
has "$out" $'kernel-test\tpending' "the last rows are printed above the verdict"
[ "$(rounds_polled)" = 3 ] && ok "max-rounds argument respected" || bad "polled $(rounds_polled)"

# 5b. exhaustion says WHY: a young pending check is ORDINARY, an old one STUCK
#     (computenet-hil5). Two invocations is the normal cold settle.
fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.rest"
printf 'kernel-test 4\n' > "$CTRL/ages.out"
out=$(run 2)
has "$out" "kernel-test has been running 4m" "the pending check's age is reported"
has "$out" "ORDINARY" "a young pending check is ordinary, not stuck"

fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.rest"
printf 'kernel-test 22\n' > "$CTRL/ages.out"
out=$(run 2)
has "$out" "STUCK" "a long-running required check is called stuck"

# 6. timeout while never fully reporting folds into TIMEOUT-PENDING too
echo
echo "timeout while not-yet-reporting"
fixture; printf '%s\n' "$EARLY" > "$CTRL/default.rest"
out=$(run 2); rc=$?
[ "$rc" -eq 4 ] && ok "exits 4" || bad "exits $rc, wanted 4"
[ "$(tail -1 <<<"$out")" = "TIMEOUT-PENDING" ] && ok "TIMEOUT-PENDING final line" \
  || bad "final: $(tail -1 <<<"$out")"

# 7. no recognizable rows at all, every round: QUERY-FAILED, exit 3.
#    Past the cold-start window the rounds say QUERY FAILED; a run exists for
#    the head (the stub's default), so this is not NO-RUN.
echo
echo "query failure"
fixture; printf '%s\n' "$GARBAGE" > "$CTRL/default.out"   # both transports mute
out=$(WAIT_CHECKS_COLD_ROUNDS=1 run 3); rc=$?
[ "$rc" -eq 3 ] && ok "exits 3" || bad "exits $rc, wanted 3"
[ "$(tail -1 <<<"$out")" = "QUERY-FAILED" ] && ok "QUERY-FAILED final line" \
  || bad "final: $(tail -1 <<<"$out")"
has "$out" "QUERY FAILED" "rounds past the cold window are labelled a query failure"

# 8. a transient query failure mid-run recovers; the verdict is the LAST state
echo
echo "transient query failure recovers"
fixture
: > "$CTRL/round1.rest"                        # REST answers nothing once
printf '%s\n' "$GREEN" > "$CTRL/round2.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "settles after a transient failure" || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "SETTLED final line" || bad "final: $(tail -1 <<<"$out")"

# 9. default max-rounds is 28 (computenet-ymv4: 40 rounds outran the 600s cap)
echo
echo "default rounds"
fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.rest"
out=$(run); rc=$?
[ "$rc" -eq 4 ] && ok "times out with exit 4" || bad "exits $rc, wanted 4"
[ "$(rounds_polled)" = 28 ] && ok "polled the default 28 rounds" || bad "polled $(rounds_polled)"

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

# --- the fallback transport (computenet-fdv9, computenet-00d8) --------------
# REST is primary because it keys on the COMMIT. `gh pr checks` remains as the
# fallback for a REST outage — announced, and flagged as not sha-bound.
echo
echo "fallback to gh pr checks"
fixture
printf '%s\n' "$GQL_GREEN" > "$CTRL/default.out"          # REST mute, GraphQL fine
out=$(run 10); rc=$?
[ "$rc" -eq 0 ] && ok "a REST outage no longer blinds the wait (exits 0)" \
  || bad "exits $rc, wanted 0"
[ "$(tail -1 <<<"$out")" = "SETTLED" ] && ok "the fallback answer settles the wait" \
  || bad "final line is '$(tail -1 <<<"$out")'"
has "$out" "answering over gh pr checks" "the transport that answered is announced"
has "$out" "NOT sha-bound" "the fallback reading is flagged as not bound to a sha"

# The fallback must not fire early: a transient failure or two is not an outage.
fixture
: > "$CTRL/round1.rest"
printf '%s\n' "$GREEN" > "$CTRL/default.rest"
printf '%s\n' "$GQL_GREEN" > "$CTRL/default.out"
out=$(run 10); rc=$?
hasnt "$out" "answering over gh pr checks" "one bad round does not trigger the fallback"
[ "$rc" -eq 0 ] && ok "recovers over REST when it comes back" || bad "exits $rc"

# Both transports down: the refusal contract is preserved — never a false green.
fixture                                                    # no fixtures at all
out=$(run 5); rc=$?
[ "$rc" -eq 3 ] && ok "both transports down still reports QUERY-FAILED" || bad "exits $rc, wanted 3"
[ "$(tail -1 <<<"$out")" = "QUERY-FAILED" ] && ok "QUERY-FAILED final line" \
  || bad "final line is '$(tail -1 <<<"$out")'"

# A red check read over the fallback is still red — it must not launder it.
fixture
printf '%s\n' "${GQL_GREEN/concord-full$'\t'pass/concord-full$'\t'fail}" > "$CTRL/default.out"
out=$(run 10); rc=$?
[ "$rc" -eq 0 ] && ok "SETTLED includes red over the fallback too" || bad "exits $rc"
has "$out" "RED — required check(s) FAILED: concord-full" "the red row is named"

# --- the required set is the RULESET's, not a literal (computenet-3qdo) -----
# A literal list can only catch the absence of a check it already knows about.
# The ruleset gained a SEVENTH context on 2026-08-31; six green rows read as
# SETTLED, `gh pr ready` ran, and the PR sat BLOCKED with the missing row
# absent from the table the caller was told to read.
echo
echo "the required set is read from the ruleset"
fixture
printf '%s\n' build-test-fast build-test-serial concord-full ui-test agora-ui-test kernel-test iroh-sidecar > "$CTRL/req.out"
printf '%s\n' "$GREEN" > "$CTRL/default.rest"      # the six, iroh-sidecar ABSENT
out=$(run 3); rc=$?
[ "$rc" -eq 4 ] && ok "a required context ABSENT from the rows never reads SETTLED" \
  || bad "exits $rc, wanted 4"
has "$out" "only 6 of 7 required rows reporting" "the count comes from the ruleset, not a literal"
has "$out" "7 required contexts, read from the ruleset" "the set it is judging against is announced"

# the UNSETTLED progress line takes its count from the ruleset too. Cosmetic —
# the verdict and exit code are right either way — but "all 6 required rows
# present" under a seven-context ruleset is the same literal-drift this bead is
# about, printed to the operator who is deciding whether to ship.
fixture
printf '%s\n' build-test-fast build-test-serial concord-full ui-test agora-ui-test kernel-test iroh-sidecar > "$CTRL/req.out"
printf '%s\n' "$GREEN"$'\niroh-sidecar\tpending\t-' > "$CTRL/default.rest"
out=$(run 2)
has "$out" "all 7 required rows present, something still pending" \
  "the pending line counts against the ruleset, not a literal"

# and with the seventh present it settles
fixture
printf '%s\n' build-test-fast build-test-serial concord-full ui-test agora-ui-test kernel-test iroh-sidecar > "$CTRL/req.out"
printf '%s\n' "$GREEN"$'\niroh-sidecar\tpass\tsuccess' > "$CTRL/default.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "all seven present and green settles" || bad "exits $rc, wanted 0"

# a NEW required check is caught with no edit to this script — the whole point
fixture
printf '%s\n' build-test-fast build-test-serial concord-full ui-test agora-ui-test kernel-test iroh-sidecar brand-new-gate > "$CTRL/req.out"
printf '%s\n' "$GREEN"$'\niroh-sidecar\tpass\tsuccess' > "$CTRL/default.rest"
out=$(run 2); rc=$?
[ "$rc" -eq 4 ] && ok "a context added to the ruleset today is required today" \
  || bad "exits $rc, wanted 4"

# the ruleset unreadable: fall back, and say the reading is degraded
fixture
touch "$CTRL/req-fail"
printf '%s\n' "$GREEN"$'\niroh-sidecar\tpass\tsuccess' > "$CTRL/default.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "an unreadable ruleset still yields a verdict on the known seven" \
  || bad "exits $rc, wanted 0"
has "$out" "HARD-CODED list" "the fallback says it is a fallback"
has "$out" "does not mean the PR can merge" "and says what the degraded verdict cannot tell you"

# --- the fourth state: GitHub never built this head (computenet-a5in) -------
# Zero rows has two causes demanding opposite responses. Inside the cold-start
# window it is ordinary; past it, with no run for the head, waiting cannot help.
echo
echo "no run for the head"
fixture
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
echo deadbeef > "$CTRL/api-sha.out"
echo 0 > "$CTRL/run-list.out"
out=$(WAIT_CHECKS_COLD_ROUNDS=9 run 3); rc=$?
[ "$rc" -eq 3 ] && ok "inside the cold window, zero rows is still QUERY-FAILED" || bad "exits $rc, wanted 3"
has "$out" "COLD START — no rows yet" "early empty rounds are labelled, not silently repeated"
hasnt "$out" "QUERY FAILED" "the cold rounds do not LEAD with the words an agent learns to skip"
hasnt "$out" "NO-RUN" "it does not call NO-RUN before the cold window is over"

# --- the wall-clock budget (computenet-tl8q) -------------------------------
# A round COUNT does not bound wall clock: rounds cost 20s of sleep PLUS their
# API round trips, and the call was auto-backgrounded past the 600s foreground
# cap twice — once at load 68, once on an idle box. The deadline is what makes
# the call return a verdict instead of being cut off.
echo
echo "wall-clock budget"
fixture; printf '%s\n' "$ONE_PENDING" > "$CTRL/default.rest"
out=$(WAIT_CHECKS_DEADLINE_SECONDS=1 run 28); rc=$?
[ "$rc" -eq 4 ] && ok "an exhausted budget is TIMEOUT-PENDING, the same verdict as exhausted rounds" \
  || bad "exits $rc, wanted 4"
has "$out" "wall-clock budget 1s reached" "it says the budget, not the rounds, ended the wait"
has "$out" "TIMEOUT-PENDING" "the verdict token is unchanged, so tail -1 callers do not break"
[ "$(rounds_polled)" -le 2 ] \
  && ok "it stops on the budget instead of polling all 28 rounds" \
  || bad "polled $(rounds_polled) rounds with a 1s budget"

# A budget large enough for the rounds must not perturb anything.
fixture; printf '%s\n' "$GREEN" > "$CTRL/default.rest"
out=$(WAIT_CHECKS_DEADLINE_SECONDS=500 run); rc=$?
[ "$rc" -eq 0 ] && ok "a settling run is untouched by the budget" || bad "exits $rc, wanted 0"
hasnt "$out" "wall-clock budget" "a run that settles never mentions the budget"

# --- a REST outage says WHY (computenet-mmzm) ------------------------------
# rest_rows() discarded stderr, so a real outage printed a bare "QUERY FAILED:"
# and a 503, a DNS failure, an expired token and a rate limit were one state.
echo
echo "REST outage cause"
fixture
echo "$GARBAGE" > "$CTRL/rest-stderr"        # no .rest fixture: REST answers nothing
out=$(WAIT_CHECKS_COLD_ROUNDS=0 run 3); rc=$?
[ "$rc" -eq 3 ] && ok "an unexplained outage is still QUERY-FAILED" || bad "exits $rc, wanted 3"
has "$out" "$GARBAGE" "the round line carries the cause gh wrote to stderr"
has "$out" "The last REST error was:" "the exhaustion summary names it without a second command"
has "$out" "same REST error as round 1" "later rounds back-reference instead of repeating"
grep -qE 'QUERY FAILED: *$' <<<"$out" \
  && bad "a round line was a colon and nothing" || ok "no bare colon-and-nothing line"
# printed once, not once per round — 28 copies is how the one useful line is skipped
[ "$(grep -cF "$GARBAGE" <<<"$out")" -le 2 ] \
  && ok "the cause is not repeated every round" \
  || bad "repeated $(grep -cF "$GARBAGE" <<<"$out") times"

fixture                                       # a clean cold start says nothing extra
printf '%s\n' "$GREEN" > "$CTRL/default.rest"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "a settling run is unaffected" || bad "exits $rc, wanted 0"
hasnt "$out" "REST said:" "a healthy run never mentions a REST error"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
