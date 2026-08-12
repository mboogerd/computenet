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
   `94-implementation-plan.md` is a historical wave decomposition, not the
   work list (its own header says so).
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
  (app-facing reads).
- `nature/`: descriptor/nature vocabulary shared by `:gen` (processor-time)
  and `:kernel` (runtime); `api` on `:kernel`, so descriptor types are
  intentionally transitive.
- `gen/`: KSP processors (`@Contract`, `@CellBase`, `@Key`, `@Protocol`) and
  descriptor/proxy generation. Generator behavior is part of the runtime
  contract; test diagnostics as well as generated output. `:kernel:compileKotlin`
  depends on `:gen:test`, so generator regressions surface as kernel compile
  failures.
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
- `inspect/` (`:inspect`): the Inspector backend — a read-only HTTP/SSE view
  of a host process's live dataflow graph (`doc/spec/90-roadmap/97-inspector-plan/`);
  consumes `:kernel` and `:demo:shell`, opt-in via `--inspect-port` on
  `demo/shopping` and `demo/skillmatch`. Its SolidJS/Vite frontend lives in
  `inspect/ui/` (npm, not Gradle), same as `demo/agora/ui`.
- `doc/spec/`: the normative design — foundations (`00`), programming model
  (`10`), dataflow semantics (`20`), execution (`30`), distribution (`40`),
  development/evolution (`50`), roadmap (`90`). Entry: `doc/spec/README.md`.
- `doc/` (rest): see the documentation map in `doc/ARCHITECTURE.md` §7.
  `doc/archive/{runs,frontend,adr}/` holds historical material, not guidance.
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

## Branches, PRs, and auto-merge

`main` is protected by a repository ruleset: changes land only through a pull
request, required status checks must pass (`build-test-fast`,
`build-test-serial`, `concord-full`, `ui-test`, `agora-ui-test`), history stays
linear, and the branch cannot be force-pushed or deleted. A direct push to
`main` is rejected — always branch.

**Auto-merge is enabled, and a workflow arms it on every PR** (`.github/workflows/auto-merge.yml`,
skipping drafts and forks). The practical consequence:

- **A ready PR merges itself** as soon as the required checks pass. Nobody
  clicks merge. Opening a PR non-draft, or running `gh pr ready`, is the
  decision to ship.
- **Keep work-in-progress in draft.** Draft is the only thing standing between
  an unfinished branch and `main`.
- **A push to an open ready PR can land within minutes.** Don't push a commit
  you are not willing to have merged.
- **Push everything before the checks go green.** Auto-merge will land the PR
  the moment they do, even if you are mid-sequence pushing follow-up commits —
  the squash captures only what was on the branch at that instant, and the rest
  is stranded and needs its own PR. (Observed 2026-08-08.)

The `auto-merge` job is `continue-on-error`, so a failure to arm auto-merge
never blocks the PR; it just means the merge has to be triggered manually.

### Marking a PR ready is the agent's call

We are aiming at continuous integration: an agent that is confident a PR is
done runs `gh pr ready` and lets it merge, without asking. No profile —
conservative included — requires a human sentence for that call, and the
"Agent Context Profiles" section below governs commit, push, rebase, manual
merge, and Dolt remote sync, not this.

Confidence means all three of:

- **No open design decisions remain** on the change — nothing in the PR or its
  bead is still a question someone has to answer.
- **No regressions.** Required checks are green and the change does not break
  functionality outside its own scope.
- **Not risky or hard to revert.** A wrong call here is a revert or a follow-up
  edit — not a migration, a wire-format change, or deleted data.

If any of the three is in doubt, leave the PR in draft and park the doubt for a
human (`.claude/skills/work/references/ask-human.md`), naming which criterion
is unsettled. Asking in your output instead is a dropped question. Draft stays
the default until all three hold; a green, finished PR left in draft for
someone to click is equally a dropped task.

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
`doc/archive/runs/ORCHESTRATION.md` is retired; recent runs used git worktrees
with the orchestrator merging — the discipline above applies either way.)

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:970c3bf2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   bd dolt push
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
