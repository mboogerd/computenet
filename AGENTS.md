# ComputeNet contributor context

ComputeNet is an experimental Kotlin/JVM dataflow runtime built around cells,
typed ports, explicit links, message context, ownership-aware payloads, hosted
execution, distribution, replication, and graph evolution. The design is
specification-led: code should make the cited specification true, not invent a
parallel model from nearby implementation accidents.

## Start every task here

1. Read the complete assigned work item in
   `doc/spec/90-roadmap/94-implementation-plan.md`, including its dependencies,
   exclusions, certainty, tests, and cited spec sections.
2. Read every cited spec section in full. Use
   `doc/spec/00-foundations/03-glossary.md` when terminology is unclear and
   `doc/spec/90-roadmap/93-feature-interactions.md` for cross-feature decisions.
3. Inspect the current implementation and its closest tests before designing a
   change. Search by the named type, gap marker (`G-*`), consistency marker
   (`C-*`), and relevant protocol term.
4. Stay inside the assigned work item. Research-gated and explicitly excluded
   corners belong to `95-research-plan.md`; do not silently solve or broaden them.

The authority order is: the ticket's cited spec text, integrated decisions in
`93-feature-interactions.md`, existing tests/code, then older roadmap prose. If
these disagree, implement the decided spec and make the divergence explicit in
tests or the final report. Do not edit the implementation plan unless the task
explicitly asks for documentation maintenance.

## Repository map

- `kernel/`: the core cell model and runtime. Important packages include:
  - `civictech.cell.port`: ports, links, fan-in/fan-out, registries, protocols.
  - `civictech.cell.host`: `ManagedHost`, scheduling, location, supervision,
    dead letters, intake and lifecycle behavior.
  - `civictech.cell.data`: data cells, deltas, tags, joins, grouping, windows.
  - `civictech.cell.consistency`: glitch-free propagation/frontier machinery.
  - `civictech.cell.replication`: replication behavior.
  - `civictech.cell.wire`: transport-neutral codecs, bridge cells, peering.
  - `civictech.cell.graph` and `.membrane`: construction and composition.
- `gen/`: KSP processors and descriptor/proxy generation. Generator behavior is
  part of the runtime contract; test diagnostics as well as generated output.
- `gen-test/`: compile/generation fixtures. `:kernel:compileKotlin` depends on
  this module's tests, so generator failures may surface indirectly.
- `wire/`: the concrete WebSocket transport. Keep transport dependencies out of
  `kernel`; transport-neutral semantics stay behind the kernel bridge API.
- `demo/`: aggregate container of demo applications, each a leaf sub-module:
  - `demo/shopping/` (`:demo:shopping`): the collaborative shopping list;
    executable and multi-JVM integration/convergence tests.
  - `demo/agora/` (`:demo:agora`): the argumentation-graph application and
    higher-level semantic/invariant tests; use it to detect accidental API or
    behavior regressions.
  - `demo/slotfinder/`, `demo/skillmatch/`, `demo/tiering/`: incremental
    dataflow demos (set intersection, joins, score fusion) whose purpose is to
    showcase the operator suite and surface kernel gaps into
    `doc/demo-findings.md`.
- `doc/spec/`: normative design, organized as foundations (`00`), programming
  model (`10`), dataflow semantics (`20`), execution (`30`), distribution (`40`),
  development/evolution (`50`), and roadmap (`90`).
- `legacy/` and `runtime/`: historical/non-included material unless a ticket
  explicitly establishes relevance. They are not Gradle modules in
  `settings.gradle.kts`.

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
- Tests should assert semantic outcomes and failure-path accounting, not internal
  scheduling timing. Use bounded waits and existing simulation controls.

## Verification

Run the narrowest relevant test first, then expand in proportion to the change.
Typical commands:

```bash
./gradlew :kernel:test --tests 'fully.qualified.TestName'
./gradlew :gen:test :gen-test:test
./gradlew :wire:test
./gradlew :demo:shopping:test
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

## Headless orchestration contract

When running as a plan worker, the host orchestrator owns Git lifecycle. Do not
commit, merge, rebase, create/remove worktrees, switch branches, or edit
`.codex-orchestrator/`. Work only in the mounted worktree, leave a reviewable diff,
and return `completed` only after implementation and verification genuinely pass.
The host performs an independent full test gate, commit, rebase, and fast-forward
merge. A worker may inherit partial edits from an earlier failed attempt; inspect
and repair them rather than assuming a pristine checkout.

Workers in one wave start from the same prior-wave baseline and may run concurrently.
Avoid unrelated formatting and broad file churn so independently completed tickets
remain straightforward to integrate. Later-wave dependencies must be consumed from
the code actually merged into `main`, never recreated locally.
