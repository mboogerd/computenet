package civictech.inspect

import civictech.cell.CellRef
import java.util.UUID

/**
 * M4 — the connected-component index
 * (`doc/spec/90-roadmap/97-inspector-plan/tickets/M4-BE.md`).
 *
 * ### Why a component index at all
 *
 * There is no `Graph` entity in the kernel: a "graph" is an *emergent*
 * connected component over the link set, and components merge and split
 * whenever links are created or removed (`10-target-v3.md` §Known kernel gaps
 * — the real answer, membranes as the nameable boundary, is tracked in Linear
 * MRB-156 and out of scope). So this index is the pragmatic stand-in: the
 * undirected connected components of the cells one [civictech.cell.host.LocationRegistry]
 * publishes, joined by the links its `TopologyIndex` records.
 *
 * **Undirected**: an edge connects its two endpoints regardless of role or
 * direction. A `CONSUME` link and an `OBSERVE` tap both mean "these two cells
 * belong to the same graph"; only reachability matters, never flow direction.
 *
 * ### Identity
 *
 * `g-<lexicographically-min cell uuid in the component>`. Stable under growth
 * (adding a cell whose uuid sorts above the current minimum leaves the id
 * alone) and — by design, not by accident — *unstable* across merges and
 * splits, because a merged component genuinely is not either of its parents.
 * That instability is exactly why the contract carries a `graphs.changed`
 * event and why [InspectorModel] anchors graph *names* to a cell rather than
 * to an id.
 *
 * The uuid, not the encoded ref: two instances of one logical cell (a replica,
 * `CellRef.instanceId > 0`) share a uuid, so the id does not flip when the
 * minimum member is replaced by a later instance of itself.
 *
 * ### Update discipline (cost, and which thread pays it)
 *
 * Every mutator is O(1) and merely invalidates the memoized partition; the
 * O(V+E) sweep runs lazily inside [partition], on whichever thread reads next
 * — normally an HTTP thread or the inspector's own scheduler.
 *
 * The one exception is [addCell], which is called from the registry's publish
 * hook and whose caller needs the new node's component id *immediately* (the
 * `topology.node` payload carries `Node.graph`). A cell no recorded link
 * mentions is a singleton component by construction, so that case extends the
 * memoized partition in place, in constant time, without invalidating it —
 * which is what keeps building an N-cell graph O(N) rather than O(N²). A
 * publish of a cell some link already names (a mirrored edge that arrived
 * first) falls back to invalidation.
 *
 * Even in the fallback the sweep is no worse than what the kernel already
 * spends on the same rare path: `TopologyIndex.wouldCloseCycle` walks the graph
 * on every `connect`. Nothing here runs on the per-message data path (P2).
 *
 * Not internally synchronized: every method is called by [InspectorModel]
 * under its single monitor, the same one that guards `nodes`/`edges`/`seq`.
 */
internal class ComponentIndex {

    /** Every locally published cell — the vertex set. */
    private val cells = LinkedHashSet<CellRef>()

    /** Every recorded link's endpoints, by link id — the edge set, retained for [removeLink]. */
    private val links = LinkedHashMap<UUID, Pair<CellRef, CellRef>>()

    /** How many recorded links mention a cell; drives [addCell]'s singleton fast path. */
    private val incidence = HashMap<CellRef, Int>()

    /** The memoized `cell -> component id` assignment; null once a mutation invalidated it. */
    private var assignment: MutableMap<CellRef, String>? = null

    /**
     * A newly published cell. Idempotent — a re-publish (`resumeHost`, a
     * returning migration) is already a member and changes nothing.
     */
    fun addCell(ref: CellRef) {
        if (!cells.add(ref)) return
        val current = assignment
        when {
            // already stale: the next read sweeps anyway
            current == null -> Unit
            // some link already names this cell, so it may join an existing
            // component — only a sweep can say which
            incidence.containsKey(ref) -> assignment = null
            // no link mentions it: a singleton component, exactly and cheaply
            else -> current[ref] = idOf(listOf(ref))
        }
    }

    /** A despawn. Removing a vertex can split its component, so the partition is dropped. */
    fun removeCell(ref: CellRef) {
        if (cells.remove(ref)) assignment = null
    }

    /**
     * A new link. Endpoints with no cell (a free-standing `Use.fixed` target)
     * connect nothing and are not recorded; an endpoint whose cell is not (yet)
     * published is recorded anyway, so the component forms as soon as it is.
     */
    fun addLink(id: UUID, from: CellRef?, to: CellRef?) {
        if (from == null || to == null) return
        val previous = links.put(id, from to to)
        if (previous != null) release(previous)
        retain(from to to)
        assignment = null
    }

