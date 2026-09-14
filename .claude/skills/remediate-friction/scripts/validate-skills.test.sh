#!/usr/bin/env bash
# Tests for validate-skills.rb's line caps, cited-path and missing-SKILL.md
# checks. Self-contained:
# builds throwaway skill trees in a temp dir, never reads .claude/skills/.
#
#   .claude/skills/remediate-friction/scripts/validate-skills.test.sh
#   .claude/skills/remediate-friction/scripts/validate-skills.test.sh /path/to/other.rb
#
# The frontmatter tier is exercised by the script's own use on every run; what
# is new here is a check that FAILS a real skill, so it needs a test that
# proves it fails for the right reason and stays quiet otherwise.
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/validate-skills.rb"}
[ -r "$SCRIPT" ] || { echo "not readable: $SCRIPT" >&2; exit 1; }

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/validate-skills-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }

# A minimal valid skill. $1 = dir name, $2 = SKILL.md line count, $3 = skill name.
skill() {
  local name=${3:-demo} n=${2:-10}
  local d="$ROOT/$1/$name"
  mkdir -p "$d/references"
  { printf -- '---\nname: %s\ndescription: A demo skill for tests.\n---\n' "$name"
    for _ in $(seq $((n - 4))); do echo "body line"; done; } > "$d/SKILL.md"
  echo "$ROOT/$1"
}

echo "case 1: a small skill passes silently"
r=$(skill small 10); out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"
grep -q 'note:' <<<"$out" && bad "warned with nothing to warn about -- $out" || ok "no spurious note"

echo "case 2: SKILL.md at the cap passes, one line over FAILS"
r=$(skill atcap 600); out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "600 lines: exit 0" || bad "exit $rc -- $out"
r=$(skill overcap 601); out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
{ [ $rc -eq 1 ] && grep -q 'SKILL.md is 601 lines, over its cap of 600' <<<"$out"; } \
  && ok "601 lines: FAIL naming the cap" || bad "exit $rc -- $out"

echo "case 3: remediate-friction has its own tighter cap"
r=$(skill lane 151 remediate-friction); out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
{ [ $rc -eq 1 ] && grep -q 'over its cap of 150' <<<"$out"; } \
  && ok "151 lines: FAIL" || bad "exit $rc -- $out"

echo "case 4: a reference over 300 lines FAILS, at 300 passes"
r=$(skill ref 10); seq 300 > "$r/demo/references/ok.md"
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "300 lines: exit 0" || bad "exit $rc -- $out"
seq 301 > "$r/demo/references/big.md"
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
{ [ $rc -eq 1 ] && grep -q 'references/big.md is 301 lines' <<<"$out"; } \
  && ok "301 lines: FAIL naming the file" || bad "exit $rc -- $out"

echo "case 5: AGENTS.md is capped at 700"
# The script derives repo_root two levels above the skills dir.
repo="$ROOT/repo"; sk="$repo/.claude/skills"; mkdir -p "$sk"
skill repo/.claude/skills 10 >/dev/null
seq 700 > "$repo/AGENTS.md"
out=$(ruby "$SCRIPT" "$sk" 2>&1); rc=$?
{ [ $rc -eq 0 ] && grep -q 'AGENTS.md: OK' <<<"$out"; } && ok "700 lines: OK" || bad "exit $rc -- $out"
echo 701 >> "$repo/AGENTS.md"
out=$(ruby "$SCRIPT" "$sk" 2>&1); rc=$?
{ [ $rc -eq 1 ] && grep -q 'AGENTS.md: FAIL is 701 lines' <<<"$out"; } && ok "701 lines: FAIL" || bad "exit $rc -- $out"
rm "$repo/AGENTS.md"
out=$(ruby "$SCRIPT" "$sk" 2>&1); rc=$?
{ [ $rc -eq 0 ] && ! grep -q 'AGENTS.md' <<<"$out"; } && ok "absent AGENTS.md says nothing" || bad "exit $rc -- $out"

echo "case 6: a cited script path that does not resolve FAILS"
r=$(skill cite 10); echo 'run .claude/skills/demo/scripts/nope.sh' >> "$r/demo/SKILL.md"
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
{ [ $rc -eq 1 ] && grep -q 'cites .claude/skills/demo/scripts/nope.sh' <<<"$out"; } \
  && ok "names the dead path" || bad "exit $rc -- $out"

echo "case 7: a skill directory with no SKILL.md FAILS"
r=$(skill empty 10); mkdir -p "$r/orphan"
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
{ [ $rc -eq 1 ] && grep -q 'orphan: FAIL directory has no SKILL.md' <<<"$out"; } \
  && ok "exit 1" || bad "exit $rc -- $out"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
