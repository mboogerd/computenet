#!/usr/bin/env bash
# Tests for verify-branch-sync.sh. Self-contained: builds a throwaway bare
# origin plus a clone per case, stubs `gh` on PATH, and deletes everything.
# Git itself is real — the fetch/ls-remote/merge-base classification is the
# thing under test, so stubbing git would test nothing.
#
# The load-bearing cases pin the two defects the prose records: an
# unreachable origin must never read as "first run, nothing to compare"
# (computenet-dtl — including the reachable-but-EMPTY remote the missing
# --exit-code exists for), and a squash-merged leftover must never read as
# an orphaning STOP (computenet-q8uv).
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verify-branch-sync.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/verify-branch-sync-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }
verdict() { tail -1 <<<"$1"; }

CASE=0
# fixture [main-msg ...] -> bare origin whose main holds "base" plus one
# commit per extra argument, and a clone CO of it. Sets ORIGIN, CO, CTRL.
fixture() {
  CASE=$((CASE+1))
  local d="$ROOT/c$CASE"
  ORIGIN="$d/origin.git"; CO="$d/co"; CTRL="$d/ctrl"; STUB="$d/bin"
  mkdir -p "$CTRL" "$STUB"
  echo '[]' > "$CTRL/prs.json"
  git init --quiet --bare "$ORIGIN"
  git init --quiet "$d/seed"
  (
    cd "$d/seed"
    git config user.email t@t; git config user.name t
    git symbolic-ref HEAD refs/heads/main
    echo base > f; git add f; git commit --quiet -m base
    for msg in "$@"; do git commit --quiet --allow-empty -m "$msg"; done
    git remote add origin "$ORIGIN"
    git push --quiet -u origin main
  ) >/dev/null 2>&1
  git clone --quiet "$ORIGIN" "$CO" 2>/dev/null
  git -C "$CO" config user.email t@t; git -C "$CO" config user.name t
  cat > "$STUB/gh" <<'EOF'
#!/usr/bin/env bash
[ -f "$CTRL/gh-fail" ] && { echo "dial tcp: can't assign requested address" >&2; exit 1; }
cat "$CTRL/prs.json"
EOF
  chmod +x "$STUB/gh"
}

branch() { # branch <name> [commit-msg] — create + commit in CO
  git -C "$CO" checkout --quiet -b "$1"
  [ $# -gt 1 ] && git -C "$CO" commit --quiet --allow-empty -m "$2"
  return 0
}
push_branch() { git -C "$CO" push --quiet -u origin "$1"; }
diverge() { # push one extra commit to origin's <branch> from a second clone
  git clone --quiet "$ORIGIN" "$ROOT/c$CASE/other" 2>/dev/null
  (
    cd "$ROOT/c$CASE/other"
    git config user.email t@t; git config user.name t
    git checkout --quiet "$1"
    git commit --quiet --allow-empty -m "pushed under you"
    git push --quiet origin "$1"
  ) >/dev/null 2>&1
}
run() { PATH="$STUB:$PATH" CTRL="$CTRL" "$SCRIPT" "$CO" "$1" 2>&1; }

BR=feature/computenet-ab12

# 1. HEAD contains origin/<branch>
echo "OK-CONTAINS"
fixture
branch "$BR" "work"; push_branch "$BR"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
[ "$(verdict "$out")" = OK-CONTAINS ] && ok "verdict is the final line" || bad "final: $(verdict "$out")"

# ...including when HEAD is strictly AHEAD of origin (unpushed local work)
fixture
branch "$BR" "work"; push_branch "$BR"
git -C "$CO" commit --quiet --allow-empty -m "unpushed"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 0 ] && [ "$(verdict "$out")" = OK-CONTAINS ] \
  && ok "strictly-ahead HEAD still contains origin" || bad "ahead: rc=$rc final=$(verdict "$out")"

# 2. origin ahead, no merged PR, no id on origin/main: the STOP stands
echo
echo "STOP-UNMERGED"
fixture
branch "$BR" "work"; push_branch "$BR"; diverge "$BR"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 1 ] && ok "exits 1" || bad "exits $rc, wanted 1"
[ "$(verdict "$out")" = STOP-UNMERGED ] && ok "STOP-UNMERGED final line" || bad "final: $(verdict "$out")"
has "$out" "origin/$BR holds commits HEAD lacks" "names the hazard"
hasnt "$out" "gh could not be consulted" "no gh caveat when gh answered"

# 3. squash leftover, found via the merged PR (gh answers)
echo
echo "SQUASH-LEFTOVER via gh"
fixture
branch "$BR" "work"; push_branch "$BR"; diverge "$BR"
echo '[{"number":262,"title":"landed by squash"}]' > "$CTRL/prs.json"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 2 ] && ok "exits 2" || bad "exits $rc, wanted 2"
[ "$(verdict "$out")" = SQUASH-LEFTOVER ] && ok "SQUASH-LEFTOVER final line" || bad "final: $(verdict "$out")"
has "$out" "merged PR #262: landed by squash" "prints the PR evidence"

