# V1C-BE — the inspector stops copying whole cells: a paged state endpoint, a bounded data search, and cold cells that answer instead of lying

**Status**: Specified — not-started
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 10 · **Branches:** `ticket/v1c-be`

Runs in parallel with `V1C-FE` (owns `inspect/ui/**` only) and `V4-PILOT`
(owns `demo/**`). Branches from `main` after wave 9 merged.

## Context

You are working on `:inspect`, the Inspector backend: a read-only HTTP/SSE view
of a live ComputeNet host process. Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full first —
its §"Binding constraints" (all ten) governs this ticket absolutely, and
constraint 8 is why you never edit
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` yourself.

This is the consumer half of the V1c vertical. Three kernel-side tickets landed
before you:

- **`V1C-BENCH`** (wave 7) measured what a whole-state copy costs and issued a
  GO/RESIZE/NO-GO recommendation in
  `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`.
- **`V1C-KERNEL`** (wave 8) shipped the primitive — read
  `tickets/V1C-KERNEL.md` in full, and read its **completion report** for the
  verbatim final signatures, because it was permitted to deviate from its own
  sketch and to choose between two arms of Decision 7.
- **`V1C-CELLS`** and **`V1C-OPS`** (wave 9) implemented `BoundedStateful`
  across the data-cell and operator families.

The **shipped** interface, transcribed from `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`
at merge `4f633d2` by the C8 checkpoint. This replaces the sketch this ticket
was originally written against; the four differences that change your work are
called out under "What C8 corrected" below. Read `BoundedRead.kt`'s KDoc in
full anyway — the contract sentences you put on the wire are written there.

```kotlin
interface BoundedStateful : Stateful {
    fun readBounded(request: StateRead): StatePage        // bare page; the result arms are the host's
    val supportsSince: Boolean get() = false              // constant for the cell's lifetime
    val supportsScope: Boolean get() = false
}

data class StateRead(
    val cursor: Cursor? = null, val limit: Int = 200, val byteBudget: Int = 50_000,
    val scope: Interest? = null, val since: TagFrontier? = null, val allowWholeCopy: Boolean = false,
)
data class StatePage(
    val entries: List<Serializable>,                      // may contain ExclusiveEntry descriptors
    val next: Cursor? = null,
    val frontier: TagFrontier? = null,
    val provenance: Provenance = Provenance.LIVE,
    val exclusivesElided: Int = 0,
    val attributes: Map<String, Serializable> = emptyMap(),
    val caveats: Set<ReadCaveat> = emptySet(),
)
@JvmInline value class Cursor(val token: Serializable)
enum class Provenance { LIVE, LIVE_SUSPENDED, CHECKPOINT }
enum class ReadCaveat { STALE_FRONTIER, POSITIONAL_CURSOR }
data class ExclusiveEntry(
    val key: Serializable?, val typeName: String, val identity: Int, val disposition: Disposition,
) : Serializable { enum class Disposition { HELD, DISCHARGED, UNKNOWN } }

sealed interface StateReadResult {
    data class Page(val page: StatePage) : StateReadResult
    data class Unbounded(val state: Serializable, val provenance: Provenance = Provenance.LIVE) : StateReadResult
    data class Unavailable(val reason: Reason) : StateReadResult
    enum class Reason {
        NOT_HOSTED, NOT_STATEFUL, NOT_BOUNDED, CHECKPOINT_NOT_BOUNDED, MIGRATING,
        SINCE_UNSUPPORTED, SCOPE_UNSUPPORTED, SCHEDULER_TERMINATED, READ_FAILED,
    }
}

fun ManagedHost.readState(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult>
```

### What C8 corrected in this ticket

Four shipped facts differ from the sketch above the way this ticket was
originally written, and one of them changes a semantic you were told to put on
the wire. Treat these as ticket text, not as background.

1. **`walkStable` cannot be computed by comparing every page against page 1.**
   The shipped `SetCell` stamps `frontier` **exactly on the first and last page
   of a walk only**; an intermediate page carries the *opening* frontier and
   declares `ReadCaveat.STALE_FRONTIER`. A per-page equality test would
   therefore report `true` on every intermediate page of a walk whose fold had
   already moved, and only flip to `false` on the closing page — the opposite of
   an honest verdict. **Compute `walkStable` as: `null` while any page so far
   carried `STALE_FRONTIER` and the walk has not closed; `true` when the closing
   page's frontier equals page 1's; `false` when it does not.** A `TagFrontier`
   is monotone, so equal endpoints prove every intermediate stamp equal too —
   the verdict is complete, it is just not available before the walk closes. Say
   so in the field's own wire comment, and do not weaken the `false` case: an
   advanced frontier is still the documented smear.
2. **`walkStable: true` is necessary, not sufficient, for the OR-set family.**
   `StatePage`'s KDoc now says the check detects tag *gains* and only tag gains.
   An OR-set observed-remove mints no tag, so a mid-walk removal of an
   already-paged element leaves both endpoint stamps equal while the union still
   names that element present. Your wire comment for `true` must not promise
   more than the kernel does — "no tag was gained during this walk", not "this
   is certainly a snapshot".
3. **`StatePage.attributes` exists**, and carries cell-level state that is not a
   per-entry row and rides *every* page — `SetCell`'s tag `counter`, and (from
   `V1C-CELLS`) `ShardCell`'s `interest`/`assignedEpoch`. Decide and state
   whether `page` surfaces it or drops it; dropping it silently is not an
   option, because a client reading page 4 of a shard walk would then be unable
   to tell whether the walk straddled a repartition.
4. **`Reason` has nine arms, not the four this ticket assumed.** Your
   `unreadable` vocabulary must map `SCHEDULER_TERMINATED` and `READ_FAILED`
   explicitly rather than letting them fall through to `"unknown"` — both are
   real, reachable answers about a live local host, which is exactly what
   `"unknown"` was reserved *not* to mean. `NOT_BOUNDED`,
   `CHECKPOINT_NOT_BOUNDED`, `SINCE_UNSUPPORTED` and `SCOPE_UNSUPPORTED` are
   unreachable for you as long as you pass `allowWholeCopy = true` and neither
   `since` nor `scope`; say that in the report rather than mapping them blind.

Two smaller confirmations, so you do not have to derive them: Decision 7's
drained arm shipped as **`Unbounded(blob, Provenance.CHECKPOINT)`** under
`allowWholeCopy` (so a drained cell answers `kind: "snapshot"`, never
`kind: "page"`, and the acceptance criterion at the end of this ticket resolves
to the `"snapshot"` branch); and **`provenance` is minted by the host, not the
cell** — `readState` overwrites a cell's `LIVE` with `LIVE_SUSPENDED` when the
ref is parked, and the cell never sees `CHECKPOINT` at all.

`readState` is modelled line-for-line on `ManagedHost.snapshotOf`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1201-1263`,
signature at `:1244`): callable from any thread against any scheduler
(`:1223-1234`), cancellation-honouring (`:1236-1242`), completing with a value
rather than exceptionally. **One page = one scheduler task.**

