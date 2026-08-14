#!/usr/bin/env bash
# Shared by run-linux-loop.sh and worktree-mount.test.sh.
#
# computenet-yj6: `docker run -v "$REPO:$REPO:ro" ...` comes up EMPTY under
# Docker Desktop on macOS when $REPO is a linked git worktree rather than the
# main checkout -- verified 2026-08-13 for a worktree nested under the main
# checkout (.claude/worktrees/<id>). The failure then surfaces inside the
# container as `java.lang.ClassNotFoundException`, which reads like a
# classpath bug; the classpath is correct, the mount is empty. The Docker
# Desktop mechanism behind the empty mount was never established (virtiofs
# handling of a path under an already-shared root, file-sharing scope, or
# something else) -- what's known is only that mounting the ENCLOSING main
# checkout instead makes the worktree's files visible in that nested layout.
#
# resolve_enclosing_checkout <repo-path> prints the main checkout path (one
# level above `git rev-parse --git-common-dir`) when <repo-path> is a linked
# worktree whose main checkout differs from itself, and prints nothing
# otherwise (plain checkout, or git metadata unavailable). The caller mounts
# the printed path in ADDITION to <repo-path> itself; it is a best-effort
# mitigation for the nested-worktree layout, not a general fix -- a worktree
# that is a SIBLING of the main checkout (../computenet-worktrees/<id>, the
# layout this repo actually uses day to day) is not covered by any mount
# rooted at the main checkout, since the worktree isn't inside it. That case
# is caught instead by the preflight in run-linux-loop.sh, which verifies the
# $REPO mount is non-empty before the real run starts.
resolve_enclosing_checkout() {
  local repo="$1" git_dir main_checkout
  git_dir="$(cd "$repo" 2>/dev/null && git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" || return 0
  [[ -n "$git_dir" ]] || return 0
  main_checkout="$(cd "$(dirname "$git_dir")" 2>/dev/null && pwd)" || return 0
  if [[ "$main_checkout" != "$repo" ]]; then
    printf '%s\n' "$main_checkout"
  fi
  return 0
}
