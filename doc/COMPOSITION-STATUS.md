# ComputeNet — Composition Status: how features pair, and where the algebra breaks

> **Generated**: 2026-07-25 · read-only survey, companion to [FEATURE-STATUS.md](FEATURE-STATUS.md).
> **Question answered**: not "which features exist" but "which *pairs* of natures
> demonstrably compose", under the philosophy that same-nature cells link directly
> and differing natures need (stackable) adapter ports — with unsurprising defaults
> and per-region reconfiguration.
> **Method**: read `93-feature-interactions.md` (the in-repo pairwise analysis),
> traced the actual link/handshake path, inventoried every adapter-like construct
> in the kernel, and built a pairwise test-coverage matrix over 15 nature axes
> from the ~126 test files.

## 1. The premise has been tested in-repo — half confirmed, half refuted

`doc/spec/90-roadmap/93-feature-interactions.md` is precisely this analysis: the
spec's 44 features reduced to **27 nodes**, all **C(27,2) = 351 pairs** classified
by 15 scan agents, each pair needing to survive three independence probes
(shared-artifact, mid-flight, quantified-guarantee).

**Result: 120 SPECIFIED · 231 TENSION · 0 ORTHOGONAL · 0 CONFLICT.**

- *Zero orthogonal*: "in a framework where every invocation carries a
  MessageContext, every connection is a Link object, and every cell lives behind a
  host queue, the shared-artifact probe alone connects nearly every feature pair…
  Orthogonality was the hypothesis; the matrix refuted it."
- *Zero conflict*: "the spec does not contradict itself anywhere in this matrix;
  every problem found is an absence, not an inconsistency." The vision is
  coherent — the composed semantics is simply not yet fully specified/built.

The 231 tensions were consolidated into 28 challenges → gaps G-34..G-62 → the
W1–W4 tickets that landed (see FEATURE-STATUS.md). The pairwise view has been the
project's organizing principle since the M12 planning round.

## 2. How nature mismatch is actually handled today

Three mechanisms, in descending frequency:

1. **Eliminate the mismatch by substrate uniformity** (dominant; the source of
   "just works"). Flagship evidence: ADR-2 specified four typed color-crossing
   bridges; the implemented kernel has **none** — "No bridge classes exist because
   there is nothing for them to do" (`32-concurrency-colors.md`). Every cross-host
   send is one non-blocking offer into a uniform host queue; for pure cells
   "'coercion' is placement, not an adapter."
2. **Detect and refuse.** Cycle without `FeedbackInlet` (`CycleWithoutHead`),
   wrong color at spawn, second subscriber on an exclusive outlet, non-`Replicable`
   asked to replicate, `Leased` across a wire or cycle edge. Rejected, never
   adapted.
3. **Detect and adapt — rare.** Only three automatic adaptations exist:
   location routing (`Local|Remote` in `LocationRegistry`), late-join catch-up
   (`catchUpOnLinked` / `onLinked`), and the tap/`Borrowed` funnel.

## 3. The adapter idiom exists — unnamed, manual, split across two strata

The de-facto idiom matching the vision: a **type-preserving wrapper cell
`X<T>(clazz)`** with `inlet: FanInlet<T>` → `outlet: FanOutlet<T>` that changes a
non-functional property while passing `T` through. Because these are ordinary
cells, they **stack**, and get supervision/membranes/policies for free.

| Adapter | Between natures | Stratum | Inserted |
|---|---|---|---|
| `BridgeEgressCell`/`BridgeIngressCell` (`cell.wire`) | local ↔ remote bytes | ordinary cells | manual install, automatic use via `LocationRegistry` |
| `GlitchFreeCell` (`cell.consistency`) | raw deltas ↔ wave-complete | ordinary cell (host special-cases it as a suspension region) | manual, opt-in |
| `TrafficLightCell` (`cell.membrane`) | flowing ↔ parked | ordinary cell | manual |
| `CompositeCell` Flatten/Mediate (`cell.membrane`) | interior ↔ exterior; unauthenticated ↔ signed | ordinary cell + served proxy | manual |
| `PartitionedCell` (`cell.data`) | atomic ↔ sharded (GroupBy only) | ordinary composite cell | manual |
| `CycleHead`/`FeedbackInlet` (`cell.port.Cycle`) | acyclic ↔ cyclic (fresh-wave mint) | special **port kind** | manual placement, host-enforced |
| `WireCodec`, `Journal` | objects ↔ bytes; volatile ↔ durable ("a journal is a bridge to disk") | kernel machinery | codec automatic; journal = host ctor param |
| `Replication` / `SingleWriterReplication` | local ↔ replicated | wiring coordinator object | register once, mesh automatic |
| `catchUpOnLinked` + `StateRequestProtocol` | push ↔ pull | extension fn + metadata protocol | automatic per link once opted in |
| taps / `Borrowed` (`FanOutlet.tap`) | exclusive ↔ observable | kernel machinery, bypasses handshake | automatic given tap call |
| `Shadow`/`Promotion` (`cell.evolve`) | production ↔ candidate; effectful ↔ suppressed | helper objects | manual |
| `ObservationSink`/`View` (`cell.host.Observe`) | delta stream ↔ readable snapshot | hosted sink cell | manual |

