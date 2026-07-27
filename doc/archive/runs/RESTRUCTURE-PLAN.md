# Restructure plan — manifesting the latent architecture

Analysis pinned at commit `4cfbb83` (2026-07-26). File/line references are as of that
commit; **always locate by type/function name, never trust line numbers.**

Execution model: **strictly sequential**, one step at a time, each step ends with a
green build and a commit. Steps are sized for a Sonnet-class agent. Each step carries
an agent directive:

- **FRESH** — start a new agent with empty context. The step lists what to read first
  ("Onboard"). Every fresh agent must also read this plan's *Ground rules* and
  *Target structure* sections, plus `AGENTS.md`.
- **CONTINUE** — keep the previous step's agent; its in-context knowledge of the files
  just touched is the main asset. If the session was lost, treat as FRESH and onboard
  from the previous steps' commits (`git log --oneline`, `git show --stat`).

## Ground rules (every step, every agent)

1. **No behavior change.** This is a structural refactor: moves, extractions,
   file splits, import updates. Public runtime semantics, wire formats, and
   `@SerialName` strings are untouchable. If a step seems to require a semantic
   choice, stop and report instead of improvising.
2. **The KSP string-table trap.** `gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt`
   hardcodes ~28 `civictech.cell.*` FQNs as string literals (companion, near the
   bottom of the file), and `gen`'s tests re-declare kernel stubs
   (e.g. `NatureDescriptorSweepTest`). **After moving or renaming ANY kernel type,
   grep `gen/` and `gen-test/` for the old FQN and update.** A miss does not fail the
   build — descriptors silently stop being generated. After any such update run
   `./gradlew :gen:test :gen-test:test :kernel:test`.
3. **Verify before deleting.** Any deletion step: re-run the call-site grep yourself
   first. If you find a production consumer the plan says shouldn't exist, stop and
   report.
4. **Kotlin move mechanics.** Moving a type = move the file (or extract to a new
   file), update its `package` line, then update all imports repo-wide
   (`grep -rl 'import <old FQN>' kernel wire gen gen-test demo`), including
   fully-qualified inline references (`grep -rn '<old package>.<Type>'`).
5. **Verification cadence.** Each step runs the tests named in the step. Each
   **session end** runs the full gate: `./gradlew test`. Never proceed past a red step.
6. **Commit per step**, message `restructure(RS-x.y): <summary>`. No generated/build
   output in the diff.
7. **No opportunistic edits.** Don't fix nearby smells, don't reformat, don't rename
   beyond the step's scope. If you see something broken, note it in the final report.
8. Deliberate simplifications or known-ceiling shortcuts get a `// ponytail:` or
   `// TODO(restructure):` marker as specified by the step.

## Target structure (the north star)

Kernel packages after the run (mechanism packages keep their jobs; vocabulary types
are sorted into four leveled homes — node natures / edge semantics / placement /
operations):

```
civictech.cell            pure vocabulary: Cell, CellRef, MessageContext, Ownership,
                          Stateful, Propagate, MergeablePayload, serializers, Ambient
civictech.cell.nature     what a cell IS: manifestOf, NatureNegotiation
civictech.cell.port       port ADT + Fan{In,Out}let + InletPolicy (mechanism only)
civictech.cell.link       edge semantics: Link ADT, handshake, LinkSupport, identity,
                          LinkPolicy, Interest, Scoped
civictech.cell.protocol   the generic protocol bus (Protocols, StateRequestProtocol)
civictech.cell.proxy      JDK dynamic-proxy toolkit + Invocation types only
civictech.cell.data       source cells
civictech.cell.data.delta delta types + TagState/MintedTags/DeliveredFrontier
civictech.cell.data.op    operators, on three base classes
civictech.cell.data.view  MapView/SetView/CountView/HubCells/MapDiffPublisher
civictech.cell.partition  PartitionedShardSet, ShardCell, routing runtime
civictech.cell.control    operations plane: suspension, progress, attention,
                          Magnitude, AbsorbAck, ParkQueue, AttentionScheduler
civictech.cell.host       registry + lifecycle; scheduler/durability/intake/deadletter
                          as named collaborator classes; CellError; remoting proxies
civictech.cell.observe    app-facing observation API (former host/Observe.kt)
civictech.cell.{consistency,replication,wire,graph,membrane,evolve,verify,durability}
                          unchanged homes, fewer stray residents
```

