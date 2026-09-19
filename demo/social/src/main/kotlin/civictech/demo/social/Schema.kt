/**
 * SNB (LDBC Social Network Benchmark) payload types for `:demo:social` (SOC1, epic
 * `computenet-07k`), decided in feature `computenet-jo2jk` design jo2jk-D3.
 *
 * These types name the SNB entities they stand for:
 * - [Person] — SNB `Person`.
 * - [Knows] — one half of an SNB `Knows` edge, stored in the *other* endpoint's cell
 *   ([SOC1-SCHEMA-03], [SOC1-SCHEMA-04]); also a [PersonFact] arm.
 * - [Forum] — SNB `Forum`.
 * - [Message] — SNB `Post`/`Comment`, distinguished by `forumId` (post) vs. `replyOfId`
 *   (comment).
 * - [Like] — an SNB `Likes` ingress record (person likes message).
 * - [Tag] / [TagClass] — SNB `Tag` / `TagClass`.
 * - [Place] / [Organisation] — SNB `Place` / `Organisation`.
 *
 * Per 07k-D1, there is **no global message table**: a message's [Message] body is held
 * twice — once in the author's `snb-authored` cell (via [SocialGraph.addPost]/
 * [SocialGraph.addComment]) and once in the `snb-message` cell as a [MessageFact.Body] —
 * so IS4-IS7-shaped reads stay one keyed read each ([SOC1-FEED-02]).
 *
 * All ids are SNB `Long`s, preserved verbatim and never renumbered
 * ([SOC1-SCHEMA-02]). Every type here is `java.io.Serializable`
 * ([SOC1-SCHEMA-01]) — written out in full below, because these types also
 * carry kotlinx's same-named `@Serializable` annotation and the two must not
 * be confused for each other.
 *
 * **Both serializations are load-bearing, for different readers**
 * (`computenet-5ab6f`):
 * - `java.io.Serializable` is what [SOC1-SCHEMA-01] demands and what
 *   `SocialSchemaTest`'s `ObjectOutputStream` round-trip pins.
 * - `@kotlinx.serialization.Serializable` is what the **host write-ahead
 *   journal** needs: `ManagedHost` encodes every accepted invocation through
 *   `WireCodec`'s polymorphic `Any` scope before staging it, so under
 *   `--journal` an unregistered payload type makes every `/op` fail with
 *   `Serializer for subclass '<name>' is not found in the polymorphic scope
 *   of 'Any'`. The registration itself is [SocialWireSerializers]; the
 *   annotation here is what gives it a serializer to register.
 */
package civictech.demo.social

import kotlinx.serialization.Serializable

@Serializable
data class Person(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val gender: String = "",
    val birthday: Long = 0L,
    val creationDate: Long = 0L,
    val locationIp: String = "",
    val browserUsed: String = "",
    val placeId: Long? = null,
) : java.io.Serializable

/** One direction of an SNB `Knows` edge, stored in the OTHER endpoint's `snb-person` cell. */
@Serializable
data class Knows(val otherId: Long, val creationDate: Long) : PersonFact

@Serializable
data class Forum(
    val id: Long,
    val title: String,
    val moderatorId: Long,
    val creationDate: Long = 0L,
) : java.io.Serializable

/** `forumId` set and `replyOfId` null is a post; `replyOfId` set is a comment. */
@Serializable
data class Message(
    val id: Long,
    val creatorId: Long,
    val creationDate: Long,
    val content: String,
    val forumId: Long? = null,
    val replyOfId: Long? = null,
) : java.io.Serializable

/** Ingress record: person [personId] likes message [messageId]. */
@Serializable
data class Like(val personId: Long, val messageId: Long, val creationDate: Long) : java.io.Serializable

@Serializable
data class Tag(val id: Long, val name: String, val tagClassId: Long) : java.io.Serializable

@Serializable
data class TagClass(val id: Long, val name: String, val parentId: Long?) : java.io.Serializable

@Serializable
data class Place(val id: Long, val name: String, val type: String, val partOfId: Long?) : java.io.Serializable

@Serializable
data class Organisation(
    val id: Long,
    val name: String,
    val type: String,
    val placeId: Long,
) : java.io.Serializable

/** Facts held in a person's `snb-person` cell. */
sealed interface PersonFact : java.io.Serializable {
    @Serializable
    data class Profile(val person: Person) : PersonFact
    // Knows (above) is also a PersonFact arm.
    @Serializable
    data class StudyAt(val organisationId: Long, val classYear: Long) : PersonFact
    @Serializable
    data class WorkAt(val organisationId: Long, val workFrom: Long) : PersonFact
    @Serializable
    data class HasInterest(val tagId: Long) : PersonFact
}

/** Facts held in a forum's `snb-forum` cell. */
sealed interface ForumFact : java.io.Serializable {
    @Serializable
    data class Info(val forum: Forum) : ForumFact
    @Serializable
    data class Member(val personId: Long, val joinDate: Long) : ForumFact
    @Serializable
    data class Contains(val messageId: Long) : ForumFact
    @Serializable
    data class HasTag(val tagId: Long) : ForumFact
}

/** Facts held in a message's `snb-message` cell. */
sealed interface MessageFact : java.io.Serializable {
    @Serializable
    data class Body(val message: Message) : MessageFact
    @Serializable
    data class Reply(val childId: Long) : MessageFact
    @Serializable
    data class LikedBy(val personId: Long, val creationDate: Long) : MessageFact
    @Serializable
    data class HasTag(val tagId: Long) : MessageFact
}
