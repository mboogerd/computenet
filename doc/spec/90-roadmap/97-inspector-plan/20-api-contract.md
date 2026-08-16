# Inspector API contract

**Status**: Implemented — reference doc for the completed inspector delivery plan (see `00-orchestration.md`).

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
| `GET /api/inspect/cell/{ref}/state?cursor=&limit=` | M1, V1C-BE | `CellState`. `cursor` (opaque, from a previous response's `page.cursor`) and `limit` (1..1000, clamped, default 200) walk a cell's state one bounded page at a time instead of copying it whole. Both are ignored for a cell with an open observation, which keeps answering `kind: "view"` from its already-materialized fold. A malformed `limit` is 400; a `cursor` that is unknown, expired, evicted, wrong-cell or already spent on a page is 410 — drop it and restart the walk. Exactly one `unavailable` arm is exempt: a response carrying `unreadable: "unanswered"` served no page and therefore spent no cursor, so the `cursor` it was sent with stays resumable — the client re-sends that same request instead of restarting. A 400, and the `view` an observed cell answers, likewise spend nothing (see `page.cursor`) |
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
                                   // (default "local", so M0-M4 output is unchanged). Remote cells,
                                   // since V4-PEERID: the announcing peer's OWN --net-name when that
                                   // peer named itself, and that value IS stable across a peer
                                   // reconnect — the peer re-asserts it in its re-hello. A peer that
                                   // announces anonymously still gets M5's locally derived "peer-<id>"
                                   // label, which is NOT stable across a reconnect (a reconnect mints
                                   // a new bridge egress). A peer name may collide with the local
                                   // --net-name, in which case that peer renders inside the local
                                   // hull, as claimed and not disambiguated; `host: null` above stays
                                   // the discriminator. Either label is transport-vouched, never
                                   // authenticated (spec 43): it says "the same connection identity
                                   // as before", not "the same principal", and a peer that reaches
                                   // the socket can claim any name. A client may group by it and rely
                                   // on its continuity; it must not present it as verified, trusted
                                   // or authenticated, nor use it as an authorization input.
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
  "attention": "none" | "low" | "normal" | "high" | null, // V2-BE: widened from "focus"|"idle"|null —
                                   // the field has never carried a non-null value in any shipped release
                                   // (hard-coded null through M1-M5), so no client can regress. null means
                                   // something precise, never a guess: the cell is not locally hosted, or its
                                   // host runs without an AttentionPolicy (no policy, no band in effect).
  "links": { "inbound": 2, "outbound": 3, "taps": 1 }
}