Findings:

- **No uniform abstraction.** `grep interface (Adapter|Converter|Coupling|Nature|Transformer)`
  → zero hits; the word "nature" occurs once, incidentally. Each adapter is ad-hoc.
- **Two strata, nothing spans them.** Adapter *cells* are stackable; kernel
  *machinery* (codec, journal, replication, shadow, taps, parking) is not. No rule
  says which stratum a new adapter belongs in.
- **Primitives duplicated by copy, not abstraction.** `Buffering` backs
  TrafficLight *and* location parking *and* the promotion swap window; `WireCodec`
  backs bridge *and* journal; state-as-delta-from-empty backs catch-up,
  `StateRequest`, repartition, replication sync, and promotion-T2 — five sites
  re-deriving one pattern.
- The one *specified* generic coupling abstraction (membrane Symport/Antiport)
  is deliberately unbuilt, research-gated on liveness (G-53 / 95 §R3).

## 4. The load-bearing gap: nature is not representable at the link

### What the handshake actually sees

`handshake()` (`port/Link.kt`) receives a `LinkTo`, a `PortRef` pair, and a
`LinkRole` — **no descriptor, no capability set, no cell metadata**. The sequence
is: policies → `onLink` → install → register → `EdgeOpen` → `onLinked`. That is
the entire negotiation.

### Per-nature verdicts at link time

| Nature | Verdict |
|---|---|
| Cardinality (SPSC/fan) | ✅ rejected — but self-declared per port, never cross-checked |
| Exclusive/ownership bit | ✅ rejected — producer side only (`FanOutlet` reads the descriptor; `FanInlet` never does) |
| Cycle membership | ✅ rejected (`CycleWithoutHead`) — locally-visible cycles only; cross-host falls to the runtime hop guard |
| Peer identity | ✅ via `LinkPolicy` (allowlist) |
| Type/contract | compile-time only (`link(Subscribe<Api>, Serve<Api>)`); the string/`GraphSpec` path — i.e. the **remote/replay path** — has **no check**; mismatch = reflection failure at first emission |
| Direction | compile-time only; every fan port is both `LinkTo` and `LinkFrom`, so inlet→inlet installs |
| Execution color | not in the link path (spawn-time check instead) |
| Glitch-free requirement | auto-adapted post-install (`EdgeOpen` → `StateRequest`), never negotiated; unsupported counterpart = **silent drop** |
| Push/pull capability | adapted unconditionally; no negotiation; unsupported = silent no-op |
| Protocol capabilities | never compared between endpoints; local-registry-derived; missing capability **silently drops** messages |
| Durability | **not represented at all** |
| Replication class | **not represented at all** |
| Partitioned vs atomic | **not represented at all** |

`LinkResult` = `Connected | Rejected(reason: String) | Deferred` — the reason an
unstructured string, so **no mismatch is machine-inspectable**. Auto-adaptation is
impossible today not because adapters are missing but because *detection* is:
nothing can tell which nature mismatched.

Additional leaks:

- **Bridged links skip the handshake wholesale** (`WireEdgeLink.bridgeTo/bridgeFrom`
  call `linking.register` directly — no policies, no allowlist, no cardinality, no
  cycle check). Local and remote links have different negotiation semantics — a
  location-transparency leak.
- **`LinkRole.Observe` is dead in the link path** — never passed anywhere in main;
  taps install via `FanOutlet.tap()` outside the handshake (no policy, no
  `EdgeOpen`, no registration).
- Descriptor vocabulary that *does* exist (KSP, G-60 half): color,
  management/data, effect, direction, cardinality, exclusive, magnitude,
  idempotentMerge, keyIndex. Missing from descriptors entirely: glitch-free,
  durable, replicated, partitioned, push/pull, cold/eager.

## 5. What demonstrably composes — pairwise test coverage

15 axes: **A** glitch-free · **B** replication · **C** partitioning · **D**
durability · **E** exec color · **F** location/remote · **G** ownership · **H**
attention · **I** cycles · **J** effectful · **K** shadow/promotion · **L**
membranes · **M** push/pull catch-up · **N** magnitude · **O** operators.

