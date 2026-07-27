# Remediation run — orchestration

**Status**: ready to execute. Derived from the 12-principle architecture audit
(2026-07-27, baseline `742f7ca`). Tickets live in `doc/remediation/tickets/`;
each is fully self-contained (problem, evidence, solution, tests,
verification). Finding→ticket traceability and conscious exclusions:
[COVERAGE.md](COVERAGE.md). This file only tells the orchestrator how to run
the tickets.

## Agents

All tickets are sized for **Sonnet 5** general-purpose coding agents. No
ticket requires design decisions beyond what its file specifies; where a
judgment call is possible the ticket names the decision rule.

## Run discipline (applies to every ticket)

- Workers follow `AGENTS.md` §Multi-agent run discipline: orchestrator owns
  the Git lifecycle; workers leave a reviewable diff and report `completed`
  only after the ticket's verification commands genuinely pass.
- Concurrent workers share one git index on main when not using worktrees:
  commit **by pathspec only**, never `git add -A`, never amend (project
  memory `shared-index-concurrent-worktrees`).
- A ticket's "Write scope" is its write boundary. Anything outside it is a
  scope violation — stop and report instead.
- Every ticket that invalidates a sentence of `doc/ARCHITECTURE.md`,
  `README.md`, or `AGENTS.md` fixes that sentence in the same diff.

## Phase plan

### Phase 0 — foundations (3 agents in PARALLEL, each a FRESH session)

Disjoint write scopes; no coordination needed. T01 owns all build files; T02
owns all docs + concord lints; T03 owns kernel/nature sources.

| Ticket | Scope summary | Session |
|---|---|---|
| [T01](tickets/T01-repo-truth-ci-build.md) | Commit untracked load-bearing files; CI workflow; concord profile default → all; test-timeout backstop; delete `:gen-test`; build-script dedupe + catalog hygiene. | fresh |
| [T02](tickets/T02-docs-spec-integrity.md) | Fix lying spec headers; archive stale doc strata; 3 doc lints into `:concord:check`; concordance denominator honesty; glossary repairs; new G-/C- markers. | fresh |
| [T03](tickets/T03-deadcode-encapsulation-sweep.md) | Delete silently-ignored knobs & dead handlers (`SAFETY_PARK`, `admission`, `WaveScope`, `Broadcast`, `Throwing`, `Gate`, 39 dead imports); tighten encapsulation (`private set`, `TopologyIndex`, `ParkQueue`, `ContractRegistry`). | fresh |

**Gate**: merge all three; `./gradlew test check` green; CI (from T01) green
on the merged tree.

### Phase 1 — kernel correctness (ONE agent, SEQUENTIAL, CONTINUE the same session T04 → T05 → T06)

All three live in `ManagedHost` / `LocationRegistry` / `InletPolicy` / the
schedulers — the #1 merge-conflict surface and the most lock-order-sensitive
code. Do **not** parallelize; do **not** start fresh sessions between them:
T04 builds exactly the `dataLock`/accounting mental model T05 needs, and T06
writes the tests that validate both. If the session dies mid-phase, the
successor reads all three tickets plus the accumulated diff before
continuing.

| Order | Ticket | Scope summary |
|---|---|---|
| 1 | [T04](tickets/T04-concurrency-hotfixes.md) | Deadlock fix (deferred saturation announce), atomic registry `getOrPut` + teardown reclaim, concurrent host maps, WAL order = acceptance order, `Throwable` backstops + terminated flag, dead-letter dispatch via scheduler, coroutine context elements. |
| 2 | [T05](tickets/T05-failure-path-accounting.md) | `install()` drain safety, one `emitOrAbsorb` helper (3 operators start acking), `Admit` exclusive discharge + ack tripwire, loud partial recovery, despawn cold-inlet drain, loud non-`Scoped` `sliceTo`, silent-void counters, file the per-link-ack residual. |
| 3 | [T06](tickets/T06-real-scheduler-conformance-tests.md) | Multi-threaded conformance tests against the REAL schedulers (FIFO under contention, two-writer durability, coroutine context, terminated-host loudness, wire-thread entry). Expected to shake out residuals — fixes to T04/T05 files are in scope. |

