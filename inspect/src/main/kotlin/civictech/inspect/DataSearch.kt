package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Provenance
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.host.LocationRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * M5 — `GET /api/inspect/search?mode=data&q=`
 * (`doc/spec/90-roadmap/97-inspector-plan/tickets/M5-SEARCH.md`): *which graph
 * holds this record?*, answered by reading candidate cells' state and matching
 * the query against the values in it.
 *
 * ### Why this is the one search mode with a cost model
 *
 * `name` and `problems` are answered from metadata the inspector already holds.
 * `data` cannot be: the records live inside cells, and the inspector has no
 * index of them (indexing is explicitly out of scope — MRB-157). So every data
 * search *asks cells*, and a naive fan-out would be a P6 violation with a
 * search box in front of it. The bounds below are the product, not a
 * safety-net: the fan-out is capped ([MAX_CELLS]), deadlined ([BUDGET_MS]),
 * restricted to hot locally-hosted cells, and its price is reported back in
 * [SearchResult.cost] so a user can see what a query cost.
 *
 * ### How state is read, and why not `StateRequest`
 *
 * The ticket's first proposal was to fan `StateRequest`
 * ([civictech.cell.protocol.StateRequestProtocol]) out to pull-serving outlets,
 * with its explicitly-sanctioned alternative being "for cells the inspector can
 * already read cheaply (active observations, or `Stateful` snapshot via host
 * routing), match against those reads instead — choose the design that keeps P6
 * intact and document the choice". This is that alternative, and the reason is
 * concrete rather than one of convenience:
 *
 * - **A pull reply is a message, and this instrument has no topology to receive
 *   it on.** `FanOutlet.at` delivers only to an entry already in the producing
 *   outlet's `consumers`/`taps`; an unlinked requester gets nothing and is
 *   counted as a target miss. So a read-only instrument issuing `StateRequest`
 *   must first install a hand-built protocol `Link` or a reply *tap* on the
 *   producing outlet — which raises attention, extends the cone, and (for a
 *   tap) receives every live emission for the duration of the request. That is
 *   a **P6** violation with a search box in front of it, and it is the
 *   load-bearing reason.
 * - **Correction (C-replan, inspector-v4, 2026-07-29).** This KDoc previously
 *   gave a *different* first reason, quoting `CatchUp.kt`: that a `baselineTo`
 *   reply "inflates the `waveState().highWater` that
 *   `civictech.cell.replication` reads directly as a source's delivered
 *   high-water". **That mechanism does not exist.** Nothing under
 *   `civictech.cell.replication` reads `waveState()`; the delivered watermark
 *   advances from a *tap*, and a targeted `at` delivery fires no tap. A pull
 *   reply does consume one value from the producing outlet's wave counter, but
 *   it moves no watermark, arms no join, gates no wave and is admitted to no
 *   completeness set. The `CatchUp.kt` KDoc has been corrected; the full
 *   analysis is in
 *   `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md`
 *   §1.2-§1.3. **The M5-SEARCH decision below survives the correction** — the
 *   P6 argument above was always the real one — but the discarded reason must
 *   not be cited by new work as a design constraint.
 *
 * `ManagedHost.readState` moves none of that: it runs the cell's own
 * `BoundedStateful.readBounded` (or, for a cell that has none,
 * `Stateful.snapshot()`) on the cell's own execution context, links nothing,
 * emits nothing, subscribes to nothing and raises no attention. A cell the
 * client already has an open observation on is read from that fold instead —
 * free, and already consistent.
 *
 * ### What it costs, since V1C-BE
 *
 * **One bounded page per candidate cell** ([SEARCH_PAGE_LIMIT] entries,
 * [SEARCH_PAGES_PER_CELL] page), not a whole-state copy. That is the whole
 * change to this class's cost model, and it is deliberately *not* a coverage
 * increase: a search that walked a 10⁵-row cell page by page would have
 * re-created the whole copy and added scheduler overhead to it. The win is that
 * the copy is now bounded — a big cell costs its own thread O(200) instead of
 * O(n) — not that the search reads further.
 *
 * A cell that implements no bounded read still costs a whole copy (the kernel's
 * `Unbounded` arm, taken under `allowWholeCopy`), which is complete coverage at
 * the old price; the notice reports that as a *cost*, not as partiality. A cell
 * on a drained host answers from the checkpoint blob its host already holds —
 * complete, but as of the drain, which the notice reports as *staleness*.
 *
 * ### Which cells are candidates
 *
 * Locally hosted, not held mid-migration, and readable — an open observation, or
 * a cell the host answers a bounded read for. Everything else is skipped and
 * counted:
 *
 * - **held for a repartition flip** ([Heat.HELD]) → [SearchCost.coldSkipped].
 *   **V1C-BE narrowed this to held cells alone.** M5-COLD skipped whole cold
 *   components without a per-cell walk, because a suspended or drained cell
 *   could not be read at all; V1C-KERNEL's Decision 7 made both readable
 *   *without waking anything* — a suspended cell's read runs on the host
 *   scheduler that is still running (`ManagedHost.isSuspended` parks only the
 *   cell's data intake) and a drained host's read is served from a blob with no
 *   cell-thread task at all — so skipping them would now be refusing to answer a
 *   question the kernel can answer. A held ref stays skipped for the reason it
 *   always was: the authoritative instance is the migration target's.
 * - **not locally hosted** (a mirrored/announced ref — M5-NET) → counted and
 *   reported in the closing notice. Cross-JVM search is out of scope.
 *
 * Nothing here mutates: this class holds no state between requests, so two
 * concurrent searches simply each pay their own bounded fan-out. It mints no
 * cursor either — one page per cell, never resumed, so nothing outlives the
 * request (contrast [CursorTable], which the paged endpoint owns).
 */
internal class DataSearch(
    private val registry: LocationRegistry,
    /** The live components, exactly as `GET /graphs` and the other search modes see them. */
    private val components: () -> List<Component>,
    /** A free read for a cell a client already observes — no host round-trip, no new attachment. */
    private val observed: (CellRef) -> StateReading?,
    /**
     * The inspector's own `ObserveCell` sinks ([Observations.sinkRefs]).
     * Skipped outright — not counted as candidates, not counted as cost: an
     * instrument is not a subject, and a sink only ever mirrors state its
     * producer already answered for.
     */
    private val instruments: () -> Set<CellRef> = ::emptySet,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxCells: Int = MAX_CELLS,
    private val budgetMs: Long = BUDGET_MS,
) {

    /**
     * Run one bounded content search. A blank query matches nothing and queries
     * nothing — the same rule `name` mode follows, and here it also means an
     * empty submit never spends a single host read.
     */
    fun search(query: String): SearchResult {
        val needle = query.trim()
        if (needle.isEmpty()) return SearchResult(SearchResult.DATA, emptyList(), SearchCost(0, 0))

        val deadline = clock() + budgetMs
        val hits = ArrayList<SearchHit>()
        val cost = Tally()

        val ours = instruments()
        components@ for (component in components()) {
            // V1C-BE removed M5-COLD's whole-component skip: a cold component is
            // now readable cell by cell, and reading it neither wakes it nor
            // raises attention (see this class's doc). The per-cell predicate
            // below governs everything.
            for (node in component.nodes) {
                val ref = InspectorServer.decodeRef(node.ref) ?: continue
                if (ref in ours) continue
                when (candidacy(ref)) {
                    Candidacy.HELD -> {
                        cost.heldSkipped += 1
                        continue
                    }

                    Candidacy.REMOTE -> {
                        cost.remoteSkipped += 1
                        continue
                    }

                    Candidacy.READABLE -> Unit
                }
                if (cost.queried >= maxCells) {
                    cost.capReached = true
                    break@components
                }
                val remaining = deadline - clock()
                if (remaining <= 0) {
                    cost.budgetSpent = true
                    break@components
                }

                when (val read = read(ref, remaining)) {
                    is Read.Unanswered -> cost.unanswered += 1
                    is Read.Refused -> cost.unreadable += 1
                    is Read.None -> Unit
                    is Read.State -> {
                        cost.queried += 1
                        if (read.partial) cost.partialPages += 1
                        if (read.wholeCopy) cost.wholeCopies += 1
                        if (read.checkpoint) cost.checkpointReads += 1
                        // A page is encoded under an unbounded *row* allowance
                        // (V1C-BE): its row bound is already the read's own
                        // limit, and re-imposing `MAX_ROWS` on top would let the
                        // encoder's shared budget — which counts an entry's
                        // nested values as rows too — silently drop entries the
                        // cell was paid to produce, matching a search against
                        // half a page it thinks it read whole. The byte budget
                        // still applies, and whatever it cuts is reported.
                        val encoded =
                            if (read.paged) {
                                ValueEncoder.encode(read.value, ValueEncoder.PAGE_ROWS_UNBOUNDED, ValueEncoder.MAX_BYTES)
                            } else {
                                ValueEncoder.encode(read.value)
                            }
                        // whatever the render budget cut — a whole copy past
                        // `MAX_ROWS`, or one abbreviated value inside a page —
                        // is content this search never matched against
                        if (isTruncated(encoded)) cost.truncatedReads += 1
                        matchIn(encoded, needle)?.let { match ->
                            hits += SearchHit(
                                graph = component.id,
                                ref = node.ref,
                                label = match.label,
                                detail = detailOf(component, node, match.records),
                            )
                        }
                    }
                }
            }
        }

        notice(cost)?.let { hits += it }
        return SearchResult(
            SearchResult.DATA,
            hits,
            SearchCost(cellsQueried = cost.queried, coldSkipped = cost.heldSkipped),
        )
    }

    /**
     * What one search spent, in one place (V1C-BE) — the fan-out grew enough
     * outcomes that threading them as eight locals through [notice] stopped
     * being readable.
     */
    private class Tally {
        /** Cells whose state this search actually read — [SearchCost.cellsQueried]. */
        var queried = 0

        /**
         * Candidates skipped as held for a repartition flip —
         * [SearchCost.coldSkipped], whose meaning V1C-BE narrowed to exactly
         * this (see the class doc).
         */
        var heldSkipped = 0

        /** Peer-announced refs: nothing local to read. */
        var remoteSkipped = 0

        /** Cells whose page had a `next` — read only to their first [SEARCH_PAGE_LIMIT] entries. */
        var partialPages = 0

        /** Cells with no bounded read: a whole-state copy, complete but expensive. */
        var wholeCopies = 0

        /** Cells answered from a drained host's checkpoint: complete, but as of the drain. */
        var checkpointReads = 0

        /** Whole copies the encoder's own row budget cut — a genuine coverage gap. */
        var truncatedReads = 0

        /** Reads abandoned at the deadline. */
        var unanswered = 0

        /** Reads the kernel refused with a decided reason (migrating, dead scheduler, a throwing cell). */
        var unreadable = 0

        var capReached = false
        var budgetSpent = false
    }

    /** Why a candidate was (or was not) queried — the whole skip vocabulary in one place. */
    private enum class Candidacy { READABLE, HELD, REMOTE }

    private fun candidacy(ref: CellRef): Candidacy = when (val heat = Heat.of(registry, ref)) {
        Heat.UNHOSTED -> Candidacy.REMOTE
        // V1C-BE: `isReadable` is now HOT/SUSPENDED/DRAINED, so this leaves
        // exactly HELD — see [Heat.isReadable]
        else -> if (heat.isReadable) Candidacy.READABLE else Candidacy.HELD
    }

    /** The outcome of one candidate read — "nothing to read" and "did not answer" are not the same. */
    private sealed interface Read {
        /**
         * State this search can match against, plus what reading it cost and how
         * far it reached.
         *
         * @property paged the value is a `StatePage`'s entry list rather than a
         *   whole state, which changes how it is encoded (see the call site).
         * @property partial the page had a `next`: this cell was read only to
         *   its first [SEARCH_PAGE_LIMIT] entries, so a record past that is
         *   genuinely unfindable and the result must say so.
         * @property wholeCopy the cell implements no bounded read, so the kernel
         *   answered `Unbounded` under `allowWholeCopy` — complete coverage at
         *   the old price. A cost note, never a partiality one.
         * @property checkpoint answered from a drained host's retained blob:
         *   state as of the drain. A staleness note, never a partiality one.
         */
        data class State(
            val value: Any?,
            val paged: Boolean = false,
            val partial: Boolean = false,
            val wholeCopy: Boolean = false,
            val checkpoint: Boolean = false,
        ) : Read

        /** The cell holds no readable state (not `Stateful`, not hosted here). Costs nothing. */
        data object None : Read

        /** The read did not land inside the deadline — the search is partial because of it. */
        data object Unanswered : Read

        /**
         * The kernel decided it would not answer, and said why. Distinct from
         * [None]: "there is nothing to read" and "there is something and you may
         * not read it right now" are different facts about coverage.
         */
        data class Refused(val reason: StateReadResult.Reason) : Read
    }

    /**
     * One cell's state — since V1C-BE, **one bounded page** of it.
     *
     * An open observation answers for free; otherwise the host is asked for one
     * page through `ManagedHost.readState`, abandoned if it does not land inside
     * [withinMs] — a slow or wedged cell costs this search its remaining budget,
     * never the whole request, and never the graph. The deadline discipline is
     * M5's verbatim: the `isDone` short-circuit for a read that never reached a
     * cell thread, a bounded `get`, and `cancel(false)` on the miss so an
     * abandoned read costs the host a dequeue rather than a page.
     *
     * `allowWholeCopy = true` so a cell the V1c cell tickets did not cover keeps
     * being searched at all, rather than regressing to "unreadable"; the flag is
     * what makes that a *reported* cost instead of a silent one.
     */
    private fun read(ref: CellRef, withinMs: Long): Read {
        observed(ref)?.let { return Read.State(it.value) }
        val host = registry.locate(ref) ?: return Read.None
        val request = StateRead(limit = SEARCH_PAGE_LIMIT, allowWholeCopy = true)
        val pending = host.readState(ref, request)
        // completed inline for every refusal the kernel decides on the caller's
        // thread: no host task was ever submitted, so this is a cheap skip
        // rather than an abandoned read
        if (pending.isDone) return classify(pending.getNow(null))
        return runCatching { pending.get(withinMs, TimeUnit.MILLISECONDS) }
            .fold(
                onSuccess = { result -> classify(result) },
                onFailure = { pending.cancel(false); Read.Unanswered },
            )
    }

    /**
     * One `StateReadResult` as this search's outcome vocabulary.
     *
     * `NOT_HOSTED` and `NOT_STATEFUL` are [Read.None], not [Read.Refused]: they
     * are the ordinary "this cell holds nothing a search could match" of every
     * non-`Stateful` cell in a graph, and reporting them as coverage failures
     * would make the notice fire on almost every query. Everything else the
     * kernel decided is a real gap and is counted.
     */
    private fun classify(result: StateReadResult?): Read = when (result) {
        null -> Read.None

        is StateReadResult.Page -> Read.State(
            value = result.page.entries,
            paged = true,
            partial = result.page.next != null,
            checkpoint = result.page.provenance == Provenance.CHECKPOINT,
        )

        is StateReadResult.Unbounded -> Read.State(
            value = result.state,
            wholeCopy = true,
            checkpoint = result.provenance == Provenance.CHECKPOINT,
        )

        is StateReadResult.Unavailable -> when (result.reason) {
            StateReadResult.Reason.NOT_HOSTED, StateReadResult.Reason.NOT_STATEFUL -> Read.None
            else -> Read.Refused(result.reason)
        }
    }

    /** A cell's hit line: the matching value, and where it lives. */
    private data class Match(val label: String, val records: Int)

    /**
     * Match [needle] against the *scalars* of one encoded state, counting the
     * records that hold one. A "record" is a `$table` row, a plain-array
     * element, or — for a state that is neither — the whole value.
     *
     * Case-insensitive substring, which subsumes equality: a user looking for
     * `alice` should find `alice` in a `candidate` column without knowing the
     * column exists. Query languages and regex are out of scope.
     */
    private fun matchIn(encoded: JsonElement, needle: String): Match? {
        var label: String? = null
        var records = 0
        for (record in recordsOf(encoded)) {
            val hit = scalars(record).firstOrNull { it.contains(needle, ignoreCase = true) } ?: continue
            records += 1
            if (label == null) label = hit
        }
        return label?.let { Match(it, records) }
    }

    /**
     * The records inside one encoded `Value` (contract §DTOs). Deliberately
     * shallow: a `$table`'s rows and a plain array's elements are the two
     * shapes the encoder produces for "many things", and everything else is one
     * thing. The `$truncated` marker is structure, not a record, and is skipped
     * in both places it can appear.
     */
    private fun recordsOf(encoded: JsonElement): List<JsonElement> {
        if (encoded is JsonArray) return encoded.filterNot { it.isTruncationMarker() }
        val table = (encoded as? JsonObject)?.get(ValueEncoder.TABLE) as? JsonObject
        val rows = table?.get("rows") as? JsonArray ?: return listOf(encoded)
        return rows.toList()
    }

    /**
     * Every scalar leaf under [element], rendered as the string a query is
     * matched against. Two of the encoder's reserved shapes are structure
     * rather than content and are treated as such:
     *
     * - `$truncated` is a marker about the read, not a record in it;
     * - `$opaque` contributes only its `text` (the value's own `toString`),
     *   never its `type` — a user searching for a record must not match every
     *   undecomposable value in the process just because they typed part of a
     *   package name.
     */
    private fun scalars(element: JsonElement): Sequence<String> = when (element) {
        is JsonPrimitive ->
            if (element.content == JSON_NULL && !element.isString) emptySequence() else sequenceOf(element.content)

        is JsonArray -> element.asSequence().flatMap { scalars(it) }
        is JsonObject -> when {
            element.isTruncationMarker() -> emptySequence()
            else -> (element[ValueEncoder.OPAQUE] as? JsonObject)
                ?.let { opaque -> opaque["text"]?.let { scalars(it) } ?: emptySequence() }
                ?: element.values.asSequence().flatMap { scalars(it) }
        }
    }

    private fun JsonElement.isTruncationMarker(): Boolean =
        this is JsonObject && ValueEncoder.TRUNCATED in this && size == 1

    /** Did the encoder's row/byte budget cut this read short anywhere in it? */
    private fun isTruncated(encoded: JsonElement): Boolean = when (encoded) {
        is JsonObject -> ValueEncoder.TRUNCATED in encoded || encoded.values.any { isTruncated(it) }
        is JsonArray -> encoded.any { isTruncated(it) }
        else -> false
    }

    /** The ticket's `detail`: `"graph / cell · type — n record(s)"`. */
    private fun detailOf(component: Component, node: Node, records: Int): String {
        val cell = node.name ?: node.ref
        val type = node.typeFqn.substringAfterLast('.')
        return "${component.label} / $cell · $type — $records ${if (records == 1) "record" else "records"}"
    }

    /**
     * The closing notice, when there is something honest to say about what the
     * search did *not* cover. It rides as a final pseudo-hit with an empty
     * [SearchHit.graph] (the ticket's sanctioned option, chosen over adding a
     * `partial` field the contract does not have — neither side edits
     * `20-api-contract.md` unilaterally). No component id is ever empty
     * (`g-<uuid>`), so the sentinel cannot collide with a real hit, and the
     * client renders it as an inert notice rather than a navigable row.
     */
    private fun notice(cost: Tally): SearchHit? {
        val reasons = buildList {
            if (cost.capReached) add("stopped at the $maxCells-cell cap")
            if (cost.budgetSpent) add("stopped at the ${budgetMs}ms budget")
            if (cost.unanswered > 0) add("${cost.unanswered} ${cells(cost.unanswered)} did not answer in time")
            if (cost.unreadable > 0) add("${cost.unreadable} ${cells(cost.unreadable)} refused the read")
            // V1C-BE: this replaces M5's "read only to the first 200 rows",
            // which described a *rendering* limit while implying a *read* one.
            // Now there is a real read limit, and the honest sentence names
            // entries.
            if (cost.partialPages > 0) {
                add("${cost.partialPages} ${cells(cost.partialPages)} read only their first $SEARCH_PAGE_LIMIT entries")
            }
            // and this one survives, generalized: whatever the *render* budget
            // cut — a whole copy past `ValueEncoder.MAX_ROWS`, or one
            // abbreviated value inside a page — is content that was read but
            // never matched against, which is a different gap from the read
            // bound above and is reported as its own
            if (cost.truncatedReads > 0) {
                add(
                    "${cost.truncatedReads} ${cells(cost.truncatedReads)} matched only against the part of " +
                        "their state the render budget fitted",
                )
            }
            // V1C-BE — a cost note, not a partiality one: this cell was read
            // whole, which is complete coverage at the price the bounded read
            // exists to avoid
            if (cost.wholeCopies > 0) {
                add("${cost.wholeCopies} ${cells(cost.wholeCopies)} cost a whole-state copy — no bounded read")
            }
            // V1C-BE — a staleness note, not a partiality one; the same
            // discipline `provenance: "checkpoint"` applies on the detail panel
            if (cost.checkpointReads > 0) {
                add(
                    "${cost.checkpointReads} ${cells(cost.checkpointReads)} read from a drained host's " +
                        "checkpoint — state as of the drain",
                )
            }
            if (cost.remoteSkipped > 0) add("${cost.remoteSkipped} remote ${cells(cost.remoteSkipped)} skipped")
            // V1C-BE: M5-COLD's "cold graphs skipped — wake to include" is gone.
            // A suspended or drained cell is now read (see the class doc), so
            // the only skip left is the one waking cannot help — and offering
            // "wake to include" for a held ref would advertise a remedy that
            // does nothing, or worse, corrupts the flip it would interrupt.
            if (cost.heldSkipped > 0) {
                add("${cost.heldSkipped} ${cells(cost.heldSkipped)} held mid-migration — not the inspector's to end")
            }
        }
        if (reasons.isEmpty()) return null
        val partial = cost.capReached || cost.budgetSpent || cost.unanswered > 0 ||
            cost.unreadable > 0 || cost.truncatedReads > 0 || cost.partialPages > 0
        return SearchHit(
            graph = NOTICE_GRAPH,
            ref = null,
            label = if (partial) "Partial results" else "Search scope",
            detail = reasons.joinToString(" · "),
        )
    }

    private fun cells(n: Int): String = if (n == 1) "cell" else "cells"

    internal companion object {
        /**
         * How many entries one candidate cell is read for (V1C-BE) — one page,
         * matching `ValueEncoder.MAX_ROWS` and `StateRead`'s own default.
         *
         * Protects the graph from the cost this whole vertical exists to remove:
         * before this, every candidate paid a whole-state copy on its own
         * thread, so a 10⁵-row cell's own live traffic stalled for ~28 ms per
         * search (`30-bounded-read-measurement.md` §4) for a search that would
         * only ever look at 200 rows of it.
         */
        const val SEARCH_PAGE_LIMIT = 200

        /**
         * Pages read per candidate cell (V1C-BE). **One, and deliberately not a
         * walk.** A search that walked a 10⁵-row cell page by page would have
         * re-created the whole copy and added scheduler overhead to it; the win
         * here is that the copy is bounded, not that coverage grew. Named rather
         * than inlined so a later ticket that decides otherwise changes one
         * constant and its justification together.
         */
        const val SEARCH_PAGES_PER_CELL = 1

        /**
         * Cells queried per search. **Unchanged by V1C-BE**, on the measurement
         * rather than on the ticket citation it used to carry:
         * `30-bounded-read-measurement.md` §9 finds "a 40 ms-per-cell budget
         * (`BUDGET_MS / MAX_CELLS` = 2,000 ms / 50) lines up almost exactly with
         * the *tail*, not the median, of a single 10⁵-element copy" and
         * explicitly recommends "neither constant is changed by this ticket".
         * The per-cell cost is now bounded by [SEARCH_PAGE_LIMIT] rather than by
         * the cell's size, which only widens that headroom; the cap still
         * protects the fan-out itself.
         */
        const val MAX_CELLS = 50

        /**
         * The whole fan-out's deadline. **Unchanged by V1C-BE**, for the reason
         * above — and with the gap §9 names left open rather than papered over:
         * "for `MAX_CELLS` whole copies queued back-to-back on the **same** host
         * (E2 shows they fully serialize — one virtual thread per host, §4),
         * this document has no data". Bounding each read to one page makes that
         * scenario cheaper, not measured; re-deriving these two constants needs
         * a measurement that does not exist yet.
         */
        const val BUDGET_MS = 2_000L

        /**
         * The closing notice's [SearchHit.graph]. Empty by construction — a real
         * component id is always `g-<uuid>` — so a client can tell a notice from
         * a hit without a contract field for it.
         */
        const val NOTICE_GRAPH = ""

        /** A JSON null renders as this; it is absence, not a value anyone searches for. */
        private const val JSON_NULL = "null"
    }
}
