# V1C-DESIGN — design note: the wave-neutral bounded state read (MRB-157)

**Status**: Specified — not-started
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that also fails, stop and hand the ticket back to the
orchestrator — do not attempt a third pass.
**Wave:** 6 (may start any time after checkpoint C3) · **Branches:**
`ticket/v1c-design`

## Your role

**This ticket produces a design document, not code.** You will read kernel and
inspector code closely, and you will write exactly one new markdown file. You
will not change a line of Kotlin, TypeScript, YAML or any other document. The
deliverable is judged as an argument: whether the problem is stated with
evidence, whether the constraints are surveyed honestly, whether the proposal
is specific enough to cost, and whether the recommendation is one a reviewer
can act on.

You are also, in effect, auditing a claim the repository makes about itself.
Take that seriously: §"Problem" below points you at a documented statement
whose supporting code you must locate and verify yourself. If it is wrong, say
so and give the corrected version — the value of this document is largely in
getting that right.

## Context

ComputeNet is a Kotlin/JVM dataflow runtime of cells, typed ports, explicit
links and wave-stamped messages. The Inspector (`inspect/`) is a read-only
HTTP/SSE view of a live host process; `doc/spec/90-roadmap/97-inspector-plan/`
is its shipped v3 plan (M0–M5, all merged), and
`doc/spec/90-roadmap/98-inspector-v4-plan/` is the current run, whose design
notes (`10-design-notes.md`) you must read in full first.

Three of the inspector's most-wanted capabilities are blocked on the same
missing kernel primitive, tracked as **MRB-157**:

- **browse-everything state chips** — showing a state summary on many cells at
  once, instead of only the one cell the user has explicitly observed;
- **honest data search** — `GET /search?mode=data` today copies whole cell
  states to grep them (`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt`);
- **big-cell state views** — a cell holding 10⁵ rows is read in full and then
  truncated client-side by `ValueEncoder`'s 200-row / 50 KB budget.

The v3 closing report states the finding this ticket turns into a design
(`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1124-1133`): that
`StateRequest` is unusable for read-only instruments because `pullServe`
replies mint waves from the producing outlet's counter, and that `snapshot()`
is a whole-state copy, making "a bounded state read (cursor/limit) … the
missing kernel primitive behind both search cost and big-cell state views".
Its "Recommended next increments" item 4 names it directly: *"Bounded state
reads (cursor/limit on `Stateful`): removes the whole-copy caveat on both data
search and large state views."*

Implementation of this primitive is explicitly **not** in this plan's ticketed
scope (`10-design-notes.md` §Verticals, "V1c design note"). Your document
feeds checkpoint **C-replan** (`00-orchestration.md`), where a fresh planning
session decides whether to ticket it.

**Line numbers below were read on `main` at 2026-07-29.** Waves 1–5 of this
run may have moved them, and none of them are in `kernel/` except where noted.
Treat every citation as a pointer to a symbol; verify each one yourself, and
cite what you actually find — a design note that cites a line that has moved
is exactly the kind of drift this repository's doc lints exist to catch.

## Problem

State the problem precisely, with evidence you have personally verified. The
starting points, and the trap in them:

### (a) A pull reply is an emission

- `FanOutlet.baselineTo` (`kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:252-265`)
  builds `MessageContext(Timestamp(sourceId, waveCounter.incrementAndGet()),
  ref, baseline = frontier)` and delivers via `at(replyTo)`. The reply
  therefore **consumes a value from the producing outlet's own wave counter**
  (the I-16 reply-sequencing rule).
- `pullServe` (`kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt:49-56`)
  is the only registration path for a `StateRequest` handler, and it exists so
  the serve block can call `baselineTo` directly.
- `StateRequest` itself
  (`kernel/src/main/kotlin/civictech/cell/protocol/StateRequestProtocol.kt:11-51`)
  already carries two bounding dimensions that matter to you: `since:
  TagFrontier?` (incremental — only tags beyond a frontier) and `scope:
  Interest?` (PN-3c: the sub-state the requester's interest admits, crossing a
  bridge as a polymorphic `Interest`). Its KDoc states the reply contract in
  the same terms: "a single-wave state-as-delta stamped with a fresh wave from
  the producer outlet's own counter".

### (b) The claim you must verify, and probably correct

