package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.link.LinkOptions
import civictech.cell.replication.WriteAuthority
import java.io.Serializable
import java.util.UUID

/**
 * One journaled topology mutation. Every endpoint is a concrete [CellRef]: handles are
 * construction labels only, while recovery rebinds the exact instance identities the
 * pre-crash graph exposed (93 I-7 R3/R6).
 */
sealed interface TopoEvent : Serializable {
    data class Spawn(
        val handle: String,
        val ref: CellRef,
        val factory: CellFactory,
        val parent: CellRef?,
        val replicated: Boolean,
        val journalId: String?,
        val shadow: Boolean,
        val authority: WriteAuthority? = null,
    ) : TopoEvent {
        private companion object {
            private const val serialVersionUID: Long = 9183278101467966974L
        }
    }

    data class Connect(
        val from: CellRef,
        val outlet: String,
        val to: CellRef,
        val inlet: String,
        val options: LinkOptions,
    ) : TopoEvent

    data class Unlink(
        val from: CellRef,
        val outlet: String,
        val to: CellRef,
        val inlet: String,
    ) : TopoEvent

    data class Despawn(val ref: CellRef) : TopoEvent

    /**
     * One completed promotion swap in the durability plane (uwt8b-D8). The
     * source id and high-water are flattened here because every persisted
     * [TopoEvent] field must itself be Java-serializable.
     */
    data class Promote(
        /** The single-instance membrane gate completed green by this commit; absent for replicas. */
        val gate: CellRef?,
        val incumbent: CellRef,
        val candidate: CellRef,
        val outlet: String,
        val candidateFactory: CellFactory,
        val replicated: Boolean,
        val sourceId: UUID,
        val highWater: Long,
    ) : TopoEvent

    data class Family(
        val handle: String,
        val family: KeyedFamily,
        val factory: KeyedCellFactory,
    ) : TopoEvent

    data class FamilyKey(val namespace: String, val key: String) : TopoEvent

    /**
     * Write-ahead evidence that [candidate] is tapped by a live [civictech.cell.evolve.Evolve]
     * run (computenet-q37rn): written only by `ApplyContext.evolve`'s hooks, before the tap
     * [Connect] it precedes, never by a declarative [GraphSpec] writer. A declared shadow's
     * staged links — however they are ordered relative to its other edges — carry no such
     * record, so recovery no longer infers "interrupted evolution" from link shape or
     * declaration order.
     *
     * Additive: a journal written before this change contains no `EvolutionTap`, and decodes
     * unchanged (existing [Connect] bytes are untouched). A build predating this change cannot
     * decode a journal that contains one — `ObjectInputStream` fails closed on the unknown
     * class — which is this record's explicit downgrade boundary.
     *
     * Retired implicitly, never by a paired "end" record: [MutableTopologyFold] drops the
     * marker the moment [candidate] is folded into a completed [Promote], or removed by a
     * [Despawn] (the abort-cleanup path `ApplyContext.abortRecoveredEvolutions` and the live
     * reject path `EvolutionHooks.despawnShadow` both end with one). So after a complete
     * journal replay, [TopologyFold.activeEvolutions] holds exactly the candidates whose
     * evolution never reached either outcome before the process stopped.
     */
    data class EvolutionTap(val candidate: CellRef) : TopoEvent
}

/** Stable key for one live edge in a [TopologyFold]. */
data class TopologyLinkKey(
    val from: CellRef,
    val outlet: String,
    val to: CellRef,
    val inlet: String,
) {
    companion object {
        fun of(event: TopoEvent.Connect) = TopologyLinkKey(event.from, event.outlet, event.to, event.inlet)
        fun of(event: TopoEvent.Unlink) = TopologyLinkKey(event.from, event.outlet, event.to, event.inlet)
    }
}

/** One live family declaration and the rendered keys known beneath it. */
data class TopologyFamily(val declaration: TopoEvent.Family, val keys: Set<String>)

/**
 * The folded live topology exposed by [ApplyContext.live]. Unlinks and despawns have already
 * been removed. [events] is therefore the compact checkpoint form: declarations before keys,
 * and all nodes before links.
 */
data class TopologyFold(
    val spawns: Map<CellRef, TopoEvent.Spawn>,
    val families: Map<String, TopologyFamily>,
    val links: Map<TopologyLinkKey, TopoEvent.Connect>,
    val handles: Map<String, CellRef>,
    /**
     * Completed distinct-ref promotions retained as recovery provenance after folding. A build
     * predating this field cannot read a checkpoint that carries one: its fold requires the
     * incumbent's spawn for every Promote, so a downgrade fails recovery of such a journal.
     */
    val promotions: Map<CellRef, TopoEvent.Promote>,
    /**
     * Candidates with an open [TopoEvent.EvolutionTap] and no retiring [TopoEvent.Promote] or
     * [TopoEvent.Despawn] yet (computenet-q37rn). Carried through checkpoint compaction via
     * [events] so a checkpoint taken mid-evolution still marks the candidate recoverable as
     * "interrupted" rather than silently losing that provenance. Empty for every journal
     * written before this field existed; such a journal never contains an `EvolutionTap`
     * either, so recovery of it classifies no candidate as interrupted (same as before this
     * change).
     */
    val activeEvolutions: Set<CellRef> = emptySet(),
) {
    fun events(): List<TopoEvent> = buildList {
        addAll(spawns.values)
        addAll(promotions.values)
        activeEvolutions.forEach { candidate -> add(TopoEvent.EvolutionTap(candidate)) }
        families.values.forEach { state ->
            add(state.declaration)
            state.keys.forEach { key -> add(TopoEvent.FamilyKey(state.declaration.family.namespace, key)) }
        }
        addAll(links.values)
    }

    companion object {
        val EMPTY = TopologyFold(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet())
    }
}

