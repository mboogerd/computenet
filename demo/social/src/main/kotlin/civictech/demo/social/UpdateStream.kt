/**
 * The ordered application of an [SnbSource]'s update stream to a [SocialGraph]
 * (SOC1, epic `computenet-07k`, feature `computenet-99qcg`, design 99qcg-D10).
 *
 * **One event at a time, in stream order, on the calling thread** — that is
 * the whole of how [SOC1-UPD-03] (events on one entity apply in stream order)
 * is met: there is no batching, reordering or hand-off to another thread
 * here, so an event's effects are issued to the graph strictly after every
 * earlier event's.
 *
 * **Incremental only** ([SOC1-UPD-02]): every event is applied through the one
 * [SocialGraph] method its arm maps to (99qcg-D5). This class never reads the
 * source's static slice and never reloads or recomputes anything from the
 * dataset; the cells' own incremental maintenance is what keeps derived state
 * current. `SocialPipelineTest` pins that by reading this file's text.
 */
package civictech.demo.social

class UpdateStream(source: SnbSource, private val graph: SocialGraph) {

    /** The source's update stream, materialised once, in its own ([UpdateEventOrder]) order. */
    val events: List<UpdateEvent> = source.updates().toList()

    /** How many events of [events] [step] has applied, a prefix; [apply] alone never moves it. */
    var applied: Int = 0
        private set

    /** Events not yet applied by [step]. */
    val remaining: Int get() = events.size - applied

    /**
     * Applies the next unapplied event; `false` (and no effect) at the end of
     * the stream. [applied] advances only once the event's graph call returned,
     * so an event whose call throws is not counted as applied.
     */
    fun step(): Boolean {
        if (applied >= events.size) return false
        apply(events[applied])
        applied++
        return true
    }

    /** Applies up to [n] events; returns how many were applied (fewer only at the end of the stream). */
    fun step(n: Int): Int {
        require(n >= 0) { "n must be non-negative, got $n" }
        var count = 0
        while (count < n && step()) count++
        return count
    }

    /** Applies every remaining event. */
    fun stepAll() {
        while (step()) Unit
    }

    /**
     * Applies [event] through its matching [SocialGraph] method (99qcg-D5,
     * [SOC1-UPD-01]). Public so a replay can re-apply an already-applied
     * event ([SOC1-UPD-04]); does NOT advance [applied]. Exhaustive over the
     * sealed [UpdateEvent] with no `else`, so a new arm fails compilation here.
     */
    fun apply(event: UpdateEvent) {
        when (event) {
            is IU1AddPerson -> graph.addPerson(event.person)
            is IU2LikePost -> graph.addLike(Like(event.personId, event.postId, event.creationDate))
            is IU3LikeComment -> graph.addLike(Like(event.personId, event.commentId, event.creationDate))
            is IU4AddForum -> graph.addForum(event.forum)
            is IU5AddMembership -> graph.joinForum(event.personId, event.forumId, event.creationDate)
            is IU6AddPost -> graph.addPost(event.message)
            is IU7AddComment -> graph.addComment(event.message)
            is IU8AddFriendship -> graph.addKnows(event.a, event.b, event.creationDate)
        }
    }
}