`CatchUp.kt:25-32` (the PN-2 note) says the deferred change is blocked because
`baselineTo` "consumes the outlet's own wave counter (the I-16 reply-sequencing
rule), which inflates the `waveState().highWater` that
`civictech.cell.replication` reads directly as a source's delivered
high-water". `DataSearch.kt:28-49` repeats that reasoning as the justification
for M5-SEARCH's design.

Read the code before you repeat it. On `main` at 2026-07-29:

- `FanOutlet.waveState()` (`FanOutlet.kt:215-216`) has exactly **one**
  production reader — `Evolution.kt:194-198`'s preserved-epoch transfer
  (`to.adoptWaveState(from.waveState())`). No file under
  `kernel/src/main/kotlin/civictech/cell/replication/` reads it.
- Replication's delivered watermark advances through a **tap**:
  `WatermarkCell.trackDeliveriesOf` (`kernel/src/main/kotlin/civictech/cell/data/Watermark.kt:188-208`),
  wired from `Replication.trackDeliveries`
  (`kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:220-241`).
  That tap reads `CurrentContext.get()?.timestamp`, and its own KDoc says
  broadcast emissions "fire taps" while "targeted `at`-catch-up does not".

So the two documented statements are in tension, and the honest description of
the perturbation has to be re-derived rather than quoted. Work out and state
exactly what a pull reply does and does not disturb — at minimum: which
counter it advances, which observers of that counter exist today (epoch
transfer, wave-completeness/glitch-free arming, `MessageContext.baseline`
exclusion, delivered watermarks, `ReBaseline`/epoch supersession), what a gap
in a source lane's counter sequence means to each of them, and whether the
`waveState().highWater` sentence is accurate, stale, or a description of a
hazard that is real by a different route. Whichever it is, the corrected
statement is a headline finding of this document — and a proposed KDoc
correction is something you **write down in the doc**, never apply.

### (c) The read that *is* wave-neutral is unbounded and local-only

