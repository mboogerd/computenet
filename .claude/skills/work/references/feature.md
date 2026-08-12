# Feature → tasks

Break one feature into tasks, then stop. Plan it, don't implement it.

## Reconcile first

```bash
bd list --parent=<id> --all --json
```

Create only what's missing.

## Break it down

Read the feature (`bd show <id>`), its parent epic, and every spec section
they cite — that text is the authority (AGENTS.md). Verify every file,
module, and test you name actually exists before writing it into a task.

**A task is a plan, not a request** — see the task section of
[issue-quality.md](issue-quality.md). What you hand the implementer is what
plan mode would hand you: the design already decided, only execution left. If
you can't state the decided direction without inventing the design, do that
design work now or park the question — don't pass the fork downstream.

If the feature's own rules and examples don't meet
[issue-quality.md](issue-quality.md), fix them first
(`bd update <feature-id> --acceptance=… --design=…`). A rule with no example
is ambiguity you are about to multiply across every task you cut from it.
Task criteria **partition** the feature's rules; each rule should end up owned
by exactly one task, and none left unowned.

Size by **read-surface**: how much an agent must read and hold to be
correct, not diff size. A fresh agent with no access to your context should
be able to read the task and do the work — roughly an hour of it.

```bash
bd create --type=task --parent=<feature-id> --validate \
  --title="<outcome as a change to the system, not an activity>" \
  --description="<current state with path:line evidence / the decided direction, saying what's settled and what's left to judgment / non-goals / the exact verification command>" \
  --acceptance="<which of the feature's rules and examples this task makes true>" \
  --metadata '{"model":"<sonnet|opus>","files":"<comma-separated paths it will create or modify>"}'
```

Note the flag: `bd create` takes **`--metadata`** with a JSON object.
`--set-metadata key=value` exists only on `bd update` — passing it to
`bd create` fails with `unknown flag` and creates nothing.

Both metadata fields are load-bearing — a task missing either can't be
scheduled and gets sent back.

**`model`** — route by how much is already decided, not by importance:

| Task shape | model |
|---|---|
| Multi-file within one subsystem; judgment on fitting existing style | `sonnet` |
| Cross-module, or intent must be inferred from specs | `sonnet` |
| Novel design inside the task, subtle invariants, concurrency, protocol/wire | `opus` |

Route up when the task requires reading far more than it changes, or when
correctness depends on something not visible in the files it touches.

Write the description in that model's register: for `sonnet`, state outcome,
constraints, and boundaries, naming entry points rather than every file; for
`opus`, state the problem, the invariants that must survive, and what is
explicitly not open for redesign.

**`files`** — the file claim. Tasks run in parallel, each on its own branch,
only when their claims are disjoint. Two branches editing the same file
merge into a conflict, so an incomplete claim costs a hand-resolved merge
later. Claim generously: a file you might touch belongs in the list.

## Dependencies

`bd dep add <task> <blocker>` for **output dependencies only** — one task
genuinely consumes what another produces (a schema change before the code
reading the new column).

Never wire one for file overlap. Overlap is symmetric; `blocks` is
directional and permanent, so encoding one as the other invents an arbitrary
order and strands the second task whenever the first stalls. The orchestrator
already separates overlapping claims into different batches.

Your job is accurate `files` claims; scheduling around them is not yours.

Apply the [ask-human.md](ask-human.md) bar: if the approach is genuinely
ambiguous, or the split has a risky/expensive/hard-to-revert fork (a schema
or API-shape choice), park a question on the feature instead of guessing.

## Finish

```bash
bd lint <task-ids...>
```

Fix anything `bd lint` reports, and check that every feature rule is owned by
some task. The tasks you created live in the local beads DB until the
orchestrator's Finalize push (SKILL.md step 6) sends them to the shared
tracker — don't sync here; the session syncs twice in total, a pull at start
and that push at the end.

Comment the tasks created on the feature. Leave it `in_progress` — features
close when their tasks do. Report the task ids.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
