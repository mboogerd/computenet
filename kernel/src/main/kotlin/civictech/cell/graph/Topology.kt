package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.link.LinkOptions
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
    ) : TopoEvent

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
) {
    fun events(): List<TopoEvent> = buildList {
        addAll(spawns.values)
        addAll(promotions.values)
        families.values.forEach { state ->
            add(state.declaration)
            state.keys.forEach { key -> add(TopoEvent.FamilyKey(state.declaration.family.namespace, key)) }
        }
        addAll(links.values)
    }

    companion object {
        val EMPTY = TopologyFold(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
}

/** The recovery seam that applies one topology event before the next journal record is read. */
fun interface TopologyApplier {
    fun apply(event: TopoEvent)

    /** Called immediately after a checkpoint was restored, before the next journal record. */
    fun checkpointRestored() {}
}

/** Mutable, synchronized owner behind [ApplyContext]'s immutable fold snapshots. */
internal class MutableTopologyFold {
    private val spawns = linkedMapOf<CellRef, TopoEvent.Spawn>()
    private val families = linkedMapOf<String, MutableFamily>()
    private val links = linkedMapOf<TopologyLinkKey, TopoEvent.Connect>()
    private val handles = linkedMapOf<String, CellRef>()
    private val promotions = linkedMapOf<CellRef, TopoEvent.Promote>()

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
    )

    private fun remove(ref: CellRef) {
        spawns.remove(ref)
        handles.entries.removeIf { it.value == ref }
        links.entries.removeIf { (_, edge) -> edge.from == ref || edge.to == ref }
        promotions.entries.removeIf { (incumbent, event) -> incumbent == ref || event.candidate == ref }
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
        (inheritedRoots + event.incumbent).forEach { root ->
            promotions[root] = event.copy(incumbent = root)
        }
        redirected.forEach { links[TopologyLinkKey.of(it)] = it }
    }
}
