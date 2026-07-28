# Inspector API contract

**Status**: Living — reference doc for the in-progress inspector delivery plan (see `00-orchestration.md`).

The contract between `:inspect` (server) and `inspect/ui` (client). Both sides
implement against this document so BE and FE tickets can run in parallel.
**Neither side edits this file unilaterally** — change requests go to the
orchestrator (see `00-orchestration.md`).

Serialization: kotlinx.serialization JSON on the server. All ids are strings.
A cell ref is encoded `"<uuid>:<instanceId>"` (e.g. `"5f3a…-…:0"`). Unknown
fields must be ignored by the client (additive evolution).

Base path: `/api/inspect`. The inspector server runs on its own port beside
the pilot demo (skillmatch), default `7071`, overridable via `--inspect-port`.

## Endpoints

| Method + path | Milestone | Returns |
|---|---|---|
| `GET /api/inspect/topology` | M0 | `TopologySnapshot` |
| `GET /api/inspect/events` | M0 | SSE stream of `Event` |
| `GET /api/inspect/cell/{ref}` | M1 | `CellDetail` |
| `GET /api/inspect/cell/{ref}/state` | M1 | `CellState` |
| `POST /api/inspect/cell/{ref}/observe` | M1 | 204; starts state summaries for this cell. 409 if the cell has no built-in fold to observe (no delta outlet, or an outlet kind with no `View`) — a client that ignores the 409 still behaves correctly, since `GET .../state` reports `kind: "unavailable"` for that cell |
| `DELETE /api/inspect/cell/{ref}/observe` | M1 | 204; stops them |
| `GET /api/inspect/errors` | M2 | `ErrorSnapshot` |
| `GET /api/inspect/graphs` | M4 | `GraphList` |
| `GET /api/inspect/topology?graph={id}` | M4 | `TopologySnapshot` scoped to one component, sharing `seq` with the unfiltered snapshot. An unrecognized/evaporated id (components merge/split, ids are not stable) returns 200 with an empty snapshot, not 404 — treat it as a race, not an error |
| `GET /api/inspect/search?mode={name\|problems\|data}&q=` | M4 (name, problems), M5 (data) | `SearchResult`. `mode` defaults to `name` if omitted; an unrecognized `mode` is 400. A blank/whitespace `q` in `name` mode returns no hits (not everything) — safe to call on every keystroke. `data` mode is submit-triggered (not per-keystroke), bounded (50 cells / 2s deadline / cold components skipped), and always returns a non-null `cost` — including a zero-hit or blank-query result, since "this query cost N cell reads" is itself the answer |
| `POST /api/inspect/graph/{id}/wake` | M5 | 202 `{ "graph": "g-…", "hosts": 2, "cells": 5 }` — resumes every suspended/drained cell and host in the component (a drained host's wake resumes *every* cell it holds, not only this component's — `hosts`/`cells` report the true blast radius). 404 for an unknown/evaporated id (unlike the read-only `?graph=`, naming nothing to wake is treated as an error, not a race) |

## DTOs

