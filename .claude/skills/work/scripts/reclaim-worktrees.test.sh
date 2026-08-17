#!/usr/bin/env bash
# Tests for reclaim-worktrees.sh. Stubs `bd` and builds real git worktrees
# against a real (local, bare) origin, because the load-bearing guard is
# "HEAD is on origin" and it cannot be exercised without one.
# Expect "28 passed, 0 failed".
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/reclaim-worktrees.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(cd "$(mktemp -d "${TMPDIR:-/tmp}/reclaim-test.XXXXXX")" && pwd -P)
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/bin" "$ROOT/computenet-worktrees"

cat > "$ROOT/bin/bd" <<'EOF'
#!/usr/bin/env bash
echo "Warning: beads.role not set"      # bd prints warnings BEFORE the JSON
printf '[{"id":"%s","status":"%s"}]\n' "$2" "$(cat "$CTRL/status.$2" 2>/dev/null || echo open)"
EOF
chmod +x "$ROOT/bin/bd"
export PATH="$ROOT/bin:$PATH" CTRL="$ROOT"

REPO="$ROOT/repo"
git init -q --bare "$ROOT/origin.git"
git init -q -b main "$REPO"
git -C "$REPO" config user.email t@e
git -C "$REPO" config user.name t
git -C "$REPO" remote add origin "$ROOT/origin.git"
git -C "$REPO" commit -q --allow-empty -m base
git -C "$REPO" push -q origin main

age() { find "$1" -exec touch -t 202001010000 {} + 2>/dev/null; true; }

mk() { # id status [dirty]
  local id=$1 st=$2 dirty=${3:-} wt="$ROOT/computenet-worktrees/$1"
  echo "$st" > "$ROOT/status.$id"
  git -C "$REPO" worktree add -q -b "b/$id" "$wt" >/dev/null 2>&1
  git -C "$wt" push -q origin "b/$id" 2>/dev/null       # the normal case: branch IS on origin
  [ -n "$dirty" ] && echo scratch > "$wt/dirty.txt"
  age "$wt"
}
commit_in() { # id message  -- a local commit that is NOT pushed
  local wt="$ROOT/computenet-worktrees/$1"
  echo "$2" > "$wt/work.txt"
  git -C "$wt" add -A && git -C "$wt" commit -q -m "$2"
  age "$wt"
}
run() { (cd "$REPO" && "$SCRIPT" "$@" 2>&1); }

pass=0; fail=0
ok()    { pass=$((pass+1)); }
bad()   { fail=$((fail+1)); echo "FAIL: $1"; }
check() { local name=$1 want=$2 got=$3; if [[ "$got" == *"$want"* ]]; then ok; else bad "$name — wanted <$want> in <$got>"; fi; }
alive() { [ -d "$ROOT/computenet-worktrees/$1" ] && ok || bad "$2 — directory was REMOVED"; }
gone()  { [ -d "$ROOT/computenet-worktrees/$1" ] && bad "$2 — directory SURVIVED" || ok; }

out=$(run --dry-run); rc=$?
check "no worktrees -> says so" "no worktrees stranded" "$out"
check "no worktrees -> exit 0" "0" "$rc"

mk computenet-open open
out=$(run --dry-run)
check "open bead is left alone" "no worktrees stranded" "$out"

mk computenet-closed closed
out=$(run --dry-run)
check "closed + pushed + clean is a candidate" "would remove" "$out"
alive computenet-closed "--dry-run must not remove"

mk computenet-dirty closed dirty
out=$(run --dry-run); rc=$?
check "closed but dirty -> SKIP" "DIRTY" "$out"
check "dirty candidate -> exit 1" "1" "$rc"

# The defect this guard exists for: clean tree, closed bead, commits that
# were never pushed. Run for REAL, not --dry-run, so a regression deletes the
# directory and the test says so.
mk computenet-unpushed closed
commit_in computenet-unpushed "unreviewed precious work"
out=$(run); rc=$?
check "unpushed commits -> SKIP" "on NO remote ref" "$out"
check "unpushed commits -> names the commit" "unreviewed precious work" "$out"
check "unpushed commits -> exit 1" "1" "$rc"
alive computenet-unpushed "unpushed commits must never be removed"

