package civictech.demo.social

import kotlin.test.assertEquals as kAssertEquals

/**
 * A pure-Kotlin batch fold of an SNB dataset — the reference the incremental
 * cells are compared against (SOC1, feature `computenet-99qcg`, design
 * 99qcg-D11; B14's "incremental equals batch").
 *
 * [load] folds a [StaticSlice] exactly as `SocialLoader.load` would write it,
 * and [apply] folds one [UpdateEvent] exactly as `UpdateStream.apply` would,
 * into plain maps — no cell, no host, no kernel type. [assertEquals] then
 * reads the same relations back out of a [SocialGraph] ([observe]) and
 * compares relation by relation, naming the relation and the caller's label
 * in every failure.
 *
 * Deliberately a plain class: later SOC1 features (F6) extend it with the
 * derived reads they add. Keep it free of kernel imports — only
 * `civictech.demo.social` types.
 *
 * **What is and is not compared.** The nine 99qcg-D11 relations plus the three
 * id sets. Facts no source writes today (`PersonFact.StudyAt/WorkAt/
 * HasInterest`, `ForumFact.HasTag`, `MessageFact.HasTag`) and the static
 * dimension cells (tags, tag classes, places, organisations) are not folded
 * here, so a stray fact of one of those kinds would not be caught.
 */
class BatchModel {

    /**
     * One snapshot of every compared relation. Empty sets are never stored,
     * so a key with no facts and an absent key compare equal on both sides.
     */
    data class Relations(
        val personIds: Set<Long>,
        val forumIds: Set<Long>,
        val messageIds: Set<Long>,
        val persons: Map<Long, Person>,
        val knows: Map<Long, Set<Knows>>,
        val forums: Map<Long, Forum>,
        val members: Map<Long, Set<ForumFact.Member>>,
        val contains: Map<Long, Set<Long>>,
        val messages: Map<Long, Message>,
        val replies: Map<Long, Set<Long>>,
        val likes: Map<Long, Set<MessageFact.LikedBy>>,
        val authored: Map<Long, Set<Long>>,
    ) {
        /** Each relation by name, in a fixed order, for per-relation comparison. */
        fun byName(): List<Pair<String, Any>> = listOf(
            "personIds" to personIds,
            "forumIds" to forumIds,
            "messageIds" to messageIds,
            "persons" to persons,
            "knows" to knows,
            "forums" to forums,
            "members" to members,
            "contains" to contains,
            "messages" to messages,
            "replies" to replies,
            "likes" to likes,
            "authored" to authored,
        )
    }

    val persons = sortedMapOf<Long, Person>()
    val knows = sortedMapOf<Long, MutableSet<Knows>>()
    val forums = sortedMapOf<Long, Forum>()
    val members = sortedMapOf<Long, MutableSet<ForumFact.Member>>()
    val contains = sortedMapOf<Long, MutableSet<Long>>()
    val messages = sortedMapOf<Long, Message>()
    val replies = sortedMapOf<Long, MutableSet<Long>>()
    val likes = sortedMapOf<Long, MutableSet<MessageFact.LikedBy>>()
    val authored = sortedMapOf<Long, MutableSet<Long>>()

    private fun <V> MutableMap<Long, MutableSet<V>>.addTo(key: Long, value: V) {
        getOrPut(key) { linkedSetOf() } += value
    }

    // --- folds -------------------------------------------------------------

    /** Folds [slice] in `SocialLoader.load`'s order (99qcg-D4). */
    fun load(slice: StaticSlice) {
        slice.persons.forEach(::addPerson)
        slice.knows.forEach { addKnows(it.a, it.b, it.creationDate) }
        slice.forums.forEach(::addForum)
        slice.memberships.forEach { joinForum(it.personId, it.forumId, it.creationDate) }
        slice.messages.forEach { if (it.forumId != null) addPost(it) else addComment(it) }
        slice.likes.forEach { addLike(it.personId, it.messageId, it.creationDate) }
    }

    /** Folds one [event], mirroring `UpdateStream.apply`'s arm-to-method mapping (99qcg-D5). */
    fun apply(event: UpdateEvent) {
        when (event) {
            is IU1AddPerson -> addPerson(event.person)
            is IU2LikePost -> addLike(event.personId, event.postId, event.creationDate)
            is IU3LikeComment -> addLike(event.personId, event.commentId, event.creationDate)
            is IU4AddForum -> addForum(event.forum)
            is IU5AddMembership -> joinForum(event.personId, event.forumId, event.creationDate)
            is IU6AddPost -> addPost(event.message)
            is IU7AddComment -> addComment(event.message)
            is IU8AddFriendship -> addKnows(event.a, event.b, event.creationDate)
        }
    }

