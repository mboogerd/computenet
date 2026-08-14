#!/usr/bin/env bash
# Tests for sweep-merged-prs.sh. Self-contained: builds a throwaway git repo
# with real worktrees per case, stubs `gh` and `bd` on PATH, and deletes
# everything. Touches no real bead, no real worktree, and makes no network
# call.
#
# This script exists because sweep-merged-prs.sh is the most destructive thing
# in .claude/skills/work/scripts/ — it closes beads and removes worktrees,
# where sweep-stale-claims.sh only flips a status. Two rounds of review of
# PR #158 each found real defects with an ad-hoc harness in minutes: a
# swallowed `bd` failure reported as a clean sweep, a cross-repo PR-number
# collision closing the wrong bead, and a `git branch -D` that destroyed a
# local-only commit. All three are pinned below.
#
# EVERY CASE GETS A FRESH FIXTURE. The first version shared one, so by the
# time the failure cases ran the earlier ones had already consumed the
# worktrees they needed — which is exactly why it could not assert the
# per-item-tolerance promise it claimed to cover.
#
#   .claude/skills/work/scripts/sweep-merged-prs.test.sh            # test the sibling
#   .claude/skills/work/scripts/sweep-merged-prs.test.sh /path/to/other.sh
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sweep-merged-prs.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

# pwd -P: on macOS $TMPDIR is /var/... which is a symlink to /private/var/...,
# and the script normalises paths the same way, so an unresolved ROOT makes
# every path assertion below compare two spellings of the same directory.
ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/sweep-merged-prs-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }
has()   { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- got: $(tr '\n' '|' <<<"$1")"; }
hasnt() { grep -qF -- "$2" <<<"$1" && bad "$3 -- got: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

CASE=0
# fixture [origin-url] -> fresh checkout + worktrees + stubs for ONE case.
# Sets CO, WTS, STUB, BD_LOG. Worktree "with spaces" is deliberate.
fixture() {
  CASE=$((CASE+1))
  local d="$ROOT/c$CASE"
  CO="$d/checkout"; WTS="$d/wt"; STUB="$d/bin"; BD_LOG="$d/bd.log"
  mkdir -p "$WTS" "$STUB"; : > "$BD_LOG"
  git init --quiet "$CO"
  (
    cd "$CO"
    git config user.email t@t; git config user.name t
    git symbolic-ref HEAD refs/heads/main
    echo base > f; git add f; git commit --quiet -m base
    [ "${1-}" = "no-origin" ] \
      || git remote add origin "${1:-git@github.com:mboogerd/computenet.git}"
  ) >/dev/null 2>&1
  for n in clean dirty branchonly foreignrepo extra; do
    git -C "$CO" worktree add --quiet -b "feature/$n" "$WTS/$n" >/dev/null 2>&1
  done
  # A worktree DIRECTORY containing a space. The branch name cannot hold one
  # (git check-ref-format rejects it), so only the path is spaced — which is
  # the half the script actually quotes.
  git -C "$CO" worktree add --quiet -b feature/spaced "$WTS/with spaces" >/dev/null 2>&1
  echo uncommitted > "$WTS/dirty/UNCOMMITTED"
  # A local-only commit made AFTER the PR merged — the crashed-session shape.
  (
    cd "$WTS/extra"
    git config user.email t@t; git config user.name t
    echo later > C; git add C; git commit --quiet -m "unpushed commit C"
  ) >/dev/null 2>&1
  write_stubs
}

write_stubs() {
  cat > "$STUB/gh" <<'EOF'
#!/usr/bin/env bash
[ "${GH_MODE:-ok}" = "fail" ] && { echo "gh: could not connect" >&2; exit 1; }
cat <<'JSON'
[{"number":101,"headRefName":"feature/clean"},
 {"number":102,"headRefName":"feature/dirty"},
 {"number":103,"headRefName":"feature/foreign"},
 {"number":104,"headRefName":"feature/deferred"},
 {"number":105,"headRefName":"feature/branchonly"},
 {"number":106,"headRefName":"feature/unrelated"},
 {"number":107,"headRefName":"feature/extra"},
 {"number":108,"headRefName":"feature/spaced"}]
JSON
EOF
  # BD_MODE: ok | listfail-pr | listfail-branch | closefail.
  # The two list failures are separate on purpose — with one combined mode,
  # swallowing the `pr` guard alone still left the suite green, because the
  # `branch` guard's die covered for it.
  cat > "$STUB/bd" <<EOF
#!/usr/bin/env bash
mode=\${BD_MODE:-ok}
if [ "\$1" = "close" ]; then
  echo "\$2" >> "\$BD_LOG"
  [ "\$mode" = "closefail" ] && { echo "boom" >&2; exit 1; }
  exit 0
fi
case "\$3" in
  pr)
    [ "\$mode" = "listfail-pr" ] && { echo "dolt: connection refused" >&2; exit 1; }
    cat <<'JSON'
[{"id":"t-clean","status":"in_progress","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/101","branch":"feature/clean","worktree":"$WTS/clean"}},
 {"id":"t-dirty","status":"in_progress","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/102","branch":"feature/dirty","worktree":"$WTS/dirty"}},
 {"id":"t-foreign","status":"open","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/103","worktree":"/Users/SomeoneElse/projects/computenet-worktrees/t-foreign"}},
 {"id":"t-deferred","status":"deferred","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/104"}},
 {"id":"t-foreignrepo","status":"in_progress","metadata":{"pr":"https://github.com/someoneelse/otherproj/pull/106","branch":"feature/foreignrepo","worktree":"$WTS/foreignrepo"}},
 {"id":"t-extra","status":"in_progress","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/107","branch":"feature/extra","worktree":"$WTS/extra"}},
 {"id":"t-spaces","status":"in_progress","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/108","branch":"feature/spaced","worktree":"$WTS/with spaces"}},
 {"id":"t-emptypr","status":"in_progress","metadata":{"pr":"","branch":"feature/branchonly"}}]
