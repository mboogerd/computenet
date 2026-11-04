# Studio v2 technical spike — 2026-10-18

**A: FITS WITH CHANGES.** Journaled topology and checkpoints can replace graph/state storage; `Effectful` cannot replace application run/equality dedupe or guarantee exactly-once Claude execution.
**B: FITS WITH CHANGES.** Python preview and promotion work for a pure computation candidate; the proposed NoOp-served `Effectful` Python runner produces no preview and remains suppressed after promotion.

## Evidence and citation key

Paths are repository-relative. `K/` = `kernel/src/main/kotlin/civictech/cell/`; `S/` = `demo/studio/src/main/kotlin/civictech/demo/studio/`; `P/` = `doc/spec/`. Test files are in `demo/studio/src/test/kotlin/civictech/demo/studio/spike/`.
The aliases below name the exact JUnit methods, with definition lines for navigation.

| Test | File:line — method |
|---|---|
| D1 | `DurabilitySpikeTest.kt:128` — `rawManagementDoesNotJournalTopologyEvenWithApplyContext` |
| D2 | `DurabilitySpikeTest.kt:144` — `runtimeGraphDeltasRecoverSpawnConnectUnlinkAndDespawnWithPinnedRefs` |
| D3 | `DurabilitySpikeTest.kt:170` — `completedEffectIsSuppressedButItsUncheckpointedStateIsNotRebuilt` |
| D4 | `DurabilitySpikeTest.kt:191` — `externalActBeforeFailedHandlerFrontierCanRunAgain` |
| D5 | `DurabilitySpikeTest.kt:200` — `asynchronousWorkReturningBeforeCompletionIsAlreadyFrontiered` |
| D6 | `DurabilitySpikeTest.kt:233` — `stateCheckpointStoresAllStudioFieldsAsJsonWithoutWirePayloadRegistration` |
| D7 | `DurabilitySpikeTest.kt:245` — `javaSerializableStudioMessagesStillNeedWireSerializersAndFactoriesCannotCaptureServices` |
| D8 | `DurabilitySpikeTest.kt:262` — `checkpointRelinkPlainCatchupIsContextlessAndRefusedAtEffectfulInlet` |
| D9 | `DurabilitySpikeTest.kt:273` — `checkpointRelinkWithFreshBaselineRefiresAnAlreadyCompletedEffect` |
| E1 | `EvolutionSpikeTest.kt:87` — `purePythonShadowHasSeparateObservableOutputAndPromotionReplaysBufferedInput` |
| E2 | `EvolutionSpikeTest.kt:110` — `effectfulPythonIsNoOpInShadowAndPromotionDoesNotRestoreItsHandler` |
| E3 | `EvolutionSpikeTest.kt:125` — `unclassifiedSubprocessReallyExecutesAndHasNoKernelSandbox` |
| E4 | `EvolutionSpikeTest.kt:137` — `mismatchedOutletContractAbortsBeforeTouchingIncumbent` |
| E5 | `EvolutionSpikeTest.kt:152` — `promotionDoesNotWaitForAWorkerDetachedFromTheIncumbentHandler` |

Durability tests reopen actual `FileJournal` files with fresh hosts/cells, pinned refs, and recovery fences. D5 copies the fsynced file while a real executor worker is blocked, recovers that crash image,
then separately permits completion through the original cell's own hosted inlet and recovers its journal.
These are scheduler-controlled lifecycle/crash-image probes, not OS power-loss tests. E1–E4 execute real `python3` script files.

## A — topology, effects, and state

1. **Runtime edits are supported through graph operations, not intercepted raw management.**
   `ApplyContext` registers a topology fold; it does not wrap every host mutation (`K/graph/ApplyContext.kt:88`, `:184`). D1 proves raw `spawn(cell)`, `spawnBound`, `connect`,
   `Link.unlink`, and `despawn` leave no topology records even with the context installed. D2 applies small `GraphSpec` deltas while data flows, including later connects/unlinks/despawns;
   recovery and checkpoint-compacted recovery preserve the surviving refs, link and data. Write-ahead recording is in `K/graph/GraphDsl.kt:577`; `graph(context)` builders also record
   individual operations (`:1044`, `:1165`, `:1271`, `:1288`, `:1343`; builder form code-inspected only).
   Use one cumulative context and its handles; recover via `context.recover(journal).awaitApplied()`. Alignment's precedent is `demo/alignment/src/main/kotlin/civictech/demo/alignment/AlignmentApp.kt:133`.