```jsonc
// TopologySnapshot
{
  "seq": 412,                      // monotonic; SSE events carry seq > this
  "nodes": [ /* Node */ ],
  "edges": [ /* Edge */ ]
}

// Node
{
  "ref": "uuid:0",
  "name": "people",               // registry/debug name if known, else null
  "typeFqn": "civictech.cell.data.SetCell",
  "color": "PURE" | "BLOCKING" | "SUSPENDING" | null,
  "manifests": ["DURABLE", "GLITCH_FREE", ...],
  "ports": [ { "name": "out", "dir": "OUT", "contractFqn": "..." } ],
  "host": "sm-host" | null,       // process host (ManagedHost) name — M0. null means remote: this is
                                   // the client's discriminator for a peer-announced cell, which has
                                   // no local descriptor (color/manifests/ports all absent, typeFqn
                                   // "<unknown>") and answers CellState "unavailable" / observe 409 (M5)
  "net": "local",                 // network host / peer id. Local cells: the launcher's --net-name
                                   // (default "local", so M0-M4 output is unchanged). Remote cells:
                                   // a "peer-<id>" label, NOT stable across a peer reconnect (M5)
  "lifecycle": "HOT" | "SUSPENDED", // SUSPENDED covers both a suspended cell and any cell on a drained
                                   // host (M5) — the vocabulary does not distinguish them; a component's
                                   // GraphList.lifecycle "cold" requires every member cell SUSPENDED
  "generation": 0,
  "graph": "g-<id>"               // component id; never null from M4 on (an unlinked cell is
                                   // its own singleton component). M0-M3 servers may still emit null.
}

// Edge
{
  "id": "uuid",
  "from": { "ref": "uuid:0", "port": "out" },
  "to":   { "ref": "uuid:0", "port": "in" },
  "role": "CONSUME" | "OBSERVE",
  "fused": false                   // true: the producing endpoint has no emission point at all
                                    //   (a delegating pass-through) — genuinely no message to observe.
                                    // false: tapped — a real per-message flow rate is observable (M3+).
                                    // null: producer not locally hosted / not resolvable (M0 may emit null
                                    //   for any edge, since flow observation lands in M3).
                                    // NOT "co-hosted" — a tap sits upstream of the direct-call-vs-enqueue
                                    // decision, so co-hosted and cross-host edges are observed identically.
}

// CellDetail (M1) — Node plus:
{
  ...Node,
  "attention": "focus" | "idle" | null,
  "links": { "inbound": 2, "outbound": 3, "taps": 1 }
}

// CellState (M1)
{
  "ref": "uuid:0",
  "frontier": { "source": "a3f2…", "counter": 412 } | null,
  "kind": "view" | "snapshot" | "unavailable",
  "value": /* Value (below) */,
  "staleMs": 120
}

// Value — generic JSON-ish encoding of cell state (M1-BE defines the encoder;
// inspired by concord's neutral Value model, but independent of :concord)
//   scalar | [Value] | {"k": Value} | {"$table": {"columns": [...], "rows": [[...]]}}
//   | {"$opaque": {"type": "civictech.foo.Bar", "text": "..."}}   // reflective toString last resort
// An empty collection of records encodes as [], not an empty $table (columns
// are only discoverable from an element) — clients must accept either as a
// cell's value drains and refills.
// The encoder truncates: max 200 rows / 50 KB per response, with
//   {"$truncated": {"total": 1800, "shown": 200}} appended when it does
//   (as a sibling of "$table" on a table, or as the appended last element on a plain array).
// A tombstoned element (e.g. a removed OR-set member) is excluded from encoded
// state entirely, never emitted as a marked row — there is no tombstone row shape.

// ErrorSnapshot (M2)
{
  "counters": { "deadLetters": 3, "parked": 14, "restarts": 1, "drainedOnTeardown": 0 },
  "deadLetters": [ { "ref": "uuid:0", "cause": "OwnershipViolation" | null,  // null for a plain drop (e.g. unknown target), no thrown exception
                     "description": "...", "wave": {"source":"9c41","counter":288} | null,
                     "atMs": 1753600000000 } ],
  "parked": [ { "ref": "uuid:0", "port": "left", "count": 11, "oldestMs": 41000 } ],
  "restarts": [ { "ref": "uuid:0", "generation": 1, "atMs": ... } ]
}

// GraphList (M4)
{
  "graphs": [ {
    "id": "g-<stable-id>",         // heuristic: lexicographically-min cell uuid in the component
    "name": "skillmatch" | null,   // from an optional host-side annotation; null = unnamed
    "cells": 13, "hosts": 3, "nets": 1,
    "health": { "deadLetters": 2, "parked": 14, "restarts": 1 },
    "lifecycle": "hot" | "cold"    // cold iff every member cell reports Node.lifecycle SUSPENDED (M5)
                                   // — LOWERCASE, unlike Node.lifecycle's "HOT"/"SUSPENDED"
  } ]
}
// health is scoped to the component's own cells, rolled up from ErrorSnapshot's
// per-cell rows (deadLetters[]/parked[]/restarts[]) rather than its host-wide
// counters, which carry no cell attribution and cannot be split between two
// components sharing a host. Consequence: health is bounded by however much
// row history the error feed's ring buffers (cap 200 each) still retain — GET
// /api/inspect/errors' counters remain the true, unbounded per-host totals.

// SearchResult (M4/M5)
{
  "mode": "name" | "problems" | "data",
  "hits": [ { "graph": "g-…", "ref": "uuid:0" | null, "label": "...", "detail": "..." } ],
  "cost": { "cellsQueried": 4, "coldSkipped": 2 } | null   // non-null on every "data" mode response
                                                            // (including zero hits or a blank query),
                                                            // null for "name"/"problems". coldSkipped
                                                            // counts skipped CELLS, not graphs (M5)
}
// A "data" mode hit whose "graph" is "" (empty, not null) is a closing NOTICE, not a navigable
// result — it names what the search did not fully cover (the 50-cell cap, the 2s deadline, a
// cell read only to its first 200 rows, cold components skipped). Render it inert. (M5)
// The inspector's own observation-sink cells (ObserveCell instruments it spawns on selection)
// never appear anywhere in this API — not as a Node, an Edge, a component member, or a search
// hit. An instrument is not a subject. (M5)
```

