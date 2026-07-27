# Audit-finding → ticket traceability

Every finding from the 12-principle audit (2026-07-27, `742f7ca`), mapped to
the ticket that resolves it or the explicit reason it is excluded. Rule: no
finding is dropped silently — it is either **ticketed**, **deferred** (real
work, consciously postponed, with a tracked marker), or **excluded** (judged
not worth doing, with the reason).

Status legend: ✅ ticketed · ⏸ deferred (tracked) · ✖ excluded (reasoned).

## SRP / cohesion

| Finding | Status | Where / why |
|---|---|---|
| ManagedHost monolith — S extractions (`DirectedProtocolLink`, `hasDampingWitness`) | ✅ | T11-A |
| ManagedHost — `LinkAdmission` extraction (M) | ✅ | T11-B |
| ManagedHost — supervision extraction (L) | ⏸ | Dedicated future single-agent session per the auditor's own RS-8 discipline advice; touches the hottest `Owned`/`Leased` catch path. Not started here so Phase-1 correctness fixes land on stable ground first. |
| LocationRegistry off-mandate lanes — document (S) | ✅ | T02-F2 (C-marker) |
| LocationRegistry — `InstanceIndex`/`DeliveryHold` extraction (M) | ⏸ | Marker filed (T02); extraction batched with the future registry work so distribution call sites churn once. |
| ContractProcessor — lints + table-builder extraction (S/M) | ✅ | T09-D |
| ContractProcessor — full emitter file-split (L) | ✖ | Auditor's own condition ("only if ksp-dx phase 5 lands") not met. |
| Replication — `replicaFrontier` → `.consistency` (M) | ✅ | T11-D |
| Replication — watermark-companion lifecycle extraction (M) | ⏸ | Recurses through `replicate`; deferred until FU-2 (which targets the moved predicate) settles the seam. |
| Replication — `rebind` ↔ `Promotion` cross-reference KDoc (S) | ✅ | T11-D |
| PartitionedShardSet — `ShardPull` extraction (S) | ✖ | Clean seam but pure structure with no correctness payoff; RESTRUCTURE-PLAN:310 records a deliberate don't-touch for this file. Revisit if the file becomes a churn hotspot. |
| PartitionedShardSet — `FlipWindow` object (M) | ✖ | CP-D4 correctness lives there; controls are load-bearing test seams; documented tradeoff per auditor. |
| PartitionedShardSet — `ledger` mirror | ✖ | Correctly scoped and gated on R1 per its own KDoc — auditor concurred no action. |
| WatermarkCell four-lane KDoc (S) | ✅ | T11-F1 |
| WatermarkCell lane split (M) | ⏸ | Auditor's own counter-argument (one metadata lattice / no second protocol) wins; the T11 KDoc names the trigger (a fifth lane). |

## Build & engineering hygiene

| Finding | Status | Where / why |
|---|---|---|
| No CI | ✅ | T01-B |
| Untracked load-bearing files | ✅ | T01-A |
| `:gen-test` no-op gate (+ JUnit version skew, + config-cache hazard) | ✅ | T01-E (deletion moots all three) |
| Catalog bypasses + 5 dead aliases | ✅ | T01-F2/F3 |
| Test-dependency block ×11 + KSP block ×3 dedupe | ✅ | T01-F1/F4 |
| `demo-app` convention plugin for the 5 simple demos | ✖ | ~15 lines/demo saved vs a third convention plugin's indirection; below the value bar. Reconsider at demo #10. |
| Heap-tuning split (1g doc vs 2g kernel) + `org.gradle.parallel` | ✅ | T01-F5/F6 |
| `ProtocolSupport` retention filed as a real ticket | ✅ | T02-F3 (G-marker) |
| Node version pin (`.nvmrc`/`engines`) for agora UI | ✖ | UI is not in the CI build and is a research frontend; add the day UI CI lands. |
| `:demo:shell` unused `:kernel` dep | ✅ | T01-F7 |

## DRY / duplication

