# Architecture decisions

Durable record of architecture audits. Read before starting a new audit: the
marker sets the incremental baseline, and the declined list prevents
re-litigating settled questions.

This ledger is maintained by whatever process acts on it: resolving a finding
updates its Status; completing a body of work verifies the ledger's claims
against the result and appends a fresh audit marker.

The 2026-07-27 full audit predates this file; its record lives in
`doc/remediation/COVERAGE.md` (findings → tickets T01–T12) and remains
authoritative for its own deferred (⏸) and excluded (✖) rows.

---

## Audit 2026-07-28

**Marker:** `dcfbb33` · **Mode:** incremental from `742f7ca` (the 2026-07-27
full audit; delta = 168 commits, dominated by the new `:inspect` subsystem +
`inspect/ui`)
**Principles evaluated:** inspector boundary containment · kernel observation
purity · spec-led integrity · no parallel models · cohesion of delta hotspots ·
verification architecture
**Method:** 6 parallel principle auditors → dedupe/normalize → independent
adversarial refutation of every Critical/High. Full report and implementation
plan: `doc/remediation/AUDIT-2026-07-28.md`.

### Accepted

| # | Finding | Severity | Location | Solution | Status |
|---|---|---|---|---|---|
| A1 | CI has never gated: exactly one run in repo history, red (2 two-JVM exchange tests on the 2-core runner); the untracked-files guard step has never executed; three docs claim gates that never gated | Critical | `.github/workflows/ci.yml`, `ExchangeScaffoldTest` | **done** (T13): classified the two-JVM CI failures as runner contention, not a kernel/wire race, with strong empirical evidence; tagged multi-JVM tests; split `ci.yml`'s `build-test` into `build-test-fast`/`build-test-serial` | done |
| A2 | `WritePosture`/`SAFETY_PARK` deleted from code (T03) but still specified normatively; G-44 defers work on the deleted enum | High | `doc/spec/40-distribution/42-replication.md:333`, `91-gap-analysis.md:95` | **done** (T15): rewrote the `WritePosture`/`SAFETY_PARK` bullet in `42-replication.md` to the landed always-fenced behavior; minted `G-67` recording the deletion; reworded G-44's `SAFETY_PARK` clause | done |
| A3 | `:inspect` (largest delta module) absent from `doc/ARCHITECTURE.md`, `AGENTS.md`, `README.md` — defeats the mandated orientation path; enabled the ratchet gap | High | `doc/ARCHITECTURE.md`, `AGENTS.md` | **done** (T14): `:inspect` added to `doc/ARCHITECTURE.md`'s module graph/table, `AGENTS.md`'s repository map, and `README.md` (run instructions + CI badge); `ModuleInventoryTest.documentedExceptions` emptied (G2 recurrence-blocked) | done |
| B1 | `:inspect` outside all three T10 ratchets; imports `.proxy` unscanned | Medium | `DemoSurfaceAllowlistTest` | **done** — G1 widened the gate per-module | done |
| B2 | Kernel API forces `.proxy` coupling on out-of-kernel observers (`FanOutlet.tap` needs a dynamic proxy; `Remote.sink` type leak); untracked | Medium | `FanOutlet.kt:275`, `LocationRegistry.kt:30` | **T17 done**: `FanOutlet` gained a payload-agnostic `observe(ref, onEmit)` attachment sharing `tap`'s storage; `Flow.kt`'s `FlowCollector`/`TapSite` migrated off the `Proxy.fromClass` dynamic-proxy dance onto it (`grep -n "Proxy.fromClass" inspect/.../Flow.kt` empty). `Remote.sink`'s `.proxy` type-leak projection remains deliberately deferred, batched with the future `LocationRegistry`/`InstanceIndex`/`DeliveryHold` extraction — unchanged from the original plan; `DemoSurfaceAllowlistTest`'s `:inspect` `.proxy` entry is untouched by design | partial |
| B3 | `Observations.viewFor` closed world: 3 foldable cells blind today (`PresenceCountCell`, `MergeableGroupByCell`, `ShardCell`), package actively growing, 3 more cells scheduled in plan 96 | Medium | `Observations.kt:306-344` | **done** (T20): `Observations.viewFor` now folds `PresenceCountCell`/`MergeableGroupByCell`/`ShardCell`; `ObservationsCompletenessTest.knownBlind` emptied (G3 fully shrunk, verified empty) | done |
| B4 | Inspector semantics carry 0 requirement ids / 0 concord scenarios; subsystem invisible to the CONCORDANCE gap worklist (denominator honesty) | Medium | `97-inspector-plan/` | **done** (T15): `concord/corpus/DISPUTES.md` entry filed naming the five unspecified inspector semantics, the reason, and a revisit trigger | done |
| B5 | `inspect/ui`: 259 tests/typecheck run by no gate (EVAL control retired with the plan); 5 recorded fixture drifts in 6 milestones | Medium | `ci.yml`, `inspect/ui/fixtures/` | **done**: T20 added `FixtureContractTest` (strict-decodes every `inspect/ui/fixtures/*.json` against `Dto.kt`); T23 added standalone `ui-test`/`agora-ui-test` npm CI jobs (Node 22 pinned via `.nvmrc` + `package.json` engines) for `inspect/ui` and `demo/agora/ui` | done |
| B6 | `InspectorModel` accretion: 205→745 lines, edited by every milestone, one shipped semantic merge bug (`476d047`); all named next increments land in it | Medium | `InspectorModel.kt` | **T24 assessed, declined**: read T21's landed diff, located the post-T21 peer-reconciliation residue, and judged it too small/entangled to clear the ≥~60-line cohesive bar for a `PeerReconciler` extraction — recorded as declined in T24's completion report, per the ticket's own "either outcome satisfies this ticket" clause. `InspectorModel.kt` is 722 lines post-run (was 745) — T21's sweep retirement trimmed it, but the accretion concern is not closed; route future `:inspect` tickets to avoid pairwise editing, as the original solution already cautioned | partial |
| B7 | `InspectorServer` fuses DI root, hand-rolled routing (order/length-dependent prefix trap), 6 schedules, 11 test accessors | Medium | `InspectorServer.kt` | **done** (T24): shared path-segment helper for `serveGraph`/`serveCell`; one `Tick`-list-driven schedule replacing six inline `scheduleAtFixedRate` calls, collapsed into a single `tickAll()` test seam; one shared `labelFor` host/peer-label builder replacing three copies | done |
| B8 | `Access-Control-Allow-Origin: *` + wildcard bind + `POST …/wake` is a no-preflight simple request — any web page can resume suspended cells; the justifying KDoc ("read-only endpoints") was falsified by M5 | Medium | `InspectorServer.kt:637`, `DemoShell.kt:24` | **done** (T19): `DemoShell` gained an optional loopback-bind parameter (default unchanged); `InspectorServer` binds loopback by default; `POST /api/inspect/graph/{id}/wake` requires an `X-Inspector: 1` header (400 without it; fails closed at the browser's CORS preflight since no `OPTIONS` handler exists); falsified "read-only endpoints" KDoc corrected | done |
| B9 | `LocationRegistry` notification seam: `onPublish`/`onUnpublish` return no handle (listener leak, `attached`-flag workaround); 3 mutation paths notify nobody, forcing the 1 Hz O(V+E) shadow sweep | Medium | `LocationRegistry.kt:97-108` | **done** (T21): `onPublish`/`onUnpublish` now return `AutoCloseable`; a new any-scope `onTopology` hook pair fires from `link`/`unlink`/`mirrorLink`/`mirrorUnlink`; `InspectorModel`'s 1 Hz `reconcilePeers`/`discoverRemotes` sweep retired in favor of event-driven `retractDangling`. One retry at the escalation tier: the first pass leaked a duplicate gossip/shipping subscription on peer disconnect/reconnect (`Replication.kt`/`SingleWriterReplication.kt`, via `FanOutlet`/`StreamTo`'s non-deterministic `PortRef`), fixed at the root cause with reversion-tested regressions | done |
| B10 | `snapshotOf` routes through `HostScheduler.submit`; `SimulationController`'s queue is documented not-thread-safe — first deterministic inspector test corrupts it silently | Medium | `ManagedHost.kt:1054`, `SimulationController.kt:82-94` | **done** (T18): `SimulationController`'s scheduler queue is lock-guarded for foreign-thread submission (task execution stays outside the lock, determinism preserved); the submitted task checks `future.isCancelled` before calling `cell.snapshot()` | done |
| B11 | `FanOutlet` per-message: redundant `.toList()` copy of an already-COW list + per-subscriber map lookup (T04 residual on the hottest path) | Medium | `FanOutlet.kt:146-147` | **done** (T17): the emission loop's redundant `.toList()` copy removed; `tapOrder`/`consumerOrder` iterated directly | done |
| B12 | `DocLints` package pointers resolve at directory granularity — type-level moves pass green; 2 stale citations live now (`34-scheduling.md:8` AttentionPolicy, G-63 stale proposal) | Medium | `concord/.../DocLints.kt:70-91` | **done**: T15 fixed the 2 originally-named instances (`34-scheduling.md`'s `cell.control.AttentionPolicy` citation, G-63's proposal text past-tensed); T16's `checkPackagePointers` now resolves a citation's PascalCase type segment against real declarations, not just directory existence, surfacing 7 further genuinely-stale citations (`ProtocolSupport`, `Link`, `PortDelegates`→`PortDelegateProvider`, `Magnitude`, the invented `Outlet`→`FanOutlet`, `RelationalGraphs`→its real join functions, `HostedCellProxy`), fixed in a same-wave orchestrator-dispatched follow-up (commit `7b7d73b`) | done |
| B13 | M5-NET socket path verified only by a hand-run two-JVM recipe; automated test covers loopback shape only | Medium | `InspectorNetTest.kt` | **done** (T22): new `TwoJvmInspectorTest` in `:demo:shopping` launches two real JVMs over the real `:wire` transport and asserts the inspector's M5-NET topology reporting matches the previously loopback-only-tested contract | done |
| B14 | `Canvas.tsx` FE accretion twin (203→509, every FE milestone; caused ticket serialization) | Medium | `inspect/ui/src/components/Canvas.tsx` | W6 optional: per-overlay components; gate on FE work actually being scheduled | planned |

