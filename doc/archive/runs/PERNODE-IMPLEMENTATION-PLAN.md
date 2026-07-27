# Per-node composition — implementation plan (orchestration)

> **Baseline**: `main` @ `809f25c` (composition run complete @ `d40e4ad`).
> **Design source**: [COMPOSITION-PERNODE-PLAN.md](COMPOSITION-PERNODE-PLAN.md) — this
> document adds *no* design; it sequences that plan into agent lanes, waves, and
> tickets ([PERNODE-TICKETS.md](PERNODE-TICKETS.md)).
> **Discipline**: worktree/merge rules as `doc/ORCHESTRATION.md`; every merge gated
> on the full `./gradlew test`; house test style (100-seed generative + controls
> that must diverge) per ticket; no-behavior-change for non-opting graphs except
> the one declared flip (PN-12).

## 1. Lanes = agent contexts

A lane is one agent keeping one functional context alive across sequenced
tickets (`CONTINUE`). A new lane means a `FRESH` context. Lanes are chosen so
that (a) sequenced tickets share domain knowledge worth retaining, and (b)
concurrent lanes are disjoint in *function and files*.

| Lane | Agent context | Tickets (in order) | Primary file territory |
|---|---|---|---|
| **W0** | five one-shot fixers | PN-0a ∥ PN-0b ∥ PN-0c ∥ CP-G1 ∥ CP-G2 | disjoint (see tickets) |
| **α — identity & recovery** | wave identity, frontier, replay semantics | PN-1 → PN-2 | `port/PortRef,MessageContext`, `consistency/WaveFrontier`, `host/ManagedHost(recoverFrom)`, `port/FanOutlet,CatchUp` |
| **β — interest algebra** | interest/delta/pull data model | PN-3a/c → PN-3b | `replication/Interest`, `port/StateRequestProtocol`, `data/SetCell,MapCell` |
| **γ — partition substrate** | router/shard/instance-set | PN-4 → PN-5 → PN-6(+G4) | `data/PartitionedCell`, `replication/InstanceSet(new)`, `replication/Replication(linker)`, `host/LocationRegistry` |
| **δ — settlement** | quorum/watermark consistency | PN-7 → PN-8 | `consistency/WaveFrontier,GlitchFree`, `replication/Replication(frontier)` |
| **ε — port stratum** | policy slots, link roles | PN-9 → PN-10 | `port/FanInlet,FanOutlet,Link,StreamTo,CatchUp`, new `port/InletPolicy` |
| **ζ — vocabulary** | nature/descriptor type system (reads CP-F1..F3, G2) | PN-12 → PN-18 → PN-13 | `gen/ContractDescriptor,ContractProcessor`, `port/NatureNegotiation`, `graph/GraphDsl` |
| **η — evolve & effects** | promotion/shadow/single-writer | PN-14 → PN-17 | `evolve/Evolution`, `replication/(rebind, SingleWriter)`, `host/ManagedHost(Effectful)` |
| **ι — attention** | Stall family, scheduling | PN-19 | `attention/**`, notices in `WaveFrontier`/`Replication`/`PartitionedCell` |
| **κ — demo evidence** | exchange demo builder (CP-E1-style) | PN-15 | `demo/exchange/**` only |
| **λ — research** | spike, no merge commitment | PN-16 | `doc/spec/90-roadmap/95`, throwaway branch |
| **solo** | mechanical extraction | PN-11 | `port/Buffering`, `membrane/TrafficLightCell`, park sites |

Retired/absorbed from the previous ticket file: **CP-G3** do not run (subsumed
by PN-6; running it first moves the durability hole — see design plan §4
resequencing table). **CP-G4** folded into PN-6 as its second exit test.
**CP-G5** superseded by PN-15. **CP-G6** stays trigger-gated with the PN-9
trigger. **CP-G7** superseded by PN-16.

## 2. Dependency DAG (merge-order constraints only)

```
PN-0b ──────────────┐
CP-G1 ──► PN-3b ────┼──────────────► PN-8
CP-G2 ──────────────┼──► PN-10 ──► PN-12 ──► PN-18, PN-13
PN-0c ──────────────┼──────────────► PN-14 ──► PN-17
                    │
PN-1 ──► PN-2 ──┬───┼──► PN-9 ──► PN-10
                │   │
PN-3a/c ──► PN-4┴──► PN-5 ──► PN-6(+G4) ──► PN-7 ──► PN-8, PN-16, PN-19
                                   │              (PN-19 also after PN-10)
PN-13, PN-18 ◄── PN-6              └──► PN-11
PN-15 ◄── PN-8, PN-12, PN-17 (last)
```

## 3. Wave schedule

Widths respect the file-collision analysis in §4. A wave starts when its
prerequisites have **merged to main** (not merely finished).