**Gate**: `./gradlew test check` +
`:concord:test -Pconcord.profiles=core,dist,dur` green.

### Phase 2 — hardening & DX (up to 5 agents in PARALLEL, each a FRESH session)

Near-disjoint write scopes. Known seams: T05 and T07 both touched
`QuorumSetCell.kt` (different methods — T07 rebases on merged main); T08 and
T12 both touch demo trees (T08 = `slotfinder` + README only; T12 = the other
demos' tests/utils — disjoint by assignment, listed in each ticket).

| Ticket | Scope summary | Session |
|---|---|---|
| [T07](tickets/T07-distribution-dedup.md) | SWR mesh: missing interest gate + unpublish cleanup (2 live divergences); promote the demo re-announce rule into `Peering`; `QuorumSetCell` onto `AdvertisedLedger`; shared lattice folds. | fresh |
| [T08](tickets/T08-link-typecheck-dsl-dx.md) | Payload-type check in the link handshake; typed observe overloads + checked `get`; `graph {}` result + `lookupOrThrow`; `ObservationSink` listener dispatch off the host thread; README cleanup. | fresh |
| [T09](tickets/T09-ksp-diagnostics-cellbase.md) | Move runtime vocabulary `:gen` → `:nature` (generator off the runtime classpath); `@CellBase` warn→error + test all diagnostics; first real `@CellBase` consumer (or ceiling documented); `ContractProcessor` lint/table extraction; delete consumer-less descriptor fields. | fresh |
| [T10](tickets/T10-architecture-ratchets.md) | Executable boundary ratchets: concord import ban, demo surface allowlist, kernel package-edge baseline. Test code only. | fresh |
| [T12](tickets/T12-test-scaffolding-demo-hygiene.md) | Budgeted `runToIdle`; hard-failing awaits + `HttpProbe` adoption; `forEachSeed` density reporting; wire smoke-test port race + injectable backoff; demo `esc`/arg-parse dedup (fixes a latent escaping bug). | fresh |

**Gate**: full `./gradlew test check` on merged main; CI green.

### Phase 3 — structural normalization (ONE agent, SOLO, fresh session)

| Ticket | Scope summary | Session |
|---|---|---|
| [T11](tickets/T11-structural-extractions.md) | `ManagedHost` clean extractions (`DirectedProtocolLink`, damping witness, `LinkAdmission`); `AttentionPolicy` → `.control` (kills a package cycle; ratchet baseline tightens); `replicaFrontier` → `.consistency` (FU-2 landing site); 15 raw-ctor port migrations; `WatermarkCell`/`WaveFrontier` self-documentation. | fresh, **nothing else running** |

Runs alone because it moves code inside the two highest-churn files
(`ManagedHost`, `Replication`) that Phases 1–2 just edited — it must sit on
fully merged main with the ratchets from T10 already guarding it.

**Gate**: full `./gradlew test check`; ratchet baseline updated in the same
diff.

## Sequencing constraints (the only hard ones)

1. T03 → T05 (Gate deletion moots two accounting fixes) and T03 → T10 (the
   ratchet baseline must not pin ghost imports).
2. T01 → Phase 1 (CI + full concord profiles must exist to catch what the
   concurrency fixes shake loose).
3. T04 → T05 → T06 — same files, same session.
4. T05 → T07 (`QuorumSetCell` overlap; T07 rebases).
5. Everything → T11 (solo pass on merged main).

## Deferred by design (do NOT let agents drift into these)

Full list with reasons in [COVERAGE.md](COVERAGE.md) §Deferred. Headlines:
catch-up unification via `baselineTo` (design work; divergence is marked
instead), instance-scoped registries (retires `forkEvery 80`; mitigations
landed in T03/T04), `ManagedHost` supervision extraction (dedicated future
session per the RS-8 discipline note), `Serve`/`Use` + `Consumer` +
`TypedCellHandle` API normalization (batched future API pass), the `93`
physical split (index landed instead), membrane-as-a-type (that is G-9).