`ManagedHost.snapshotOf` (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1032-1094`)
already gives an instrument almost everything it wants — its KDoc says so
explicitly: it runs `Stateful.snapshot()` on the cell's own execution context,
"nothing is linked, nothing is emitted, no wave counter moves", it is callable
from any thread against any scheduler, it honours cancellation, and it
completes with null rather than exceptionally. What it does not give:

- **boundedness** — `Stateful.snapshot()` (`kernel/src/main/kotlin/civictech/cell/Stateful.kt:11-14`)
  returns the whole state as one `Serializable`; every implementation is a
  full copy (`SetCell`, `MapCell`, `KeyedSetCell`, `ListCell`, `ShardCell`,
  `Watermark`, `InstanceSet`, …). Truncation happens *after* the copy, in the
  inspector's `ValueEncoder`. The cost lands on the cell's own thread.
- **a consistency stamp** — the caller gets a value with no frontier attached;
  the inspector's per-cell frontier comes from the observation path
  (`Observations.kt`'s `StampedView`), not from the snapshot path.
- **remoteness** — a cell whose `host` is null answers `unavailable`; there is
  no wave-neutral read across a bridge.
- **a defined story for non-hot cells** — the inspector invented one for
  itself (`inspect/src/main/kotlin/civictech/inspect/Cold.kt`'s `Heat`:
  suspended, drained-host and migration-held cells are skipped and counted).

Quantify the consequence where you can: `DataSearch`'s bounds
(`MAX_CELLS = 50`, `BUDGET_MS = 2000`, `DataSearch.kt:343-348`) are sized
around whole-state copies, and the closing notice it emits
(`DataSearch.kt:309-339`) is the user-visible confession that the read model
is wrong.

## Solution direction

Write `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md`.

**Header.** The file needs a `**Status**:` line in its header block, before
the first `## ` section. `:concord:docLints` fatally rejects any status line
that does not *begin* with one of
`Specified|Partial|Implemented|Exploratory|Historical|Living`
(`concord/src/main/kotlin/civictech/concord/lint/DocLints.kt`, `allowedStatusWords`).
So write:

```
**Status**: Exploratory — Draft. A proposal for the C-replan checkpoint; no
implementation is ticketed by this plan.
```

Do not invent a new status word, and do not "fix" the lint by editing
`DocLints.kt`.

**Required sections** (your own headings, but every one of these must be
answerable from the document):

1. **Problem, with evidence.** §"Problem" above, written out with verified
   citations, including your corrected account of what a pull reply perturbs
   and your assessment of the `CatchUp.kt` KDoc claim.

2. **Constraint survey.** What any proposal must respect, each with the
   source that imposes it:
   - **P2** — nothing on the per-message fast path.
   - **P6** — observation is causal; a read must raise no attention, create no
     link, and extend no cone. This is what makes "browse everything" hard:
     the whole point is a read that is *not* a subscription.
   - **Ownership** (`doc/spec/20-dataflow-semantics/23-ownership.md`) — a read
     must never consume, copy, delay or leak an `Owned`/`Leased` payload. Say
     what a bounded read returns when the state *contains* exclusive values;
     `Stateful.snapshot()`'s `Serializable` return type already implies an
     answer, and it may be the wrong one.
   - **Per-cell consistency only** (F-5) — every state view carries its own
     frontier stamp and nothing promises cross-cell alignment. A *paged* read
     raises the sharper question this design must answer head-on: what is a
     cursor's consistency guarantee when the cell folds between pages?
   - **Glitch-freedom and wave completeness**
     (`doc/spec/20-dataflow-semantics/22-consistency.md`, §Pull/late-join at
     `:327` onward) — the existing answer is that a catch-up baseline is
     excluded from every wave-completeness set and carries
     `MessageContext.baseline`. Does a wave-neutral read need to be a message
     at all?
   - **Replication watermarks and epochs**
     (`doc/spec/40-distribution/42-replication.md` §Delivered watermarks;
     `Watermark.kt`, `Replication.kt`, `Evolution.kt`).
   - **Membrane disclosure** (`doc/spec/40-distribution/43-security.md`, 93
     I-28) — a catch-up unicast crossing a boundary must pass the disclosure
     filter, "filtered, not forked". A bounded read crossing one inherits that
     obligation; `FanOutlet.at` routes targeted delivery through
     `disclosureFilter` today (`FanOutlet.kt:267-298`) precisely for this.
   - **Execution context and schedulers** — the read runs on the cell's own
     context; on a simulated host it lands on the next `step()`/`runToIdle()`
     (`ManagedHost.kt`'s KDoc, `SimulationController`).
   - **Viz never blocks** — bounded, abandonable, cancellable.

3. **The proposed primitive.** Specific enough to cost:
   - signature(s) and where they live (an addition to `Stateful`? a sibling
     interface? a `ManagedHost` accessor? a protocol message?), including what
     a cell that does not implement it does;
   - execution context and threading, and how cancellation/abandonment works;
   - **bounding**: cursor and limit semantics — what a cursor *is* (an opaque
     token? a key? an index?), whether it is stable across folds, what happens
     when the underlying state changes mid-iteration, and what the caller is
     promised when it does. Relate it to what already exists: `StateRequest`'s
     `since: TagFrontier` (incremental) and `scope: Interest` (partial) are
     two bounding dimensions already in the protocol; say whether your cursor
     is a third, a generalization, or a replacement;
   - **consistency stamp semantics**: what frontier accompanies a page, what a
     caller may conclude from it, and what it may not;
   - **suspended / drained / migrating cells**: answer, refuse, or serve from
     a checkpoint — and what the caller can distinguish;
   - **remote cells**: in scope, sketched, or explicitly deferred with a
     reason;
   - **wave-neutrality, argued**: why your primitive does not move the wave
     plane, in terms of the observers you enumerated in §1.

4. **Alternatives considered and rejected** — at least one, argued, not
   strawmanned. Obvious candidates: a counter-neutral baseline reply lane
   (the change `CatchUp.kt:25-32` itself defers), which would make
   `StateRequest` usable read-only and is the *closest* alternative to your
   proposal; an inspector-side index or materialized mirror; keeping
   `snapshot()` and bounding only at the encoder; reading from
   `.durability`'s journals / a checkpoint reader instead of from the live
   cell. For each: what it would buy, what it costs, and the concrete reason
   it loses.

5. **Kernel surface touched** — an exact list of files and structures, with
   what changes in each, and which are additive versus behaviour-changing.
   Include the ripple: every `Stateful` implementation, the `Observe` view
   family (`kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:60-110`),
   `ShardCell`'s composite snapshot, durability's use of the same seam
   (`doc/spec/30-execution-model/33-mobility.md` §Snapshot, G-25 — drain,
   migration and checkpointing share `Stateful`), and the wire/codec surface
   if a message shape changes. Note explicitly what would break binary/wire
   compatibility, since the repository's default is additive evolution.

6. **Test and conformance obligations.** Which existing tests bound the
   design and would have to keep passing — at minimum
   `kernel/src/test/kotlin/civictech/cell/data/StatePullTest.kt`,
   `LateJoinCatchUpTest.kt`, `InterestScopedCatchUpTest.kt`,
   `kernel/src/test/kotlin/civictech/cell/link/CatchUpTest.kt`,
   `kernel/src/test/kotlin/civictech/cell/port/PullServiceRefusalTest.kt`,
   `PullPolicyCompositionTest.kt`, and `demo/exchange`'s composition exit
   gate. What new tests are owed (including the failure/recovery cases: a
   cursor invalidated mid-read, a cell suspended between pages, an abandoned
   read, an ownership-bearing state). Then the conformance question: **are
   concord scenarios owed, and under which requirement ids?** The corpus is
   single-writer and schema-change-gated — `concord/schema/scenario.md` is the
   authoring contract, `concord/corpus/DISPUTES.md` is the honesty ledger, and
   `doc/spec/CONCORDANCE.md` is generated. Existing anchors to reason from:
   `[21-PULL-01]` and `[21-CATCHUP-02]` in
   `doc/spec/20-dataflow-semantics/21-propagation.md` §Pull, and the scenario
   `concord/corpus/21-propagation/21-PULL-01.yaml`. **Propose** ids, the
   chapter each would be specified in, and scenario shapes. Do not create,
   edit or reserve anything under `concord/**` — including DISPUTES.md.

7. **Recommendation and open questions.** An explicit **go / no-go**, with the
   condition attached ("go, if X"; "no-go until Y") and a rough size class for
   the implementation. Then the questions that belong to research rather than
   to a ticket, framed as entries for `doc/spec/90-roadmap/95-research-plan.md`
   — which is owned by that file, so you write the proposed entries here and
   the replan session decides. State plainly anything you could not determine
   from the code.

**Style.** Match the register of `10-design-notes.md` and the v3 plan: dense,
citation-first, decisions stated as decisions with their reason attached, no
hedging filler, no restating of what the reader can see in the code. Every
non-obvious claim carries a `path:line` citation. Prefer being wrong and
specific over being safe and vague — the C-replan session can correct a
specific claim and can do nothing with a vague one.

## Files expected to touch

- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` —
  **new, and the only file you create or modify** (besides this ticket's own
  `**Status**:` line, which the orchestrator may also maintain).

Any other file appearing in your diff is a defect in this ticket's execution.
That includes: no kernel changes, no inspector changes, no KDoc corrections
(propose them in the document), no edits to `concord/**`, no edits to
`95-research-plan.md`, `93-feature-interactions.md`, `CONCORDANCE.md`,
`00-orchestration.md`, `10-design-notes.md`, or the v3 plan folder.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — whole file;
  §"Binding constraints" is the constraint set your survey must cover.
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1114-1180` —
  "Open items fed back to the roadmap" (MRB-157's three sub-findings) and
  "Recommended next increments" item 4. This is the mandate.
- `doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md:109-135` — "Known
  kernel gaps this plan works around", including the sentence that names the
  missing primitive.
- Kernel: `port/FanOutlet.kt` (`:207-265`, `:267-305`), `link/CatchUp.kt`
  (whole file, 56 lines), `protocol/StateRequestProtocol.kt` (whole file),
  `Stateful.kt`, `host/ManagedHost.kt:1032-1094`, `data/Watermark.kt:170-216`,
  `replication/Replication.kt:200-245`, `evolve/Evolution.kt:180-205`,
  `observe/Observe.kt:40-115`.
- Inspector: `inspect/src/main/kotlin/civictech/inspect/DataSearch.kt` (whole
  file — its KDoc is the fullest existing statement of the problem),
  `Observations.kt` (the `SnapshotSource` seam and `StampedView`),
  `Cold.kt` (`Heat`), `ValueEncoder.kt` (the 200-row / 50 KB budget).
- Spec: `doc/spec/20-dataflow-semantics/21-propagation.md` §Pull (including
  "Catch-up is a baseline, not a wave input", 93 I-24),
  `22-consistency.md` §Pull/late-join (`:327` onward),
  `23-ownership.md`, `24-data-cells.md`,
  `doc/spec/30-execution-model/33-mobility.md` §Snapshot,
  `doc/spec/40-distribution/42-replication.md` §Delivered watermarks,
  `43-security.md` (disclosure across membranes),
  `doc/spec/90-roadmap/93-feature-interactions.md` (I-16, I-24, I-11, I-14 —
  the decisions the reply path is built on).
- Concord: `concord/schema/scenario.md`, `concord/corpus/21-propagation/21-PULL-01.yaml`,
  `concord/corpus/DISPUTES.md` (read the first section for the honesty rule —
  do not add to it).
- `AGENTS.md` §"Core invariants to protect" — the system-wide constraints your
  proposal is judged against.

Do not modify: everything except the one new design document.

## Acceptance criteria

- [ ] `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md`
      exists, with a single `**Status**:` line in its header block beginning
      with an allowed vocabulary word (`Exploratory`), before the first `## `.
- [ ] The problem is stated with verified `path:line` evidence, and the
      document says explicitly whether `CatchUp.kt`'s `waveState().highWater`
      claim is accurate — with the corrected account if it is not.
- [ ] The constraint survey covers P2, P6, ownership, per-cell consistency
      (F-5), glitch-freedom/wave completeness, replication watermarks and
      epochs, membrane disclosure, execution context, and "viz never blocks" —
      each tied to its source.
- [ ] The proposal gives a signature, an execution context, cursor/limit
      semantics including the mid-fold mutation case, consistency-stamp
      semantics, and a stated behaviour for suspended/drained/migrating cells.
- [ ] Its relationship to `StateRequest`'s existing `since` and `scope`
      bounding dimensions is stated, not ignored.
- [ ] At least one alternative is considered and rejected with reasons; the
      counter-neutral baseline lane deferred by `CatchUp.kt:25-32` is either
      the proposal or one of the rejected alternatives.
- [ ] The kernel files and structures touched are enumerated exactly,
      including the ripple through `Stateful` implementations and durability's
      shared use of the seam, and flagging anything that would break wire or
      binary compatibility.
- [ ] Test and conformance obligations are defined, naming the existing tests
      that bound the design, the new failure/recovery cases owed, and whether
      concord scenarios are owed — with proposed requirement ids and the
      chapter that would specify them.
- [ ] The document ends with an explicit go/no-go plus open questions framed
      as proposed `95-research-plan.md` entries.
- [ ] The diff contains exactly one new file and no other change of any kind.

## Verify

There is no build gate on a document beyond the doc lints. Run:

```bash
./gradlew :concord:docLints
```

**Read the output, not just the exit code.** On `main` at 2026-07-29 this task
already fails with three pre-existing status-vocabulary findings inside this
plan folder (`00-orchestration.md` and `10-design-notes.md` say `Planned`;
tickets say `not-started` — none of which is in the allowed vocabulary). Your
bar is that **your new file contributes no finding of its own**: confirm it
does not appear in the "Status-header vocabulary" list, and that no
`Unresolved package pointer` / `Unresolved type pointer` finding names it —
the package-pointer lint resolves every backticked `cell.<pkg>.<Type>`
reference against `kernel/src/main/kotlin/civictech/cell/<pkg>/`, so a mistyped
kernel path in your prose is a build failure, not a typo. Report the
pre-existing findings to the orchestrator rather than fixing them.

If the Gradle wrapper cannot run in your worktree, fall back to confirming the
header by inspection and say so in the report.

## Report on completion

- The corrected statement of what a pull-serve/`StateRequest` reply actually
  perturbs, and your verdict on `CatchUp.kt:25-32`'s `waveState().highWater`
  sentence — accurate, stale, or right-for-the-wrong-reason. Include the
  proposed KDoc wording you did **not** apply.
- Your go/no-go, in one paragraph, with the condition attached.
- The concord answer: scenarios owed or not, proposed ids, and why — plus
  confirmation that nothing under `concord/**` was touched.
- Which of your citations had moved since this ticket was written (waves 1–5
  landed in between), so the orchestrator knows the ticket's own line numbers
  are stale.
- `:concord:docLints` output: the pre-existing findings, and confirmation that
  your file added none.
- Anything you could not determine from the code, stated as an open question
  rather than guessed.
- Files actually touched (expected: exactly one).