| Wave | Parallel tickets | Contexts |
|---|---|---|
| **W0** | PN-0a ∥ PN-0b ∥ PN-0c ∥ CP-G1 ∥ CP-G2 | 5 × FRESH (0a/0b/0c are tiny; G1/G2 may run into W1) |
| **W1** | PN-1 ∥ PN-3a/c | α FRESH ∥ β FRESH |
| **W2** | PN-2 ∥ PN-4 ∥ PN-3b | α CONTINUE ∥ γ FRESH ∥ β CONTINUE (3b waits for CP-G1) |
| **W3** | PN-5 ∥ PN-9 | γ CONTINUE ∥ ε FRESH |
| **W4** | PN-6(+G4) ∥ PN-10 | γ CONTINUE ∥ ε CONTINUE (10 waits for CP-G2) |
| **W5** | PN-7 ∥ PN-12 | δ FRESH ∥ ζ FRESH |
| **W6** | PN-8 ∥ PN-18 → PN-13 ∥ PN-14 ∥ PN-11 ∥ PN-16 | δ CONT ∥ ζ CONT ∥ η FRESH ∥ solo FRESH ∥ λ FRESH |
| **W7** | PN-17 ∥ PN-19 | η CONTINUE ∥ ι FRESH |
| **W8** | PN-15 | κ FRESH (the evidence join — always last) |

Rationale for the FRESH/CONTINUE choices that aren't obvious:

- **PN-2 continues α** (not a new agent): the derived-`PortRef` knowledge from
  PN-1 — which call sites consume `sourcePort`, which tests pin identity — is
  exactly the context PN-2's replay work needs.
- **PN-4 is FRESH**, not a continuation of β: the router/shard domain is a
  different mental model from the interest algebra; it only *consumes* β's API.
- **PN-7 is FRESH** even though γ touched `Replication.kt` last: settlement is
  consistency-domain reasoning (waves, watermarks, quorums), not routing; a γ
  continuation would carry router bias into a consistency proof. δ reads PN-2
  and PN-6 outcomes instead.
- **PN-18 and PN-13 continue ζ in that order**: both are pure applications of
  the vocabulary PN-12 builds; splitting them keeps each exit test small.
- **PN-11 is a solo FRESH** late (W6): mechanical, but it touches
  `PartitionedCell`'s flip buffer — must wait until γ's lane is finished (W4).

## 4. File-collision analysis (why the waves are shaped this way)

Hotspots and how they're serialized:

| File | Touched by | Serialization |
|---|---|---|
| `consistency/WaveFrontier.kt` | PN-0a → PN-2 → PN-9 (tier decl) → PN-10 (edge filter) → PN-7 → PN-19 | strictly one lane at a time: W0 → α(W2) → ε(W3–4) → δ(W5) → ι(W7). ε's W3/W4 touches are declarative one-liners; δ owns the file in W5. |
| `host/ManagedHost.kt` | PN-0b (checkpoint) → PN-2 (recoverFrom) → PN-4 (checkpoint region, via γ) → PN-6 (assignment invocation) → PN-12 (spawn check) → PN-17 (Effectful) → PN-11 (park sites) | function-disjoint regions; never two lanes in the same wave except W2 (PN-2 `recoverFrom` vs PN-4 `checkpoint` — disjoint functions, declared) |
| `replication/Replication.kt` | PN-0c → PN-6 (linker) → PN-7 (frontier read) → PN-14 (rebind) → PN-19 (notices) | W0 → γ(W4) → δ(W5) → η(W6) → ι(W7); PN-8 (W6) touches tests only |
| `data/PartitionedCell.kt` | PN-4 → PN-5 → PN-6 → PN-11 → PN-19 | single lane γ through W4, then solo W6, then ι W7 |
| `data/MapCell.kt` | CP-G1 ∥ PN-3b | **serialized**: CP-G1 first, PN-3b (β) continues after its merge — this is why 3b is split out of 3a/c |
| `port/CatchUp.kt`, `port/FanOutlet.kt` | PN-2 (baseline marking) → PN-9/10 (policy lists, roles) → PN-12 (default flip) | α(W2) → ε(W3–4) → ζ(W5) |
| `port/Link.kt` | CP-G2 → PN-10 | G2 merges before ε starts PN-10 (W4) |
| `gen/ContractDescriptor.kt`, `ContractProcessor.kt` | CP-G2 → PN-12 | serialized across waves |
| `host/LocationRegistry.kt` | PN-5 (hold reuse) → PN-6 → PN-7 (logicalId index) → PN-11 (park extraction) | γ(W3–4) → δ(W5) → solo(W6) |
| `demo/exchange/**` | CP-G1 (deletes `MapMergeCell` in `Main.kt`) → PN-15 | G1 in W0; κ owns the demo in W8; **no other ticket touches the demo** (PN-2's test lives in kernel for exactly this reason) |

## 5. Global gates

- Every merge: full `./gradlew test` green; the byte-identity pin set
  (`MixedDurabilityTest` WAL bytes, `KeyedCellsRecoveryTest`,
  `InterestScopedGossipTest`, `TypedLinkTest`, `BridgedHandshakeTest`,
  `NatureDefaultsPreserveBehaviorTest`, `ExchangeScaffoldTest`,
  `ExchangeCompositionExitTest`) green **unchanged** unless the ticket
  explicitly amends one (only PN-12's flip may).
- Wave gate before W5: `PartitionedShardsAcrossHostsTest` +
  `PartitionedCellTest` + `InterestScopedGossipTest` green on γ's merged work.
- Wave gate before W8: pair-matrix update — `COMPOSITION-STATUS.md` §5 empty
  cells A–D, C–D, C–M, K–B, plus effectful/ownership/attention rows, flipped to
  covered with the new test names.
- The **one deliberate behavior change** (taps/`streamTo` negotiate by default)
  lands only in PN-12, gated on the demo's 100-seed + two-JVM tests.
