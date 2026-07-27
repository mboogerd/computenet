# Inspector delivery plan — progress log

Appended by each milestone's EVAL ticket (see `00-orchestration.md` §Evaluation
protocol step 5). Newest entry last.

---

## M0 — Topology skeleton (evaluated 2026-07-28)

Merged: `worktree-wf_a5e81a8e-df2-1` (M0-BE) + `worktree-wf_a5e81a8e-df2-2`
(M0-FE) + one evaluator commit, as `inspector(M0): …`. Verdict: **accepted**;
three defects fixed in the eval session, no bounce needed.

### Tests run

| Suite | Result |
|---|---|
| `./gradlew :inspect:test` | 10 passed (topology snapshot, SSE deltas over real HTTP, slow-client drop) |
| `./gradlew :kernel:test` | full suite green, incl. new `LocationRegistryDescribeTest` (5) |
| `./gradlew :demo:skillmatch:test` | 4 passed, incl. `SkillMatchInspectorTest` (2) |
| `npm test` in `inspect/ui/` | 43 passed / 7 files (was 40; +3 from this evaluation) |
| `npm run typecheck`, `npm run build` | clean; `dist/` removed, not committed |
| `./gradlew test` (repo gate, primary worktree) | BUILD SUCCESSFUL |

Live verification: `:demo:skillmatch:run --args="8321 --inspect-port 7099"`
plus `npm run dev` proxying the real backend. Screenshots captured for graph
render, click selection + detail panel, and the light/dark toggle.

### Contract conformance (`20-api-contract.md`)

Diffed field-by-field against a live capture. `TopologySnapshot`, `Node`,
`NodePort`, `Edge`, `Endpoint` and the SSE envelope match exactly — no missing,
extra, or misnamed fields; `Content-type: text/event-stream`, `data: <json>\n\n`
framing, 15 s `heartbeat`. Driving the demo's data endpoints produced **no**
topology events, which is correct (P2 — data flow is not topology).

### Defects found and fixed by the evaluator

1. **A dropped SSE delta could be swallowed forever** (BE/FE interaction; the
   most serious). The server's heartbeat re-states the current `seq` without
   consuming one, while the client accepted any `seq == lastSeq + 1` frame as
   the next delta. A client that lost *exactly one* delta therefore saw the
   next heartbeat as that delta, advanced `lastSeq` past the loss, and never
   detected a gap — holding a permanently wrong topology. Each half was
   reasonable alone; only the composition is wrong. Fixed in
   `inspect/ui/src/sync/client.ts`: a heartbeat never advances `lastSeq`, and
   one ahead of `lastSeq` is itself proof of a lost delta → refetch. Two
   regression tests added.
2. **Node type names clipped mid-glyph.** Card content needs 76 px; the layout
   fixed cards at `NODE_H = 72`. `.node-card__type` is the only child with
   `overflow: hidden`, which opts it out of flex's automatic minimum size, so
   it absorbed the whole deficit and rendered a 16.8 px line box in 10.8 px.
   Fixed: `NODE_H` 72 → 80 and `flex: 0 0 auto` on the type row.
3. **`fixtures/topology.json` was invented, not captured.** M0-FE authored it
   from the contract's illustrative "13 cells, 3 hosts" before any server
   existed, with synthetic hosts, empty `manifests`, and `OBSERVE` edges the
   M0 server never emits. Per the contract's own §Fixture instruction and
   M0-EVAL step 1, it is now a verbatim capture of the real skillmatch pilot:
   16 cells (10 named + 6 `ObserveCell` sinks) on the single `skillmatch`
   host, 18 `CONSUME` edges, `fused: null`. `test/fixture.test.ts` now asserts
   reality, including the descriptor metadata the canvas renders from.

Merge drift resolved (all flagged by M0-BE, none silent): `registry.topology.all()`
→ `registry.all()` (main made the field private behind T03 projections); the
four now-redundant test-stack lines dropped from `inspect/build.gradle.kts`;
and both sides of the `LocationRegistry.publish` KDoc conflict kept.

