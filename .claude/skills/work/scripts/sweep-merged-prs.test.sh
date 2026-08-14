#!/usr/bin/env bash
# Tests for sweep-merged-prs.sh. Self-contained: builds a throwaway git repo
# with real worktrees in a temp dir, stubs `gh` and `bd` on PATH, and deletes
# everything. Touches no real bead, no real worktree, and makes no network
# call.
#
# This script exists because sweep-merged-prs.sh is the most destructive thing
# in .claude/skills/work/scripts/ — it closes beads and deletes worktrees and
# branches, where sweep-stale-claims.sh only flips a status. An ad-hoc harness
# built during review of PR #158 surfaced two real defects in minutes (a
# swallowed `bd` failure reported as a clean sweep, and a cross-repo PR-number
# collision closing the wrong bead); both are pinned below.
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
# Assert on the combined stdout+stderr of a run.
has()    { grep -qF -- "$2" <<<"$1" && ok "$3" || bad "$3 -- output was: $(tr '\n' '|' <<<"$1")"; }
hasnt()  { grep -qF -- "$2" <<<"$1" && bad "$3 -- output was: $(tr '\n' '|' <<<"$1")" || ok "$3"; }

# ---------------------------------------------------------------- fixtures --
# A checkout whose origin is github.com/mboogerd/computenet (so the anchored
# owner/repo join has something to anchor on), plus five worktrees.
CO="$ROOT/checkout"
git init --quiet "$CO"
(
  cd "$CO"
  git config user.email t@t; git config user.name t
  git symbolic-ref HEAD refs/heads/main
  echo base > f; git add f; git commit --quiet -m base
  git remote add origin git@github.com:mboogerd/computenet.git
) >/dev/null 2>&1

WTS="$ROOT/worktrees"; mkdir -p "$WTS"
for name in clean dirty branchonly foreignrepo; do
  git -C "$CO" worktree add --quiet -b "feature/$name" "$WTS/$name" >/dev/null 2>&1
done
echo uncommitted > "$WTS/dirty/UNCOMMITTED"

STUB="$ROOT/bin"; mkdir -p "$STUB"

# `gh pr list --state merged` — every fixture PR below is MERGED.
cat > "$STUB/gh" <<'EOF'
#!/usr/bin/env bash
cat <<'JSON'
[{"number":101,"headRefName":"feature/clean"},
 {"number":102,"headRefName":"feature/dirty"},
 {"number":103,"headRefName":"feature/foreign"},
 {"number":104,"headRefName":"feature/deferred"},
 {"number":105,"headRefName":"feature/branchonly"},
 {"number":106,"headRefName":"feature/unrelated"}]
JSON
EOF
chmod +x "$STUB/gh"

# `bd`. BD_MODE picks the failure being exercised; BD_LOG records every close.
cat > "$STUB/bd" <<EOF
#!/usr/bin/env bash
mode=\${BD_MODE:-ok}
if [ "\$1" = "close" ]; then
  echo "\$2" >> "\$BD_LOG"
  [ "\$mode" = "closefail" ] && { echo "boom" >&2; exit 1; }
  exit 0
fi
[ "\$mode" = "listfail" ] && { echo "dolt: connection refused" >&2; exit 1; }
case "\$3" in
  pr)
    cat <<'JSON'
[{"id":"t-clean","status":"in_progress","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/101","branch":"feature/clean","worktree":"$WTS/clean"}},
 {"id":"t-dirty","status":"in_progress","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/102","branch":"feature/dirty","worktree":"$WTS/dirty"}},
 {"id":"t-foreign","status":"open","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/103","worktree":"/Users/SomeoneElse/projects/computenet-worktrees/t-foreign"}},
 {"id":"t-deferred","status":"deferred","metadata":{"pr":"https://github.com/mboogerd/computenet/pull/104"}},
 {"id":"t-foreignrepo","status":"in_progress","metadata":{"pr":"https://github.com/someoneelse/otherproj/pull/106","branch":"feature/foreignrepo","worktree":"$WTS/foreignrepo"}},
 {"id":"t-emptypr","status":"in_progress","metadata":{"pr":"","branch":"feature/branchonly"}}]
