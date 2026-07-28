# M5-EVAL — Final acceptance: the inspector vs the v3 vision

**Status**: Specified — not yet dispatched (see `00-orchestration.md` §Ticket index).

Model: `claude-fable-5` (effort high; raise to xhigh for the invariant audit
if findings warrant) · Fresh session · Depends: M5-NET, M5-SEARCH, M5-COLD
merged-or-ready per the orchestrator's sequencing. You are the final arbiter
for the whole inspector: verify, fix or bounce, merge what remains, and
deliver the closing report.

## Scope

Two layers — the M5 verticals, then the whole product.

### 1. M5 vertical verification

Per `00-orchestration.md` §Evaluation protocol, for each of NET / SEARCH /
COLD: contract conformance, the ticket's acceptance list replayed live
(including the two-JVM NET recipe), and the milestone-specific invariants —
NET: no remote state/flow leakage, honest "unavailable" placeholders;
SEARCH: P6 (no observation creation for search, suspended skipped, budgets
enforced), cost surfaced in UI; COLD: coldness computed subscription-free,
wake is explicit + confirmed + logged, no fake previews.

### 2. Whole-product acceptance against `10-target-v3.md`

Walk the target document clause by clause and verify each is true of the
merged system: one canvas + five toggles (each toggling cleanly, any
combination); all-properties detail panel with lazy state subscription;
navigator with three search modes; the six binding constraints
(§Constraints) — for each, name the evidence (test, code cite, or live
check). Then a full kernel-invariant audit of the cumulative `inspect` +
kernel diff against `AGENTS.md` "Core invariants to protect": grep the whole
inspector for subscriptions, payload retention, graph-thread work, and kernel
edits beyond the two sanctioned seams (M0's `describe`, anything the
orchestrator approved since — check `90-progress-log.md`).

Gates: `./gradlew test` (full), `npm test`, `./gradlew :concord:check`
untouched-and-green.

## Arbitration

You may fix defects directly or bounce to fresh implementation sessions with
defect lists — your call, biased by defect size. Nothing merges or remains
merged that fails a binding constraint.

## Closing report (append to `90-progress-log.md`)

- Acceptance verdict per `10-target-v3.md` clause (met / met-with-caveat /
  unmet+why).
- The cumulative kernel diff, listed file-by-file, each line justified.
- Open items for the roadmap: feed the confirmed kernel gaps back — graph
  identity (MRB-156), inspect-without-attention + search cost model
  (MRB-157), E2 observation-edge alignment — with anything this build
  learned about them.
- Recommended next increments (deferred v2/v3 ideas: per-message ticker,
  wave tracer, journal time-travel, remote state via bridge once FU-1 lands).
