# Feature → tasks

Break one feature into tasks, then stop. Plan it, don't implement it.

## Contents

- [Reconcile first — and again immediately before the first create](#reconcile-first-and-again-immediately-before-the-first-create)
- [Do not write a worktree, a branch or a base commit into a task](#do-not-write-a-worktree-a-branch-or-a-base-commit-into-a-task)
- [A bead must not suggest a method its own acceptance forbids](#a-bead-must-not-suggest-a-method-its-own-acceptance-forbids)
- [What a file claim must include that the bead never says](#what-a-file-claim-must-include-that-the-bead-never-says)
- [Verify the load-bearing premises first](#verify-the-load-bearing-premises-first)
- [Break it down](#break-it-down)
- [Dependencies](#dependencies)
- [Finish](#finish)

## Reconcile first — and again immediately before the first create

```bash
bd list --parent=<id> --all --json
```

Create only what's missing.

**Re-read this listing immediately before your first `bd create`, and abort
with a report if the child set changed.** One check at the start is a
check-then-act with a multi-minute window, and nothing revalidates inside it.
On 2026-08-17 two breakdown agents dispatched ~3 minutes apart both ran this
listing when epic `computenet-4ru` had exactly one child, both correctly
concluded "not decomposed", and both proceeded. Their creates interleaved:
**13 features where 6 belong**, in near-duplicate pairs covering identical
scope, with the epic's blocking edges left straddling both sets
(computenet-f2p4).

That outcome is worse than clutter, and specifically so: `next-batch.py`
batches on disjoint `metadata.files`, and a duplicate PAIR has **identical**
claims — so it does not merely schedule both, it schedules them as *unrelated*
work, putting two implementers into the same paths.

**If your own dotted ids skip numbers while you are creating, another writer
is creating under this parent right now.** Both agents watched their ids skip
(one got `.3/.5/.6/.8`, the other `.2/.4/.7/.9`) and one noticed in real time,
with no rule attached to the signal. Stop and report rather than finishing the
set; a partial set someone can reconcile beats a complete duplicate one.

## Do not write a worktree, a branch or a base commit into a task

They are assigned at **dispatch** time by `next-batch.py` and
`ensure-worktree.sh`, and they are **not knowable when the task is filed**.
Every task in one session opened its description with a line naming the
FEATURE's worktree and branch — for one, in as many words, *"Work in the
feature worktree …, branch feature/…"* — which is wrong for every task:
SKILL.md 5b gives each task its OWN worktree and branch cut from the feature
branch, and 5c merges back. An implementer that followed its bead literally
would work where the orchestrator merges, and violate one-worktree-one-live-agent
by construction as soon as two tasks run concurrently, as two did. It happened
on 4 of 4 tasks across 2 breakdowns, so it is a habit, not a slip
(computenet-qlky).

The same goes for `Base: origin/main <sha>`: by dispatch time one task's real
base was two sibling merges later than the sha its bead named. **If a task
must reference the feature, name the feature's ID and nothing else.** A
breakdown that emits a worktree path, a branch name or a base sha into a task
description is a defect the next reader can point at.

## A bead must not suggest a method its own acceptance forbids

When a task's acceptance rules out a class of technique — estimating,
guessing, substituting a default — the *suggested method* you write must not
name a member of that class. One bead offered "a reflective retained-size walk
… classifying Timestamp instances and tag-map structure" as a sizing technique
while its own acceptance carried `[BEN1-21]`, which forbids *estimating* the
payload/metadata split — and any hand-rolled shallow-size model is an estimate.
The two clauses pulled opposite ways, and resolving them decided the whole
instrument. The implementer got it right and paid design time to notice; the
orchestrator read the bead and dispatched it, because the contradiction was
*inside one bead* and no file-claim check or batching rule can see that
(computenet-qp07).

**Where the distinction is subtle, state the discriminator in the bead.** For
that case one sentence removed the whole ambiguity: *walk to classify, measure
to size.*

## What a file claim must include that the bead never says

`check-files-claim.sh` greps the bead's text for path-shaped strings, so three
kinds of required file are invisible to it by construction. Get them into the
claim **at filing**, where you know the answer, rather than having it widened
after a red suite:

- **A task that adds a Gradle module claims `doc/ARCHITECTURE.md`.** kernel's
  `ModuleInventoryTest` parses every `include(...)` line in
  `settings.gradle.kts` and fails `:kernel:test` unless each module appears as
  a literal backticked `` `:x` `` in that doc. Its KDoc forbids editing the
  test to pass, and its `documentedExceptions` allowlist is not the sanctioned
  route — so a claim of `<module>/,settings.gradle.kts` is **unsatisfiable by
  construction**. Two sessions hit this the same day, independently, on
  `:oracle` and `:identity` (computenet-d7qn, computenet-m9px).
- **A criterion that names a TYPE claims that type's defining file.** "…
  matchable by kind from `RunOutcome`" names the only file a kind can be added
  to, and a type name is not slash-separated and has no extension, so nothing
  about it looks like a path. One such task was unsatisfiable from the moment
  it was filed and the guard passed it clean; the implementer discovered it
  mid-work and had to choose between stalling and working outside its claim
  (computenet-hws5). The sibling consequence counts too: **adding a kind to a
  sealed hierarchy breaks every exhaustive `when` over it**, so the test
  holding that `when` is required and belongs in the claim as well.
- **"The file does not exist yet" is NOT evidence the claim is free.** A
  breakdown checked that `doc/bench/findings.md` existed neither on
  `origin/main` nor on the sibling feature branch — both true — and filed a
  task to create it, while a task under a *different feature of the same epic*
  already claimed creating it. The collision was with a task that had not run
  yet, so it was invisible to every check that looks at the tree, and nothing
  compares claims across features (computenet-55ro). Check the **pending
  tasks** under the whole epic, not the tree:

  ```bash
  .claude/skills/work/scripts/epic-of.sh <feature-id>      # -> <epic-id>
  bd list --parent=<epic-id> --all --json | sed -n '/^[[{]/,$p' \
    | jq -r '(if type=="array" then . else (.issues // []) end)[]
             | select(.status != "closed")
             | "\(.id)\t\((.metadata // {}).files // "-")"'
  ```

  An overlap that is real gets a `blocks` edge and a comment saying the file
  will already exist and must be appended to, not created — which is what was
  done by hand the one time anybody noticed.

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
  --metadata '{"model":"<sonnet|opus>","files":"<comma-separated paths it will create or modify>","cross_bead":"<ids and action, or omit>"}'
```

Note the flag: `bd create` takes **`--metadata`** with a JSON object.
`--set-metadata key=value` exists only on `bd update` — passing it to
`bd create` fails with `unknown flag` and creates nothing.

**`cross_bead` is where an authorized write to ANOTHER bead is recorded.** If
this task's criteria require touching a bead other than its own — commenting
on a sibling, updating an upstream item — name the ids and the action here
(`"cross_bead":"computenet-abc: comment the measured number"`). Omit the key
when there is none; that is the normal case and the orchestrator reads a
missing key as "none authorized".

Write it here rather than only in the description. The orchestrator has to
restate this verbatim in the dispatch prompt — authorization living only in
the bead is invisible to the policy check, which reads the prompt — and
without a field it would have to hand-grep every task's prose for a clause it
cannot reliably spot (computenet-eetn). A field it can read is the difference
between a load-bearing input and a guess.

The `model` and `files` fields are load-bearing — a task missing either can't be
scheduled and gets sent back. One exception: a **diagnosis-first task** (a
flake, a defect whose location *is* the question) cannot know its claim —
any pre-diagnosis `files` is a guess that co-schedules a sibling into a
conflict, and a guess has been categorically wrong (test-source hypothesis,
production-defect reality; computenet-ahu). Leave `files` empty, open the
description with `files unknowable before diagnosis`, and put the expected
scope in the acceptance criteria instead ("diff confined to test sources
unless a production defect is found, in which case it is reported"). The
orchestrator dispatches it alone and writes the real claim from the diff.

A **zero-diff task** — a measurement, a soak, a spike whose deliverable is a
bd comment or a run id rather than a file — has the same shape and the
opposite cause: its claim is not unknowable, it is genuinely *empty*. Leave
`files` empty and open the description with `no diff: <what it produces
instead>`. **Never write a nominal claim over files it only reads**: a claim
is a lock, so a read-only claim blocks a sibling that needs to write those
files, for no benefit (computenet-wpvy.30). The orchestrator batches a
claimless task alone, which is the right handling for both shapes.

The description is the only thing that tells the orchestrator which shape it
is looking at — nothing checks these openers mechanically, so use them
verbatim rather than a paraphrase, and never leave `files` empty in silence:
a task whose description explains neither emptiness reads as having simply
**forgotten** its claim, and that is a breakdown defect to fix, not a shape to
schedule around. Equally, never put the explanation *in* `files` — a
descriptive string there (`none (tracker mutations only)`) is read as a path
and batched on, which is worse than an empty claim, not better
(computenet-wpvy.30).

**A clause that predicts CURRENT behaviour gets the same premise check you
applied to your input.** You verify the epic's premises before splitting it;
the examples and acceptance clauses *you write* are premises too, and an
unverified one is paid for a layer down — the implementer builds against it,
discovers a one-command probe falsifies it, and the cost lands on the party
least placed to absorb it (computenet-j69i). So wherever a clause asserts
something about code that already exists, or about an external tool's
observable behaviour:

- **Run the one command that confirms it** — `grep` for the symbol, `ls` the
  path, `<tool> --help`, the single test — and write what you observed into
  the clause. It is seconds at this layer.
- **Or mark it `unverified:`** in as many words, so the implementer knows to
  check it *first* rather than to trust it. That is a legitimate answer; an
  unmarked guess is not, because nothing downstream can tell your checked
  claims from your plausible ones.

This is `task.md` step 3's rule — a prescribed reproduction is run against the
unfixed code before anything is built on it — applied one layer up, to the
clauses that generate those reproductions.

If the task is a bug fix and you write a reproduction into its description,
label it `verified-failing:` (with the output you watched it fail with) or
`untested-hypothesis:` — see [issue-quality.md](issue-quality.md). An
unlabelled sequence is read as verified, and one that in fact passes against
the unfixed code hands the implementer a test that goes green while proving
nothing.

**Evidence the implementer cannot produce locally must say so, and say how to
read it.** A clause whose proof only exists on another platform or inside a CI
job — "passes on Linux", "the serial lane runs it with two JVMs" — is
unsatisfiable where the implementer is standing, and an implementer that does
not know this spends slot time rediscovering it (computenet-wpvy.31). Split
the clause explicitly:

- **which half is local** (the suite it can run on darwin, the behaviour it
  can prove here),
- **which half rides on the CI dispatch**, named as such,
- **the exact command that reads the dispatch's answer**, runnable as
  written — not "check CI".

On that third bullet, name the command the way you would run it, redirect
included. `gh run view <run-id> --log` is the usual one and it is **large**:
measured 2026-08-17 on this repo's CI, 2670 lines / 345 KB for a green run in
3s, so it goes to a file and gets grepped, never straight into a reader's
context. Pick the narrowest form that answers the clause:

```bash
gh pr checks <pr>                                   # pass/fail per check, cheap
gh run view <run-id> --log > "$SCRATCH/run.log"     # whole run; then grep it
gh run view <run-id> --job <job-id> --log           # one job of a matrix
gh run view <run-id> --log-failed                   # a failure's lines only
gh run download <run-id> -n <artifact>              # when the answer is ONLY
                                                    # in an artifact
```

The last line is not hypothetical: computenet-wpvy.31's own case was a
re-arm marker that `wire-suite-sample.yml` never echoes into the job log, so
`--log` answers nothing and only the downloaded `chunk-*.console` does. If
that is your clause's shape, say the artifact name. And if you cite an
*already-finished* run rather than the one this PR will trigger, excerpt the
evidence into the bead — GitHub ages run logs out in days
([issue-quality.md](issue-quality.md), computenet-ttz).

Without the third bullet, "verified on Linux" becomes a claim nobody can
check, and the platform half quietly turns into an unverified assertion the
reviewer inherits.

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

Comment the tasks created on the feature.
The invocation, since it is the one command this file asks you to run and
nothing else shows it — the body is **positional**; `--text`, `--body` and
`bd comment add` are all wrong and have each been guessed by a different
agent (computenet-danb, computenet-63pn):

```bash
bd comment <id> "<text>"
bd comment <id> --file "$SCRATCH/note.md"   # any body that quotes code
```

Use the `--file` form whenever the text contains backticks: inside a
double-quoted argument they execute as shell and the word vanishes from the
stored comment while `bd` reports success ([bd-traps.md](bd-traps.md)).
 Leave it `in_progress` — a feature
closes only when its PR merges, never on task completion or a review verdict
(review-feature.md: "Ready is not merged"). Report the task ids.

**Friction:** end your report with anything that made you slower or forced a
guess — an unusable parent item, a command here that did not work, a case these
instructions do not cover. Report it; do not file it. The orchestrator logs it
centrally so recurrences are visible (SKILL.md step 7).
