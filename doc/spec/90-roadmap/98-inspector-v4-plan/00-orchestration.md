# Inspector v4 — orchestration plan

**Status**: Living — runbook for a `claude-sonnet-5` orchestrator session;
wave tables are updated as the run progresses.
Tickets live in `tickets/`. Read a ticket only when dispatching it. Design
context: `10-design-notes.md`. Owner: Merlijn.

## Goal

Deliver the inspector v4 verticals in priority order **data → activity →
errors**, each vertical shipping backend feed + UI slice together, plus the
doorstep fixes and the canvas/testing FE track. Done means: waves 1–6 merged
to `main` with green gates, the V1c design note written, and the replan
checkpoint has decided what (if anything) of V1c-impl/V4/V5 to ticket next.

## Sandbox / isolation

No Docker (the repo's Docker harness is retired — see `AGENTS.md`). Every
ticket runs in its own **git worktree** on branch `ticket/<id>`, created under
`/Users/merlijn/Documents/local-projects/computenet-worktrees/`. Workers act
freely inside their worktree; the branch is the unit of review. Workers do
not commit/merge/rebase outside their own branch; the evaluator owns git for
its ticket (merge authority delegated by this plan, as in the v3 run).

- Setup per worktree: none beyond `git worktree add` — Gradle wrapper and
  `npm ci` in `inspect/ui/` as needed.
- Test ports: concurrent sessions squat common ports. Any live server a
  worker/evaluator starts must use an ephemeral or explicitly chosen
  non-default port (never assume 7071/8080 is free).
- Workers never edit `concord/**`, `doc/spec/**` outside this plan's folder,
  or `../97-inspector-plan/20-api-contract.md` (contract edits are
  orchestrator-only, from ticket reports).

## Standing rules

- Ticket status is authoritative in the ticket file; mirror it in the wave
  tables below. `:concord:docLints` restricts the Status vocabulary, so ticket
  Status lines use: `**Status**: Specified — not-started` →
  `**Status**: Specified — in-progress` → `**Status**: Implemented — merged`.
- New FE fixture files under `inspect/ui/fixtures/` require a paired decoder
  entry in `inspect/src` (`FixtureContractTest` asserts directory ↔ decoder-map
  equality). A fixture for a new feed lands with its BE ticket, or the FE
  ticket uses inline test samples instead.
- Merge target is `main`. Merge each ticket as soon as it passes evaluation.
- Before a wave's merges land, run the repo gate: `./gradlew test`, plus
  `npm test` in `inspect/ui/` when the wave touched the FE.
- Commit by explicit pathspec only; never `git add -A`; never amend (other
  sessions may share state).
- Unpredicted file collision between concurrent tickets: serialize — let the
  first merge, rebase the second, record the miss in the ticket report.
- Implementation tickets run at `effort: xhigh`; evaluators at `high`.

## Failure policy

1. Verification fails → evaluator fixes small gaps itself and re-checks.
2. Fails twice, or the implementer stalls → re-run at the ticket's escalation
   tier in a fresh session.
3. Fails at the escalation tier → stop. Re-split the ticket or hand it back.
   No third retry.

**Orchestrator escalation.** Process problems — an untangleable merge,
contradictory tickets, a wave that no longer matches the repo — spawn a
`claude-opus-5` session, hand it the conflicting tickets + diffs + this plan,
take its decision, continue. Do not improvise a design decision you were not
given; do not stall waiting for a human.

**Contract changes.** When a ticket report flags a contract addition, the
orchestrator folds it into `../97-inspector-plan/20-api-contract.md` (with a
`**Status**:` header intact) before the next wave that depends on it.

## Wave 1 — V0 doorstep · branches from `main`

Parallel: V0-BE ∥ V0-FE — file claims disjoint (`inspect/src` vs `inspect/ui`).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V0-BE | Wire SnapshotSource→snapshotOf; serve built UI from InspectorServer | sonnet | fresh | ticket/v0-be | sonnet | merged |
| V0-FE | showNet route bug; legend; dead-code removal; README refresh | sonnet | fresh | ticket/v0-fe | sonnet | merged |

**Checkpoint C1 — verification.** Fresh evaluator per ticket; judge against
the ticket only; merge on pass; run the repo gate.

## Wave 2 — V1a live data · branches from `main` after C1

Parallel: V1A-BE ∥ V1A-FE (FE codes against the contract addition + fixture).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1A-BE | Coalesced state.summary (flow.rates pattern); summary drives value freshness | opus | fresh | ticket/v1a-be | opus | merged |
| V1A-FE | Live ValueView: refetch-on-summary, row-flash, onChange log panel | sonnet | fresh | ticket/v1a-fe | opus | merged |

**Checkpoint C2 — verification.** As C1; the evaluator additionally exercises
the live-update path end to end (real server, mutation, observed re-render).

## Wave 3 — V1b pins + demo · branches from `main` after C2

Parallel: V1B-FE ∥ V1-DEMO (ui files vs scripts/docs — disjoint).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1B-FE | Pinned multi-cell observations; cost surfacing | sonnet | fork V1A-FE eval-fixed context or fresh+handoff | ticket/v1b-fe | opus | merged |
| V1-DEMO | Two-JVM shopping convergence demo runbook + script | haiku | fresh | ticket/v1-demo | sonnet | merged |

**Checkpoint C3 — verification.** As C1; evaluator runs the demo runbook
verbatim once.

## Wave 4 — V2 activity · branches from `main` after C3

Sequenced: V2-KERNEL → V2-BE (BE consumes the kernel seams). V2-FE runs in
parallel with both (ui only, fixture-driven).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V2-KERNEL | Lifecycle listener, attention accessor, hook deregistration, remoteRefs() | opus | fresh | ticket/v2-kernel | opus | merged |
| V2-BE | Consume seams (drop polls); activity feed (ring + GET + SSE); attention in CellDetail | opus | fork V2-KERNEL | ticket/v2-be | opus | merged |
| V2-FE | Activity feed panel; attention display; suspended emphasis | sonnet | fresh | ticket/v2-fe | opus | merged |

**Checkpoint C4 — verification.** As C1, plus a kernel-invariant audit of the
V2-KERNEL diff (P2/P6, read-only, transport-neutral) before anything merges.

## Wave 5 — V3 errors + wave health · branches from `main` after C4

Parallel: V3-BE ∥ V3-FE.

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V3-BE | Wave-health heuristic rows; supervision-timeline capture; richer dead-letter detail | opus | fresh | ticket/v3-be | opus | merged |
| V3-FE | Wave-health UI; supervision timeline; dead-letter detail cards | sonnet | fresh | ticket/v3-fe | opus | merged |

**Checkpoint C5 — verification.** As C1; evaluator drives a stalled-wave
scenario live and confirms a heuristic row appears and clears.

## Wave 6 — FE track + V1c design · branches from `main` after C5

Sequential: FE-CANVAS → FE-TOOLTIPS → FE-TESTS (all touch `Canvas.tsx` or its
harness). V1C-DESIGN is doc-only and runs in parallel with any of them (it may
start as early as after C3 if the orchestrator has idle capacity).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| FE-CANVAS | Zoom/pan/fit-to-screen on the canvas | sonnet | fresh | ticket/fe-canvas | sonnet | merged |
| FE-TOOLTIPS | Rich positioned tooltips replacing native title | sonnet | fork FE-CANVAS | ticket/fe-tooltips | sonnet | merged |
| FE-TESTS | Component/DOM smoke tests for the rendering layer | sonnet | fresh | ticket/fe-tests | sonnet | merged |
| V1C-DESIGN | Design note: wave-neutral bounded state read (MRB-157) | opus | fresh | ticket/v1c-design | opus | merged |

**Checkpoint C6 — verification.** As C1. For V1C-DESIGN the evaluator judges
the document against its ticket's acceptance criteria (no code to merge
beyond the doc).

