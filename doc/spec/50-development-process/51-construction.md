# 51 — Constructing Cellular Programs

> **Status**: Partial (cell authoring + graph DSL exist; `SpawnStep`
> identity/parent/factory and `spawnBound` remote application built (W3.6,
> G-51 core); `UnlinkStep` and placement/membrane extensions decided in
> [93](../90-roadmap/93-feature-interactions.md), unimplemented;
> codegen/tooling exploratory)
> **Sources**: ADR — Cellular Software Development Process, ADR — Task Definitions, ADR 3 (codegen)
> **Implementation**: hand-written cells + host API; `cell.graph` DSL (`graph`/`GraphSpec`, `IdentityBinding`, `HostManagementApi.spawnBound`, `GraphSpec.applyRemote`/`ApplyReport`); KSP seed (`gen`); no scaffolding

## Authoring a cell today (normative pattern)

```kotlin
interface EchoApi {                       // 1. API interface (proxyable, 41)
    val inlet: Use<Consumer<String>>
    val outlet: Subscribe<Consumer<String>>
}

class EchoCell(override val ref: CellRef = CellRef.random()) : Cell, EchoApi {
    override val inlet by input<Consumer<String>>()     // 2. ports as declarations
    override val outlet by output<Consumer<String>>()

    override fun onActivate(ctx: CellContext) {         // 3. logic in onActivate
        inlet.serve(Consumer { outlet.call.provide(it) })
    }
}
```

Composition happens through hosts:

```kotlin
val host = ManagedHost()
val api = host.managementInlet.call
val a = api.spawn(EchoCell()); val b = api.spawn(CollectorCell())
api.connect(a, "outlet", b, "inlet")
```

Rules: contracts are push-only interfaces (12); no constructor logic (15);
declare merge semantics for concurrent state (24); declare color if not pure
(32).

## Graph construction DSL (G-30, implemented M4.5)

A builder that stays a thin veneer over the host protocol (`cell.graph`):

```kotlin
val spec = graph(host.managementInlet) {
    val a = spawn("writer") { SetCell<String>() }
    val u = spawn("union")  { UnionSetCell<String>() }
    a linkTo u                        // default ports; connect(a, "outlet", u, "inlet") for explicit
}
spec.applyTo(otherHost.managementInlet)   // replay: fresh cells, same topology
```

Every builder operation both applies immediately (`spawn`/`connect` on the
management inlet — link rejections fail construction loudly) and records into
a serializable `GraphSpec(steps)` — **graphs-as-data**, the substrate for the
generative harness (52) and the declarative/no-code path later. Spawn steps
carry a `CellFactory` (a `Serializable` fun interface; lambda captures must
be serializable), so replay mints fresh cells and refs. No new semantics in
the DSL layer, ever: anything the DSL does must be expressible as management
invocations. Operator combinators (`handle.map { }` — Task Definitions'
cold/hot operators) stay unbuilt until a caller needs more than
spawn-and-link.

The step vocabulary's decided extension (decided in 93 I-21; `SpawnStep`'s
identity/parent/factory parameters built W3.6, `UnlinkStep` still unbuilt):
steps are `SpawnStep(handle, factory, identity, parent)`, `ConnectStep`,
and `UnlinkStep` (`Link.unlink()` as a recorded step — unbuilt). `identity` is
an `IdentityBinding` choosing which ref the host mints — `FreshLogical`
(default: the shipped replay-as-new-graph behavior, so "replay mints fresh
cells and refs" stays true by default), `NewInstanceOf(logicalId)` (fresh
instanceId under a given logicalId — a candidate version, 53, or a
deliberately seeded replica), or `Exact(ref)` (deterministic tests,
migration targets; re-applying an `Exact` spawn of a live ref hits the G-16
live-ref spawn guard → `Rejected`, giving idempotent re-apply for free).
`parent` lowers to the parent-aware spawn already recorded at spawn (G-28) —
today recorded as a bookkept association on `spawnBound`, not yet enforced
by a membrane (that enforcement is G-9, unbuilt); selective exposure stays a
property of the container's type (G-9), never a step. The litmus: **the DSL
gains parameters, not verbs** — every step MUST lower to a management
invocation the host already accepts.