Modules: new `:nature` (runtime nature vocabulary, shared by `:gen` and `:kernel`),
new `:testkit`, new `:demo:shell`.

**Explicitly out of scope** (design work, owner decisions — do NOT attempt):
`OutletPolicy`, `GraphChange` (mutation-as-data), `PeerSession`/`RoutedDeltaLinker`,
Frontier/Epoch unification, journal-vs-wire-version decoupling, adding/removing
`absorbAck` in any operator, `<Cell>Ports` call-site migration, spec edits.

---

## Session RS-1 — Dead code removal

### RS-1.1 — Delete the dead point-to-point port hierarchy — FRESH
**Onboard:** this plan; `kernel/src/main/kotlin/civictech/cell/port/` file list.
**Do:** Verify with grep that `Inlet`, `Outlet` (the non-Fan classes in
`port/Inlet.kt`, `port/Outlet.kt`) have zero `src/main` references outside their own
files and the `ManagedHost` dispatch arms. If confirmed: delete both files, their
tests (`InletTest.kt`, `OutletTest.kt`, related `LinkTest` cases), and the
now-dead `is Outlet<*>` / `is Inlet<*>` arms in `ManagedHost`. Check
`FeedbackInlet`/`CycleHead` (`port/Cycle.kt`) separately — reports disagree on
whether production code references it; if `src/main` references exist **keep it**
and only note the finding.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-1.1): delete dead Inlet/Outlet point-to-point hierarchy`

### RS-1.2 — Collapse the InvocationHandler zoo — CONTINUE
**Do:** In `cell/proxy/`: `NoOp.kt`, `Throwing.kt`, `Callback.kt`, `Buffering.kt`,
`Broadcast.kt` are five one-class files; `Proxy.kt` additionally re-implements NoOp
and Broadcast as methods (`Proxy.noop`, `Proxy.broadcasting`). Merge the five
handlers into one file `Handlers.kt`; make `Proxy.noop`/`Proxy.broadcasting`
delegate to the handler classes (or vice versa — pick the direction with fewer call
sites to touch; both forms are live). Also extract the byte-identical
`InvocationTargetException` unwrap (8 occurrences across proxy/port) into one
internal helper in `Proxy.kt` and call it from all 8 sites.
**Verify:** `./gradlew :kernel:test`, then session gate `./gradlew test`
**Commit:** `restructure(RS-1.2): consolidate InvocationHandlers and ITE unwrap`

---

## Session RS-2 — Vocabulary re-homing (breaks most package cycles)

Pure moves + import updates. Highest-churn, lowest-judgment session.

### RS-2.1 — Serializers out of `cell.wire` — FRESH
**Onboard:** this plan; `kernel/.../cell/wire/Serializers.kt`.
**Do:** Move `UuidSerializer` and `IndexedValueSerializer` from
`cell/wire/Serializers.kt` to package `civictech.cell` (file
`kernel/.../civictech/cell/Serializers.kt`). Update all imports (`PortRef`,
`CellRef`, `MessageContext`, `TopologyIndex`, `PnCounterCell`, `Watermark`,
`ListCell`, plus an inline FQN in `attention/Attention.kt`). Ground rule 2 applies.
**Verify:** `./gradlew :kernel:test :wire:test`
**Commit:** `restructure(RS-2.1): move generic serializers from cell.wire to cell`

### RS-2.2 — `Propagate` to the root — CONTINUE
**Do:** Move `Propagate` from `cell/data/Propagate.kt` to package `civictech.cell`.
~39 importing files across kernel, wire, demos. Ground rule 2 applies (this one IS
in the KSP string table).
**Verify:** `./gradlew :gen:test :gen-test:test :kernel:test`
**Commit:** `restructure(RS-2.2): move Propagate to civictech.cell`

### RS-2.3 — `MergeablePayload` to the root — CONTINUE
**Do:** Move `MergeablePayload` from `cell/host/` to package `civictech.cell`
(consumers: `SetCell`, `Watermark`, `ManagedHost` coalescing). Ground rule 2 applies.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-2.3): move MergeablePayload to civictech.cell`

