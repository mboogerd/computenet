# M4-BE — Multi-graph: components, identity, search (name/problems)

Model: `claude-opus-5` (effort xhigh) · Track: backend · Depends: M3-EVAL
merged · Parallel with: M4-FE

Files owned: `inspect/src/**`; plus ONE opt-in naming hook (see §2) which may
live in `inspect` (preferred) or, if genuinely necessary, as a tiny additive
registry annotation — flag the orchestrator before touching the kernel.

## Context

There is no `Graph` entity in the kernel — a "graph" is an emergent connected
component over the link set, and components merge/split whenever links are
created/removed. The real naming answer (membranes as the nameable boundary)
is a tracked kernel question (Linear MRB-156) and explicitly out of scope; you
are building the pragmatic version `10-target-v3.md` §Known-gaps describes.
Contract: `20-api-contract.md` §GraphList, §SearchResult, §graphs.changed.

## Implement

1. **Component index**: maintain connected components over
   `TopologyIndex` (undirected, links of both roles connect), incrementally
   updated from the same registry hooks the topology feed uses. Component id:
   `g-<lexicographically-min cell uuid in the component>` — stable under
   growth, changes on merge/split by design; emit `graphs.changed` when the
   component set changes. Also stamp each `Node.graph` in topology output.
2. **Naming hook**: an opt-in, inspector-level annotation API —
   `InspectorServer.nameGraph(anchorRef, name)` (or an equivalent builder
   option) that hosts/demos call to label the component containing a given
   cell. Wire skillmatch's pilot launcher to name its graph "skillmatch".
   Unnamed components get `name: null` (the UI renders the id) — do NOT
   invent names.
3. **`GET /graphs`** per contract: per-component cell/host/net counts and
   health rollup (dead letters / parked / restarts scoped to the component's
   refs, from the M2 error store). `lifecycle` is always `"hot"` (cold is M5).
4. **`GET /search`**: `mode=name` — case-insensitive substring over graph
   names and cell names/types, hits carry graph + optional ref;
   `mode=problems` — graphs with nonzero health counters, ordered by
   severity (dead letters first). `mode=data` returns 501 with a JSON body
   `{"error": "data search arrives in M5"}`.
5. **Scoping**: `GET /topology` gains an optional `?graph=g-…` filter
   (unfiltered remains valid); SSE stays global (client filters).

## Exclusions

Cold graphs, content search, cross-JVM anything (M5). No membrane/kernel
naming mechanism. No persistence of names.

## Tests / acceptance

- Component index: build two disjoint graphs, assert two components; link
  across them → one component + `graphs.changed`; unlink → split. Id
  stability under add/remove-within-component.
- Health rollup scoping (errors in graph A don't count toward B).
- Search: name hits (graph and cell), problems ordering, data → 501.
- `./gradlew :inspect:test` green; curl transcript of `/graphs` + `/search`
  against a two-graph pilot (spawn a second small graph in the launcher for
  the demo — allowed within `demo/skillmatch` launcher scope).