**Checkpoint C-replan.** Trigger: wave 6 merged and V1C-DESIGN accepted.
Fresh `claude-opus-5` session re-enters the `create-implementation-plan`
skill. Inspect: the merged waves, every ticket report's flagged
contract/kernel questions, the V1C-DESIGN document, and
`../97-inspector-plan/90-progress-log.md`'s open items. Output: concrete
tickets (appended here) for whichever of V1c-implementation, V4 distribution
(PeerId→registry, descriptors over the wire, replicated pilot), and V5
cold/checkpoint reader are now justified — or a decision to stop.

## Wave 7+ — the bounded state read, stable peer identity, a replicated pilot

**C-replan ran 2026-07-29** against `main` at `7125455` (all six waves merged,
V1C-DESIGN accepted). It re-entered the `create-implementation-plan` skill and
read: the merged waves, `20-wave-neutral-read-design.md` in full,
`../97-inspector-plan/90-progress-log.md`'s open items and recommended
increments, `../97-inspector-plan/20-api-contract.md` (including V3-BE's one
OPEN question), V3-BE's G-40 kernel-gap flag as it survives in
`inspect/src/main/kotlin/civictech/inspect/WaveHealth.kt:8-119`, and the current
state of the durability and peering subsystems.

The three items it was asked to decide split into six verdicts. Each stop is
argued in "Not ticketed, and why" below; none is a deferral for want of time.

| Item | Verdict |
|---|---|
| **V1c — local bounded read** | **Go, behind a measurement gate.** Seven tickets, waves 7–11. |
| **V1c — remote arm** | **No-go.** Not ticketed. Disclosure question unanswered. |
| **V4 — PeerId → registry** | **Go.** One ticket, wave 9 (`V4-PEERID`). |
| **V4 — descriptors over the wire** | **No-go.** Not ticketed. Wire break for a cosmetic benefit, and a disclosure decision in disguise. |
| **V4 — replicated pilot** | **Go, as an evidence ticket.** Wave 10 (`V4-PILOT`). |
| **V5 — cold/checkpoint reader** | **Stop.** Not ticketed. Its cheap half is already inside `V1C-KERNEL`; its real half is blocked on a durability decision that is not an inspector concern. |

