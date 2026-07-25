# Composition plan — orchestration state (live)

Live state for the COMPOSITION-TICKETS.md run. Keep current as tickets land.

- **Source of scope**: `doc/COMPOSITION-TICKETS.md` (Phases 1–3, tracks α β γ / δ ε / ζ).
- **Baseline**: `main` @ `b6e59ca` — full `./gradlew test` **BUILD SUCCESSFUL** (2026-07-25).
- **Model**: implementation + validation subagents both **Opus-5** (`claude-opus-5`).

## Roles & merge protocol

- **Host (this orchestrator)** owns the `main` ref. It is the only worktree with
  `main` checked out, so it performs the final `git merge --ff-only` ref bump. It
  creates per-ticket worktrees + branches and dispatches agents.
- **Impl agent** (one per ticket, or per *sequenced same-context chain* — the plan's
  CONTINUE bundles): works only inside its worktree, TDD, runs the gate under a
  watchdog timeout, commits to its branch. Never touches `main`.
- **Validation agent** (Opus-5, its own worktree cut from the impl branch): checks
  faithfulness (correctness + completeness vs ticket & cited spec), does touch-ups,
  runs the full gate, then **rebases its branch onto latest `main` and resolves any
  conflicts** so the branch is fast-forwardable. Reports READY. The host then does the
  trivial `merge --ff-only`. (Only the host can move the checked-out `main` ref; the
  validator owns the hard rebase/conflict work — the intent of "validator merges".)
- **Merge serialization**: separate worktrees have separate indices, so parallel
  *commits* are safe; only the `main` ref bump is serial, done one ticket at a time by
  the host. Concurrent session on `claude/ksp-kotlin-poet-cells-3cd908` also lives on
  `main`'s tree — commit by pathspec, never `--amend`, verify the tree after each merge.

## Worktrees / branches

- Parent dir: `/Users/merlijn/Documents/local-projects/computenet-comp-wt/`
- Impl:  `wt-<TICKET>`      on branch `comp/<TICKET>`      (e.g. `wt-CP-A1` / `comp/CP-A1`)
- Valid: `wt-<TICKET>-val`  on the same branch (cut after impl commits).

## Test discipline (mandatory)

Every gradle test run wrapped in a watchdog (macOS has no `timeout`): capture a
`jstack` dump before killing, `--no-daemon`, isolated `GRADLE_USER_HOME=$PWD/.gradle-home`.
Caps: narrow class ≤ 180s, full `./gradlew test` ≤ 400s (deadlock backstop, suite
normally ~5–30s). A cap hit = ticket failure → escalate with the dump.

## Bundling decision (CONTINUE chains → one impl agent)

