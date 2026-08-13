#!/usr/bin/env bash
# Tests for ensure-worktree.sh. Self-contained: builds throwaway git repos in a
# temp dir with a real (bare) origin, exercises every state, deletes them.
# Touches nothing in the real repo or the real worktrees directory.
#
#   .claude/skills/work/scripts/ensure-worktree.test.sh            # test the sibling script
#   .claude/skills/work/scripts/ensure-worktree.test.sh /path/to/other.sh
#
# Exits 0 if all cases pass, 1 otherwise, printing a per-case verdict.
set -uo pipefail

SCRIPT=${1:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/ensure-worktree.sh"}
[ -x "$SCRIPT" ] || { echo "not executable: $SCRIPT" >&2; exit 1; }

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/ensure-worktree-test.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  PASS $*"; }
bad()  { fail=$((fail+1)); echo "  FAIL $*"; }

# A fresh sandbox: bare origin with `main` (2 commits), plus a clone acting as
# the machine's main checkout. Echoes the clone path.
sandbox() {
  local d="$ROOT/$1"
  mkdir -p "$d"
  git init --quiet --bare "$d/origin.git"
  git init --quiet "$d/seed" 2>/dev/null
  (
    cd "$d/seed"
    git config user.email t@t; git config user.name t
    git symbolic-ref HEAD refs/heads/main
    echo base > f; git add f; git commit --quiet -m base
    echo more >> f; git add f; git commit --quiet -m more
    git remote add origin "$d/origin.git"; git push --quiet -u origin main
  ) >/dev/null 2>&1
  git clone --quiet "$d/origin.git" "$d/checkout" >/dev/null 2>&1
  ( cd "$d/checkout"; git config user.email t@t; git config user.name t )
  echo "$d/checkout"
}

# Add a commit on <branch> in a scratch clone and push it to origin, without
# creating that branch in the checkout under test. Echoes the pushed sha.
push_remote_commit() {
  local co=$1 branch=$2 msg=$3 from=${4:-origin/main}
  local d; d=$(dirname "$co"); local tmp="$d/pusher-$RANDOM"
  git clone --quiet "$d/origin.git" "$tmp" >/dev/null 2>&1
  (
    cd "$tmp"
    git config user.email t@t; git config user.name t
    git checkout --quiet -B "$branch" "$from" 2>/dev/null || git checkout --quiet -B "$branch"
    echo "$msg" >> f; git add f; git commit --quiet -m "$msg"
    git push --quiet -u origin "$branch"
  ) >/dev/null 2>&1
  git -C "$tmp" rev-parse "$branch"
  rm -rf "$tmp"
}

run() { ( cd "$1"; shift; "$SCRIPT" "$@" ) ; }

sha()  { git -C "$1" rev-parse HEAD; }
short(){ git -C "$1" rev-parse --short HEAD 2>/dev/null; }

echo "testing: $SCRIPT"

# ---------------------------------------------------------------- 1. brand new
co=$(sandbox new); wt="$co/../wt-new"
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  base=$(git -C "$co" rev-parse origin/main)
  if [ "$(sha "$out")" = "$base" ] && [ "$(git -C "$out" rev-parse --abbrev-ref HEAD)" = feature/x ]; then
    ok "brand new: branch created at base ref"
  else
    bad "brand new: HEAD $(short "$out") on $(git -C "$out" rev-parse --abbrev-ref HEAD), wanted base $base"
  fi
else
  bad "brand new: script exited non-zero"
fi

# ------------------------------------------------------- 2. already attached
co=$(sandbox attached); wt="$co/../wt-att"
first=$(run "$co" "$wt" feature/x origin/main 2>/dev/null)
before=$(sha "$first")
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  [ "$(sha "$out")" = "$before" ] \
    && ok "already attached: idempotent, HEAD unchanged" \
    || bad "already attached: HEAD moved $before -> $(sha "$out")"
else
  bad "already attached: script exited non-zero (not idempotent)"
fi

# ------------------------------------------- 3. local branch exists, no worktree
co=$(sandbox localonly); wt="$co/../wt-local"
git -C "$co" checkout --quiet -b feature/x
echo local >> "$co/f"; git -C "$co" add f; git -C "$co" commit --quiet -m "local work"
want=$(git -C "$co" rev-parse feature/x)
git -C "$co" checkout --quiet main
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  [ "$(sha "$out")" = "$want" ] \
    && ok "local-only branch: attached at the local tip" \
    || bad "local-only branch: HEAD $(sha "$out"), wanted $want"
else
  bad "local-only branch: script exited non-zero"
fi

# ----------------------------------------------- 4. remote-only branch (THE BUG)
co=$(sandbox remoteonly); wt="$co/../wt-remote"
want=$(push_remote_commit "$co" feature/x "the PR's only commit")
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  got=$(sha "$out")
  if [ "$got" = "$want" ]; then
    up=$(git -C "$out" rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null)
    [ "$up" = "origin/feature/x" ] \
      && ok "remote-only branch: attached at the remote tip, tracking origin/feature/x" \
      || bad "remote-only branch: at remote tip but upstream is '${up:-<none>}'"
  else
    bad "remote-only branch: HEAD $got, wanted remote tip $want (PR commits orphaned)"
  fi
else
  bad "remote-only branch: script exited non-zero"
fi

# --------------------------------------------------- 5. both exist, and agree
co=$(sandbox agree); wt="$co/../wt-agree"
want=$(push_remote_commit "$co" feature/x "shared commit")
git -C "$co" fetch --quiet origin
git -C "$co" branch --quiet feature/x origin/feature/x
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  [ "$(sha "$out")" = "$want" ] \
    && ok "both exist and agree: attached at the shared tip" \
    || bad "both exist and agree: HEAD $(sha "$out"), wanted $want"
else
  bad "both exist and agree: script exited non-zero"
fi

# ------------------------------------------- 6. both exist, DIVERGED -> must fail
co=$(sandbox diverged); wt="$co/../wt-div"
push_remote_commit "$co" feature/x "remote side" >/dev/null
git -C "$co" fetch --quiet origin
git -C "$co" checkout --quiet -b feature/x origin/main
echo localside >> "$co/f"; git -C "$co" add f; git -C "$co" commit --quiet -m "local side"
git -C "$co" checkout --quiet main
if out=$(run "$co" "$wt" feature/x origin/main 2>&1); then
  bad "diverged: exited 0 (silently picked a side); HEAD $(short "$(echo "$out" | tail -1)")"
else
  echo "$out" | grep -qi diverged \
    && ok "diverged: failed loudly, message names the divergence" \
    || bad "diverged: failed, but message does not mention divergence: $out"
fi

# ------------------------ 7. both exist, local strictly BEHIND (fast-forward)
co=$(sandbox behind); wt="$co/../wt-behind"
first=$(push_remote_commit "$co" feature/x "first")
git -C "$co" fetch --quiet origin
git -C "$co" branch --quiet feature/x origin/feature/x
want=$(push_remote_commit "$co" feature/x "second" origin/feature/x)
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  [ "$(sha "$out")" = "$want" ] \
    && ok "local behind remote: fast-forwarded to the remote tip" \
    || bad "local behind remote: HEAD $(sha "$out"), wanted remote tip $want (stale local tip $first)"
else
  bad "local behind remote: script exited non-zero (a fast-forward is not divergence)"
fi

# ------------------------------- 8. both exist, local strictly AHEAD (keep local)
co=$(sandbox ahead); wt="$co/../wt-ahead"
push_remote_commit "$co" feature/x "pushed" >/dev/null
git -C "$co" fetch --quiet origin
git -C "$co" checkout --quiet -b feature/x origin/feature/x
echo unpushed >> "$co/f"; git -C "$co" add f; git -C "$co" commit --quiet -m "unpushed local work"
want=$(git -C "$co" rev-parse feature/x)
git -C "$co" checkout --quiet main
if out=$(run "$co" "$wt" feature/x origin/main 2>/dev/null); then
  [ "$(sha "$out")" = "$want" ] \
    && ok "local ahead of remote: kept the local tip (unpushed work preserved)" \
    || bad "local ahead of remote: HEAD $(sha "$out"), wanted local tip $want"
else
  bad "local ahead of remote: script exited non-zero (being ahead is not divergence)"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