**53 of 105 pairs have at least one real test.** Highlights of *covered* pairs:
A–H (`GlitchFreeSuspensionTest`), A–M (`StatePullTest`), B–D+B–F
(`CrashRecoveryTest`, `CrashRestartConvergenceTest` — incl. genuinely **mixed**
durability: one journaled peer among volatile ones), B–G
(`SingleWriterReplicationTest` rejects a `Leased` write), F–G (`OwnershipTest`
move-by-serialize), G–J–K (`ShadowOwnershipTest`), I–N
(`CycleQuiescenceTest`/`CycleHeadTest`), D–I–N (agora `DurabilityTest`), C–L–M–O
(`PartitionedCellTest`), K–J–L–M (`ShadowPromotionTest`).

### The empty cells (the important output)

- **Partitioning (C): 11 of 14 pairs uncovered** — the worst axis.
  `PartitionedCell` appears in exactly one test file; shards are in-process
  organelles "never independently spawned onto a ManagedHost"; the file itself
  says "distribution edges remain open, G-56". Nothing shards across hosts, the
  wire, a journal, attention, or promotion.
- **Glitch-freedom (A) never leaves the local in-memory world**: A–B, A–C, A–D,
  A–F, A–G, A–J, A–K, A–L, A–N, A–O all empty. `WireEdgeLink` carries frontier
  data, but no test wires a `GlitchFreeCell` across a peer boundary, and none
  combines it with the shipped operator library.
- **Attention × cycles (H–I)** — no test suspends a cyclic region.
- **Promotion (K) never meets replication, sharding, journal, color, or
  attention** (K–B, K–C, K–D, K–E, K–H empty).
- **Effectful (J) only ever meets durability, ownership, shadow** — no effect
  across the wire, under replication, or under magnitude scheduling.
- **Membranes (L) never meet glitch-freedom, replication, durability, colors, or
  exclusives.**

## 6. Verdict against the vision's specific asks

| Ask | Verdict |
|---|---|
| Push/pull mixed per region | ✅ works, per-link (`StateRequest` + `catchUpOnLinked`); silent no-op if unsupported |
| Part in-memory, part change-log-then-process persistent | ✅ works and is exactly the intake-tee discipline (journal order = acceptance order = replay FIFO) — but **host-granular** (`journal` is a `ManagedHost` ctor param); per-cell durability means placement; no check at the durable↔ephemeral boundary |
| Part replicated, part local | ✅ works, per-cell (`Replicable` + `Replication.replicate`) |
| Partition some cells; many partition data-structure types | ❌ weakest area: one structure (partitioned GroupBy), in-process only, pluggable partitioner but fixed structure |
| Part glitch-free, part not | ✅ locally (opt-in wrapper, unwaved traffic passes through) — untested across wire/operators/replication/durability; static frontier ("ponytail" marker, needs G-13) |
| Unsurprising defaults, reconfigurable | ✅ genuinely good: unbounded intake, `WaveMode.WAIT`, `journal = null`, hash partitioner, `LinkRole.Consume`, opt-in glitch-freedom — each with a knob |

## 7. Biggest gaps, ranked

1. **Nature is not declarable** → mismatch undetectable → auto-adaptation and
   principled stacking impossible. (The half-built G-60 descriptor sweep is the
   natural vehicle.)
2. **Partitioning composes with nothing** — one structure, in-process, one test.
3. **Glitch-freedom has zero mixed coverage** beyond attention/pull — the axis
   most likely to silently degrade when combined.
4. **Two adapter strata with no bridge**; shared primitives duplicated by copy
   (Buffering ×3, WireCodec ×2, state-as-delta ×5).
5. **Bridged links bypass the handshake** — remote negotiation ≠ local
   negotiation.
6. Empty high-value pairs: attention×cycles, promotion×{replication, sharding,
   journal}, effectful×wire.

## 8. Cheapest unlock

Not a universal adapter framework — **make mismatch visible**:

1. Extend the existing KSP descriptor sweep (G-60, already half-landed) so
   descriptors carry the structural natures (glitch-free, durable, replicated,
   partitioned, pull-capable, eager/cold).
2. Pass the descriptor pair into `handshake()`.
3. Replace `Rejected(reason: String)` with a typed mismatch
   (`Rejected(mismatch: NatureMismatch)`).

Auto-adaptation then falls out: the type-preserving adapter cells one would
insert **already exist and already stack**. This converts today's
detect-and-refuse into detect-and-adapt without inventing a new abstraction
layer — and gives the test suite a target vocabulary for filling the empty
pairwise cells (start with A–F, C–F, K–B).
