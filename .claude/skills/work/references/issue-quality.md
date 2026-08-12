# Writing epics, features, and tasks

The standard every breakdown writes to and every review judges against.
Cited by [epic.md](epic.md), [feature.md](feature.md),
[review-task.md](review-task.md), and [review-feature.md](review-feature.md).

**The one test:** a fresh agent, with no access to the conversation that
created the issue, can read it and know both what to build and when it is
done. Every rule below is a way of failing that test earlier, on the cheap
side.

## Universal

- **Title is an outcome**, a change to the system — not an activity.
  "Outlet brings late subscribers current" over "Work on catch-up".
- **Description carries the context you have and the reader doesn't**: what
  the system does here today, why the work exists, and which spec sections
  govern it. Cite the spec by path and section — that text is the authority
  (AGENTS.md), not the issue's own prose.
- **`--acceptance` is mandatory** on epics, features, and tasks. It also
  satisfies `bd`'s own gate, so create with `--validate` and run
  `bd lint <ids>` before you report — a missing criterion caught at creation
  costs nothing; caught at review it forfeits the work.
- **Every criterion is checkable.** Either a command someone can run, or a
  named observable artifact someone can read. "Behaves correctly", "is clean",
  "handles errors properly" are not criteria — they are the absence of one.
- **Say what's out of scope.** An unstated exclusion gets built anyway by
  whoever reads the issue most generously.

## Phrasing a requirement: EARS

Requirements use the five EARS templates already in force in this repo
(`concord/schema/provenance.md` has the full rules and examples):

| Template | Shape |
|---|---|
| Ubiquitous | The X SHALL … |
| Event-driven | WHEN «trigger», the X SHALL … |
| State-driven | WHILE «state», the X SHALL NOT … |
| Unwanted behavior | IF «condition», THEN the X SHALL … |
| Optional feature | WHERE «capability», the X SHALL … |

Two hard rules:

- **Cite ids, never mint them.** Requirement ids like `[21-PROP-01]` live in
  `doc/spec/`, are immutable, and are gated on being checkable through the
  concord driver SPI. Reference existing ones freely. Inventing
  `[21-PROP-99]` in a beads issue creates a dangling id that fails
  `./gradlew :concord:check` the moment anyone acts on it.
- **New normative requirement = spec work.** If delivering the item requires
  a requirement the spec doesn't state, say so explicitly in the description.
  If it would change what the spec already means, that clears the
  [ask-human.md](ask-human.md) bar — park it.

Use EARS *phrasing* for acceptance criteria even where no spec id exists. The
templates force a trigger, a subject, and an observable effect, which is
exactly what makes a criterion checkable.

## Epic — the outcome and its boundary

An epic is a coherent capability, not a bucket. It should read as one
sentence about the system, plus the edges of it.

- **Success criteria (3–7).** Observable end states for the whole capability,
  EARS-phrased. Each one must be traceable to at least one feature — a
  criterion no feature covers means the breakdown is incomplete, and it is
  the feature reviewer's job to notice.
- **Governing spec chapters**, by path.
- **Explicit non-goals**, especially anything research-gated
  (`doc/spec/90-roadmap/95-research-plan.md`) or owned by an adjacent epic.

Done means every feature closed *and* every success criterion demonstrable.
If those two can diverge, the criteria are wrong.

## Feature — example mapping, then rules and examples

Before creating any task, run **example mapping** on the feature. Four kinds
of card:

| Card | What it is | Where it lands |
|---|---|---|
| **Story** | the feature itself | the title |
| **Rule** | one EARS statement that must hold | `--acceptance`, one per line |
| **Example** | a concrete Given/When/Then that pins the rule down | `--design`, and later a test |
| **Question** | something you cannot answer from the cited spec | resolve, or park per [ask-human.md](ask-human.md) |

Read the map for the three signals it exists to give:

- **A rule with no example is not understood yet.** Write the example; if you
  can't, you don't know the rule well enough to hand it to an implementer.
- **More than ~6 rules means the feature is two features.** Split it.
- **A question you cannot answer from the spec is the decision point.** Cheap
  to get wrong → decide it and record the assumption in `--design`. Expensive,
  risky, or hard to revert → park it. That judgment is the whole of
  [ask-human.md](ask-human.md).

Examples are concrete: real types, real values, real cell/port names. "Given
a subscriber links after 3 deltas, when it connects, then its fold equals the
source's" is an example. "Given some deltas, when connecting, then it works"
is a rule wearing an example's clothes.

Feature criteria are stated at feature level — what must be true once the
whole thing works, not the sum of what each task does. A dedicated reviewer
ships or holds the PR on exactly these statements
([review-feature.md](review-feature.md)).

## Task — a plan, not a request

A task is what plan mode would hand an implementer for one part of a feature:
**the design decisions are already made, only execution is left.** If you
cannot write the "decided direction" below without inventing the design,
the task isn't ready — do that design work now, in the breakdown, or park the
question on the feature.

Five things, all of them:

1. **Current state**, with `path:line` evidence. Verify every file, module,
   and test you name actually exists.
2. **Decided direction** — what is settled, and what is explicitly left to
   the implementer's judgment. Name both; silence on a fork reads as "settled"
   to one agent and "open" to the next.
3. **Boundary** — non-goals in prose, plus the `metadata.files` claim. They
   must agree: a file in the claim that no instruction touches is noise, and a
   file the instructions require that isn't claimed is a merge conflict.
4. **Acceptance criteria** — which of the feature's rules and examples this
   task makes true. Task criteria are a partition of the feature's, not a
   restatement of them. A task whose criteria are the feature's whole criteria
   is not a task.
5. **Verification** — the exact command, e.g.
   `./gradlew :kernel:test --tests 'civictech.cell.…'`. If the task changes
   behavior, name the test that will assert it; the reviewer runs it.

   For a bug, **a prescribed reproduction is a claim and must be labelled as
   one.** Write either `verified-failing:` followed by the output you watched
   it fail with (test name and assertion message, or the command and its
   error), or `untested-hypothesis:`. Nothing in between, and never an
   unlabelled sequence: the two carry completely different authority and are
   indistinguishable once written down. computenet-dqy.20's prescribed
   sequence read as authoritative and in fact *passes* against the unfixed
   code, which buys an implementer a green test that proves nothing.
   [task.md](task.md) step 3 makes the implementer check — that is the
   backstop, not a reason to skip the label.

Sizing is by read-surface, per [feature.md](feature.md).

## Smells that send an issue back

| Smell | Why it fails |
|---|---|
| "Refactor X for clarity" | no observable outcome; nothing to review against |
| Criteria that restate the title | zero information added |
| "…and handles errors correctly" | which errors, doing what, observed how |
| A rule with no example | ambiguity survives to implementation |
| Task criteria == feature criteria | the split never happened |
| An invented `[NN-SLUG-nn]` id | dangling reference; breaks `:concord:check` |
| A task that starts "investigate whether…" | that's design, and it belongs in the breakdown or a parked question |