    /** An unlink. Removing an edge can split a component, so the partition is dropped. */
    fun removeLink(id: UUID) {
        val endpoints = links.remove(id) ?: return
        release(endpoints)
        assignment = null
    }

    /** [ref]'s component id, or null when this index has never been told about it. */
    fun componentOf(ref: CellRef): String? = partition()[ref]

    /** The full `cell -> component id` assignment, swept if a mutation invalidated it. */
    fun partition(): Map<CellRef, String> = assignment ?: sweep()

    /** `id -> members`, insertion-ordered by first-seen member. */
    fun components(): Map<String, Set<CellRef>> {
        val assigned = partition()
        val grouped = LinkedHashMap<String, MutableSet<CellRef>>()
        cells.forEach { ref ->
            val id = assigned[ref] ?: return@forEach
            grouped.getOrPut(id) { LinkedHashSet() } += ref
        }
        return grouped
    }

    private fun retain(endpoints: Pair<CellRef, CellRef>) =
        endpoints.toList().distinct().forEach { ref -> incidence[ref] = (incidence[ref] ?: 0) + 1 }

    private fun release(endpoints: Pair<CellRef, CellRef>) =
        endpoints.toList().distinct().forEach { ref ->
            val left = (incidence[ref] ?: 0) - 1
            if (left > 0) incidence[ref] = left else incidence.remove(ref)
        }

    /**
     * One O(V+E) pass: build undirected adjacency over the links whose *both*
     * endpoints are published here, then flood-fill from every unvisited cell.
     * A link naming a cell this registry does not publish contributes nothing —
     * it cannot make two local cells reachable from one another.
     */
    private fun sweep(): MutableMap<CellRef, String> {
        val adjacency = HashMap<CellRef, MutableList<CellRef>>()
        links.values.forEach { (from, to) ->
            if (from == to || from !in cells || to !in cells) return@forEach
            adjacency.getOrPut(from) { ArrayList() } += to
            adjacency.getOrPut(to) { ArrayList() } += from
        }
        val assigned = HashMap<CellRef, String>(cells.size)
        val seen = HashSet<CellRef>(cells.size)
        cells.forEach { start ->
            if (!seen.add(start)) return@forEach
            val members = ArrayList<CellRef>().apply { add(start) }
            val frontier = ArrayDeque<CellRef>().apply { add(start) }
            while (frontier.isNotEmpty()) {
                adjacency[frontier.removeLast()]?.forEach { next ->
                    if (seen.add(next)) {
                        members += next
                        frontier += next
                    }
                }
            }
            val id = idOf(members)
            members.forEach { assigned[it] = id }
        }
        return assigned.also { assignment = it }
    }

    private companion object {
        const val PREFIX = "g-"

        /** The contract's heuristic: `g-` plus the lexicographically-min member uuid. */
        fun idOf(members: Collection<CellRef>): String = PREFIX + members.minOf { it.id.toString() }
    }
}

/**
 * One connected component, resolved against the topology view that produced
 * it: its id, its (optional, host-annotated) name, and its member nodes with
 * `Node.graph` already stamped. Built under [InspectorModel]'s monitor so the
 * membership, the placement fields and the names it carries are one consistent
 * read.
 */
internal data class Component(
    val id: String,
    val name: String?,
    val nodes: List<Node>,
) {
    /** The contract's encoded refs of this component's members — the health-rollup scope. */
    val refs: Set<String> get() = nodes.mapTo(LinkedHashSet()) { it.ref }

    /** What the navigator shows when there is no name: the id itself. */
    val label: String get() = name ?: id

    /**
     * M5-COLD — `GraphSummary.lifecycle`: [GraphSummary.COLD] when every member
     * cell is parked, [GraphSummary.HOT] otherwise.
     *
     * Derived from the members' own already-stamped [Node.lifecycle] rather
     * than re-read from the registry, so a card and the canvas behind it can
     * never disagree about the same cells — [InspectorModel] stamps both from
     * one [Heat] read, under one monitor.
     *
     * **All**, not any: one running cell means the graph is computing, and a
     * screen that told the user otherwise — and offered to "wake" what is
     * already awake — would be worse than no screen. An empty component cannot
     * occur (a component is a non-empty set of cells by construction) and
     * reports hot rather than claiming coldness about nothing.
     */
    val lifecycle: String
        get() = if (nodes.isNotEmpty() && nodes.all { it.lifecycle == Node.SUSPENDED }) {
            GraphSummary.COLD
        } else {
            GraphSummary.HOT
        }
}

/**
 * The read models `GET /api/inspect/graphs` and `GET /api/inspect/search`
 * serve, derived from a [Component] list and (for health) one [ErrorSnapshot].
 *
 * Pure functions on purpose: the two inputs are each captured under their own
 * owner's lock ([InspectorModel] for components, [Errors] for the error
 * snapshot), and joining them here means neither lock is held while the other
 * is taken.
 */
