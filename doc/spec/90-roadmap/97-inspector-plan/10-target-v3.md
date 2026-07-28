# Inspector v3 — target design

The ComputeNet Inspector is a real-time dashboard for the dataflow graphs a
host process runs: topology, placement, per-cell state, errors, and traffic —
live. It is a developer instrument first (debugging running graphs, supporting
the develop → modify → debug loop) and a demonstration surface second.

Design lineage: two interactive mockups (Claude artifacts, ask Merlijn for
access if needed):

- v1 (tabbed views): https://claude.ai/code/artifact/cddd4787-e290-453c-8958-7103c9278e46
- v2 (perspectives + multi-graph navigator): https://claude.ai/code/artifact/19ee08e5-9604-4ec8-a169-d1e9e041f296

v3 refines v2 with one structural change, decided 2026-07-27:

## The v3 model: one canvas, additive toggles, all-properties detail

**There are no view tabs.** The dataflow graph is always rendered. Everything
else is an *overlay toggle* that adds or removes visual elements on that one
canvas — mirroring the backend's conceptual model: the server hands the client
a dataflow graph whose properties update in real time, and the client filters
what to draw.

Toggles (each independent, any combination):

| Toggle | Adds to the canvas | Feed |
|--------|--------------------|------|
| Process hosts | Solid hulls grouping cells by `ManagedHost` | placement fields (M0) |
| Network hosts | Dashed hulls grouping by JVM/peer; nests with process hulls | placement fields (M5) |
| Flow | Edge pulses + rate labels; fused edges marked, never animated | flow events (M3) |
| Errors | Red badges on erring cells, amber "n parked" pills on edges | error events (M2) |
| State | Per-cell chip: cardinality · frontier wave · staleness | state summaries (M1) |

**Selecting a node shows all of its properties at once** — the detail panel is
not perspective-dependent (that was v2). Subsections, stacked:

1. **Descriptor & placement** — class, color (P/B/S), manifests, ports,
   process host, network host, generation, lifecycle, attention band.
2. **State** — materialized view (table) where available, else generic
   snapshot rendering; per-cell frontier stamp.
3. **Flow** — per-port rates, last wave (source · counter), hop; "fused —
   no observable messages" where applicable.
4. **Errors** — local dead letters, parked counts, restart history; "no local
   errors" placeholder otherwise.

Sections render lazily (state subscribes on selection; see observer-effect
rules below) but are all present.

## Navigator (home screen, M4)

A process hosts many disconnected graphs. Home shows:

- **Graph cards** (left rail): name-or-generated-id, cell/host counts, health
  pills (n dead / n parked / hot / cold).
- **Constellation** (main): structure-only thumbnails of every component,
  cold ones dimmed; click to enter a graph's canvas.
- **Search** with modes: *name* (live filter), *problems* (graphs with dead
  letters/parked/restarts; opening jumps to the graph with the Errors toggle
  on), *data* (M5 — find the cell holding a record).

Selection, viewport, and toggle set persist while navigating within a graph.

## Backend seams (all verified to exist in the codebase)

| Need | Seam |
|------|------|
| Topology snapshot | `LocationRegistry.localRefs()`, `registry.topology.all()` (`kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt`, `TopologyIndex.kt`; `TopologyLink` is `@Serializable`) |
| Topology deltas | `registry.onLocalPublish/onLocalUnpublish/onLocalTopology(...)` — the same initial-sync-then-delta pattern `Peering.announceTo` uses (`kernel/.../cell/wire/Peering.kt`) |
| Placement | `registry.locate(ref)` → host; peers via `Peering` (M5) |
| Descriptors | `ContractRegistry.cellDescriptor(cls)` → color, ports, manifests (`nature/.../ContractDescriptor.kt`) — KSP-generated, authoritative |
| State reads | `host.observe(ref, View...)` → `ObservationSink.current()`/`onChange` with late-join catch-up (`kernel/.../cell/observe/Observe.kt`); `Stateful.snapshot()` fallback |
| Errors | `ManagedHost.deadLetterOutlet`, `registry.parkedFor(ref)`, `supervisionAccounting()`, `generationOf(ref)` |
| Flow hooks | `FanOutlet.tap(port)` (Observe-role, fires before consumers), `ManagedHost.enqueueHostedInvocation` (open — global choke-point), `Journal` tee |
| HTTP/SSE | `DemoShell` (`demo/shell/.../DemoShell.kt`) — JDK httpserver + SSE broadcast with initial-frame catch-up |
| UI architecture | `demo/agora/ui/` — SolidJS+Vite; its `src/sync` seam (applySnapshot / diff-once / structure-vs-value split) is the model to copy |

## Constraints (kernel invariants — binding on every ticket)

1. **P2 — fast path untouched.** No per-message hook on the data path.
   Observation happens at taps/crossings. A tap sits on the emitting outlet,
   upstream of the direct-call-vs-enqueue decision, so co-hosted and
   cross-host edges are observed identically — a co-hosted edge is not
   automatically "fused" for observation purposes (M3 correction: the
   original premise here, that co-hosted chains are unobservable, does not
   hold for tap-based flow; see `Edge.fused` in `20-api-contract.md`).
   `fused: true` means the edge's producing endpoint has no emission point at
   all (a delegating pass-through) — genuinely no message to observe; the UI
   renders those as fused, never fakes activity.
2. **P6 — observation is causal.** Subscribing raises attention and can
   un-park cones. v1–v4 rule: state subscriptions are created on node
   selection and released on deselection; browsing/listing never subscribes.
   Cold graphs are listed from registry metadata only (M5 makes cold reads
   explicit).
3. **Ownership.** Taps see payloads read-only (`Borrowed`); the inspector
   never consumes, copies exclusively, or delays `Owned`/`Leased` payloads.
4. **Consistency stance.** Per-cell consistent reads only; cross-panel wave
   alignment is NOT guaranteed (known defect class F-5, accepted until E2).
   Each state view carries its own frontier stamp; nothing in the UI promises
   cross-cell alignment.
5. **Kernel stays transport-neutral.** All HTTP/SSE/JSON lives in the
   `:inspect` module. Kernel changes are limited to small, explicitly-listed
   accessors, threaded through existing structures (descriptors/registry) —
   never runtime reflection.
6. **Viz never blocks the graph.** Feeds are sampled/bounded; on backpressure
   the inspector drops frames, never blocks producers.

## Known kernel gaps this plan works around (do not silently solve)

- **Graph identity**: no `Graph` entity exists; components are emergent and
  unnamed. M4 uses a pragmatic heuristic (stable min-cell-id + optional name
  annotation); the real answer (membranes as naming boundary) is tracked in
  Linear MRB-156 and out of scope here.
- **Inspect-without-attention**: cold reads from checkpoint/journal without
  waking a cone. M5-COLD delivers a minimal explicit-wake UX; the full
  capability is tracked in MRB-157.
- **Content-search cost model**: M5-SEARCH fans out `StateRequest` to
  pull-serving cells only, hot cones only, and surfaces the cost in the UI.
  Anything broader is out of scope (MRB-157).