2. **Recovery needs reconstructible, Java-serializable factories carrying config, not live services.**
   `CellFactory : Serializable` receives the pinned ref (`K/graph/GraphDsl.kt:38`); `TopoEvent.Spawn` stores it (`K/graph/Topology.kt:15`). D2 rebuilds from these factories;
   D7 rejects a factory capturing a non-serializable dependency. `spawnBound` alone is insufficient (D1). The context's local apply actually calls `spawn(cell)` after creating from the recorded factory
   (`K/graph/ApplyContext.kt:239`). Studio's callback-capturing, random-ref construction needs redesign
   (`S/StudioRuntime.kt:65`, `S/StudioCell.kt:25`). External services must be reattached at startup.

3. **`Effectful` is a cell marker, with a per-inlet delivery frontier, not an async-job protocol.**
   Declaration: `K/evolve/Evolution.kt:33`; spec: `P/20-dataflow-semantics/24-data-cells.md:1331`.
   The frontier is per `(cellRef, portName, sourceId)` counter, recorded by `FrontierRecord`
   (`K/host/HostDurability.kt:149`, `:1186`). After a successful hosted handler returns, the host
   advances/journals it (`K/host/ManagedHost.kt:2243`, `:2265`). D3 proves completed invocations
   are skipped on recovery, but a fresh timestamp with identical content runs again: no value dedupe.
   Direct contextless calls are refused (D3; `K/host/ManagedHost.kt:2184`); stamp external actions
   with stable actor identity/counters through `ActorIngress` (`K/host/HostedCellProxy.kt:152`).

4. **Finished-before-crash is conditional, not an unconditional kernel guarantee.**
   A durable frontier suppresses its position; a crash after the external act but before its advance
   may repeat it (`P/20-dataflow-semantics/24-data-cells.md:1478`, `[24-DUR-09]`). D4 models that
   missing-frontier boundary by acting then throwing before handler completion; recovery acts twice.
   D5 proves returning after offloading marks the trigger processed while the worker is still blocked:
   recovery skips it and does not resume the unfinished job. A completion inlet on the same `Effectful`
   cell is also skipped on replay, losing uncheckpointed result state. D3 proves this even synchronously.
   Keep deterministic pending/completed/result state separately journaled; an effect must finish inside
   its handler for the frontier to track that completion, or the app must own job recovery/idempotency.
   Moving arbitrary process I/O into the single host handler also changes host responsiveness. Current studio workers actually mutate external `Entry.state` under its lock and emit back through
   the cell inlet (`S/StudioRuntime.kt:294`, `:348`, `:190`); there is no completion/tick inlet today.

5. **Checkpoints can hold all listed node state.** `Stateful.snapshot(): java.io.Serializable`
   and `restore` are the seam (`K/Stateful.kt:15`); snapshots use Java object serialization
   (`K/host/HostDurability.kt:965`, `:1062`, `:1132`). D6 round-trips actual `NodeState` containing
   output, held inputs, git ref, text, generated code and both hashes, encoded as a JSON String.
   No wire registration is needed for that snapshot string. Invocation arguments, however, use
   `WireCodec` (`K/host/HostDurability.kt:787`): D7 proves today's Java-serializable `StudioMsg`
   fails that encoding, while String works. Add kotlinx serializers + `WireSerializers` registration,
   or use an explicit String JSON envelope. Snapshot data must include every recoverable field; callbacks, timers, subprocesses and futures are not checkpoint state. Pure state updates must
   re-enter journaled, non-effectful handlers; snapshots alone do not save changes after the last checkpoint.