The plan sequences some tickets strictly (same agent, same files, cannot parallelize).
These are bundled into one impl agent + one validation pass (validation still checks
each ticket's named test + controls):
- **α-tail** = CP-A2 → CP-A3 → CP-A4 (all edit `GlitchFree.kt`; "do NOT run beside").
- **δ-tail** = CP-D2 → CP-D3 → CP-D4 (interest substrate build-out).
- **ζ**      = CP-F1 → CP-F2 → CP-F3 (Phase 3 is one lane).
Spec-only tickets (CP-A1, CP-D1) stay standalone (fast, disjoint).

## Wave plan (dependency-ordered; parallel within a wave)

| Wave | Tickets (parallel) | Depends on | Notes |
|------|--------------------|-----------|-------|
| W1 | CP-A1(spec) ∥ CP-B1 ∥ CP-C1 | — | all FRESH, disjoint files. Merge C1 before A4. |
| W2 | α-tail(CP-A2→A3→A4) ∥ CP-B2 | W1 (A1,B1,C1 merged) | α=wire/glitchfree/host; β=repl/watermark. Disjoint. |
| W3 | CP-B3 (JOIN) | W2 (A4 + B2 merged) | settlement reads replica frontier. |
| W4 | CP-D1(spec) ∥ CP-E1(scaffold) | W3 (Phase-1 gate) | E1 = new `demo/exchange` module, disjoint. |
| W5 | δ-tail(CP-D2→D3→D4) | W4 (D1 merged) | interest substrate + partitioned shards. |
| W6 | CP-E2 (PHASE EXIT) | W5 (D4) + W4 (E1) | partitioned exchange; `ExchangeCompositionExitTest`. |
| W7 | ζ(CP-F1→F2→F3) | W6 evidence | minimal nature type system. |

Phase-1 gate (after W3): full gate green + GlitchFreeBridgedDiamondTest,
GlitchFreeOperatorSuiteTest, InletFrontierPolicyTest, GlitchFreeReplicaFrontierTest,
MixedDurabilityTest all landed with controls.

## Status log

Legend: pending · impl-running · impl-done · validating · READY · merged · escalated · blocked

**Wave 1 COMPLETE** — combined gate green (`./gradlew test` BUILD SUCCESSFUL) @ `f9d563d`.

| Ticket | State | Branch | Merged commit | Notes |
|--------|-------|--------|---------------|-------|
| CP-A1 | merged | comp/CP-A1 | be8468e | spec; validator PASS |
| CP-B1 | merged | comp/CP-B1 | f9d563d | richer spec §E3.2 shape (rows/closed); validator PASS |
| CP-C1 | merged | comp/CP-C1 | 395c85e | journalFor selector; byte-identical default; validator PASS |
| CP-A2 | merged | comp/CP-A-tail | f94e5ce | bundled α-tail; validator PASS |
| CP-A3 | merged | comp/CP-A-tail | cdbe1c2 | bundled α-tail; validator PASS |
| CP-A4 | merged | comp/CP-A-tail | 9e12569 | WaveFrontier + FanInlet.frontierPolicy; validator PASS |
| CP-B2 | merged | comp/CP-B2 | 309dd3d | outlet-tap seam (coarse); E3.3(a) per-origin frontier deferred to CP-B3 |
| CP-B3 | merged | comp/CP-B3 | 9e26039 | JOIN (E3.3(a)+E3.4); validation caught whole-cell-replace glitch → reworked to per-edge; PASS |

**PHASE 1 COMPLETE** — gate green @ `9e26039`; all 5 named tests landed (GlitchFreeBridgedDiamondTest, GlitchFreeOperatorSuiteTest, InletFrontierPolicyTest, GlitchFreeReplicaFrontierTest, MixedDurabilityTest). One rework: CP-B3's replica-fed settlement was whole-cell-replacing the wave-frontier predicate (broke local-arm glitch-freedom, 50/50 probe); reworked to per-edge (`markReplicaFed`), + committed `MixedArmGlitchFreeTest`.

### Phase 2 (Wave 4–6)
| Ticket | State | Branch | Merged commit | Notes |
|--------|-------|--------|---------------|-------|
| CP-D1 | merged | comp/CP-D1 | d81b4c6 | spec: interest-scoped instance sets; G-56 retired; validator PASS |
| CP-E1 | merged | comp/CP-E1 | eade494 | demo/exchange scaffold; validator PASS. **Probe gap for E2**: no MapDelta-merge operator (GroupByCell not Replicable → input-replication+recompute) |
| CP-D2 | merged | comp/CP-D-tail | 7e02d37 | Interest (Total/Slots), maybeLink filter, default byte-identical; validator PASS |
| CP-D3 | merged | comp/CP-D-tail | c4d6aa9 | PartitionedCell shards-across-hosts, routingEpoch wire field; validator PASS |
| CP-D4 | merged | comp/CP-D-tail | a5cf896 | buffered flip zero-loss; unbuffered control diverges; validator PASS |
| CP-E2 | pending | comp/CP-E2 | | PHASE EXIT: partitioned exchange + ExchangeCompositionExitTest (7 empty pairs) |

**Wave 5 COMPLETE** — combined gate green @ `a5cf896`. Note (from D-tail review, non-blocking): distributed router uses a total-interest ledger for replay rather than shard-to-shard StateRequest (in-process `routed` ledger left in place — partial realization of "retire bespoke ledger"); control-plane holds ShardCell refs, data-plane crosses wire.

**Wave 2 COMPLETE** — combined gate green @ `9e12569` (559 tests, 0 failed).
**CP-B3 note**: CP-B2's watermark keys by re-emitter epoch (`originate` discards origin ctx before the tap). CP-B3's E3.4 replica-frontier settlement needs the finer **per-origin** delivered frontier → CP-B3 must also implement E3.3(a): a `DeliveredFrontier` in `SetCell`/`PnCounterCell.applyRemote` feeding `advance` keyed by origin source. Expanded CP-B3 file scope accordingly.
| CP-D1 | pending | comp/CP-D1 | | spec |
| CP-E1 | pending | comp/CP-E1 | | demo/exchange |
| CP-D2 | pending | comp/CP-D-tail | | bundled δ-tail |
| CP-D3 | pending | comp/CP-D-tail | | bundled δ-tail |
| CP-D4 | pending | comp/CP-D-tail | | bundled δ-tail |
| CP-E2 | pending | comp/CP-E2 | | PHASE EXIT |
| CP-F1 | pending | comp/CP-F | | bundled ζ |
| CP-F2 | pending | comp/CP-F | | bundled ζ |
| CP-F3 | pending | comp/CP-F | | bundled ζ |
</content>
</invoke>
