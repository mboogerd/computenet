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
| V2-KERNEL | Lifecycle listener, attention accessor, hook deregistration, remoteRefs() | opus | fresh | ticket/v2-kernel | opus | not-started |
| V2-BE | Consume seams (drop polls); activity feed (ring + GET + SSE); attention in CellDetail | opus | fork V2-KERNEL | ticket/v2-be | opus | not-started |
| V2-FE | Activity feed panel; attention display; suspended emphasis | sonnet | fresh | ticket/v2-fe | opus | not-started |

**Checkpoint C4 — verification.** As C1, plus a kernel-invariant audit of the
V2-KERNEL diff (P2/P6, read-only, transport-neutral) before anything merges.

## Wave 5 — V3 errors + wave health · branches from `main` after C4

Parallel: V3-BE ∥ V3-FE.

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| V3-BE | Wave-health heuristic rows; supervision-timeline capture; richer dead-letter detail | opus | fresh | ticket/v3-be | opus | not-started |
| V3-FE | Wave-health UI; supervision timeline; dead-letter detail cards | sonnet | fresh | ticket/v3-fe | opus | not-started |

**Checkpoint C5 — verification.** As C1; evaluator drives a stalled-wave
scenario live and confirms a heuristic row appears and clears.

## Wave 6 — FE track + V1c design · branches from `main` after C5

Sequential: FE-CANVAS → FE-TOOLTIPS → FE-TESTS (all touch `Canvas.tsx` or its
harness). V1C-DESIGN is doc-only and runs in parallel with any of them (it may
start as early as after C3 if the orchestrator has idle capacity).

| Ticket | Nature | Model | Session | Branch | Evaluator | Status |
|---|---|---|---|---|---|---|
| FE-CANVAS | Zoom/pan/fit-to-screen on the canvas | sonnet | fresh | ticket/fe-canvas | sonnet | not-started |
| FE-TOOLTIPS | Rich positioned tooltips replacing native title | sonnet | fork FE-CANVAS | ticket/fe-tooltips | sonnet | not-started |
| FE-TESTS | Component/DOM smoke tests for the rendering layer | sonnet | fresh | ticket/fe-tests | sonnet | not-started |
| V1C-DESIGN | Design note: wave-neutral bounded state read (MRB-157) | opus | fresh | ticket/v1c-design | opus | not-started |

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

## Wave 7+ — intent only, not yet ticketed

V1c implementation (kernel primitive + graph-wide state chips + search
rebuild), V4 distribution truth, V5 inspect-the-not-running. Shapes sketched
in `10-design-notes.md` §Verticals; research gates MRB-156/MRB-157 apply.