Two checkpoint-level actions were taken directly rather than ticketed, and are
already on `main`: the four-site correction of the stale
`waveState().highWater` claim (commit `1677953`), and the resolution of
`GraphList.health`'s wave-health roll-up question to **no** (commit `74c7bc6`).
Both are described at the end of this section.

### Standing rules, addenda for waves 7+

Everything under "Standing rules" above still applies. Additionally:

- **Wave 8 is the only kernel-interface wave.** `V1C-KERNEL` is the sole owner
  of `kernel/.../host/ManagedHost.kt` and `kernel/.../data/SetCell.kt` for the
  whole of waves 7–11. No other ticket may touch either file.
- **Wave 9's three tickets have disjoint kernel claims**, and that disjointness
  is the only reason they run in parallel: `V1C-CELLS` owns
  `cell/data/{MapCell,KeyedSetCell,ListCell,Watermark}.kt`,
  `cell/replication/InstanceSet.kt`, `cell/partition/ShardCell.kt`; `V1C-OPS`
  owns `cell/data/op/**`; `V4-PEERID` owns `cell/host/LocationRegistry.kt`,
  `cell/wire/Peering.kt`, `wire/.../WsTransport.kt`, `inspect/.../Peers.kt` and
  the two demo launchers. Test directories split the same way and it matters:
  `V1C-CELLS` owns `kernel/src/test/.../cell/data/` (where the `ShardCell`
  tests already live, there being no `cell/partition/` test package) and
  `V1C-OPS` owns `cell/data/op/` — a subdirectory, so still disjoint at file
  level, but each ticket is told to create nothing in the other's.
- **Binding constraint 7 ("no edits under `concord/`",
  `10-design-notes.md:140`) stays in force for every ticket except
  `V1C-CONCORD`**, for which this checkpoint lifts it. That lift is not a
  loosening: `concord/schema/scenario.md:6-9` requires schema growth to be *"a
  deliberate schema-change ticket between waves, not a corpus-authoring
  convenience"*, and `V1C-CONCORD` is exactly that ticket, scheduled in its own
  wave after everything it describes has shipped.
- **`doc/spec/90-roadmap/95-research-plan.md` is not edited by any ticket or
  checkpoint in this plan.** The four research questions the design note
  proposed are named at the end of this section; placing them in that file is
  the spec owner's call, not a checkpoint's.
- **`../97-inspector-plan/20-api-contract.md` remains orchestrator-only.**
  `V1C-BE` and `V4-PEERID` both propose wording in their reports; neither edits
  it.

## Wave 7 — V1c measurement gate · branches from `main` after C-replan

Runs alone. `V1C-BENCH` is doc-producing, in the register `V1C-DESIGN`
established: its deliverable is
`30-bounded-read-measurement.md`, and it checks in no benchmark test (this
repository has no JMH, no benchmark source set and no slow-test gating
convention; adding that for a one-shot measurement is a permanent tax).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1C-BENCH | Measure the whole-copy cost; GO / RESIZE / NO-GO on the V1c chain | sonnet | fresh | ticket/v1c-bench | opus | merged |

**Checkpoint C7 — the gate, not a verification.** The evaluator judges the
document against the ticket *and acts on its recommendation*:

- **GO** → waves 8–11 proceed as written.
- **RESIZE** → the orchestrator narrows `V1C-CELLS`' and `V1C-OPS`' cell lists
  to the families the measurement supports, records the narrowing in each
  ticket file before dispatch, and proceeds.
- **NO-GO** → `V1C-KERNEL`, `V1C-CELLS`, `V1C-OPS`, `V1C-BE`, `V1C-FE` and
  `V1C-CONCORD` are all cancelled (Status → `Historical — cancelled at C7`,
  with the measurement cited). Wave 9 collapses to `V4-PEERID` alone, wave 10
  to `V4-PILOT` alone, wave 11 disappears, and the plan runs straight to
  C-replan-2 after wave 10. This is a real branch of the plan, not a
  formality: `20-wave-neutral-read-design.md` §4.3 argues the status quo is a
  defensible answer, and the measurement is what decides between them.