/** The recovery seam that applies one topology event before the next journal record is read. */
fun interface TopologyApplier {
    fun apply(event: TopoEvent)

    /** Called immediately after a checkpoint was restored, before the next journal record. */
    fun checkpointRestored() {}

    /**
     * The candidate refs this applier's fold currently holds an open [TopoEvent.EvolutionTap]
     * for (computenet-q37rn), read by [civictech.cell.host.HostDurability.recoverFrom] once the
     * complete journal has been applied, before it stages any decoded frame for delivery. A
     * frame targeting one of these refs is excluded from staging rather than delivered and
     * later dead-lettered once [ApplyContext] despawns the still-unjudged candidate. The
     * default empty set is correct for a caller with no topology (no evolution is possible
     * without one).
     */
    fun activeEvolutions(): Set<CellRef> = emptySet()
}

/** Mutable, synchronized owner behind [ApplyContext]'s immutable fold snapshots. */
internal class MutableTopologyFold {
    private val spawns = linkedMapOf<CellRef, TopoEvent.Spawn>()
    private val families = linkedMapOf<String, MutableFamily>()
    private val links = linkedMapOf<TopologyLinkKey, TopoEvent.Connect>()
    private val handles = linkedMapOf<String, CellRef>()
    private val promotions = linkedMapOf<CellRef, TopoEvent.Promote>()
    private val activeEvolutions = linkedSetOf<CellRef>()

    private data class MutableFamily(val declaration: TopoEvent.Family, val keys: LinkedHashSet<String>)

    @Synchronized
    fun containsHandle(handle: String): Boolean = handle in handles || handle in families

    @Synchronized
    fun refFor(handle: String): CellRef? = handles[handle]

    @Synchronized
    fun adopt(handle: String, ref: CellRef) {
        check(!containsHandle(handle)) { "duplicate handle '$handle'" }
        handles[handle] = ref
    }

    @Synchronized
    fun record(event: TopoEvent) {
        when (event) {
            is TopoEvent.Spawn -> {
                check(!containsHandle(event.handle)) { "duplicate handle '${event.handle}'" }
                spawns[event.ref] = event
                handles[event.handle] = event.ref
            }

            is TopoEvent.Family -> {
                check(!containsHandle(event.handle)) { "duplicate handle '${event.handle}'" }
                families[event.handle] = MutableFamily(event, linkedSetOf())
            }

            is TopoEvent.FamilyKey -> families.values
                .firstOrNull { it.declaration.family.namespace == event.namespace }
                ?.keys
                ?.add(event.key)

            is TopoEvent.Connect -> links[TopologyLinkKey.of(event)] = event
            is TopoEvent.Unlink -> links.remove(TopologyLinkKey.of(event))
            is TopoEvent.Despawn -> remove(event.ref)
            is TopoEvent.Promote -> recordPromotion(event)
            is TopoEvent.EvolutionTap -> activeEvolutions.add(event.candidate)
        }
    }

    @Synchronized
    fun linksTouching(ref: CellRef): List<TopoEvent.Connect> =
        links.values.filter { it.from == ref || it.to == ref }

    @Synchronized
    fun snapshot(): TopologyFold = TopologyFold(
        spawns = LinkedHashMap(spawns),
        families = families.mapValuesTo(linkedMapOf()) { (_, state) ->
            TopologyFamily(state.declaration, LinkedHashSet(state.keys))
        },
        links = LinkedHashMap(links),
        handles = LinkedHashMap(handles),
        promotions = LinkedHashMap(promotions),
        activeEvolutions = LinkedHashSet(activeEvolutions),
    )

    private fun remove(ref: CellRef) {
        spawns.remove(ref)
        handles.entries.removeIf { it.value == ref }
        links.entries.removeIf { (_, edge) -> edge.from == ref || edge.to == ref }
        promotions.entries.removeIf { (incumbent, event) -> incumbent == ref || event.candidate == ref }
        activeEvolutions.remove(ref)
    }

    private fun recordPromotion(event: TopoEvent.Promote) {
        val incumbentSpawn = spawns[event.incumbent]
        if (incumbentSpawn == null) {
            val candidateSpawn = checkNotNull(spawns[event.candidate]) {
                "promotion provenance names missing candidate ${event.candidate}"
            }
            check(!candidateSpawn.shadow) {
                "promotion provenance candidate ${event.candidate} is still shadowed"
            }
            promotions[event.incumbent] = event
            return
        }
        if (event.incumbent == event.candidate) {
            spawns[event.incumbent] = incumbentSpawn.copy(
                factory = event.candidateFactory,
                replicated = event.replicated,
                shadow = false,
            )
            activeEvolutions.remove(event.incumbent)
            return
        }

        val candidateSpawn = checkNotNull(spawns[event.candidate]) {
            "promotion names missing candidate ${event.candidate}"
        }
        val redirected = links.values
            .filter { it.from == event.incumbent && it.outlet == event.outlet }
            .map { it.copy(from = event.candidate) }
        val inheritedRoots = promotions
            .filterValues { it.candidate == event.incumbent }
            .keys

        remove(event.incumbent)
        spawns[event.candidate] = candidateSpawn.copy(
            factory = event.candidateFactory,
            replicated = event.replicated,
            shadow = false,
        )
        activeEvolutions.remove(event.candidate)
        (inheritedRoots + event.incumbent).forEach { root ->
            promotions[root] = event.copy(incumbent = root)
        }
        redirected.forEach { links[TopologyLinkKey.of(it)] = it }
    }
}
