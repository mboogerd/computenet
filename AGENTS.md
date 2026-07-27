# ComputeNet contributor context

ComputeNet is an experimental Kotlin/JVM dataflow runtime built around cells,
typed ports, explicit links, message context, ownership-aware payloads, hosted
execution, distribution, replication, and graph evolution. The design is
specification-led: code should make the cited specification true, not invent a
parallel model from nearby implementation accidents.

Detailed architecture (module graph, kernel package map, KSP generation flow,
runtime lifecycle, concord, demos): `doc/ARCHITECTURE.md`.

## Start every task here

1. Read the complete assigned work item — tickets live in the file your
   assignment names. There is no single standing work queue; the live sources
   of work are `doc/spec/90-roadmap/96-incremental-engines-plan.md` (proposed
   E-items), `doc/spec/90-roadmap/95-research-plan.md` (research-gated scope),
   `doc/PERNODE-FOLLOWUP-TICKETS.md` (open FU items), `backlog/` (idea inbox),
   `doc/demo-findings.md`, and the `gap` rows of `doc/spec/CONCORDANCE.md`.
   `94-implementation-plan.md` is a historical wave decomposition — all its
   waves merged long ago; despite its header it is not the work list.
2. Read every cited spec section in full. Use
   `doc/spec/00-foundations/03-glossary.md` when terminology is unclear and
   `doc/spec/90-roadmap/93-feature-interactions.md` for cross-feature decisions.
3. Inspect the current implementation and its closest tests before designing a
   change. Search by the named type, gap marker (`G-*`), consistency marker
   (`C-*`), requirement id (`[NN-SLUG-nn]`), and relevant protocol term.
4. Stay inside the assigned work item. Research-gated and explicitly excluded
   corners belong to `95-research-plan.md`; do not silently solve or broaden
   them.

The authority order is: the ticket's cited spec text, integrated decisions in
`93-feature-interactions.md`, the concord corpus (`concord/corpus/` — the
executable arbiter of spec↔code agreement), existing tests/code, then older
roadmap prose. If these disagree, implement the decided spec and make the
divergence explicit in tests or the final report. A requirement that cannot be
checked honestly is filed in `concord/corpus/DISPUTES.md`, never weakened into
a passing scenario. Do not edit plan documents unless the task explicitly asks
for documentation maintenance.

## Repository map

- `kernel/`: the core cell model and runtime — all of `civictech.cell.*`.
  Package-by-package inventory in `doc/ARCHITECTURE.md` §2. Notable packages
  beyond the obvious: `.durability` (journals), `.evolve` (shadow/promotion),
  `.verify` (invariant cells), `.membrane` (composition), `.observe`
  (app-facing reads). `AttentionPolicy` lives in `.host`; the attention
  scheduler in `.control`.
- `nature/`: descriptor/nature vocabulary shared by `:gen` (processor-time)
  and `:kernel` (runtime); `api` on `:kernel`, so descriptor types are
  intentionally transitive.
- `gen/`: KSP processors (`@Contract`, `@CellBase`, `@Key`, `@Protocol`) and
  descriptor/proxy generation. Generator behavior is part of the runtime
  contract; test diagnostics as well as generated output.
- `gen-test/`: codegen fixture module (currently no sources — its build script
  runs KSP). `:kernel:compileKotlin` depends on this module's tests, so
  generator failures surface as kernel compile failures.
- `testkit/`: shared test scaffolding (`SimWorld`, `awaitUntil`, `HttpProbe`,
  `JvmPeer`) consumed as `testImplementation` by `:kernel` and every demo.
- `wire/`: the concrete WebSocket transport. Keep transport dependencies out of
  `kernel`; transport-neutral semantics stay behind the kernel bridge API.
- `concord/`: the executable specification — implementation-neutral conformance
  suite. YAML scenarios in `concord/corpus/` cover EARS requirement ids in
  `doc/spec/`; `concord/schema/*.md` are the authoring contracts (single-writer,
  schema-change-gated); `concord/corpus/DISPUTES.md` is the honesty ledger;
  `doc/spec/CONCORDANCE.md` is generated (`./gradlew :concord:concordance`).
  Only `civictech.concord.driver.kernel` may import `civictech.cell.*`.
- `demo/`: aggregate container of demo applications, each a leaf sub-module:
  - `demo/shell/` (`:demo:shell`): shared demo HTTP/SSE shell (`DemoShell`);
    depends only on `:kernel`; not an application.
  - `demo/shopping/`: collaborative shopping list; multi-JVM peering over
    `:wire`; convergence and crash-restart tests.
  - `demo/exchange/`: the composition probe — partitioned + replicated +
    durable + glitch-free in one graph; `ExchangeCompositionExitTest` is the
    repo's toughest property gate.
  - `demo/agora/`: argumentation-graph application and higher-level
    semantic/invariant tests; use it to detect accidental API or behavior
    regressions. Its SolidJS/Vite frontend lives in `demo/agora/ui/` (npm, not
    Gradle).
  - `demo/slotfinder/`, `demo/skillmatch/`, `demo/tiering/`,
    `demo/backlog-triage/`: incremental dataflow demos (quorum sets, joins,
    score fusion, ranking) that showcase the operator suite and surface kernel
    gaps into `doc/demo-findings.md`.