## SSE events

`GET /api/inspect/events` emits `data: <json>\n\n` frames. Envelope:

```jsonc
{ "seq": 413, "kind": "<kind>", "payload": { ... } }
```

Client protocol: fetch `TopologySnapshot`, then apply events with
`seq > snapshot.seq`; on gap or reconnect, refetch the snapshot (events are
not replayed). Server sends `heartbeat` every 15 s.

| kind | Milestone | payload |
|---|---|---|
| `topology.node` | M0 | `{ "op": "added"\|"removed", "node": Node }` |
| `topology.link` | M0 | `{ "op": "added"\|"removed", "edge": Edge }` (removed: only `id` required) |
| `lifecycle` | M0 | `{ "ref", "lifecycle", "generation" }` |
| `state.summary` | M1 | `{ "ref", "cardinality": "4 rows"\|null, "frontier": {...}\|null, "staleMs" }` — only for cells with an active observe subscription |
| `error.deadLetter` | M2 | one `deadLetters[]` element |
| `error.parked` | M2 | one `parked[]` element (send on change; `count: 0` clears) |
| `error.restart` | M2 | one `restarts[]` element |
| `flow.rates` | M3 | `{ "window": 1000, "edges": [ { "id", "rate", "lastWave": {...}\|null, "hop": 2\|null } ] }` — 1 Hz batch; `rate` is messages/second (a Double; with `window: 1000` numerically equal to the raw count); edges with no traffic that window are omitted (not sent as `rate: 0`). Publishes every second while anything is tapped, even an all-empty window (so a client's decay logic can key off "window received" rather than off silence), then one trailing empty window after the last tap detaches, then nothing. Unlike every other feed in this table, `flow.rates` has no paired snapshot/`GET` endpoint — a client's only source of truth for flow is this stream |
| `graphs.changed` | M4 | `{}` — refetch both `GraphList` **and** any held `TopologySnapshot` (filtered or not). Fires on any component membership change or a `nameGraph` rename, not only merge/split. Required, not optional: a cell is published (stamped with its own singleton `Node.graph`) *before* the link that merges it into an existing component, so a client applying deltas alone holds a stale `Node.graph` until it resyncs |
| `heartbeat` | M0 | `{}` |

## Fixture

`inspect/ui/fixtures/topology.json` — a `TopologySnapshot` of the skillmatch
demo graph (16 cells: 10 named pipeline cells + 6 `ObserveCell` sinks, all on
a single `skillmatch` host — the M0-FE draft guessed 13 cells across 3
synthetic hosts before a real server existed; M0-EVAL reconciled it against
the real `GET /api/inspect/topology` response), checked in by M0-FE and used
by unit tests and for offline development. Keep it conformant to this
contract. M1-FE needing multi-host coverage (e.g. for the process-host hull
toggle) should add a second, clearly-labelled synthetic multi-host fixture
alongside this golden one rather than re-inventing it.
