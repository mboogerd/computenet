# Inspector delivery plan — progress log

**Status**: Implemented — closing log of a completed delivery plan; see the M5-EVAL entry for the final acceptance report.

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

---

## M1 — Selection + state (evaluated 2026-07-28)

Merged: `worktree-wf_61385043-d56-1` (M1-BE) + `worktree-wf_61385043-d56-2`
(M1-FE) into evaluator branch `worktree-wf_61385043-d56-3`, head `74db7f9`.
Verdict: **accepted**; one real cross-side defect and one fixture/dead-code
mismatch fixed in the eval session, no bounce needed.

### Environment note (same class of issue as M0's item, recurred)

Both worker worktrees, and this evaluator's own, were handed branches at
`46d5a4a` — an ancestor of main predating the entire M0 merge (`inspect/`
absent). M1-BE independently fast-forwarded its branch to main with
`git reset --hard main` before starting; M1-FE could not (no git authority
per its own discipline) and instead copied `inspect/ui/` from main verbatim
as an isolated commit (`a076abd`). This evaluator fast-forwarded its own
branch to main (`8cb285d`) first — clean, working tree was empty — then
merged BE (clean, its `fa7029f` merge-base is an ancestor of main, no
overlapping files) then FE. FE's merge produced 12 `add/add` conflicts
(the `46d5a4a` merge-base has no `inspect/ui/` at all, so git could not
3-way-merge); each was verified by diff to be a pure superset of main's M0
baseline before resolving to FE's side — no work lost. **Flagging again for
the orchestrator**: this is the second consecutive milestone where worker
worktrees were not branched from current main; worth fixing before M2.

### Tests run

| Suite | Result |
|---|---|
| `./gradlew :inspect:test` | 53 passed (10 M0 + 43 new: `ValueEncoderTest` 18, `InspectorObserveTest` 13, `ObservationsIdleTest` 7, `InspectorCellDetailTest` 5) |
| `npm run typecheck` (`inspect/ui/`) | clean |
| `npm test` (`inspect/ui/`) | 65/65 passed / 10 files (was 43/7 at M0; +3 new files, +2 net new tests after the fixture fix below) |
| `npm run build` | clean; `dist/` removed, not committed |
| `./gradlew test` (repo gate, `--rerun-tasks`, this worktree) | BUILD SUCCESSFUL in 2m9s, 75/75 tasks, incl. `:concord:test`, `:demo:exchange:test`, `:demo:agora:test`, `:wire:test`, `:gen:test` |

Live verification: `:demo:skillmatch:installDist` run on port 8393 with
`--inspect-port 7393`; `npm run dev` (port 5493) proxying the real backend
via `INSPECT_BACKEND`. Screenshots captured in-session for: graph with no
prior observe traffic, `candSkills` selected (all four stacked subsections,
descriptor/placement/state/flow-placeholder/errors-placeholder), live state
update after a `POST /op` mutation (frontier `3adb86c5·5`), Process-hosts +
State toggles on (dashed `skillmatch` hull, one chip on the observed node
only), and post-deselect (panel empty, chip gone).

### Contract conformance (`20-api-contract.md`)

`CellDetail`, `CellState`, observe endpoints, `state.summary` — diffed
against live captures from the real server (curl + browser network log), not
just the FE's own fixtures. Matched exactly except the two defects below,
both in the FE's `Value` handling, both now fixed and reconciled to the
server per this ticket's own instruction.

### Defects found and fixed by the evaluator

1. **Real defect — FE's `opaqueOf()` used the wrong key and shape.**
   `ValueEncoder.kt` emits the reserved key `$opaque` holding
   `{"type": ..., "text": ...}`; `inspect/ui/src/api/types.ts`'s `opaqueOf`
   read the bare key `opaque` and expected a plain string. Every server
   `$opaque` value would have silently fallen through to the tree/scalar
   renderer instead of the code-block branch — confirmed by reading both
   sides' source (skillmatch's pipeline never produces an opaque value, so
   this could not be triggered live; the mismatch is unambiguous from the
   two encoders' definitions alone). Fixed `opaqueOf` (correct key, returns
   `{type, text}`), `ValueView.tsx`'s rendering (shows both), `ValueView.css`,
   `fixtures/cell-state-opaque.json`, and `test/value.test.ts`.
2. **Fixture/dead-code mismatch — a tombstoned `$table` row the server never
   sends.** `fixtures/cell-state-table.json` fabricated a
   `{cells: [...], tombstoned: true}` row to exercise the ticket's
   "tombstone-style strikethrough" rendering. Verified live against
   skillmatch's `candSkills` `SetCell` (added two skills, removed a third):
   `ValueEncoder.orSetMembership` excludes tombstoned elements from the
   encoded state entirely — a removed element just disappears from `rows`;
   the wire never carries a tombstone marker of any shape. Reconciled the
   fixture and its test to the real 2-row shape; left `isTombstoneRow`/
   `rowCells` and the `.is-tombstoned` CSS class in place as harmless,
   explicitly-documented forward compatibility (dead code today, no server
   response can construct it) rather than ripping them out.

No other defects found. `InspectorServer`'s `serveCell` ref parsing
(split on `/`, ref itself unencoded) matches FE's `defaultDetailTransport`
assumption exactly — no fix needed there.

### Acceptance items verified live

- **Observer-effect discipline (P6, priority 1)**: browsing the graph before
  any selection issued zero `/observe` calls (network log). Selecting
  `candSkills` issued exactly one `POST .../observe` (204); reselecting
  `jobSkills` issued exactly one `DELETE` for `candSkills` then one `POST`
  for `jobSkills`; clicking the panel's close (×) issued exactly one
  `DELETE`. After a full select → mutate → reselect → deselect loop,
  `GET /api/inspect/topology` reported the same 16 nodes / 18 edges as the
  clean baseline — no leaked `ObserveCell` sinks, matching M1-BE's own
  `InspectorObserveTest` assertions (`sinkRef !in registry.localRefs()` after
  release).
- **Vertical smoke**: selected `candSkills`, table showed `alice/kotlin`,
  `alice/typescript`; `curl .../op` adding `carol/rust` produced a live
  `state.summary` → refetch → `carol/rust` appeared, frontier advanced to
  `3adb86c5·5`, staleness reset. Bulk-seeded 250 more candidate/skill pairs
  (253 total live elements); server response carried `rows.length === 200`
  and `{"$truncated":{"total":253,"shown":200}}` exactly per contract; the
  State canvas chip read "253 rows · 3adb86·255 · 0ms".
