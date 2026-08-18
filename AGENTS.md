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
- `bench/` (`:bench`): the JMH benchmark harness module (BEN1). Three source
  sets: shared fixtures in `bench/src/main/kotlin` (`civictech.bench`),
  `@Benchmark` bodies in `bench/src/jmh/kotlin` (`civictech.bench.micro`),
  fast unit tests in `bench/src/test/kotlin`. Depends on `:kernel` and
  `:testkit` only, and is depended on by nothing. Benchmark *execution* is
  deliberately outside the build lifecycle — neither `:bench:jmhJar` nor
  `:bench:jmh` is reachable from `:bench:build`.
- `oracle/` (`:oracle`): batch-oracle differential tester over the operator
  algebra (ORA1, epic `computenet-4ru`). `civictech.oracle.bind.OperatorCatalog`
  binds a catalog id to a kernel `CellFactory` and an independent `ReferenceOp`
  together, and `civictech.oracle.model` may reference value/key/delta types
  but no `civictech.cell.data.op` type — that independence is what makes it a
  check on the implementation rather than a second copy of it. Deliberately a
  separate module rather than part of `:concord`; its own `ModuleDependencyTest`
  bars deps on `:concord`/`:wire`/`:inspect`/`:demo:*`. Depends on `:kernel`,
  `:testkit`.
- `wire/`: the concrete WebSocket transport. Keep transport dependencies out of
  `kernel`; transport-neutral semantics stay behind the kernel bridge API.
- `identity/` (`:identity`): JDK-only Ed25519 keypairs (JEP 339, no
  third-party crypto), a fail-closed file-backed key store with
  machine-distinguishable refusal reasons, and key-derived `PeerId`
  fingerprints implementing the kernel's `SignatureVerifier` seam (DSC1, epic
  `computenet-ssa`). Depends on `:kernel`; `:kernel` must not depend on it.
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
  - `demo/beadsmirror/` (`:demo:beadsmirror`): mirrors a bd/Dolt-backed beads
    workspace — polls the Dolt commit feed, projects it through kernel cells
    into a materialized OR-map fold, and serves the fold over `:demo:shell`'s
    HTTP/SSE; an opt-in two-node mode gossips deltas over `:wire`. Its e2e
    suite (`TwoNodeRigTest`, `TwoJvmMirrorTest`) is asserted as executed, not
    replayed, in CI's `build-test-fast` and serial lanes (computenet-3g6n,
    computenet-7em.5).
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
- Agent sessions in this repo run zsh, where `:` followed by `r`/`h`/`t`/`e`
  (and others) right after an unbraced parameter expansion is a history
  modifier, not literal path text — `:r` strips an extension. A hand-typed
  same-path bind mount, `docker run -v "$REPO:$REPO:ro" ...`, expands to a
  mount one character off from `$REPO` (the trailing `:ro` is read as `:r`
  applied to the second `$REPO`, plus a literal `o`), so `$REPO` is absent
  inside the container and anything rooted under it — a classpath, a script
  path — fails in a way that looks like an unrelated bug. Brace it:
  `-v "${REPO}:${REPO}:ro"`. See `scripts/flake-loop/run-linux-loop.sh` for a
  worked case (computenet-yj6/computenet-m3iy).

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

A filtered invocation like `--tests 'fully.qualified.TestName'` can print
`BUILD SUCCESSFUL` while running zero tests: Gradle's up-to-date checking
treats an unchanged test task as `UP-TO-DATE` and skips it, so a rerun of the
exact command above that produced real JUnit output can complete in under a
second with no test output at all — indistinguishable from a pass at a
glance. When you need proof a test actually executed (reviews, verifying a
fix is not a no-op), add `--rerun` to the specific test task:
`./gradlew :kernel:test --tests 'fully.qualified.TestName' --rerun`.
`--rerun` binds to the task immediately preceding it, not to the whole
command line, and does not force upstream tasks the named task depends on;
use `--rerun-tasks` for a repo-wide run.

If the change touches `.claude/skills/`, it is a skill change and the Gradle
gates say nothing about it. Run the rubric gate, which checks every skill
against Anthropic's skill-creator criteria (frontmatter, and the shape rules
its own script omits — the SKILL.md line budget and a table of contents on
long reference files):

