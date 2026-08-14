#!/usr/bin/env bash
# Keep the MAIN checkout on the latest origin/main, so that every worktree cut
# from it — including Claude Code's own session worktrees under
# .claude/worktrees/, which are branched from local `main`'s HEAD, not from
# origin/main — starts from current code and a current .claude/skills/.
#
# Why this exists (computenet-kcu, and one level above it): nothing in the
# /work flow fast-forwards the main checkout. Step 3's `git fetch origin main`
# updates the remote-tracking ref, not the branch and not the files. Measured
# 2026-08-14: the checkout sat 44 commits behind at one point, and a routine
# firing at that moment would have run a stale `work` skill for a whole 5h
# slot, because its session worktree is cut from local main.
#
# Wired as a SessionStart hook in .claude/settings.json.
#
# SAFETY — this must never surprise a human working in that checkout, and must
# never fail a session start. It no-ops unless ALL of these hold:
#   * the main checkout is on branch `main`
#   * its working tree and index are completely clean
#   * the update is a true fast-forward (--ff-only; never a merge or rebase)
# Anything else: report and leave it alone. Always exits 0.
set -uo pipefail

# Resolve the MAIN checkout even when invoked from a worktree: --git-common-dir
# is the shared .git for every worktree, and its parent is the main checkout.
common=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
repo=$(dirname "$common")
[ -d "$repo" ] || exit 0

branch=$(git -C "$repo" symbolic-ref --short HEAD 2>/dev/null || echo "")
if [ "$branch" != "main" ]; then
  echo "ff-main: main checkout is on '$branch', not main — left alone."
  exit 0
fi

# Tracked changes only. An untracked scratch file is not work-in-progress the
# way a staged or modified tracked file is, and git refuses a fast-forward on
# its own if one would be clobbered — so it does not need a guard here.
dirty=$(git -C "$repo" status --porcelain --untracked-files=no 2>/dev/null)
if [ -n "$dirty" ]; then
  echo "ff-main: main checkout has staged/modified tracked files — left alone:"
  printf '%s\n' "$dirty" | sed 's/^/  /'
  echo "ff-main: commit or stash them and main will fast-forward on the next session."
  exit 0
fi

git -C "$repo" fetch --quiet origin main 2>/dev/null || {
  echo "ff-main: fetch failed (offline?) — left alone."
  exit 0
}

behind=$(git -C "$repo" rev-list --count main..origin/main 2>/dev/null || echo 0)
[ "${behind:-0}" -eq 0 ] && exit 0        # already current: say nothing

if git -C "$repo" merge --ff-only --quiet origin/main 2>/dev/null; then
  echo "ff-main: fast-forwarded main $behind commit(s) to $(git -C "$repo" rev-parse --short main)."
else
  # Diverged: local main has commits origin does not. Never resolve that here.
  echo "ff-main: main has diverged from origin/main ($behind behind) — left alone, resolve by hand."
fi
exit 0
