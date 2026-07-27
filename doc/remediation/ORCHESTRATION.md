# Remediation run — orchestration

**Status**: ready to execute. Derived from the 12-principle architecture audit
(2026-07-27, baseline `742f7ca`). Tickets live in `doc/remediation/tickets/`;
each is fully self-contained (problem, evidence, solution, tests,
verification). This file only tells the orchestrator how to run them.

## Agents

All tickets are sized for **Sonnet 5** general-purpose coding agents. No ticket
requires design decisions beyond what its file specifies; where a judgment call
is possible the ticket names the decision rule.

## Run discipline (applies to every ticket)

- Workers follow `AGENTS.md` §Multi-agent run discipline: orchestrator owns the
  Git lifecycle; workers leave a reviewable diff and report `completed` only
  after the ticket's verification commands genuinely pass.
- Concurrent workers share one git index on main when not using worktrees:
  commit **by pathspec only**, never `git add -A`, never amend (see project
  memory `shared-index-concurrent-worktrees`).
- A ticket's "Files touched" list is its write scope. Anything outside it is a
  scope violation — stop and report instead.
- Every ticket that invalidates a sentence of `doc/ARCHITECTURE.md`, `README.md`
  or `AGENTS.md` fixes that sentence in the same diff.

## Phase plan

### Phase 0 — foundations (3 agents in PARALLEL, each a FRESH session)

Disjoint write scopes; no coordination needed.

| Ticket | Scope summary | Agent/session |
|---|---|---|
| [T01](tickets/T01-repo-truth-ci-build.md) | Commit untracked load-bearing files; add CI; flip concord profile default; test timeouts; delete `:gen-test`; build-script dedupe. Gradle/CI/git only — no kernel code. | fresh |
| [T02](tickets/T02-docs-spec-integrity.md) | Fix lying spec headers; archive stale docs; doc lints into `:concord:check`; concordance denominator honesty; glossary repairs; new gap markers. Docs + concord lint code only. | fresh |
| [T03](tickets/T03-deadcode-encapsulation-sweep.md) | Delete dead knobs/handlers/policies (`SAFETY_PARK`, `admission`, `WaveScope`, `Broadcast`, `Throwing`, `Gate`, dead imports); tighten encapsulation (`private set`, `TopologyIndex`, `ParkQueue`, `ContractRegistry`). Kernel sources only — no build files. | fresh |

**Gate**: merge all three; `./gradlew test check` green; CI from T01 green on
the merged tree. Then start Phase 1.

### Phase 1 — kernel correctness (ONE agent, SEQUENTIAL, CONTINUE the same session T04 → T05 → T06)

These tickets all live in `ManagedHost` / `LocationRegistry` / `InletPolicy` /
the schedulers — the repo's #1 merge-conflict surface and its most
lock-order-sensitive code. Do **not** parallelize them and do **not** start
fresh sessions between them: the T04 agent builds exactly the mental model of
`dataLock` ordering and accounting invariants that T05 needs, and T06 writes
the tests that validate both. If the session dies mid-phase, the successor
must read all three tickets plus the accumulated diff before continuing.

| Order | Ticket | Scope summary |
|---|---|---|
| 1 | [T04](tickets/T04-concurrency-hotfixes.md) | Deadlock fix (deferred saturation announce), atomic registry `getOrPut`, concurrent host maps, WAL order = acceptance order, `Throwable` backstops + terminated flag, dead-letter dispatch via scheduler, coroutine context elements. |
| 2 | [T05](tickets/T05-failure-path-accounting.md) | `install()` drain-safety, one `emitOrAbsorb` helper replacing 5 divergent copies, `Admit` exclusive discharge + ack tripwire, loud partial recovery, despawn cold-inlet drain, loud `sliceTo` fallthrough. Assumes T03 merged (Gate deleted). |
| 3 | [T06](tickets/T06-real-scheduler-conformance-tests.md) | New multi-threaded conformance tests against the REAL schedulers. Expected to shake out residual issues from T04/T05 — budget an iteration loop; fixes to just-landed code are in scope. |

**Gate**: `./gradlew test check` + `:concord:test -Pconcord.profiles=core,dist,dur`
green. Then start Phase 2.

### Phase 2 — hardening & DX (up to 4 agents in PARALLEL, each a FRESH session)

Disjoint write scopes. T10 additionally depends on T03 being merged (its
ratchet baseline must not pin ghost imports) — satisfied by phase ordering.

| Ticket | Scope summary | Agent/session |
|---|---|---|
| [T07](tickets/T07-distribution-dedup.md) | Port the missing interest gate + unpublish cleanup into `SingleWriterReplication`; `QuorumSetCell` onto `AdvertisedLedger`; extract shared lattice folds. Note: `QuorumSetCell` was also touched by T05 (different method) — rebase on merged main. | fresh |
| [T08](tickets/T08-link-typecheck-dsl-dx.md) | Payload-type check in the link handshake (`Rejected` on mismatch); `lookupOrThrow`; result-returning `graph {}`; typed observe overloads; README example cleanup. | fresh |
| [T09](tickets/T09-ksp-diagnostics-cellbase.md) | Promote silent `@CellBase` warnings to errors + test every diagnostic path; give `@CellBase` its first real consumer (or document the ceiling honestly); extract `ContractProcessor` lints/tables. `:gen` + one demo — disjoint from all others. | fresh |
| [T10](tickets/T10-architecture-ratchets.md) | Executable boundary tests: concord import ban, demo import allowlist, kernel package-layering ratchet pinned to the current edge set. Test code only. | fresh |

**Gate**: full `./gradlew test check` on merged main; CI green.

## Sequencing constraints (the only hard ones)

1. T03 before T05 — Gate deletion moots two of T05's accounting fixes.
2. T03 before T10 — the layering ratchet baseline must reflect the cleaned
   import graph.
3. T01 before Phase 1 — so CI + full concord profiles exist to catch what the
   concurrency fixes shake loose.
4. T04 before T05 before T06 — same files, same session.
5. T05 before T07 — both touch `QuorumSetCell.kt` (different methods; T07
   rebases).

## Deferred by design (do NOT let agents drift into these)

Tracked, deliberate deferrals — each already has (or T02 files) a marker:

- **Catch-up unification** (`catchUpOnLinked` → `baselineTo`): blocked on
  decoupling replication's wave-counter read; design work, not a ticket here.
- **Instance-scoping the JVM-global registries** (retires `forkEvery 80`):
  L-effort touching dispatch/proxy/bind; T04 lands the safe mitigations only.
- **`ManagedHost` supervision extraction**: dedicated future single-agent
  session per the RS-8 discipline note; never inside these waves.
- **`Serve`/`Use` API normalization + `TypedCellHandle` narrowing**: batched
  into a future API pass so call sites churn once.
- **`93-feature-interactions.md` physical split**: T02 lands the index +
  honest header only.
- **Membrane as a type**: that is G-9; out of scope by the project's own rules.
