#!/usr/bin/env bash
# Merge a reviewed task branch into its feature branch — merge-task.md §3's
# pre-merge guard sequence as ordered gates, then the mutation, then proof,
# then the close.
#
# Order is the point. Every gate prints before anything mutates, because 5a
# set the feature branch up potentially an hour and several merges ago and
# nothing between then and here re-reads it (computenet-wpvy.29): origin can
# hold a tip the local ref does not contain, and a PR you did not open can
# have this branch as its head — either is a park, not something to resolve,
# because both sides may hold pushed unreviewed work and picking a winner
# discards somebody's. And the close comes only AFTER the merge is proven on
# origin: a close is what tells every later session the work landed, so a
# close on an undurable merge is the lie this ordering exists to prevent.
#
# Usage: merge-task.sh [--dry-run] [--keep-open] <task-id> <feature-branch>
#   --dry-run  run the gates and show the incoming --stat; mutate nothing
# Each gate prints "GATE <name>: PASS" or aborts with "GATE <name>: FAIL — why".
# Exit: 0 = merged, pushed, proven durable, closed (or --dry-run with gates
#       green); 1 = a gate failed or a later step failed — the output says
#       exactly how far it got, and the task was NOT closed unless "closed:"
#       was printed; 2 = bad usage; 3 = bd unusable (task unresolvable).
set -uo pipefail

DRY_RUN=0
# --keep-open: merge and push the task branch WITHOUT closing the bead. The
# one case that needs it is an ask-human PARK that carries a commit worth
# keeping: the task's honest outcome is "parked", closing it would destroy the
# parked question, and leaving the branch unmerged means the commit dies with
# this machine (merge-task.md §3). Before this flag the only routes were "lose
# the work" or "close the park", and a session with 402 lines of CI-green
# evidence pinning a structural proof chose to lose them (computenet-wdhu).
# Not for a task that merely failed review — that stays in_progress with its
# branch unmerged, which is a different state and already covered.
KEEP_OPEN=0
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --keep-open) KEEP_OPEN=1 ;;
    -*) echo "merge-task: unknown flag: $1" >&2; exit 2 ;;
    *) args+=("$1") ;;
  esac
  shift
done
[ "${#args[@]}" -eq 2 ] \
  || { echo "usage: merge-task.sh [--dry-run] [--keep-open] <task-id> <feature-branch>" >&2; exit 2; }
task=${args[0]}
fbr=${args[1]}

fail_gate() { echo "GATE $1: FAIL — $2"; exit 1; }
pass_gate() { echo "GATE $1: PASS — $2"; }

# Resolve the task's branch and worktree from the bead. bd --json returns a
# LIST, and warnings can precede the JSON on stdout — slice from the first
# [ or { before jq, and unwrap .[0].
shown=$(bd show "$task" --json) \
  || { echo "merge-task: bd show $task failed — cannot resolve the task branch" >&2; exit 3; }
tbr=$(printf '%s\n' "$shown" | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].metadata.branch // empty')
twt=$(printf '%s\n' "$shown" | sed -n '/^[[{]/,/^[]}]/p' | jq -r '.[0].metadata.worktree // empty')
[ -n "$tbr" ] || tbr="task/$task"       # 5b's default when the breakdown omitted it

# The feature worktree is RECOMPUTED locally, the same way feature-branch.sh
# computes it — metadata.worktree routinely carries the other machine's path
# (computenet-dqy.65/.69).
MAIN=$(cd "$(git rev-parse --git-common-dir)/.." && pwd -P)
FWT="$(cd "$MAIN/.." && pwd -P)/computenet-worktrees/$(basename "$fbr")"

# Gate 1: the feature worktree is still on the recorded branch
# (computenet-wpvy.29 — nothing since 5a re-read it).
cur=$(git -C "$FWT" rev-parse --abbrev-ref HEAD 2>/dev/null) \
  || fail_gate branch-identity "no git checkout at $FWT"
[ "$cur" = "$fbr" ] \
  || fail_gate branch-identity "feature worktree $FWT is on '$cur', not '$fbr'"
pass_gate branch-identity "$FWT is on $fbr"

# Gate 2: feature branch vs origin — the three-way classifier. Absent, ahead
# and unreachable are three different findings, which is why this is
# if/elif/else and not an &&/|| chain; a bare `if fetch` would diagnose an
# unreachable origin as an absent branch, an answer on a check that never ran
# (the same defect computenet-dtl fixed in 5a's block).
if git -C "$FWT" fetch origin "$fbr" 2>/dev/null; then
  if git -C "$FWT" merge-base --is-ancestor FETCH_HEAD HEAD; then
    pass_gate origin-state "local contains origin/$fbr"
  else
    fail_gate origin-state "origin/$fbr is AHEAD — somebody pushed under you; both sides may hold pushed unreviewed work, so park via ask-human.md rather than picking a winner"
  fi