| Finding | Status | Where / why |
|---|---|---|
| Gossip mesh ×2 with 2 live divergences | ✅ | T07-A (minimal-correctness patch + pairing tests) |
| — full `MeshLinker` extraction (M/L) | ⏸ | Chosen against for now: medium-risk typing across `Stamped`-vs-plain outlets; the T07 pairing tests make future drift loud, which was the actual harm. |
| emit-or-absorb rule ×5, 3 without ack | ✅ | T05-B |
| shopping/exchange peer-bootstrap semantic copy (re-announce rule) | ✅ | T07-B (`Peering.chainOnReannounce`) |
| — cosmetic scaffold (Wire ADT, arg parsing) | ✅/✖ | arg-parse helper: T12-E, migrated in `agora`/`backlog-triage`; `shopping`/`exchange` left on their local `value(flag)` copies per T12's own contingency — both files are T07's peering-scaffold `Main.kt`s, mid-flight in the same phase when T12 ran, so migrating them belongs to a fast follow-up once T07 lands. The tiny `Wire` ADT stays per-demo (2 files, 4 lines, no drift risk). |
| `QuorumSetCell` reimplements `AdvertisedLedger` | ✅ | T07-C |
| Pointwise-max ×3 with two identities; `TagState` del-fold copy | ✅ | T07-D |
| — full `Lattice<T>` interface (L) | ✖ | Auditor's own judgment: over-scoped for a research runtime; touches wire-serialized types. |
| `HttpProbe`/`esc`/`value(flag)` demo duplication | ✅ | T12-B/E |
| Build-script duplication | ✅ | T01-F |

## Testability

| Finding | Status | Where / why |
|---|---|---|
| 440 unbudgeted `runToIdle` — JUnit timeout backstop | ✅ | T01-D |
| — budgeted `runToIdle` default | ✅ | T12-A |
| — mass migration of 88 files to `SimWorld` (L) | ✖ | The two fixes above deliver the failure-loudness; wholesale migration is churn without new signal. `SimWorld` multi-host extension deferred with it. |
| Global registries: test pollution / no teardown | ✅/⏸ | Race + teardown reclaim: T04-A; per-test registry snapshot extension excluded — instance-scoping (the real fix) is the tracked end state (T02-F3 marker); an interim snapshot harness would be throwaway. |
| Seed sweeps: no seed in failures, abort-at-first | ✅ | T12-C (`forEachSeed` + worst-offender migration; remainder opportunistic, listed in the T12 report) |
| No CI + concord profile default skips dist/dur | ✅ | T01-B/C |
| Wire smoke: port re-bind race + wall-clock backoff | ✅ | T12-D |
| — grow wire suite toward semantic invariants (L) | ⏸ | T06-E adds the wire-thread conformance test; a full wire semantic suite is future work once a second transport exists. |
| — reconnect bounded attempt ceiling / surfaced give-up state | ✖ | T12-D's own scope line: `WsConnection`'s reconnect loop retries forever by design (ponytail-flagged in `WsTransport.kt`); a ceiling or an observable "gave up" signal is unrequested product behavior, not a test-infra fix — out of scope for T12. |
| Soft-timeout awaits | ✅ | T12-B |
| `@Covers` requirement-id traceability in JVM tests (LOW) | ⏸ | Real but interacts with concord scanner design; revisit after the T02 density lint changes the coverage picture. |

## Error handling & failure paths

| Finding | Status | Where / why |
|---|---|---|
| `install()` park-batch destruction | ✅ | T05-A |
| `Throwable` kills drain loops | ✅ | T04-E |
| ADMIT: no discharge, no reliable ack | ✅ | T05-C |
| `recoverFrom` partial-replay abort (+ intake-bound abort) | ✅ | T05-D |
| Despawn misses the cold-inlet park queue | ✅ | T05-E (Gate case mooted by T03) |
| `CountCell`/Intersect/Quorum missing ack | ✅ | T05-B |
| `sliceTo` null-slice mints no per-link Progress | ⏸ | Needs a per-link ack variant (M, interacts with replication); **filed** as a marker/DISPUTES entry by T05-H rather than fixed. |
| `at()` NoOp void + pre-hello frame drop unaccounted | ✅ | T05-G |
| G-26 "Resolved" overstatement | ✅ | T02-F4 |
| LocationRegistry dead-letter seam (M) | ⏸ | Batched with the registry extraction work (same marker); stderr paths remain, now honestly listed in the ledger. |

## Concurrency