// CellState (M1, V1C-BE)
{
  "ref": "uuid:0",
  "frontier": { "source": "a3f2…", "counter": 412 } | null,
                                   // UNCHANGED, and deliberately still null for "page"/"snapshot": this is a
                                   // WAVE position, and only an observation's fold has one. A bounded read's
                                   // currency is a TagFrontier — a different clock, never a wave position —
                                   // so stamping a paged read with a wave would be exactly the lie this field
                                   // exists to prevent. What a paged read offers instead is "page.walkStable".
  "kind": "view" | "snapshot" | "page" | "unavailable",
                                   // "view"        — M1, unchanged: an open observation's materialized fold.
                                   // "snapshot"    — M1, unchanged in MEANING: one whole copy of the cell's
                                   //                 state. Now also the answer for a cell that does not
                                   //                 implement the kernel's BoundedStateful, and for a drained
                                   //                 host's checkpoint blob — so it is not a legacy value.
                                   // "page"        — V1C-BE: one bounded page of a walk. Carries "page".
                                   // "unavailable" — M1, unchanged: nothing honest to report. Now carries
                                   //                 "unreadable", which says WHICH nothing.
  "value": /* Value (below) — but see "What a page's value contains" after this block */,
  "staleMs": 120,                  // UNCHANGED: ms since the reported value last effectively changed, which
                                   // only a "view" can know. 0 for "page"/"snapshot" — a read is as fresh as
                                   // the instant it was taken, a different claim, not a better number.

  "provenance": "live" | "liveSuspended" | "checkpoint" | null,
                                   // WHERE THE BYTES CAME FROM. Non-null exactly when "kind" is "page" or
                                   // "snapshot"; null for "view" (a fold in the inspector's own heap is
                                   // neither a live cell read nor a checkpoint) and for "unavailable".
                                   //   "live"          — read from the running cell on its own execution context.
                                   //   "liveSuspended" — read from a SUSPENDED cell's own fold. Quiescent by
                                   //                     construction, so this is the most stable read in the
                                   //                     graph, not a degraded one. Reading it resumed nothing,
                                   //                     woke nothing and raised no attention.
                                   //   "checkpoint"    — read from the blob a DRAINED host already retains from
                                   //                     its drain. State as of the drain, not as of now, and no
                                   //                     cell thread was scheduled to produce it. The one
                                   //                     provenance that is stale by construction; clients MUST
                                   //                     label it.

  "page": {                        // present iff kind == "page"; null otherwise — a whole "snapshot" has no page
                                   // contract, and its absence is how a client knows no bounded read was available
    "cursor": "p-7f3a…" | null,    // OPAQUE. Echo it back verbatim as ?cursor= to fetch the next page; null means
                                   // the walk is complete. Never parse it, never construct one.
                                   // ONE ID PER SERVED PAGE. A response that serves a page mints a fresh cursor and
                                   // RETIRES the one that produced it, so a cursor re-sent after it has already
                                   // produced a page is a visible 410 rather than an invisible skipped or repeated
                                   // page. That is what "already-consumed" means, precisely: SPENT ON A PAGE.
                                   // WHAT ELSE SPENDS IT: a "snapshot" body, and every TERMINAL "unavailable" —
                                   // "migrating", "remote", "notStateful", "terminated", "readFailed", "unknown".
                                   // Those walks are over; keeping the id alive would invite a retry that cannot
                                   // succeed, so the next request carrying it is an honest 410.
                                   // THE ONE "unavailable" ARM THAT DOES NOT SPEND IT is "unreadable": "unanswered".
                                   // NO PAGE WAS SERVED — either the read itself missed the bounded wait, or (in the
                                   // render-reconciliation case) a page was read but the re-read that would have
                                   // narrowed it to exactly what it shows did not complete, so it was discarded
                                   // unserved. No page having been served, the walk position the request carried is
                                   // still exactly valid, and the server puts it back under the SAME id. The correct
                                   // client response is to RE-SEND THAT SAME REQUEST, not to restart the walk: the
                                   // retry that "unanswered" invites costs one request, whereas a mid-walk restart on
                                   // a host loaded enough to miss one bounded wait is as likely to miss another, so
                                   // restarting can starve a long walk rather than merely slow it. ?limit= may differ
                                   // on the retry — a cursor names a POSITION, not a page size. (V1C described this
                                   // as an unconditional 410; the carve-out is the shipped behaviour, computenet-1xx.)
                                   // 410 STILL MEANS RESTART and a client must still handle it: an id this server
                                   // never minted, one past its 60 s TTL, one evicted by the 256-open-walk cap, one
                                   // used against another cell, or one already spent as above. A restored id's TTL
                                   // clock is refreshed, so an actively retrying client does not age out; the cap can
                                   // still evict it, so "resumable" is a strong expectation, never a guarantee.
                                   // TWO NON-BODIES ALSO SPEND NOTHING, for the same reason — no page was served: a
                                   // 400 on a malformed ?limit= is refused before the cursor is touched, so a client's
                                   // typo does not cost it its walk; and the "view" an observed cell answers ignores
                                   // ?cursor= entirely, leaving that id untouched and resumable within its TTL.
    "limit": 200,                  // the limit actually applied; the server clamps ?limit= to 1..1000, so this is
                                   // how a client learns its request was reduced.
    "entries": 200,                // entries in THIS page, as the cell counted them before encoding — what the
                                   // cursor advanced past, and exactly the number of top-level rows "value"
                                   // renders. The server never serves a page whose entries the encoder's byte
                                   // budget cut; it re-reads a smaller page instead, and when that re-read cannot
                                   // complete it serves NO page — "unavailable" / "unanswered", with the ?cursor=
                                   // left resumable — never a short one. So a "$truncated" marker inside "value"
                                   // means one VALUE was abbreviated, never that entries went missing.
                                   // "page.cursor" is the one and only signal that more state exists.
    "exclusivesElided": 0,         // entries whose value is an Owned/Leased payload. The kernel pages a presence
                                   // descriptor and never a copy of the payload (spec 23 §Ownership), so > 0 means
                                   // this page is deliberately incomplete in a way no further page will ever fill
                                   // in. Render it: a fact about the data, not a diagnostic about the read. A
                                   // descriptor encodes as an ordinary record — {key, typeName, identity,
                                   // disposition} — where typeName is "civictech.cell.Owned"/"civictech.cell.Leased"
                                   // and disposition is "HELD" | "DISCHARGED" | "UNKNOWN". Never a payload.
    "walkStable": true | false | null,
                                   // The ONLY consistency claim a paged read makes, VERIFIED server-side rather
                                   // than promised: the walk's CLOSING tag frontier compared against its OPENING one.
                                   //   false — PROOF the fold changed mid-walk, and the strongest thing this field
                                   //           ever says. The union is a SMEARED read: it contains every entry
                                   //           present for the whole walk, may contain entries added mid-walk, and
                                   //           may miss entries removed mid-walk after being passed over. Never
                                   //           torn at entry granularity, never returns an entry twice. LATCHES.
                                   //   true  — the closing stamp equalled the opening one. NECESSARY, NOT
                                   //           SUFFICIENT for "the union is a snapshot". A TagFrontier measures tag
                                   //           GAINS and only tag gains, so an observed-remove (which mints no tag)
                                   //           is invisible to it everywhere; and in the NON-RETAINING families —
                                   //           every cell under civictech.cell.data.op, plus ShardCell — the stamp
                                   //           can also FALL, so a mid-walk gain can be masked by a mid-walk loss
                                   //           and equality excludes nothing at all. Render "true" as "not observed
                                   //           to change", NEVER as "this is a snapshot". True on page 1, which
                                   //           compares the opening stamp with itself.
                                   //   null  — NOT DETERMINED, and this is the value an INTERMEDIATE page of a
                                   //           multi-page walk carries: such a page holds only the opening stamp
                                   //           (see caveats "staleFrontier"), so the verdict is not available until
                                   //           the walk closes. Also null when the cell reports no tag frontier at
                                   //           all (MapCell, ListCell, Watermark, InstanceSet). Render it as
                                   //           neither; it is not a "false".
    "caveats": ["staleFrontier"],  // the KERNEL's own declared weakenings, forwarded rather than inferred and
                                   // ACCUMULATED across the walk, so a client joining at page 4 still learns them.
                                   //   "staleFrontier"    — this page carries the walk's opening frontier rather
                                   //                        than the fold's frontier now; the first and last page of
                                   //                        a walk always carry an exact one. This is why
                                   //                        "walkStable" is null in between.
                                   //   "positionalCursor" — the cursor is positional, not key-based (the documented
                                   //                        exception for a family with no element identity —
                                   //                        ListCell). "No entry twice in one walk" and "every
                                   //                        surviving entry appears" both weaken to best-effort.
                                   // Unknown values must be ignored, not rejected.
    "attributes": { "counter": 412 }
                                   // cell-level state that is not a per-entry row and rides EVERY page — SetCell's
                                   // and KeyedSetCell's tag "counter", ShardCell's "interest"/"assignedEpoch", the
                                   // operator family's "mintCounter"/"lanes". Each value is an encoded Value, like
                                   // "value". Surfaced rather than dropped because a client reading page 4 of a
                                   // shard walk would otherwise be unable to tell whether the walk straddled a
                                   // repartition. `{}` when the cell has none. Keys are per-family; a client renders
                                   // what it recognises and ignores the rest.
  } | null,

  "unreadable": "migrating" | "remote" | "notStateful" | "unanswered" | "terminated" | "readFailed"
              | "unknown" | null
                                   // present iff kind == "unavailable" — WHY there is nothing to report.
                                   //   "migrating"   — held for a repartition flip, or already migrated. The
                                   //                   authoritative instance is another host's; a stale local read
                                   //                   would be a lie with a timestamp on it.
                                   //   "remote"      — no local host. A wave-neutral read is not an emission and so
                                   //                   passes through no disclosure filter; it does not cross a
                                   //                   bridge. Unchanged from M5, and deliberate.
                                   //   "notStateful" — the cell holds no readable state at all.
                                   //   "unanswered"  — the read did not land inside the server's bounded wait, or its
                                   //                   render reconciliation could not complete. No page is served —
                                   //                   nothing partial, and nothing at all — and a retry may succeed.
                                   //                   THE ONLY ARM HERE THAT DOES NOT SPEND A ?cursor=: re-send the
                                   //                   identical request to resume the walk — see "page.cursor".
                                   //   "terminated"  — the host's scheduler is gone. A dead host has no state to
                                   //                   read, and a retry will not help.
                                   //   "readFailed"  — the cell's own readBounded()/snapshot() threw. A broken
                                   //                   cell, not a broken read.
                                   //   "unknown"     — a kernel reason this server build does not map.
                                   //                   Forward-compatibility, never a guess.
                                   // A client that does not recognise a value renders a plain "unavailable" rather
                                   // than failing.
}
// All four V1C-BE additions are ADDITIVE and DEFAULTED, so an M1–V3 client decoding this shape is
// unaffected and a client coded against this shape decodes an older server's response unchanged.

