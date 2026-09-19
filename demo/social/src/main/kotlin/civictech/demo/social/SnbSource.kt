/**
 * The SNB data-source seam for `:demo:social` (SOC1, epic `computenet-07k`),
 * decided in feature `computenet-99qcg` design 99qcg-D4..D6.
 *
 * An [SnbSource] hands over a bulk [StaticSlice] to load once, and an ordered
 * [UpdateEvent] stream to apply afterwards, one event at a time, through
 * [SocialGraph]. [SnbGenerator] (this task) and `DatagenCsvSource` (a sibling
 * task, [SOC1-GEN-04]) both implement it; [SocialLoader] consumes it without
 * branching on which implementation it got.
 */
package civictech.demo.social

/** One data source of an SNB dataset: a bulk static slice plus an ordered update stream. */
interface SnbSource {
    /** The bulk of the dataset, loaded once, in [StaticSlice]'s own field order. */
    fun staticSlice(): StaticSlice

    /** The remainder of the dataset, applied one event at a time, in [UpdateEventOrder]. */
    fun updates(): Sequence<UpdateEvent>
}

/**
 * One undirected SNB `Knows` edge in the static slice, always stored with
 * `a < b` so the pair has one canonical representation.
 */
data class KnowsEdge(val a: Long, val b: Long, val creationDate: Long) {
    init {
        require(a < b) { "KnowsEdge must be stored with a < b, got a=$a b=$b" }
    }
}

/** One SNB forum membership in the static slice. */
data class Membership(val personId: Long, val forumId: Long, val creationDate: Long)

/**
 * The bulk of an SNB dataset, loaded once. Fields are in [SocialLoader]'s load
 * order (99qcg-D4): tagClasses, tags, places, organisations, persons, knows,
 * forums, memberships, messages (posts and comments interleaved, sorted by
 * `(creationDate, id)` so a comment's parent always sorts before it), likes.
 */
data class StaticSlice(
    val tagClasses: List<TagClass>,
    val tags: List<Tag>,
    val places: List<Place>,
    val organisations: List<Organisation>,
    val persons: List<Person>,
    val knows: List<KnowsEdge>,
    val forums: List<Forum>,
    val memberships: List<Membership>,
    val messages: List<Message>,
    val likes: List<Like>,
)

/**
 * One LDBC SNB interactive-update event (IU1..IU8), decided in 99qcg-D5. Every
 * arm carries the ids [SocialLoader]/[UpdateStream] needs to replay it through
 * the matching [SocialGraph] method, plus a [creationDate] (epoch millis) used
 * for [UpdateEventOrder] and by [validateReferences].
 */
sealed interface UpdateEvent {
    val creationDate: Long
}

/** IU1: add person. Applied via [SocialGraph.addPerson]. */
data class IU1AddPerson(val person: Person) : UpdateEvent {
    override val creationDate: Long get() = person.creationDate
}

/** IU2: like a post. Applied via `SocialGraph.addLike`. */
data class IU2LikePost(val personId: Long, val postId: Long, override val creationDate: Long) : UpdateEvent

/** IU3: like a comment. Applied via `SocialGraph.addLike`. */
data class IU3LikeComment(val personId: Long, val commentId: Long, override val creationDate: Long) : UpdateEvent

/** IU4: add forum. Applied via [SocialGraph.addForum]. */
data class IU4AddForum(val forum: Forum) : UpdateEvent {
    override val creationDate: Long get() = forum.creationDate
}

/** IU5: add forum membership. Applied via [SocialGraph.joinForum]. */
data class IU5AddMembership(val personId: Long, val forumId: Long, override val creationDate: Long) : UpdateEvent

/** IU6: add post (`message.forumId` set, `message.replyOfId` null). Applied via [SocialGraph.addPost]. */
data class IU6AddPost(val message: Message) : UpdateEvent {
    init {
        require(message.forumId != null) { "IU6AddPost message ${message.id} must set forumId" }
        require(message.replyOfId == null) { "IU6AddPost message ${message.id} must not set replyOfId" }
    }

    override val creationDate: Long get() = message.creationDate
}

/** IU7: add comment (`message.replyOfId` set). Applied via [SocialGraph.addComment]. */
data class IU7AddComment(val message: Message) : UpdateEvent {
    init {
        require(message.replyOfId != null) { "IU7AddComment message ${message.id} must set replyOfId" }
    }

    override val creationDate: Long get() = message.creationDate
}

