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
- **"Landed", "merged" and "shipped" NAME THE BRANCH and the sha.** Each is
  ambiguous between a merge into a feature branch and a merge into `main`, and
  an orchestrator writing a residual mid-feature is at exactly the moment the
  two are easiest to conflate — it has just run a merge and seen it succeed.
  Measured: a residual asserted a findings entry had *landed* and imposed
  "STRICTLY APPEND-ONLY. Do not edit the landed entry", derived from a merge
  into a FEATURE branch. The entry had never been on `main` and was still in an
  open draft PR, so a reader obeying the constraint would have appended a
  correction to an entry shipping in the SAME pull request — publishing a
  document that contradicts itself in one commit, in an evidence file whose
  whole value is that readers can trust it (computenet-7z3t). **Any constraint
  whose truth depends on that distinction — append-only, do-not-edit,
  already-published — states which merge it rests on.**
- **A prescribed remedy is checked against the data the bead cites, or
  labelled a suggestion to verify.** In the same bead, a remedy told the
  implementer to attribute a "0.1–0.2 µs/element band" to the per-run medians;
  those medians are 0.082–0.223, both ends outside the band. Asserting a fix
  without running it against the bead's own evidence is the same failure as
  asserting an untested mechanism, one step downstream.
- **Backticks in a double-quoted `bd` argument execute as shell.** Bead
  bodies quote code, and Markdown's backticks are command substitution
  inside `--description="..."`: the shell runs each backticked phrase and
  splices its output (usually empty) into the stored text — the bead
  silently loses exactly its technical terms, `bd` reports success, and the
  quoted command really executes (computenet-9w9: filing an issue ran
  `gh pr ready`; only a missing PR stopped it shipping one). Never inline a
  body in double quotes. Build it with a quoted heredoc and pass the
  variable — delimiters at column 0, exactly as below (an indented `EOF`
  doesn't terminate, and the stray line lands in the stored body):

```bash
BODY=$(cat <<'EOF'
... any `code`, $vars, and paths, verbatim ...
EOF
)
bd create --title="..." --description="$BODY" ...
```

  For comments, `bd comment <id> --file <path>` skips the shell entirely.
  Every `bd create`/`bd comment` template in these references shows
  `"<placeholder>"` bodies for brevity — this rule governs them all.
- **Code citations are pinned and anchor-first.** Verify every file, module,
  and test you name exists at the commit you actually inspected, and name
  that commit. Prefer stable anchors (symbol names, requirement ids, a grep
  pattern) over bare line numbers: siblings merge underneath a written item,
  so a parent's line numbers are stale by construction the moment one lands
  (computenet-5ao: shifted spans, a moved package, criteria a sibling had
  already satisfied). Every child item you write must itself instruct:
  *re-verify all cited paths and line numbers against your own base commit,
  and record any drift rather than copying it forward.*
- **CI evidence must outlive the run it cites.** A bead citing a run id is
  not completely filed until the primary evidence is inline or attached as
  a `bd comment` at filing time: the failing task, the exception class and
  full stack, the surrounding task headers with timestamps, and the runner
  spec. GitHub ages run logs out in days while beads queue behind epics for
  weeks, so a bare "see run `<id>`" decays quietly and the implementer
  rebuilds the diagnosis from a second-hand summary (computenet-ttz:
  `gh run view --log` already returned nothing by the time the bead was
  worked). Excerpt what the fix will need, not the whole log.

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

1. **Current state**, with `path:anchor` (or `path:line`) evidence, pinned
   and re-verify-carrying per the Universal citation rule above.
2. **Decided direction** — what is settled, and what is explicitly left to
   the implementer's judgment. Name both; silence on a fork reads as "settled"
   to one agent and "open" to the next.
3. **Boundary** — non-goals in prose, plus the `metadata.files` claim. They
   must agree: a file in the claim that no instruction touches is noise, and a
   file the instructions require that isn't claimed is a merge conflict.
   A criterion that requires writing to **another bead** (a comment on a
   sibling, an update upstream) is part of the boundary too, and goes in
   `metadata.cross_bead` — ids and action — as well as the prose: the
   orchestrator relays that field verbatim into the dispatch prompt, and an
   authorization living only in the description is invisible to the policy
   check that reads the prompt (computenet-eetn). Omit the key when there is
   none; that is the normal case. See [feature.md](feature.md).
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

**Do not prescribe a mechanism the acceptance criterion cannot verify.**
Prose describing *how* is the clause most likely to be wrong — it can name a
design no codebase ever had — and when it contradicts the criterion, the
criterion wins ([task.md](task.md) step 1). computenet-lxq prescribed
expressing a bind as a structural seam while its criterion demanded a
mutation kill at the production call site; the two cannot both hold, and both
the implementer and its reviewer spent effort re-deriving which one governed.
Before writing an Implement clause, ask what observation would distinguish
"followed it" from "did not", and if the criterion cannot make that
observation, either strengthen the criterion or drop the prescription.

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
| Implement prose the acceptance criterion cannot check | the two can contradict; the implementer has to diverge to pass |
| "Re-measure at N runs" with no per-run cost | unsizeable, and an affordable substitute silently stands in for it |
