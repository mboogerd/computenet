# Breaking down an epic or a feature

You read this when a dispatch prompt says "you are breaking down <epic|feature>
<id>": turn one parent into children (features under an epic, tasks under a
feature), then stop. You implement nothing. Read [agent.md](agent.md) first;
the level sections say what differs from the shared ones.

## Contents

- What a child issue carries
- Before you write: verify premises
- The files claim
- Metadata and edges
- Epic breakdown
- Feature breakdown
- Finish

## What a child issue carries

A child is a hand-off to a fresh agent with none of your context, and the
standard its reviewer judges against. Every child carries:

- A title that is an outcome, a change to the system ("Outlet brings late
  subscribers current", not "Work on catch-up").
- Context the reader lacks: what the system does there today, why the work
  exists, and the governing spec sections by path. The spec is the authority,
  not your prose.
- The decided design: what is settled and what is left to the implementer's
  judgment. Silence on a fork reads as settled to one agent and open to the
  next. A child that starts "investigate whether…" is design you have not done.
- Checkable acceptance — a command someone runs or an artifact someone reads.
  Children's acceptance partitions the parent's: each parent rule is owned by
  exactly one child, and no child restates the whole parent.
- Non-goals; an unstated exclusion gets built anyway.
- The exact verification command, able to fail if the acceptance is false. A
  task that changes the shape of a type constructed outside its module names a
  repo-wide compile (`./gradlew testClasses`), proven by each module's
  `compileTestKotlin` task line ([evidence.md](evidence.md#did-the-tests-run)).

Phrase acceptance in EARS form (`WHEN <trigger>, the X SHALL…`; all five
templates in `concord/schema/provenance.md`). **Cite requirement ids such as
`[21-PROP-01]`; never mint one**; an invented id fails
`./gradlew :concord:check`. A requirement the spec does not state is spec work: say so, and
park if it would change what the spec already means.

**Tag every factual claim with how you know it.** Later agents execute what you
write literally and cannot tell a guess from a check. A claim about existing
code or tools, a claim that a test proves a property, a cost or duration, a
prescribed command, reproduction or check (mutation route, fixture values,
expected result) is either `observed:` with the command, run or sha, or
`unverified:` so the implementer checks it first. A negative assertion over
values you chose ("not any member's row") can be false by coincidence; do the
arithmetic before writing it. A bug reproduction you did not watch fail on the
unfixed code is `unverified:`.

Keep the prose consistent with the acceptance and with time:

- Prescribe no method the acceptance forbids or cannot observe. If the
  acceptance cannot distinguish "followed it" from "did not", strengthen it or
  drop the prescription. Where the line is subtle, state the discriminator in
  one sentence ("walk to classify, measure to size").
- Pin state that moves: name the commit you inspected, prefer symbol anchors to
  line numbers, name the branch a "landed" constraint depends on, and write
  "the next free F-number" rather than a literal allocated later.
- Write a do-not-edit constraint as a condition ("an open sibling claims it"),
  not a roster of ids. Never write a worktree, branch or base sha into a child;
  dispatch assigns them.
- Qualify decision ids as `<bead-short-id>-D<n>` (`<id>-D6`) in `--design` and
  in every citation; `bd` renders every list as bare `D<n>`.
- Evidence that exists only in CI or on another platform is named as such:
  which half runs locally, which rides on CI, and the exact command that reads
  the CI answer. Excerpt a finished run's evidence into the bead; logs age out.
- A prescribed measurement states its per-run cost; split it if sample size
  times cost exceeds a task, so a cheaper run cannot stand in for it.

## Before you write: verify premises

A parent can assert its own infrastructure or behaviour ("over the existing
tailnet", "test X already pins this"), and every child inherits it. Before
decomposing, verify each load-bearing premise — environmental (host, service,
credential, tool, platform) or semantic (what a cited test compares) — with one
command, and comment commands and outputs on the parent. Confirm every file,
module and test you name exists. A "discovery" child is not verification.

When a premise is false, route by who can fix it:

| Situation | Do | Why |
|---|---|---|
| A tool, daemon or credential is missing on this machine (`.claude/skills/work/scripts/have-tool.sh <tool>` exits nonzero) | `bd update <id> --add-label "needs:<tool>"`, comment what you probed, stop and report | The parent is fine elsewhere and selection skips it here; a human park blocks every machine |
| The parent cites a decided, verified finding that retires the premise and names the replacement | Rewrite the parent's title, description and acceptance to match, comment the old wording and the finding, break down the rewritten parent, report the re-scope | The parent is instructing you; the orchestrator must re-read what it claimed |
| A person must decide: scope genuinely ambiguous, a risky or hard-to-revert fork, a finding absent or disputed | Park the parent and stop | A guess hands the fork to agents less placed to catch it |

A design question that evidence can settle is not a park: settle it and record
it in `--design`. Park per [recovery.md](recovery.md#parks); the `QUESTION:` comment states
what you verified, the fork, and what each answer produces.

## The files claim

`metadata.files` is a scheduling lock: tasks with disjoint claims run in
parallel, and an implementer stays inside its claim. An incomplete claim makes
a task unsatisfiable; an over-broad one costs a sibling a batch slot, which is
cheaper.

**A claim covers every file that must change for the acceptance to hold, and no
file the task only reads.** Claim what the design forces, not what the prose
names, including:

- the test that pins the acceptance, where the property lives, and any existing
  test that stops compiling or turns vacuous (an exhaustive `when` over a
  sealed hierarchy you extend);
- registries, enumerators and completeness gates over a set you add to — grep
  the set's identifier (package path, hierarchy root, catalog), not the new
  entry's, in the own module, other modules and `.github/`;
- call sites of changed public surface — grep each public member name, not only
  the declaring type — and the type that holds the state a fix must write;
- build and architecture inventories: a new Gradle module claims
  `doc/ARCHITECTURE.md`; a new cross-package kernel reference claims
  `kernel/src/test/resources/architecture/package-edges.txt`;
- comments and KDoc that narrate a rule you change (grep its distinctive
  phrases and requirement id); no test fails on a stale explanation.

Read the hits rather than claiming them all; an import is not an enumeration. A
sibling's claim is a lower bound. When unsure, claim wider and say in the
description that the breadth is deliberate and how you derived it.

Check pending work, not only the tree: a file that does not exist yet may be
claimed by an unstarted task elsewhere in the epic. Resolve the epic with
`.claude/skills/work/scripts/epic-of.sh <id>`, list the open children of each
of its features (`bd list --parent` reaches one level), and compare claims. A
sibling with no claim gives no signal: read its description instead.

An empty claim is legitimate when files are unknowable before a diagnosis
(state the expected scope in the acceptance) or the task produces no diff. Say
which in the description; never in `files`, where a sentence reads as a path.

## Metadata and edges

Create each task with `--metadata` carrying:

- `model` — `sonnet` when the direction is decided, even across modules;
  `opus` for novel design, subtle invariants, concurrency, wire formats, or
  correctness that depends on code the task does not touch. Write for it:
  outcome and entry points for `sonnet`; invariants and what is closed for `opus`.
- `files` — the claim, one comma-separated string of repo-relative paths.
- `cross_bead` — ids and action for any write the task must make to another
  bead. Omit it when there is none. The orchestrator relays this field into the
  dispatch prompt; an authorization only in prose never reaches the agent.

Wire `bd dep add <blocked-id> <blocker-id>` only when one child consumes
another's output, such as a file one task creates and the next amends. Never
wire an edge for overlap between independent work: batching separates
overlapping claims, and an edge strands the second child when the first stalls.
One `bd dep add` per Bash call; confirm with `bd dep list <id>`.

`bd` refuses blocking edges between an epic and a non-epic. When children must
wait on an epic's own deliverable, give the deliverable a feature child and
block the dependents on it. When a feature needs another epic's feature, wire
feature to feature and comment on your epic naming both ids; never substitute
an epic-to-epic edge.

For an EPIC breakdown, run `.claude/skills/work/scripts/breakdown-marker.sh
check <epic-id>` immediately before your first create and proceed only on OWN
(10) with the token the dispatch handed you: NONE means the local DB lost the
marker, FOREIGN or BOTH mean stop and report.

**Re-run `bd list --parent=<id> --all --json` immediately before your first
create, and stop and report if the child set changed since your first read.**
If your dotted ids skip numbers while you create, another writer is creating
under this parent: stop and report rather than finish the set.

Under the parent you were dispatched on, `bd create --parent=<id>` is safe for
a FEATURE breakdown's tasks; an EPIC breakdown's features always go through
`.claude/skills/work/scripts/create-ticket.sh --parent <epic-id> --breakdown
<token> ...` (6wc.5-D5 — under partition two claimants can both hold the epic,
and dotted ids would then collide on `child_counters`). Under any parent the
session does not hold, use `create-ticket.sh` either way.

Backticks inside a double-quoted argument execute, so bodies go in files
written with a quoted heredoc ([traps.md](traps.md#bd)):

```bash
bd create --type=task --parent=<feature-id> --validate --title="<outcome>" --body-file <scratch>/<feature-id>-t1-desc.md --acceptance="<EARS rules, no backticks>" --metadata '{"model":"sonnet","files":"<path-a>,<path-b>"}'
```

## Epic breakdown

Your children are features, each independently reviewable. Read the epic with
`.claude/skills/work/scripts/bead.sh <id>` and every spec section it cites.

- Start from the epic's success criteria: observable end states for the whole
  capability (usually three to seven), governing spec chapters, and non-goals,
  especially research-gated work (`doc/spec/90-roadmap/95-research-plan.md`). If
  they are vague or absent, repair them first — rewrite the field to read as
  current work, comment the superseded wording, and report the repair.
- Existing children may be consumers, not parts: if none is a feature, or each
  names the epic as a prerequisite, the epic's own deliverable still needs
  features and the consumers wait on them. If the listing shows near-identical
  pairs, run `.claude/skills/work/scripts/twin-scan.py <id>`, treat a flag as a
  question, and report it rather than create more.
- Give each feature EARS rules true once the whole feature works, examples in
  `--design`, and enough context for a fresh agent to break it down.
- **If the prompt says this is a sub-epic under an epic the session holds, do
  not claim it, set an assignee, add an `owner:` label, or comment on it.** The
  orchestrator records its provenance.

Create each feature with the token the dispatch handed you, never `bd create
--parent=` (see "Metadata and edges" above):

```bash
.claude/skills/work/scripts/create-ticket.sh --type=feature --parent=<epic-id> --breakdown <token> --title="<outcome>" --desc-file <scratch>/<epic-id>-f1-desc.md --accept-file <scratch>/<epic-id>-f1-accept.md --metadata '{"model":"sonnet","files":"<path-a>,<path-b>"}'
```

## Feature breakdown

Your children are tasks: the design decided, only execution left. Read the
feature and its epic with `bead.sh`, and every spec section cited.

- Map examples first. Each rule is one EARS statement in the acceptance, each
  rule gets a concrete Given/When/Then with real types and values in
  `--design`, and each question is resolved or parked. A rule with no example
  is not understood yet. More than about six rules usually means two features:
  report that.
- Fix a short-falling feature before splitting it. `--design` replaces the
  whole field: read it to a file, stop unless the read exits 0, append, and
  write the file back.

```bash
BEAD_SPILL_BYTES=100000000 .claude/skills/work/scripts/bead.sh <feature-id> -r '.design // ""' > <scratch>/<feature-id>-design.md
```

```bash
bd update <feature-id> --design-file <scratch>/<feature-id>-design.md
```

- Each task carries current state with path and anchor evidence, the decided
  direction, non-goals that agree with its claim, the rules it owns, and its
  verification command.
- Size by read-surface (what a fresh agent must read and hold), so a task
  fits one implementer session ([implement.md](implement.md#hand-off)).
- A test-only task names how its implementer will show the tests are not
  vacuous without leaving its claim ([evidence.md](evidence.md#mutation-checks)).
- Anchors in code a blocker has not landed yet are `unverified:`.
- If the feature's `metadata.files` omits a file a task must edit, widen it
  with `bd update <feature-id> --set-metadata files=<list>`, since the feature
  review scopes to it, and claim the file on the task too.

## Finish

```bash
bd lint <child-ids>
```

1. Fix what `bd lint` reports. For tasks, run
   `.claude/skills/work/scripts/check-files-claim.sh <task-ids>` (its header
   documents the output); it sees only files the text names, so clean does not
   replace [the walk](#the-files-claim).
2. Check the trace both ways: every parent criterion is owned by a child (else
   you are not done), and every child serves one (else it is out of scope).
3. Comment the created ids on the parent (`bd comment <id> --file <path>`),
   unless it is a sub-epic. Leave the parent's status as you found it, and do
   not sync.

Report per [agent.md](agent.md#your-final-message): the ids created, any
re-scope, parent repair, park or `needs:` label, and under
`REQUIRED ORCHESTRATOR ACTION` the exact command for any write you were refused.
