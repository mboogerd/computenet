package civictech.inspect

import civictech.cell.CellRef
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
 * - **A pull reply is an emission.** `pullServe` answers through
 *   `FanOutlet.baselineTo`, which mints a wave from the producing outlet's own
 *   counter (the I-16 reply-sequencing rule). `CatchUp.kt`'s own KDoc records
 *   the consequence: that "inflates the `waveState().highWater` that
 *   `civictech.cell.replication` reads directly as a source's delivered
 *   high-water". A read-only instrument must not move a graph's wave plane, and
 *   a search box that silently perturbs replication watermarks is precisely the
 *   kind of observer effect the inspector exists to avoid.
 * - It would also need a hand-built protocol `Link` and a reply *tap* on the
 *   producing outlet (the only way an unlinked requester can receive an `at()`
 *   delivery), which would additionally see every live emission for the
 *   duration of the request.
 *
 * `ManagedHost.snapshotOf` moves none of that: it runs the cell's own
 * `Stateful.snapshot()` on the cell's own execution context, links nothing,
 * emits nothing, subscribes to nothing and raises no attention. A cell the
 * client already has an open observation on is read from that fold instead —
 * free, and already consistent.
 *
 * What it is *not* is cheap: `snapshot()` copies a cell's whole state on that
 * cell's thread. That cost is the reason data mode is submit-triggered rather
 * than per-keystroke, why the fan-out is capped, and why the cost is surfaced.
 *
 * ### Which cells are candidates
 *
 * Locally hosted, not held mid-migration, not suspended, not on a drained host,
 * and readable — an open observation, or a `Stateful` the host answers for.
 * Everything else is skipped and counted:
 *
 * - **not hot** ([Heat.isReadable] false: suspended, on a drained host, or held
 *   for a migration flip) → [SearchCost.coldSkipped]. M5-COLD widened this from
 *   M5-SEARCH's per-cell predicate to the whole-component one its ticket asks
 *   for: a component the navigator lists as cold is skipped *as a component*,
 *   without a per-cell registry walk, and every one of its candidate cells is
 *   counted. The per-cell test still runs for the rest, so a lone parked cell
 *   inside an otherwise-running graph is skipped and counted too.
 * - **not locally hosted** (a mirrored/announced ref — M5-NET) → counted and
 *   reported in the closing notice. Cross-JVM search is out of scope.
 *
 * Nothing here mutates: this class holds no state between requests, so two
 * concurrent searches simply each pay their own bounded fan-out.
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
        var queried = 0
        var coldSkipped = 0
        var remoteSkipped = 0
        var truncatedReads = 0
        var unanswered = 0
        var capReached = false
        var budgetSpent = false
        var coldGraphs = 0

        val ours = instruments()
        components@ for (component in components()) {
            // M5-COLD ticket Implement §3: a cold component is skipped whole,
            // and its candidate cells are what `coldSkipped` counts. Reading
            // even one of them would be exactly the touching the cold screen
            // promises not to do — and the cheapest correct thing is also the
            // most honest one.
            if (component.lifecycle == GraphSummary.COLD) {
                coldGraphs += 1
                coldSkipped += component.nodes.count { node ->
                    InspectorServer.decodeRef(node.ref)?.let { it !in ours } ?: false
                }
                continue
            }
            for (node in component.nodes) {
                val ref = InspectorServer.decodeRef(node.ref) ?: continue
                if (ref in ours) continue
                when (candidacy(ref)) {
                    Candidacy.COLD -> {
                        coldSkipped += 1
                        continue
                    }

                    Candidacy.REMOTE -> {
                        remoteSkipped += 1
                        continue
                    }

                    Candidacy.HOT -> Unit
                }
                if (queried >= maxCells) {
                    capReached = true
                    break@components
                }
                val remaining = deadline - clock()
                if (remaining <= 0) {
                    budgetSpent = true
                    break@components
                }

                val read = read(ref, remaining)
                if (read is Read.Unanswered) {
                    unanswered += 1
                    continue
                }
                if (read !is Read.State) continue
                queried += 1
                val encoded = ValueEncoder.encode(read.value)
                if (isTruncated(encoded)) truncatedReads += 1
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

        notice(capReached, budgetSpent, truncatedReads, unanswered, remoteSkipped, coldGraphs)?.let { hits += it }
        return SearchResult(SearchResult.DATA, hits, SearchCost(cellsQueried = queried, coldSkipped = coldSkipped))
    }

    /** Why a candidate was (or was not) queried — the whole skip vocabulary in one place. */
    private enum class Candidacy { HOT, COLD, REMOTE }

    private fun candidacy(ref: CellRef): Candidacy = when (val heat = Heat.of(registry, ref)) {
        Heat.UNHOSTED -> Candidacy.REMOTE
        else -> if (heat.isReadable) Candidacy.HOT else Candidacy.COLD
    }

    /** The outcome of one candidate read — "nothing to read" and "did not answer" are not the same. */
    private sealed interface Read {
        /** State this search can match against. */
        data class State(val value: Any?) : Read

        /** The cell holds no readable state (not `Stateful`, or nothing to snapshot). Costs nothing. */
        data object None : Read

        /** The read did not land inside the deadline — the search is partial because of it. */
        data object Unanswered : Read
    }

    /**
     * One cell's state. An open observation answers for free; otherwise the
     * host is asked for a routed `Stateful.snapshot()`, abandoned if it does
     * not land inside [withinMs] — a slow or wedged cell costs this search its
     * remaining budget, never the whole request, and never the graph.
     */
    private fun read(ref: CellRef, withinMs: Long): Read {
        observed(ref)?.let { return Read.State(it.value) }
        val host = registry.locate(ref) ?: return Read.None
        val pending = host.snapshotOf(ref)
        // completed inline for a non-Stateful cell: no host task was ever
        // submitted, so this is a cheap skip rather than an abandoned read
        if (pending.isDone) return pending.getNow(null)?.let { Read.State(it) } ?: Read.None
        return runCatching { pending.get(withinMs, TimeUnit.MILLISECONDS) }
            .fold(
                onSuccess = { state -> state?.let { Read.State(it) } ?: Read.None },
                onFailure = { pending.cancel(false); Read.Unanswered },
            )
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
    private fun notice(
        cap: Boolean,
        budget: Boolean,
        truncatedReads: Int,
        unanswered: Int,
        remoteSkipped: Int,
        coldGraphs: Int,
    ): SearchHit? {
        val reasons = buildList {
            if (cap) add("stopped at the $maxCells-cell cap")
            if (budget) add("stopped at the ${budgetMs}ms budget")
            if (unanswered > 0) add("$unanswered ${cells(unanswered)} did not answer in time")
            if (truncatedReads > 0) {
                add("$truncatedReads ${cells(truncatedReads)} read only to the first ${ValueEncoder.MAX_ROWS} rows")
            }
            if (remoteSkipped > 0) add("$remoteSkipped remote ${cells(remoteSkipped)} skipped")
            // M5-COLD: the one skip a user can do something about, so it names
            // the remedy rather than only the fact
            if (coldGraphs > 0) {
                add("$coldGraphs cold ${if (coldGraphs == 1) "graph" else "graphs"} skipped — wake to include")
            }
        }
        if (reasons.isEmpty()) return null
        val partial = cap || budget || unanswered > 0 || truncatedReads > 0
        return SearchHit(
            graph = NOTICE_GRAPH,
            ref = null,
            label = if (partial) "Partial results" else "Search scope",
            detail = reasons.joinToString(" · "),
        )
    }

    private fun cells(n: Int): String = if (n == 1) "cell" else "cells"

    internal companion object {
        /** Ticket: "max 50 cells queried". */
        const val MAX_CELLS = 50

        /** Ticket: "2 s budget". */
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