### RS-2.4 — `CellError` down to `host` — CONTINUE
**Do:** Move `CellError.kt` (both `CellError` and `ErrorReporting`) from
`civictech.cell` to `civictech.cell.host`. This removes the root package's imports
of `FanOutlet` and `HostedPortInvocation` (two of its five cycles). Update imports
in `verify/`, demos, tests.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-2.4): move CellError/ErrorReporting to cell.host`

### RS-2.5 — `manifestOf` up to a new `cell.nature` — CONTINUE
**Do:** Create package `civictech.cell.nature`; move `CellManifest.kt` into it.
It legitimately imports `GlitchFree` (consistency), `Partitioned`/`Replicable`
(data) — from `cell.nature` those are downward edges. Update the callers
(`GraphDsl`, others per grep). Ground rule 2: `ContractProcessor` has its own
`manifestOf` twin — do NOT touch the generator logic, but check whether it names
the runtime type's FQN anywhere.
**Verify:** `./gradlew :gen:test :gen-test:test :kernel:test`
**Commit:** `restructure(RS-2.5): move manifestOf to new cell.nature package`

### RS-2.6 — Remoting types out of `proxy` into `host` — CONTINUE
**Do:** Move `RoutedInlet.kt`, `HostedCellProxy.kt`, `HostProxy.kt` from
`cell/proxy/` to `cell/host/` (they are extensions/clients of `ManagedHost` and
`LocationRegistry`; this kills the `proxy → host` inversion). `Invocation.kt`,
`HostedPortInvocation.kt`, `InvocationSink.kt`, `Proxy.kt`, `Handlers.kt`,
`ParkQueue.kt` stay in `proxy` for now (`ParkQueue` moves in RS-7).
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-2.6): move remoting types from cell.proxy to cell.host`

### RS-2.7 — Observation API out of `host` — CONTINUE
**Do:** Create package `civictech.cell.observe`; move `host/Observe.kt` into it
(split into `Observe.kt` + `View.kt` if trivial, else keep one file). Demos import
`civictech.cell.host.View` etc. — update all demo/test imports.
**Verify:** `./gradlew :kernel:test :demo:agora:test`, session gate `./gradlew test`
**Commit:** `restructure(RS-2.7): move observation API to cell.observe`

---

## Session RS-3 — The `:nature` module

### RS-3.1 — Extract the runtime nature vocabulary — FRESH
**Onboard:** this plan; `gen/src/main/kotlin/civictech/gen/wire/` file list;
`settings.gradle.kts`; `kernel/build.gradle.kts`; `gen/build.gradle.kts`.
**Do:** Create Gradle module `:nature` (plain Kotlin/JVM, kotlinx-serialization if
needed). Move the **runtime** vocabulary out of `:gen` into it, package
`civictech.nature`: `NatureVector`, `NatureAxis`, `Manifest`, `ContractRegistry`,
descriptor data classes (`CellDescriptor`, `PortDescriptor`, `ProtocolDescriptor`,
`ContractModule`-side value types), the wire conversion helpers
(`toWire`/`natureVectorFromWire`), and `StableHash` if descriptor types need it.
The KSP **processor** classes stay in `:gen`. Wire the dependencies:
`:gen` → `implementation(project(":nature"))`;
`:kernel` → `api(project(":nature"))` (replacing the current
`implementation(project(":gen"))` — check whether the kernel still needs `:gen`
at all beyond `ksp(project(":gen"))`; if not, drop it).
Remove `demo/exchange`'s direct `implementation(project(":gen"))` (its comment says
it exists only to name `Manifest`).
This is a package rename (`civictech.gen.wire` → `civictech.nature`) touching ~42
kernel files + demos + generated-code references **emitted by the processor** —
update the FQN strings the processor writes into generated output too.
**Verify:** `./gradlew :nature:build :gen:test :gen-test:test :kernel:test`, then
`./gradlew test`
**Commit:** `restructure(RS-3.1): extract :nature module from :gen`

