#!/usr/bin/env bash
# Close beads whose PR already merged, and take their worktrees off disk.
#
# Why this exists: auto-merge lands a PR minutes AFTER the session that opened
# it has ended, so no session ever observes its own merge. Every reconciliation
# site in SKILL.md is preconditioned — Finalize's watch covers only PRs marked
# ready THIS session, and 5a's resume re-check covers only features inside the
# claimed epic. A feature under an epic nobody re-claims is reconciled by
# nobody, forever; `bd ready` hides in_progress, so it is invisible to
# selection too, and the worktree stays because removal is gated on the close.
# Measured 2026-08-14: four features in_progress behind PRs merged 12-20h
# earlier, all four worktrees still on disk (computenet-wpvy.25).
#
# So this sweep is deliberately UNCONDITIONAL: every non-closed bead carrying
# metadata.pr, whatever epic it sits under and whatever else its metadata says.
# Cost is one `gh` call per session.
#
# Usage: sweep-merged-prs.sh [--dry-run] [--limit N]
#   --dry-run  print what would be closed/removed, change nothing
#   --limit    how many merged PRs to fetch (default 500; the repo passed 150
#              on 2026-08-14, and gh's own default of 30 would silently miss
#              every older one)
set -euo pipefail

DRY_RUN=0
LIMIT=500
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --limit) LIMIT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# The one network call. number+headRefName covers both joins below.
merged=$(gh pr list --state merged --limit "$LIMIT" --json number,headRefName)

# --limit 0 = unlimited; bd's default of 50 would silently strand the rest.
# No --status filter: `bd list` already excludes closed, and open / in_progress
# / blocked / deferred all need looking at. --has-metadata-key does the join
# key selection server-side.
withpr=$(bd list --has-metadata-key pr --limit 0 --json 2>/dev/null || echo '[]')
withbranch=$(bd list --has-metadata-key branch --limit 0 --json 2>/dev/null || echo '[]')

# Two joins, deliberately unequal in authority:
#  - metadata.pr is the PR *this bead's session opened for this bead*. A MERGED
#    state there means this bead's change landed → safe to close.
#  - metadata.branch alone is weaker: a branch can merge while its bead stays
#    legitimately open. Real case, 2026-08-14: computenet-dqy.2 is deferred to
#    2026-08-25 awaiting a CI capture, and its branch merged as PR #27 titled
#    "...the 5-minute hang stays undiagnosed". Closing that would delete live
#    work. So a branch-only match is REPORTED, never closed.
# A deferred bead is likewise reported, not closed — deferral is a deliberate
# park and a merged PR does not revoke it.
plan=$(jq -n \
  --argjson merged "$merged" \
  --argjson withpr "$withpr" \
  --argjson withbranch "$withbranch" '
  ($merged | map(.number) | map(tostring)) as $nums
  | ($merged | map(.headRefName)) as $refs
  | [ $withpr[]
      | select((.metadata.pr | tostring | capture("/pull/(?<n>[0-9]+)") | .n) as $n
               | $nums | index($n))
      | {id, worktree: (.metadata.worktree // "-"),
         action: (if .status == "deferred" then "report" else "close" end),
         why: (if .status == "deferred"
               then "its PR merged, but the bead is deferred on purpose"
               else "metadata.pr merged" end)} ]
  + [ $withbranch[]
      | select(.metadata.pr == null)
      | select(.metadata.branch as $b | $refs | index($b))
      | {id, worktree: (.metadata.worktree // "-"), action: "report",
         why: "its branch merged, but the bead carries no metadata.pr"} ]
  | unique_by(.id)')

count=$(jq length <<<"$plan")
if [ "$count" -eq 0 ]; then
  echo "no beads behind a merged PR"
  exit 0
fi

closed=0
while IFS=$'\t' read -r id action worktree why; do
  [ -n "$id" ] || continue
  if [ "$action" = "report" ]; then
    echo "review by hand (NOT closed): $id — $why"
    continue
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "would close: $id — $why"
  else
    bd close "$id" >/dev/null
    echo "closed: $id — $why"
    closed=$((closed + 1))
  fi

  # Worktree removal is a consequence of the close, never independent of it.
  # Three gates, all required: the path resolves on THIS machine (metadata
  # routinely carries the other machine's paths, e.g. /Users/MerlijnB/...),
  # git still knows it as a worktree, and it is clean. Dirty means uncommitted
  # work nobody has looked at — leave it and say so.
  [ "$worktree" != "-" ] || continue
  wt=$(cd "$worktree" 2>/dev/null && pwd -P) || { echo "  worktree not on this machine: $worktree"; continue; }
  if ! git worktree list --porcelain | grep -qxF "worktree $wt"; then
    echo "  not a registered worktree, left alone: $wt"
    continue
  fi
  if [ -n "$(git -C "$wt" status --short)" ]; then
    echo "  DIRTY, left in place: $wt"
    continue
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "  would remove worktree: $wt"
  else
    git worktree remove "$wt" && echo "  removed worktree: $wt"
  fi
done < <(jq -r '.[] | [.id, .action, .worktree, .why] | @tsv' <<<"$plan")

# Closes are local writes like everything else in this flow. They reach the
# other machine at the session's Finalize push (SKILL.md step 6); this script
# does not sync, and nothing is scheduled to do it for us
# (doc/ops/beads-sync-runbook.md section 5).
if [ "$DRY_RUN" -eq 1 ]; then
  echo "dry run: nothing changed"
else
  echo "closed $closed bead(s) behind merged PRs"
fi
