# Inspector delivery plan — orchestration

Status: ACTIVE PLAN (2026-07-27). Owner: Merlijn. Consumed by: an orchestrating
LLM that assigns tickets to sub-agents, sequences them, and gates merges through
evaluators.

Goal: deliver the **ComputeNet Inspector v3 dashboard** (see `10-target-v3.md`)
in incremental verticals — every milestone ships a working slice of UI *plus*
the backend feed that powers it, ordered by (a) what the backend can already
deliver and (b) user value, deferring niche capability to the end.

Tickets live in `tickets/`. Each ticket is self-contained: a worker needs only
the repo, the ticket file, and the two reference docs it cites
(`10-target-v3.md`, `20-api-contract.md`).

## Milestones (verticals)

| M | Vertical | Ships | Backend readiness |
|---|----------|-------|-------------------|
| M0 | Topology skeleton | Live graph canvas fed by a new `:inspect` server (snapshot + SSE deltas) | Substrate exists (`LocationRegistry`, `TopologyIndex`, hooks); one small kernel seam |
| M1 | Selection + state | Node detail panel (descriptor/placement + live state preview), process-host hull toggle | `ObservationSink` / `Stateful.snapshot()` exist; needs generic value encoding |
| M2 | Errors | Error toggle (badges, park pills) + per-cell error subsection + counters | `deadLetterOutlet`, `parkedFor`, `supervisionAccounting()` exist — pure wiring |
| M3 | Flow | Flow toggle (edge rates/pulses, wave+hop) + per-port flow subsection + feed | Hooks exist (taps, host choke-point); the event stream must be built |
| M4 | Navigator | Multi-graph home: components, cards, constellation, name/problems search | Connected components + identity heuristic must be built |
| M5 | Distribution & niche | Network-host hulls (peer introspection), content search, cold-graph inspection | All three demand new kernel-adjacent capability; deliberately last |

Dependency shape:

```
M0 ──► M1 ──► M2 ──► M3 ──► M4 ──► M5
      (each milestone starts from main after the previous EVAL merged)

Within a milestone:   *-BE ∥ *-FE   (parallel, file-disjoint, both code
                                     against 20-api-contract.md)
                      then *-EVAL   (barrier: verifies, fixes, merges)

Within M5:            M5-NET ∥ M5-SEARCH, then M5-COLD (SEARCH and COLD both
                      touch navigator UI files — serialize those two), then
                      M5-EVAL.
```

M2 and M3 are intentionally sequenced (not parallel) even though their backend
seams differ: both extend `InspectorServer`/the SSE encoder and both extend the
same FE canvas-overlay and detail-panel files. Serializing them avoids merge
conflicts at the cost of little wall-clock, since each is small.

## Model routing

Exact model ids; do not append date suffixes. Set `effort: xhigh` for
implementation tickets (the recommended setting for coding/agentic work on
these models) and `high` for evaluators unless stated.

| Model | Use for | Rationale |
|-------|---------|-----------|
| `claude-sonnet-5` | Well-specified implementation against a fixed contract: all FE tickets (M0–M4), M2-BE; evaluators for UI-heavy milestones (M1, M2) | Near-Opus quality on coding/agentic work at lower cost; strongest when the task is precisely specified — which the contract + mockups provide. Follows instructions literally: tickets for Sonnet state scope explicitly. |
| `claude-opus-5` | Kernel-adjacent or design-carrying backend tickets: M0-BE, M1-BE, M3-BE, M4-BE, M5-NET, M5-SEARCH, M5-COLD; evaluators for M0, M3, M4 | The agentic-coding workhorse; strongest on multi-file work needing judgment (kernel invariants P2/P6, ownership rules, backpressure design, identity heuristics). Give the full ticket up front and let it run. |
| `claude-fable-5` | M5-EVAL only: final acceptance of the whole inspector against `10-target-v3.md` + kernel-invariant audit | Most capable model, priced above Opus — reserve for the single highest-judgment gate. Note: turns can run many minutes; safety classifiers may refuse (HTTP 200, `stop_reason: refusal`) — irrelevant for this content, but handle it if driving via API. |

Notes for the orchestrator:
- Do not down-route a kernel-touching ticket to Sonnet to save cost; the
  invariants in `AGENTS.md` ("Core invariants to protect") are exactly the kind
  of cross-cutting constraint that justifies Opus on those tickets.
- Sonnet-targeted tickets are written prescriptively (explicit scope, explicit
  exclusions). Opus-targeted tickets state goals and constraints and leave
  approach open. Keep that style if you edit tickets.

## Session reuse

Sessions accumulate valuable context (repo layout, contract, prior decisions).
Reuse them deliberately:

- **Two long-lived tracks**: a BE session and an FE session. The FE session
  runs M0-FE → M1-FE → M2-FE → M3-FE → M4-FE consecutively; the BE session runs
  the BE tickets it is routed the same model for. When routing changes model
  (e.g. M2-BE on Sonnet between Opus tickets), that ticket gets its own session.
- **Between tickets in a session**: instruct the agent to `git pull` / rebase
  onto latest `main` first — the previous milestone's EVAL merged changes it
  has not seen.
- **Start a fresh session when**: (a) the evaluator required significant rework
  of that session's previous ticket (context is contaminated with a wrong
  approach), (b) the session has completed 3 tickets (context volume), or
  (c) the track switches model.
- Evaluators always get fresh sessions: their value is an independent view.

## Parallelism rules