// What a page's "value" contains — RULED AT C10, and NOT what a "snapshot" value contains for the
// same cell. A page's "value" is the encoding of the KERNEL'S OWN PAGE ENTRIES, forwarded verbatim;
// it is NOT the interpreted state ValueEncoder.normalize produces for a whole snapshot. For the
// OR-set family that is a $table with columns element | addTags | delTags, where each tag set is
// itself a nested $table of sourceId | counter — and where an element is a member IFF it holds an
// add-tag with no matching del-tag. The client must apply that rule itself: there is no membership
// column, and TOMBSTONED ELEMENTS APPEAR AS ROWS. For the composite operator family the columns are
// subState | element | tags | lane and friends, always carrying the sub-state label; for MapCell,
// key | value.
//   WHY, since it is strictly less readable than the snapshot form: interpreting page entries into
//   membership would DROP the tombstoned rows, so the rendered rows would be fewer than
//   "page.entries" — the cursor would have advanced past entries that appear nowhere in the
//   response, which is precisely the silent loss "page.entries == rendered rows" exists to forbid,
//   and a page of only-tombstones would render as an empty table with a non-null cursor. Doing it
//   correctly instead needs a per-family interpreter across every paged cell family, which is its
//   own scope; it is filed for the replan, not papered over in normalize. Consequence to expect: a
//   paged big-cell view shows STORED state where the snapshot view of the same cell showed
//   INTERPRETED state, and the two do not render identically.

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
// SCOPED BY V1C-BE: that sentence is about the INTERPRETED forms — "view" and
// "snapshot", where ValueEncoder.normalize folds the kernel's tag algebra to the
// membership it encodes. It does NOT hold for kind: "page", which forwards the
// cell's own page entries verbatim and therefore DOES carry a tombstoned element
// as an ordinary row (element | addTags | delTags, member iff an add-tag has no
// matching del-tag). See "What a page's value contains" under CellState.
// $truncated AND A CURSOR ARE DIFFERENT FACTS (V1C-BE). On a "page" response the
// marker only ever means ONE VALUE WAS ABBREVIATED — never that entries went
// missing: the server encodes a page under an unbounded row allowance and, if the
// BYTE budget cut whole entries, re-reads the page at the smaller limit rather
// than serving it short — and serves no page at all when that re-read cannot
// complete (see "page.cursor") — so "page.entries" always equals the top-level
// rows rendered. "page.cursor != null" is the one and only signal that more state
// exists. The 200-row bound above still applies verbatim to "view"/"snapshot".

