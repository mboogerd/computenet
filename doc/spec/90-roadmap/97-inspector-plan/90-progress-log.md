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