# Branch origin has never heard of, but carrying NOTHING of its own: its HEAD
# is the shared base, which other origin/* refs hold. Under the by-NAME guard
# this was skipped forever; containment is what actually matters, so it is now
# reclaimed and nothing is lost (computenet-13kh).
mk computenet-nopush closed
git -C "$ROOT/origin.git" update-ref -d refs/heads/b/computenet-nopush
out=$(run); rc=$?
check "branch absent from origin but commits held elsewhere -> reclaimed" "removed" "$out"
gone computenet-nopush "a branch absent from origin whose commits ARE on origin is reclaimable"

# THE case computenet-13kh is about: a task worktree. Its branch is never
# pushed (computenet-zmso); 5c merges it into the feature branch, which IS
# pushed. The by-name guard reported "these commits exist only here" — false,
# and permanently, making computenet-8l4r's fix inert for every task.
mk computenet-task closed
commit_in computenet-task "task work merged into its feature"
git -C "$REPO" branch -q feature/holder computenet-task 2>/dev/null \
  || git -C "$REPO" branch -q feature/holder "b/computenet-task"
git -C "$REPO" push -q origin feature/holder                 # the feature branch is pushed
git -C "$ROOT/origin.git" update-ref -d refs/heads/b/computenet-task
age "$ROOT/computenet-worktrees/computenet-task"
out=$(run); rc=$?
check "task branch absent from origin but held by the feature branch -> reclaimed" "removed" "$out"
gone computenet-task "a task worktree whose commits are in the pushed feature branch is reclaimable"

# The hole a bare (non-pruning) fetch leaves. `branch -r --contains` reads a
# CACHE: push a branch, delete it on origin, and `origin/<branch>` survives
# locally unless the fetch prunes. The guard would then "prove" containment
# against a ref origin does not have and delete the only copy of the commits.
# The predecessor asked origin directly (`ls-remote`) and could not be fooled;
# --prune is what buys that authority back.
mk computenet-staleref closed
commit_in computenet-staleref "work origin later lost"
git -C "$ROOT/computenet-worktrees/computenet-staleref" push -q origin b/computenet-staleref
(cd "$REPO" && git fetch origin --quiet)                     # cache now holds origin/b/computenet-staleref
git -C "$ROOT/origin.git" update-ref -d refs/heads/b/computenet-staleref
age "$ROOT/computenet-worktrees/computenet-staleref"
out=$(run); rc=$?
check "stale remote-tracking ref must not count as containment" "on NO remote ref" "$out"
check "stale ref case -> exit 1" "1" "$rc"
alive computenet-staleref "a branch origin has DELETED must never prove containment"

# Detached HEAD: no branch holds the commits.
mk computenet-detached closed
git -C "$ROOT/computenet-worktrees/computenet-detached" checkout -q --detach
age "$ROOT/computenet-worktrees/computenet-detached"
out=$(run); rc=$?
check "detached HEAD -> SKIP" "DETACHED" "$out"
alive computenet-detached "detached HEAD must never be removed"

# Paused rebase: `git status --short` is empty, so it needs its own guard.
mk computenet-rebasing closed
mkdir -p "$(git -C "$ROOT/computenet-worktrees/computenet-rebasing" rev-parse --absolute-git-dir)/rebase-merge"
age "$ROOT/computenet-worktrees/computenet-rebasing"
out=$(run); rc=$?
check "paused rebase -> SKIP" "IN PROGRESS (rebase-merge)" "$out"
alive computenet-rebasing "a paused rebase must never be removed"

# ...and the happy path really does remove, so the guards above are not just
# refusing everything.
mk computenet-happy closed
out=$(run)
check "clean+pushed+closed IS removed for real" "removed $ROOT/computenet-worktrees/computenet-happy" "$out"
[ -d "$ROOT/computenet-worktrees/computenet-happy" ] && bad "happy path did not remove" || ok

# Unreachable origin. Containment is unprovable for EVERY candidate, so this
# aborts before the loop rather than skipping item by item: exit 3, and not a
# single SKIP line, because nothing was checked. Last, because it breaks the
# remote for good.
mk computenet-unreachable closed
git -C "$REPO" remote set-url origin "$ROOT/there-is-no-origin.git"
out=$(run); rc=$?
check "unreachable origin -> says nothing was checked" "NOTHING was checked" "$out"
check "unreachable origin -> exit 3" "3" "$rc"
[[ "$out" != *"SKIP"* && "$out" != *"removed"* ]] && ok \
  || bad "unreachable origin must check nothing at all — got <$out>"
alive computenet-unpushed "an unreachable origin must not remove anything"

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
