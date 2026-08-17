#!/usr/bin/env bash
# The publication push, with Finalize's rejection recovery built in. Treats a
# push as failed on EITHER signal — a nonzero exit OR a rejection in the
# output — because the two have disagreed and only one of them has been
# re-measured (computenet-kbk0).
#
# WHAT IS MEASURED. `dolt push` against a real non-fast-forward exits 1 and
# prints `! [rejected] ... (non-fast-forward)` (measured 2026-08-17 on a
# throwaway repo with a file:// remote and two diverged clones). What could
# NOT be re-measured is `bd dolt push`'s own propagation of that status:
# producing a real non-fast-forward against the shared DoltHub remote needs
# the other machine to push, so it cannot be staged safely from here. The
# historical observation stands unrefuted — bd dolt push was once seen to
# exit 0 while printing a rejection, which is exactly how a session reports
# itself clean with nothing published.
#
# So neither signal alone is trusted, and both callers agree on the pair:
# this script and scripts/beads-nightly-sync.sh. Requiring both to be clean
# is strictly safer than either — a false alarm costs one extra pull+push,
# and a missed rejection costs a session's published state.
#
# A non-fast-forward is the EXPECTED outcome of two machines pushing on a
# schedule, not an incident; the recovery is pull, then push again.
#
# Run with a generous timeout (>=300s): a push after a remote merge has been
# measured over 120s, which silently blows the default shell timeout.
#
# Exit 0: published (possibly after one pull+retry; the output says which).
#   After a RECOVERED push, verify your own writes survived the pull's merge —
#   dolt reconciles last-write-wins on updated_at, so a lost row was lost at
#   the pull and is already published: name it and park it for a human; never
#   re-apply it blind.
# Exit 2: NOT published — a real merge conflict (operator resolution,
#   doc/ops/beads-sync-runbook.md §3.3) or a second rejection. Say so at the
#   top of the session summary; local state is safe but local-only, and the
#   other machine still sees this epic's items as they were at session start.
set -uo pipefail

# One helper, so the two call sites cannot drift apart.
push_failed() {  # $1 = output, $2 = exit status
  [ "$2" -ne 0 ] && return 0
  grep -qiE "rejected|error" <<<"$1"
}

out=$(bd dolt push 2>&1); rc=$?
printf '%s\n' "$out"
push_failed "$out" "$rc" || exit 0

echo "-- push rejected; recovering: pull, then push --"
pout=$(bd dolt pull 2>&1)
printf '%s\n' "$pout"
if grep -qi "conflict" <<<"$pout"; then
  echo "ESCALATE: merge conflict — operator resolution required (runbook §3.3); state is LOCAL-ONLY" >&2
  exit 2
fi
out=$(bd dolt push 2>&1); rc=$?
printf '%s\n' "$out"
if push_failed "$out" "$rc"; then
  echo "ESCALATE: push failed twice; tracker state is LOCAL-ONLY" >&2
  exit 2
fi
echo "-- recovered. Now VERIFY your own writes survived the merge (see step 6) --"
