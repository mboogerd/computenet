# :demo — collaborative shopping list over the cell graph

The M4 exit-criterion app ([spec 92](../doc/spec/90-roadmap/92-way-forward.md))
with a face: a shared shopping list with votes, built purely from cells —
per-user `SetCell` writers, `UnionSetCell` views, a DSL-built
filter/count chain plus an `items ∩ votes` "still wanted" intersection
([spec 51](../doc/spec/50-development-process/51-construction.md)) —
fronted by the JDK's built-in HTTP server with one static page and SSE.

```
./gradlew :demo:shopping:run     # then open http://localhost:8080 in two tabs
```

Each browser tab is a user (random id in sessionStorage). Two tabs converge
on every add/remove/vote — observed-remove tags (spec 24) make concurrent
edits order-independent — and a freshly opened tab is populated immediately.

## Two machines, one graph (M5)

The same app spans two JVMs over the real wire ([spec 41](../doc/spec/40-distribution/41-location-transparency.md)):

```
./gradlew :demo:shopping:run --args="8080 --listen 9090"                 # peer 1
./gradlew :demo:shopping:run --args="8081 --peer ws://localhost:9090"    # peer 2
```

Open tabs against either port — edits cross the WebSocket as serialized
`WireFrame`s and converge on both sides. The peers are symmetric: each hosts
its own users' writer cells and its own derived views; the two `UnionSetCell`s
stream into each other through registry-routed proxies (tag dedup makes the
two-way chain cycle-safe), and a peer that starts late catches up from the
parked replay. The cell graph is identical to single-process mode — placement
is the only difference.

The headless, seeded version of this session — late joiner, host migration,
injected failure, invariants — runs in CI as `CollaborativeAppTest` (one
process) and `DistributedCollaborativeAppTest` (split across two registries
over the loopback bridge) in `:kernel`; the two-OS-process form is
`TwoJvmConvergenceTest` here. This module is a view, not the verification.

<!-- ponytail: SSE still pushes full state to the browser; an incremental
     browser client (browser tab as a peer) is M6+ material -->