6. **Keep equality/run dedupe.** Checkpoint recovery re-connects folded links
   (`K/graph/ApplyContext.kt:212`). D8's studio-shaped plain targeted catch-up has no context and
   is refused at an `Effectful` target (`K/link/CatchUp.kt:64`, `K/port/FanOutlet.kt:510`).
   D9's correctly stamped fresh baseline instead re-fires the old value: exact-position suppression
   cannot recognize identical content under a new position. This reproduces the fresh-baseline concern
   tracked in `P/50-development-process/52-verification.md:227` (checkpoint catch-up, `computenet-n2jwi`).
   Baseline discharge retention is also capped at 1024 positions (`P/20-dataflow-semantics/24-data-cells.md:1427`;
   code/spec inspection only, cap eviction not spike-tested).

7. **Plain files are a storage choice, not a kernel requirement for UI metadata.**
   The topology fold stores cell/link structure, not canvas x/y/title or studio's many-canvas-edges-to-one-link
   mapping (`K/graph/Topology.kt:15`, `S/StudioRuntime.kt:155`). Keep a small UI metadata file initially,
   or journal a dedicated metadata cell; the latter is a design inference, not spike-tested.
   Config must be in reconstructible factory data and/or journaled state, not solely in that UI file. Keep stdout/stderr/run history as bounded artifacts with journaled references; compaction is not an
   archival log (`S/StudioRuntime.kt:343`, `K/host/HostDurability.kt:938`). Materialize `cell.py` from stored code/hash before execution (`S/StudioRuntime.kt:321`). These storage choices are code-based recommendations.

## B — Python shadows and promotion

1. API: `Shadow.spawn(host: ManagedHost, cell: Cell): CellRef` (`K/evolve/Evolution.kt:94`).
   It spawns only; the app wires live input fan-out and candidate output observation (E1). `Promotion.promote(host, gate: TrafficLightApi<T>, incumbent, candidate, outletName,
   downstream: List<Use<*>>, judge: PromotionJudge? = null, journal: PromotionJournal? = null)`
   (`K/evolve/Evolution.kt:236`). E1 succeeds without `InvariantCell` evidence or a judge;
   E4 proves an incompatible selected outlet contract aborts without retiring the incumbent.
   The spec requires port-name/contract sameness across rebindable ports
   (`P/50-development-process/53-evolution.md:169`, `:243`); the low-level implementation checks
   the selected outlet's contract class plus downstream policies (`K/evolve/Evolution.kt:262`).
   Callers must supply the compatible, already input-linked candidate and complete downstream set.
   A supplied judge must Accept; policy window is observed waves, not seconds
   (`K/evolve/PromotionPolicy.kt:13`, `:149`; optional-judge refusal code-inspected only).

2. The red gate buffers inbound traffic; COMMIT imports state, adopts the outlet wave lane,
   rebinds downstream, removes incumbent input subscription, greens/replays, then retires
   (`K/evolve/Evolution.kt:284`). E1 proves a buffered input reaches the new Python implementation
   and subsequent inputs stop running the old one. E5 proves detached workers are not drained by this
   window: the incumbent can be retired while its executor task remains blocked. App must fence jobs.

3. **NoOp means the whole served handler does not execute.** E2 launches zero candidate processes,
   emits no preview, and remains silent after promotion: `promote` does not undo `Shadow.suppress`.
   Spec: `P/50-development-process/52-verification.md:134`; code: `K/evolve/Evolution.kt:96`, `:123`.
   Per-contract `@Contract(effect=true)` suppression is finer, but opaque subprocess I/O inside an
   `Effectful` cell still takes the coarse cut (`P/50-development-process/53-evolution.md:25`).
   There is no “allow safe process” option. E1 runs real Python through an ordinary non-effectful
   computation inlet; E3 proves this classification supplies no sandbox by writing a temporary marker.
   Therefore only trusted/pure or externally isolated Python qualifies. Stdlib-only is not isolation.