internal object Graphs {

    /** `GET /api/inspect/graphs` — the contract's `GraphList`, ordered by id. */
    fun list(components: List<Component>, errors: ErrorSnapshot): GraphList = GraphList(
        components.map { component ->
            GraphSummary(
                id = component.id,
                name = component.name,
                cells = component.nodes.size,
                hosts = component.nodes.mapNotNull { it.host }.distinct().size,
                nets = component.nodes.map { it.net }.distinct().size,
                health = health(component, errors),
                lifecycle = component.lifecycle,
            )
        },
    )

    /**
     * `?mode=name` — case-insensitive substring over graph names and cell
     * names/types. Graph hits carry no ref; cell hits carry theirs, so the
     * client can open the graph *and* select the cell.
     *
     * A blank query matches nothing rather than everything: the navigator
     * types into this endpoint on every keystroke, and an empty box means "no
     * filter", which the client already renders from its card list.
     */
    fun byName(components: List<Component>, query: String): SearchResult {
        val needle = query.trim()
        if (needle.isEmpty()) return SearchResult(SearchResult.NAME, emptyList())
        val hits = ArrayList<SearchHit>()
        components.forEach { component ->
            if (component.name?.contains(needle, ignoreCase = true) == true ||
                component.id.contains(needle, ignoreCase = true)
            ) {
                hits += SearchHit(
                    graph = component.id,
                    label = component.label,
                    detail = "${component.nodes.size} cells",
                )
            }
            component.nodes.forEach { node ->
                val byName = node.name?.contains(needle, ignoreCase = true) == true
                val byType = node.typeFqn.contains(needle, ignoreCase = true)
                if (byName || byType) {
                    hits += SearchHit(
                        graph = component.id,
                        ref = node.ref,
                        label = node.name ?: node.ref,
                        detail = node.typeFqn,
                    )
                }
            }
        }
        return SearchResult(SearchResult.NAME, hits)
    }

    /**
     * `?mode=problems` — every graph with a nonzero health counter, ordered by
     * severity: dead letters first, then parked, then restarts, with the id as
     * the final tie-break so the list is stable between refreshes.
     */
    fun problems(components: List<Component>, errors: ErrorSnapshot): SearchResult {
        val hits = components
            .map { it to health(it, errors) }
            .filter { (_, health) -> health.deadLetters > 0 || health.parked > 0 || health.restarts > 0 }
            .sortedWith(
                compareByDescending<Pair<Component, GraphHealth>> { it.second.deadLetters }
                    .thenByDescending { it.second.parked }
                    .thenByDescending { it.second.restarts }
                    .thenBy { it.first.id },
            )
            .map { (component, health) ->
                SearchHit(
                    graph = component.id,
                    label = component.label,
                    detail = describe(health),
                )
            }
        return SearchResult(SearchResult.PROBLEMS, hits)
    }

    /**
     * The per-component health rollup, scoped to the component's own refs.
     *
     * Read off the [ErrorSnapshot]'s *rows*, never its counters: the counters
     * are host-wide totals from `ManagedHost.supervisionAccounting()` with no
     * cell attribution at all, so they cannot be split between two components
     * sharing a host. The rows carry refs and can. The consequence is that
     * dead letters and restarts roll up only what M2's bounded ring buffers
     * still retain (cap 200 each) — the honest per-graph number, which the
     * top-level `GET /errors` counters remain the true totals for.
     *
     * computenet-tr82 — a `BoundaryPolicy` refusal (`DeadLetterRow.denial !=
     * null`) is an enforced boundary working, never a fault, so it is excluded
     * from [GraphHealth.deadLetters] the way `computenet-0994` excluded it
     * from the client-side error counters. This rollup is the *fault*
     * register; refusals are read per-graph off `GET /errors`' rows, which
     * retain them and carry the typed `denial`.
     */
    private fun health(component: Component, errors: ErrorSnapshot): GraphHealth {
        val refs = component.refs
        return GraphHealth(
            deadLetters = errors.deadLetters.count { it.ref in refs && it.denial == null },
            parked = errors.parked.filter { it.ref in refs }.sumOf { it.count },
            restarts = errors.restarts.count { it.ref in refs },
        )
    }

    /** A problems hit's `detail`: the nonzero counters, most severe first. */
    internal fun describe(health: GraphHealth): String = listOf(
        health.deadLetters to "dead",
        health.parked to "parked",
        // "dead" and "parked" are adjectives and read the same at any count;
        // "restart" is a noun and does not
        health.restarts to if (health.restarts == 1) "restart" else "restarts",
    ).filter { it.first > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }
}