- `doc/spec/`: the normative design — foundations (`00`), programming model
  (`10`), dataflow semantics (`20`), execution (`30`), distribution (`40`),
  development/evolution (`50`), roadmap (`90`). Entry: `doc/spec/README.md`.
- `doc/` (rest): see the documentation map in `doc/ARCHITECTURE.md` §7. The
  `COMPOSITION-*`/`PERNODE-*`/`RESTRUCTURE-*`/`ORCHESTRATION` files are
  completed-run records, not guidance — they cite pre-restructure paths.
- `backlog/`: idea inbox, one file per prospective feature (some marked
  IMPLEMENTED/absorbed). `bugs/`: fixed-defect reports, inert.
- `legacy/` and `runtime/`: untracked directories containing only stale build
  output — no sources. Ignore them.

## Core invariants to protect

Treat these as system-wide constraints even when a ticket touches one seam:

- Cell identity and port identity remain explicit; linking and dispatch must not
  become implicit local-call shortcuts.
- Dispatch classes, direction, cardinality, ownership, topology ordering, and
  message-context rules are semantic contracts, not optimization hints.
- `Owned` and `Leased` values require explicit consume/release/discharge behavior;
  no failure, suppression, shadow, park, or dead-letter path may silently drop an
  exclusive payload.
- Wave/source/tag continuity and glitch-free completeness must survive lifecycle,
  linking, recovery, replication, and wire boundaries.
- In-process and remote paths should preserve the same observable semantics; wire
  code encodes the model rather than defining a weaker one.
- Generated descriptors/proxies are authoritative runtime metadata. Thread new
  descriptor fields through registry and consumers instead of recomputing them
  reflectively at runtime.
- Preserve deterministic simulation/generative tests. Do not replace a discovered
  failing seed with a friendlier seed.

## Implementation conventions

- Kotlin/JVM uses the Gradle wrapper and Java toolchain 21.
- Follow the existing package layout and nearby style. Prefer the smallest coherent
  change that realizes the full ticket; avoid opportunistic refactors across paths
  owned by other work items in the same wave.
- Search before adding a new abstraction. Several substrate types already exist in
  partial form because the roadmap extends landed milestones.
- Do not hand-edit KSP output under `build/generated/`; change `gen/` and its tests.
- Keep `kernel` transport-neutral. A new transport dependency belongs in a small
  transport module such as `wire`.
- Preserve binary/wire compatibility unless the cited spec explicitly requires a
  version or frame change. Prefer additive encoding where the spec permits it.
- Remove only the gap/consistency markers explicitly closed by the assigned item.
  Do not mark adjacent residuals complete.
- Keep `doc/spec/CONCORDANCE.md` regenerated, never hand-edited; a dangling
  `covers:` id or orphan scenario fails `./gradlew :concord:check`.
- Tests should assert semantic outcomes and failure-path accounting, not internal
  scheduling timing. Use bounded waits and existing simulation controls
  (`testkit`'s `SimWorld`/`awaitUntil`).

## Verification

Run the narrowest relevant test first, then expand in proportion to the change.
Typical commands:

```bash
./gradlew :kernel:test --tests 'fully.qualified.TestName'
./gradlew :gen:test
./gradlew :wire:test
./gradlew :concord:test                          # acceptance corpus (core, dist, dur — the default)
./gradlew :concord:test -Pconcord.profiles=core   # fast local loop, core only
./gradlew :demo:shopping:test
./gradlew :demo:exchange:test                    # composition exit gate
./gradlew :demo:agora:test
./gradlew test
```

Before declaring completion:

- Add focused tests named by the work item, including its failure/recovery case.
- Run affected module tests and the repository-wide `./gradlew test` gate.
- Check that no generated/build output or unrelated files entered the diff.
- Review the diff against every sentence of the work item's `Implement`, `Depends`,
  exclusion, and `Test` clauses.
- Report exactly which tests ran and any remaining limitation that the ticket
  explicitly allows.

## Multi-agent run discipline

When running as a plan worker, the host orchestrator owns Git lifecycle. Do not
commit, merge, rebase, create/remove worktrees, or switch branches unless your
assignment explicitly grants it. Work only in your assigned worktree, leave a
reviewable diff, and report `completed` only after implementation and
verification genuinely pass. A worker may inherit partial edits from an earlier
failed attempt; inspect and repair them rather than assuming a pristine
checkout.

Workers in one wave start from the same prior-wave baseline and may run
concurrently. Avoid unrelated formatting and broad file churn so independently
completed tickets remain straightforward to integrate. Later-wave dependencies
must be consumed from the code actually merged into `main`, never recreated
locally. (The Docker/Codex harness under `scripts/plan-orchestrator/` and
`doc/ORCHESTRATION.md` is retired; recent runs used git worktrees with the
orchestrator merging — the discipline above applies either way.)