4. Observe candidate's ordinary outlet with a separate collector linked using
   `LinkOptions(role = Observe, staged = true)` (E1). Production stays on the incumbent outlet until
   promotion; preview sees candidate output before and after it. The app can expose that collector via SSE.

## Concrete implementation order and remaining limits

1. A: introduce ref-aware serializable factories and one persistent `ApplyContext`; route every edit
   through live GraphSpec deltas. Keep UI metadata and run artifacts. Stop sources before teardown;
   do not journal normal shutdown as deletion of every graph node (`S/StudioRuntime.kt:448`).
2. A: move authoritative node/run state into journaled cells; use JSON snapshots initially. Journal
   deterministic completion results separately from effect execution; retain equal-input dedupe and stable
   run IDs. Recover/fence before enabling git polling, workers, or HTTP writes. Remove `state.json` last.
3. B: split code generation/paid agents from pure Python computation and world-effect boundaries.
   Establish safe preview execution before permitting generated, unapproved code to run. Add the gate,
   compatible candidate, live-input fan-out and preview collector; approve the reviewed hash and promote only after all detached jobs settle. Transfer held inputs without replacing candidate code.
   Explicitly publish/recompute the selected value on approval: E1 proves buffered/new inputs, not
   immediate publication of an existing preview with no new input (unproven integration detail).
4. A+B integration: use graph-owned promotion bookkeeping/journaling, not naked `Promotion.promote`.
   `K/graph/ApplyContext.kt:310`, `:620` require shared data/topology journal and a `StateMigrating`
   candidate over a `Stateful` incumbent. **Not spike-proven:** journaled Python promotion recovery,
   safe subprocess isolation, metadata-cell design, post-retire rollback, and a durable job ledger.
   Cross-build journal migration is unsupported (`K/durability/Journal.kt:101`); version/schema upgrades
   need an explicit application decision. No production code or other module was modified.

Validation: `./gradlew :demo:studio:test --rerun` passed: 44 tests, 14 spike tests, zero failures/skips.
`32 actionable tasks: 3 executed, 29 up-to-date`; `:demo:studio:test` executed. A stale daemon
was sandbox-denied; the passing command used `JAVA_OPTS=-Dorg.gradle.daemon=false` and
`ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy=in-process`. No repository-wide gate was run (spike scope).


## Outcome — part A durability

Implemented one shared `studio.journal`/`ApplyContext`, GraphSpec edits, data-only
factories and cell-owned JSON state/config checkpoints. Journaled inbox acknowledgements
close the delivery-to-intake crash window. The latest-job ledger records started
before launch and completed with result/output; unfinished jobs recover durably as
interrupted without automatic retry. Plain files retain canvas metadata/mappings and
run artifacts; `state.json` is gone. Checkpoint cadence is 100 committed state/structural
changes plus clean shutdown, preserving live nodes; empty graphs retain their WAL
because the kernel refuses empty checkpoints. Cold kernel precheck prevents refused
links entering the write-ahead topology log. Fresh baseline catch-up still re-fires
(kernel gap `computenet-n2jwi`); Studio's held-value dedupe absorbs it.

The superseded spike probes are replaced by `StudioDurabilityTest`; eight remaining
kernel boundaries, including the part B design evidence, live in `KernelAssumptionsTest`
with a comment per test. Part B is untouched; its graph-owned promotion can use the
same context/journal. V1 data migration, cross-build journal migration and OS power-loss
behaviour are not proven by these restart/crash-image tests.

Validation: `:demo:studio:test --rerun` executed 47 tests (30 existing, nine durability,
eight retained kernel assumptions), zero failures/errors/skips; 23 actionable tasks:
six executed, 17 up-to-date. `:demo:studio:installDist` passed; 28 actionable tasks:
two executed, 26 up-to-date. Both used the spike's `JAVA_OPTS=-Dorg.gradle.daemon=false`
and in-process Kotlin compiler workaround. No repository-wide test gate was run.

## Outcome (part B)