```bash
ruby .claude/skills/remediate-friction/scripts/validate-skills.rb
```

Skill edits belong in the `remediate-friction` lane, which owns
`.claude/skills/` and carries this gate — including a one-off fix that
arrives outside the friction backlog. A skill edited outside that lane
silently skips the only check it has.

Before declaring completion:

- Add focused tests named by the work item, including its failure/recovery case.
- Run affected module tests and the repository-wide `./gradlew test` gate.
- Confirm the test task you care about executed rather than being reported
  `UP-TO-DATE` or `FROM-CACHE` — read Gradle's `N actionable tasks: X
  executed, Y from cache` line and, if in doubt, rerun with `--rerun`.
- Check that no generated/build output or unrelated files entered the diff.
- Review the diff against every sentence of the work item's `Implement`, `Depends`,
  exclusion, and `Test` clauses.
- Report exactly which tests ran and any remaining limitation that the ticket
  explicitly allows.

## Branches, PRs, and auto-merge

`main` is protected by a repository ruleset: changes land only through a pull
request, **six** required status checks must pass (`build-test-fast`,
`build-test-serial`, `concord-full`, `ui-test`, `agora-ui-test`,
`kernel-test`), history stays linear, and the branch cannot be force-pushed or
deleted. A direct push to `main` is rejected — always branch.

That list is the ruleset's, not folklore — read it rather than trusting this
paragraph if a check's status ever decides a ship:

```bash
GH_PAGER=cat gh api repos/mboogerd/computenet/rulesets/20149495 \
  | jq -r '.rules[]|select(.type=="required_status_checks")
           |.parameters.required_status_checks[].context'