    private fun addPerson(p: Person) {
        persons[p.id] = p
    }

    private fun addKnows(a: Long, b: Long, date: Long) {
        knows.addTo(a, Knows(b, date))
        knows.addTo(b, Knows(a, date))
    }

    private fun addForum(f: Forum) {
        forums[f.id] = f
    }

    private fun joinForum(personId: Long, forumId: Long, date: Long) {
        members.addTo(forumId, ForumFact.Member(personId, date))
    }

    private fun addPost(m: Message) {
        messages[m.id] = m
        authored.addTo(m.creatorId, m.id)
        contains.addTo(requireNotNull(m.forumId), m.id)
    }

    private fun addComment(m: Message) {
        messages[m.id] = m
        authored.addTo(m.creatorId, m.id)
        replies.addTo(requireNotNull(m.replyOfId), m.id)
    }

    private fun addLike(personId: Long, messageId: Long, date: Long) {
        likes.addTo(messageId, MessageFact.LikedBy(personId, date))
    }

    // --- comparison --------------------------------------------------------

    /** This model's relations, in [Relations]' shape. */
    fun relations(): Relations = Relations(
        personIds = persons.keys.toSortedSet(),
        forumIds = forums.keys.toSortedSet(),
        messageIds = messages.keys.toSortedSet(),
        persons = persons.toSortedMap(),
        knows = knows.nonEmpty(),
        forums = forums.toSortedMap(),
        members = members.nonEmpty(),
        contains = contains.nonEmpty(),
        messages = messages.toSortedMap(),
        replies = replies.nonEmpty(),
        likes = likes.nonEmpty(),
        authored = authored.nonEmpty(),
    )

    /**
     * Asserts every relation of this model equals the one [observe] reads out
     * of [graph]; the failure names the relation and carries [label] (the
     * seed and event index, by the pipeline test's convention).
     */
    fun assertEquals(graph: SocialGraph, label: String) {
        val expected = relations().byName()
        val actual = observe(graph).byName()
        expected.zip(actual).forEach { (e, a) ->
            kAssertEquals(e.second, a.second, "$label relation=${e.first}: batch model != cells")
        }
    }

    companion object {
        private fun <V> Map<Long, Set<V>>.nonEmpty(): Map<Long, Set<V>> =
            filterValues { it.isNotEmpty() }.mapValues { it.value.toSet() }.toSortedMap()

        /**
         * Reads every [Relations] relation out of [graph]'s observed cells —
         * the incremental side of the comparison, and the snapshot a replay
         * is compared against ([SOC1-UPD-04]). A known entity whose cell has
         * no `Profile`/`Info`/`Body` fact, or more than one, is left out of
         * the entity map, so it shows up as a mismatch rather than a crash.
         */
        fun observe(graph: SocialGraph): Relations {
            val personIds = graph.personIds()
            val forumIds = graph.forumIds()
            val messageIds = graph.messageIds()
            val personFacts = personIds.associateWith { graph.personFacts(it) }
            val forumFacts = forumIds.associateWith { graph.forumFacts(it) }
            val messageFacts = messageIds.associateWith { graph.messageFacts(it) }
            return Relations(
                personIds = personIds,
                forumIds = forumIds,
                messageIds = messageIds,
                persons = personFacts
                    .mapNotNull { (id, f) -> f.filterIsInstance<PersonFact.Profile>().singleOrNull()?.let { id to it.person } }
                    .toMap().toSortedMap(),
                knows = personFacts.mapValues { it.value.filterIsInstance<Knows>().toSet() }.nonEmpty(),
                forums = forumFacts
                    .mapNotNull { (id, f) -> f.filterIsInstance<ForumFact.Info>().singleOrNull()?.let { id to it.forum } }
                    .toMap().toSortedMap(),
                members = forumFacts.mapValues { it.value.filterIsInstance<ForumFact.Member>().toSet() }.nonEmpty(),
                contains = forumFacts
                    .mapValues { f -> f.value.filterIsInstance<ForumFact.Contains>().map { it.messageId }.toSet() }
                    .nonEmpty(),
                messages = messageFacts
                    .mapNotNull { (id, f) -> f.filterIsInstance<MessageFact.Body>().singleOrNull()?.let { id to it.message } }
                    .toMap().toSortedMap(),
                replies = messageFacts
                    .mapValues { f -> f.value.filterIsInstance<MessageFact.Reply>().map { it.childId }.toSet() }
                    .nonEmpty(),
                likes = messageFacts.mapValues { it.value.filterIsInstance<MessageFact.LikedBy>().toSet() }.nonEmpty(),
                authored = personIds.associateWith { id -> graph.authored(id).map { it.id }.toSet() }.nonEmpty(),
            )
        }
    }
}
