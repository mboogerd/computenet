#!/usr/bin/env bash
# Shared by run-linux-loop.sh and worktree-mount.test.sh.
#
# computenet-yj6 recorded that `docker run -v "$REPO:$REPO:ro" ...` comes up
# EMPTY under Docker Desktop on macOS when $REPO is a linked git worktree
# rather than the main checkout, surfacing inside the container as
# `java.lang.ClassNotFoundException` -- a correct classpath over an empty
# mount. That claim did NOT survive verification. computenet-m3iy ran
# run-linux-loop.sh end to end on 2026-08-14 (Docker Desktop, server 29.6.1,
# linux/aarch64) from BOTH layouts -- a worktree nested under its main
# checkout (.claude/worktrees/<id>) and a SIBLING worktree
# (../computenet-worktrees/<id>) -- and both produced a real iteration
# report, with the mount preflight passing in both. Being a linked git
# worktree is not, by itself, a reason for an empty mount.
#
# The reported symptom is real and reproduces exactly, but the cause is the
# SHELL, not git worktrees. Agent sessions in this repo run zsh, where a `:r`
# following a parameter expansion is a history modifier (strip extension):
#
#   bash: "$M:$M:ro" -> /path/computenet:/path/computenet:ro  (mounts at $M)
#   zsh : "$M:$M:ro" -> /path/computenet:/path/computeneto    (mounts at ${M:r}o)
#
# The modifier strips an extension FIRST, so the mangled target is `${M:r}o`,
# not `${M}o` -- and worktree ids here routinely carry a dot. yj6's own $REPO
# ended in `computenet-dqy.40`, which mounts at `computenet-dqyo`. That also
# explains yj6's verbatim probe: `-v "$REPO:$REPO:ro" -w "$REPO" ... sh -c
# "pwd; ls -a"` printed only `.` and `..` because `-w` carries no modifier, so
# docker created the unmounted workdir empty. Re-run 2026-08-14 under each
# shell against a sibling worktree ending in `.65`: zsh printed `.` and `..`
# only, bash printed the full tree. (yj6's "mitigation worked" command spelled
# both paths out literally, with no `$REPO` to modify, which is why it worked.)
#
# So an ad-hoc `docker run -v "$REPO:$REPO:ro" ...` typed at a zsh prompt
# bind-mounts a directory to the SIDE of $REPO; $REPO itself is
# then absent in the container, and a classpath rooted under it raises
# ClassNotFoundException -- the whole gotcha, end to end. Confirmed by running
# the identical command under each shell against a nested worktree: bash exit
# 0, zsh exit 1. The committed scripts here are unaffected (they are
# `#!/usr/bin/env bash`); only hand-run probes are exposed, so write those as
# `-v "${REPO}:${REPO}:ro"` -- braces disarm the modifier -- or run them
# under bash.
#
# The mitigation below is kept as belt-and-braces rather than deleted: it is
# one extra read-only mount that cannot hurt, and the non-reproduction above
# is one host on one Docker Desktop version, not proof that no version ever
# behaved as yj6 described.
#
# resolve_enclosing_checkout <repo-path> prints the main checkout path (one
# level above `git rev-parse --git-common-dir`) when <repo-path> is a linked
# worktree whose main checkout differs from itself, and prints nothing
# otherwise (plain checkout, or git metadata unavailable). The caller mounts
# the printed path in ADDITION to <repo-path> itself. Note what it can and
# cannot reach: a worktree that is a SIBLING of the main checkout
# (../computenet-worktrees/<id>, the layout this repo actually uses day to
# day) is not covered by any mount rooted at the main checkout, since the
# worktree isn't inside it. On 2026-08-14 that layout needed no covering --
# it mounted and ran fine on its own. Whatever the mount is doing, the
# preflight in run-linux-loop.sh is the real guard: it verifies the $REPO
# mount is non-empty before the run starts, whichever layout you are in.
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
