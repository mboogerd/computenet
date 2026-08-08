# Feature → tasks

Same shape as [epic.md](epic.md), one level down: you are breaking one
feature into tasks. Plan it, don't implement it.

## Reconcile before creating

The feature may already have tasks from a breakdown that died part-way:

```bash
bd list --parent=<id> --json
```

Create only what's missing.

## The breakdown

- Read the full feature (`bd show <id>`), its parent epic for context, and
  any spec sections they cite. The cited spec text is the authority
  (AGENTS.md). Verify every file, module, and test you name actually exists
  before writing it into a task.
- Size tasks by **read-surface**: how much an agent must read and hold to be
  correct, not by diff size. Right-sized means a fresh agent with no prior
  session and no access to your context can read the task and do the work.
  Roughly an hour, per the dispatch time box.

For each task:

```bash
bd create --type=task --parent=<feature-id> \
  --title="<outcome as a change to the system, not an activity>" \
  --description="<context / problem with path:line evidence / solution direction, saying what's decided and what's left to judgment / verification commands>" \
  --acceptance="<checkable statements>" \
  --set-metadata model=<sonnet|opus> \
  --set-metadata files="<comma-separated paths it expects to create or modify>"
```

Both metadata fields are load-bearing for the orchestrator — a task missing
either can't be scheduled and will be sent back:

**`model`** — route by how much is already decided, not by importance:

| Task shape | model |
|---|---|
| Multi-file within one subsystem, judgment on fitting existing style | `sonnet` |
| Cross-module, or intent must be inferred from specs | `sonnet` |
| Novel design inside the task, subtle invariants, concurrency, protocol/wire | `opus` |

Route up when the task requires reading far more than it changes, or when
correctness depends on something not visible in the files it touches. Write
the description in the register that model needs: for `sonnet`, state
outcome, constraints, and boundaries, naming entry points rather than every
file; for `opus`, state the problem, the invariants that must survive, and
what is explicitly not open for redesign.

**`files`** — the file claim. The orchestrator runs tasks in parallel only
when their claims are disjoint, so an incomplete claim causes real merge
conflicts. Claim generously: a file you might touch belongs in the list.

## Dependencies

Wire `bd dep add <task> <blocker>` for **output dependencies only** — one
task genuinely consumes what another produces (a schema change before the
code that reads the new column; an interface before its implementor).

Do **not** wire a dependency for file overlap. Overlap is symmetric ("these
two shouldn't run at the same time"), while `blocks` is directional and
permanent ("this one can't start until that one closes") — encoding one as
the other forces an arbitrary ordering, and then strands the second task
whenever the first parks a question or stalls. The orchestrator already
computes overlap from `metadata.files` when it builds each batch, so
overlapping tasks land in different batches automatically. That's the
serialization, and it costs nothing when it turns out to be unnecessary.

Your job is to make the `files` claims **accurate**; the orchestrator's job
is to schedule around them. Don't wire a merely preferred order either —
over-wiring starves the queue and idles the session. If two tasks overlap
heavily, prefer resizing them so the claims separate.

Apply the [ask-human.md](ask-human.md) bar: if the approach is genuinely
ambiguous, or the split has a risky/expensive/hard-to-revert fork (a schema
or API-shape choice), park a question on the feature instead of guessing.

## Finishing

- `bd dolt push`.
- Comment on the feature summarizing the tasks created. Leave it
  `in_progress`, assignee unchanged (features close when their tasks do, not
  here). It already carries the epic's `owner:` label by inheritance — don't
  stamp it with this session's id (see claim-sync.md's note): that would make
  the `SessionEnd` hook release it, and it needs to stay claimed across
  sessions just like the epic does.
- Report back: the feature id, its parent epic id, and the task ids created.
  Your dispatch is done — the session dispatches the next item.