### RS-3.2 — Single-source the processor's FQN table — CONTINUE
**Do:** In `ContractProcessor`, gather the hardcoded kernel FQN strings into one
named constants object with a comment pointing at `ManifestDriftTest` as the drift
guard. Where a referenced type now lives in `:nature` (visible to `:gen`), replace
the string with `X::class.qualifiedName!!`. Kernel-type FQNs must remain strings
(`:gen` cannot see `:kernel`) — that's expected; do not add a dependency.
**Verify:** `./gradlew :gen:test :gen-test:test :kernel:test`
**Commit:** `restructure(RS-3.2): consolidate ContractProcessor FQN table`

---

## Session RS-4 — KSP on the demos that define cells

### RS-4.1 — Fix KSP module application — FRESH
**Onboard:** this plan; `demo/*/build.gradle.kts`; `kernel/build.gradle.kts` (as the
reference for correct ksp + generated-source-set wiring);
`doc/ksp-dx-catalog.md` (context only — it is stale).
**Do:** Remove the dead `ksp`/`:gen` config from `:demo:tiering` (it has no cells).
Apply KSP (plugin + `ksp(project(":gen"))` + generated source set) to
`:demo:agora` and `:demo:backlog-triage` — the two modules that define cells
(`ClaimCell`, `EdgeCell`, `RatingCell`, `MetaRankCell`). Run their tests. Expect
possible new failures: descriptors now exist, so the G-17 spawn check and nature
checks activate. Fix **name mismatches only** (port name typos etc.). Known hazard:
`MetaRankCell` registers ports dynamically from a constructor list — descriptors
cannot express that; if it breaks descriptor generation or the spawn check,
**exclude `:demo:backlog-triage` from KSP, keep `:demo:agora`, and record the
finding** in your final report and as a `// TODO(restructure):` at the class.
**Verify:** `./gradlew :demo:agora:test :demo:backlog-triage:test`, then
`./gradlew test`
**Commit:** `restructure(RS-4.1): apply KSP to cell-defining demos, drop dead config`

---

## Session RS-5 — The data layer

One agent for the whole session: the delta algebra knowledge built in RS-5.1 is the
asset every later step uses.

