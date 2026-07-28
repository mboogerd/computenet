package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost

/**
 * M5-COLD — what "cold" observably means in this kernel today
 * (`doc/spec/90-roadmap/97-inspector-plan/tickets/M5-COLD.md`), and the one
 * seam that ends it.
 *
 * ### Why a vocabulary rather than a boolean
 *
 * The ticket asks for the inspector's cold predicate to be *defined from what
 * exists*, not invented. Three distinguishable "this cell is not running"
 * states exist in the kernel, they do not mean the same thing, and only two of
 * them are something a user may end:
 *
 * | [Heat] | Kernel state | Seam that reports it | Seam that ends it |
 * |---|---|---|---|
 * | [SUSPENDED] | `SupervisionPolicy.SUSPEND`, or an explicit `HostManagementApi.suspend` (spec 34, G-26) — data and ordinary-management traffic parks on the host | `ManagedHost.isSuspended` | `HostManagementApi.resume(ref)` |
 * | [DRAINED] | the whole host drained (spec 33 §Drain, G-16) — intake closed, accepted work flushed, cells deactivated and snapshotted | `ManagedHost.isDrained` | `HostManagementApi.resumeHost()` |
 * | [HELD] | delivery parked for a repartition flip window (spec 20/24, CP-D4) | `LocationRegistry.isHeld` | the migration's own `release` — **never the inspector** |
 * | [UNHOSTED] | no local location: unpublished, or mirrored from a peer (M5-NET) | `LocationRegistry.locate` returning null | not applicable |
 *
 * [HELD] is deliberately *not* cold for listing purposes. A held cell is
 * running; its deliveries are buffered for the milliseconds a partition flip
 * takes, and the only thing that may end that window is the migration that
 * opened it. Offering a "Wake to inspect" button for it would either do
 * nothing or corrupt the flip. It is still skipped by a content search — it
 * genuinely cannot be read consistently right now — which is why [isReadable]
 * and [isCold] are two different questions.
 *
 * ### What is *not* in the predicate, and why
 *
 * **Attention-parked cones** (spec 34): `AttentionScheduler.attentionParked` is
 * internal to the kernel and keyed inside a host's private scheduler, and the
 * band itself lives on the cell object behind `ManagedHost`'s private `cells`
 * map — the same wall M1 hit when it had to answer `CellDetail.attention` with
 * the contract's null. Reporting an attention-parked cone as cold would need
 * new kernel surface, and — worse for this ticket — attention parking is ended
 * by *raising attention*, which is precisely the causal act (P6) the cold
 * screen exists to make explicit rather than automatic. So an attention-parked
 * cone reads as [HOT] here: the honest answer is "the inspector cannot see
 * this today", not a coldness it would then offer to end by the very
 * side-effect it is warning about. Tracked with the rest of
 * inspect-without-attention in Linear MRB-157.
 *
 * ### Cost
 *
 * Every read here is a map lookup on an already-published ref
 * ([LocationRegistry.locate] plus one `ConcurrentHashMap.containsKey` or one
 * volatile field read). Nothing is enqueued on a host, no cell is touched, no
 * link is made and no attention is raised — computing coldness is metadata
 * only, which is what lets the navigator list a cold graph without waking it
 * (constraint P6, `10-target-v3.md` §Constraints 2).
 */
internal enum class Heat {
    /** Running, locally hosted, readable. */
    HOT,

    /** Parked by supervision or an explicit `suspend` — resumable per cell. */
    SUSPENDED,

    /** On a host that has finished draining — resumable per host. */
    DRAINED,

    /** Deliveries parked for a migration flip window. Not the inspector's to end. */
    HELD,

    /** No local host: unpublished, or a peer's ref mirrored into this registry. */
    UNHOSTED,
    ;

    /**
     * Is this the "parked, and the inspector may offer to end it" sense of
     * cold — the one that makes a component's card cold and puts a wake button
     * in front of its canvas? See the class doc for why [HELD] is excluded.
     */
    val isCold: Boolean get() = this == SUSPENDED || this == DRAINED