| Finding | Status | Where / why |
|---|---|---|
| `dataLock` ABBA deadlock via saturation relay | ✅ | T04-C |
| WAL order ≠ acceptance order | ✅ | T04-D (+T06-B test) |
| Non-atomic `getOrPut` ×4; leak/teardown | ✅ | T04-A |
| — instance-scoped registries (retires `forkEvery`) | ⏸ | T02-F3 marker; L-effort across dispatch/proxy/bind. |
| Plain `HashMap`s read cross-thread | ✅ | T04-B |
| Dead-letter fan-out on raising thread; `FanOutlet` maps | ✅ | T04-F |
| `ObservationSink` listeners under lock on the host thread | ✅ | T08-D |
| Coroutine `ThreadLocal` context loss + stale `drainingThread` | ✅ | T04-G (+T06-C test) |
| Real schedulers untested | ✅ | T06 |

## Docs & knowledge architecture

All six findings ✅: untracked docs → T01-A; concordance denominator →
T02-C/D; `93` authority contradiction → T02-A4 (index + header; physical
split ⏸ — 257k-token file, L rewrite of inbound links, index removes the
context tax); stale-doc co-location → T02-B; `34-scheduling` phantom package →
T02-A3 (+ lint T02-C1); status-header convention → T02-A/C3. Scenario-count
nit ("~57" vs 55) → T02 general pass.

## API design & DX

| Finding | Status | Where / why |
|---|---|---|
| No payload-type check on links (incl. replay path) | ✅ | T08-A |
| — descriptor-validated `ConnectStep` replay (M/L) | ⏸ | Better-than-erasure fidelity; blocked on descriptor coverage gaps (catalog open question 2). Handshake check covers the majority class now. |
| `@CellBase` zero consumers + doc lies + silent warn | ✅ | T09-B/C |
| `Serve`/`Use` split + `Consumer` retirement | ⏸ | Public-API churn across 34 interfaces + 53 test files; batched into one future API pass so call sites churn once. Convention decision recorded there, not piecemeal. |
| Observation API type erasure | ✅ | T08-B |
| Port-declaration idioms: delete unused `inlet(name)`/`outlet(name)` | ✅ | T03-A7 |
| — migrate 15 raw-ctor sites | ✅ | T11-E |
| — spawn-time port-name/descriptor mismatch check (M) | ⏸ | Advisory-only until descriptor coverage is complete; a partial check that must stay advisory invites false confidence — the exact failure shape this run is eliminating. |
| `graph {}` lateinit/`!!`; `lookup` nullability | ✅ | T08-C |
| `requireBoundRef` skips `FreshLogical` | ✖ | Fixing it breaks the documented zero-arg factory path the KDoc promises; the guard matters for `NewInstanceOf`, which it already covers. |
| Port 8080 ×8 defaults | ✖ | Documented in README troubleshooting; demos are run-by-hand; an ephemeral-port default would break every documented `open localhost:PORT` instruction. |
| `Stateful` opt-in with no diagnostic | ✖ | Volatile-by-design cells are legitimate (`MetaRankCell`); a warning can't distinguish intent without an annotation nobody asked for. |

## Encapsulation

| Finding | Status | Where / why |
|---|---|---|
| `TypedCellHandle.cell` + concrete-port backdoor | ⏸ | Deferred to the API pass (same batch as `Serve`/`Use`) — KSP output shape change, all cells recompile. Discipline currently holds at every call site (verified). The `@KernelInternal` opt-in stopgap was considered and skipped: it wouldn't stop `.call`, the actual hazard. |
| Mutable public `TopologyIndex` | ✅ | T03-B11 |
| `ParkQueue` full `MutableList` API | ✅ | T03-B12 |
| No enforced boundaries (323 public / 20 internal; `explicitApi`) | ✅/⏸ | Executable boundary tests: T10. `explicitApi`/`@RequiresOptIn` sweep ⏸ — flag-day churn conflicts with the concurrent-agent discipline; ratchets deliver the drift protection. |
| `private set` on `dispatchStep`/`unmatchedDrops` | ✅ | T03-B10 |
| `AttentionScheduler.dataQueues` live-map exposure to tests | ✖ | `internal` + same-module tests is Kotlin's model; a snapshot accessor adds API for a hypothetical misuse no test currently commits. |
| `ContractRegistry` LWW + live views | ✅ | T03-B13 |
| `FanOutlet.disclosureFilter` mutable public lambda | ⏸ | The correct owner is the membrane (G-9, explicitly out of scope by project rules); noted in T02's glossary membrane correction. |

## YAGNI

Findings 1–7 (SAFETY_PARK, admission, WaveScope, Gate, Broadcast, Throwing,
ProtocolTraversal hot-path) ✅ → T03. Finding 8 (`lane`/`cardinality`,
`Manifest.GATED`/`PULL_SERVING`) ✅ → T09-E. `Admit` kept deliberately —
T05-C makes it correct instead.