```

`kernel-test` was missing here until 2026-08-17 while `gh pr checks` reported
it on every PR, so a session deciding whether a red check blocked had to guess
(computenet-4prd).

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
"Agent Context Profiles" section below governs commit, push, rebase and manual
merge, not this. (Dolt remote sync is likewise not the profile's call any more —
see "Syncing bead state is required, not optional" below.)

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

Within the /work skill's orchestrated flow, the two roles split: the feature
reviewer certifies (verdict + `metadata.review=passed`) and the orchestrator
runs `gh pr ready`, so the agent that certified a change is never also the
one that ships it.

### Syncing bead state is required, not optional

**Agents sync freely, by default, without asking.** `bd dolt pull && bd dolt
push` after a shared-surface bead write is ordinary session flow, not an
escalation. No profile — conservative included — requires a human sentence for
it, and no agent should stop, hand off, or report "pending your approval" for a
sync. Unsynced bead state is the failure mode this rule exists to prevent:
review and PR state never reaches the next session, other machines act on a
stale graph, and finished work gets redone. Sync is not a courtesy to the
remote; it is how two machines stay one workspace.

This section governs Dolt remote sync and **supersedes** the "Agent Context
Profiles" line below — "Do not run git commits, git pushes, or Dolt remote sync
unless explicitly asked" — for the sync half, in this file and in `CLAUDE.md`,
whose managed block carries the same sentence. Git commits and git pushes are
untouched by this section and still follow the profile; `gh pr ready` follows
"Marking a PR ready is the agent's call" above.

**The only restraint is redundancy.** Push because state needs to leave this
machine, not on a timer:

- If you already know more writes are coming and you will push again shortly,
  **batch them** and push once at the natural checkpoint. Two pushes where one
  would do is the waste to avoid — a round trip is seconds, but a push per
  close, per comment, or per commit is noise that buys nothing.
- Owned territory — items under an epic you claimed, items you claimed — still
  accumulates locally and rides out on the Finalize push (SKILL.md step 6).
- **Acquisitions and shared-surface writes push at the moment they happen**:
  claiming an epic, claiming an item in another epic, filing or upvoting under
  the SDLC epic, creating a shared anchor, stealing a stale claim. Their whole
  point is that the *other* machine cannot proceed until it can see them.

**Keep the bracket: `pull` → verify → write → `push`.** Never push without
pulling first. The pull is what stops a clobber and what makes a claim a lock
rather than a private note; skipping it is how two machines mint the same id or
both claim one epic. See `.claude/skills/work/references/claim-sync.md`.

Dispatched implementers and reviewers still **don't** push — not because they
need permission, but because the orchestrator serializes pushes and a subagent
push is exactly the redundant kind: concurrent pushes from parallel agents
contend, and their writes are already carried by the orchestrator's next
bracket.

This section is deliberately **outside** the `bd`-managed block below. A `bd`
regen rewrites that block from its own template and silently drops edits made
inside it, so the override has to live here to survive.

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

## Creating tickets under a shared epic

**Never hand-type `bd create --parent=<shared epic>`.** Tickets under a parent
this session does not own are created through
`.claude/skills/work/scripts/create-ticket.sh`, which creates *unparented* and
then re-parents.

The reason is a primary-key collision that destroys beads. `bd create --parent=X`
allocates the child id from `child_counters`, a **per-database** table
reconciled only at sync. Two machines filing under the same parent between
syncs read the same `last_child` and mint **the same id for different beads**
(measured 2026-08-14: from a common ancestor at `last_child=39` one machine
went to 45 and the other to 42, and `wpvy.40/.41/.42` each named two unrelated
items). The pull then aborts on `child_counters`, and the runbook's
last-write-wins resolution would destroy one bead of each pair. Creating
unparented yields a hash id and leaves the counter untouched; re-parenting
afterwards keeps that id. `computenet-wpvy.47` (2026-08-15) is what a
hand-typed create under the SDLC epic looks like after the fact — harmless
that time, unrecoverable the time the other machine mints the same id.

**Scope is shared parents only.** Breakdown children under an epic or feature
this session has *claimed* are exclusive by that claim, cannot collide, and
keep their readable dotted ids — `bd create --parent=` is correct there. Reads,
updates, claims and closes through `bd` are unaffected; only *create* draws
from the counter.

`.beads/hooks/pre-push` warns (never blocks) when this machine has recently
minted a dotted id under a parent it does not own — the `wpvy.47` signature.
The warning is a backstop, not the rule: by the time it fires the id exists and
cannot be changed. Modifying `bd` itself to close the manual path was
considered and rejected as out of scope (`computenet-azt`).

This section is deliberately **outside** the `bd`-managed block below, for the
same reason the sync section above is: a `bd` regen rewrites that block from
its own template and silently drops edits made inside it.

## Choosing work: bv (beads_viewer)

**`bv` is OPTIONAL and machine-specific — check it exists before relying on
it, and fall back without diagnosing:**

```bash
command -v bv >/dev/null || echo "bv absent — use bd ready / bd list and move on"
```

It is installed on some machines and not others (absent on `Anva@A0030`,
2026-08-16, where a session spent time working out why *the* documented entry
point was missing — computenet-j9ku). There is no install step here, so
absence is the expected state on a fresh machine, not a fault to fix. The
fallback is `bd ready --json` plus `.claude/skills/work/scripts/ready-in-epic.sh`
for epic scope (that path, not the repo-root `scripts/`, which has no such
file); you lose the graph ranking, not the ability to select work.

Work **selection and prioritization** starts with `bv`
(https://github.com/Dicklesworthstone/beads_viewer), a graph-aware triage
engine over the beads workspace. `bd` remains the single blessed CLI for
mutations — create, update, claim, close, dep. Where the managed Beads blocks
below say `bd ready` finds available work, read that as the *listing* command;
the *decision* of what to pick up comes from `bv`. Do not use `br`
(beads_rust); it is not installed here — `bd` is the mutation tool.

Rules:

- Use **only `--robot-*` flags**. Bare `bv` opens an interactive TUI that
  blocks an agent session.
- Run `bv` from the main repository checkout. It reads the passive export
  `.beads/issues.jsonl`, which `bd` keeps fresh on every mutation; worktrees
  have no export, so `bv` fails there by design.
- `bv` output embeds `br ...` claim/show commands. Translate them to `bd`
  (`bd update <id> --claim`, `bd show <id>`).
- **Check the export is fresh before trusting `bv`.** `bd` refuses to
  overwrite `.beads/issues.jsonl` when it holds a record the Dolt store no
  longer has — the state a *deliberately deleted* bead leaves behind — and
  then the export stops tracking the DB entirely while every `bd` command
  still reports success. The refusal is announced only as a side effect of an
  unrelated mutation, so a session that only reads never sees it, and `bv`
  silently ranks against hours-old state with the newest beads invisible
  (computenet-exb0; hit again 2026-08-17 when a dispatched reviewer created
  and deleted 7 throwaway beads to test `bd` behaviour). One line, before the
  triage:

  ```bash
  [ "$(bd list --all --limit 0 --json | sed -n '/^[[{]/,$p' \
        | jq '(if type=="array" then . else .issues end) | length')" \
    = "$(grep -c . .beads/issues.jsonl)" ] \
    && echo "export FRESH" \
    || echo "STALE export — bv is reading a frozen file; repair before triaging"
  ```

  Both sides are the same population — `bd list --all` and the exporter both
  exclude infra, template, gate and memory records by default, and every line
  of the export is one `"_type":"issue"` record — so the counts are
  comparable. A gap of one or two records seconds after a `bd` write is just
  the 60s export throttle, not the wedge: re-run it before repairing.

  **Repair by moving the stale export aside** and letting the next `bd`
  mutation re-export, or by filtering the deleted ids out of it. **Not** by
  `bd init --from-jsonl`, which the warning itself suggests: it re-imports
  from the stale file as a *re-init*, not a merge, resurrecting the beads
  that were deliberately deleted.
- Recommendations can include blocked or already-claimed work ranked by graph
  importance. Only `quick_ref.top_picks` and entries marked actionable are
  claimable; verify with `bd show <id>` before claiming.

Commands (verified on **one machine** 2026-08-12 at bv v0.18.0, and again on
`MacBoo` 2026-08-17 at the same version — a per-machine, per-version
observation, not a repo-wide guarantee):

```bash
bv --robot-triage     # THE entry point: ranked picks, quick wins, blockers, health
bv --robot-next       # single top pick only
bv --robot-plan       # parallel execution tracks (multi-agent scheduling)
bv --robot-alerts     # stale issues, blocking cascades
bv --robot-suggest    # hygiene: duplicates, missing deps
bv --robot-insights   # full graph metrics (PageRank, betweenness, cycles)
bv --robot-triage --graph-root <epic-id>   # scope triage to one epic's subgraph
```

(`--format toon` is documented upstream but unavailable here — it needs the
`tru` binary, which is not installed; bv falls back to JSON.)

Workflow: `bv --robot-triage` → verify with `bd show <id>` → `bd update <id>
--claim` → work → `bd close <id>`.

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

**Architecture in one line:** issues live in a local Dolt DB; sync uses a native Dolt remote on DoltHub (`sync.remote` in .beads/config.yaml); `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

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
   bd dolt push          # publication push at end of session (acquisition brackets may have synced earlier)
   git push
   git status
   ```
   That end-of-session `bd dolt push` is the session's *publication* sync.
   The governing principle (decided 2026-08-13, computenet-wpvy.3): **sync
   brackets acquisition, not writes; ownership makes writes free.** Writes
   inside owned territory — items under an epic the session claimed, items
   it claimed — stay local until the publication push; do not sync per
   close, per comment, or per commit there. Acquisitions and shared-surface
   writes — claiming an epic, claiming an item in another epic, filing or
   upvoting under the SDLC epic (`computenet-wpvy`) — are each bracketed
   pull → verify → write → push at the moment they happen. A round-trip is
   ~30s, so a handful per session is noise; the former exactly-two-syncs
   rule is retired as an invariant and survives only as the publication
   cadence. Any other round-trip — catch-up after a failed push, refreshing
   an idle machine — is `scripts/beads-nightly-sync.sh`, which **no
   scheduler currently runs**: a human invokes it or installs a schedule.
   See `doc/ops/beads-sync-runbook.md` (§0 for the sync policy and its
   history, §5 for the caller inventory, §8 for installing a schedule).
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

**Architecture in one line:** issues live in a local Dolt DB; sync uses a native Dolt remote on DoltHub (`sync.remote` in .beads/config.yaml); `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->
