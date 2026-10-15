#!/bin/sh
# Self-checks for the three feedback mechanisms. Each asserts the tool
# DISCRIMINATES — fires when it should and stays quiet when it should not —
# because a check that can only pass is the failure mode this whole lane exists
# to catch (work/references/evidence.md: evidence counts only if it could have come out
# the other way).
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
# nbd5h fixtures: a repeat that never cites the fix in prose (2jx46 -> q8ksa)
# and a retro-only repeat (cz5c1) are found only through metadata.recurs_of.
F=$(mktemp "${TMPDIR:-/tmp}/recur.XXXXXX"); E='"dependencies":[{"depends_on_id":"computenet-wpvy","type":"parent-child"}]'
cat > "$F" <<EOF
{"id":"fix-a","status":"closed","close_reason":"fixed in #1","closed_at":"2026-10-06T19:25:00Z","created_at":"2026-10-01T00:00:00Z",$E}
{"id":"fix-b","status":"closed","close_reason":"fixed in #2","closed_at":"2026-10-06T19:25:00Z","created_at":"2026-10-01T00:00:00Z",$E}
{"id":"rep-a","status":"open","title":"same symptom, new words","created_at":"2026-10-07T12:02:00Z","metadata":{"recurs_of":"fix-a"},$E}
{"id":"retro-b","status":"open","issue_type":"retro","title":"retro","created_at":"2026-10-08T00:00:00Z","metadata":{"recurs_of":["fix-b"]}}
{"id":"old","status":"open","title":"fix-a recurred","created_at":"2026-09-01T00:00:00Z","metadata":{"recurs_of":"fix-b"},$E}
{"id":"rep-c","status":"open","title":"unlike fix-a in detail","created_at":"2026-10-09T00:00:00Z","metadata":{"recurs_of":"fix-a, fix-b"},$E}
{"id":"fix-c","status":"closed","close_reason":"fixed in #3","closed_at":"2026-10-09T00:00:00Z","created_at":"2026-10-08T00:00:00Z","metadata":{"recurs_of":"fix-c"},$E}
EOF
out=$($S/recurrence-audit.py --jsonl "$F" --since 2026-10-07 2>&1) || true
grep -q 'FAILED-FIX  fix-b .*->  rep-c  linked' <<<"$out" && ok "comma-string recurs_of names both fixes" || bad "comma-string recurs_of not split"
grep -q 'CITED .*rep-c' <<<"$out" && bad "linked repeat also listed as CITED" || ok "a linked pair is not re-listed as a citation"
grep -q -- '-> *fix-c' <<<"$out" && bad "self-link reported" || ok "self-link ignored"
$S/recurrence-audit.py --jsonl "$F" --since yesterday >/dev/null 2>&1 && rc=0 || rc=$?
[ "$rc" = 2 ] && ok "non-date --since is bad usage" || bad "--since yesterday gave exit $rc"
grep -q 'FAILED-FIX  fix-a .*->  rep-a  linked' <<<"$out" && ok "linked repeat without a prose citation is reported" || bad "missed linked 2jx46-shaped repeat"
grep -q 'FAILED-FIX  fix-b .*->  retro-b  linked' <<<"$out" && ok "retro-only repeat is reported" || bad "missed cz5c1-shaped retro repeat"
grep -q -- '-> *old' <<<"$out" && bad "--since listed a report from before the window" || ok "--since hides older reports"
grep -q '^1 reports created before 2026-10-07 not listed' <<<"$out" && ok "--since collapses history to one count line" || bad "no history count line"
grep -q 'carries metadata.recurs_of' <<<"$out" && bad "caveat printed although links exist" || ok "no caveat when links exist"
grep -v recurs_of "$F" > "$F.nolink"
{ $S/recurrence-audit.py --jsonl "$F.nolink" 2>&1 || true; } | grep -q "means none was linked or cited, not none happened" \
  && ok "says detection depends on links when none exist" || bad "bare 0 recurred with no links in scope"
rm -f "$F" "$F.nolink"

echo "reachability.py"
if $S/reachability.py --for orchestrator .claude/skills/work/SKILL.md >/dev/null 2>&1
  then ok "orchestrator is served by its own entry document"
  else bad "orchestrator not served by SKILL.md — the graph is broken"; fi
# A /work file the named role does NOT reach: implement.md is the implementer's own
# entry document and sits 2 hops from the orchestrator. This is the l5rc
# discrimination and it must stay exit 1. (It used to be this lane's own
# SKILL.md, which now declines instead — computenet-z9tu.)
$S/reachability.py --for orchestrator .claude/skills/work/references/implement.md >/dev/null 2>&1 && rc=0 || rc=$?
[ "${rc:-0}" = 1 ] && ok "exit 1 for a work file the named role does not read" \
                   || bad "the orchestrator appears to read the implementer's implement.md"
# Outside /work the graph has no model, and a confident NOT-READ there is a
# false negative — the script committing the defect it exists to catch.
$S/reachability.py --for implementer .claude/skills/remediate-friction/SKILL.md 2>/dev/null \
  | grep -q '^NO-MODEL' \
  && ok "declines (NO-MODEL) for a skill the role graph does not model" \
  || bad "asserted a /work-role verdict on a non-/work skill file"
$S/reachability.py --for orchestrator .claude/skills/work/scripts/check-files-claim.sh 2>/dev/null \
  | grep -q '^NO-MODEL' \
  && ok "declines for a non-.md file, which no markdown walk can reach" \
  || bad "gave a reading-chain verdict on a file that is RUN, not read"
$S/reachability.py .claude/skills/sync-report/SKILL.md 2>/dev/null \
  | grep -q "every invocation of its own skill" \
  && ok "bare form still credits a skill's own SKILL.md to its own readers" \
  || bad "bare form lost the one reachability fact that needs no graph"

# An ABSOLUTE path is what an agent produces when it has just been handed a
# worktree path. It used to match neither the decline nor the graph and came
# back a confident NOT-READ (computenet-k58k).
$S/reachability.py --for orchestrator "$PWD/.claude/skills/sync-report/SKILL.md" 2>/dev/null \
  | grep -q '^NO-MODEL' \
  && ok "an absolute path gets the same verdict as its relative form" \
  || bad "absolute path answered from a graph that cannot contain it"
$S/reachability.py --for orchestrator /etc/hosts >/dev/null 2>&1 && rc=0 || rc=$?
[ "$rc" = 2 ] \
  && ok "a path outside the repo is refused, not answered" \
  || bad "judged a path outside the repo (rc=$rc)"
# A RELATIVE path can escape the repo too (../sibling-worktree/...), and a
# guard that only covers the absolute form leaves the defect standing one
# shape over — which is how this family got to four beads. The path must
# EXIST outside the repo: a merely-missing one takes the pre-existing "does not
# exist" branch, which also exits 2, so the case would pass against the very
# defect it pins. Computed, not hard-coded, so it stays cwd-portable.
REL_ESCAPE=$(python3 -c "import os;print(os.path.relpath('/etc/hosts', os.getcwd()))")
$S/reachability.py --for orchestrator "$REL_ESCAPE" >/dev/null 2>&1 && rc=0 || rc=$?
[ "$rc" = 2 ] \
  && ok "a relative path escaping the repo is refused too" \
  || bad "judged a relative path outside the repo (rc=$rc)"

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

[ "$fail" = 0 ] && echo "feedback.test.sh: all checks passed" \
                || echo "feedback.test.sh: FAILURES above"
exit $fail
