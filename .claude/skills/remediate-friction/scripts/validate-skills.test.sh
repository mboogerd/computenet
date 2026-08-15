#!/usr/bin/env bash
# Tests for validate-skills.rb's SHAPE tier — the SKILL.md line budget and the
# reference-file table-of-contents rule (added 2026-08-15). Self-contained:
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

# A minimal valid skill. $1 = dir name, $2 = body line count.
skill() {
  local d="$ROOT/$1/demo" n=${2:-10}
  mkdir -p "$d/references"
  { printf -- '---\nname: demo\ndescription: A demo skill for tests.\n---\n\n'
    for _ in $(seq "$n"); do echo "body line"; done; } > "$d/SKILL.md"
  echo "$ROOT/$1"
}

# A reference file. $1 = skill root, $2 = name, $3 = lines, $4 = with-toc|no-toc|no-sections
reference() {
  local f="$1/demo/references/$2" n=$3 shape=$4
  : > "$f"
  [ "$shape" = "with-toc" ] && printf '## Contents\n\n1. One\n2. Two\n\n' >> "$f"
  if [ "$shape" != "no-sections" ]; then printf '## 1. One\n\n## 2. Two\n\n' >> "$f"; fi
  for _ in $(seq "$n"); do echo "filler" >> "$f"; done
}

echo "case 1: a small skill with no references passes silently"
r=$(skill small 10); out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"
grep -q 'note:' <<<"$out" && bad "warned with nothing to warn about -- $out" || ok "no spurious note"

echo "case 2: a >300-line reference with sections and no Contents FAILS"
r=$(skill noToc 10); reference "$r" big.md 400 no-toc
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 1 ] && ok "exit 1" || bad "exit $rc -- $out"
grep -q 'references/big.md' <<<"$out" && ok "names the file" || bad "did not name it -- $out"

echo "case 3: the same file WITH a Contents passes"
r=$(skill withToc 10); reference "$r" big.md 400 with-toc
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"

echo "case 4: a reference under the threshold is not asked for a Contents"
r=$(skill smallRef 10); reference "$r" small.md 100 no-toc
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0" || bad "exit $rc -- $out"
grep -q 'small.md' <<<"$out" && bad "complained about a short file -- $out" || ok "quiet"

echo "case 5: a long reference with NO sections warns, and does not fail"
r=$(skill flat 10); reference "$r" flat.md 400 no-sections
out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0 (a warning, not a gate)" || bad "exit $rc -- $out"
grep -q 'no sections to index' <<<"$out" && ok "names the real problem" || bad "wrong message -- $out"

echo "case 6: a SKILL.md over the line ideal warns, and does not fail"
r=$(skill fat 600); out=$(ruby "$SCRIPT" "$r" 2>&1); rc=$?
[ $rc -eq 0 ] && ok "exit 0 (design question, not a gate)" || bad "exit $rc -- $out"
grep -qE 'body is 6[0-9][0-9] lines' <<<"$out" && ok "counts the body, not the frontmatter" || bad "bad count -- $out"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