### `InstanceSetStep` — declaring a heterogeneous instance set (PN-13, implemented)

`InstanceSetStep(handle, logicalId, factory, instances)` is the composed-node
declaration (spec 40/42 §Interest-scoped instance sets): one logical id and a
set of instances, `instances = f(interestPartition, replicationFactor)`, each
an `InstanceSpec` carrying `(interest, placement, journalId, frontierPolicy,
instanceId)`. It ends the status quo of hand-wiring N mechanisms per composed
node — a single declaration is the whole set.

It is a step in the recorded `GraphSpec` (graphs-as-data) that **lowers** to
`N × SpawnStep` under `IdentityBinding.NewInstanceOf(logicalId)` — the litmus
holds: the DSL gained *parameters* (per-instance interest and hints), not a new
verb. The N interest assignments are the per-instance factories'
construction-time formation assignments (PN-6 made assignment a management
invocation; the declaration folds the *initial* assignment into construction,
and a later journaled *re*assignment is the runtime counterpart, not a
declaration). Apply and replay run over the lowered form (`GraphSpec.lowered()`);
because memberships and links are interest-determined, replay onto fresh hosts
yields identical memberships (instanceId → interest) and link sets (the overlap
graph) regardless of step order — the parameters-not-verbs payoff, and the
control that a fully-default declaration `lower`s to a `GraphSpec` `equals` to
the hand-written N × `SpawnStep` form.

The declaration is the natural home for the cold structural pre-validation the
core apply path leaves research-gated (G-51, 93 I-21/I-26): a mis-composition is
refused loudly at declaration, naming the offending axis — **partitioning a
SINGLETON cell** (one whose manifest lacks `PARTITIONED`) is refused on the
`INSTANCE_SCOPING` axis, and a **`DURABLE` cell declared journal-less** (a null
`journalId`) is refused on the `DURABLE` nature. This is stricter than PN-12's
host-level *soft* durability count (23 §Negotiation) on purpose: a declaration
surface can refuse what a bare spawn only surfaces.

⚠ GAP (G-57): a client holding only a logicalId has no defined
instance-selection policy (nearest replica for reads, leader for writes,
active candidate during promotion), and instanceId minting has no stated
collision discipline across hosts or in deterministic tests. *Proposal*: a
logical rendezvous policy over `instancesOf` keyed by operation class; the
port-compatibility rule on relink (reject vs adapt on
`(portName, contractId)` mismatch) confirmed to live on ports rather than
refs; and a stated birthday-bound argument for random instanceId minting,
with caller-chosen ids in deterministic tests, covering construction-time
`NewInstanceOf` minting across hosts (93 I-2/I-21).

Across the wire, `spawn(cell)` is local-only; the wire form is
`spawnBound(factory, identity, parent)` (built W3.6) — the factory is
already `Serializable`, so `GraphSpec.applyRemote(remoteInlet)` sends
factories and the remote host constructs locally. Loud failure is
synchronous only where construction is co-located with the target host
(`applyTo`, unchanged); remote application (`applyRemote`) degrades to
asynchronous rejection reporting: dead letters on the target host, folded
into a returned `ApplyReport`, never a synchronous cross-wire reply (decided
in 93 I-21). The "fail construction loudly" claim above is thereby scoped to
co-located construction. *Simplification, documented*: `applyRemote` returns
its `ApplyReport` directly rather than wrapping it in a `Deferred` — there is
no real socket transport in-process to await, so the synchronous-return-vs-
Deferred distinction collapses; a genuine cross-process wire layer (still
unbuilt — no module today bridges `HostManagementApi` over an actual
transport) would need the `Deferred<CellRef>`/`Deferred<ApplyReport>`
wrapping `40/41` point 5 describes.