### The three capabilities this unblocks

`20-wave-neutral-read-design.md` §1.5 names them, and they are the whole point
of this ticket:

> `DataSearch`'s bounds — `MAX_CELLS = 50`, `BUDGET_MS = 2_000` — are sized
> around whole-state copies on cell threads, and the closing notice it emits is
> the user-visible confession … Three inspector capabilities are blocked on the
> same missing bound: browse-everything state chips (a summary on many cells at
> once), honest data search, and big-cell state views.

§7's go/no-go is **"Go — conditionally, and split in two"**: the local read
now, no-go on the remote arm. This ticket is the local read's consumer side.

### What the inspector reads state with today

- **`GET /api/inspect/cell/{ref}/state`** — `InspectorServer.serveState`
  (`inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:552-568`, KDoc
  `:543-551`). An open observation answers from its materialized fold
  (`Observations.reading`, `Observations.kt:299-302`); otherwise
  `Observations.snapshotReading` (`:304-311`) calls the `SnapshotSource` seam
  (`:99-107`), whose shipped default routes `ManagedHost.snapshotOf` under
  `SNAPSHOT_WAIT_MS = 200` (`InspectorServer.kt:230-236`, constant at `:883`,
  read-through wiring at `:242`); otherwise `CellState(kind = "unavailable")`
  (`InspectorServer.kt:556-558`; the constant's KDoc at `Dto.kt:191-192` reads
  "No observation and no snapshot source — nothing honest to report").
- **`GET /api/inspect/search?mode=data`** — `DataSearch`
  (`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt`). Its class KDoc's
  "How state is read, and why not `StateRequest`" argument is `:28-59`; the
  read itself is `host.snapshotOf(ref)` at `:211` inside `read()` (`:208-220`).
- **The truncation is downstream of the copy.** `ValueEncoder.MAX_ROWS = 200`
  (`ValueEncoder.kt:53`) and `MAX_BYTES = 50_000` (`:56`), enforced by `Budget`
  (`:341-348`) over an already-materialized value. The cell's thread pays for
  10⁵ rows so 200 can be rendered.
- **Non-hot cells are skipped, not read.** `Heat.isReadable` is true only for
  `HOT` (`Cold.kt:98`); `Heat.of` is `Cold.kt:109-117`. `DataSearch` skips a
  whole cold component without a per-cell walk (`:125-131`, rationale comment
  `:120-124`) and skips lone non-hot cells per-cell (`:135-147`,
  `coldSkipped += 1` at `:137`, predicate at `:185-188`).

## Problem

1. **Every unobserved state read is a whole-state copy on a cell's thread.**
   The endpoint and the search both go through `snapshotOf`, and the bound the
   user actually sees (200 rows) is applied *after* the copy, in the encoder.
   A 10⁵-row cell costs its own thread a full copy so a panel can show 200
   rows, and there is no way to ask for less.

2. **`DataSearch`'s cost model is a confession, not a design.** `MAX_CELLS = 50`
   (`DataSearch.kt:345`), `BUDGET_MS = 2_000` (`:348`) and the closing notice
   (`:309-339`) exist *because* every read is unbounded. The notice's clause
   `"$n cells read only to the first ${ValueEncoder.MAX_ROWS} rows"` (`:321-323`)
   describes a *rendering* limit while implying a *read* limit — the cell paid
   for all of it.

3. **Suspended and drained cells are told a lie about themselves.** A suspended
   cell's fold is quiescent by construction — the *most* stable thing in the
   graph to read — and a drained host already holds a checkpoint blob of every
   cell it held (`ManagedHost.kt:499-502`, written at `:501`). Both are skipped
   by search (counted into `SearchCost.coldSkipped`) and both answer
   `unavailable` on `GET state`, because `snapshotOf` submits to a scheduler
   that will never run the task. The frontend has made that a promise in
   `inspect/ui/src/nav/cold.ts:13`:
   `COLD_NOTICE = 'cold — parked; state/flow unavailable without waking'`, whose
   own comment (`:10-12`) names the checkpoint/journal capability as a tracked
   kernel gap. `V1C-KERNEL` Decision 7 closed that gap. **The promise must
   change, and this ticket hands the change to `V1C-FE`.**

4. **`kind: "unavailable"` says nothing about which nothing it is.** Held for a
   migration flip, remote, not `Stateful`, and "the read missed its deadline"
   are four different facts and one word.

## Solution direction

The **what** is decided below. The **how** — collaborator decomposition, where
the cursor table lives, internal data structures — is yours; a new file under
`inspect/src/main/kotlin/civictech/inspect/` is the expected shape if the paged
read deserves its own collaborator, the way `Flow.kt` and `WaveHealth.kt` do.

Four parts. Part 2 is contract-binding and `V1C-FE` codes against it in
parallel, so it is written out in full.

---

### Part 1 — a paged state endpoint

`GET /api/inspect/cell/{ref}/state` grows two optional query parameters and
answers from `ManagedHost.readState` instead of the whole-copy fallback.

**Query parameters** (parsed with the existing `HttpExchange.query()` helper,
`InspectorServer.kt:942-950`):

- `?cursor=<opaque>` — a `page.cursor` from a previous response. Absent = the
  start of a fresh walk.
- `?limit=<int>` — `1..PAGE_LIMIT_MAX`, clamped by the server (the applied
  value is reported back as `page.limit`). Absent = `PAGE_LIMIT_DEFAULT`.
- A `limit` that is not a positive integer is **400** with the existing
  `problem(reason)` body shape (`InspectorServer.kt:919-920`), matching
  `serveSearch`'s "unknown search mode" 400 (`:460`).
- An unknown, expired, already-consumed or wrong-cell `cursor` is **410**
  with the same body shape. The client drops the cursor and restarts the walk.
  410 rather than a silent restart: silently restarting would let a client
  believe it was continuing a walk it was not, which is the class of lie this
  whole vertical exists to remove.

**The read chain in `serveState`**, in this order:

1. `model.knows(ref)` → 404, unchanged.
2. `observations.touch(ref)` — the contract's "matching `GET state`", unchanged.
3. **An open observation answers from its materialized fold** (`kind: "view"`),
   exactly as today. **Do not regress this path**: the fold is already
   materialized in the inspector's own heap, it is free, and it is already
   consistent. `cursor`/`limit` are *ignored* for an observed cell, the
   response carries no `page` object, and the client's walk therefore
   terminates on page 1 with whatever the encoder's existing 200-row budget
   renders. `View` paging is explicitly out of scope (`V1C-KERNEL` Decision 9);
   record the consequence as a residual in your report.