## Conceptual integrity

| Finding | Status | Where / why |
|---|---|---|
| Catch-up: two half-mechanisms | ⏸ | The real fix (counter-neutral `baselineTo`) is the deferral the code itself names (`CatchUp.kt:24-32`) and pre-empting it with a typed-arm patch risks the wave/state fault line. Instead: T02 files the marker; T11-F2 documents the three arms at the code site. **Downgraded from the synthesis' "S mitigation" on closer risk review — flagged here so the downgrade is a visible decision.** |
| `Scoped` covers 2 of 7 delta types | ✅ | T05-F (loud rejection). Implementing `Scoped` for the lattice deltas ✖ — mechanical for counters but genuinely hard for `ListDelta`; loud refusal at the boundary is the sustainable contract until a real partial-interest counter use case exists. |
| "Frontier" ×8 senses; glossary mismatch | ✅ | T02-E1 (disambiguation table; renames ✖ — wide mechanical diff, no behavior payoff). `DeliveredFrontier`/`RetainedFrontiers` unification ⏸ — small but touches replication currency; batch with FU-2. |
| Tier ADT vestigial (GATE unused, ADMIT test-only) | ✅ | T03-A6 (delete Gate + honest KDoc); T05-C makes ADMIT real. Routing host suspension through the inlet chain (L) ✖ — the host-level mechanism is the working one; the spec text is corrected instead. `IntakeControl` drops gaining the `mintsProgressAck` obligation ⏸ — folded into the per-link-ack marker (T05-H). |
| Membrane spec-central, code-peripheral | ⏸/✅ | Glossary corrected + five seams enumerated: T02-E. The `Membrane` type is G-9 (project-deferred). "Policy" name narrowing ✖ — eight-type rename sweep, no confusion evidenced in code. |
| Glossary ghosts (`Invalidate`, `Organelle`, fused Attention/Interest row) | ✅ | T02-E2/E3. Glossary-term→type concord lint ⏸ — revisit once T02's three lints bed in. |

## Modularity

| Finding | Status | Where / why |
|---|---|---|
| 20-package SCC vs claimed layering | ✅/⏸ | Marker + spec correction: T02-F1; ratchet: T10-C; two cheap cycle cuts: `AttentionPolicy` move ✅ T11-C; `host→wire` codec inversion ⏸ (M, touches `HostDurability`/`WireCodec` — batch with the next durability work; named in the marker). |
| Generator on kernel runtime classpath | ✅ | T09-A |
| `:kernel`↔`:testkit` project cycle | ⏸ | Real but contained (4 files); `java-test-fixtures` conversion batched with eventual publishability work. T01-F1 removes the worst side effect (JUnit on consumers' compile classpath via convention dedupe). |
| 39 dead imports; no unused-import gate | ✅/✖ | Sweep: T03-A8. `allWarningsAsErrors`/detekt ✖ — untriaged existing warnings make it a flag-day; T10's ratchet catches the cross-package recurrence class that actually matters. |
| Concord neutrality unenforced + 1 dead violation | ✅ | T10-A. Physical `:concord`/`:concord-kernel-driver` split ⏸ — the stronger fix, deferred to when the W5 second binding becomes concrete. |
| `:gen-test` phantom gate; `:demo:shell` phantom dep | ✅ | T01-E/F7 |

## Deferred register (single list)

For orchestrator visibility, everything ⏸ above in one place: supervision
extraction · LocationRegistry extraction + dead-letter seam ·
watermark-companion extraction · WatermarkCell lane split · MeshLinker ·
instance-scoped registries · per-link ack (`sliceTo` null slices +
`IntakeControl` ack obligation) · catch-up `baselineTo` unification ·
descriptor-validated replay · `Serve`/`Use` + `Consumer` + `TypedCellHandle`
API pass · spawn-time port-name check · `explicitApi`/opt-in sweep ·
`disclosureFilter` ownership (G-9) · `DeliveredFrontier`/`RetainedFrontiers`
unification · glossary-term lint · `host→wire` codec inversion ·
`:kernel`↔`:testkit` test-fixtures · concord physical split · `@Covers`
traceability · wire semantic suite · `93` physical split · SimWorld
multi-host extension.

Each is either already marked in the repo's ledgers by T02/T05, or blocked on
a named event (second binding, FU-2, publishability, API pass). None is
silently dropped.