elif git -C "$FWT" ls-remote origin >/dev/null 2>&1; then
  # §3 calls this a CHECK, not a STOP — a missing branch endangers nobody's
  # pushed work — but 5a ends with `git push -u origin <branch>`, so absence
  # means that push never landed or something deleted the ref. This script
  # cannot "find out why and push it", so it still refuses.
  fail_gate origin-state "CHECK: origin has no $fbr — 5a pushed it, so absence is not normal; find out why, push it, then re-run"
else
  fail_gate origin-state "origin is UNREACHABLE — nothing was checked"
fi

# Gate 3: no competing open PR on the feature head. "Expect: only yours, or
# none" (§3) — an absent PR is normal (5d opens it only after the first task
# merges), and your own 5d PR is fine; a PR under another login is the park.
if ! prs=$(gh pr list --head "$fbr" --state open --json number,author 2>&1); then
  fail_gate competing-pr "gh failed ($(printf '%s' "$prs" | head -1)) — open-PR state of $fbr unreadable, and a failed call is not a reading"
fi
n=$(jq length <<<"$prs" 2>/dev/null) \
  || fail_gate competing-pr "unparseable gh output: $prs"
if [ "$n" -eq 0 ]; then
  pass_gate competing-pr "no open PR on $fbr (normal before 5d)"
else
  me=$(gh api user --jq .login 2>/dev/null) || me=""
  [ -n "$me" ] \
    || fail_gate competing-pr "open PR(s) exist but own login is unreadable — cannot tell yours from a competitor's"
  foreign=$(jq -r --arg me "$me" \
    '.[] | select(.author.login != $me) | "#\(.number) by \(.author.login)"' <<<"$prs")
  [ -z "$foreign" ] \
    || fail_gate competing-pr "open PR on $fbr not yours: $foreign — park via ask-human.md"
  pass_gate competing-pr "every open PR on $fbr is yours"
fi

# Gate 4: the task branch exists as a LOCAL ref. Local by design — a
# dispatched implementer's push is denied by the classifier (computenet-zmso),
# so the ref may exist only on the machine that ran the task.
git -C "$FWT" rev-parse --verify --quiet "$tbr" >/dev/null \
  || fail_gate task-branch "no local ref '$tbr' — the task branch is local by design; it may only exist on the machine that ran the task"
pass_gate task-branch "local ref $tbr exists"

# The two-dot --stat BEFORE the merge, every time, not only when something
# feels wrong: two-dot shows both directions, so content on the feature
# branch and absent from the task branch appears as a DELETION — the
# signature of a base that moved (§3's observed case: deletions under
# references/ and a 262-line test file the implementer never wrote). Read it.
echo "incoming ($fbr..$tbr):"
git -C "$FWT" diff --stat "$fbr..$tbr" \
  || { echo "merge-task: the pre-merge --stat failed — nothing was merged" >&2; exit 1; }

if [ "$DRY_RUN" -eq 1 ]; then
  echo "dry run: gates green, nothing merged"
  exit 0
fi

# Merge the LOCAL ref, never origin/<task-branch>: worktrees of one
# repository share refs, and the local ref carries the reviewer's `review:`
# repair commits — a fetched merge silently dropped a certified repair
# (observed 2026-08-17 on computenet-7em.2.3).
git -C "$FWT" merge --no-ff "$tbr" -m "Merge $task" \
  || { echo "merge-task: merge FAILED — resolve or abort in $FWT; task NOT closed" >&2; exit 1; }
git -C "$FWT" push \
  || { echo "merge-task: push FAILED — the merge exists only locally; task NOT closed" >&2; exit 1; }

# Durability: prove the task tip reached origin BEFORE closing. This prints
# STOP for an unreachable origin too — that is the safe direction here
# (unlike 5a's block, where an unreachable origin printing OK was the bug).
tip=$(git -C "$FWT" rev-parse "$tbr")
if git -C "$FWT" fetch origin "$fbr" 2>/dev/null \
   && git -C "$FWT" merge-base --is-ancestor "$tip" FETCH_HEAD; then
  echo "OK: $tbr ($tip) is on origin/$fbr — durable"
else
  echo "STOP: the merge is NOT proven on origin — task NOT closed; diagnose, retry the push, re-run" >&2
  exit 1
fi

# Only now the close — the merge is durable, so a crash after this line
# leaves at worst a closed task whose work IS on origin.
if [ "$KEEP_OPEN" -eq 1 ]; then
  echo "kept open: $task — merge is durable on origin/$fbr; status and assignee untouched"
  echo "  (--keep-open: the bead's own state is the record; nothing here closes it)"
  exit 0
fi
bd close "$task" \
  || { echo "merge-task: bd close $task FAILED — the merge IS durable but the task still reads open; close it by hand and say so" >&2; exit 1; }
echo "closed: $task"

# Post-close hygiene from §3: a dirty task worktree means an agent died
# mid-edit. Report only, and only if the path resolves on THIS machine —
# metadata.worktree may be the other machine's path.
if [ -n "$twt" ] && [ -d "$twt" ]; then
  dirt=$(git -C "$twt" status --short 2>/dev/null) || dirt=""
  if [ -n "$dirt" ]; then
    echo "note: task worktree $twt is DIRTY — an agent may have died mid-edit; report it:"
    printf '%s\n' "$dirt"
  fi
fi