- **Snapshot thread-safety (priority 4)**: code review, not a live trigger
  (see Deviations #1 below) — `SnapshotSource`'s shipped default
  (`Unavailable`) means `kind: "snapshot"` is unreachable in this milestone;
  `serveState` never reads a live cell off the HTTP thread. M1-BE's own test
  suite covers the seam's injected behavior; the host-routed executing-thread
  assertion the ticket asks for cannot exist until the seam is wired (open
  question #1, carried from M1-BE).
- All four detail-panel subsections stack on every selection (Descriptor &
  placement, State, Flow placeholder "arrives with the Flow milestone",
  Errors placeholder "arrives with the Errors milestone") — verified on both
  an occupied cell (`candSkills`) and an empty one (`jobSkills`, 0 rows,
  value `[]`, no crash).
- Process-hosts toggle: one dashed hull labelled `skillmatch` beneath the
  edges. State toggle: exactly one chip, on the observed node only, tracking
  live cardinality/frontier/staleness.

### Kernel invariant audit (BE diff)

M1-BE touched no kernel files at all (`git diff fa7029f..worktree-wf_61385043-d56-1`
is confined to `inspect/src/**`), so this is a code-review confirmation, not a
kernel-surface review:

- **P2**: no per-message hook; the only new feed is one ordinary
  `ObserveCell` per active observation, ended explicitly.
- **P6**: `Observations.start`/`stop` are the only path that spawns/links a
  sink; `serveDetail`/`serveState`(unobserved)/`snapshotReading`(default)
  never do. Verified both by code review and live network/topology counts
  above.
- **Ownership**: the observation sink is a normal `View`-folding consumer of
  the outlet's own delta stream (`Borrowed`, same as any other consumer) —
  no tap, no exclusive-payload path touched.
- **Release correctness**: `Observations.release` runs `link.unlink()` then
  `despawn(sinkRef)`, in that order, exactly as documented and tested
  (`InspectorObserveTest`'s "no further onChange" + despawn assertions).
- **Transport-neutral**: zero kernel files in the diff.
- Main advanced during M1-BE's run to include `851ab0c` (`ObserveCell`
  reopens its dispatcher on `onActivate`) — M1-BE reviewed this diff and
  found every API surface `Observations.kt` uses unchanged; this evaluator
  independently confirms the merged tree (which includes `851ab0c`) passes
  the full `./gradlew test` gate.

### Diff hygiene

`git diff main HEAD --name-only` touches only `inspect/src/**` and
`inspect/ui/**` (verified via grep for `concord`, `kernel/`, `build/`,
`dist/`, `node_modules` — all empty). No gap/consistency markers removed. No
weakened tests — the two fixture fixes above strengthened assertions to
match verified server behavior rather than loosening them.

### Deviations from ticket or contract (all accepted)

1. **`Stateful.snapshot()` host-routed fallback not wired** (M1-BE Implement
   §3, second branch). No kernel seam exists to run it on the owning host's
   thread, and the ticket's own Exclusions require stopping and flagging
   rather than editing the kernel without sign-off. `SnapshotSource` is
   declared and the whole `kind: "snapshot"` path is implemented and tested
   behind it; shipped default is `Unavailable` (→ `kind: "unavailable"`).
   Practical blast radius: cells with no delta outlet (chiefly `ObserveCell`
   sinks) permanently report unavailable until this is approved. See open
   question #1.
2. **`POST /observe` can answer `409`**, not only the contract's `204`, when
   the cell has no built-in fold. A client that ignores it still behaves
   correctly (`GET state` reports `unavailable`). Accepted; contract
   addition needed (open question #2a).
3. **`$opaque` is a new reserved key** beyond the contract's `$table`/
   `$truncated`, shape `{type, text}`. Accepted — realizes the ticket's
   "safe reflective-toString last resort clearly marked opaque"; now
   correctly consumed on the FE side after this evaluation's fix. Contract
   addition needed (open question #2b).
4. **`attention` always `null`, `links.taps` always `0`** — both explicitly
   permitted by the ticket (kernel access limits: `AttentionSupport`/
   `FanOutlet.tap` bookkeeping are not reachable from the seams M1 grants).
   Confirmed live: `candSkills`' panel showed `Attention —`, `taps 0`.
5. **State canvas chips ship in M1**, per the ticket's own later "Correction
   for clarity" overriding its earlier Exclusions sentence — FE followed the
   correction; verified live (chip appears only for an actively-observed
   cell, never a merely-browsed one).
6. **A freshly-opened observation reports `frontier: null`** even though its
   fold is already populated (the producer's late-join catch-up baseline is
   deliberately not a wave position, spec 20/21 §Pull, 93 I-24). Verified
   live: `candSkills`' first read showed no frontier chip; one appeared after
   the first live mutation.
7. **An empty collection of records encodes as `[]`, not an empty `$table`**
   — columns are only discoverable from an element. Verified live:
   `jobSkills` (0 rows) rendered as an empty list, no crash, "0 rows" chip.

### Open questions for M2

1. **Snapshot seam approval, carried from M1-BE**: add
   `ManagedHost.snapshotOf(ref): Serializable?`
   (`enqueueAwaiting(0) { (cells[ref] as? Stateful)?.snapshot() }`)? Until
   approved, `kind: "snapshot"` stays unreachable in production.
2. **Contract updates still needed in `20-api-contract.md`** (implemented in
   code, verified correct by this evaluation, not yet written into the
   contract file — evaluators do not edit it unilaterally): (a) `POST
   /observe` may return `409` when the cell has no observable outlet; (b)
   `$opaque` as a third reserved `Value` key, shape `{type: string, text:
   string}` (this evaluation corrected the FE's guess at both the key name
   and the shape — the contract should specify the real one); (c) a sentence
   confirming `$table` never carries a tombstone-marked row today (tombstoned
   elements are excluded from encoded state entirely, not flagged).
3. **`:concord:check`'s `docLints` task fails**, independent of this
   milestone: every file under `doc/spec/90-roadmap/97-inspector-plan/`
   lacks the bold `**Status**:` header the lint requires, going back to the
   folder's first commit (`57a5844`, pre-M0). Not part of the specified
   repo gate (`./gradlew test`, which is green) and out of this ticket's
   scope (`concord/` is off-limits; fixing plan-doc headers is a docs
   maintenance task, not an M1-EVAL one) — flagging for the orchestrator to
   schedule.
4. **Worktree provisioning recurred** (see Environment note above) — the
   second consecutive milestone where worker branches were not cut from
   current main. Worth fixing in the harness before M2's workers are
   dispatched.
5. **`state.summary` rate**, carried from M1-BE: emitted per settled
   effective change with no coalescing, sharing `seq` with topology deltas.
   Correct per this ticket and never blocks the graph (bounded drop-oldest
   queue), but a hot cell produces a high frame rate; M3 introduces 1 Hz
   `flow.rates` batching and may want the same applied to `state.summary`.

### Orchestrator addendum (resolving M1's open questions before M2)

1. **Snapshot seam**: approved in principle
   (`ManagedHost.snapshotOf(ref): Serializable?` via `enqueueAwaiting`), but
   **not scheduled now** — it is a kernel edit outside every currently-planned
   ticket's declared scope, and the degraded behavior (`kind: "unavailable"`
   for snapshot-only cells, chiefly `ObserveCell` sinks) is not blocking M2+.
   Left as backlog: pick up opportunistically in a future ticket that already
   touches `ManagedHost`, or file it in `backlog/` if none does before M5.
2. **Contract updates (a)/(b)/(c)**: applied directly to `20-api-contract.md`
   — `POST .../observe` now documents its `409`; `Value`'s comment block now
   names `$opaque` (`{type, text}`) as a third reserved key and states that a
   tombstoned element is excluded from encoded state entirely (no tombstone
   row shape exists).
3. **`docLints` header gap**: acknowledged, deliberately not fixed now — it
   predates M0, is outside `./gradlew test` (the specified gate), and touching
   every file under this plan folder is unrelated churn mid-run. Tracked here
   for a documentation pass after M5.
4. **Worktree provisioning drift**: root cause is the isolation harness
   snapshotting a worktree base at dispatch time that can predate a
   just-merged milestone by the time the agent actually starts (both M0→M1
   worker sets exhibited it). Every worker so far detected and repaired it
   correctly (fast-forward or verbatim baseline copy) at some token cost. From
   M2 on, each ticket prompt explicitly tells workers to check for this and
   `git reset --hard main` first if their worktree predates the prior
   milestone's merge, rather than discovering it mid-ticket.
5. **`state.summary` coalescing**: noted for M3; no action needed at M2.


---

## M2 — Errors (evaluated 2026-07-28)

Merged: `worktree-wf_6329fc15-cdf-1` (M2-BE, `9abf6b4`) +
`worktree-wf_6329fc15-cdf-2` (M2-FE, `960f7f7`) + one evaluator defect-fix
commit (`433930f`), as `inspector(M2): …`. Verdict: **accepted**; one small
defect fixed in the eval session, no bounce needed.

### Environment note

This evaluator's worktree was stale at start (`46d5a4a`, missing the entire
`97-inspector-plan/` folder — predated even M0). Fast-forwarded with
`git reset --hard main` before reading anything, per this milestone's own
instruction to check for exactly this. Both worker branches, by contrast,
were clean single commits already sitting on current `main` — no rebasing
needed on the merge side, and both merged with zero conflicts (`inspect/src/**`
and `inspect/ui/**` are still perfectly file-disjoint).

### Tests run

- `./gradlew :inspect:test --tests 'civictech.inspect.InspectorErrorsTest'
  --tests 'civictech.inspect.RingBufferTest'` — 12/12 new BE tests PASSED.
- `./gradlew :inspect:test` (full module, 63 tests) — all PASSED, no
  regressions in any M0/M1 suite.
- `npm test` in `inspect/ui/` — 96/96 PASSED (95 from the FE worker + 1 new
  store test added by this evaluation for the `cause: null` fix below).
- `npm run typecheck` — clean. `npm run build` — clean production build;
  `dist/` removed after, not committed.
- `./gradlew test` (repo-wide gate, post-merge, post-fix) — **BUILD
  SUCCESSFUL**: kernel, gen, wire, concord (`:concord:test` UP-TO-DATE, no
  edits under `concord/`), testkit, every demo module including
  `:demo:skillmatch:test`.

### Live vertical smoke (full stack, real server + real UI)

skillmatch's own public HTTP surface validates its inputs before ever
reaching the kernel (confirmed by both workers), so it cannot itself induce
a dead letter, restart, or park — there is no error-induction recipe against
the demo binary as shipped. Rather than settle for BE's server-only curl
transcript and FE's mock-server screenshot (each already individually
sufficient per their tickets' own allowances), this evaluation built one
additional real, disposable harness: a temporary JUnit test
(`ScratchLiveHarness.kt`, deleted before the final commit — never part of the
diff) that boots a real `InspectorServer` on a real `ManagedHost`, induces a
genuine unknown-port dead letter (drop, `cause: null`), a genuine thrown-
exception dead letter + supervision RESTART, and genuine parked traffic
(`registry.hold` + queued calls), then blocks. Pointed the real (built, not
mocked) `inspect/ui` dev server at it via `INSPECT_BACKEND` and drove it with
the browser tool:

- Header counter strip read "2 dead · 2 parked · 1 restarts", exactly
  matching a concurrent `curl /api/inspect/errors`.
- Errors toggle on: the cell with a real thrown-exception dead letter got a
  red badge ("1") and a red-tinted border; toggle off cleared both instantly,
  canvas structure untouched (no re-layout) — confirmed by the accessibility
  tree, not just a screenshot.
- Detail panel Errors subsection: dead-letter card showed cause
  (`IllegalStateException`) bold, description, "—" for the null wave stamp,
  and a real timestamp; a separate cell's parked row showed
  `inlet · 2 parked · oldest ~33s ago`. The subsection stayed visible with
  the toggle off (correct — only the canvas overlay is toggle-gated per the
  ticket's own wording, the detail panel is not).
- Live-confirmed BE's own documented deviation #5 firsthand: the induced
  RESTART bumped `generationOf` before the poller's first tick ever observed
  that ref, so `counters.restarts` read `1` (true total, off
  `supervisionAccounting()`) while `restarts[]` stayed empty (first-sight
  seeding, no fabricated history) — both server and client reported this
  identically, confirming the FE store didn't paper over or diverge from the
  server's own documented approximation.
- The host-level routerInlet dead letter (drop, no target cell) correctly
  produced no canvas badge on any cell — its `ref` falls back to the host's
  own ref per BE's documented deviation #2, and the host itself is not a
  topology node, so there is nothing to badge. Expected, not a defect.

### Defects found / fixed

1. **`DeadLetterEntry.cause` typed non-nullable in the FE, but the server
   legitimately sends `null`** (a drop — unknown target, no thrown
   exception to name; verified live above and exercised by BE's own
   `InspectorErrorsTest`'s first test). `20-api-contract.md`'s DTO block
   doesn't spell out nullability in its illustrative example, but the real
   server behavior (and BE's own `DeadLetterRow.cause: String? = null`) is
   unambiguous. Not a crash (Solid renders `null` as nothing), but a real
   type-conformance gap and a cosmetic one — the dead-letter card rendered
   an empty bold-red line for a drop instead of anything meaningful. Fixed
   directly: `cause: string | null` in `src/api/types.ts`, a
   `dl.cause ?? 'dropped (unknown target)'` fallback in
   `DetailPanel.tsx`, and a new `ErrorStore` test locking in the null-cause
   path (`inspect/ui/test/errorStore.test.ts`). Commit `433930f`.

No structural defects — neither worker's output needed rework. No weakened
tests (the one pre-existing test edit, `client.test.ts`'s
`error.deadLetter` → `flow.rates` future-kind swap, was FE's own required
correction once `error.deadLetter` became a recognized M2 kind — verified
correct, not loosened). No gap/consistency markers touched. No files outside
`inspect/src/**` / `inspect/ui/**`. No `concord/` edits.

### No-consumption invariant (M2-EVAL check 1)

Verified by code review: `Errors.kt`'s only attachment to
`ManagedHost.deadLetterOutlet` is `.tap(Use.fixed(...))` — confirmed against
`FanOutlet.tap`'s source that a non-`Linked` `Use.fixed` target always takes
the unnegotiated fallback (`putTap` directly, `LinkRole.Observe`), never the
negotiated-handshake branch and never `subscribe`/consuming. `grep`'d the
full BE diff for any retained `DeadLetter`/`HostedPortInvocation` reference
beyond `onDeadLetterReceived`'s conversion — none found; the function reads
`letter.invocation?.cellRef`, `letter.cause?.javaClass?.simpleName`,
`letter.description`, and one `Timestamp` field, builds a `DeadLetterRow` of
plain strings/primitives, and returns — no field of `Errors` holds a
`DeadLetter` or `HostedPortInvocation` anywhere. Confirmed live: a dead
letter carrying a payload never blocked or was double-consumed (parked/dead
letter counts moved independently and correctly through the smoke test
above).

### Contract conformance

`ErrorSnapshot`, `DeadLetterRow`/`Entry`, `ParkedRow`/`Entry`,
`RestartRow`/`Entry`, `error.deadLetter`/`error.parked`/`error.restart` all
match `20-api-contract.md` field-for-field, both in the Kotlin DTOs and the
TS types (after the one nullability fix above) — checked by direct field
comparison and by the live curl-vs-UI cross-check in the smoke test.

### Deviations from ticket or contract (all accepted, none new beyond what BE/FE already flagged)

Carried from the workers' own reports, verified correct by this evaluation
and not repeated in full here (see `worktree-wf_6329fc15-cdf-1` /
`-cdf-2`'s reports for detail): restart-ring-buffer cap reuse at 200,
dead-letter `ref` host-fallback for host-level failures, `cause` as the
exception's simple class name, `oldestMs` as inspector-side "first seen"
bookkeeping, and the first-poll-window restart-history under-report (all
five live-confirmed above where applicable). One new item, `cause`
nullability, is not a deviation but a genuine FE type-conformance defect,
fixed above.

### Open questions for M3

1. **`error.deadLetter`'s `cause` nullability should be written into
   `20-api-contract.md`'s `ErrorSnapshot` DTO block explicitly** (the
   illustrative example only shows the non-null case) — this evaluation
   fixed the FE-side symptom but, per this plan's own discipline, an
   evaluator does not unilaterally edit the contract file; flagging for the
   orchestrator the same way M1-EVAL flagged its own contract additions.
2. **`state.summary` coalescing** (carried from M1-EVAL, restated by
   M2-BE's own report): still relevant for M3's `flow.rates` 1 Hz batching
   design — no action needed at M2, but M3-BE should look at both together.
3. **`docLints` header gap** (carried from M1-EVAL): still unresolved,
   still out of every milestone's declared scope; still tracked for a
   documentation pass after M5.

---

## M3 — Flow (evaluated 2026-07-28)

Merged: `worktree-wf_633d4319-b71-1` (M3-BE, `668cb64` + `35ea34d`) +
`worktree-wf_633d4319-b71-2` (M3-FE, `d31dfde`) + three evaluator commits
(`f255d42`, `08e033f`, `b672a52`), as `inspector(M3): …`. Verdict:
**accepted**; two defects fixed in the eval session, no bounce needed. Both
worker branches sat on current `main` and merged with zero conflicts —
`inspect/src/**` and `inspect/ui/**` are still perfectly file-disjoint.

### Environment note

This evaluator's worktree was stale at start (`46d5a4a`, predating the whole
`97-inspector-plan/` folder — the same staleness M1-EVAL and M2-EVAL each hit,
and which both workers reported hitting too). `git reset --hard main` to
`4eed35a` before reading anything.

### Tests run

All results are fresh runs on the final tree.

| Suite | Result |
|---|---|
| `./gradlew :inspect:test` | 79 passed (15 new: `InspectorFlowTest` 10, `InspectorFlowStreamTest` 5) |
| `./gradlew :kernel:test --tests OutletAtTest` | 4 passed (new, added by this evaluation) |
| `./gradlew :demo:skillmatch:test` | passed |
| `npm test` (`inspect/ui/`) | 141 passed / 16 files (139 from FE + 2 added by this evaluation) |
| `npm run typecheck` | clean |
| `npm run build` | clean; `dist/` removed after, not committed |
| `./gradlew test --rerun-tasks` (repo gate) | **BUILD SUCCESSFUL** — every module re-executed from scratch, nothing from cache |

### Live verification (real server + real UI, not the mock)

Ran the real pilot (`:demo:skillmatch:installDist`, demo on 8395, inspector on
7093) with the real built `inspect/ui` proxied at it via `INSPECT_BACKEND`.

- **Contract conformance, byte-level.** `GET /api/inspect/topology` → 16 nodes
  / 18 edges, `fused: false` on all 18. A captured `flow.rates` frame matches
  `20-api-contract.md` §SSE field for field: envelope `{seq, kind, payload}`,
  payload `{window: 1000, edges: [{id, rate, lastWave: {source, counter},
  hop}]}`, `seq` strictly monotonic and shared with every other event kind.
- **Attribution (M3-EVAL item 4).** Under load: 17 of 18 edges reported, the
  18th correctly omitted for zero traffic; rates spanning 2–79 msg/s with
  busy edges proportionally higher than quiet ones (43 vs 18 in one window),
  hops 0–3, two distinct wave sources. Quiet edges stayed dark and carried the
  "no observed traffic" tooltip rather than a fabricated zero.
- **Viz never blocks (10-target-v3 §Constraints 6).** Measured, not asserted:
  300 ops with no SSE client = 1794 ops/s; with a deliberately *stalled* SSE
  client that opens `/events` and never reads a byte = 1803 ops/s; after it
  detached = 1898 ops/s. Identical within noise, with the live browser UI
  attached throughout.
- **Flow toggle.** Active edges tint amber with a midpoint rate pill and
  travelling pulses; toggling off clears the overlay with no re-layout. Rate
  bands landed well on real traffic — the observed 2–79 msg/s spread exercised
  all three of M3-FE's bands rather than saturating the top one, so its
  thresholds (5 / 25) are kept as validated rather than guessed.
- **Hover tooltips.** Verified on all 18 edges via the DOM:
  `jobSkills.outlet → required.inlet — wave c6d8c48e·1556 · hop 0 · 11.0/s`
  for active edges, `… — no observed traffic` for quiet ones.
- **Fused, end-to-end (M3-EVAL item 3).** The skillmatch pilot contains no
  fused edge — every one of its 18 producing endpoints is a real outlet — so a
  disposable harness (deleted before committing; never in the diff) booted a
  real `InspectorServer` on a graph carrying one genuine delegating
  pass-through beside one tapped edge. The real server answered `fused: true`
  on the pass-through and `false` on the tapped edge; the real UI rendered the
  fused edge as a thick static double-stroke with an italic "fused" pill, zero
  pulses, and the tooltip `relay.inlet → relayed.inlet — fused — no observable
  messages`, next to the tapped edge's `15.0/s` and its pulses. Over 12
  consecutive live windows the fused edge id appeared **0** times in
  `flow.rates` while the tapped edge appeared 12 — fused means unobserved, never
  a zero rate.

### Defects found / fixed

1. **The detail panel's per-port rate multiplied every OUT port by its
   fan-out** (`f255d42`). M3-BE attributes an outlet's emission count to *each*
   of its outgoing edges (broadcast — duplicated, never divided, and correctly
   so); M3-FE then summed a port's edges, following the M3-FE ticket's own
   wording "rate (sum of that port's edges)". Each side is defensible alone;
   composed, they over-report. Caught live and quantified rather than inferred:
   `jobSkills.outlet` genuinely emitting 6 msg/s across 5 edges was displayed as
   `30.0/s`, and at higher load 21 msg/s across 6 edges as `126.0/s`. Fixed by
   making the combination directional — an OUT port is one `FanOutlet`, so its
   edges are all readings of one counter and the reading is taken once (max,
   robust to an edge bound mid-window); an IN port's edges come from distinct
   producing outlets, so those genuinely add and still sum. Re-verified live:
   the same port now reads `21.0/s` against a raw feed showing `21.0` per edge.
   Two tests added, the misleading one rewritten to the corrected semantics.
2. **`fixtures/topology.json` went stale at `"fused": null`** (`08e033f`) —
   flagged by M3-BE itself as out of its owned files. The contract documents
   this fixture as a verbatim capture of the real pilot's
   `GET /api/inspect/topology`, and M0-EVAL reconciled it against the live
   server for exactly that reason. Re-captured (18 edges, `false` on all 18);
   only the `fused` field changed, so no other fixture-backed test moved.

Also added, not a defect: **a kernel-side test for `ManagedHost.outletAt`**
(`b672a52`). M3-BE covered its new kernel accessor only transitively through
`:inspect`; a new public kernel surface should be pinned where it lives, so its
contract is now tested directly — the outlet it resolves, plus the two nulls a
flow feed reads differently ("registered port but not an emission point" →
fused, versus "not hosted here" → unknown).

No structural defects; neither worker needed rework. No weakened tests — the
two pre-existing test edits in the workers' diffs were both verified as
necessary corrections (`InspectorTopologyTest`'s `fused` null→false, and
`client.test.ts` moving its "unrecognized future kind" placeholder from
`flow.rates` to `graphs.changed` now that `flow.rates` is understood). No
`concord/` edits, no generated/build output, no gap markers removed (`G-47` is
cited in new KDoc, not closed).

### Kernel-invariant audit

- **P2 — fast path (M3-EVAL item 1, bounce-level).** Read the handler line by
  line; M3-BE's stated cost is accurate. Per message per tapped outlet it does
  one reference comparison (screening `Object`'s own methods off the counting
  path, so a null return cannot NPE on `hashCode` unboxing), one
  `AtomicLong.incrementAndGet`, one `ThreadLocal` read, one volatile reference
  store. Zero allocations, zero locks, zero map lookups, nothing proportional to
  payload size. The collector's `lock` is never taken on the graph thread; the
  aggregation runs on the inspector's own pre-existing daemon scheduler, and
  `sample()` releases that lock *before* handing the batch to the model, so the
  two lock orders (model→collector on link, collector-then-model on sample) can
  never close a cycle. Around the handler sits the kernel's own pre-existing tap
  dispatch, unchanged — including `FanOutlet.call`'s `tapOrder.toList()`, which
  allocates on every emission whether or not a tap is installed, and the
  `disclosureFilter` + `method.invoke` spread. That dispatch cost is inherent to
  the seam the M3-BE ticket itself prescribed, not something this milestone
  introduced; `FanOutlet` semantics are untouched.
- **Ownership (item 2).** Confirmed against the source: the handler never reads
  or retains `args` — the only path that touches it is the `Object`-method
  screen, which compares `args?.firstOrNull()` by identity for `equals`. Taps
  attach via `Use.fixed`, which is not `Linked`, so `FanOutlet.tap` takes its
  unnegotiated path (`putTap`, `LinkRole.Observe`) — never `subscribe`, never
  consuming, and firing no `onLinked` hook, so the inspector's own attachments
  never appear as edges it then reports. The one retained object is the
  `MessageContext` the outlet had already built (`Timestamp`, `PortRef`,
  optional `ReBaselineNotice`/`TagFrontier`, `hop`) — verified to carry no
  payload reference. No `Owned`/`Leased` payload is consumed, copied, delayed
  or dropped.
- **Lifecycle (item 5).** Covered by tests and confirmed by review: unlink
  untaps through the topology hook, despawn untaps through the *unpublish* hook
  (despawn does not unlink), an outlet keeps its tap while any of its edges
  survives (refcounted per outlet), an inspector started against an
  already-wired graph taps what its startup sync adopts, and `close()` untaps
  everything so a stopped inspector leaves no handler on a live graph.
- **Kernel seam.** `ManagedHost.outletAt(PortRef)` (~6 lines) is **approved** as
  arbiter. It is not the route M3-BE's ticket sanctioned, and M3-BE flagged that
  correctly rather than taking it silently. The sanctioned fallback was measured
  and genuinely cannot work: a co-hosted chain runs as nested direct calls, so
  `enqueueHostedInvocation` sees only the ingress to the first cell and *zero*
  internal-edge traffic — in the pilot graph it would report nothing at all. The
  accessor is what `10-target-v3.md` §Constraints 5 explicitly permits ("small,
  explicitly-listed accessors, threaded through existing structures — never
  runtime reflection"): it resolves through the host's own `cells` map and
  `PortRegistry`, hands back only what any caller holding the cell object can
  already reach, and adds no runtime reflection. Now covered by its own kernel
  test.

### Deviations from ticket or contract

1. **`fused` means something narrower than the M3-BE ticket's premise — and
   this is the right call.** The ticket (and `10-target-v3.md` §Constraints 1)
   assumes "fused co-hosted chains compile to direct calls — there is no message
   to observe on a fused edge." That premise does not survive contact with the
   tap seam the same ticket prescribes: the tap sits on the emitting outlet,
   *upstream* of the decision to call directly or enqueue, so co-hosted and
   cross-host edges are observed identically. Taking the premise literally would
   mark every edge in a single-host graph fused and report no rates at all,
   making the whole feed vacuous. What genuinely has no observable message is an
   edge whose producing endpoint is not a `FanOutlet` — a delegating
   pass-through (spec 10/14 "chains of delegation MUST flatten", 20/21 §Fusion).
   So `true` = no emission point at the producing endpoint, `false` = tapped,
   `null` = producer not locally hosted. Verified end-to-end above. **This wants
   writing into `20-api-contract.md`'s `Edge.fused` line and
   `10-target-v3.md` §Constraints 1**, which still carry the old premise — an
   orchestrator edit, not a unilateral one (same discipline as M1-EVAL's
   `$opaque`/409 and M2-EVAL's `cause` nullability).
2. **`rate` unit.** The contract does not state one. The server emits
   messages/second (`count × 1000 / window`), which is exactly what M3-FE
   assumed and labels "N.n/s". Confirmed live — the two sides agree, so M3-FE's
   open question 1 is resolved, and the unit is worth writing into the contract.
3. **Fan-out attribution is duplicated, not divided** (M3-BE's documented
   choice, KDoc'd). Correct for the broadcast fan-out the kernel implements, and
   correct per-edge. It is only the *per-port aggregate* that had to change —
   see defect 1.
4. **`prefers-reduced-motion` not screenshotted.** M3-FE flagged this honestly
   and it stands: the sandboxed browser exposes no reduced-motion emulation, and
   a synthetic `change` dispatch cannot reach the app's own `MediaQueryList`
   instance. Verified instead at unit-test level (`pulsesToRender` returns 0 for
   every band) plus CSS review — the static-intensity styling is a plain
   `@media (prefers-reduced-motion: reduce)` block keyed on the `data-band`
   attribute, and that attribute was confirmed present in the live DOM, so the
   path is real and needs no JS to engage.
5. **M3-BE's kernel edit** — approved above rather than treated as a deviation
   to carry forward.

### Open questions for M4

1. **The canvas silently drops an edge whose endpoint port is not in the node's
   `ports` list.** Found while building the fused harness: a cell with no
   KSP descriptor reports `ports: []` and `color: null`, its topology edges name
   ports by raw ref id, and the canvas — having no port dot to anchor to — draws
   nothing at all. The edge is in the snapshot and simply never appears. Not an
   M3 defect (no such cell exists in the pilot, and both harness variants were
   mine), and pre-existing since M0, but M4's multi-graph navigator will render
   graphs nobody curated, so it is likely to bite there. A visible fallback
   (anchor to the node's edge, mark the port unknown) beats silent omission.
2. **Contract additions to fold in** (from Deviations 1 and 2): the M3 meaning
   of `Edge.fused`, `flow.rates.rate`'s unit, and the fact that `flow.rates` is
   the one feed with no paired snapshot/GET endpoint (M3-FE's open question 2 —
   currently documented only in its own code comment).
3. **`docLints` header gap** (carried from M1-EVAL and M2-EVAL, restated by
   M3-BE): `./gradlew :concord:check` is red on `main` with 23 fatal "Missing
   Status header" findings, every one of them on `97-inspector-plan/**` docs.
   Confirmed pre-existing and untouched by this milestone, whose only markdown
   changes are this log entry and `inspect/ui/README.md` (inside M3-FE's owned
   scope) — nothing under `doc/spec/` but this file. Still out of every declared
   scope; still tracked for a documentation pass after M5. Worth doing before
   M4 if any gate is ever wired to `:concord:check` rather than `:concord:test`.
4. **`state.summary` coalescing** (carried from M1/M2-EVAL): M3's `flow.rates`
   settled the batching question for its own feed with a 1 Hz snapshot-and-reset
   window that publishes even when quiet (so the client's decay rule can key on
   *received* windows). `state.summary` still has no equivalent; the flow window
   is the pattern to copy if it is ever revisited.

---

## M4 — Navigator (evaluated 2026-07-28)

Merged: `worktree-wf_85284fdb-eee-1` (M4-BE) + `worktree-wf_85284fdb-eee-2`
(M4-FE) + one evaluator commit, as `inspector(M4): …`. Verdict: **accepted**;
six defects fixed in the eval session, no bounce needed. Both worker diffs were
genuinely file-disjoint (`inspect/src/**` + `demo/skillmatch/**` vs
`inspect/ui/**`), so both merges were conflict-free.

### Tests run

| Suite | Result |
|---|---|
| `./gradlew :inspect:test` | 109 passed (108 from the workers + 1 evaluator test); `InspectorGraphsTest` 16, `InspectorSearchTest` 12 |
| `./gradlew :demo:skillmatch:test` | 5 passed, incl. the new two-graph pilot listing test |
| `npm test` (`inspect/ui/`) | 202 passed / 21 files (188 from M4-FE + 14 evaluator tests); `npm run typecheck` and `npm run build` clean |
| `./gradlew test --rerun-tasks` | 1087 tests, 0 failures, 0 errors — forced re-execution of every module, nothing from cache |

Live verification ran against a real two-graph pilot (skillmatch pipeline +
`SideGraph`, inspector on 7097) with the built UI served beside it, driven in a
browser. A throwaway JUnit harness in `demo/skillmatch/src/test` drove
merge/split/dead-letter on command; it was deleted before committing and is not
in the diff.

### Acceptance items verified live

1. **Component semantics.** Two disjoint pilot graphs listed as two cards
   (`g-098568c3…` "skillmatch" / 16 cells, `g-905f49b1…` unnamed / 2 cells).
   Linking `savedSearches.outlet → candSkills.deltaInlet` at runtime collapsed
   them to one card of 18 cells, keeping the min-uuid id, **without a reload** —
   `graphs.changed` → `GraphList` refetch → topology resync works end to end.
   Unlinking split them back to 16 + 2 with the original two ids. Id stability
   within a component's lifetime is pinned by `InspectorGraphsTest`.
2. **Naming honesty.** Only the explicitly anchored component shows
   "skillmatch"; the side graph renders its `g-…` id in italics behind a dashed
   border. No name is invented anywhere — `nameOf` returns null for a component
   holding no anchor.
3. **Scoping.** A dead letter injected into the *side* graph's `savedSearches`
   showed as `deadLetters: 1` on that card only; the skillmatch card stayed
   all-zero, while `GET /errors`' host-wide counter read 1. `?graph=` returned
   exactly the 2 nodes / 1 edge of the side component, sharing the unfiltered
   snapshot's `seq`.
4. **Navigation state.** The hash round-trips graph + ref + toggles
   (`#/g/<id>/<ref>/flow,state`); a forced reload restored the filtered graph,
   the selected cell's detail panel and exactly those toggles, with the first
   topology fetch already carrying `?graph=`. Thumbnail click-through preserved
   Flow/State. A `problems` hit opened the erring graph with **Errors forced
   on** and the offending cell badged.

Contract conformance was diffed endpoint by endpoint against
`20-api-contract.md`: `GraphList`, `SearchResult` (`name`, `problems`,
`data` → 501 with the verbatim body), `cost: null` on every 200, `?graph=`
scoping, and `Node.graph` on every node. No console errors throughout.

### Defects found and fixed (all by the evaluator)

1. **A vanished graph rendered as a blank canvas.** Merging the component the
   user was inside left "No cells reported yet." and a "—" title with no
   explanation — indistinguishable from a bug, and the one thing M4-EVAL asks
   the UI to be honest about. Now an explicit state naming what happened, with
   a way back (`nav/route.ts`'s pure `graphIsGone` + `app.tsx`'s `GraphGone`).
2. **Constellation thumbnails had no shared scale.** Each component's layout
   was stretched to fill its card, so the two-cell side graph's dots rendered
   roughly six times the diameter of the sixteen-cell pipeline's. Fixed with a
   viewBox floor (`viewBoxOf`).
3. **Both topology fixtures were stale at `"graph": null`** — a shape the M4
   server can no longer emit, since `Node.graph` is non-null for every
   published cell. Re-stamped with the id the heuristic yields for each
   (`topology.json`'s is exactly the id `graphs.json` already calls
   "skillmatch"), same treatment M3-EVAL gave `fused`.
4. **An M0-era assertion pinned `graph === null`** in `test/fixture.test.ts`,
   and would have kept the stale fixture honest-looking. Updated to the M4
   shape rather than deleted.
5. **Card accessible names were wrong.** A graph card's tooltip is also its
   accessible name, so every card announced itself as "0 restarts";
   constellation cards had no accessible name at all. Both now lead with the
   graph they open.
6. **`Graphs.describe` rendered one restart as "1 restarts"**, and
   `fixtures/search-problems.json` invented a `detail` format the server never
   produces. Both corrected, the former pinned by a direct unit test.

### Deviations from ticket or contract

- **`Node.graph` is now non-null for every published cell**, where the contract
  still reads "component id — null until M4". Accepted: an unlinked cell is a
  component of one, so there is no honest null left. Needs folding into the
  contract (below).
- **`graphs.changed` fires on more than merge/split** — on any membership
  change and on `nameGraph`. Strictly more informative for a client that
  refetches; accepted.
- **M4-FE's `TopologyClient` also force-refetches the topology snapshot** on
  `graphs.changed`, beyond the contract's "hint to refetch `GraphList`".
  Confirmed **necessary**, not redundant: verified live that a cell is
  published (stamped with its own singleton id) *before* the link that merges
  it, so a client applying deltas alone holds a stale `Node.graph` until it
  resyncs. Both workers independently reached this; it is the correct reading.
- **Behaviours the contract does not specify**, decided by M4-BE and accepted
  here: unknown `?graph=` → 200 with an empty snapshot (ids evaporate by
  design, so it is a race, not an error); blank `q` → no hits; absent `mode`
  → `name`; unknown `mode` → 400; `SearchHit.detail` content.
- **Per-graph health is bounded by M2's ring buffers** (cap 200 each), because
  `supervisionAccounting()` counters carry no cell attribution and cannot be
  split between components sharing a host. `GET /errors` remains the true
  total. Accepted and documented in `Graphs.health`.
- **No kernel change was needed or made.** The diff touches only
  `inspect/**` and `demo/skillmatch/**`; `kernel/`, `gen/`, `nature/`,
  `wire/`, `testkit/` and `concord/` are untouched, and no gap or consistency
  marker was removed. The component sweep is O(V+E), lazy, and runs only on
  HTTP/scheduler threads — never the per-message data path (P2) — and the M4
  endpoints subscribe to nothing and raise no attention (P6).

### Open questions for M5

1. **Contract additions to fold in** (carrying M3-EVAL's item 2 forward, still
   unfolded): `Edge.fused`'s M3 meaning is now in the contract, but M4 adds —
   `Node.graph` is never null after M4; `graphs.changed` invalidates the held
   `TopologySnapshot` as well as the `GraphList`; unknown `?graph=` → empty
   200; blank `q` → no hits; `mode` defaults to `name`; unknown `mode` → 400;
   and the easy client trap that `GraphSummary.lifecycle` is lowercase while
   `Node.lifecycle` is uppercase (both as specified — worth a note, not a
   change).
2. **The canvas still drops an edge whose endpoint port is not in the node's
   `ports` list** (M3-EVAL's open question 1). M3-EVAL predicted M4's navigator
   would expose it; it did not bite, because every cell in the pilot has a KSP
   descriptor. M5-COLD and M5-NET bring in refs this inspector did not spawn,
   which is the likelier trigger. Still an FE fix, still unscoped.
3. **Component identity vs replicas.** The id uses the logical `CellRef.id`, so
   two instances of one logical cell cannot flip it, but no genuinely
   replicated graph has been driven through the inspector. `ComponentIndex.sweep`
   requires *both* endpoints to be locally published for a link to connect them
   — that is the line M5-NET will need to revisit for mirrored/announced refs.
4. **`docLints` header gap is still red on `main`** (carried from M1/M2/M3):
   `./gradlew :concord:check` reports 23 fatal "Missing Status header"
   findings, all on `97-inspector-plan/**` docs. Untouched and unaffected —
   `./gradlew test` is green — but it bites the moment a gate is wired to
   `:concord:check`. This is the last milestone before M5's final acceptance;
   worth clearing first.
5. **`state.summary` coalescing** (carried from M1/M2/M3-EVAL): still no
   equivalent of `flow.rates`' 1 Hz publish-even-when-quiet window.

---

## M5 + whole-product acceptance (M5-EVAL, evaluated 2026-07-28)

Merged: `worktree-wf_468f847c-cfa-3` (M5-SEARCH + M5-COLD, 9 commits) +
`worktree-wf_468f847c-cfa-1` (M5-NET, 5 commits) into evaluator branch
`worktree-wf_468f847c-cfa-4`, plus four evaluator commits (merge-conflict
resolution, one cross-branch compile fix, the instrument-exclusion fix, one
a11y fix). Verdict: **accepted — the inspector v3 is delivered**; every
binding constraint holds on the merged system.

### Merge notes

Three textual conflicts (`InspectorModel.nodeOf` — NET's `net` resolution vs
COLD's lifecycle stamping; `InspectorServer` KDoc + test accessors — union;
`DetailPanel` StateSection — the cold gate now nests outside the remote gate)
plus one semantic conflict git could not see: COLD renamed `withGraph` →
`stamped` while NET's new `adopt()` still called `withGraph` (fixed in
`476d047`) — a peer-adopted node is now stamped with both `graph` and
`lifecycle` like every other emission path. The two branches otherwise
composed remarkably well: COLD's `Heat.UNHOSTED` (not cold, not readable)
already gave NET's mirrored refs the right answers everywhere — a pure-peer
component is never "cold", and a remote cell is search-skipped as `remote`,
never counted into `coldSkipped`.

### Tests run (final tree)

| Suite | Result |
|---|---|
| `./gradlew test --rerun-tasks` | BUILD SUCCESSFUL — **1125 tests, 0 failures, 0 errors**, all 66 tasks re-executed (kernel 702, concord 168, inspect 147, backlog-triage 34, gen 19, agora 19, exchange 14, skillmatch 5, shopping 4, shell 4, wire 4, slotfinder 3, tiering 2) |
| `./gradlew :concord:check` | green, `concord/` untouched (the 23 doc-lint findings were cleared on main by `c7d0cac` before this milestone) |
| `npm test` (`inspect/ui`) | 259 passed / 25 files; `npm run typecheck` and `npm run build` clean; `dist/` removed, not committed |

### Layer 1 — M5 vertical verification (all replayed live, not from reports)

**SEARCH** (skillmatch pilot, real dist): `mode=data&q=alice` → 9 real hits
with correct graph/ref/label/detail + cost `{cellsQueried: 16, coldSkipped:
2}` while the side graph was cold; blank `q` → `{0, 0}` and no reads;
zero-hit query still reports its full cost; `name`/`problems` unchanged with
`cost: null`; unknown mode → 400. In the browser: typing in Data mode issued
zero search requests, Enter issued exactly one; the cost line and the dashed
inert notice row render; hit click-through opens the graph, selects the cell,
and the State subsection shows the matching row. P6: verified live that an
open observation leaves the search's candidate count unchanged (the sink is
excluded), and the M1-EVAL leak-check battery is inherited in
`InspectorDataSearchTest`.

**COLD** (`--cold-graph` pilot): `GET /graphs` lists the side component
`"cold"`; its scoped topology serves 2 `SUSPENDED` nodes + 1 edge without
touching a cell; wake → 202 `{graph, hosts: 0, cells: 2}`, and an SSE capture
attached *before* the wake recorded exactly two `lifecycle: HOT` frames
followed by one `graphs.changed`, contiguous seq — the wake is logged.
Unknown id → 404; GET on the wake path → 404. In the browser: cold card
dimmed with ❄ + COLD pill; the cold screen shows the ghosted structure, the
ticket's verbatim notice, and "Wake to inspect"; **selecting a cell inside
the cold graph issued exactly one request (`GET /cell/{ref}`) — no observe
POST, no state GET** (network log); the State subsection explains rather than
erroring; the confirmation dialog carries the ticket's verbatim consequence
line plus the drained-host blast-radius line; confirming transitions to the
live canvas.

**NET** (two real JVMs over `:wire`, the checked-in shopping recipe, fresh
ports): jvm-a's inspector showed 20 nodes across nets `{jvm-a: 10,
peer-0ae324f9: 10}`; peer cells report `host: null`, `typeFqn: <unknown>`,
no color/ports; both directions symmetric (jvm-b saw `{jvm-b, peer-…}` and
named its own cells). `GET /graphs` listed the named `shopping` component as
`16 cells · 1 hosts · 2 nets` — the declared cross-boundary edges join the
two sides into one component. Remote-cell honesty: `GET /cell/{ref}` 200,
`GET state` → `unavailable`, `POST observe` → 409; the UI renders the exact
sentence "remote — state/flow/errors not available in this milestone" in
State/Flow/Errors and "not reported (remote)" + a `peer` tag in placement.
A declared cross-boundary edge carried a real rate (50 messages observed in
`flow.rates` while driving 50 adds); demo convergence verified in both
directions. Killing the peer JVM retracted its 12 cells and their mirrored
edges within a reconcile tick; the two declared edges survive by design (see
Arbitration); restarting the peer brought its cells back under a new
`peer-…` label, the documented reconnect-relabel. Nested hulls verified in
the browser: dashed `peer-… PEER` net hull, solid `shopping` process hull
inside `jvm-a`'s net hull; all five toggles on simultaneously with a remote
cell selected produced zero console errors.

One live-verification false alarm worth recording: an early SSE capture
(`curl -N -o file`) suggested `flow.rates` had gone silent on the pilots —
it was curl's stdio buffering delaying file writes past the traffic window.
A synchronous reader showed correct per-edge rates on both pilots. Flow is
healthy; nothing regressed.

### Defects found and fixed by the evaluator

1. **Cross-branch compile break** (`476d047`): NET's `adopt()` called the
   `withGraph` COLD had renamed to `stamped`. Caught by the first compile;
   fixed to stamp both world-derived fields.
2. **The selection flip-flop — fixed as recommended** (`87e15ba`; M5-COLD's
   open question 1, a pre-existing M1×M4 interaction handed to this ticket).
   The inspector's own `ObserveCell` sink joined the observed cell's
   component; a sink uuid sorting below the current minimum *renamed* the
   component (`g-<min uuid>`), kicking the client out of the graph it had
   just selected a cell in — release, revert, repeat. Fix: an instrument is
   not a subject. `Observations` registers the sink ref *before* spawn (the
   publish hook fires during spawn) and unregisters on release;
   `InspectorModel` filters instrument refs out of nodes, edges, and the
   component partition at every entry point (hooks, `sync`,
   `reconcilePeers`). DataSearch already excluded them; topology, `/graphs`,
   and the constellation now agree with it. Verified live: during an open
   observation the snapshot stayed 18 nodes / 19 edges, component ids and
   cell counts unchanged, the observation itself still working. One M1 test
   rewritten (not weakened — the sink is no longer a topology delta, so a
   real topology change supplies its mixed-kind seq-continuity case); one
   new `InspectorGraphsTest` case pins the exclusion with ff…-prefixed
   uuids a random sink uuid would displace.
3. **Search-hit rows had no accessible name** (`2dfc143`; flagged by
   M5-SEARCH, same class as M4-EVAL's card fix, confirmed live via the
   accessibility tree). `aria-label` = label + detail.

### Arbitration rulings (each flagged by a worker rather than taken silently)

1. **`InspectorServer.declareLink` — accepted.** Investigated the seams NET
   cited: `LinkAdmission.connect` resolves both endpoints in one host's
   `cells` map, so a cross-JVM stream is genuinely inexpressible as a
   `TopologyLink`, and `Peering.announceTo` mirrors only a peer's own local
   links. Without the annotation the pilot cannot form one component and
   §3's "replication edge visible" is unreachable. Same opt-in shape as
   M4's `nameGraph`; no contract change (an ordinary `Edge`).
2. **`Node.net` carries the configured local label — accepted.** The
   contract's own comment scopes `"local"` as "until M5"; a separate label
   field would have been a real contract addition for no gain. The default
   keeps M0–M4 output byte-identical (verified: skillmatch still emits
   `"local"`).
3. **Three kernel accessors (`snapshotOf`, `isSuspended`, `isDrained`) —
   approved** under `10-target-v3.md` §Constraints 5; see the kernel-diff
   table below. `snapshotOf` is the accessor M1-EVAL's orchestrator
   addendum already approved in principle, future-shaped so the caller owns
   the deadline.
4. **A declared edge survives its peer's disappearance — accepted.** The
   subscription genuinely still exists and still emits into the registry's
   park queue; retracting it would misreport the process. The client
   anchors what it can, and the edge reconnects on peer return.
5. **`coldSkipped` non-zero before M5-COLD merged (SEARCH deviation 3) —
   moot and correct**: COLD merged in the same milestone and widened the
   same predicate.
6. **Process hulls dashed → solid (NET deviation 3) — accepted**: that is
   what `10-target-v3.md`'s toggle table always specified; M1 shipped them
   dashed, which only mattered once a dashed net hull nested outside.
7. **FE remote gating is server-refusal, not client-suppression**: selecting
   a remote cell still issues `POST observe` (→ 409) and `GET state`
   (→ `unavailable`). Contract-sanctioned ("a client that ignores the 409
   still behaves correctly") and P6-safe (no observation is created), so
   accepted as-is; noted for symmetry with COLD's stricter client-side
   gate.

### Layer 2 — whole-product acceptance vs `10-target-v3.md`

| Clause | Verdict | Evidence |
|---|---|---|
| One canvas, no view tabs | **met** | one `Canvas.tsx`; everything else is an overlay |
| Five toggles, independent, any combination | **met** | all five enabled and exercised together live (two-JVM graph, remote cell selected, zero console errors); each gates only its overlay |
| Process hosts — solid hulls | **met** | solid since this milestone, per the target's own table |
| Network hosts — dashed hulls, nested | **met** | live two-JVM run; nesting invariant unit-tested (`net.test.ts`); cosmetic caveat: layout does not cluster by placement, so net hulls can overlap on screen while nesting stays correct — roadmap note |
| Flow — pulses/rates, fused never animated | **met** | M3 evidence carried; re-verified live this milestone (synchronous capture, declared edge at 50 msg/s) |
| Errors — badges, park pills | **met** | M2 evidence carried; header counters live in every session |
| State — per-cell chip | **met** | M1 evidence carried; chip only for observed cells |
| All-properties detail panel, stacked subsections | **met** | verified on local, remote, and cold cells — each subsection states what it honestly can |
| Lazy state subscription on selection, released on deselect | **met** | network-log verified again this milestone; the new instrument exclusion means observing no longer perturbs the topology being observed |
| Navigator: cards, constellation, cold dimmed | **met** | live: HOT/COLD pills, ❄, dimming, thumbnails |
| Search: name / problems / data | **met** | all three live; data is submit-only with visible cost |
| Selection/viewport/toggles persist while navigating | **met** | M4 evidence; hash round-trip re-verified |
| §Constraints 1 — P2 fast path | **met** | tap handler audited line-by-line (M3-EVAL) and unchanged; M5 added no per-message hook; `reconcilePeers`/lifecycle poll are 1 Hz metadata sweeps on the inspector's scheduler |
| §Constraints 2 — P6 observation causal | **met** | browsing/listing/search/coldness subscribe to nothing (leak-checks in `InspectorDataSearchTest` + `InspectorColdTest`); the one causal act is the confirmed wake button; data search reads via `snapshotOf`, which links/emits/subscribes nothing and moves no wave counter |
| §Constraints 3 — ownership, taps Borrowed-only | **met** | two tap sites total (dead letters, flow), both `Use.fixed`/Observe-role; the flow handler never reads args; no `Owned`/`Leased` consumed, copied, delayed, or dropped anywhere in `inspect/` |
| §Constraints 4 — per-cell consistency, F-5 accepted | **met** | every state view carries its own frontier; the footnote renders in the State and Flow subsections |
| §Constraints 5 — kernel transport-neutral, listed accessors only | **met** | the cumulative kernel diff is five read-only accessors, listed below; zero HTTP/JSON/transport types in `kernel/` |
| §Constraints 6 — viz never blocks | **met** | bounded drop-oldest SSE queues (M0), measured non-blocking under a stalled client (M3), search deadline abandons slow reads, wake calls are enqueued not awaited |

### The cumulative kernel diff (all of it, justified)

| File | Change | Milestone | Justification |
|---|---|---|---|
| `LocationRegistry.kt` | `describe(ref): Class<out Cell>?` + weakly-referenced `descriptions` map + defaulted `publish` param + removal on `unpublish`/`mirrorUnpublish` | M0 | descriptor lookup for topology; audited M0-EVAL |
| `ManagedHost.kt` | `outletAt(PortRef): FanOutlet<*>?` (~6 lines) | M3 | tap-seam resolution; approved + kernel-tested by M3-EVAL (`OutletAtTest`) |
| `ManagedHost.kt` | `snapshotOf(ref): CompletableFuture<Serializable?>` | M5-SEARCH | host-routed `Stateful.snapshot()` — the accessor M1's orchestrator addendum pre-approved; caller-owned deadline; completes null, never throws; P2/P6-clean |
| `ManagedHost.kt` | `isSuspended(ref)` + `suspendedCells` → `ConcurrentHashMap` | M5-SEARCH | tell a cone is parked *without touching it*; writers unchanged (scheduler thread), reader is the observer's thread |
| `ManagedHost.kt` | `isDrained` + `state` → `@Volatile` | M5-COLD | distinguish DRAINED from DRAINING so a wake never fires `resumeHost` at a still-draining host (whose own `require` would dead-letter it) |

Nothing else in `kernel/`, `gen/`, `nature/`, `wire/`, `testkit/`, or
`concord/` is touched by the entire inspector delivery. No gap or
consistency markers were removed at any point.

### Contract additions for the orchestrator to fold into `20-api-contract.md`

1. `POST /api/inspect/graph/{id}/wake` → 202 `{graph, hosts, cells}`; 404
   for an unknown id, other methods, or other sub-paths (M5-COLD).
2. `Node.net`: from M5, the configured local label (launcher `--net-name`,
   default `"local"`) for local cells; a stable per-connection `peer-…`
   label for peer-announced cells. `Node.host == null` ⟺ the cell is
   remote — the FE relies on this as the remote discriminator; worth one
   explicit sentence.
3. `Node.lifecycle` is genuinely two-valued from M5 (`SUSPENDED` covers
   both a suspended cell and a cell on a drained host);
   `GraphSummary.lifecycle` `"cold"` is live.
4. `SearchResult`: in data mode, `cost` is non-null on every response
   (including zero-hit); a hit with an empty `graph` is a closing notice,
   not navigable; `SearchHit.ref` is null on notices.
5. A cell with an open inspector observation no longer appears in topology:
   the inspector's own sinks are not subjects (this evaluation).
   App-created `ObserveCell`s are unaffected (the golden fixture's 16
   cells still include skillmatch's six).
6. Carried from M4-EVAL's list, still unfolded: `graphs.changed` fires on
   any membership change and on rename; unknown `?graph=` → empty 200;
   blank `q` → no hits; `mode` defaults to `name`; unknown `mode` → 400;
   the lowercase-vs-uppercase lifecycle note.

### Open items fed back to the roadmap

- **Graph identity (MRB-156)**: the min-uuid heuristic held through M5, and
  the instrument fix removed its worst artifact (self-inflicted renames).
  Still emergent and still unnamed for peers: a component spanning JVMs has
  one id per JVM-side view, and genuine same-logical-id replicas across a
  peer boundary remain undriven end to end (`ComponentIndex` now admits
  mirrored refs as vertices — M4-EVAL's line was revisited as predicted —
  but no replicated pilot exercises it). Membranes as naming boundary
  remain the real answer.
- **Inspect-without-attention + search cost model (MRB-157)**, what this
  build learned: (a) `StateRequest` is unusable for read-only instruments
  as long as `pullServe` replies mint waves from the producing outlet's
  counter (it perturbs replication watermarks; `CatchUp.kt` documents it) —
  any future cold-read or search protocol needs a wave-neutral reply path.
  (b) A cold graph's *structure* is genuinely free today; its *state* needs
  the checkpoint reader — the cold screen's "unavailable" is the honest
  boundary. (c) `snapshot()` is a whole-state copy; a bounded state read
  (cursor/limit) is the missing kernel primitive behind both search cost
  and big-cell state views.
- **E2 observation-edge alignment**: unchanged; every state view is
  per-cell stamped and the UI promises nothing cross-panel. The F-5
  footnote renders in the State and Flow subsections.
- **Peer identity across reconnects**: `PeerId` reaches only the transport
  ingress; a reconnect relabels the peer's hull (observed live:
  `peer-0ae324f9` → `peer-804f5917`). Stable cross-reconnect identity needs
  `PeerId` threaded to the registry — a peering-protocol change, now with a
  concrete consumer.
- **Kernel hook gaps the inspector papers over with 1 Hz polls** (three,
  all documented in code): `LocationRegistry.onPublish`/`onUnpublish`
  return no deregistration handle (disarmed-listener workaround);
  `unpublishRemotes`/`mirrorLink`/`mirrorUnlink` notify nobody
  (`reconcilePeers`); suspend/resume/drain have no lifecycle listener
  (`publishLifecycleChanges`). A `remoteRefs()` projection beside
  `localRefs()` would additionally close the catch-up discovery hole for
  isolated, never-linked remote cells.
- **`state.summary` coalescing** (carried since M1): still uncoalesced;
  `flow.rates`' publish-even-when-quiet window remains the pattern to copy.
- **Should `POST /cell/{ref}/observe` refuse a cold cell server-side?**
  (M5-COLD's question 3.) Today the gate is client-side only, verified
  live; a defence-in-depth 409 changes an M1 endpoint's contract. Left to
  the orchestrator with the other contract edits.
- **Cosmetics**: layout does not cluster by placement (net hulls can
  overlap while nesting correctly); remote endpoints show raw port uuids
  (descriptor metadata does not cross the wire — a peering-protocol
  change).

### Recommended next increments

1. **Per-message ticker / wave tracer** on the existing tap seam: the
   collector already holds the last `MessageContext` per outlet; a bounded
   ring of (wave, hop, port) tuples per tapped outlet would give a
   follow-one-wave debugging view with no new kernel surface.
2. **Remote state via the bridge once FU-1 lands**: `GET state` for a
   `host: null` cell currently answers `unavailable`; a scoped,
   wave-neutral pull across the bridge is the natural next NET increment.
3. **Journal time-travel**: `.durability`'s journals + the cold screen are
   the two halves of "inspect a graph that is not running"; MRB-157's
   checkpoint reader would light up the cold screen's missing preview.
4. **Bounded state reads** (cursor/limit on `Stateful`): removes the
   whole-copy caveat on both data search and large state views.
5. **Housekeeping for the orchestrator**: fold the contract list above
   into `20-api-contract.md`; strike the progress-log items now resolved
   (M3-EVAL question 1 / M4-EVAL question 2 — dropped edges, fixed by
   `cardAnchor`; M4-EVAL question 3 — partially, mirrored refs are now
   vertices).

---

## Orchestrator closing note (2026-07-28)

All six milestones (M0–M5) are merged to `main`. Housekeeping from the
closing report, done:

- Every contract addition listed above (wake endpoint, `Node.host`'s
  null-means-remote / `Node.net`'s peer-label semantics, `Node.lifecycle`'s
  drained-host meaning, `SearchResult.cost`'s always-non-null-in-data-mode
  guarantee, the empty-graph notice-hit convention, the instrument-exclusion
  guarantee) is folded into `20-api-contract.md`. `10-target-v3.md`'s "Known
  kernel gaps" section is re-trued against what actually shipped (MRB-156,
  MRB-157 — including the correction that content search does not use
  `StateRequest`, since it is wave-perturbing).
- `./gradlew :concord:docLints` is clear (every doc in this folder now
  carries the required `**Status**:` header) and `:concord:check` is green.
- Status headers across the folder now read `Implemented`.

Decisions on the remaining open items:

- **`POST /cell/{ref}/observe` refusing a cold cell server-side**: left as
  client-side-only, as delivered. The gate is verified live and P6 is not
  violated by it today; adding a defence-in-depth 409 changes an M1
  endpoint's contract for a case with no observed exploit path, and no
  ticket in this plan owns that edit. Backlog, not a defect.
- **Stable peer identity across a reconnect, layout clustering by
  placement, remote port names, per-cell `state.summary`/lifecycle push
  instead of poll, accessible names on any remaining unlabeled controls**:
  genuine, but each is either a peering-protocol change, a layout redesign,
  or new kernel surface outside every ticket's granted scope — left for
  the backlog rather than expanding M5 after whole-product acceptance.
- The five "Recommended next increments" above stand as the roadmap
  starting point for whatever comes after this plan.

The inspector v3 dashboard described in `10-target-v3.md` is delivered and
accepted. This orchestration run is complete.