- Parallel only when file-disjoint. The standing split: BE tickets own
  `inspect/src/**`, `inspect/build.gradle.kts`, and any explicitly listed
  kernel/demo files; FE tickets own `inspect/ui/**` only. `settings.gradle.kts`
  is touched once, by M0-BE.
- The API contract (`20-api-contract.md`) is what makes BE ∥ FE safe: FE codes
  against the contract with a bundled JSON fixture until the real endpoint is
  merged; BE implements the contract exactly. **Contract changes require a
  ticket note to the orchestrator, never a unilateral edit** — the orchestrator
  updates the contract and notifies the other track.
- Never parallelize two tickets that both list `inspect/src/**` or both list
  `inspect/ui/**`.
- Workers must not run repo-wide formatting or opportunistic refactors
  (AGENTS.md discipline) — this is what keeps parallel diffs mergeable.

## Evaluation protocol

Every milestone ends with an EVAL ticket. The evaluator is the **arbiter**: it
decides whether the milestone is correctly implemented, fixes what falls short,
and merges when satisfied. Concretely:

1. **Inputs**: the milestone's implementation ticket(s), the diffs/branches from
   the worker(s), `10-target-v3.md`, `20-api-contract.md`.
2. **Verify**:
   - Build + tests: `./gradlew :inspect:test`, `npm test` in `inspect/ui/`,
     and the affected demo module's tests. Before merging: repo gate
     `./gradlew test`.
   - Contract conformance: exercise each new endpoint/event and diff the shapes
     against `20-api-contract.md`.
   - Live verification: launch the pilot host (skillmatch demo + inspector),
     open the UI, and check every acceptance item in the tickets — with
     screenshots as evidence where visual.
   - Diff hygiene: no generated/build output, no files outside the ticket's
     listed scope, no weakened tests, no removed gap markers beyond the
     ticket's scope (AGENTS.md checklist).
   - Kernel invariants: for BE tickets, check the specific invariants the
     ticket names (e.g. P2 fast path untouched, taps Borrowed-only, no silent
     drop of Owned/Leased payloads).
3. **Arbitrate**: small defects — fix directly in the eval session. Structural
   defects — send the ticket back to the implementing session with a concrete
   defect list (one round trip; if the second attempt still fails, the
   evaluator fixes it itself and notes the failure in its report).
4. **Merge**: only the evaluator merges. Merge to `main`, milestone by
   milestone; verify the post-merge tree builds. Commit messages:
   `inspector(M<k>): <summary>`.
5. **Report**: tests run, defects found/fixed, deviations from ticket or
   contract, and any new open question — appended to `90-progress-log.md` in
   this folder (create on first use).

## Git & environment discipline

- Workers do not commit, merge, or switch branches — they leave a reviewable
  working tree/branch per the orchestrator's convention; the evaluator owns git
  for its milestone (per AGENTS.md multi-agent discipline, with merge authority
  explicitly delegated to evaluators by this plan).
- If workers share a checkout/index (rather than isolated worktrees): commit by
  explicit pathspec only, never `git add -A`, never amend.
- The frontend (`inspect/ui/`) is npm/Vite, **not** wired into Gradle — same
  decision as `demo/agora/ui/` and for the same reason. Do not add
  gradle-node-plugin.
- Do not edit files under `concord/` in any inspector ticket; `:concord:check`
  must stay green untouched.
- The plan documents in `doc/spec/90-roadmap/` other than this folder are not
  to be edited by workers.

## Ticket index

| Ticket | Model | Depends on | Parallel with |
|--------|-------|------------|---------------|
| `tickets/M0-BE.md` | claude-opus-5 | — | M0-FE |
| `tickets/M0-FE.md` | claude-sonnet-5 | — | M0-BE |
| `tickets/M0-EVAL.md` | claude-opus-5 | M0-BE, M0-FE | — |
| `tickets/M1-BE.md` | claude-opus-5 | M0-EVAL merged | M1-FE |
| `tickets/M1-FE.md` | claude-sonnet-5 | M0-EVAL merged | M1-BE |
| `tickets/M1-EVAL.md` | claude-sonnet-5 | M1-BE, M1-FE | — |
| `tickets/M2-BE.md` | claude-sonnet-5 | M1-EVAL merged | M2-FE |
| `tickets/M2-FE.md` | claude-sonnet-5 | M1-EVAL merged | M2-BE |
| `tickets/M2-EVAL.md` | claude-sonnet-5 | M2-BE, M2-FE | — |
| `tickets/M3-BE.md` | claude-opus-5 | M2-EVAL merged | M3-FE |
| `tickets/M3-FE.md` | claude-sonnet-5 | M2-EVAL merged | M3-BE |
| `tickets/M3-EVAL.md` | claude-opus-5 | M3-BE, M3-FE | — |
| `tickets/M4-BE.md` | claude-opus-5 | M3-EVAL merged | M4-FE |
| `tickets/M4-FE.md` | claude-sonnet-5 | M3-EVAL merged | M4-BE |
| `tickets/M4-EVAL.md` | claude-opus-5 | M4-BE, M4-FE | — |
| `tickets/M5-NET.md` | claude-opus-5 | M4-EVAL merged | M5-SEARCH |
| `tickets/M5-SEARCH.md` | claude-opus-5 | M4-EVAL merged | M5-NET |
| `tickets/M5-COLD.md` | claude-opus-5 | M5-SEARCH merged (shares navigator UI files) | M5-NET |
| `tickets/M5-EVAL.md` | claude-fable-5 | M5-NET, M5-SEARCH, M5-COLD | — |
