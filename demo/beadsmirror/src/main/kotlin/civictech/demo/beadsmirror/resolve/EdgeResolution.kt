package civictech.demo.beadsmirror.resolve

import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.WorkspaceMirror
import civictech.demo.beadsmirror.projector.MirrorEdge

/**
 * The outcome of resolving one [MirrorEdge]'s [MirrorEdge.dependsOnIssueId]
 * against the sibling folds hosted in this process (feature computenet-3bso.2,
 * task computenet-3bso.2.1).
 *
 * The three cases are mutually exclusive and collectively exhaustive over
 * "how many hosted workspaces hold this id": exactly one ([Resolved]), none
 * ([Unresolved]), or more than one ([Ambiguous]). None of the three is an
 * error channel — an id no hosted workspace holds is exactly as legitimate a
 * result as one a single workspace holds (3bso.2's acceptance rule: "never
 * dropped, never an error, never misattributed").
 */
sealed class EdgeResolution {

    /**
     * [issueId] is a member of exactly one hosted sibling's fold: [workspaceIdentity]
     * ([WorkspaceMirror.identity]). [fields] is that issue's mirrored field map at
     * the moment of resolution — [civictech.demo.beadsmirror.projector.MirrorProjector.view]'s
     * per-issue slice, JSON-rendered exactly as that projector stores it (undo
     * with [civictech.demo.beadsmirror.ready.ReadyPredicate.stringField], the
     * same helper the ready view uses on this module's own field maps).
     */
    data class Resolved(
        val workspaceIdentity: String,
        val fields: Map<String, String>,
    ) : EdgeResolution()

    /**
     * [issueId] is a member of no hosted workspace's fold. The verbatim id is
     * carried, never dropped — this is the case for a wisp target, a foreign
     * id seeded from another tracker's export, or a workspace this process
     * does not mirror at all; distinguishing those is out of this task's
     * scope (see [MirrorEdge]'s KDoc on why the mirror keeps such ids
     * verbatim in the first place).
     */
    data class Unresolved(val issueId: String) : EdgeResolution()

    /**
     * [issueId] is a member of more than one hosted sibling's fold.
     * [candidates] names every one of them ([WorkspaceMirror.identity]) — the
     * acceptance rule this exists to satisfy is "never a silent pick", so the
     * whole candidate set is surfaced rather than an arbitrary first match.
     */
    data class Ambiguous(val issueId: String, val candidates: Set<String>) : EdgeResolution()
}

/**
 * A read-side, read-time lookup of one [MirrorEdge]'s far side across the
 * mirrors hosted in one [civictech.demo.beadsmirror.BeadsMirrorApp] process
 * (design decisions 3bso.2-D1..D3 — see feature computenet-3bso.2's design
 * note).
 *
 * **A plain lookup object, not a derived cell** (3bso.2-D2): every [resolve]
 * call re-reads each sibling's [MirrorState.current] and re-derives its
 * [civictech.demo.beadsmirror.projector.MirrorProjector.view] from scratch —
 * there is no cached fold of its own to go stale. That is deliberate: a
 * re-baseline swaps a sibling's whole projector via [MirrorState.swap]
 * ([MirrorState]'s KDoc — "every read must go through `state.current` at read
 * time"), and this resolver holds onto [MirrorState] handles rather than
 * [civictech.demo.beadsmirror.projector.MirrorProjector] references for
 * exactly that reason: a captured projector reference would silently start
 * reading a discarded fold the moment its workspace re-baselines.
 *
 * **Attribution is by fold membership, never by prefix** (3bso.2-D3): a
 * candidate is "one whose [civictech.demo.beadsmirror.projector.MirrorProjector.view]
 * has this id as a key right now", nothing about the id's textual shape.
 *
 * Incremental/derived consumption (a cell that recomputes only on change) is
 * computenet-3bso.3's concern, not this one's — this type is deliberately the
 * simplest thing that can be queried, so 3bso.3 has one API to layer on top
 * of rather than two.
 */
class EdgeResolver(private val workspaces: List<Pair<String, MirrorState>>) {

    /**
     * Resolves [issueId] against every hosted sibling's fold, as it stands at
     * the moment of this call (see the class KDoc on why nothing is cached).
     */
    fun resolve(issueId: String): EdgeResolution {
        val holders = workspaces.filter { (_, state) -> issueId in state.current.view() }
        return when (holders.size) {
            0 -> EdgeResolution.Unresolved(issueId)
            1 -> {
                val (identity, state) = holders.single()
                EdgeResolution.Resolved(identity, state.current.view().getValue(issueId))
            }
            else -> EdgeResolution.Ambiguous(issueId, holders.mapTo(LinkedHashSet()) { it.first })
        }
    }

    /** Resolves [edge]'s far side ([MirrorEdge.dependsOnIssueId]) — see [resolve]. */
    fun resolve(edge: MirrorEdge): EdgeResolution = resolve(edge.dependsOnIssueId)

    companion object {

        /**
         * Builds a resolver directly over sibling `(identity, [MirrorState])`
         * pairs, for callers and tests that have no
         * [WorkspaceMirror] to hand — [WorkspaceMirror] itself is only ever
         * constructed over a real `bd`/`dolt` workspace
         * ([WorkspaceMirror.Companion.start]), so in-process unit fixtures
         * build the pair form directly instead.
         */
        fun ofStates(workspaces: List<Pair<String, MirrorState>>): EdgeResolver = EdgeResolver(workspaces)

        /** Builds a resolver over the hosted mirrors, keyed by [WorkspaceMirror.identity]. */
        fun forMirrors(mirrors: List<WorkspaceMirror>): EdgeResolver =
            EdgeResolver(mirrors.map { it.identity to it.state })
    }
}
