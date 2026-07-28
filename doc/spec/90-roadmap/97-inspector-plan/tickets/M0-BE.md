# M0-BE — `:inspect` module: topology snapshot + SSE delta feed

**Status**: Implemented — merged to main (see `90-progress-log.md`).

Model: `claude-opus-5` (effort xhigh) · Track: backend · Depends: — ·
Parallel with: M0-FE (file-disjoint)

Files owned: `settings.gradle.kts` (add module), `inspect/build.gradle.kts`,
`inspect/src/**`, `demo/skillmatch/**` (launcher wiring only), plus the one
kernel seam listed under Implement §3.

## Context

Read `AGENTS.md` first (repo map, invariants, verification discipline), then
`doc/spec/90-roadmap/97-inspector-plan/10-target-v3.md` (product target,
constraints) and `20-api-contract.md` (the shapes you must serve — they are
binding). ComputeNet is a Kotlin/JVM dataflow runtime: cells with typed ports,
explicit links; `LocationRegistry` + `TopologyIndex`
(`kernel/src/main/kotlin/civictech/cell/host/`) already hold the live topology
and expose add/remove hooks (`onLocalPublish`, `onLocalUnpublish`,
`onLocalTopology`) — `Peering.announceTo`
(`kernel/.../cell/wire/Peering.kt`) shows the intended
initial-sync-then-delta consumption pattern. `DemoShell`
(`demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt`) is the
house pattern for HTTP + SSE.

## Implement

1. **New Gradle module `:inspect`** depending on `:kernel` and `:demo:shell`
   (reuse `DemoShell`'s httpserver/SSE machinery; if its API is too
   demo-shaped, add minimal reusable pieces to `:demo:shell` rather than
   duplicating). No new third-party dependencies; kotlinx.serialization for
   JSON, matching kernel usage.
2. **`InspectorServer`**: constructed with a `LocationRegistry` and the set of
   `ManagedHost`s to inspect; serves `GET /api/inspect/topology` and
   `GET /api/inspect/events` per the contract. Snapshot is built from
   `registry.localRefs()` + `registry.topology.all()`; deltas from the
   registry hooks, forwarded as `topology.node` / `topology.link` /
   `lifecycle` events with a monotonic `seq`. SSE clients that connect
   mid-stream rely on snapshot-then-events with seq filtering (contract §SSE).
   Slow SSE clients must never block graph threads: per-client bounded queue,
   drop-oldest, and force-refetch marker on drop.
3. **Kernel seam — cell metadata**: the registry today cannot answer
   `CellRef → concrete class`. Add the *smallest* accessor consistent with the
   invariant "generated descriptors are authoritative": capture the cell's
   class (or its `CellDescriptor`) at publish/spawn time in `LocationRegistry`
   (weakly referenced), exposed as `describe(ref)`. Thread it through the
   existing publish path; do not use runtime reflection at read time beyond
   `ContractRegistry.cellDescriptor(cls)` (`nature/`), which is the sanctioned
   lookup. Port names/dirs and color/manifests come from that descriptor.
   Fill `Node.host` via `registry.locate(ref)`; `net` is `"local"` for now.
   `Edge.fused` may be `null` in M0 if fusion isn't cheaply detectable — do
   not guess.
4. **Pilot wiring**: give the skillmatch demo an opt-in inspector —
   `--inspect-port <p>` (or env `INSPECT_PORT`) starts `InspectorServer`
   alongside the demo. Default off. Touch the demo's main only.

## Exclusions

State/detail endpoints (M1), errors (M2), flow (M3), multi-graph (M4). No UI.
No changes to wire/, concord/, or other demos. No new fields beyond the
contract.

## Invariants to respect

P2: nothing on the per-message data path — topology hooks are rare-path.
P6: serving topology must not subscribe to any cell or raise attention.
Transport-neutral kernel: the only kernel edit is Implement §3.

## Tests / acceptance

- `inspect/src/test/**`: snapshot correctness against a small in-process graph
  (spawn cells, link, assert nodes/edges/ports/host); delta emission on
  spawn/despawn/link/unlink (seq monotonic, shapes per contract); slow-client
  drop behavior (bounded queue, producer never blocks).
- Kernel accessor: focused test in `:kernel` for `describe(ref)` including the
  unregistered-host case (registry-less hosts are invisible — document this in
  the module KDoc, don't crash).
- `./gradlew :inspect:test :kernel:test :demo:skillmatch:test` green.
- Manual check documented in the report: start skillmatch with
  `--inspect-port 7071`, `curl /api/inspect/topology` matches contract.

## Report

List tests run, the exact kernel diff (file + lines), any contract ambiguity
found (flag to orchestrator — do not edit the contract), and known limitations.
