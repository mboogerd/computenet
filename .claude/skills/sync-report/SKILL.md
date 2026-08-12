---
name: sync-report
description: Reports what the unattended `work` runs did since you last looked, and what they now need from you. Reads the checkpoint, diffs PRs, beads, Linear and main against it, and returns one ranked list — per pending PR, the exact question you have to answer — then stops and waits. Use when the user says "what happened since last time", "sync me up", "give me a report", "catch me up", "where are we", or asks what the overnight/concurrent runs left behind.
---

# /sync-report

The `work` and `work-unsupervised` skills run with nobody watching. They are
built to *never* block on a human: anything they can't decide gets parked in
Beads or written into a PR body and the run moves on. So the queue of things
needing you doesn't announce itself — it accumulates, silently, in four
different places.

This skill collects it, ranks it, and hands it back as questions. **It reports.
It does not fix.** The one exception is bookkeeping drift (§5), and even that
is proposed, not applied.

You end by waiting. No "I'll go ahead and…", no merging, no `gh pr ready`, no
closing beads. The user's answers are the input to the *next* session.

## 1. The checkpoint

`.claude/last-sync.json` (gitignored, local per machine):

```json
{"at":"2026-08-12T09:00:00Z","sha":"f45215e","prs_seen":[44,43,42],"beads_open_questions":["computenet-dqy.8"]}
```

Missing or unreadable → this is the first report: use `origin/main@{2.days.ago}`
as the SHA and say in the header that the window was assumed.

**Write it only after the report is delivered** — a crash mid-gather must not
consume the window. Never write it if the user interrupted before the report
landed.

## 2. Gather

Six queries. Run them together; none depends on another.

```bash
git fetch origin main -q
git --no-pager log --oneline <sha>..origin/main
GH_PAGER=cat gh pr list --state open  --limit 40 --json number,title,isDraft,headRefName,mergeStateStatus,reviewDecision,statusCheckRollup,updatedAt
GH_PAGER=cat gh pr list --state merged --limit 40 --json number,title,headRefName,mergedAt
bd human list
bd list --status=in_progress --json
```

Plus, if `LINEAR_API_KEY` is set, the Computenet project
(`7122a2bb-1f79-46e5-9fc5-d250a1d0565c`) via the GraphQL API — issues whose
`updatedAt` is after the checkpoint. Not set, or the call fails → say
"Linear: not reachable" in one line and carry on. Linear is a mirror; nothing
in this report depends on it.

Everything else is joined on the branch name: `work` names branches
`feature/<bead-id>` and `task/<bead-id>`, so a PR's `headRefName` *is* its
bead id. Use `bd show <id> --json` (`.metadata.pr`, `.metadata.review`) only
for the handful of items you actually report on — not for all 190.

Beware the two query traps `work` warns about: `bd ready` hides
`in_progress`/`blocked`/`deferred`, and `bd list` hides closed items without
`--all`. A report built on the wrong one under-counts silently.

## 3. What actually goes wrong out there

Classify every finding into one of these. They are not hypothetical — each has
happened in this repo, and the taxonomy is what makes the report exhaustive
without being long.

| Kind | Signal | Who resolves it |
|---|---|---|
| **Parked decision** | `bd human list`; PR body says "do not mark ready" | **You.** Nothing else can. |
| **Permission/policy grant** | PR changes AGENTS.md/CLAUDE.md, profiles, ship policy | **You.** An agent cannot authorize its own widening. |
| **Green draft, no ready call** | draft + all checks SUCCESS + `mergeStateStatus: CLEAN` | You, or the policy in the row above |
| **Red or stuck CI** | a check FAILURE, or `mergeStateStatus` BLOCKED/DIRTY/UNKNOWN | Agent — unless it's the third attempt |
| **Bookkeeping drift** | PR merged, bead still `open`/`in_progress` | Agent, on your say-so |
| **Stranded claim** | `in_progress`, `updated_at` hours old, no live run | Agent (startup sweep) — you only if the machine is gone |
| **Deliberate residual** | bead `deferred`, or PR body says "not run / left open" | You: accept or re-file |
| **Doc drift** | merged code contradicts a plan/spec doc still on main | Agent, low priority |
| **Environment** | worktree left dirty, index lock, gitignored file the agent couldn't see, a run saturating the machine | Agent, but name it — it corrupts later runs |

Two structural facts to check for explicitly, because they hide:

- **A merged PR does not close its bead.** Auto-merge lands the PR minutes
  after the session ended; the bead stays `in_progress` forever and `bd ready`
  cannot see it. Always cross the merged-PR list against open beads.
- **An epic claimed by a dead machine is unrecoverable by any other.** The
  stale-claim sweep deliberately spares epics and features. If an epic's
  `assignee` machine is gone, only reassignment frees it — report it, don't
  wait for it to resolve itself.

## 4. Rank

Order by **how many things the answer unblocks**, then by **cost of being
wrong**, then by CI state. Concretely, top to bottom:

1. **A decision that gates other pending work.** One policy answer that
   releases four green PRs outranks four separate PR reviews. Find these by
   reading the parked questions *against* the pending list — the link is
   usually in the PR body, not in a dependency edge.
2. **Green drafts waiting on a ready call** — done work, one word from
   landing, and it rots into conflicts while it waits.
3. **Red CI on a PR that carries real work** — needs a fix, but the fix is an
   agent's job; you only decide whether it's still worth the attempt.
4. **Deliberate residuals** awaiting accept-or-refile.
5. **Bookkeeping and hygiene** — bundled into one line, never itemized.

This order is the report's order and the questions' order. Do not re-sort by
PR number, date, or epic.

## 5. The report

One message. Ranked. Per item at most four lines:

```
1. PR #26 — conservative-profile carve-out for the ready call     [decision]
   Blocks: #45 #46 #47 (all green, all draft, waiting on this policy)
   Q: Confirm the carve-out? And should the profiles section move out of the
      bd-managed block, where a regen would silently drop it?
   → Then: mark #45/#46/#47 ready; they merge themselves.
```

Rules that keep it tight:

- **Every pending PR states the information required from the user**, in one
  question, answerable without opening the PR. If nothing is required from
  the user, say what the agent will do instead — one line, no question.
- Merged-since-checkpoint work is **one line total** with counts, not a list.
  Name a merged PR only if it broke something or contradicts a doc.
- Bookkeeping drift is **one line**: "N beads still open behind merged PRs
  (ids) — close on your say-so."
- No status tables, no per-item summaries of what the PR does. The user wrote
  the epics; they need the delta and the question, not a recap.
- Anything you could not check (Linear down, a PR whose checks are still
  running) gets one honest line. Do not silently omit it.

Close the report with the questions restated as a numbered list in the same
order — so the user can answer "1: yes, 2: skip, 3: …".

## 6. Stop

Deliver the report and **wait**. Do not start on item 1. Do not "prepare" the
merge. Do not open a branch. The user's reply decides what happens next, and
acting before it arrives is exactly the failure this skill exists to prevent.

Then — and only then — write `.claude/last-sync.json` with the current
`origin/main` SHA, the timestamp, the PR numbers seen, and the parked-question
ids reported, so the next report starts where this one ended.