JSON
    ;;
  branch)
    cat <<'JSON'
[{"id":"t-emptypr","status":"in_progress","metadata":{"pr":"","branch":"feature/branchonly"}},
 {"id":"t-nomatch","status":"open","metadata":{"branch":"feature/never-merged"}}]
JSON
    ;;
esac
EOF
chmod +x "$STUB/bd"

run() { ( cd "$CO" && PATH="$STUB:$PATH" BD_LOG="$BD_LOG" "$SCRIPT" "$@" 2>&1 ); }

# ------------------------------------------------------------------ dry run --
echo "dry-run: reports without changing anything"
BD_LOG="$ROOT/log.dry"; : > "$BD_LOG"
out=$(run --dry-run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0" || bad "exits $rc, wanted 0"
has "$out" "would close: t-clean" "clean worktree bead would be closed"
has "$out" "would remove worktree: $WTS/clean" "clean worktree would be removed"
[ -s "$BD_LOG" ] && bad "dry run called bd close" || ok "dry run called no bd close"
[ -d "$WTS/clean" ] && ok "dry run left the worktree on disk" || bad "dry run removed a worktree"

# -------------------------------------------------------------- the six cases --
echo
echo "real run: the six join/gate cases"
BD_LOG="$ROOT/log.real"; : > "$BD_LOG"
out=$(run); rc=$?
[ "$rc" -eq 0 ] && ok "exits 0 when every item succeeds" || bad "exits $rc, wanted 0"

# 1. clean -> closed, worktree removed, local branch deleted.
has "$out" "closed: t-clean" "clean: bead closed"
[ -d "$WTS/clean" ] && bad "clean: worktree still on disk" || ok "clean: worktree removed"
git -C "$CO" show-ref --quiet --verify refs/heads/feature/clean \
  && bad "clean: local branch survived" || ok "clean: local branch deleted"

# 2. dirty -> closed, worktree LEFT ALONE. The one gate that must never yield.
has "$out" "DIRTY, left in place: $WTS/dirty" "dirty: worktree reported, not removed"
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
#    merged; someoneelse/otherproj#106 is not ours. (Defect found in review.)
grep -qx t-foreignrepo "$BD_LOG" && bad "foreign repo: closed on a colliding PR number" \
  || ok "foreign repo: colliding PR number did not close the bead"
[ -d "$WTS/foreignrepo" ] && ok "foreign repo: worktree untouched" \
  || bad "foreign repo: worktree removed"

# ------------------------------------------------------------------ failures --
echo
echo "failure modes: never report clean"
BD_LOG="$ROOT/log.listfail"; : > "$BD_LOG"
out=$(BD_MODE=listfail run --dry-run); rc=$?
[ "$rc" -eq 3 ] && ok "unreachable tracker: exits 3" || bad "unreachable tracker: exits $rc, wanted 3"
hasnt "$out" "no beads behind a merged PR" "unreachable tracker: does NOT report a clean sweep"
has "$out" "refusing to report a clean sweep" "unreachable tracker: says why"

BD_LOG="$ROOT/log.closefail"; : > "$BD_LOG"
out=$(BD_MODE=closefail run); rc=$?
[ "$rc" -eq 1 ] && ok "failing bd close: exits 1" || bad "failing bd close: exits $rc, wanted 1"
has "$out" "FAILED to close: t-clean" "failing bd close: names the failure"
# The point of the case: one bad close must not abort the sweep.
grep -qx t-dirty "$BD_LOG" \
  && ok "failing bd close: later beads still attempted" \
  || bad "failing bd close: aborted the run, later beads unreconciled"
has "$out" "closed 0 bead(s)" "failing bd close: still prints its summary"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