### RS-5.1 — Extract `data.delta` — FRESH
**Onboard:** this plan; read fully: `data/TagState.kt`, `data/MintedTags.kt`,
`data/DeliveredFrontier.kt`, `data/SetCell.kt`, `data/MapCell.kt`,
`data/CounterCell.kt`, `data/PnCounterCell.kt`, `data/ListCell.kt`,
`data/Watermark.kt`, `wire/WireCodec.kt`; spec `doc/spec/20-dataflow-semantics/24-data-cells.md`.
**Do:** Create `civictech.cell.data.delta`. Move into it, each delta in its own file:
`SetDelta` (from `SetCell.kt`), `MapDelta` incl. its `merge`/`within` helpers (from
`MapCell.kt`), `CounterDelta`, `PnCounterDelta`, `ListDelta`, `WatermarkDelta` (from
their cells), plus `TagState.kt`, `MintedTags.kt`, `DeliveredFrontier.kt`. Widen
`internal` on `TagState`/`MintedTags` only if the move forces it — prefer keeping
`internal` (same module). **`@SerialName` strings and serialization shapes must not
change** — Kotlin serial names that are explicit stay identical across package moves;
verify each delta has an explicit `@SerialName` and flag any that relies on the FQN
default (if one does, STOP — that's a wire-format decision for the owner).
Update `WireCodec` to import deltas from `data.delta` (it should no longer import
any cell file). Update all operator imports.
**Verify:** `./gradlew :kernel:test :wire:test :demo:shopping:test`
**Commit:** `restructure(RS-5.1): extract data.delta package (delta algebra)`

### RS-5.2 — `TaggedSetOperator` base — CONTINUE
**Do:** Create `civictech.cell.data.op` with base class `TaggedSetOperator`
capturing the copy-pasted skeleton of `FilterCell`, `UnionSetCell`,
`FlatMapSetCell`, `CountCell`: a `TagState`, `catchUpOnLinked` replay, the
`apply → effective-or-absorbAck → transform → propagate` inlet body, and
`snapshot`/`restore`. Each operator becomes its transform expression. Move the four
onto the base and into `data.op`. **Preserve each operator's exact current ack
behavior** — all four currently call `absorbAck` on empty effective deltas; the base
must do exactly that, nothing more.
**Verify:** `./gradlew :kernel:test --tests '*Operator*' --tests '*GlitchFree*'`,
then `./gradlew :kernel:test`
**Commit:** `restructure(RS-5.2): TaggedSetOperator base; port 4 unary set operators`

### RS-5.3 — `KeyedBinarySetJoin` base — CONTINUE
**Do:** Same treatment for `JoinSetCell`, `SemiJoinCell`, `IntersectSetCell`: base
class in `data.op` carrying the two `TagState`s, the per-side key index (the
byte-identical `index()` helper), the minted/advertised output-tag ledger, the
`onLeft`/`onRight → reconcile → emit` flow, and the `restore` index rebuild.
`IntersectSetCell` uses an `advertised` map instead of `MintedTags` — parameterize
the ledger, don't unify semantics. **Ack behavior stays per-operator exactly as
today** (Join/SemiJoin ack absorbed waves; Intersect currently does NOT — keep that,
and mark it `// TODO(restructure): ack divergence, owner decision pending`).
Assess `QuorumSetCell` last: port it ONLY if it drops in without changing its lane
logic; otherwise leave it and note why.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-5.3): KeyedBinarySetJoin base; port binary set operators`

### RS-5.4 — Adopt `MapDiffPublisher` in the kernel — CONTINUE
**Do:** `CombineLatestCell` and `LookupJoinCell` hand-roll the exact
`emitChanges(touched)` fold that `data/MapDiffPublisher.kt` already implements
(including `catchUpDelta`). Replace their private copies with a `MapDiffPublisher`
field. Check `JoinCell` and `GroupByCell` — adopt there too ONLY if the fold is
line-for-line equivalent; otherwise leave and note. **Do not add `absorbAck`
anywhere it isn't already called.** Move the remaining operator cells
(`GroupByCell`, `MergeableGroupByCell`, `JoinCell`, `LookupJoinCell`,
`CombineLatestCell`, `PresenceCountCell`, `QuorumSetCell` if not already,
`KeyedSetCell` stays a source) into `data.op` as pure package moves.
**Verify:** `./gradlew :kernel:test :demo:tiering:test :demo:skillmatch:test :demo:backlog-triage:test`
**Commit:** `restructure(RS-5.4): adopt MapDiffPublisher in kernel operators; data.op moves`

### RS-5.5 — `data.view` — CONTINUE
**Do:** Create `civictech.cell.data.view`; move `MapView`, `SetView`, `CountView`,
`HubCells`, `MapDiffPublisher` into it. Update `cell.observe` and demo imports.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-5.5): group read models under data.view`

### RS-5.6 — Split `PartitionedCell.kt` into `cell.partition` — CONTINUE
**Do:** Create `civictech.cell.partition`. From `data/PartitionedCell.kt` (760
lines) move: `ShardCell` (own file), `RoutedCommand`/`PullReply` (own file),
`PartitionedShardSet` (own file). `Partitioned` (the marker) and `PartitionedCell`
(the in-process facade) move to `partition` too, each its own file. Pure file
split + package move — do not touch the routing/flip/ledger logic, including its
documented dead `epoch` field. Ground rule 2 applies (`Partitioned` is
KSP-scanned — update the FQN string in `ContractProcessor`).
**Verify:** `./gradlew :gen:test :gen-test:test :kernel:test :demo:exchange:test`,
then session gate `./gradlew test`
**Commit:** `restructure(RS-5.6): split PartitionedCell into cell.partition`

---

## Session RS-6 — Splitting `cell.port`