*(G-51 core resolved, W3.6)*: partial-apply is defined as always
**leave-and-report** — a rejected step (including idempotent re-apply via
`Exact` hitting the G-16 guard) dead-letters and is skipped; every other step
still applies, folded into the returned `ApplyReport`. Open, research-gated
(95 §R4): compensating rollback of the successful prefix (partial-apply
*atomicity*); a structured per-step `ApplyReport` *outlet* the applier can
link before target cells exist (today `ApplyReport` is a direct return
value, not a dataflow-subscribable port); and eager cold structural
pre-validation (cardinality/ownership/contract) with a defined error surface
for an invalid spec (93 I-21/I-26).

Replay is placement: each spawn step is validated at the target host's
spawn-admission gate, the cell's color being derivable from its factory
(the generated `CellDescriptor.color`, §Code generation below). A
single-inlet replay MUST fail loudly on a wrong-color cell — the admission
gate doing its job — and mixed-color graphs need an optional per-step
placement constraint (auto-derived from the cell's color, or an explicit
host target) routed by a multi-host replay driver (decided in 93 I-15;
driver unbuilt).

Distinct from construction mode (mint fresh), a **preserve-refs replay
mode** — `applyTo` rebinding existing `(logicalId, instanceId)`, i.e.
every spawn step carrying an `Exact` ref pinned in journaled topology
entries — is the decided future home of journaled topology recovery
(decided in 93 I-7, unbuilt): recovery re-spawns under pinned refs so
links survive without re-handshaking; one flag on one replay machine.
Recorded divergence: the landed M10 recovery does not use it — the durable
host journals every intake frame (management included) but not topology,
the graph is rebuilt out-of-band before `recoverFrom` (30/31), and replay
re-emits un-suppressed (made safe by replay-stable identity + idempotent
merges + catch-up dedup) rather than by the decided NoOp-served
suppression (93 I-7 R4).

## Code generation (direction fixed by ADR 3)

KSP (already wired: `gen` module, `SerializerProcessor`,
`GenerateSuspended`) will generate, per contract interface:

- serializable method-id tables + argument serializers (41);
- reflection-free proxies (14, C-5) for JVM and eventually KMP;
- port metadata (ownership flags 23, color 32, data/management marker G-11).

The generated `CellDescriptor.color` is the admission-gate form of color:
40/41 point 2's "emit color" means `CellDescriptor.color` — letting a host
validate a spawn without instantiating — never a per-message frame field
(decided in 93 I-15).

Legacy `GenerateSuspended` (blocking→suspending derivation) is superseded by
the color/adapter model (32) — fold or retire (G-1).

⚠ GAP (G-52): the adopted membrane design (exposure map + Flatten/Mediate
surface modes over the TrafficLight idiom) is undesigned at its edges — DSL
lowering and proxy generation, exposed-name resolution across the wire,
nested/transitive exposure, wave re-mint interplay, and leaf-cell
membranes. *Proposal*: KSP-generate the Mediate proxy (Invocation capture +
policy/coupling/re-mint) from the membrane declaration and lower the DSL to
spawn/serve/delegate/connect; announcements carry exposed aliases only,
resolved bridge-side to organelle full-ref ports without leaking the
interior; define exposure re-composition (wave-scope/mode) across nesting
levels with cardinality accounting; pin Remint interaction with attention
propagation and late-join catch-up; and specify the minimal non-composite
(leaf) membrane form (93 I-10).

## The programming environment (vision, unbuilt)

Runtime-integrated IDE: scaffolding of cell types, declarative wiring for
no-code workflows, automatic dependency discovery from connected ports,
standard Git/Gradle/test integration. Depends on: topology introspection
(P3 — largely available), the DSL (G-30), and visualization tooling (the
process ADR's own named risk: "runtime graph complexity may grow without
strong visualization tools").