// ErrorSnapshot (M2, V3-BE)
{
  "counters": { "deadLetters": 3, "parked": 14, "restarts": 1, "drainedOnTeardown": 0, "waveHealth": 2 },
  "deadLetters": [ { "ref": "uuid:0", "cause": "OwnershipViolation" | null,  // null for a plain drop (e.g. unknown target), no thrown exception
                     "description": "...", "wave": {"source":"9c41","counter":288} | null,
                     "atMs": 1753600000000,
                     "invocation": {                    // V3-BE — null: a plain host-level drop, no invocation
                       "port": "left", "type": "PORT_API" | "PORT_MANAGEMENT" | "PORT_PROTOCOL",
                       "method": "propagate", "parameterTypes": ["civictech.cell.data.SetDelta"],
                       "argCount": 1, "hop": 2 | null
                     } | null,
                     "disposition": [                   // one entry per argument, in order; [] when no invocation/no args
                       { "index": 0, "ownership": "frozen" | "redacted" | "borrowed" | "owned" | "leased" | "plain",
                         "reason": "Leased payload released at dead-letter capture" | null }
                     ],
                     "denial": {                         // computenet-4ixu — non-null iff this row is a BoundaryPolicy
                       "seam": "ADMISSION" | "LINK_AUTHORITY" | "PROTOCOL_AUTHORITY" | "DISCLOSURE" | "INTEGRITY",
                       "reason": "NOT_ADMITTED" | "LINK_REFUSED" | "MIN_AUTH" | "RATE" | "DISCLOSURE_DENIED" |
                                 "DISCLOSURE_PROJECTED_AWAY" | "UNSIGNED" | "BAD_SIGNATURE" | "REPLAY",
                       "exposure": "feed", "principal": "peer-1" | null,
                       "subject": "civictech.contract.Foo#bar" | null, "detail": "counter=41" | null
                     } | null } ],
  "parked": [ { "ref": "uuid:0", "port": "left", "count": 11, "oldestMs": 41000 } ],
  "restarts": [ { "ref": "uuid:0", "generation": 1, "atMs": ...,
                  "cause": "IllegalStateException" | null,     // V3-BE, see below
                  "causeAtMs": 1753600000000 | null,
                  "reBaselineAtMs": 1753600000000 | null } ],
  "waveHealth": [ /* WaveHealthRow, open rows only — see the SSE table's error.waveHealth row */ ]
}
// V3-BE's dead-letter `invocation`/`disposition` is the kernel's own dead-letter sanitization
// outcome read back, not an inspector classification: the dead-letter outlet is a fan-out, so an
// Owned argument arrives already Frozen (or Redacted if consumed before capture) and a Leased
// argument arrives released and replaced by Redacted. "owned"/"leased" remain in the vocabulary as
// the honesty case — a live exclusive handle reaching this outlet is a kernel invariant violation,
// and the row says so rather than mislabelling it. `reason` is populated only for "redacted", is the
// kernel-authored Redacted.reason truncated at 200 characters, and is the only text ever taken from
// the argument. Never-retain guarantee: no argument value appears on the row in any form — not the
// value, not its toString(), not an encoded form — and no reference to it survives the capture call.
// `parameterTypes` are declared type names, never values.
//
// `deadLetters[].denial` (computenet-4ixu, closing the residual left open by computenet-usd.7)
// is the structural discriminator between a BoundaryPolicy refusal and a plain host-level drop —
// both are `cause: null`, and before this field the only way to tell them apart was parsing
// `description`'s "boundary denial at exposure" prefix. Non-null exactly when `cause == null` AND
// the drop was a policy refusal, never alongside a non-null `cause` — a denial is never classified
// as a cell fault (server BoundaryDenial/BoundarySeam/DenialReason). `principal`/`subject` follow
// the seam-dependent convention the server's own KDoc documents (e.g. `principal` is null for a
// LocalTrusted crossing, not an absence of information); a client must not assume one meaning
// across seams. Additive and defaulted (kotlinx `encodeDefaults = true`, so the key is always
// present, `null` on every non-denial row) — an older client decoding this shape is unaffected.
//
// `restarts[].cause`/`.causeAtMs` are a TIME-WINDOW CORRELATION, not a kernel-reported restart
// cause: no seam reports the failure and the restart as one event. `cause` is the simple class name
// of the most recent dead letter captured for the same ref within 5000ms preceding the observed
// generation bump; a coincidental dead letter for the same ref inside that window would be
// attributed here. Both null when no candidate exists — never a guess.
// `reBaselineAtMs` is when a re-baseline beat was observed on one of this cell's tapped outgoing
// edges. null means NOT OBSERVED, never "did not happen": only a ReBaselineEmitting cell
// re-baselines at all (today civictech.cell.data.op.UnionSetCell is the only kernel implementation),
// and the cell needs at least one tapped outgoing edge for the inspector to see the beat. Clients
// must render absence, not a negative claim.

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
// RESOLVED — health does NOT roll up wave-health rows, and will not.
// (V3-BE raised it deliberately unanswered; decided at the inspector-v4
// C-replan checkpoint, 2026-07-29.) The three fields health already carries are
// properties of the component: dead letters arrive on a per-host tap, parked and
// restarts are sampled over every known ref, all without a client asking for
// anything. Wave-health rows are not. A row exists only for a (tapped edge,
// explicitly-observed cell) pair, and the observed set is exactly what some
// client asked for — so "waveHealth: 2" on a component would mean "2 rows among
// the cells somebody happens to be watching", and GraphList is one server-wide
// snapshot with no place to say whose attention produced it. The zero-observation
// case has no honest value either: 0 would render as healthy where it means
// unexamined. health is already softened by ring-buffer retention (above);
// compounding that with an attention-dependent term would make the field mean
// nothing in particular.
// A client that wants the roll-up can compute it exactly and label it honestly:
// ErrorSnapshot.waveHealth carries every open row with its ref, and the client
// knows each ref's Node.graph. Doing it client-side keeps the caveat — "scoped to
// your own observations" — attached to the number, which is the whole point.