# 4. squash leftover, found via the origin/main grep alone (gh sees nothing)
echo
echo "SQUASH-LEFTOVER via the origin/main grep"
fixture "landed work (computenet-ab12) (#123)"
branch "$BR" "work"; push_branch "$BR"; diverge "$BR"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 2 ] && ok "exits 2" || bad "exits $rc, wanted 2"
has "$out" "computenet-ab12" "prints the commit evidence"

# 5. the -rN suffix is stripped before extracting the id
echo
echo "-rN suffix stripping"
fixture "landed work (computenet-ab12) (#123)"
branch "$BR-r2" "work"; push_branch "$BR-r2"; diverge "$BR-r2"
out=$(run "$BR-r2"); rc=$?
[ "$rc" -eq 2 ] && ok "feature/computenet-ab12-r2 greps for computenet-ab12" \
  || bad "r2: exits $rc, wanted 2 -- $(tr '\n' '|' <<<"$out")"

# ...and a dotted child id survives extraction
fixture "landed work (computenet-dqy.2) (#27)"
branch feature/computenet-dqy.2 "work"; push_branch feature/computenet-dqy.2
diverge feature/computenet-dqy.2
out=$(run feature/computenet-dqy.2); rc=$?
[ "$rc" -eq 2 ] && ok "dotted id computenet-dqy.2 is extracted whole" \
  || bad "dotted: exits $rc, wanted 2 -- $(tr '\n' '|' <<<"$out")"

# 6. gh failed: never treated as "no merged PR" on its own
echo
echo "gh failure is not a reading"
fixture
branch "$BR" "work"; push_branch "$BR"; diverge "$BR"
touch "$CTRL/gh-fail"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 1 ] && [ "$(verdict "$out")" = STOP-UNMERGED ] \
  && ok "gh down + empty grep still STOPs" || bad "rc=$rc final=$(verdict "$out")"
has "$out" "gh could not be consulted" "the STOP carries the gh caveat"

# ...but the local grep alone can still prove the leftover
fixture "landed work (computenet-ab12) (#123)"
branch "$BR" "work"; push_branch "$BR"; diverge "$BR"
touch "$CTRL/gh-fail"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 2 ] && [ "$(verdict "$out")" = SQUASH-LEFTOVER ] \
  && ok "gh down + grep hit is still a leftover" || bad "rc=$rc final=$(verdict "$out")"

# ...and a branch with no bead id in its name cannot run the grep, so with gh
# down nothing is established and the STOP stands
fixture
branch feature/no-id-here "work"; push_branch feature/no-id-here
diverge feature/no-id-here
touch "$CTRL/gh-fail"
out=$(run feature/no-id-here); rc=$?
[ "$rc" -eq 1 ] && [ "$(verdict "$out")" = STOP-UNMERGED ] \
  && ok "no id + gh down STOPs" || bad "no-id: rc=$rc final=$(verdict "$out")"
has "$out" "no bead id in branch name" "says the grep could not run"

# 7. origin reachable, branch absent: the first-run OK
echo
echo "OK-NO-REMOTE-BRANCH"
fixture
branch "$BR" "work"                      # never pushed
out=$(run "$BR"); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
[ "$(verdict "$out")" = OK-NO-REMOTE-BRANCH ] && ok "OK-NO-REMOTE-BRANCH final line" \
  || bad "final: $(verdict "$out")"

# 8. THE --exit-code TRAP: a reachable EMPTY remote (no refs at all) must
#    read as reachable, not unreachable (computenet-dtl's guard).
echo
echo "reachable empty remote"
fixture
git init --quiet --bare "$ROOT/c$CASE/empty.git"
git -C "$CO" remote set-url origin "$ROOT/c$CASE/empty.git"
branch "$BR" "work"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 0 ] && [ "$(verdict "$out")" = OK-NO-REMOTE-BRANCH ] \
  && ok "empty remote reads as reachable-with-no-branch" \
  || bad "empty: rc=$rc final=$(verdict "$out")"

# 9. unreachable origin: NOTHING WAS CHECKED — never an OK
echo
echo "STOP-UNREACHABLE"
fixture
branch "$BR" "work"
git -C "$CO" remote set-url origin "$ROOT/c$CASE/nowhere-such-dir"
out=$(run "$BR"); rc=$?
[ "$rc" -eq 3 ] && ok "exits 3" || bad "exits $rc, wanted 3"
[ "$(verdict "$out")" = STOP-UNREACHABLE ] && ok "STOP-UNREACHABLE final line" \
  || bad "final: $(verdict "$out")"
has "$out" "NOTHING WAS CHECKED" "says the check proved nothing"
hasnt "$out" "OK" "no OK anywhere in an unreachable reading"

# 10. usage
echo
echo "usage"
out=$("$SCRIPT" 2>&1); rc=$?
[ "$rc" -ne 0 ] && ok "no args is an error" || bad "no args exited 0"
out=$("$SCRIPT" onlyone 2>&1); rc=$?
[ "$rc" -ne 0 ] && ok "one arg is an error" || bad "one arg exited 0"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
