#!/bin/sh
# Self-checks for the three feedback mechanisms. Each asserts the tool
# DISCRIMINATES — fires when it should and stays quiet when it should not —
# because a check that can only pass is the failure mode this whole lane exists
# to catch (work/references/gradle-evidence.md, "Measurements whose failure
# mode is a PASS").
#
# Runs from a worktree or the main checkout — recurrence-audit.py locates the
# export via git-common-dir when the local tree has none.
set -eu
cd "$(dirname "$0")/../../../.."
S=.claude/skills/remediate-friction/scripts
fail=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1"; fail=1; }

echo "recurrence-audit.py"
if $S/recurrence-audit.py >/dev/null 2>&1; then ok "runs against the export"
  else bad "did not run"; fi
if $S/recurrence-audit.py 2>/dev/null | grep -q 'FAILED-FIX.*computenet-l5rc.*computenet-u0b0'
  then ok "finds the known l5rc -> u0b0 recurrence"
  else bad "missed l5rc -> u0b0, which is recorded in both beads"; fi
$S/recurrence-audit.py --jsonl /nonexistent.jsonl >/dev/null 2>&1 && rc=0 || rc=$?
[ "${rc:-0}" = 3 ] && ok "exit 3 on a missing export (NOTHING checked)" \
                   || bad "missing export gave exit ${rc:-0}, expected 3"

echo "reachability.py"
if $S/reachability.py --for orchestrator .claude/skills/work/SKILL.md >/dev/null 2>&1
  then ok "orchestrator is served by its own entry document"
  else bad "orchestrator not served by SKILL.md — the graph is broken"; fi
$S/reachability.py --for implementer .claude/skills/remediate-friction/SKILL.md >/dev/null 2>&1 && rc=0 || rc=$?
[ "${rc:-0}" = 1 ] && ok "exit 1 for a file no work role reads" \
                   || bad "a work implementer appears to read this lane's SKILL.md"

echo "twin-scan.py (work skill)"
T=.claude/skills/work/scripts/twin-scan.py
n=$($T computenet-4ru 2>/dev/null | grep -c '^TWIN?' || true)
[ "$n" = 6 ] && ok "finds all 6 known computenet-4ru double-breakdown twins" \
             || bad "found $n of the 6 known 4ru twins"
$T computenet-ssa >/dev/null 2>&1 && ok "exit 0 on an epic with no twins" \
                                  || bad "flagged twins in computenet-ssa, which has none"
$T computenet-4ru >/dev/null 2>&1 && bad "exit 0 on an epic that HAS twins" \
                                  || ok "exit 1 on an epic that has twins"
$T computenet-4ru --jsonl /nonexistent.jsonl >/dev/null 2>&1 && rc=0 || rc=$?
[ "${rc:-0}" = 3 ] && ok "exit 3 on a missing export (NOTHING checked)" \
                   || bad "missing export gave exit ${rc:-0}, expected 3"

echo "validate-skills.rb line-budget ratchet"
if ruby $S/validate-skills.rb >/dev/null 2>&1; then ok "the tree is inside budget"
  else bad "tree is over budget"; fi
t=$(mktemp -d); cp .claude/skills/line-budget.txt "$t/lb"
grep -v '^work  ' .claude/skills/line-budget.txt > "$t/nolb" && cp "$t/nolb" .claude/skills/line-budget.txt
ruby $S/validate-skills.rb 2>/dev/null | grep -q "no line budget for 'work'" \
  && ok "a skill with no budget FAILS (unpriced growth is the thing it stops)" \
  || bad "a missing budget entry passed"
cp "$t/lb" .claude/skills/line-budget.txt; rm -rf "$t"
ruby $S/validate-skills.rb >/dev/null 2>&1 && ok "budget file restored, tree green" \
  || bad "restore left the tree red"

[ "$fail" = 0 ] && echo "feedback.test.sh: all checks passed" \
                || echo "feedback.test.sh: FAILURES above"
exit $fail