### RS-6.1 — `cell.link` — FRESH
**Onboard:** this plan; read fully: `port/Link.kt`, `port/CatchUp.kt`,
`port/NatureNegotiation.kt`; spec `doc/spec/10-programming-model/13-links.md`.
**Do:** Create `civictech.cell.link`. Move `Link.kt` there, split into files:
`Link.kt` (Link, LinkResult, LinkRequest, LinkRole, PortLink),
`Identity.kt` (Identity, PeerId, CurrentPeer), `LinkPolicy.kt` (LinkPolicy,
allowPeers), `Handshake.kt` (both handshake overloads, ProtocolBridge),
`LinkSupport.kt`. Also move `CatchUp.kt` (catch-up/baseline is edge semantics).
No logic changes — the handshake bodies move verbatim.
**Verify:** `./gradlew :kernel:test :wire:test`
**Commit:** `restructure(RS-6.1): extract cell.link from cell.port`

### RS-6.2 — `cell.protocol` — CONTINUE
**Do:** Create `civictech.cell.protocol`; move `Protocols.kt` and
`StateRequestProtocol.kt` there.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-6.2): extract cell.protocol from cell.port`

### RS-6.3 — `Interest`/`Scoped` to `cell.link`; `NatureNegotiation` to `cell.nature` — CONTINUE
**Do:** Move `Interest.kt` (incl. `Scoped`) from `cell.replication` to `cell.link`
— it is the edge-semantics demand vocabulary (the spec unifies interest slicing
with disclosure as outlet filters). Update the many consumers, INCLUDING
fully-qualified inline references (`grep -rn 'civictech.cell.replication.Interest\|civictech.cell.replication.Scoped'`
— `SetCell`, `MapCell`, `PartitionedCell`/partition, `Watermark`,
`LocationRegistry`, `StateRequestProtocol` use inline FQNs).
Move `NatureNegotiation.kt` to `cell.nature`. Do NOT split its three reconcile
entry points — that's flagged as a follow-up, not this step.
**Verify:** `./gradlew :kernel:test :wire:test`, session gate `./gradlew test`
**Commit:** `restructure(RS-6.3): Interest to cell.link, NatureNegotiation to cell.nature`

---

## Session RS-7 — The control plane (`cell.control`)

### RS-7.1 — Split `Attention.kt` into `cell.control` — FRESH
**Onboard:** this plan; read fully: `attention/Attention.kt` (406 lines, seven
concepts), `data/AbsorbAck.kt`, `port/InletPolicy.kt`, `host/AttentionPolicy.kt`.
**Do:** Create `civictech.cell.control`. Split `Attention.kt` into files there:
`Attention.kt` (Attention, AttentionProtocol, AttentionFrontier, AttentionBand,
AttentionAggregator, AttentionSupport), `Suspension.kt` (StallReason, StallNotice,
SuspensionProtocol), `Progress.kt` (Progress, ProgressProtocol, VersionMinter).
Delete the now-empty `cell.attention` package. Update imports (host, membrane,
wire, port/InletPolicy, consistency).
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-7.1): split Attention.kt into cell.control`

### RS-7.2 — `Magnitude`, `AbsorbAck`, `ParkQueue` to `cell.control` — CONTINUE
**Do:** Move `data/Magnitude.kt` (a scheduling hint read by the host — this kills
the `port → data` cycle via `Cycle.kt`), `data/AbsorbAck.kt` (metadata-plane
protocol, not delta algebra), and `proxy/ParkQueue.kt` (used by port, membrane,
host, data — its KDoc says so) into `civictech.cell.control`. Ground rule 2 applies
(`Magnitude` is likely in the KSP string table).
**Verify:** `./gradlew :gen:test :gen-test:test :kernel:test`, session gate
`./gradlew test`
**Commit:** `restructure(RS-7.2): Magnitude, AbsorbAck, ParkQueue to cell.control`

---

## Session RS-8 — `ManagedHost` seam extraction

One agent for the whole session — it must know `ManagedHost.kt` intimately by RS-8.2.
Every step is extract-and-delegate: the new class holds the moved state + methods,
`ManagedHost` keeps thin forwarding calls. **Do not reorganize logic, rename
methods, or change locking granularity.** If an extraction forces a lock-order
change, stop and report.

