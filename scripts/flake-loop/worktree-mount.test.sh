#!/usr/bin/env bash
# Tests for worktree-mount.sh's resolve_enclosing_checkout (computenet-yj6).
# Self-contained: builds throwaway git repos and worktrees in a temp dir,
# exercises every layout, deletes them. Touches nothing in the real repo or
# the real worktrees directory.
#
#   scripts/flake-loop/worktree-mount.test.sh
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=./worktree-mount.sh
source "$HERE/worktree-mount.sh"

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/worktree-mount-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT
# Canonicalize (macOS mktemp can hand back a path through a /tmp -> /private/tmp
# symlink) so comparisons against resolve_enclosing_checkout's `cd && pwd`
# output are apples-to-apples.
ROOT=$(cd "$ROOT" && pwd -P)

pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS $*"; }
bad() { fail=$((fail+1)); echo "  FAIL $*"; }

# A checkout with one commit, so `git worktree add` has something to check out.
seed_checkout() {
  local d="$ROOT/$1"
  git init --quiet "$d" >/dev/null 2>&1
  ( cd "$d"
    git config user.email t@t; git config user.name t
    git symbolic-ref HEAD refs/heads/main
    echo base > f; git add f; git commit --quiet -m base
  ) >/dev/null 2>&1
  # Canonicalize (macOS /tmp is a symlink to /private/tmp) so comparisons
  # against resolve_enclosing_checkout's `cd && pwd` output are apples-to-apples.
  (cd "$d" && pwd -P)
}

# --------------------------------------------- 1. plain checkout, no worktree
main1=$(seed_checkout plain)
got=$(resolve_enclosing_checkout "$main1")
[ -z "$got" ] \
  && ok "plain checkout: no extra mount" \
  || bad "plain checkout: expected empty, got '$got'"

# ------------------------------------- 2. linked worktree NESTED under main
main2=$(seed_checkout nested-main)
wt2="$main2/.worktrees/wt"
( cd "$main2" && git worktree add --quiet -b wt-branch "$wt2" main ) >/dev/null 2>&1
got=$(resolve_enclosing_checkout "$wt2")
if [ "$got" = "$main2" ]; then
  ok "nested worktree: resolves to its main checkout"
else
  bad "nested worktree: expected '$main2', got '$got'"
fi

# ------------------------------------- 3. linked worktree SIBLING of main
main3=$(seed_checkout sibling-main)
wt3="$ROOT/sibling-wt"
( cd "$main3" && git worktree add --quiet -b wt-branch2 "$wt3" main ) >/dev/null 2>&1
got=$(resolve_enclosing_checkout "$wt3")
if [ "$got" = "$main3" ]; then
  ok "sibling worktree: still resolves to its main checkout (mount alone won't cover it, but the resolution is correct)"
else
  bad "sibling worktree: expected '$main3', got '$got'"
fi

# ----------------------------- 4. called from a subdirectory of a worktree
main4=$(seed_checkout subdir-main)
wt4="$main4/.worktrees/wt"
( cd "$main4" && git worktree add --quiet -b wt-branch3 "$wt4" main ) >/dev/null 2>&1
mkdir -p "$wt4/sub/dir"
got=$(resolve_enclosing_checkout "$wt4/sub/dir")
if [ "$got" = "$main4" ]; then
  ok "worktree subdirectory: still resolves via git-common-dir"
else
  bad "worktree subdirectory: expected '$main4', got '$got'"
fi

# ----------------------------------------------------- 5. non-git directory
nongit="$ROOT/not-a-repo"
mkdir -p "$nongit"
got=$(resolve_enclosing_checkout "$nongit")
[ -z "$got" ] \
  && ok "non-git directory: no extra mount, no crash" \
  || bad "non-git directory: expected empty, got '$got'"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