// SearchResult (M4/M5)
{
  "mode": "name" | "problems" | "data",
  "hits": [ { "graph": "g-…", "ref": "uuid:0" | null, "label": "...", "detail": "..." } ],
  "cost": { "cellsQueried": 4, "coldSkipped": 2 } | null   // non-null on every "data" mode response
                                                            // (including zero hits or a blank query),
                                                            // null for "name"/"problems". coldSkipped
                                                            // counts skipped CELLS, not graphs (M5)
                                                            // NARROWED BY V1C-BE: coldSkipped now counts
                                                            // cells HELD FOR A MIGRATION FLIP AND NOTHING
                                                            // ELSE. A suspended cell and a cell on a drained
                                                            // host are no longer skipped — the kernel reads
                                                            // the first from its own quiescent fold and the
                                                            // second from the checkpoint blob its host
                                                            // already holds, neither of which wakes anything
                                                            // — so both are searched and counted in
                                                            // cellsQueried. CONSEQUENCE FOR CLIENTS: the
                                                            // remedy wording "wake their graph to include"
                                                            // is now WRONG for every cell this counts.
                                                            // Waking a held ref does nothing (and would at
                                                            // best interrupt the flip); the honest remedy is
                                                            // none — it is not the inspector's to end.
}
// A "data" mode hit whose "graph" is "" (empty, not null) is a closing NOTICE, not a navigable
// result — it names what the search did not fully cover (the 50-cell cap, the 2s deadline, a
// cell read only to its first 200 rows, cold components skipped). Render it inert. (M5)
// V1C-BE re-cut the notice's clauses: a cell read to its bounded page reports "read only their
// first 200 ENTRIES" (a real read limit, where M5's "200 rows" described a rendering limit while
// implying a read one); a cell with no bounded read reports the whole-state copy it cost; a cell
// answered from a drained host's checkpoint reports that its state is as of the drain; and the
// held-cell clause offers no remedy. Only the cap, the deadline, an unanswered read, a refused
// read, a partial page and a render-budget cut make a result PARTIAL — a whole copy and a
// checkpoint read are complete coverage, reported as cost and as staleness respectively.
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
| `lifecycle` | M0, V2-BE | `{ "ref", "lifecycle", "generation" }` — mechanism change, not a shape change (V2-BE): pushed at the transition off the kernel's lifecycle listener rather than sampled at 1 Hz, and fires exactly once per real transition (a re-publish that does not move the lifecycle, e.g. a host resume observed while still draining, emits nothing) |
| `activity` | V2-BE | one `ActivityEntry`: `{ "ref", "kind": "passivated"\|"activated"\|"drained"\|"woken"\|"restarted", "atMs", "generation"\|omitted }` — `generation` present only on `restarted`. `passivated`/`activated`/`drained` come from the kernel's per-cell lifecycle listener (passivated covers both an explicit suspend and a supervision suspend, indistinguishable at this seam); `woken` is the inspector's own causal act (`POST /graph/{id}/wake`), recorded before the resume calls it triggers, so it precedes the `activated` it causes — a single wake legitimately yields both, neither is suppressed; `restarted` is a supervision restart, observed as a generation increase. Rides the shared monotonic `seq`. Paired with `GET /api/inspect/activity` → `{"entries": [ActivityEntry]}`, oldest first, bounded at 200 for the whole process (not per cell); an empty ring answers `{"entries": []}`, never 404 |
| `state.summary` | M1, V1A-BE | `{ "ref", "cardinality": "4 rows"\|null, "frontier": {...}\|null, "staleMs", "changes": 3 }` — only for cells a client explicitly observed: every summary belongs to an observation that was open when the window was built, including the single trailing window a release publishes as its last act; a cell with no observe subscription never produces one. One coalesced window per second per observed cell (V1A-BE) — an arbitrary number of settled effective changes inside a window produce **one** summary carrying the *latest* reading, never an intermediate one. Publishes every window while the observation is open, even a quiet one (so a client's staleness/decay logic can key off "window received" rather than off silence), then exactly one trailing window when the observation is released — by `DELETE`, the 5-minute idle sweep, or inspector shutdown — then nothing for that cell. `staleMs` is computed at publish time from the last effective change: it decreases in a window where something settled and grows by roughly one window across consecutive quiet ones. `changes` is additive: how many settled effective changes this window coalesced, `0` for a quiet window — the exact change signal, where `staleMs`'s decrease is a heuristic that can miss a change landing almost exactly one window after the previous one. Guarantee: an effective state change on an observed cell is announced within one window, so a client that refetches `GET /cell/{ref}/state` on a summary it judges to indicate change can never be left holding a stale value indefinitely. There is no immediate catch-up summary at observe-start beyond the first scheduled window (≤1s later) — a client wanting an instant first paint does its own `GET /cell/{ref}/state` on observe success, as `inspect/ui` already does |
| `error.deadLetter` | M2, V3-BE, computenet-4ixu | one `deadLetters[]` element, now with `invocation`/`disposition`/`denial` — see the `ErrorSnapshot` DTO note above |
| `error.parked` | M2 | one `parked[]` element (send on change; `count: 0` clears) |
| `error.restart` | M2, V3-BE | one `restarts[]` element, now with `cause`/`causeAtMs`/`reBaselineAtMs` — see the `ErrorSnapshot` DTO note above |
| `error.waveHealth` | V3-BE | one `WaveHealthRow`: `{ "id", "kind": "frontierLag"\|"stalledWave", "state": "open"\|"cleared", "ref", "edge", "wave": {...}\|null, "frontier": {...}\|null, "lagWaves": 7\|null, "heldMs", "atMs", "heuristic": true, "description" }`. `id` is `"<kind>:<edgeId>:<ref>"`, stable across the open row, its updates and its clear. `state: "cleared"` retires the open row with the same `id` and carries its last known field values — the same discipline `error.parked`'s `count: 0` already established. `wave`/`frontier` may be null; `lagWaves` is populated only when both stamps share a `source` (two different sources are incomparable and never subtracted). Paired with `ErrorSnapshot.waveHealth` (open rows only, a gauge like `parked`, never a history log) and `counters.waveHealth` (also a gauge, unlike its monotonic siblings). Bounded at 200 simultaneously open rows; an eviction forced by that cap emits the evicted row's `cleared` event. **This class is a heuristic diagnostic, not kernel-grade detection** — computed by the inspector from outside the graph by correlating a tapped outlet's last observed wave with an explicitly-observed cell's frontier stamp. Absorption, filtering and aggregation are legitimate and indistinguishable from a stall at this vantage point (spec 20/22, completeness over silent/stuck edges, **G-40**). Every row carries `heuristic: true` and its `description` opens with the word "heuristic"; no row asserts that a wave *is* lost, that a cell *is* stuck, or that glitch-freedom *is* violated |
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
