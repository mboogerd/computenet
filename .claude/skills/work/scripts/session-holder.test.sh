#!/usr/bin/env bash
# Tests for session-holder.sh. Uses REAL processes rather than a ps stub: the
# whole point of the token is that it tracks a live OS process, and a stubbed
# ps would test the parser instead of the property. Expect "10 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/session-holder.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
check() { # token expected-word expected-rc label
  local out rc
  out=$("$SCRIPT" --check "$1" 2>&1); rc=$?
  { [ "$out" = "$2" ] && [ "$rc" = "$3" ]; } && ok "$4" || bad "$4 — got '$out' rc=$rc, wanted '$2' rc=$3"
}

export BEADS_ACTOR=${BEADS_ACTOR:-test-actor}

# 1-2. The token is well-formed and stable within one session.
tok=$("$SCRIPT"); rc=$?
[ "$rc" = 0 ] && [ -n "$tok" ] && ok "prints a token" || bad "no token (rc=$rc)"
[ "$tok" = "$("$SCRIPT")" ] && ok "the token is stable across calls" || bad "token changed between calls"

# 3. Own token is MINE — the case that must never read as a foreign holder.
check "$tok" MINE 0 "this session's own token reads MINE"

# 4-5. A live process held by a DIFFERENT actor is still LIVE: the whole point
# is that the actor cannot distinguish sessions, so the pid must.
pid=${tok#*:}; pid=${pid%%:*}
start=${tok#*:}; start=${start#*:}
check "someone-else:$pid:$start" LIVE 0 "a live pid under another actor reads LIVE"
[ -n "$start" ] && ok "the token carries a process start time" || bad "no start time in token"

# 6. A dead pid is DEAD. Start a process, capture it, let it exit.
sleep 30 & victim=$!
vstart=$(ps -o lstart= -p "$victim" 2>/dev/null | tr -s ' ' | sed 's/^ *//;s/ *$//')
check "test-actor:$victim:$vstart" LIVE 0 "a running child reads LIVE"
kill "$victim" 2>/dev/null; wait "$victim" 2>/dev/null
check "test-actor:$victim:$vstart" DEAD 1 "the same token reads DEAD once it exits"

# 7. PID REUSE: a live pid whose start time does not match is DEAD, not LIVE.
# Reading it as LIVE would deadlock an epic behind an unrelated process.
check "test-actor:$pid:Tue Jan  1 00:00:00 2020" DEAD 1 "a recycled pid reads DEAD, not LIVE"

# 8-9. Nothing established is UNKNOWN (exit 3), never an all-clear.
check "nonsense" UNKNOWN 3 "an unparseable token is UNKNOWN, not DEAD"
check "actor::start" UNKNOWN 3 "an empty pid is UNKNOWN, not DEAD"

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
