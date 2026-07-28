# M5-NET — Network hosts: peer topology + nested hulls (full vertical)

**Status**: Specified — not yet dispatched (see `00-orchestration.md` §Ticket index).

Model: `claude-opus-5` (effort xhigh) · Track: full vertical (BE + FE) ·
Depends: M4-EVAL merged · Parallel with: M5-SEARCH (disjoint: NET owns
placement/peering code + canvas hull code; SEARCH owns search endpoint +
navigator search UI)

Files owned: `inspect/src/**` (placement/peering), `inspect/ui/**` (hulls +
placement display only), `demo/shopping/**` or `demo/exchange/**` (pilot
wiring only — pick whichever demo already runs multi-JVM peering with least
friction; shopping's convergence tests suggest it).

## Context

Until now `Node.net` has been `"local"`. This vertical makes the "Network
hosts" toggle real: cells hosted on peer JVMs appear in topology with their
network host, and the canvas nests dashed network hulls around solid process
hulls (v2 mockup shows the exact rendering — shopping across jvm-a/jvm-b).

Seams: `Peering` (`kernel/.../cell/wire/Peering.kt`) already mirrors remote
refs and links into the local registry (`announceTo` replays
`localRefs()`/`localLinks()` on connect, then deltas);
`LocationRegistry.location(ref)` distinguishes `Local(host)` from
`Remote(sink)`. Deep-dive both before designing. Known constraint: FU-1 in
`doc/PERNODE-FOLLOWUP-TICKETS.md` (StateRequest scope over-fetch across a
bridge) — relevant only if you attempt remote state; you must NOT (see
exclusions).

## Implement

1. **BE**: extend topology assembly to include remote-mirrored refs with
   `net` set to a peer identity (derive a stable peer label from the peering
   connection; local cells keep `net: "local"`, renderable as the local JVM's
   label from a `--net-name` launcher option). Process-host attribution for
   remote cells may be unavailable — `host: null` is acceptable and the UI
   groups them under the net hull only. Cross-boundary links (e.g.
   replication) must appear as edges; if the registry mirrors them already,
   surface them; mark role per what the mirror records.
2. **FE**: enable the "Network hosts" toggle — dashed hulls per `net`,
   nesting with process hulls (net outside, proc inside); peer hulls labeled
   with the peer id and a "peer" tag; nodes with `host: null` sit directly in
   the net hull. Placement subsection in the detail panel shows both levels.
3. **Pilot**: wire the chosen multi-JVM demo so one JVM runs the inspector
   and sees both sides (its own cells + mirrored peer cells), with the
   replication edge visible. Document the run recipe in the ticket report
   (ports, args) — the evaluator will replay it.

## Exclusions

Remote *state/flow/error* feeds (topology + placement only — selecting a
remote cell shows descriptor/placement and "remote — state/flow/errors not
available in this milestone" in the other subsections; make the UI say
exactly that). No StateRequest across the wire. No peering protocol changes.

## Tests / acceptance

- BE: with an in-process pair of peered registries (see how `:kernel` /
  `:wire` peering tests construct them — `testkit`'s `JvmPeer` may serve),
  assert remote refs appear with correct `net`, and disconnect removes them.
- FE: hull nesting unit test (net ⊃ proc grouping), remote-cell detail
  placeholder gating.
- `./gradlew :inspect:test`, `npm test` green; the documented two-JVM manual
  run with a screenshot showing nested hulls and the cross-boundary edge.