4. **Otherwise the bounded read**, through a new seam beside `SnapshotSource`
   (see below), with `allowWholeCopy = true`:
   - `Page` → `kind: "page"`, `provenance` from the kernel's `Provenance`,
     `page` populated.
   - `Unbounded` → `kind: "snapshot"`, `provenance` from the arm that answered
     (`"live"` for a live host, `"checkpoint"` if `V1C-KERNEL` chose the
     `Unbounded(blob)` arm for a drained host — see Part 4). This is today's
     behaviour, byte-identical, for a cell that does not implement
     `BoundedStateful`.
   - `Unavailable(reason)` → `kind: "unavailable"` with `unreadable` mapped
     from the kernel's reason. **Never fall back to `snapshotOf` after an
     `Unavailable`**: for `MIGRATING` that would answer a stale local read,
     which is the lie Decision 7 forbids, and for a drained host it would burn
     a second deadline on a scheduler that will not run.
   - The read did not land inside `SNAPSHOT_WAIT_MS` → `kind: "unavailable"`,
     `unreadable: "unanswered"`, and `cancel(false)` on the future, mirroring
     the existing pattern at `InspectorServer.kt:230-236`. **One deadline per
     request, never two.**
5. `observations.snapshotReading(ref)` remains as the last fallback, so a test
   that installs its own `SnapshotSource` still works (see "Existing tests that
   change", below).

**Why `allowWholeCopy = true`.** Passing `false` would make every cell that
`V1C-CELLS`/`V1C-OPS` did not cover regress from `kind: "snapshot"` to
`kind: "unavailable"` — a strict loss against shipped behaviour. The flag
exists so a caller learns the cost before paying; the detail panel's honest
answer is "you got the whole copy, and the absent `page` object is how you know
there was no bounded read available".

**The seam.** Add a sibling to `SnapshotSource` (`Observations.kt:99-107`)
rather than widening it: `SnapshotSource` is public API, three existing tests
install stand-ins through `InspectorServer.snapshots`
(`InspectorObserveTest.kt:360`, `:372`), and opt-in-beside is the same shape
`BoundedStateful` took beside `Stateful`. Expected shape:

```kotlin
fun interface BoundedReadSource {
    /** [ref]'s next page, captured on its host's execution context; null when this seam did not answer. */
    fun readState(ref: CellRef, request: StateRead): StateReadResult?
    companion object { val Unavailable = BoundedReadSource { _, _ -> null } }
}
```

with the shipped default wired in `InspectorServer` beside `snapshots`
(`:230-236`) — `registry.locate(ref)?.readState(ref, request)` under
`SNAPSHOT_WAIT_MS`, cancel-on-miss — and held in an `internal var` for the same
test-stand-in reason `snapshots` is. Extend `SNAPSHOT_WAIT_MS`'s KDoc
(`:872-883`) rather than adding a second constant for the same wait; a page is
strictly cheaper than the whole copy that constant was sized for.

**The cursor table.** `Cursor(token: Serializable)` is kernel-minted and opaque,
and must not be reconstructed from client input — Java-deserializing a
client-supplied token would be a deserialization sink on an HTTP endpoint, and
no dev-instrument convenience is worth that. So the string a client echoes is a
**server-minted id into a bounded table**, not an encoding of the token:

- entry = `(id, CellRef, kernel Cursor, walk identity, first page's TagFrontier,
  stableSoFar, mintedAtMs)`;
- **one id per page**: each response mints a fresh id for its `next` cursor and
  retires the id that produced it, so an accidentally re-sent cursor answers
  410 (visible) instead of silently skipping a page (invisible). The walk's
  identity and its running `walkStable` verdict are inherited by the successor
  id;
- an id is bound to its `CellRef`; used against another cell it is 410;
- bounded: `CURSOR_TTL_MS = 60_000`, `CURSOR_MAX_OPEN = 256`, oldest evicted, a
  final page (`next == null`) retires its entry immediately. A UI that
  abandoned a walk must not pin server state for longer than a minute.
- **No client-supplied bytes are ever deserialized.** State this in the KDoc.

This table is the HTTP endpoint's, not the read seam's: `DataSearch` reads one
page per cell and never resumes, so it mints no entries.

**`walkStable`.** The table entry is also what makes `V1C-KERNEL` Decision 5's
*verifiable* stability claim checkable without asking the client to do
bookkeeping it cannot do: it holds page 1's `TagFrontier` and compares each
subsequent page's against it. See Part 2 for the exact semantics of the three
values. Do **not** put a raw `TagFrontier` on the wire: it is a
`Map<UUID, Long>` (`MessageContext.kt:58-72`) whose size grows with the tag
source count, the client cannot construct one, and the only actionable fact is
the verdict.

**Page/render reconciliation — the `$truncated` question, answered.** The
encoder's budget must never silently swallow entries the cursor has already
advanced past. The rule:

> A page is served only when **every entry the kernel returned was rendered**.
> Encode the page under a budget of `rows = page.entries.size` and
> `bytes = ValueEncoder.MAX_BYTES`. If the byte budget cut whole entries
> (rendered < returned), **re-issue the same `readState` with
> `limit = rendered.coerceAtLeast(1)`** and serve that page instead, so the
> cursor names exactly the entries that were shown. At most
> `PAGE_RENDER_RETRIES = 1` retry.
>
> `$truncated` may then still appear *inside* a rendered entry — one wide record
> abbreviated — which is the marker's existing, unchanged meaning: **this value
> was abbreviated**, never **this walk is incomplete**. `page.cursor != null` is
> the one and only signal that more state exists.

This needs an additive `ValueEncoder` entry point that takes an explicit row/byte
allowance (the existing `encode(state)` at `:72` and both constants stay exactly
as they are for every existing caller) and a way to learn how many entries were
rendered. Keep `normalize` (`:221-224`) in the path: a page's entries go through
the same interpretation and `$table` detection as a snapshot's, so the FE's
existing `ValueView` needs no new shape. If a cell's page entries arrive as raw
tag algebra rather than as interpreted membership, that is a
`V1C-CELLS`/`V1C-OPS` defect — **report it, do not paper over it in
`normalize`**; adding a `normalize` arm for a genuinely new *page-entry* shape
is permitted but must be justified in the report.

**Named constants, one place, each with a comment:**

| Constant | Value | Protects |
|---|---|---|
| `PAGE_LIMIT_DEFAULT` | 200 | Matches `ValueEncoder.MAX_ROWS` and `StateRead`'s own default; what the FE renders |
| `PAGE_LIMIT_MAX` | 1 000 | Ceiling on one page = one scheduler task |
| `CURSOR_TTL_MS` | 60 000 | An abandoned walk must not pin server state |
| `CURSOR_MAX_OPEN` | 256 | Simultaneously live walks, oldest evicted |
| `PAGE_RENDER_RETRIES` | 1 | Bounds the re-read a byte-cut page costs |

---

### Part 2 — the wire shape (contract-binding: `V1C-FE` codes against this)

Purely additive. Every new field carries a default, so an M1–V3 client decoding
this shape is unaffected and a client coded against this shape decodes an older
server's response unchanged (`10-design-notes.md` constraint 8: "additive
evolution only (unknown fields ignored by the client)").

```jsonc
// CellState (M1) — V1C-BE additions
{
  "ref": "uuid:0",
  "frontier": { "source": "a3f2…", "counter": 412 } | null,
                                   // UNCHANGED, and deliberately still null for "page"/"snapshot": this is
                                   // a WAVE position, and only an observation's fold has one (StampedView,
                                   // Observations.kt:543-587). A bounded read's currency is a TagFrontier —
                                   // a different clock, never a wave position (MessageContext.kt:58-72,
                                   // spec 20/21 §Pull, 93 I-24) — so stamping a paged read with a wave
                                   // would be exactly the lie this field exists to prevent. What a paged
                                   // read offers instead is "page.walkStable".
  "kind": "view" | "snapshot" | "page" | "unavailable",
                                   // "view"        — M1, unchanged: an open observation's materialized fold.
                                   // "snapshot"    — M1, unchanged in MEANING: one whole copy of the cell's
                                   //                 state. Now also the answer for a cell that does not
                                   //                 implement the kernel's BoundedStateful, which is why it
                                   //                 is not a legacy value.
                                   // "page"        — V1C-BE: one bounded page of a walk. Carries "page".
                                   // "unavailable" — M1, unchanged: nothing honest to report. Now carries
                                   //                 "unreadable", which says WHICH nothing.
  "value": /* Value — the same encoder, the same $table/$opaque/$truncated shapes */,
  "staleMs": 120,                  // UNCHANGED semantics: milliseconds since the reported value last
                                   // effectively changed, which only a "view" can know. 0 for
                                   // "page"/"snapshot" — a read is as fresh as the instant it was taken,
                                   // which is a different claim, not the same one with a better number.

  "provenance": "live" | "liveSuspended" | "checkpoint" | null,
                                   // WHERE THE BYTES CAME FROM — the field that lets a client say "this is a
                                   // drained host's checkpoint, not a live read". Non-null exactly when
                                   // "kind" is "page" or "snapshot"; null for "view" (a fold materialized in
                                   // the inspector's own heap is neither a live cell read nor a checkpoint,
                                   // and claiming "live" would blur the one distinction this field exists to
                                   // make) and for "unavailable".
                                   //   "live"          — read from the running cell on its own execution
                                   //                     context.
                                   //   "liveSuspended" — read from a SUSPENDED cell's own fold. Quiescent by
                                   //                     construction, so this is the most stable read in the
                                   //                     graph, not a degraded one. Reading it resumed
                                   //                     nothing, woke nothing and raised no attention.
                                   //   "checkpoint"    — read from the blob a DRAINED host already retains
                                   //                     from its drain (ManagedHost.beginDrain). State as of
                                   //                     the drain, not as of now, and no cell thread was
                                   //                     scheduled to produce it. This is the one provenance
                                   //                     that is stale by construction; clients must label it.

  "page": {                        // present iff kind == "page"; null otherwise — a whole "snapshot" has no
                                   // page contract, that is the older unbounded seam
    "cursor": "p-7f3a…" | null,    // OPAQUE. Echo it back verbatim as ?cursor= to fetch the next page; null
                                   // means the walk is complete. Never parse it, never construct one, never
                                   // reuse one: each response mints a fresh cursor and retires the one that
                                   // produced it. A stale, unknown, expired or wrong-cell cursor answers 410
                                   // — a client that gets one drops it and restarts the walk from page 1.
    "limit": 200,                  // the limit actually applied. The server clamps ?limit= to 1..1000, so
                                   // this is how a client learns its request was reduced.
    "entries": 200,                // entries in THIS page, as the cell counted them before encoding. Every
                                   // one of them is rendered in "value": the server never serves a page whose
                                   // entries the encoder's byte budget cut — it re-reads a smaller page
                                   // instead. So a "$truncated" marker inside "value" means one VALUE was
                                   // abbreviated, never that entries went missing. "page.cursor" is the one
                                   // and only signal that more state exists.
    "exclusivesElided": 0,         // entries on this page whose value is an Owned/Leased payload. The kernel
                                   // pages a presence descriptor (key, declared type, disposition) for those
                                   // and never a copy of the payload (spec 23 §Ownership; V1C-KERNEL
                                   // Decision 3), so > 0 means this page is deliberately incomplete in a way
                                   // no further page will ever fill in. Render it: it is a fact about the
                                   // data, not a diagnostic about the read.
    "walkStable": true | false | null
                                   // The ONLY consistency claim a paged read makes, and it is VERIFIED
                                   // server-side rather than promised (V1C-KERNEL Decision 5):
                                   //   true  — every page of this walk so far carried the same tag frontier,
                                   //           so the union of the pages fetched so far is exactly a snapshot
                                   //           of that fold. Always true on page 1.
                                   //   false — the fold changed mid-walk. The union is a SMEARED read: it
                                   //           contains every entry present for the whole walk, may contain
                                   //           entries added mid-walk, and may miss entries removed mid-walk
                                   //           after being passed over. It is never torn at entry granularity
                                   //           and never returns an entry twice.
                                   //   null  — the cell reports no tag frontier, so neither claim can be
                                   //           checked. Render it as neither; it is not a "false".
  } | null,

  "unreadable": "migrating" | "remote" | "notStateful" | "unanswered" | "unknown" | null
                                   // present iff kind == "unavailable" — WHY there is nothing to report.
                                   //   "migrating"   — held for a repartition flip, or already migrated. The
                                   //                   authoritative instance is another host's; a stale
                                   //                   local read would be a lie with a timestamp on it.
                                   //   "remote"      — no local host (Node.host == null). A wave-neutral read
                                   //                   is not an emission and so passes through no disclosure
                                   //                   filter; it does not cross a bridge. Unchanged from M5,
                                   //                   and deliberate.
                                   //   "notStateful" — the cell holds no readable state at all.
                                   //   "unanswered"  — the read did not land inside the server's bounded
                                   //                   wait. Nothing was read; a retry may succeed.
                                   //   "unknown"     — a kernel Unavailable reason this server build does not
                                   //                   map. Forward-compatibility, never a guess.
}
```

**Endpoint row to propose** (`20-api-contract.md` §Endpoints, the
`GET /api/inspect/cell/{ref}/state` row):

> `GET /api/inspect/cell/{ref}/state?cursor=&limit=` | M1, V1C-BE | `CellState`.
> `cursor` (opaque, from a previous response's `page.cursor`) and `limit`
> (1..1000, clamped, default 200) walk a cell's state one bounded page at a
> time instead of copying it whole. Both are ignored for a cell with an open
> observation, which keeps answering `kind: "view"` from its already-materialized
> fold. A malformed `limit` is 400; an unknown, expired, already-consumed or
> wrong-cell `cursor` is 410 — drop it and restart the walk.

---

### Part 3 — rewire `DataSearch`

`DataSearch`'s bounds and its closing notice exist *because* every read was a
whole-state copy. With a bounded read they mean something different, and the
notice must say the different thing.

**The read** (`DataSearch.read`, `:208-220`). Keep the free path first —
`observed(ref)` at `:209` — then ask the host for **one bounded page** instead
of `snapshotOf`:

- `limit = SEARCH_PAGE_LIMIT = 200`, `allowWholeCopy = true`, one page per
  candidate cell (`SEARCH_PAGES_PER_CELL = 1`, named so a later ticket can
  raise it). **Do not walk.** A search that walks a 10⁵-row cell page by page
  has re-created the whole copy and added scheduler overhead to it; the win
  here is that the *copy* is now bounded, not that coverage grew.
- Keep the existing deadline discipline verbatim: `pending.isDone` short-circuit
  (`:214`), bounded `get` (`:215`), `cancel(false)` on failure (`:218`).
- Classify the outcome so the notice can report it: a `Page` with
  `next != null` (partial coverage of that cell), a `Page` with `next == null`
  (complete), an `Unbounded` (a whole copy — the cell has no bounded read), a
  `CHECKPOINT` provenance (a drained host's state as of its drain), an
  `Unavailable`, an unanswered read.

**The notice** (`:309-339`). Requirements, in order of importance:

1. **It must remain honest** — never claim coverage it did not achieve, and
   never imply coverage it did.
2. The clause `"$n cells read only to the first ${ValueEncoder.MAX_ROWS} rows"`
   (`:321-323`) is replaced. It described a *rendering* limit while implying a
   *read* limit; now there is a real read limit, and the honest sentence names
   entries, not rows — e.g. `"$n cells read only their first $SEARCH_PAGE_LIMIT
   entries"`. Exact wording is yours; the fact it states is not.
3. **New clause — whole copies.** A cell read via `Unbounded` cost the graph a
   whole-state copy. That is complete coverage, so it does **not** make the
   result partial; it is a *cost* note, in the register of the existing
   "remote cells skipped" clause.
4. **New clause — checkpoint reads.** A cell answered from a drained host's
   checkpoint is state as of the drain. That is a *staleness* note, not a
   partiality note, and the user must see it — it is the same discipline
   `provenance: "checkpoint"` applies on the detail panel.
5. The cold-graph clause `"$n cold graphs skipped — wake to include"`
   (`:327-329`) becomes reachable far less often (Part 4). It must still fire
   for what genuinely remains unreadable, and its "wake to include" remedy must
   not be offered for a cell waking cannot help (`HELD` — see `Cold.kt:21-32`,
   "never the inspector").
6. `partial` (`:332`) stays true for cap / budget / unanswered, and gains the
   partial-page case. It is **not** true for a whole copy or a checkpoint read.

**`MAX_CELLS` and `BUDGET_MS`** (`:345`, `:348`). These may be re-derived **only
from `30-bounded-read-measurement.md`** — `V1C-BENCH`'s numbers — and any change
must quote the line of that document that justifies it, in the constant's KDoc
and in your report. **Never guess, never round a number you liked.** If the
measurement contains no sentence sizing them, leave both exactly as they are and
say so; the ticket's default expectation is that they are unchanged, because the
deadline still protects the fan-out and the per-cell cost is now bounded by
`SEARCH_PAGE_LIMIT` rather than by the cell's size. Replace the KDoc's bare
`"Ticket: …"` justification with the measurement's, either way.

**P6 is absolute.** No new subscription, no tap, no pull, no `StateRequest`,
no `Observations.start`, no attention raised. Mirror the leak-check pattern of
`InspectorDataSearchTest.kt:272-288` (`serving.observedRefs.shouldBeEmpty()`,
`registry.localRefs()` unchanged, `registry.all().size` unchanged) in a new test
covering the paged path, and extend it with `tappedOutlets` unchanged.

---

### Part 4 — the suspended and drained cells stop being lies

`V1C-KERNEL` Decision 7 makes a suspended cell readable (`LIVE_SUSPENDED`, from
the live fold) and a drained host's cell answerable from the checkpoint blob the
host already retains (`ManagedHost.kt:499-502`, written at `:501`). Consume
both:

- **`Heat.isReadable` widens** (`Cold.kt:92-98`) from `HOT` only to
  `HOT || SUSPENDED || DRAINED`. Rewrite its KDoc: the reason it was `HOT`-only
  was that every other value "would either refuse the read or answer a torn or
  stale one" — that is now false for two of them, and the third is still true.
- **`Heat.isCold` does not change** (`:85-90`). Cold still means "parked, and
  the inspector may offer to end it": it drives the cold screen and the wake
  button, and waking is still the only causal act in the inspector. `isCold`
  and `isReadable` genuinely diverging is what `Cold.kt:30-32` already
  anticipated ("which is why `isReadable` and `isCold` are two different
  questions"); make that sentence true rather than aspirational.
- **Remove `DataSearch`'s whole-component cold skip** (`:125-131`). A cold
  component is now readable, cell by cell, and reading it neither wakes it nor
  raises attention: a suspended cell's read runs on the host scheduler that is
  still running (`ManagedHost.isSuspended` parks only the cell's data intake,
  `ManagedHost.kt:231`), and a drained host's read is served from a blob with no
  cell-thread task at all. The per-cell `candidacy` predicate (`:185-188`)
  governs everything after this.
- **`SearchCost.coldSkipped` shrinks to the genuinely unreadable.** After this
  ticket it counts `HELD` cells only. **This narrows a shipped contract
  meaning** (`20-api-contract.md:186-189`) and breaks the FE's remedy wording
  (`inspect/ui/src/nav/cold.ts:45-49` says "wake their graph to include", and
  waking a held cell does nothing). Flag both, to the orchestrator and to
  `V1C-FE`; do not edit either file.
- **`COLD_NOTICE` (`inspect/ui/src/nav/cold.ts:13`) becomes false for state.**
  Its current promise, `'cold — parked; state/flow unavailable without waking'`,
  and its comment "the real capability, cold reads from a checkpoint or journal,
  is a tracked kernel gap" are both now out of date for the state half. **Flow
  remains genuinely unavailable** — no messages flow in a parked cone, and there
  is nothing honest to show. Hand `V1C-FE` the fact ("state is readable from the
  parked fold or the drain checkpoint, and is labelled with its provenance; flow
  is not"), let them word it, and **do not touch `inspect/ui/**`**.

**What remains unreadable after this ticket, and why** — be exhaustive about
this in your report:

| Case | Answer | Why |
|---|---|---|
| Held for a migration flip (`LocationRegistry.isHeld`, `Cold.kt:112`) | `unavailable` / `"migrating"` | The authoritative instance is the target host's; a stale local read would be a lie with a timestamp |
| Migrated away (removed from `cells` by `migrate`) | `unavailable` / `"migrating"` | Same |
| Not locally hosted — unpublished or peer-mirrored (`Cold.kt:110`) | `unavailable` / `"remote"` | No wave-neutral read crosses a bridge; see Exclusions |
| Not `Stateful` | `unavailable` / `"notStateful"` | Nothing to read |
| Wedged or slow past the bounded wait | `unavailable` / `"unanswered"` | Viz never blocks |
| Attention-parked cone | reads as `HOT` and answers normally | Unchanged from `Cold.kt:46-57`: the kernel exposes no band-parked predicate the inspector may read here, and the honest answer stays "the inspector cannot see this today" |

## Explicitly out of scope

- **Remote / cross-bridge reads.** No-go per `20-wave-neutral-read-design.md`
  §3.7 and `V1C-KERNEL` Decision 9: a wave-neutral read has no emission and
  therefore passes through no disclosure filter, and the repository's only
  disclosure seam is an outlet seam (`FanOutlet.kt:105-117`, `:293`; 93 I-28
  "filtered, not forked", `21-propagation.md:72-76`). Shipping a read across a
  membrane today would be a security regression wearing a feature's clothes. A
  `host: null` cell keeps answering `unavailable` (now with
  `unreadable: "remote"`).
- **Kernel edits.** Every seam you need shipped in waves 8 and 9. If you find
  one missing, **SKIP the dependent behaviour and flag it in the report** — do
  not reach into `kernel/`.
- **`inspect/ui/**`** — `V1C-FE` owns it and runs concurrently. The two fixture
  files below are authored by `V1C-FE`; you add only their decoder entries.
- **`concord/**`** (binding constraint 7) and
  `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (constraint 8,
  orchestrator-owned — you propose wording in your report).
- **`View`/`ObserveCell` paging**, `state.summary`'s shape, the observation
  lifecycle, and the `POST/DELETE observe` routes. All unchanged.
- **Ownership in `Stateful.snapshot()`** — the older, undefined seam
  (`23-ownership.md` G-46 at `:220`). `V1C-KERNEL` declined to inherit it and so
  do you: a `kind: "snapshot"` whole copy has no `exclusivesElided` contract,
  and `page` is absent for it precisely because of that.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/Dto.kt` — `CellState`'s additive
  fields (`:172-194`), the page object, and the `provenance`/`unreadable`
  vocabularies as named constants beside `VIEW`/`SNAPSHOT`/`UNAVAILABLE`
  (`:186`, `:189`, `:192`).
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt` — the
  `BoundedReadSource` seam beside `SnapshotSource` (`:99-107`), the widened
  internal `StateReading` (`:40-46`), and the bounded read's reading path beside
  `snapshotReading` (`:304-311`).
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — the query
  parameters, the default `BoundedReadSource` wiring beside `snapshots`
  (`:230-236`), `serveState` (`:552-568`), the new constants, and `close()`
  symmetry if the cursor table needs it (`:751-771`).
- `inspect/src/main/kotlin/civictech/inspect/DataSearch.kt` — the bounded read,
  the notice, the candidacy change, the constants' justification.
- `inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt` — the additive
  explicit-allowance entry point and the rendered-entry count. `encode(state)`
  (`:72`), `MAX_ROWS` (`:53`) and `MAX_BYTES` (`:56`) keep their current
  behaviour for every existing caller.
- `inspect/src/main/kotlin/civictech/inspect/Cold.kt` — `Heat.isReadable`
  (`:92-98`) and its KDoc.
- Optionally a new `inspect/src/main/kotlin/civictech/inspect/…​.kt` for the
  cursor table / paged-read collaborator, if it deserves its own file.
- `inspect/src/test/kotlin/civictech/inspect/**` — new focused tests, the two
  `FixtureContractTest` decoder entries below, and the adjusted existing tests.

**Cross-ticket coupling you must honour.** `FixtureContractTest`
(`inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt:113-120`)
asserts that its hand-written `decoders` map (`:85-111`) covers *exactly* the
contents of `inspect/ui/fixtures/`. `V1C-FE` runs in parallel and will add
**exactly these two files**:

- `"cell-state-page.json"` → `CellState` — a live paged read with a cursor.
- `"cell-state-page-checkpoint.json"` → `CellState` — a drained host's
  checkpoint read.

**Add both decoder entries as part of this ticket**, mapped to `CellState`, with
a KDoc paragraph in the register of the existing `activity.json` /
`error-event-wave-health*.json` notes (`:64-83`). A third fixture is a
cross-ticket change neither of you may make unilaterally; `V1C-FE` uses inline
samples instead (`00-orchestration.md` §Standing rules). Consequence to expect
and **not** to "fix": until both branches merge, `:inspect:test` in a worktree
holding only one of them fails the directory-equality assertion. Do not edit
`inspect/ui/**` to work around it — note it in your report.

**Existing tests that change, and how.** These are the ones this ticket is
expected to touch; keep each test's *intent* and adjust the setup minimally,
never delete an assertion:

- `InspectorObserveTest.kt:354-367` — "a wired snapshot source answers for a
  cell with no observation" installs a `SnapshotSource` stand-in for a real
  hosted `SetCell`, which after wave 9 is `BoundedStateful` and will now be
  answered by the bounded seam first. Keep the test (it is the only coverage of
  the whole-copy labelling and encoding path) by disabling the bounded seam for
  it, e.g. `server.reads = BoundedReadSource.Unavailable`.
- `InspectorObserveTest.kt:369-377` ("an open observation wins over the snapshot
  source") and `:396-404` ("a snapshot read that misses the bounded wait answers
  unavailable, not a hang") are expected to pass **unchanged**; `:379-394` (the
  `CounterCell` default-wiring test) is expected to pass unchanged too, since a
  scalar cell needs no bounded read and answers `Unbounded` → `kind: "snapshot"`.
  If any of them does not, say so and explain rather than loosening it.
- `InspectorDataSearchTest.kt:250-270` — "a suspended cell is skipped, counted
  as cold, and never read" is now **wrong by design**: a suspended cell is read.
  Rewrite it as its inverse — the suspended cell is *read*, the hit is found,
  `coldSkipped == 0`, and the cell is **still suspended afterwards** — and keep
  the leak check at `:268` verbatim.
- `InspectorDataSearchTest.kt:314-328` — "a held cell is skipped and counted
  cold" stays true and must stay green unmodified.

Touching files outside `inspect/src/**` (other than the fixture coupling above,
which is read-only for you): note it in the completion report rather than
expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-KERNEL.md` — all nine
  decisions, **and its completion report** for the verbatim shipped signatures
  and which arm of Decision 7 the drained host got.
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-CELLS.md` and
  `V1C-OPS.md` — which cell families actually implement `BoundedStateful`, and
  what their page entries look like. Your `Unbounded` fallback's reach is
  exactly their complement.
- `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md` —
  `V1C-BENCH`'s numbers. **The only admissible source for a change to
  `MAX_CELLS`/`BUDGET_MS`.**
- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` §1.4
  (what `snapshotOf` does and does not give), §1.5 (the three blocked
  capabilities), §3.3 (ownership: described, never paged), §3.4 (the cursor and
  the mid-fold mutation case), §3.6 (suspended/drained/migrating) and §7
  (go/no-go, and the research questions you must not solve).
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" — all ten; 2 (P6), 4 (per-cell consistency only), 6 (viz never
  blocks), 7 (no `concord/`), 8 (contract is orchestrator-owned) and 10 govern
  you directly.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — the **whole**
  file. `CellState` is `:92-99`, `Value` and its truncation marker `:101-112`,
  the endpoint table `:19-31`, `SearchResult`/`SearchCost` `:182-196`. You
  propose additions to it in your report and **never edit it**.
- `inspect/src/main/kotlin/civictech/inspect/DataSearch.kt` — the whole file.
  Its KDoc `:28-59` records why it does not use `StateRequest`; that argument
  survives this ticket (the load-bearing half is P6/topology, per the design
  note §1.3) but its cost story `:57-59` does not, and neither does the
  candidacy story `:61-78`.
- `inspect/src/main/kotlin/civictech/inspect/Observations.kt:36-46`
  (`StateReading`), `:99-107` (`SnapshotSource`), `:296` (`frontierOf`),
  `:299-311` (`reading`/`snapshotReading`), `:543-587` (`StampedView`, frontier
  at `:548-550`, written at `:577`) — the two different stamps this ticket must
  not conflate.
- `inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt` — the whole file;
  `normalize` (`:221-224`) and its three recognisers (`:232-243`, `:246-252`,
  `:261-267`), and `Budget` (`:341-348`).
- `inspect/src/main/kotlin/civictech/inspect/Cold.kt` — the whole file; the
  `Heat` table `:19-25`, why `HELD` is not cold `:26-32`, the attention-parked
  paragraph `:46-57`, `Heat.of` `:109-117`, `Waker` `:147-209`.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:441-465`
  (`serveSearch` and its 400), `:495-516` (`serveGraph`, the 404-vs-race
  reasoning), `:518-568` (`serveCell`/`serveState`), `:697-737` (the `Tick`
  list), `:788` (`tickAll`), `:818-921` (the companion; route-prefix discipline
  at `:834-848`, `SNAPSHOT_WAIT_MS` at `:883`), `:932-950` (`tailSegments` and
  `query`).
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1201-1263`
  (`snapshotOf` and its threading/cancellation contract — your bounded read
  wiring mirrors it), `:487-511` (`beginDrain`, the retained blob at `:501`),
  `:231` (`isSuspended`).
- `kernel/src/main/kotlin/civictech/cell/MessageContext.kt:58-72` —
  `TagFrontier`: what `walkStable` compares, and why it does not go on the wire.
- `inspect/src/test/kotlin/civictech/inspect/InspectorDataSearchTest.kt` — the
  whole file; the leak-check pattern at `:272-288` is the one your new tests
  mirror.
- `inspect/src/test/kotlin/civictech/inspect/InspectorObserveTest.kt:352-404` —
  the snapshot-fallback tests, and `SlowSnapshotCell` (`:406-`) for the
  missed-deadline recipe.
- `inspect/src/test/kotlin/civictech/inspect/ObservationsIdleTest.kt` — the
  injected-clock pattern; use it for cursor TTL rather than sleeping.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `inspect/ui/**` (`V1C-FE` owns it), `kernel/**`, `concord/**`,
`wire/**`, `gen/**`, `demo/**` (`V4-PILOT` owns it),
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`, and any plan
document other than this ticket's `**Status**:` line.

## Acceptance criteria

**The paged endpoint**

- [ ] `GET /cell/{ref}/state` on an unobserved `BoundedStateful` cell with more
      entries than `limit` answers `kind: "page"`, `provenance: "live"`, a
      non-null `page.cursor`, `page.entries == page.limit`, and a `value`
      rendering exactly those entries.
- [ ] Echoing `page.cursor` back as `?cursor=` walks the cell: successive pages
      carry disjoint entries, the union over a stable fold equals the whole
      state, and the final page carries `page.cursor: null`.
- [ ] `?limit=` is honoured, clamped to `PAGE_LIMIT_MAX`, and the applied value
      is reported in `page.limit`. A malformed `limit` is 400.
- [ ] A cursor that is unknown, expired past `CURSOR_TTL_MS`, already consumed
      by a previous request, or minted for a different cell answers **410**, and
      a fresh walk after a 410 succeeds. Driven with an injected clock, not a
      sleep.
- [ ] `page.walkStable` is `true` across a walk over a quiescent cell, and
      `false` on the first page after the fold changed mid-walk — with the walk
      still completing, entries still whole, and no entry returned twice.
- [ ] **The observation path does not regress**: a cell with an open observation
      answers `kind: "view"` with `page: null` and its existing `frontier` /
      `staleMs`, with or without `cursor`/`limit` on the request.
- [ ] A cell that is `Stateful` but not `BoundedStateful` answers
      `kind: "snapshot"` with `page: null` and `provenance: "live"` — byte-wise
      the same `value` the shipped server answers today.
- [ ] A read that misses the bounded wait answers `kind: "unavailable"`,
      `unreadable: "unanswered"`, cancels the future, and costs **one** deadline,
      not two.

**Rendering and the `$truncated` reconciliation**

- [ ] A page whose entries exceed the encoder's byte budget is re-read at the
      smaller limit and served whole: no entry the cursor advanced past is ever
      absent from `value`. Asserted with a cell of deliberately wide entries.
- [ ] A single entry wider than the byte budget still yields a rendered entry
      carrying `$truncated` — the marker's existing meaning — and the page's
      entry count still matches what was rendered.
- [ ] `ValueEncoder.encode(state)`, `MAX_ROWS` and `MAX_BYTES` behave exactly as
      before for every existing caller; the existing encoder tests pass
      unmodified.

**Provenance and cold cells**

- [ ] A **suspended** cell answers `kind: "page"` (or `"snapshot"`),
      `provenance: "liveSuspended"`, with real state — and is **still suspended**
      after the read (`host.isSuspended(ref)` still true).
- [ ] A cell on a **drained** host answers with `provenance: "checkpoint"` — as
      `kind: "page"` or `kind: "snapshot"` depending on which arm `V1C-KERNEL`
      shipped for Decision 7 — and the host is **still drained** afterwards.
- [ ] A **held** ref answers `kind: "unavailable"`, `unreadable: "migrating"`,
      and never a stale local read.
- [ ] A **remote** (`host: null`) cell answers `kind: "unavailable"`,
      `unreadable: "remote"` — unchanged behaviour, newly explained.
- [ ] `Heat.isReadable` is true for `HOT`/`SUSPENDED`/`DRAINED` and false for
      `HELD`/`UNHOSTED`; `Heat.isCold` is unchanged and the cold screen, the
      wake button and `GraphSummary.lifecycle` all still behave exactly as
      before.

**Ownership**

- [ ] A cell whose state holds `Owned`/`Leased` values pages
      `exclusivesElided > 0`, and **no payload value, `toString()` of one, or
      encoded form of one appears anywhere in the response** — only the kernel's
      presence descriptors (key, declared type, disposition). Assert on the
      serialized body, not on an intermediate object.

**Data search**

- [ ] A data search over a graph of `BoundedStateful` cells returns the same
      hits it returns today for the same seeded data, and reads **one bounded
      page per cell** — asserted by instrumenting the read, not by timing.
- [ ] A data search over a **cold** (suspended and/or drained) component now
      **finds hits**, reports `coldSkipped == 0`, and leaves every cell
      suspended and every host drained afterwards.
- [ ] A **held** cell is still skipped and still counted in `coldSkipped`, and
      the notice does not offer "wake to include" as its remedy.
- [ ] The closing notice is honest in every mixed case: partial pages, whole
      copies, checkpoint reads, unanswered reads, the cap, the deadline, and
      remote skips each produce their own clause, and `partial` is true for
      exactly the coverage failures (cap / budget / unanswered / partial page).
- [ ] **No new subscriptions**: across a full search *and* a full multi-page
      walk, `observedRefs` and `tappedOutlets` are unchanged, `registry.localRefs()`
      and `registry.all().size` are unchanged, and no `ObserveCell` sink is
      spawned — asserted in the `InspectorDataSearchTest.kt:272-288` style.
- [ ] `MAX_CELLS`/`BUDGET_MS` are either unchanged, or changed with the
      justifying line of `30-bounded-read-measurement.md` quoted in the
      constant's KDoc. No third option.

**Hygiene**

- [ ] Every new constant is named, lives in one place, and carries a comment
      saying what it protects against. No test asserts on scheduler timing; TTL
      and deadline behaviour is driven by an injected clock and `awaitUntil`.
- [ ] `FixtureContractTest`'s `decoders` map carries
      `"cell-state-page.json"` and `"cell-state-page-checkpoint.json"`, both
      mapped to `CellState`, with the KDoc paragraph explaining the parallel-branch
      arrangement.
- [ ] Every added public member carries KDoc naming this ticket (`V1C-BE`) and
      the reason it exists, in the register of the surrounding file.
- [ ] No kernel, `concord/`, `inspect/ui/`, `demo/` or contract-document edits
      in the diff. No generated/build output. No unrelated files.

## Verify

```bash
./gradlew :inspect:test
./gradlew :inspect:test --tests 'civictech.inspect.InspectorDataSearchTest'
./gradlew :demo:skillmatch:test
./gradlew test
```

Narrow loops while iterating:

```bash
./gradlew :inspect:test --tests 'civictech.inspect.InspectorObserveTest'
```

Any live server you start for manual checking must bind an **ephemeral or
explicitly non-default port** — concurrent sessions squat 7071 and 8080
(`00-orchestration.md` §Sandbox).

## Report on completion

- Checks run and their results.
- **The exact final contract additions, in the wording the orchestrator should
  paste into `20-api-contract.md`** — you never edit it yourself. At minimum:
  1. `CellState`'s `kind: "page"`, `provenance`, `page` (all five sub-fields)
     and `unreadable`, as a `jsonc` block in the register of the existing
     `ErrorSnapshot` block, including the sentence that `frontier` stays a wave
     position and is still null for a paged read.
  2. The `GET /cell/{ref}/state?cursor=&limit=` endpoint row, with the 400 and
     410 rules and the "ignored for an observed cell" rule.
  3. The narrowed meaning of `SearchCost.coldSkipped` (`:186-189`) and the
     consequence for the FE's "wake their graph to include" remedy wording.
  4. The `Value` §note (`:108-110`) on `$truncated` coexisting with a real
     cursor: the marker means a value was abbreviated, `page.cursor` means more
     state exists, and the server never lets the encoder swallow whole entries.
  5. Anything you had to deviate from Part 2's block, flagged loudly — `V1C-FE`
     coded against that block in parallel.
- **Whether `MAX_CELLS`/`BUDGET_MS` changed**, and if so the exact line of
  `30-bounded-read-measurement.md` that justifies it, quoted.
- **What is still unreadable after this ticket, exhaustively** — the table in
  Part 4, as you actually shipped it, plus anything you discovered that is not
  in it.
- **The P6 claim, checkable**: name every read path in the shipped code
  (`serveState`'s three arms, `DataSearch.read`'s two arms, the cursor table)
  and assert of each that it installs no link, spawns no sink, attaches no tap
  and raises no attention — with the test that proves it.
- **The cold-screen handover to `V1C-FE`**, as a paragraph they can act on:
  what `COLD_NOTICE` (`inspect/ui/src/nav/cold.ts:13`) may now claim, what it
  still may not (flow), how `provenance: "checkpoint"` must be labelled, how
  `exclusivesElided > 0` must be rendered, the 410-restart rule, and the
  `formatColdSkipHint` (`:45-49`) wording consequence.
- **What shape the kernel's exclusive-value presence descriptors encoded to**
  through `ValueEncoder`, so the orchestrator can describe it in the contract
  and `V1C-FE` can render it.
- **Anything you skipped rather than reaching into `kernel/`** — and, separately,
  anything that would have been easy with one more kernel accessor, as input to
  the replan checkpoint.
- Files actually touched, and any not in the claim above; the
  `FixtureContractTest` cross-branch red window, if you hit it.
- Anything specified here you could not do, and why.
