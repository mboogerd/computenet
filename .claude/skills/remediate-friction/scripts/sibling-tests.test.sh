#!/usr/bin/env bash
# Tests for sibling-tests.sh. Builds a throwaway git repo with a
# .claude/skills/<skill>/scripts/ tree, commits a base, mutates it, and checks
# which suites the runner selects and what it exits. Expect "19 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sibling-tests.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

# A fresh repo per case: the runner diffs against a ref, so state must not leak.
ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/sibling-tests.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
CASE=0
mkrepo() {
  CASE=$((CASE+1)); R="$ROOT/c$CASE"
  mkdir -p "$R/.claude/skills/demo/scripts"
  ( cd "$R" && git init -q . && git config user.email t@t && git config user.name t )
  D="$R/.claude/skills/demo/scripts"
  printf '#!/usr/bin/env bash\necho hi\n' > "$D/foo.sh"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$D/foo.test.sh"
  ( cd "$R" && git add -A && git commit -qm base && git branch -f basepoint )
}
run() { ( cd "$R" && bash "$SCRIPT" basepoint 2>&1 ); }

echo "nothing changed"
mkrepo
out=$(run); rc=$?
[ "$rc" = 0 ] && ok "exits 0 when no script changed" || bad "exits $rc, wanted 0"
has "$out" "nothing to run" "it says why it ran nothing"

echo
echo "a changed script runs its name sibling"
mkrepo
echo '# edit' >> "$D/foo.sh"; ( cd "$R" && git add -A && git commit -qm edit )
out=$(run); rc=$?
[ "$rc" = 0 ] && ok "a passing suite exits 0" || bad "exits $rc, wanted 0"
has "$out" "PASS      .claude/skills/demo/scripts/foo.sh -> foo.test.sh" "the suite it ran is named"

echo
echo "a RED sibling suite fails the gate — the whole point"
mkrepo
printf '#!/usr/bin/env bash\nexit 1\n' > "$D/foo.test.sh"
echo '# edit' >> "$D/foo.sh"; ( cd "$R" && git add -A && git commit -qm edit )
out=$(run); rc=$?
[ "$rc" = 1 ] && ok "a red suite exits 1" || bad "exits $rc, wanted 1"
has "$out" "FAIL" "the failing pair is named"

echo
echo "editing only the TEST does not put it on trial as a script"
mkrepo
echo '# edit' >> "$D/foo.test.sh"; ( cd "$R" && git add -A && git commit -qm edit )
out=$(run)
has "$out" "nothing to run" "a *.test.sh edit is not itself a changed script"

echo
echo "no name sibling: a suite that MENTIONS the script is found (computenet-hkjo)"
# reachability.py's cover is feedback.test.sh; a strict name rule calls the
# lane's own tooling untested.
mkrepo
printf '#!/usr/bin/env python3\nprint(1)\n' > "$D/bar.py"
printf '#!/usr/bin/env bash\ngrep -q x /dev/null; exit 0  # covers bar.py\n' > "$D/cover.test.sh"
( cd "$R" && git add -A && git commit -qm add )
echo '# edit' >> "$D/bar.py"; ( cd "$R" && git add -A && git commit -qm edit )
out=$(run); rc=$?
[ "$rc" = 0 ] && ok "the mentioning suite is run, exit 0" || bad "exits $rc, wanted 0"
has "$out" "-> cover.test.sh" "the mentioning suite is selected"
hasnt "$out" "NO-TEST" "a covered script is not reported untested"

echo
echo "a python script runs under python3"
mkrepo
printf '#!/usr/bin/env python3\nprint(1)\n' > "$D/baz.py"
printf 'import sys; sys.exit(1)\n' > "$D/baz.test.py"
( cd "$R" && git add -A && git commit -qm add )
echo '# edit' >> "$D/baz.py"; ( cd "$R" && git add -A && git commit -qm edit )
out=$(run); rc=$?
[ "$rc" = 1 ] && ok "a red .test.py fails the gate too" || bad "exits $rc, wanted 1"
has "$out" "-> baz.test.py" "the python suite is named"

echo
echo "genuinely uncovered: reported, not fatal"
mkrepo
printf '#!/usr/bin/env bash\necho x\n' > "$D/lonely.sh"
( cd "$R" && git add -A && git commit -qm add )
echo '# edit' >> "$D/lonely.sh"; ( cd "$R" && git add -A && git commit -qm edit )
out=$(run); rc=$?
[ "$rc" = 0 ] && ok "a script with no suite does not block the ship" || bad "exits $rc, wanted 0"
has "$out" "NO-TEST" "but it is reported"

echo
echo "the gate runs BEFORE the commit — uncommitted work must be visible"
# A three-dot diff sees only committed work, so the gate would pass on exactly
# the change step 4 asks it to test.
mkrepo
printf '#!/usr/bin/env bash\nexit 1\n' > "$D/foo.test.sh"   # red, and NOT committed
echo '# edit' >> "$D/foo.sh"                                  # edited, NOT committed
out=$(run); rc=$?
[ "$rc" = 1 ] && ok "an uncommitted script edit is put on trial" || bad "exits $rc, wanted 1"
has "$out" "FAIL" "and its red suite fails the gate"

echo
echo "a brand-new untracked script is on trial too"
mkrepo
printf '#!/usr/bin/env bash\necho new\n' > "$D/newbie.sh"
printf '#!/usr/bin/env bash\nexit 1\n' > "$D/newbie.test.sh"
out=$(run); rc=$?
[ "$rc" = 1 ] && ok "an untracked script with a red suite fails the gate" || bad "exits $rc, wanted 1"
has "$out" "newbie.sh -> newbie.test.sh" "the untracked pair is named"

echo
echo "usage"
mkrepo
out=$( cd "$R" && bash "$SCRIPT" no-such-ref 2>&1 ); rc=$?
[ "$rc" = 2 ] && ok "an unknown base ref exits 2" || bad "exits $rc, wanted 2"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
