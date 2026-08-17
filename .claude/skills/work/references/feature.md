# Feature → tasks

Break one feature into tasks, then stop. Plan it, don't implement it.

## Reconcile first

```bash
bd list --parent=<id> --all --json
```

Create only what's missing.

## Verify the load-bearing premises first

An item can assert its own infrastructure. "Over the existing Headscale
tailnet that is reachability and encryption for free" is a *premise*, not a
requirement, and the breakdown inherits it into every child it writes.
Epic computenet-o97 did exactly that: two of five features were written on top
of that sentence, and there is no tailnet on this machine — no
tailscale/headscale binary, no `100.64.0.0/10` address on any interface,
nothing in the repo mentioning it. One `ifconfig` would have caught it, before
~40 minutes of breakdown across two agents.

So before you decompose: **list the item's load-bearing environmental
premises — a host, a network, a service, a credential, a tool on `PATH`, a
platform behaviour — and verify each with a concrete command.** Put the
command and its output in the item's comment thread, so the next reader does
not re-derive it.

```bash
command -v <tool>            # a tool the item assumes is installed
ifconfig | grep 100.64       # a network: grep an ADDRESS PREFIX, not a CIDR —
                             # interfaces render 100.x.y.z, so 100.64.0.0/10 never matches
ls <path>                    # a host artifact the item assumes exists
curl -sS -o /dev/null -w '%{http_code}' <url>   # a service it assumes is up
```

**If a premise is false, park the item** ([ask-human.md](ask-human.md)) rather
than producing children that inherit it. Deferring the question into a child
task as a "discovery step" is not verification — it is the same unverified
assumption, one level further from anyone who could notice.

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
be able to read the task and do the work — roughly 45–60 minutes of it (the
orchestrator budgets on that figure).

**A clause that prescribes a measurement must state its per-run cost**, so
the sizing above can be applied to it. "Re-measure at >= 240 fresh-JVM runs"
is not sizeable until someone writes down that a fresh-JVM run costs ~40s,
i.e. ~2.7h — well past a task slot. Multiply N x cost yourself and split the
task, or file the large sample as its own item, rather than handing an
implementer a number it cannot afford.

This shape fails more quietly than an oversized implementation clause. An
implementation task that is too big produces visibly unfinished code; a
measurement task that is too big produces **a number**, which looks like an
answer. Measured on computenet-dqy.37: the clause named one instrument
(in-process, baseline 0.26%) and a sample size belonging to a different
experiment (fresh-JVM, baseline 0.83%); the implementer spent 993s on the
affordable one and correctly reported that 0/260 bounds the rate at 1.15% —
which does not exclude even the unrepaired rate. The failure mode to close is
**an affordable measurement silently standing in for an unaffordable one**.

```bash
# Bodies quoting code: heredoc-build them first (issue-quality.md
# "Backticks…", computenet-9w9) — inline backticks execute as shell.
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
scheduled and gets sent back. One exception: a **diagnosis-first task** (a
flake, a defect whose location *is* the question) cannot know its claim —
any pre-diagnosis `files` is a guess that co-schedules a sibling into a
conflict, and a guess has been categorically wrong (test-source hypothesis,
production-defect reality; computenet-ahu). Leave `files` empty, open the
description with `files unknowable before diagnosis`, and put the expected
scope in the acceptance criteria instead ("diff confined to test sources
unless a production defect is found, in which case it is reported"). The
orchestrator dispatches it alone and writes the real claim from the diff.

If the task is a bug fix and you write a reproduction into its description,
label it `verified-failing:` (with the output you watched it fail with) or
`untested-hypothesis:` — see [issue-quality.md](issue-quality.md). An
unlabelled sequence is read as verified, and one that in fact passes against
the unfixed code hands the implementer a test that goes green while proving
nothing.

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

Two tasks **may** deliberately claim the same file when a dependency edge
sequences them — a create-then-amend pair is the intended shape, not a claim
collision. Overlap is only a defect among tasks meant to run *concurrently*;
once `bd dep add` orders them they never share a batch, so the shared claim
costs nothing and is the honest record of what each will touch
(computenet-bx4y). This is the one case where an edge follows file overlap,
and it is legal because the edge is an output dependency in its own right:
the second task amends what the first creates.

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
tracker — don't sync here; only acquisitions are synced mid-session, and
this is not one (claim-sync.md).

Comment the tasks created on the feature. Leave it `in_progress` — a feature
closes only when its PR merges, never on task completion or a review verdict
(review-feature.md: "Ready is not merged"). Report the task ids.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