/** IU8: add friendship (a `Knows` edge). Applied via [SocialGraph.addKnows]. */
data class IU8AddFriendship(val a: Long, val b: Long, override val creationDate: Long) : UpdateEvent

/** 1..8, in the IU numbering, for [UpdateEventOrder]'s secondary key. */
private val UpdateEvent.kindOrdinal: Int
    get() = when (this) {
        is IU1AddPerson -> 1
        is IU2LikePost -> 2
        is IU3LikeComment -> 3
        is IU4AddForum -> 4
        is IU5AddMembership -> 5
        is IU6AddPost -> 6
        is IU7AddComment -> 7
        is IU8AddFriendship -> 8
    }

/** The ids carried by this event, in field order, for [UpdateEventOrder]'s tertiary key. */
private fun UpdateEvent.orderIds(): List<Long> = when (this) {
    is IU1AddPerson -> listOf(person.id)
    is IU2LikePost -> listOf(personId, postId)
    is IU3LikeComment -> listOf(personId, commentId)
    is IU4AddForum -> listOf(forum.id)
    is IU5AddMembership -> listOf(personId, forumId)
    is IU6AddPost -> listOf(message.id)
    is IU7AddComment -> listOf(message.id)
    is IU8AddFriendship -> listOf(a, b)
}

/**
 * The total order [UpdateEvent]s are applied in (99qcg-D5):
 * `(creationDate, kindOrdinal 1..8, then the ids in field order)`.
 */
val UpdateEventOrder: Comparator<UpdateEvent> = Comparator { x, y ->
    var c = x.creationDate.compareTo(y.creationDate)
    if (c != 0) return@Comparator c
    c = x.kindOrdinal.compareTo(y.kindOrdinal)
    if (c != 0) return@Comparator c
    val xi = x.orderIds()
    val yi = y.orderIds()
    for (i in 0 until maxOf(xi.size, yi.size)) {
        c = xi.getOrElse(i) { 0L }.compareTo(yi.getOrElse(i) { 0L })
        if (c != 0) return@Comparator c
    }
    0
}

/**
 * Walks [updates] carrying the known id sets of [slice] forward, and throws
 * [IllegalStateException] naming the offending event the moment one
 * references an entity absent from the slice and from every earlier event
 * (99qcg-D6, [SOC1-GEN-06]). [SnbGenerator.updates] runs this over its own
 * output before returning it.
 */
fun validateReferences(slice: StaticSlice, updates: Sequence<UpdateEvent>) {
    val knownPersons = slice.persons.mapTo(HashSet()) { it.id }
    val knownForums = slice.forums.mapTo(HashSet()) { it.id }
    val knownMessages = slice.messages.mapTo(HashSet()) { it.id }

    updates.forEachIndexed { index, event ->
        fun fail(kind: String, id: Long): Nothing =
            throw IllegalStateException("event #$index $event references unknown $kind $id")

        when (event) {
            is IU1AddPerson -> {}
            is IU2LikePost -> {
                if (event.personId !in knownPersons) fail("person", event.personId)
                if (event.postId !in knownMessages) fail("message", event.postId)
            }
            is IU3LikeComment -> {
                if (event.personId !in knownPersons) fail("person", event.personId)
                if (event.commentId !in knownMessages) fail("message", event.commentId)
            }
            is IU4AddForum -> {
                if (event.forum.moderatorId !in knownPersons) fail("person", event.forum.moderatorId)
            }
            is IU5AddMembership -> {
                if (event.personId !in knownPersons) fail("person", event.personId)
                if (event.forumId !in knownForums) fail("forum", event.forumId)
            }
            is IU6AddPost -> {
                if (event.message.creatorId !in knownPersons) fail("person", event.message.creatorId)
                val forumId = event.message.forumId
                if (forumId == null || forumId !in knownForums) fail("forum", forumId ?: -1L)
            }
            is IU7AddComment -> {
                if (event.message.creatorId !in knownPersons) fail("person", event.message.creatorId)
                val replyOfId = event.message.replyOfId
                if (replyOfId == null || replyOfId !in knownMessages) fail("message", replyOfId ?: -1L)
            }
            is IU8AddFriendship -> {
                if (event.a !in knownPersons) fail("person", event.a)
                if (event.b !in knownPersons) fail("person", event.b)
            }
        }

        when (event) {
            is IU1AddPerson -> knownPersons += event.person.id
            is IU4AddForum -> knownForums += event.forum.id
            is IU6AddPost -> knownMessages += event.message.id
            is IU7AddComment -> knownMessages += event.message.id
            else -> {}
        }
    }
}
