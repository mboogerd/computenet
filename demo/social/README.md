# demo/social

`:demo:social` is the LDBC-SNB social graph demo: four per-key
`KeyedCells<Long>` families (person, authored, forum, message) behind one
ingress API, built to exercise ComputeNet's cell model at a realistic
graph shape (epic `computenet-07k`, SOC1).

This file is the `[SOC1-VER-01]` coverage ledger: one row per functional
requirement id in epic `computenet-07k` §4 ("Functional requirements
(EARS)"), naming the test that checks it or, where none exists, the reason
it is uncheckable. It is the artifact the epic's exit audit reads to decide
whether every `[SOC1-*]` id has a named test or a recorded justification.

## Coverage table

| Requirement | Test class | Method | Note |
|---|---|---|---|
| `[SOC1-MOD-01]` | `ModuleDependencyTest` | `SOC1-MOD-01 no forbidden module is on the demo-social test runtime classpath` | also `SOC1-MOD-01 demo-social build file declares exactly kernel, demo-shell and testkit` |
| `[SOC1-MOD-02]` | `civictech.cell.architecture.DemoSurfaceAllowlistTest` (kernel module) | `application-surface main sources only import their allowed civictech-cell slice` | B20; external module, `kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt` |
| `[SOC1-MOD-03]` | `civictech.cell.architecture.DemoSurfaceAllowlistTest` (kernel module) | `application-surface main sources only import their allowed civictech-cell slice` | same allowlist test is the enforcement mechanism: an import needing `protocol`/`partition` would fail it, forcing the escalation this requirement describes; no `src/main` import currently needs either package |
| `[SOC1-HTTP-01]` | `SocialServerTest` | `SOC1-HTTP-01 serves slash, state and op through DemoShell` | |
| `[SOC1-HTTP-02]` | `SocialServerTest` | `SOC1-HTTP-02 state is byte-identical across repeated fetches and across two apps given the same ops` | |
| `[SOC1-HTTP-03]` | `SocialServerTest` | `SOC1-HTTP-03 the first SSE frame carries current state before any change frame` | |
| `[SOC1-HTTP-04]` | `SocialServerTest` | `SOC1-HTTP-04 a missing param, non-numeric id, unknown id or unknown action is 400 and never mutates state` | also `SocialJournalTest`'s `SOC1-HTTP-04 a rejected op leaves journalled state byte-identical` |
| `[SOC1-SCHEMA-01]` | `SocialSchemaTest` | `SOC1-SCHEMA-01 Person Knows Forum Message Like and Tag are Serializable and round-trip` | |
| `[SOC1-SCHEMA-02]` | `SocialSchemaTest` | `SOC1-SCHEMA-02 a big SNB id is preserved verbatim in keys and cell` | also `SOC1-SCHEMA-02 a big SNB id is preserved verbatim in state` (same class) and `SocialServerTest`'s `SOC1-SCHEMA-02 a big SNB id appears verbatim in state` |
| `[SOC1-SCHEMA-03]` | `SocialSchemaTest` | `SOC1-SCHEMA-03 knows adjacency lives in each owner's own cell, undirected, no cell holds it all` | |
| `[SOC1-SCHEMA-04]` | `SocialSchemaTest` | `SOC1-SCHEMA-04 addKnows writes both cells, removeKnows removes both` | |
| `[SOC1-SCHEMA-05]` | `SocialSchemaTest` | `SOC1-SCHEMA-05 the per-person family ref formula and cross-host determinism` | also `SOC1-SCHEMA-05 the per-person family ref is identical across two SocialApp instances` (same class) |
| `[SOC1-GEN-01]` | `SocialGeneratorTest` | `SOC1-GEN-01 two generators with the same seed and scale produce equal output` | |
| `[SOC1-GEN-02]` | `SocialGeneratorTest` | `SOC1-GEN-02 the knows relation is Zipf-skewed - top decile holds at least 40 percent of degree mass` | |
| `[SOC1-GEN-03]` | `SocialGeneratorTest` | `SOC1-GEN-03 updates are non-decreasing in creationDate with the D5 secondary key` | |
| `[SOC1-GEN-04]` | `SocialGeneratorTest` | `SocialLoader loads a generator's static slice into SocialGraph in D4 field order` | loader-interface half; the CSV-swappability half is `DatagenCsvSourceTest`'s `SOC1-GEN-04 datagen-mini fixture and a generator load through the same SocialLoader call` |
| `[SOC1-GEN-05]` | `SocialAppLoadTest` | `SOC1-GEN-05 loaded person count equals the source's staticSlice person count, and differs by scale` | |
| `[SOC1-GEN-06]` | `SocialGeneratorTest` | `SOC1-GEN-06 validateReferences throws on a hand-built dangling reference` | also `SOC1-GEN-06 the generator's own updates already ran the validator successfully` (same class) |
| `[SOC1-UPD-01]` | `SocialPipelineTest` | `SOC1-UPD-01 every IU arm is in the seed-42 stream and one sampled event per arm lands in the cells` | |
| `[SOC1-UPD-02]` | `SocialPipelineTest` | `SOC1-UPD-02 cells equal the batch fold after every event over a 20-seed sweep` | also `SOC1-UPD-02 UpdateStream neither reloads nor recomputes from the source` (same class) |
| `[SOC1-UPD-03]` | `SocialPipelineTest` | `SOC1-UPD-03 every IU7 comment's parent is created by the slice or an earlier event` | |
| `[SOC1-UPD-04]` | `SocialPipelineTest` | `SOC1-UPD-04 replaying an applied event leaves state and applied unchanged` | |
| `[SOC1-ATOM-01]` | `SocialAtomicityTest` | `SOC1-ATOM-01 F-22 one addPost lands as three waves, one per contributing outlet` | the OR-branch fired: the three writes carry three distinct wave ids on every one of the 20 swept seeds; also `SOC1-ATOM-01 the same addPost under ActorIngress drive lands as one wave` (the reactive-branch counter-experiment) |
| `[SOC1-ATOM-02]` | `SocialAtomicityTest` | `SOC1-ATOM-01 F-22 one addPost lands as three waves, one per contributing outlet` | same test: it is the finding-writing act this requirement calls for — the class KDoc records the divergence as finding `F-22` in `doc/demo-findings.md`, and no kernel wave-stamping change was made |
| `[SOC1-ATOM-03]` | `SocialAtomicityTest` | `SOC1-ATOM-03 a Consume-linked GlitchFreeCell releases nothing under plain ingress and one contiguous wave under drive` | checked on a test-side `GlitchFreeCell` per the class KDoc's "Limit of this file" note; the assertion is contiguity of the release, not "no torn state" in general |
| `[SOC1-SREAD-01]` | `SocialShortReadTest` | `SOC1-SREAD-01 IS1 to IS7 answer the example graph` | plus three sibling methods in the same class and two HTTP-half methods |
| `[SOC1-SREAD-02]` | `SocialShortReadTest` | `SOC1-SREAD-02 IS1 IS4 and IS5 each cost exactly one read of the owning cell` | plus three sibling methods in the same class |
| `[SOC1-SREAD-03]` | `SocialShortReadTest` | `SOC1-SREAD-03 an unknown id is Empty, costs no read and spawns no cell` | also the HTTP-half `SOC1-SREAD-03 HTTP half an unknown id is 200 found false and leaves state byte-identical` |
| `[SOC1-SREAD-04]` | `SocialReadRefusalTest` | `SOC1-SREAD-04 a refused ref is 503 with the reason name, does not leak to another ref, and never caches` | plus the `SINCE_UNSUPPORTED` boundary variant, the never-spawned `NOT_HOSTED` variant and the stuck-read `TIMEOUT` variant, all in the same class |
| `[SOC1-INT-01]` | `SocialInterestTest` | `SOC1-INT-01 scope is one sorted singleton range per friend, declared on the person ref, read once per pull` | |
| `[SOC1-INT-02]` | `SocialInterestTest` | `SOC1-INT-02 B12 adding a knows edge widens the next pull by exactly one read` | |
| `[SOC1-INT-03]` | `SocialInterestTest` | `SOC1-INT-03 B13 removing an edge stops the leg and hides earlier messages, re-adding resumes from the retained frontier` | |
| `[SOC1-INT-04]` | `SocialInterestTest` | `SOC1-INT-04 a spawner durably spawns an admitted-but-absent friend, which answers Empty at since = null` | |
| `[SOC1-INT-05]` | `SocialInterestTest` | `SOC1-INT-05 an empty knows set is the Empty scope - no leg read, empty board, never Total` | |
| `[SOC1-FEED-01]` | `SocialFeedScatterGatherTest` | `SOC1-FEED-01 one read per friend holding posts, refs the three authored refs, board the union of their pages` | |
| `[SOC1-FEED-02]` | `SocialFeedScatterGatherTest` | `SOC1-FEED-02 no cell holds Message elements from more than one author, and no single leg equals board` | |
| `[SOC1-FEED-03]` | `SocialFeedFrontierTest` | `SOC1-FEED-03 one retained frontier per answered leg and no frontier construction or per-source read in Feed kt` | |
| `[SOC1-FEED-04]` | `SocialFeedFrontierTest` | `SOC1-FEED-04 each leg is sent its own retained frontier and later posts on either leg arrive` | the merged-`since` trap, B7 |
| `[SOC1-FEED-05]` | `SocialFeedFrontierTest` | `SOC1-FEED-05 a MIGRATING leg is deferred without a frontier and later delivers everything it authored` | |
| `[SOC1-FEED-06]` | `SocialFeedFrontierTest` | `SOC1-FEED-06 PullReport has exactly one property, legs, and one own frontier per answered leg` | |
| `[SOC1-FEED-07]` | `SocialFeedFrontierTest` | `SOC1-FEED-07 a pull moves no wave state, no tap, no observed value and no dead letter` | |
| `[SOC1-FEED-08]` | `SocialFeedScatterGatherTest` | `SOC1-FEED-08 1000 unrelated persons, 20 of them posting, leave the leg count and refs unchanged` | |
| `[SOC1-FEED-09]` | `SocialFeedOrderTest` | `B10 board orders the accumulated feed creationDate desc then id desc and honors limit` | |
| `[SOC1-FEED-10]` | `SocialFeedOrderTest` | `Feed kt orders demo-side, never through a kernel operator` | |
| `[SOC1-CREAD-01]` | `SocialComplexReadTest` | `SOC1-CREAD-01 ic8 answers the example graph date desc id asc at 1 plus 2 plus 3 reads` | IC8; IC3 by `SOC1-CREAD-01 ic3 answers friends and friends-of-friends at 1 plus 2 plus 3 reads` and IC2 (feed) by `SOC1-CREAD-01 feed returns the D1 order for a viewer with two friends and five messages`, all in the same class, plus HTTP-surface siblings `SOC1-CREAD-01 replies returns 23 21 22 for the IC8 example graph rebuilt through the app` and `SOC1-CREAD-01 fof returns the IC3 example rows, and 400s on missing to or equal countries` |
| `[SOC1-CREAD-02]` | `SocialComplexReadTest` | `SOC1-CREAD-02 state contains the exact queries literal` | IC5, IC6, IC12 are not implemented — each is dropped to finding `F-23` in `doc/demo-findings.md` (`Queries.kt`'s own table); this test pins `/state`'s `"ic5":"finding"` etc. documentation of that fact |
| `[SOC1-CREAD-03]` | `SocialComplexReadTest` | `SOC1-CREAD-03 shortest ic1 ic13 ic14 are 404 and slash stays 200` | |
| `[SOC1-CREAD-04]` | `SocialComplexReadTest` | `SOC1-CREAD-04 Queries kt names none of the forbidden symbols` | also `SocialPipelineTest`'s `SOC1-CREAD-04 ic2, ic8 and ic3 equal the batch model after every event for three viewers over five seeds` |
| `[SOC1-DUR-01]` | `SocialCrashRestartTest` | `B16 an in-process restart equals the pre-drop snapshot and a fresh replay of the same prefix` | the on-disk-layout assertion (one root WAL, one non-empty `keys` log per family) runs inside the private `crashAfterPrefix` helper this test calls |
| `[SOC1-DUR-02]` | `SocialCrashRestartTest` | `SOC1-DUR-02 keys are live before the first replayed frame is delivered` | |
| `[SOC1-DUR-03]` | `SocialCrashRestartTest` | `B16 an in-process restart equals the pre-drop snapshot and a fresh replay of the same prefix` | |
| `[SOC1-DUR-04]` | `SocialCrashRestartTest` | `B17 a truncated keys file fails recovery loudly and binds no port` | |
| `[SOC1-PLC-01]` | `SocialScaleTest` | `SOC1-PLC-01 gated scale load reports cell count and per-cell footprint` | gated on the `SOCIAL_SCALE` environment variable (74yvm-D1); skipped by default, including in this task's own gate run |
| `[SOC1-PLC-02]` | uncheckable: the pressure verdict and finding-write are a human/task-level decision over `SocialScaleTest`'s printed JSON line, not an assertion the test itself makes | — | `SocialScaleTest`'s own KDoc states this directly: "This test does not evaluate the criterion or write a finding — it only prints the numbers a human or a sibling task reads to decide" (74yvm-D4); see `F-26` in `doc/demo-findings.md` |
| `[SOC1-FIND-01]` | uncheckable: procedural constraint on how `doc/demo-findings.md` is edited (append, cite G-ids, never hand-edit `91-gap-analysis.md`/`CONCORDANCE.md`) | — | not a `:demo:social` code property; followed by convention in the findings entries themselves (F-21..F-24, F-26) and enforced by review, not by a test in this module |
| `[SOC1-FIND-02]` | uncheckable: conditional finding-writing action (record a KAGG-R finding if a query is limited by a missing kernel operator), not itself a runtime assertion | — | the antecedent held for CREAD-02's IC5/IC6/IC12 and for feed ordering (`[SOC1-FEED-10]`); the resulting finding is `F-23` in `doc/demo-findings.md`, which `[SOC1-CREAD-02]`'s test pins the demo-visible half of |
| `[SOC1-FIND-03]` | `SocialShortReadTest` | `SOC1-FIND-03 ShortReads runs on a stub reader and stub locator with no host at all` | |
| `[SOC1-VER-01]` | this file (`demo/social/README.md`) | — | the coverage ledger itself |
| `[SOC1-VER-02]` | `SocialPipelineTest` | `SOC1-UPD-02 cells equal the batch fold after every event over a 20-seed sweep` | incremental-vs-batch equivalence over the 20-seed sweep, driven by `SimWorld`/`runToIdle` |
| `[SOC1-VER-03]` | this file, "Seed sweeps" section below | — | |
| `[SOC1-VER-04]` | this file, "Timing audit" section below | — | |

## `[SOC1-VER-03]` seed sweeps

Every seed-sweep call site under `demo/social/src/test` (7 total), each a
contiguous `LongRange` starting at 0:

- `SocialAtomicityTest.kt:225` — `forEachSeed(0L until 20L)` (`SOC1-ATOM-01 F-22 one addPost lands as three waves, one per contributing outlet`)
- `SocialAtomicityTest.kt:243` — `forEachSeed(0L until 20L)` (`SOC1-ATOM-01 the same addPost under ActorIngress drive lands as one wave`)
- `SocialAtomicityTest.kt:264` — `forEachSeed(0L until 20L)` (`SOC1-ATOM-03 a Consume-linked GlitchFreeCell releases nothing under plain ingress and one contiguous wave under drive`)
- `SocialPipelineTest.kt:52` — `forEachSeed(0L until 20L)` (`SOC1-UPD-02 cells equal the batch fold after every event over a 20-seed sweep`)
- `SocialPipelineTest.kt:83` — `forEachSeed(0L until 20L)` (`SOC1-UPD-03 every IU7 comment's parent is created by the slice or an earlier event`)
- `SocialPipelineTest.kt:113` — `forEachSeed(0L until 20L)` (`SOC1-UPD-04 replaying an applied event leaves state and applied unchanged`)
- `SocialPipelineTest.kt:196` — `forEachSeed(0L until 5L)` (`SOC1-CREAD-04 ic2, ic8 and ic3 equal the batch model after every event for three viewers over five seeds`)

Each range is contiguous and starts at 0; none omits a seed inside its range.

**Failing seeds retained by `SocialAtomicityTest`:** none of the 20 swept
seeds (`0` through `19`) is a *failing* seed distinguished from a passing
majority. Per the class KDoc, the `[SOC1-ATOM-01]` divergence (one `addPost`
lands as three distinct wave ids under plain ingress) is structural and holds
**uniformly on every one of the 20 seeds tested**, and the class documents
seed 0's timestamps verbatim as the representative case. `SOC1-ATOM-03`'s
release-contiguity property likewise holds across the same 20-seed sweep with
no exception recorded. `SocialPipelineTest`'s sweeps (`[SOC1-UPD-02..04]`,
`[SOC1-CREAD-04]`) record no failing seed either — its KDoc states the
standing policy ("a failing seed is a finding, never filtered") but no seed
has yet triggered it.

## `[SOC1-VER-04]` timing audit

Every match of `Thread.sleep(`, `System.nanoTime` and `System.currentTimeMillis`
under `demo/social/src/test`:

- `SocialCrashRestartTest.kt:203` — `Thread.sleep(delayMs)` inside
  `SlowDataScheduler.submit`, the test's own fault injector for
  `start waits for a slow replay to drain before it binds`. This is a delay
  the test *injects* into a data-band task's dispatch, not a wait the test
  performs on the system under test — it manufactures a slow replay so the
  test can observe that `start()` still blocks until the drain completes.
  Kept.
- `SocialReadRefusalTest.kt:209` and `:211` — a `System.nanoTime()` pair around
  the `/state` probe in
  `SOC1-SREAD-04 a stuck short read times out with 503 TIMEOUT and stalls the dispatcher`,
  asserting `stateElapsedMs >= (timeoutSeconds * 1000) - 100`. This is a
  **lower bound** on the app's own configured `shortReadTimeoutSeconds`
  contract, not an upper bound on scheduling: the measurement starts only
  after a `CountDownLatch` confirms the dispatcher thread has provably
  entered the stuck read (`dispatcherEnteredStuckRead.await(...)`), so a slow
  host can only push `stateElapsedMs` *higher*, never lower, and the
  assertion cannot flake from the host being slow — only from `/state`
  answering suspiciously early, which is the failure this test exists to
  catch. Kept; no test edit made.

Both sites are justified as non-scheduling-timing per `[SOC1-VER-04]`, so no
assertion was removed and `SocialReadRefusalTest.kt` was not modified —
`./gradlew :demo:social:test --tests 'civictech.demo.social.SocialReadRefusalTest' --rerun`
was not required by the acceptance criteria's conditional clause.