Implemented the decided macOS sandbox profile for every live/preview Python run,
fresh canonical WORK/TMPDIR, and fail-closed missing-binary handling. Exec/custom
commands and read-only agents retain their existing execution modes. Graph-owned
shadows are non-Effectful computations with live gate fan-out and an Observe-role
collector; preview never connects to production downstream. Regeneration fences
jobs/process trees and despawns the prior shadow. Approval checks the candidate
hash, fences both instances, and calls the shared `ApplyContext.promote`; T1
migration preserves incumbent continuity while selecting the candidate code and
approval hash. Explicit recomputation closes E1's immediate-publication gap.
The UI compares preview/live output, displays sandbox failures and preview-ready
badges, and confirms failed-preview promotion. README documents the additive field.

This matches B's pure-shadow path and the A+B journal requirement. Kernel friction
remains E5 (detached work is outside the promotion drain), E1 (no automatic
publication of the reviewed result), and retired graph handles (canvas ids need
separate tracking). No kernel changes were needed. Preview artifacts are separate
from live run history. The repository demo-bypass ratchet also caught part A's
raw intake notification subscription; it now uses an admitted Observe-role link
to a rebuilt service collector. Unlike v1, generation retains the previous approved code;
promotion explicitly recomputes once even for manual nodes. Recovery discards
unpromoted shadows and recreates them lazily on graph/SSE reads, new input or
approval; it never reruns a recovered live job.

Proven with real Python/sandbox-exec: isolation, held/new-input previews,
downstream exclusion, promotion delivery/equality dedupe, wrong-hash refusal,
worker termination, lazy preview recovery, exact promoted-state recovery after
compaction, and a fsynced journal image taken immediately after kernel COMMIT
before Studio's follow-up state commit/publication. That image selects the new
code and approvedHash without running it. Swap and output publication are separate
transactions: a crash in between leaves the incumbent's output until explicit Run
or a new automatic input. Not proven: every kernel failure/instruction boundary,
OS power loss, post-retire rollback or cross-build journal migration.

Validation: `:demo:studio:test --rerun` executed 59 tests (including 11 preview/
promotion integration tests), zero failures/errors/skips; `:demo:studio:installDist`
passed in the same gate (25 actionable tasks: eight executed, 17 up-to-date).
UI typecheck, 16 Vitest tests and production build passed. The demo-bypass ratchet's
two tests also executed and passed after the observation-link correction. Gradle
used the spike's no-daemon/in-process compiler workaround; the real sandbox tests
ran with outer execution permission so macOS could apply sandbox-exec itself.
The repository-wide `test` run caught the corrected notification ratchet and also
hit an out-of-scope five-minute timeout in Beads-mirror's
`ReadySecondSeedDifferentialTest` (seed 21). The broader run was stopped after that
failure; it is not a green repository-wide gate. No Beads-mirror files were changed.


## Process-runner follow-up

The latest-job ledger now also contains the running artifact and verified process
identities. A plain-file placeholder exists before worker submission and records
argv before launch, then PID/start instant/command and sampled descendants after
launch. Startup reconciles both the ledger and running artifacts, terminates only
matching identities, and persists interrupted records before any new work starts.
Completion is fenced before artifact writes as well as state publication. This
extends the part A interrupted-job evidence to run history and real child cleanup;
it does not establish exactly-once external effects or OS crash containment.
README's process-runner section documents the launch-to-record gap, descendant
sampling/reparenting limits, parent-first timeout termination and prompt limits.

Validation: six focused regression tests first failed against the original runner
(including the timeout canary and missing running artifact), then passed after the
fixes. The final `:demo:studio:test --rerun :demo:studio:installDist` gate executed
128 tests, including nine new regression checks, with zero failures/errors/skips.
Both requested tasks executed; 25 actionable tasks: eight executed, 17 up-to-date.
The no-daemon/in-process compiler workaround was used; outer execution permission
allowed real ProcessHandle tree inspection and macOS sandbox tests. Agent tests
use real Python stdin/argv probes in place of paid CLIs; installed CLI help was
checked for stdin support. UI sources were unchanged.
