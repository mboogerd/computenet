# M2-BE — Error lane feed

**Status**: Implemented — merged to main (see `90-progress-log.md`).

Model: `claude-sonnet-5` (effort xhigh) · Track: backend · Depends: M1-EVAL
merged · Parallel with: M2-FE

Files owned: `inspect/src/**` only. No kernel changes — every seam below
already exists; if you believe one is missing, stop and flag the orchestrator.

## Context

Read `AGENTS.md`, `20-api-contract.md` (§ErrorSnapshot, §error.* events), and
the merged `inspect/src/`. Seams (all in
`kernel/src/main/kotlin/civictech/cell/host/`):

- `ManagedHost.deadLetterOutlet` — a `FanOutlet<Propagate<DeadLetter>>`;
  subscribe with an Observe-role attachment (`streamTo`/tap — see
  `kernel/.../cell/port/StreamTo.kt`), never a consuming link: dead letters
  may carry exclusive payloads and the inspector must not consume them.
- `LocationRegistry.parkedFor(ref)` — documented test/introspection surface
  for parked traffic.
- `ManagedHost.supervisionAccounting()` (counters), `generationOf(ref)`.

## Implement (prescriptive — stay within this scope)

1. `GET /api/inspect/errors` → `ErrorSnapshot` exactly per contract: counters
   from `supervisionAccounting()` across all inspected hosts; current dead
   letters retained in a bounded ring buffer (cap 200, oldest evicted);
   parked rows built from `parkedFor` across known refs; restart rows from
   generation observations.
2. SSE events: `error.deadLetter` on each dead letter received via the
   observe-role attachment (map cause/description/wave from the `DeadLetter`
   payload; never retain the payload object itself beyond serialization —
   extract strings, drop the reference); `error.parked` on change (poll
   parked counts on a 2 s timer — cheap registry reads, no subscriptions);
   `error.restart` when `generationOf(ref)` is observed to increase (same
   timer).
3. Wire the error feed into `InspectorServer` startup/shutdown symmetrically
   with the topology feed.

## Exclusions

No flow (M3). No kernel/demo edits. No retention beyond the ring buffer. No
per-cell subscriptions for error data.

## Tests / acceptance

- With an in-process test graph: force a dead letter (e.g. despawn a cell
  with parked traffic, or use whatever mechanism existing kernel tests use to
  produce one — search `:kernel` tests for `DeadLetter`), assert the SSE
  event and snapshot row; force a supervision restart and assert
  `error.restart`; park messages and assert `error.parked` including the
  `count: 0` clear.
- Ring-buffer eviction test.
- `./gradlew :inspect:test` green; curl transcript of `/errors` against a
  demo run in the report.
