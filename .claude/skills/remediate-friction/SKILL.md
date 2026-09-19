---
name: remediate-friction
description: Owns changes to .claude/skills/ and keeps the skills correct, small and consistent. Triages everything filed under the SDLC epic (computenet-wpvy), ships same-day fixes only for wrong prescriptions, script bugs and real hazards, and periodically revises whole sections to fix the rest. Use when a routine or the user starts friction remediation ("/remediate-friction", "drain the friction log", "fix the work skill friction"). Also use for ANY edit to a skill file under .claude/skills/, however it arrives, because the gates that check skill edits live here.
---

# /remediate-friction

Keep the skills under `.claude/skills/` correct, small and consistent. The
goal is a falling friction rate, not an empty queue: a lane that turns each
friction into a sentence regrows the skill it exists to fix.

The lane works in two modes, and the nightly scheduled run does both:

- **Triage** classifies every open item. It usually edits nothing.
- **Revision** rewrites one whole section when enough queued items point at
  it. It leaves the section no longer than before.

## Boundaries

- **Never claim the SDLC epic.** Claim only its child items, so /work's
  concurrent-run check never sees this lane.
- **Touch only `.claude/skills/` and `AGENTS.md`.** Product code belongs to
  /work.
- **Sync.** Triage writes (closes, labels, metadata, comments) stay local
  until §6. Acquisitions (claiming an item, filing with `create-ticket.sh`)
  are bracketed pull → verify → write → push. Claim with `claim-item.sh`,
  push with `publish-beads.sh`. Scripts live in `.claude/skills/work/scripts/`.

## 1. Start

```bash
echo "${BEADS_ACTOR:?}"; git fetch origin main; bd dolt pull
.claude/skills/remediate-friction/scripts/recurrence-audit.py   # landed fixes that did not take
bd list --parent=computenet-wpvy --all --json | sed -n '/^[[{]/,/^[]}]/p' \
  | jq '[(if type=="array" then . else (.issues // []) end)[] | select(.status != "closed")]'
```

Skip items labelled `human`, `needs-evidence` or `revise` (already queued).
Skip a claimed item whose `session-holder.sh --check <metadata.holder>` says
LIVE or FOREIGN. Any other claimed item that has been untouched for more than
12h may be taken: pull, claim it, push, and leave a comment saying so.
Triage itself claims nothing; claim an item only before fixing it (§3).

## 2. Triage

Take items in order of `comment_count` (recurrence) descending. For each,
read the **current** skill text against the complaint; `metadata.skill_version`
names the revision it was filed under. Choose one row:

| The friction is… | Do |
|---|---|
| Already fixed by the current text | Close: `superseded: <git rev-parse --short origin/main>` |
| A command, flag, path or fact the skill states wrongly | **Fix now** (§3). Reproduce it first |
| A bug in a skill script | **Fix now**, with a test that fails without the fix |
| A data-loss, collision or shipping-safety hazard | **Fix now** |
| An existing rule that misfired, or two rules that conflict | **Queue** for revision |
| A new tool or environment hazard that can recur | **Queue** as a `traps.md` row |
| Covered by a principle, or something a capable agent reasons through | Close: `covered: principle <N>` or `covered: judgment`, naming which |
| Elapsed time, stalls or ending a turn | Close: `time:` unless a script could detect it; never add prose |
| A product, tracker or CI bug | Re-file outside the SDLC epic, then close: `refiled: <new id>` |
| A misreading of the skill | Close: `rejected:` and quote the text it misreads |
| One instance you can't verify | Park (below) |

Before closing or queueing, record the class from
[error-classes.md](references/error-classes.md):
`bd update <id> --set-metadata friction_class=E<n>`. Make one `bd` write per
call.

- **Queue:** `bd update <id> --add-label revise --set-metadata 'section=<file>#<heading>'`.
- **Park:** comment exactly what a future instance must capture (the command,
  its verbatim output, what it cost), then
  `bd update <id> --add-label needs-evidence`.
  /work step 7 removes the label when a second instance arrives.
- Unsure whether a change would be an improvement? Label the item `human` and
  park the question per /work's `references/recovery.md` "Parks".

**The bar for new skill prose.** The friction has at least two independent
instances, no principle already covers it, and a capable agent could not
derive it. Even then, the prose must replace at least as much text as it
adds, or arrive inside a revision. Fix-now rows *correct* text; they don't
grow it.

## 3. Fix now

Claim the item (`claim-item.sh`, then push). Make one small PR per item, in
a worktree cut from `origin/main`:

```bash
.claude/skills/work/scripts/ensure-worktree.sh "$PWD/../computenet-worktrees/<id>" friction/<id> origin/main
```

Run the gates (§5), commit with the item id in the message, and open a draft
PR. Dispatch a fresh reviewer under /work's `references/agent.md`. On its
READY, run `gh pr ready <n> && gh pr merge <n> --auto --squash`; both, always.
When it merges, close the item as `fixed in <pr-url>` and remove the worktree.
A claim you still hold at session end: land the PR or release the claim
(`--assignee="" --status=open`).

## 4. Revision

Run a revision when one section has **5 queued items**, or **2 queued items
that each have a second instance** (`comment_count` ≥ 1). Run at most one per night, and keep at most
one revision PR open at a time.

1. **Pin** `origin/main`'s sha. Read the whole section and every queued item
   for it, against the error classes.
2. **Rewrite the section** instead of inserting into it. Each rule keeps one
   owning file and one statement, placed in the file of the role that acts
   on it (`reachability.py --for <role> <file>`). The section must be **no
   longer than before**.
3. **Audit twice,** with two fresh agents that did not write the rewrite:
   - a *coverage audit*: every load-bearing rule in the pinned text survives
     or is deliberately changed;
   - a *walkthrough*: an agent traces the scenarios from the queued items
     through the new text and reports where it gets stuck.

   Fix what they find.
4. **One draft PR.** Its description lists each queued item and its outcome.
   It stays a draft for a human to approve: park that question per
   `recovery.md` "Parks" so a person sees it. Close the items when it merges.

## 5. Gates

```bash
ruby .claude/skills/remediate-friction/scripts/validate-skills.rb     # frontmatter, cited paths, line caps
.claude/skills/remediate-friction/scripts/sibling-tests.sh            # suites of every changed script
.claude/skills/work/scripts/usage-table.test.sh                       # work/SKILL.md script table
```

The caps are SKILL.md ≤ 600 lines (this file ≤ 150), each reference ≤ 300,
and `AGENTS.md` ≤ 700. Over a cap means rewrite, not justify; raising a cap
is a reviewed edit to `validate-skills.rb`. The commit message is where a
change explains itself.

## 6. Finalize

Run `publish-beads.sh` with a timeout of at least 300s. Then report:

- verdict counts per triage row, with PRs for fix-now items;
- the revision PR, if any;
- queue depth per section;
- friction filed since the last run, by class;
- `recurrence-audit.py`'s `FAILED-FIX` lines.

A rate that does not fall across revisions means this lane's approach is
wrong. Say so.
