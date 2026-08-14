#!/usr/bin/env bash
# The publication push, with Finalize's rejection recovery built in. Reads the
# OUTPUT, not exit codes: bd dolt push has been observed to exit 0 while
# printing a rejection (Error 1105 ... non-fast-forward), which is exactly how
# a session reports itself clean with nothing published. A non-fast-forward is
# the EXPECTED outcome of two machines pushing on a schedule, not an incident;
# the recovery is pull, then push again.
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

out=$(bd dolt push 2>&1)
printf '%s\n' "$out"
grep -qiE "rejected|error" <<<"$out" || exit 0

echo "-- push rejected; recovering: pull, then push --"
pout=$(bd dolt pull 2>&1)
printf '%s\n' "$pout"
if grep -qi "conflict" <<<"$pout"; then
  echo "ESCALATE: merge conflict — operator resolution required (runbook §3.3); state is LOCAL-ONLY" >&2
  exit 2
fi
out=$(bd dolt push 2>&1)
printf '%s\n' "$out"
if grep -qiE "rejected|error" <<<"$out"; then
  echo "ESCALATE: push failed twice; tracker state is LOCAL-ONLY" >&2
  exit 2
fi
echo "-- recovered. Now VERIFY your own writes survived the merge (see step 6) --"