**C7 resolved GO on 2026-07-30**, on the evidence in
`30-bounded-read-measurement.md`. **Waves 8–11 proceed as written, unresized** —
neither `V1C-CELLS`' nor `V1C-OPS`' cell list is narrowed. The load-bearing
result is that document's §6, the E2-versus-E3 comparison the branch turns on:
the live-traffic stall a concurrent read imposes on a cell's own service falls
from 8.6–10.5 ms to ~1.2 ms at 10⁴ elements (~85–90%) and from 27.7–29.2 ms to
~0.14 ms at 10⁵ (~99%), for a total-work premium of only 1.7–2.4×. That is the
trade §3.2 predicted and could not quantify, and it is real and large at the
sizes the design targets. The evaluator independently reproduced the
foundational E1 magnitude (10⁵ `SetCell.snapshot()`: median 7.6 ms, p95 21.1 ms
against the document's 5.8 / 23.3 ms) and verified every load-bearing code
citation, including the priority-0 queue-jump mechanism (`ManagedHost.kt:1249`
submitting above data's priority 20) that explains *why* the dip occurs.

Three findings carried forward rather than resolved here:

- **The 10³ result is inconclusive**, and the document says so. Paging neither
  clearly helps nor hurts a cell that small. This is not a reason to narrow the
  cell lists, because `BoundedStateful` is opt-in (§3.1): a family that never
  grows past a few thousand elements simply does not implement it and pays
  nothing.
- **`V1C-KERNEL`'s cursor must resume in O(page), not O(n).** E3's counterfactual
  used a `List<Int>` stand-in with an O(1) seek; a cursor that rescans the tag
  map from the start on each page would turn the measured 1.7–2.4× premium into
  O(n²) and invalidate the trade this checkpoint accepted. §3.4's key-ordered
  cursor admits an O(page) resume — the implementation must actually take it.
- **`MAX_CELLS = 50` / `BUDGET_MS = 2_000` are left unchanged**, as the ticket
  required. §9 finds the implied 40 ms/cell budget lines up with the *tail* of a
  single 10⁵ copy, which is defensible but undocumented, and names the untested
  case: several large cells on one host, where E2 shows copies fully serialize.
  A candidate for C-replan-2, not for this gate.

## Wave 8 — the kernel primitive · branches from `main` after C7

Runs alone — it is the only ticket permitted to touch `kernel/**` in this wave,
and every wave-9 and wave-10 ticket is written against its interface.

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1C-KERNEL | `BoundedStateful` + `ManagedHost.readState` + provenance arms + `SetCell` reference impl | opus | fresh | ticket/v1c-kernel | opus | merged |

**Checkpoint C8 — verification plus a kernel-invariant audit**, in the shape C4
used for `V2-KERNEL`. Before anything merges, audit the diff for: P2 (no
per-message call site), P6 (no link, no tap, no attention raised), read-only-ness,
transport-neutrality, `Stateful` unchanged, `StateRequest` unchanged, no wire or
codec change. Then run `./gradlew :demo:exchange:test` as the neutrality gate.

**The evaluator has one further job, and it is not optional.** `V1C-CELLS`,
`V1C-OPS` and `V1C-BE` are written against this ticket's *sketch* of the
interface. If the shipped signatures differ, the evaluator edits those three
ticket files to match the shipped shape before wave 9 is dispatched, and says so
in the merge message. A worker discovering the drift for itself is a wave-9
failure caused here.

**C8 resolved PASS on 2026-07-30**, merging `V1C-KERNEL` at `4f633d2` after one
in-place repair on the branch. The shipped shape *did* differ from the sketch,
so all three downstream tickets were edited, plus a path typo in `V4-PEERID`:

- `StatePage` gained `attributes: Map<String, Serializable>` (cell-level state
  that rides every page) and `caveats: Set<ReadCaveat>`
  (`STALE_FRONTIER`/`POSITIONAL_CURSOR`). This closes `V1C-CELLS`' open question
  about where `ShardCell`'s `interest`/`assignedEpoch` and `KeyedSetCell`'s
  `tagCounter` live, and **contradicts** `V1C-OPS`' flat assertion that no such
  channel exists — its Decision D riders move there.
- `BoundedStateful` gained `supportsSince`/`supportsScope`, defaulting to
  `false`, with `readState` refusing an undeclared bound **on the caller's
  thread** (`SINCE_UNSUPPORTED`/`SCOPE_UNSUPPORTED`). This is the mechanism
  `V1C-CELLS` was told to flag as an interface-shape finding if it did not
  exist; it exists, and it covers `scope` as well as `since`.
- `Reason` has nine arms, not four; `Unbounded` gained a `provenance` so the
  drained arm can answer `CHECKPOINT`.
- **The one that would have bitten wave 10 silently:** `SetCell` stamps
  `frontier` exactly on the *first and last* page of a walk only, declaring
  `STALE_FRONTIER` in between, because an exact per-page frontier costs an O(n)
  rescan per page — the O(n²) shape C7 ruled out. `V1C-BE`'s `walkStable` was
  written to compare every page against page 1, which would report `true`
  through a walk whose fold had already moved. Corrected in that ticket: the
  verdict is `null` until the walk closes, and complete once it does.

One repair the evaluator made rather than flagged: `StatePage`'s across-page
contract claimed, unqualified, that equal endpoint frontiers imply the union is
exactly a snapshot. A `TagFrontier` measures tag *gains*, and an OR-set
observed-remove mints no tag — so a remove-only mid-walk mutation is invisible
to the check. Both KDocs now say the check is necessary but not sufficient for
such a family, and a kernel test pins it. `V1C-CONCORD` must not write a
`[21-PULL-03]`-style stability scenario over a removal.

## Wave 9 — cell coverage and stable peer identity · branches from `main` after C8

Parallel: V1C-CELLS ∥ V1C-OPS ∥ V4-PEERID — kernel claims disjoint (see the
addenda above).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1C-CELLS | `BoundedStateful` on the six map/set-backed data cells (incl. `ShardCell`'s composite) | opus | fork V1C-KERNEL | ticket/v1c-cells | opus | **Implemented — merged** (`b8ec8a3`) |
| V1C-OPS | `BoundedStateful` on the composite operator cells; cross-sub-state cursor ordering | opus | fresh + handoff | ticket/v1c-ops | opus | **Implemented — merged** (`00789c4`) |
| V4-PEERID | `PeerId` reaches the registry; peer labels survive a reconnect | opus | fresh | ticket/v4-peerid | opus | **Implemented — merged** (`6d5cf98`) |

`V1C-CELLS` forks `V1C-KERNEL` because it copies the `SetCell` pattern almost
literally. `V1C-OPS` does not: its work is exploratory — deciding a total order
across two or three sub-states per operator — and an exploratory ticket needs
its own reading rather than an inherited one.

**Checkpoint C9 — verification.** As C1, plus: `./gradlew :demo:exchange:test`
after the two cell tickets merge; and for `V4-PEERID` a live two-JVM
disconnect/reconnect run through `scripts/demo-shopping-two-inspectors.sh`,
confirming each side's `Node.net` is the other's `--net-name` and is *stable*
across the reconnect.

Two things the C9 evaluator should expect rather than treat as scope creep.
`wire/.../WsTransport.kt` is very likely in `V4-PEERID`'s diff: a listener-side
`Session` spawns its registry mirror in its own constructor, before any peer
name has arrived, so the peer must be late-bound; the ticket predicts the edit
and owes a happens-before argument for it. Conversely, a diff that touches
`ManagedHost`'s `PORT_API` branch gets the same P2 audit C8 applies, or is
rejected — that is the per-message path and the ticket forbids it.

**C9 closed.** All three tickets merged (`b8ec8a3`, `6d5cf98`, `00789c4`).
`./gradlew :demo:exchange:test --rerun-tasks` green with both cell tickets on
`main` (the combined check this checkpoint owes), plus repo-wide `./gradlew
test` and `:concord:check`/`:concord:docLints`; `V4-PEERID`'s live two-JVM
reconnect run was confirmed by its own evaluator.

Two residuals the C9 evaluators recorded, both for a later wave, neither
blocking:

1. **Two intra-key orderings now coexist.** `V1C-CELLS` imposed
   `civictech.cell.data.EntryOrder`, a *value-derived* total order that
   survives a `snapshot()`/`restore()` round trip. `V1C-OPS` was told by its
   own Decision C to adopt `V1C-KERNEL`'s mechanism verbatim, and did:
   `cell/data/op/**` freezes the backing map's *encounter* order. Both are
   correct within a walk — a frozen sequence lives inside the cursor, so no
   walk is affected by a restore — but two instances of an operator cell
   holding identical content enumerate differently, which `EntryOrder` exists
   to prevent. Unifying is a one-line change per `freeze` in
   `OperatorPaging.kt` and a rewrite of the order-asserting tests; it belongs
   to whoever needs page-order comparability across instances (scatter-gather,
   replica diffing), not to `V1C-BE`.
2. **`TagFrontier` is not monotone for the operator family.**
   `BoundedRead.kt`'s `StatePage`/`ReadCaveat.STALE_FRONTIER` KDoc reasons from
   "a `TagFrontier` is monotone". `V1C-OPS` established that no cell in
   `cell/data/op/**` is such a family — every `TagState` there is
   non-retaining and both `JoinLedger` implementations' `exit` *removes* the
   advertised tag — so a mid-walk removal mints nothing and can *lower* the
   stamp. Each affected `readBounded` says so and a kernel test pins it, but
   the kernel-level KDoc still generalizes. `V1C-CONCORD` must not write a
   stability scenario that assumes monotonicity, and a later ticket owning
   `BoundedRead.kt` should soften that sentence.

## Wave 10 — the consumers and the pilot · branches from `main` after C9

Parallel: V1C-BE ∥ V1C-FE ∥ V4-PILOT — `inspect/src` vs `inspect/ui` vs
`demo/`, disjoint. V1C-FE codes against the contract-binding JSON in
`V1C-BE`'s ticket text, the pattern waves 2 and 5 already used.

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1C-BE | Paged state endpoint; `DataSearch` rewired; suspended and drained cells become readable | opus | fresh | ticket/v1c-be | opus | not-started |
| V1C-FE | Paged big-cell state view; browse-everything state chips; honest cold preview | sonnet | fresh | ticket/v1c-fe | opus | not-started |
| V4-PILOT | First same-logical-id replicated pilot over a real socket, two inspectors, findings | opus | fresh | ticket/v4-pilot | opus | **Implemented — merged** (`9dd03a8`) |

`V4-PILOT` extends `demo/shopping` behind a bare `--replicate` flag defaulted
off, rather than adding a demo module: every prerequisite the pilot needs —
real `--listen`/`--peer` peering, the `:inspect` dependency with
`--inspect-port`/`--net-name`, graph naming, cross-JVM `declareLink`, a
multi-JVM test lane and a two-inspector script — exists only there, and a new
module would spend the ticket's risk budget rebuilding scaffolding instead of
driving replication across a socket. Its `Main.kt` claim overlaps `V4-PEERID`'s,
which is why the two are in consecutive waves rather than the same one.

**Checkpoint C10 — verification.** As C1, plus the evaluator drives a real
inspector against a cell of ~10⁵ entries and walks it page by page end to end,
confirming the graph keeps serving traffic throughout — the claim the whole V1c
chain rests on. For `V4-PILOT` the evaluator **reads the findings and does not
act on them**: defects it reveals are sized at C-replan-2, not patched inside a
One seam between `V1C-BE` and `V1C-FE` needs an explicit ruling at C10 rather
than a per-ticket answer: `SearchCost.coldSkipped` narrows its meaning to
held-for-migration only *after* `V1C-BE` merges, and a browser cannot tell which
server build it is talking to. The FE ticket requires wording that is true under
both; confirm it is, against both a pre- and post-merge server.

One `V4-PILOT` finding needs care rather than a yes/no: `ComponentIndex`
groups by graph-id *string* while its sweep can yield two genuinely
disconnected components, and `idOf` uses the *logical* uuid — so two replicas
can share a graph id with no edge between them, which would read as a "yes" on
MRB-156 for the wrong reason. The report must say which case holds.

**`V4-PILOT` merged at C10** (`9dd03a8`); the other two wave-10 tickets are
judged separately and C10 is not closed by this entry. The findings live in
`doc/demo-shopping-replica-pilot.md` §"What we observed"; all eight are answered
from observation and none of the four defects is patched, which is what this
checkpoint required. The evaluator reproduced the pilot end to end — a fresh
`TwoJvmReplicaPilotTest` run (two real JVMs, a real socket, an inspector each,
both tests green), a live `scripts/demo-shopping-replica-pilot.sh` run driven
through both UIs with bidirectional `action=share` convergence, and an
independent mode-off/mode-on control pair.

**Which case holds — both, in one payload.** Re-deriving `sweep()`'s exact
algorithm over a freshly captured 30-node/21-edge `GET /topology` gives **11
flood-fill components collapsing to 10 `components()` map entries**:

- *Case (a), a real yes.* The data replica pair sits in **one** 20-member
  component containing both `4f421498-…:0` and `4f421498-…:1`. The connector is
  **not** the demo's `declareLink` — dropping the declared gossip edge from the
  swept edge set leaves the same 20-member component. What joins the sides is
  the peer's own `manage.link(shared.outlet → itemsUnion.inlet)`, a real
  `ManagedHost.connect`, indexed and mirrored. The declared edge documents the
  mesh; it does not form it.
- *Case (b), a yes for the wrong reason.* The **watermark companion pair** is
  two disconnected singletons — zero edges incident to either — merged into
  `g-98ebe0fa-…` solely because `idOf` (`Graphs.kt:180`) keys on the logical
  uuid. `GET /graphs` bills it as `{"cells":2,"hosts":1,"nets":2}`: a two-cell
  graph spanning two nets, for two cells connected by nothing.

Both sides return the same ten graph ids in the same order; the only field that
differs is `hosts`, correctly, because a mirrored node has `host: null`.

Four defects recorded, none patched, each sized for C-replan-2:
**D1** `components()` merges disconnected same-logical-id replicas into one card
(`Graphs.kt:124-132` + `:180`; `sweep()` itself is correct — the merge happens
after it). **D2** the error lane's `parked` rows never fire for a partitioned
replica mesh (`parked:0` at every capture while writes demonstrably parked);
undiagnosed by design — it needs an `inspect/src/**` owner. **D3** the *named*
graph's id changed on every peer connect and disconnect, and twice the deciding
minimum uuid belonged to a randomly minted cell in the **other** JVM, so a
deep-linked graph id does not survive a peer restart. **D4** (cosmetic, FE) the
breadcrumb showed the previously-opened graph's name.

**C-replan-2 input — the min-uuid heuristic's specific failure mode under
replication.** `idOf` collapses vertex identity from `CellRef` down to
`CellRef.id` at exactly the moment two instances of that id coexist. That
collapse was deliberate and is documented (`Graphs.kt:34-36`) for the
*replacement* case — a minimum member replaced by a later instance of itself
must not flip the id — but coexistence was never considered, and under peering
every replica coexists with its mirror. A correct answer to graph identity
across a peer boundary needs three properties the heuristic cannot have: an id
that is **not derived from its own membership** (D3 is the direct consequence of
one that is); a **declared boundary identity** that survives merge, split and
peer churn — this is what "membranes as naming boundary" would have to provide,
and the pilot is evidence for it rather than against; and an explicit way to
express **instance multiplicity**, so "one logical cell, two places" is a thing
the payload states rather than a thing a client infers.

Two contract observations flagged for `../97-inspector-plan/20-api-contract.md`,
orchestrator-owned and deliberately not edited: `Node.ref`'s
`"<uuid>:<instanceId>"` encoding is the *only* thing that makes two instances
decidable from the payload, and the contract neither tells a client to parse it
nor offers a grouping field; and `GraphSummary.id`'s documented instability does
not say the id can be **peer-owned**, which D3 shows it can.

Two residuals for whoever owns the pilot next, neither blocking: replicate mode
adds **six** nodes per side, not the four the ticket predicted (the extra pair is
the demo's own `ObserveCell`, re-measured at C10 as 24/16 → 30/21 nodes/edges);
and `Replication.watermarkRef` being `internal` to `:kernel` forces the demo to
recompute the derivation, which the ticket mandated and the diff cites — a
mislabelled node rather than a compile error if the two ever diverge.

## Wave 11 — conformance · branches from `main` after C10

Runs alone. Skipped entirely if C7 returned NO-GO.

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V1C-CONCORD | Spec requirement text → concord schema change → three scenarios | opus | fresh | ticket/v1c-concord | opus | not-started |

**Checkpoint C11 — verification.** `./gradlew :concord:check` green with zero
dangling `covers:` ids and zero orphan scenarios; `doc/spec/CONCORDANCE.md`
regenerated and not hand-edited; `:concord:docLints` clean. A requirement that
could not be checked honestly must appear in `concord/corpus/DISPUTES.md` — a
scenario weakened until it passes is a rejection, not a deviation.

**Checkpoint C-replan-2.** Trigger: wave 11 merged (or wave 10, under a C7
NO-GO). Fresh `claude-opus-5` session, `create-implementation-plan` again.
Inspect: `V4-PILOT`'s findings, `V1C-BENCH`'s measurement against what the
chain actually delivered, `V1C-CONCORD`'s report on whether wave-neutrality is
expressible implementation-neutrally, and whether any of the three no-go
decisions below has had its blocker removed.

## Not ticketed, and why

Three deliberate stops. Each is a decision, not a deferral for want of time.

**1. V1c's remote arm — no-go, unchanged from the design note's own verdict.**
A bounded read is wave-neutral *because* it is not an emission, and this
repository's only disclosure seam is an emission seam
(`kernel/.../port/FanOutlet.kt:105-117`, `:293`; 93 I-28 "filtered, not forked",
`doc/spec/20-dataflow-semantics/21-propagation.md:72-76`). A read crossing a
membrane or a bridge therefore has no filter to pass through. Shipping one would
be a disclosure regression wearing a feature's clothes. The blocker is the first
research question below; until it is answered, a `host: null` cell keeps
answering `unavailable` and the plan says so out loud rather than half-building
the capability.

**2. Descriptors over the wire — no-go, for two independent reasons.** First,
mechanics: an announce carrying a cell's type FQN means changing
`RegistryAnnounce.published`'s signature, and `methodId` is
`StableHash.of("$fqn#$name$descriptor")`
(`gen/.../wire/ContractProcessor.kt:385`), so the id repoints and
`WireCodec.decode`'s `checkNotNull(ContractRegistry.method(...))` throws on the
old frame (`kernel/.../wire/WireCodec.kt:278-280`); adding a *new* method
instead leaves old receivers unable to resolve it, and no capability
negotiation exists for announces (only
`BridgeIngressCell.protocolCapabilities`, for `PORT_PROTOCOL`,
`kernel/.../wire/BridgeCells.kt:71-72`). `AGENTS.md` requires wire compatibility
to be preserved unless a cited spec demands otherwise, and none does. Second,
and more decisively: *what one peer may learn about another's cells is a
disclosure decision*, not plumbing — the same seam that blocks (1). The benefit
is real but small, and the v3 closing report itself files "remote endpoints show
raw port uuids" under **Cosmetics**
(`../97-inspector-plan/90-progress-log.md:1169-1172`). Note what makes this
tractable *later*: the minimum payload is one string, because
`PortRef.of(cell, name)` is name-derived and `ContractRegistry.cellDescriptor`
can rebuild color, manifests and port names locally from an FQN. The work is
small; the decision in front of it is not.

**3. V5 cold/checkpoint reader — stop, and the reason is structural.**

- *Its cheap half is already ticketed.* "Show a drained host's state" needs no
  journal work at all: `ManagedHost.beginDrain` already retains
  `snapshots[cellRef] = cell.snapshot()` (`ManagedHost.kt:499-502`, written at
  `:501`) for exactly the window in which the inspector reports the component
  cold. `V1C-KERNEL`'s Decision 7 answers such a read with
  `provenance = CHECKPOINT`, and `V1C-BE`'s part 4 surfaces it. That is the v3
  cold screen's missing preview, delivered inside the V1c chain.
- *Its real half is blocked on something that is not an inspector concern.*
  **Nothing in production calls `ManagedHost.checkpoint(journal)`** — the only
  callers anywhere are `concord`'s durability driver and five kernel tests. So a
  real journal directory written by any demo contains only frame and frontier
  records, never a checkpoint, and reading state out of it means re-running the
  fold, which means starting a host — the exact thing "inspect the not-running"
  exists to avoid. Introducing a checkpoint cadence is a durability-subsystem
  decision touching `HostDurability.checkpoint`'s PN-0b guard
  (`HostDurability.kt:224-229`); it belongs to the durability roadmap and to the
  spec owner, not to an inspector plan.
- *And even with checkpoints, the canvas cannot be drawn from a directory.*
  `doc/spec/30-execution-model/31-hosts.md:91-92` is normative: the journal
  "does not journal topology at all — the graph must be rebuilt out-of-band
  before `recoverFrom`". Frame records additionally need the app's
  `ContractRegistry` and ServiceLoader-discovered `WireSerializers` to decode
  (`kernel/.../wire/WireCodec.kt:194-198`, `:278-280`), so an "out-of-process"
  reader is really "in the app's JVM without a running host". The only tractable
  scope is cold *state* for a graph whose *structure* a running inspector still
  knows — which is the cheap half, already ticketed.

Consequence for the UI: `inspect/ui/src/nav/cold.ts:7-13`'s
`COLD_NOTICE = 'cold — parked; state/flow unavailable without waking'` becomes
partly wrong once `V1C-BE` lands the drained-checkpoint arm. `V1C-FE` owns that
string; it must say what is now available and stay honest about what is not.

## Two actions taken at the checkpoint rather than ticketed

**The stale `waveState().highWater` claim, corrected in all four sites**
(commit `1677953`). `20-wave-neutral-read-design.md` §1.2 established that the
mechanism this repository records for "why a read-only instrument cannot use
`StateRequest`" does not exist — nothing under `civictech.cell.replication`
reads `FanOutlet.waveState()`, and a targeted `at` delivery fires no tap and
moves no watermark row. The design note called this the zero-cost item and
recommended it before either arm of the work, because the discarded sentence was
being cited as a design constraint by new work. It was a KDoc-and-prose edit
with no behaviour change, so the checkpoint took it directly rather than
spending a ticket. Corrected: `kernel/.../link/CatchUp.kt` (the origin),
`inspect/.../DataSearch.kt` (which quoted it verbatim),
`../97-inspector-plan/90-progress-log.md` (the MRB-157 finding and the closing
note's echo) and `../97-inspector-plan/10-target-v3.md` (the "Known kernel
gaps" restatement — a fourth site the design note had not found). Every
conclusion survives; only the reason changed, from wave perturbation to
topology (P6).

**`GraphList.health` does not roll up wave-health rows** (commit `74c7bc6`).
V3-BE raised this deliberately unanswered and handed it here. `health`'s three
existing fields are properties of the component; a wave-health row is a property
of *(a tapped edge, a cell some client chose to observe)*. Rolling them up would
make a server-wide snapshot field a function of one client's attention, with no
place to say whose, and would render "unexamined" as "healthy" whenever a
component has no observed cells. A client that wants the number can compute it
exactly from `ErrorSnapshot.waveHealth` plus `Node.graph` — and computing it
client-side keeps the caveat attached to it. This was the last OPEN question in
`../97-inspector-plan/20-api-contract.md`.

## Research questions named, not placed

`20-wave-neutral-read-design.md` §7 proposes four entries for
`doc/spec/90-roadmap/95-research-plan.md`. That file is owner-maintained and
this checkpoint does not edit it; the questions are recorded here so the
no-go decisions above are traceable to a blocker rather than to a shrug.

1. **Disclosure for non-emitting reads.** 93 I-28's seam 3 filters *emissions*.
   A wave-neutral read has no emission and therefore no filter — already true of
   shipped `snapshotOf`, tolerable only because `InspectorServer` binds
   loopback-only. Does "filtered, not forked" have a read-side twin? **Blocks
   V1c's remote arm and, in substance, descriptors over the wire.**
2. **Ownership in `Stateful.snapshot()`.** `23-ownership.md` has no rule for a
   fold whose state contains `Owned`/`Leased` values, yet drain, migration and
   checkpointing all serialize that state; G-46 (`23-ownership.md:220`) covers
   only the crash-loss half. `V1C-KERNEL` gives the bounded read a *stricter*
   contract and explicitly declines to inherit the older seam's undefined one.
3. **Cursor semantics across a scatter-gather boundary.** A partitioned pull is
   per-shard-consistent and cross-shard-arbitrary
   (`doc/spec/40-distribution/42-replication.md:401`) and the answering instance
   may change between pages. One token or a vector of per-instance tokens?
4. **The `Effectful` processed frontier and baselines.** `WaveFrontier` and
   `absorbAck` exempt baselines; `ManagedHost`'s `PORT_API` branch does not.
   Should a baseline-stamped arrival advance a durable suppression frontier at
   all? This is the residual blocker for PN-2's push/pull catch-up unification
   now that the `waveState()` claim is corrected.

A fifth, from V3-BE's G-40 flag rather than from the design note, and the one
this checkpoint judges highest-leverage: **making absorption observable.**
`inspect/.../WaveHealth.kt:83-92` states plainly that absorption is "the single
largest source of honest lag and the reason this class is heuristic", and that
"the only defence available without kernel watermarks is a conservative
`LAG_THRESHOLD_WAVES` plus `LAG_GRACE_MS`". Per-source per-edge delivered
watermarks — whose max-contiguous-prefix algebra already exists in
`DeliveredFrontier` for replica tags but is applied to neither waves nor edges —
and `Progress` absorb-acks made observable to an Observe-role attachment would
between them collapse the largest false-positive class and turn three of G-40's
four cases from guesswork into reported fact. That is `.verify`/kernel work
(`22-consistency.md:198-207`), outside every inspector plan's scope, and it is
the thing to reach for when one is opened.
