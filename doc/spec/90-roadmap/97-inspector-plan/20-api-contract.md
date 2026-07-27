# Inspector API contract

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
| `POST /api/inspect/cell/{ref}/observe` | M1 | 204; starts state summaries for this cell |
| `DELETE /api/inspect/cell/{ref}/observe` | M1 | 204; stops them |
| `GET /api/inspect/errors` | M2 | `ErrorSnapshot` |
| `GET /api/inspect/graphs` | M4 | `GraphList` |
| `GET /api/inspect/search?mode={name\|problems\|data}&q=` | M4 (name, problems), M5 (data) | `SearchResult` |

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
  "host": "sm-host",              // process host (ManagedHost) name — M0
  "net": "local",                 // network host / peer id — "local" until M5
  "lifecycle": "HOT" | "SUSPENDED",
  "generation": 0,
  "graph": "g-<id>"               // component id — null until M4
}

// Edge
{
  "id": "uuid",
  "from": { "ref": "uuid:0", "port": "out" },
  "to":   { "ref": "uuid:0", "port": "in" },
  "role": "CONSUME" | "OBSERVE",
  "fused": false                   // best-effort; null when unknown (M0 may emit null)
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
// The encoder truncates: max 200 rows / 50 KB per response, with
//   {"$truncated": {"total": 1800, "shown": 200}} appended when it does.

// ErrorSnapshot (M2)
{
  "counters": { "deadLetters": 3, "parked": 14, "restarts": 1, "drainedOnTeardown": 0 },
  "deadLetters": [ { "ref": "uuid:0", "cause": "OwnershipViolation",
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
    "lifecycle": "hot" | "cold"    // cold: M5; until then always "hot"
  } ]
}

// SearchResult (M4/M5)
{
  "mode": "name" | "problems" | "data",
  "hits": [ { "graph": "g-…", "ref": "uuid:0" | null, "label": "...", "detail": "..." } ],
  "cost": { "cellsQueried": 4, "coldSkipped": 2 } | null   // data mode only (M5)
}
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
| `flow.rates` | M3 | `{ "window": 1000, "edges": [ { "id", "rate", "lastWave": {...}\|null, "hop": 2\|null } ] }` — 1 Hz batch; edges with rate 0 omitted |
| `graphs.changed` | M4 | `{}` — hint to refetch `GraphList` (components merged/split) |
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