    /**
     * May a content search read this cell's state right now? Only [HOT]: every
     * other value is a cell that would either refuse the read or answer a torn
     * or stale one, and reading it would also be exactly the touching the cold
     * screen promises not to do.
     */
    val isReadable: Boolean get() = this == HOT

    companion object {
        /**
         * [ref]'s heat, read from registry and host metadata only.
         *
         * Order matters: a cell can be several of these at once (suspended
         * *and* on a drained host, say), and the answer names the most specific
         * thing the inspector could do about it — per-cell resume before
         * per-host resume, and "not yours to end" ([HELD]) before either.
         */
        fun of(registry: LocationRegistry, ref: CellRef): Heat {
            val host = registry.locate(ref) ?: return UNHOSTED
            return when {
                registry.isHeld(ref) -> HELD
                host.isSuspended(ref) -> SUSPENDED
                host.isDrained -> DRAINED
                else -> HOT
            }
        }
    }
}

/**
 * `POST /api/inspect/graph/{id}/wake` (M5-COLD ticket Implement §1): end one
 * component's coldness through the kernel's own resume seams, and report what
 * that took.
 *
 * ### Explicit, never implicit
 *
 * This is the only thing in the inspector that changes the graph it is
 * inspecting. It exists precisely so that nothing else does: browsing, listing,
 * entering a cold graph and selecting a node inside it all stay read-only, and
 * the one causal act is a button a user pressed and confirmed
 * (`10-target-v3.md` §Constraints 2 — "browsing/listing never subscribes";
 * ticket Exclusions — "no auto-wake on any browse action").
 *
 * ### Order, and the blast radius the caller is told about
 *
 * Hosts first, then cells: `resume(ref)` replays the cell's parked traffic
 * through `enqueueHostedInvocation`, which refuses a closed intake, so resuming
 * a suspended cell on a still-drained host would dead-letter its own replay.
 *
 * A drain is a whole-host act and so is its undo: `resumeHost()` reactivates
 * and republishes *every* cell that host holds, including cells of components
 * the user did not ask to wake. [WakeReport.hosts] is how many hosts that was,
 * so the UI can say so instead of implying the wake was confined to the
 * component.
 */
internal class Waker(private val registry: LocationRegistry) {

    /**
     * Resume everything cold in [component]. Idempotent and safe on a hot
     * component (it resumes nothing and reports zeroes) — the endpoint answers
     * 202 either way, because "accepted" is the honest answer for management
     * calls that are enqueued on their hosts rather than executed inline.
     */
    fun wake(component: Component): WakeReport {
        val refs = component.nodes.mapNotNull { InspectorServer.decodeRef(it.ref) }
        val drained = LinkedHashSet<ManagedHost>()
        val suspended = ArrayList<Pair<ManagedHost, CellRef>>()
        refs.forEach { ref ->
            val host = registry.locate(ref) ?: return@forEach
            when (Heat.of(registry, ref)) {
                Heat.DRAINED -> drained += host
                Heat.SUSPENDED -> suspended += host to ref
                else -> Unit
            }
        }
        // a refused management call would land as a dead letter in the very
        // error lane this inspector serves, so each call is guarded by the
        // predicate the kernel itself requires
        drained.forEach { host -> runCatching { host.managementInlet.call.resumeHost() } }
        suspended.forEach { (host, ref) -> runCatching { host.managementInlet.call.resume(ref) } }
        return WakeReport(hosts = drained.size, cells = suspended.size)
    }
}

/** What one [Waker.wake] asked for — the 202's body. */
internal data class WakeReport(
    /** Drained hosts resumed. Each one reactivates every cell it holds, not only this component's. */
    val hosts: Int,
    /** Individually suspended cells resumed. */
    val cells: Int,
) {
    /** Nothing was cold: the wake was a no-op, which is a success, not an error. */
    val isNoop: Boolean get() = hosts == 0 && cells == 0
}