### RS-8.1 — Extract `AttentionScheduler` — FRESH
**Onboard:** this plan; read fully: `host/ManagedHost.kt`, `host/HostScheduler.kt`,
`host/AttentionPolicy.kt`; spec `doc/spec/30-execution-model/34-scheduling.md`.
**Do:** The data-plane dispatch layer inside `ManagedHost` — per-cell FIFO queues,
attention-band selection, stride floor, magnitude boost, attention parking (fields
`dataQueues`, `dispatchStep`, `strideCount`, `lastAttended`, `magnitudeBoost`,
`attentionParked` under `dataLock`; methods around staging/`dispatchStep`) — moves
to `civictech.cell.control.AttentionScheduler`, constructed with
`AttentionPolicy` + the submit callback it needs. `ManagedHost` delegates.
**Verify:** `./gradlew :kernel:test --tests '*Attention*' --tests '*Scheduling*'`,
then `./gradlew :kernel:test`
**Commit:** `restructure(RS-8.1): extract AttentionScheduler from ManagedHost`

### RS-8.2 — Extract `HostDurability` — CONTINUE
**Do:** WAL tee, `journalFrame`, `recoverFrom`, `checkpoint`, `restoreCheckpoint`,
processed-frontier bookkeeping (~250 lines) → `host/HostDurability.kt`, internal
class owned by `ManagedHost`. The wire-envelope journal encoding moves verbatim
(its coupling to `WireCodec.VERSION` is a known, out-of-scope issue — keep the
existing comment).
**Verify:** `./gradlew :kernel:test --tests '*Durab*' --tests '*Recover*' --tests '*Journal*'`,
then `./gradlew :kernel:test`
**Commit:** `restructure(RS-8.2): extract HostDurability from ManagedHost`

### RS-8.3 — Extract `DeadLetters` and intake gating — CONTINUE
**Do:** Two small extractions in one step: (a) dead-letter emission + the
`Owned`/`Leased` sanitization block → `host/DeadLetters.kt`; (b) intake
closed/saturated gating + coalescing + saturation announce →
`host/IntakeControl.kt`.
**Verify:** `./gradlew :kernel:test`
**Commit:** `restructure(RS-8.3): extract DeadLetters and IntakeControl`

### RS-8.4 — Topology walking joins `TopologyIndex` — CONTINUE
**Do:** `suspensionRegionOf`, `bfs`, `hasFrontierPolicy`, `notifyDownstream`, and
the cycle-admission check (`wouldCloseCycle`) walk link topology that
`host/TopologyIndex.kt` already indexes. Move them onto/next to `TopologyIndex`
(same file or `TopologyWalks.kt`), taking the state they read as parameters.
`ManagedHost` delegates.
**Verify:** `./gradlew :kernel:test`, session gate `./gradlew test`
**Commit:** `restructure(RS-8.4): move topology walks from ManagedHost to TopologyIndex`

---

## Session RS-9 — `:testkit` and the demo shell

### RS-9.1 — Create `:testkit` — FRESH
**Onboard:** this plan; read: `kernel/.../host/SimulationController.kt`,
`demo/agora/src/test/kotlin/civictech/agora/TestSupport.kt` (the `Harness`),
one kernel `Fixture` (e.g. `AttentionSchedulingTest.kt`),
`demo/shopping/src/test/.../TwoJvmConvergenceTest.kt`,
`demo/tiering/src/test/.../TieringServerTest.kt`.
**Do:** New module `:testkit` (depends on `:kernel`; consumed as
`testImplementation` everywhere). Contents, extracted from the existing copies —
match their behavior exactly:
`SimWorld` (registry + host + `SimulationController(seed)`, plus agora's *budgeted*
`runToIdle(budget)` — the budgeted form becomes the offered API; kernel's unbudgeted
`SimulationController.runToIdle` is left untouched),
`awaitUntil(what, timeoutMs, condition)`,
`HttpProbe(baseUrl)` with `post`/`state`/`await`,
`JvmPeer.launch(mainClass, vararg args)` + `freePort()`.
Wire `:testkit` into `:kernel` test deps and each demo's test deps.
**Verify:** `./gradlew :testkit:build :kernel:test`
**Commit:** `restructure(RS-9.1): create :testkit module`