### Declined

| Finding | Severity claimed | Reason declined |
|---|---|---|
| `snapshotOf` at band 0 preempts management; 50-wide fan-out starves data dispatch | High | Refuted: `DataSearch.read` blocks per read — at most one in-flight snapshot per search; snapshots are shallow copies; the cited `34-scheduling.md` §5 text constrains *attention banding*, is marked unimplemented, and band 0 already carries non-management work; sanctioned twice in the plan (`90-progress-log.md:359,1031`). Low residual (orphaned task after abandoned search on a pathological cell) folded into B10's cancellation check. |
| Search/state reads bypass `disclosureFilter` — second disclosure enforcement point | Medium | Refuted: `43-security.md:78` `LocalTrusted` sanctions in-host/same-registry crossings; `DataSearch` reads hot, locally-hosted cells only — no membrane is crossed; G-54 core is landed and its residual list does not include local observers. |
| `ValueEncoder.normalize` decodes only 3 of ~25 snapshot shapes | Medium | Refuted: `?: raw` fallback renders unknown shapes as their raw map — cosmetic fidelity on a mostly-unwired path, not unobservability. |
| `types.ts` `net: string\|null` diverges from `Dto.kt` non-null | High (as cited) | Refuted: wider type, every consumer coalesces defensively; the real defect is a stale comment. Free-ride fix in W4. |
| `ManagedHost` regrew / accessors made supervision extraction harder | — | Checked clean: growth is remediation KDoc minus T11 extractions; the four accessors are KDoc-heavy readers of genuinely host-private state. |
| `SingleWriterReplication` +95 added responsibility | — | Checked clean: T07-A brought shipping-link formation into agreement with `Replication`; T03 removed a concern. |
| UI god store | — | Checked clean: 74-line event router, per-milestone modules; `Canvas.tsx` is the only FE accretion site (B14). |
| Concord `Driver` SPI observation verbs (`topologySnapshot`, `flowRates`, …) | — | Declined for now: real SPI cost, only pays off with a second inspector binding. Revisit trigger: a second binding, or the inspector becoming product surface. |
| Normative spec chapter for inspector semantics now | — | Declined in favor of the cheaper honest form (B4's DISPUTES entry + W4 executable contract tests). Same revisit trigger as above. |

Do not re-report these without new evidence. If the reason no longer holds,
say which part changed.

### Guardrails

**Added** (each file carries its own amendment header):

- **G1** — per-module `civictech.cell` surface allowlist (demo leaves + `:inspect`;
  `.proxy` allowed for `:inspect` only, documented as forced and shrinking) —
  `kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt`,
  from findings B1/B2. Green.
- **G2** — every `settings.gradle.kts` module appears in `doc/ARCHITECTURE.md`
  (shrinking exception seed: `:inspect`) —
  `kernel/src/test/kotlin/civictech/cell/architecture/ModuleInventoryTest.kt`,
  from finding A3. Green; `documentedExceptions` emptied by T14 (verified
  2026-07-28: `setOf<String>()`).
- **G3** — every `SetDelta`/`MapDelta`-emitting cell resolves an
  `Observations.viewFor` fold (reflective over generic signatures, no
  hand-list to rot; shrinking `knownBlind` seed of 3, each entry
  asserted-stale-on-fix) —
  `inspect/src/test/kotlin/civictech/inspect/ObservationsCompletenessTest.kt`,
  from finding B3. Green. Empirically found 2 blind cells beyond the audit's 1;
  `knownBlind` emptied by T20 (verified 2026-07-28: `emptySet<String>()`).

**Considered, not encoded:**

- Fixture ↔ `Dto.kt` strict-decode test — it is W4 implementation (a contract
  test, not a structure rule); encode when W4 lands.
- File-size / responsibility-count ratchets for `InspectorModel`/`Canvas` —
  judgment-based, generate exceptions faster than value.
- Lock-residency assertion ("no O(V+E) work under the model monitor") —
  heuristic, not mechanically checkable.
- Concord scenarios for inspector semantics — blocked on the declined Driver
  SPI extension; see Declined.
- `DocLints` type-level pointer resolution — a lint-code change (W3), not a
  new rule; once landed it *is* the guardrail for the B12/A2 class.

**Amended:**

- `DemoSurfaceAllowlistTest` (T10-B) generalized from a demo-only walk to the
  per-module table (G1). Approved as part of accepting this audit; the demo
  set is unchanged, `:inspect`'s set is seeded from its actual current
  surface.
- **G1 widened: `partition` added to `inspectCellPrefixes`** (2026-07-28,
  escalation decision during the remediation run, standing in for human
  approval per the orchestration plan's escalation clause). T20 closed the
  last of finding B3's three blind folds by adding `ShardCell::class.java` to
  `Observations.SET_OUTLETS`, which requires
  `import civictech.cell.partition.ShardCell` in
  `inspect/src/main/kotlin/civictech/inspect/Observations.kt`. The widening is
  accepted because the coupling is nominal, not behavioral: `ShardCell` is a
  plain `Cell` (`Cell, Stateful, Replicable<SetDelta<E>>, Partitioned`) with
  no generated `@CellBase` `ShardApi` marker to key on — unlike every other
  `SET_OUTLETS`/`MAP_OUTLETS` entry — so the fold table has no Api type
  available and must name the concrete class. The reference is a bare `Class`
  literal consumed by `isAssignableFrom` in `viewFor`; no
  `civictech.cell.partition` member is called, constructed, or subtyped.
  (T20's sibling entry `MergeableGroupByCell` needed no widening — it lives
  under the already-allowed `data` prefix.) Rejected alternatives:
  `Class.forName("civictech.cell.partition.ShardCell")` would satisfy the
  regex-based gate while leaving the coupling exactly as strong, and would
  additionally turn a class rename from a compile error into a silent refold
  regression — a loophole, not a fix; reverting the `ShardCell` entry would
  reopen the B3 blind spot that guardrail G3 exists to close. **Shrink
  trigger:** the day `ShardCell` gains a `@CellBase` Api marker interface (or
  B2's kernel-owned observe seam subsumes the fold table), drop `partition`
  from the set. Recorded in the test's class KDoc.
- **G1 widened: `replication` added to `demoCellPrefixes`** (2026-07-31, human
  approval via the 99-defects-engines-plan orchestrator). `V4-PILOT`
  (`doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V4-PILOT.md`) extends
  `demo/shopping` behind a `--replicate` flag to drive the first same-logical-id
  replicated pilot over a real socket; `demo/shopping/src/main/kotlin/civictech/demo/Main.kt`
  imports `civictech.cell.replication.Replication` to wire the registry
  `onPublish`/`onUnpublish` hooks the replica mesh is driven by. There is no
  narrower seam: `Replication` is the public entry point for exactly this
  capability, not an internal detail leaking through. Only `demo/shopping`
  exercises the import today. **Shrink trigger:** none identified — a
  `--replicate` pilot is expected to remain part of `demo/shopping` (per
  V4-PILOT's own report) until a later ticket extracts it into its own module,
  at which point the prefix moves with it rather than dropping.

### Remediation closed — 2026-07-28

**Marker:** `e7b913a` (merge of `ticket/T24`, wave 3, the last wave of this
run) · **Closes:** tickets T13–T24 against this audit's Accepted findings.
All Critical/High findings (A1–A3) and all Medium findings except B6 and B14
are **done**; B2 and B6 are **partial** (each has a deliberately deferred or
declined remainder, recorded in its own row above); B14 was not ticketed this
run and is unchanged. Guardrails G2 (`ModuleInventoryTest.documentedExceptions`)
and G3 (`ObservationsCompletenessTest.knownBlind`) are verified empty. The
repo-wide `./gradlew test` gate is green on `main` at this marker. Next
incremental audit baseline: `e7b913a`.