JSON
    ;;
  branch)
    [ "\$mode" = "listfail-branch" ] && { echo "dolt: connection refused" >&2; exit 1; }
    cat <<'JSON'
[{"id":"t-emptypr","status":"in_progress","metadata":{"pr":"","branch":"feature/branchonly"}},
 {"id":"t-nomatch","status":"open","metadata":{"branch":"feature/never-merged"}}]
JSON
    ;;
esac
EOF
  chmod +x "$STUB/gh" "$STUB/bd"
}

run() { ( cd "$CO" && PATH="$STUB:$PATH" BD_LOG="$BD_LOG" "$SCRIPT" "$@" 2>&1 ); }

# ------------------------------------------------------------------ dry run --
echo "dry-run: reports without changing anything"
fixture
out=$(run --dry-run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
has "$out" "would close: t-clean" "clean bead would be closed"
has "$out" "would remove worktree: $WTS/clean" "clean worktree would be removed"
hasnt "$out" "local branch" "says nothing about deleting a branch"
[ -s "$BD_LOG" ] && bad "dry run called bd close" || ok "dry run called no bd close"
[ -d "$WTS/clean" ] && ok "dry run left the worktree on disk" || bad "dry run removed a worktree"

# ------------------------------------------------------------ the join/gates --
echo
echo "real run: the join and gate cases"
fixture
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0 when every item succeeds" || bad "exits $rc, wanted 0"

# 1. clean -> closed, worktree removed. Branch MUST survive (see 7).
has "$out" "closed: t-clean" "clean: bead closed"
[ -d "$WTS/clean" ] && bad "clean: worktree still on disk" || ok "clean: worktree removed"

# 2. dirty -> closed, worktree LEFT ALONE. The gate that must never yield.
has "$out" "DIRTY, left in place: $WTS/dirty" "dirty: reported, not removed"
[ -f "$WTS/dirty/UNCOMMITTED" ] && ok "dirty: uncommitted file survived" \
  || bad "dirty: uncommitted work destroyed"

# 3. foreign path -> closed, nothing touched (metadata routinely carries the
#    other machine's paths).
has "$out" "worktree not on this machine" "foreign path: reported, not touched"

# 4. deferred -> reported, NEVER closed. Deferral is a deliberate park and a
#    merged PR does not revoke it (real case: computenet-dqy.2 / PR #27).
has "$out" "review by hand (NOT closed): t-deferred" "deferred: reported"
grep -qx t-deferred "$BD_LOG" && bad "deferred: was closed" || ok "deferred: not closed"

# 5. branch-only / empty metadata.pr -> reported, never closed. `pr=""` is
#    written on purpose by SKILL.md's squash-resume path and must count as
#    absent on BOTH joins, not vanish from both.
has "$out" "review by hand (NOT closed): t-emptypr" "empty pr: falls through to the branch join"
grep -qx t-emptypr "$BD_LOG" && bad "empty pr: was closed" || ok "empty pr: not closed"
hasnt "$out" "t-nomatch" "unmerged branch: not reported"

# 6. foreign REPO with a colliding PR number -> must not close. Our #106 is
#    merged; someoneelse/otherproj#106 is not ours.
grep -qx t-foreignrepo "$BD_LOG" && bad "foreign repo: closed on a colliding PR number" \
  || ok "foreign repo: colliding PR number did not close the bead"
[ -d "$WTS/foreignrepo" ] && ok "foreign repo: worktree untouched" \
  || bad "foreign repo: worktree removed"

# 7. THE DATA-LOSS REGRESSION. A clean worktree whose branch holds a
#    local-only commit made after the merge: worktree goes, BRANCH STAYS, and
#    the commit stays reachable through it. Squash-merge makes "branch has
#    commits not in main" the normal state, so no gate can tell the landed
#    case from the stranded one.
[ -d "$WTS/extra" ] && bad "local commit: worktree not removed" \
  || ok "local commit: worktree removed"
git -C "$CO" show-ref --quiet --verify refs/heads/feature/extra \
  && ok "local commit: branch SURVIVED (no -D)" \
  || bad "local commit: branch deleted — the unpushed commit is now unreachable"
[ "$(git -C "$CO" log --format=%s -1 feature/extra 2>/dev/null)" = "unpushed commit C" ] \
  && ok "local commit: commit C still reachable by name" \
  || bad "local commit: C lost"
hasnt "$out" "deleted local branch" "no branch deletion is announced"

# 8. Paths with spaces survive quoting end to end.
has "$out" "removed worktree: $WTS/with spaces" "spaces: worktree removed"

# ------------------------------------------------------------------ failures --
echo
echo "failure modes: never report a clean sweep"

for mode in listfail-pr listfail-branch; do
  fixture
  out=$(BD_MODE=$mode run --dry-run); rc=$?
  [ "$rc" -eq 3 ] && ok "$mode: exits 3" || bad "$mode: exits $rc, wanted 3"
  hasnt "$out" "no beads behind a merged PR" "$mode: does NOT report a clean sweep"
  has "$out" "refusing to report a clean sweep" "$mode: says why"
done

fixture
out=$(GH_MODE=fail run --dry-run); rc=$?
[ "$rc" -eq 3 ] && ok "gh failure: exits 3" || bad "gh failure: exits $rc, wanted 3"
hasnt "$out" "no beads behind a merged PR" "gh failure: does NOT report a clean sweep"

fixture no-origin
out=$(run --dry-run); rc=$?
[ "$rc" -eq 3 ] && ok "missing origin: exits 3" || bad "missing origin: exits $rc, wanted 3"
hasnt "$out" "no beads behind a merged PR" "missing origin: does NOT report a clean sweep"

# A local-path origin parses without error but yields a slug that matches no
# PR url — the silent-clean-sweep shape the -n check alone let through.
fixture "$ROOT/some/local/path"
out=$(run --dry-run); rc=$?
[ "$rc" -eq 3 ] && ok "non-url origin: exits 3" || bad "non-url origin: exits $rc, wanted 3"
hasnt "$out" "no beads behind a merged PR" "non-url origin: does NOT report a clean sweep"

fixture
out=$(BD_MODE=closefail run); rc=$?
[ "$rc" -eq 1 ] && ok "failing bd close: exits 1" || bad "failing bd close: exits $rc, wanted 1"
has "$out" "FAILED to close: t-clean" "failing bd close: names the failure"
# The point of the case: one bad close must not abort the sweep, and the
# unreconciled bead keeps its worktree.
grep -qx t-dirty "$BD_LOG" \
  && ok "failing bd close: later beads still attempted" \
  || bad "failing bd close: aborted the run, later beads unreconciled"
[ -d "$WTS/clean" ] && ok "failing bd close: worktree kept for the failed bead" \
  || bad "failing bd close: removed the worktree of a bead that did not close"
has "$out" "closed 0 bead(s)" "failing bd close: still prints its summary"

# Worktree removal fails -> exit 1, and the branch is (still) untouched.
fixture
cat > "$STUB/git" <<'EOF'
#!/usr/bin/env bash
for a in "$@"; do [ "$a" = "remove" ] && { echo "fatal: cannot remove" >&2; exit 1; }; done
exec /usr/bin/git "$@"
EOF
chmod +x "$STUB/git"
out=$(run); rc=$?
[ "$rc" -eq 1 ] && ok "worktree-removal failure: exits 1" || bad "worktree-removal failure: exits $rc, wanted 1"
has "$out" "FAILED to remove worktree" "worktree-removal failure: named"
[ -d "$WTS/clean" ] && ok "worktree-removal failure: worktree still there" \
  || bad "worktree-removal failure: worktree vanished anyway"
/usr/bin/git -C "$CO" show-ref --quiet --verify refs/heads/feature/clean \
  && ok "worktree-removal failure: branch untouched" \
  || bad "worktree-removal failure: branch deleted"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
