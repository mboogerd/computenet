#!/usr/bin/env bash
# Attach a worktree at PATH on BRANCH, whatever state things are already in.
#
# Why this exists: `git worktree add` refuses when the path is already a
# worktree, and `-b` refuses when the branch already exists — so a *resumed*
# item fails both naive forms, which is precisely the case resumption needs to
# work. Three cases have to be told apart, and getting it wrong silently points
# an agent at a directory that isn't there.
#
# Usage: ensure-worktree.sh <path> <branch> [base-ref]
#   base-ref  only used when the branch doesn't exist yet (default origin/main)
#
# Prints the resolved absolute path on success. Exits non-zero, loudly, if the
# worktree can't be produced on the requested branch — never fail silently here.
set -euo pipefail

WORKTREE="${1:?usage: ensure-worktree.sh <path> <branch> [base-ref]}"
BRANCH="${2:?usage: ensure-worktree.sh <path> <branch> [base-ref]}"
BASE="${3:-origin/main}"

mkdir -p "$(dirname "$WORKTREE")"
# Absolute path: a relative one resolves differently inside a subagent's own
# worktree, which is how an agent ends up working in the main checkout.
abs=$(cd "$(dirname "$WORKTREE")" && pwd)/$(basename "$WORKTREE")

if git worktree list --porcelain | grep -qx "worktree $abs"; then
  :                                                   # already attached
elif git show-ref --quiet "refs/heads/$BRANCH"; then
  git worktree add "$abs" "$BRANCH" >/dev/null        # branch exists, reattach
else
  git fetch origin main --quiet 2>/dev/null || true
  git worktree add "$abs" -b "$BRANCH" "$BASE" >/dev/null
fi

on=$(git -C "$abs" rev-parse --abbrev-ref HEAD)
if [ "$on" != "$BRANCH" ]; then
  echo "worktree $abs is on '$on', expected '$BRANCH'" >&2
  exit 1
fi

echo "$abs"
