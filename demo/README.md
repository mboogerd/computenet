# :demo — collaborative shopping list over the cell graph

The M4 exit-criterion app ([spec 92](../doc/spec/90-roadmap/92-way-forward.md))
with a face: a shared shopping list with votes, built purely from cells —
per-user `SetCell` writers, `UnionSetCell` views, a DSL-built
filter/count chain ([spec 51](../doc/spec/50-development-process/51-construction.md)) —
fronted by the JDK's built-in HTTP server with one static page and SSE.

```
./gradlew :demo:run     # then open http://localhost:8080 in two tabs
```

Each browser tab is a user (random id in sessionStorage). Two tabs converge
on every add/remove/vote — observed-remove tags (spec 24) make concurrent
edits order-independent — and a freshly opened tab is populated immediately.

The headless, seeded version of this session — late joiner, host migration,
injected failure, invariants — runs in CI as `CollaborativeAppTest` in
`:kernel`. This module is a view, not the verification.

<!-- ponytail: JDK httpserver + SSE pushing full state; replace with the real
     wire transport + incremental client when M5 lands -->