### RS-9.2 — Migrate test scaffolding onto `:testkit` — CONTINUE
**Do:** Replace the seven kernel `Fixture` classes, agora's `Harness`
construction trio, the six HTTP-polling waiter copies
(`TieringServerTest`, `SkillMatchServerTest`, `SlotFinderServerTest`,
`TriageServerTest`, `DemoServerTest`, `AgoraServerTest`), and the three multi-JVM
harnesses (`TwoJvmConvergenceTest`, `CrashRestartConvergenceTest`,
`ExchangeScaffoldTest`) with `:testkit` equivalents. Mechanical substitution —
where a local copy differs from the testkit version, keep the local behavior and
note the difference rather than silently changing it.
**Verify:** `./gradlew test`
**Commit:** `restructure(RS-9.2): migrate test scaffolding to :testkit`

### RS-9.3 — Extract the demo HTTP/SSE shell — FRESH
**Onboard:** this plan; read two demo mains fully: `demo/tiering/.../TieringApp.kt`
and `demo/skillmatch/.../SkillMatchApp.kt`; skim the other five mains for shell
variance.
**Do:** New module `:demo:shell` (depends on `:kernel` only). Extract the
byte-identical shell: `HttpExchange.respond`, the SSE client list + `broadcast()` +
`send`, `HttpServer` setup + route registration, port/arg parsing, `start`/`stop`.
API shape: smallest thing that covers the seven demos as they are (e.g.
`DemoShell(port) { get("/state"){...}; post("/cmd"){...}; sse() }` — derive it from
the actual duplicated code, don't design beyond it). Migrate **tiering and
skillmatch only** in this step.
**Verify:** `./gradlew :demo:tiering:test :demo:skillmatch:test`
**Commit:** `restructure(RS-9.3): :demo:shell module; migrate tiering + skillmatch`

### RS-9.4 — Migrate the remaining demos — CONTINUE
**Do:** shopping, exchange, agora, slotfinder, backlog-triage onto `:demo:shell`.
The embedded `PAGE` HTML literals stay in each demo — only the server shell moves.
**Verify:** `./gradlew test` (full gate)
**Commit:** `restructure(RS-9.4): migrate remaining demos to :demo:shell`

---

## Session RS-10 — Documentation alignment

### RS-10.1 — Update the maps — FRESH
**Onboard:** this plan; `AGENTS.md`; `doc/ksp-dx-catalog.md`; `doc/demo-findings.md`;
`git log --oneline` since the run started.
**Do:** (a) Update `AGENTS.md`'s Repository map for the new packages
(`cell.link`, `cell.protocol`, `cell.nature`, `cell.control`, `cell.observe`,
`cell.partition`, `data.delta/op/view`) and modules (`:nature`, `:testkit`,
`:demo:shell`). (b) Mark the landed phases in `doc/ksp-dx-catalog.md` (its "no
implementation yet" header is ~3 phases stale) — status annotations only, no
rewrite. (c) In `doc/demo-findings.md`, mark F-1 and F-3 as closed (promoted to
`CombineLatestCell`/`KeyedSetCell`) and append the two unrecorded gaps from
backlog-triage (cross-key atomic two-key updates; N-ary MapDelta combine) as new
findings. (d) List every `TODO(restructure):` marker left in code in a short
"Deferred decisions" section at the bottom of this plan file.
**Verify:** `./gradlew test` (final gate)
**Commit:** `restructure(RS-10.1): align docs with the new structure`

---

## Deferred decisions

Markers left in the tree at the end of the restructure run (RS-10.1), each an
owner decision explicitly deferred rather than resolved in-place:

- `kernel/src/main/kotlin/civictech/cell/data/op/IntersectSetCell.kt:72` —
  `// TODO(restructure): ack divergence, owner decision pending`. `IntersectSetCell`
  does not call `absorbAck` on empty effective deltas the way `JoinSetCell` and
  `SemiJoinCell` (its `KeyedBinarySetJoin` siblings) do; RS-5.3 preserved this
  divergence rather than unifying ack behavior across the three operators, per
  Ground rule 1 (no behavior change) and the plan's explicitly-out-of-scope list
  (no `absorbAck` additions).