### Acceptance items verified live

- Graph renders from the real backend; header shows the host and a `Live` pip.
- **Insertion does not disturb the layout**: a cell spawned and linked at
  runtime appeared via SSE with **zero** pre-existing nodes moved (every prior
  node's `left/top` byte-identical across the change). Removal likewise —
  the despawned node and its edge vanished, survivors unmoved.
- Click selection highlights the card and fills the (M1-stub) detail panel;
  theme toggle switches and persists.

### Invariant audit (BE diff)

- Kernel change is confined to the declared seam: `LocationRegistry.describe`
  + a weakly-referenced `descriptions` map, one extra defaulted `publish`
  parameter, its removal in `unpublish`/`mirrorUnpublish`, and the single
  `ManagedHost` spawn-path call site. Nothing else in `kernel/` changed.
- **P2**: no per-message hook; the only feeds are rare-path registry hooks.
- **P6**: serving topology reads registry metadata and generated descriptors
  only — it subscribes to no cell and raises no attention.
- **Viz never blocks the graph**: `SseBroadcaster` gives each client a bounded
  (256) queue, its own virtual-thread pump, and drop-oldest; `publish()` never
  waits on a socket. Verified by reading the code *and* by the stalled-client
  test (producer returns while the slow client loses its oldest frames).
- `concord/` untouched; no gap markers removed; no generated/build output in
  the diff.

### Deviations from ticket or contract

Carried forward from M0-BE, all judged acceptable for M0 and visible in code:
`Edge.role` is always `CONSUME` (the topology index only holds consume-role
links; taps are not indexed), `Edge.fused` always `null` (explicitly permitted),
`Node.lifecycle` always `HOT`, `Node.name` supplied by the application rather
than a kernel name registry, and an added `Access-Control-Allow-Origin: *` on
both endpoints (a header, not a DTO field; the UI proxies in dev, so this only
removes a failure mode). M0-FE added a persisted manual theme toggle beyond the
ticket's `prefers-color-scheme` and shipped no e2e tests — both in line with the
ticket's own acceptance list.

### Open questions for M1

1. **The contract's §Fixture line now misdescribes the fixture.** It says
   "13 cells, 3 hosts"; reality is 16 cells on 1 host, and the fixture has been
   reconciled to reality as instructed. The evaluator did not edit
   `20-api-contract.md` (workers and evaluators do not change it unilaterally)
   — the orchestrator should correct that parenthetical.
2. **No `SUSPENDED` lifecycle exists in M0.** `ManagedHost.suspendedCells` is
   private and the registry only knows published-or-not, so the M0-EVAL item
   "kill/restart flows update lifecycle" could not be verified as a
   `HOT → SUSPENDED` transition: the `lifecycle` event only fires on
   re-publish of a known ref (`resumeHost`, a returning migration). The FE
   already renders SUSPENDED (ghosted/dashed) and the store treats a lifecycle
   flip as a pure value change. If M1 wants real suspend/resume reporting it
   needs a second kernel seam — an explicit M1-BE decision, not a silent one.
3. **Should an explicit resync/gap event `kind` exist?** Both workers flagged
   M0-BE's "force-refetch marker on drop" as unnamed in the contract. As of
   this milestone the answer is *no marker needed*: `seq` continuity plus the
   seq-restating heartbeat detects every drop, including the exactly-one case
   once defect 1 above is fixed. Recording it so M1+ does not re-litigate.
4. **Snapshot edges come from `registry.all()`**, which would also include
   mirrored remote links. Single-process M0 has none, so nodes and edges cannot
   disagree yet — revisit at M5, when a mirrored edge could reference a ref
   that is not in `localRefs()`.
5. **`describe(ref)` returns `Class<out Cell>?`** — deliberately the smallest
   thing M0 needs. M1 will want state views and attention through this seam;
   widening it is an M1-BE decision.
