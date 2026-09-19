/**
 * The single write/read ingress over [SnbPipeline]'s keyed families (SOC1,
 * epic `computenet-07k`), decided in feature `computenet-jo2jk` design
 * jo2jk-D5.
 *
 * **Existence.** A person/forum/message is "known" exactly when its id is in
 * the owning family's [civictech.cell.host.KeyedCells.keys]: keys are only
 * ever touched by this class's own creation methods ([addPerson], [addForum],
 * a post/comment minting a `snb-message` key), so `family.keys()` is exactly
 * the set of ids this graph has itself admitted. Every method that
 * *references* an id it did not itself just create validates ALL referenced
 * ids against `keys()` before touching any cell, and throws
 * [IllegalArgumentException] naming the kind and the id — before any
 * `getOrSpawn` or inlet call, so an unknown-id call leaves every family's
 * `keys()` unchanged ([SOC1-HTTP-04]'s pre-write validation, applied here at
 * the ingress rather than only at the HTTP layer added in F1's next task).
 * Re-adding an already-known entity is an idempotent re-add (an OR-set
 * `add`), never an error.
 *
 * **Writes** go through the routed, journaled inlet
 * (`host.lookup(TypedRef<SetApi<F>>(cell.ref))!!.inlet.call`, jo2jk-D1) —
 * never the cell instance directly — so every mutation this class makes is
 * write-ahead journaled the same way every other `--journal` demo's writes
 * are.
 *
 * **Reads** are one [civictech.cell.observe.ObservationSink] per spawned
 * keyed cell (jo2jk-D2): the sink is created the moment a cell is first
 * reached (in [personCell]/[forumCell]/[messageCell]/[authoredCell]), folding
 * that cell's outlet with [civictech.cell.observe.View.set] so
 * [personFacts]/[authored]/[forumFacts]/[messageFacts] read a consistent
 * snapshot (`sink.current()`) from any thread, never `SetCell.membership()`
 * directly. This is a demo-scale cost worth stating plainly: one
 * [civictech.cell.observe.ObserveCell] per keyed cell that has ever been
 * touched, not per family — an SNB dataset with many persons/forums/messages
 * spawns one extra observing cell for each. [onChange] registers a listener
 * that every sink, present and future, fires on a settled effective change,
 * mirroring the per-outlet `graph.onChange { broadcast() }` idiom the other
 * `--journal` demos use for their single outlet.
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.SetOps
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.ManagedHost
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.View
import civictech.cell.observe.observe
import java.util.SortedSet
import java.util.concurrent.CopyOnWriteArrayList

class SocialGraph(
    private val host: ManagedHost,
    private val graph: SnbPipeline.Graph,
) {
    private val personSinks = LinkedHashMap<Long, ObservationSink<Set<PersonFact>>>()
    private val forumSinks = LinkedHashMap<Long, ObservationSink<Set<ForumFact>>>()
    private val messageSinks = LinkedHashMap<Long, ObservationSink<Set<MessageFact>>>()
    private val authoredSinks = LinkedHashMap<Long, ObservationSink<Set<Message>>>()

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Registers [listener] to fire on every settled change of every sink, present and future. */
    fun onChange(listener: () -> Unit) {
        changeListeners += listener
    }

    private fun fireChange() {
        changeListeners.forEach { it() }
    }

    // --- cell access: getOrSpawn + register-once observe sink -------------

    private fun personCell(id: Long): CellRef {
        val ref = graph.families.person.getOrSpawn(id).ref
        personSinks.getOrPut(id) { host.observe(ref, View.set<PersonFact>()) { fireChange() } }
        return ref
    }

    private fun forumCell(id: Long): CellRef {
        val ref = graph.families.forum.getOrSpawn(id).ref
        forumSinks.getOrPut(id) { host.observe(ref, View.set<ForumFact>()) { fireChange() } }
        return ref
    }

    private fun messageCell(id: Long): CellRef {
        val ref = graph.families.message.getOrSpawn(id).ref
        messageSinks.getOrPut(id) { host.observe(ref, View.set<MessageFact>()) { fireChange() } }
        return ref
    }

    private fun authoredCell(id: Long): CellRef {
        val ref = graph.families.authored.getOrSpawn(id).ref
        authoredSinks.getOrPut(id) { host.observe(ref, View.set<Message>()) { fireChange() } }
        return ref
    }

    private fun requirePerson(id: Long) {
        if (id !in graph.families.person.keys()) throw IllegalArgumentException("unknown person $id")
    }

    private fun requireForum(id: Long) {
        if (id !in graph.families.forum.keys()) throw IllegalArgumentException("unknown forum $id")
    }

    private fun requireMessage(id: Long) {
        if (id !in graph.families.message.keys()) throw IllegalArgumentException("unknown message $id")
    }

    private inline fun <reified F : Any> writeApi(ref: CellRef): SetOps<F> =
        host.lookup(TypedRef<SetApi<F>>(ref))!!.inlet.call

    // --- writes -------------------------------------------------------------

    fun addPerson(p: Person) {
        val ref = personCell(p.id)
        writeApi<PersonFact>(ref).add(PersonFact.Profile(p))
    }

    /** Undirected: writes `Knows(b, date)` into [a]'s cell AND `Knows(a, date)` into [b]'s ([SOC1-SCHEMA-03]). */
    fun addKnows(a: Long, b: Long, date: Long) {
        requirePerson(a)
        requirePerson(b)
        require(a != b) { "a person cannot know themselves ($a)" }
        val refA = personCell(a)
        val refB = personCell(b)
        writeApi<PersonFact>(refA).add(Knows(b, date))
        writeApi<PersonFact>(refB).add(Knows(a, date))
    }

    /** Undirected removal, mirroring [addKnows]: removes both halves of the edge. */
    fun removeKnows(a: Long, b: Long, date: Long) {
        requirePerson(a)
        requirePerson(b)
        val refA = personCell(a)
        val refB = personCell(b)
        writeApi<PersonFact>(refA).remove(Knows(b, date))
        writeApi<PersonFact>(refB).remove(Knows(a, date))
    }

    fun addForum(f: Forum) {
        requirePerson(f.moderatorId)
        val ref = forumCell(f.id)
        writeApi<ForumFact>(ref).add(ForumFact.Info(f))
    }

    fun joinForum(personId: Long, forumId: Long, date: Long) {
        requirePerson(personId)
        requireForum(forumId)
        val ref = forumCell(forumId)
        writeApi<ForumFact>(ref).add(ForumFact.Member(personId, date))
    }

    /** `m.forumId` set, `m.replyOfId` null: writes the author's, the forum's and the message's cell. */
    fun addPost(m: Message) {
        val forumId = requireNotNull(m.forumId) { "post ${m.id} must set forumId" }
        require(m.replyOfId == null) { "post ${m.id} must not set replyOfId" }
        requirePerson(m.creatorId)
        requireForum(forumId)
        val authoredRef = authoredCell(m.creatorId)
        val messageRef = messageCell(m.id)
        val forumRef = forumCell(forumId)
        writeApi<Message>(authoredRef).add(m)
        writeApi<MessageFact>(messageRef).add(MessageFact.Body(m))
        writeApi<ForumFact>(forumRef).add(ForumFact.Contains(m.id))
    }

    /** `m.replyOfId` set: writes the author's and the message's cell, plus a `Reply` on the parent. */
    fun addComment(m: Message) {
        val replyOfId = requireNotNull(m.replyOfId) { "comment ${m.id} must set replyOfId" }
        requirePerson(m.creatorId)
        requireMessage(replyOfId)
        val authoredRef = authoredCell(m.creatorId)
        val messageRef = messageCell(m.id)
        val parentRef = messageCell(replyOfId)
        writeApi<Message>(authoredRef).add(m)
        writeApi<MessageFact>(messageRef).add(MessageFact.Body(m))
        writeApi<MessageFact>(parentRef).add(MessageFact.Reply(m.id))
    }

    fun addLike(l: Like) {
        requirePerson(l.personId)
        requireMessage(l.messageId)
        val ref = messageCell(l.messageId)
        writeApi<MessageFact>(ref).add(MessageFact.LikedBy(l.personId, l.creationDate))
    }

    fun addTag(t: Tag) {
        writeApi<Tag>(graph.statics.tags.ref).add(t)
    }

    fun addTagClass(tc: TagClass) {
        writeApi<TagClass>(graph.statics.tagClasses.ref).add(tc)
    }

    fun addPlace(place: Place) {
        writeApi<Place>(graph.statics.places.ref).add(place)
    }

    fun addOrganisation(o: Organisation) {
        writeApi<Organisation>(graph.statics.organisations.ref).add(o)
    }

    fun addPersonInterest(personId: Long, tagId: Long) {
        requirePerson(personId)
        val ref = personCell(personId)
        writeApi<PersonFact>(ref).add(PersonFact.HasInterest(tagId))
    }

    fun addStudyAt(personId: Long, organisationId: Long, classYear: Long) {
        requirePerson(personId)
        val ref = personCell(personId)
        writeApi<PersonFact>(ref).add(PersonFact.StudyAt(organisationId, classYear))
    }

    fun addWorkAt(personId: Long, organisationId: Long, workFrom: Long) {
        requirePerson(personId)
        val ref = personCell(personId)
        writeApi<PersonFact>(ref).add(PersonFact.WorkAt(organisationId, workFrom))
    }

    fun addMessageTag(messageId: Long, tagId: Long) {
        requireMessage(messageId)
        val ref = messageCell(messageId)
        writeApi<MessageFact>(ref).add(MessageFact.HasTag(tagId))
    }

    fun addForumTag(forumId: Long, tagId: Long) {
        requireForum(forumId)
        val ref = forumCell(forumId)
        writeApi<ForumFact>(ref).add(ForumFact.HasTag(tagId))
    }

    // --- reads: /state and tests read from here, never from the raw cells ---

    fun personIds(): SortedSet<Long> = graph.families.person.keys().toSortedSet()
    fun forumIds(): SortedSet<Long> = graph.families.forum.keys().toSortedSet()
    fun messageIds(): SortedSet<Long> = graph.families.message.keys().toSortedSet()

    fun personFacts(id: Long): Set<PersonFact> = personSinks[id]?.current() ?: emptySet()
    fun authored(id: Long): Set<Message> = authoredSinks[id]?.current() ?: emptySet()
    fun forumFacts(id: Long): Set<ForumFact> = forumSinks[id]?.current() ?: emptySet()
    fun messageFacts(id: Long): Set<MessageFact> = messageSinks[id]?.current() ?: emptySet()
}
