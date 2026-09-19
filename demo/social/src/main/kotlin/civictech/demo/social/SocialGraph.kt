/**
 * The single write/read ingress over [SnbPipeline]'s keyed families (SOC1,
 * epic `computenet-07k`), decided in feature `computenet-jo2jk` design
 * jo2jk-D5.
 *
 * **Existence.** A person/forum/message is "known" exactly when its id is in
 * the owning family's [civictech.cell.host.KeyedCells.keys] **and its
 * creating write actually succeeded**: keys are only ever touched by this
 * class's own creation methods ([addPerson], [addForum],
 * a post/comment minting a `snb-message` key), so `family.keys()` is exactly
 * the set of ids this graph has itself *attempted*. Every method that
 * *references* an id it did not itself just create validates ALL referenced
 * ids against `keys()` before touching any cell, and throws
 * [IllegalArgumentException] naming the kind and the id — before any
 * `getOrSpawn` or inlet call, so an unknown-id call leaves every family's
 * `keys()` unchanged ([SOC1-HTTP-04]'s pre-write validation, applied here at
 * the ingress rather than only at the HTTP layer added in F1's next task).
 * Re-adding an already-known entity is an idempotent re-add (an OR-set
 * `add`), never an error.
 *
 * **The "and its creating write succeeded" half** (`computenet-5ab6f`) is not
 * decoration: [civictech.cell.host.KeyedCells.getOrSpawn] mints the key —
 * and fsyncs it into the family's durable `keys` log — *before* the inlet
 * call, and that call can still throw (a journal that refuses the append, a
 * payload the host WAL cannot encode). [KeyedCells] has no un-mint, so the
 * minted key cannot be withdrawn; what this class does instead is refuse to
 * *report* an id whose creating write failed and which has never since had a
 * successful one ([creating]). Without it a rejected `/op` still changed
 * `/state` — two failed `action=person` posts left two persons with
 * `"name":""` for ever — which is a direct violation of [SOC1-HTTP-04]
 * ("...the response SHALL be 400 and /state SHALL be byte-identical before
 * and after"). A later successful create for the same id clears the
 * suppression.
 *
 * **The durable half is closed by [suppressUnwrittenKeys]** (v10ou-D6, closes
 * `computenet-2v3e4`): a recovering app (F7, `computenet-v10ou`) pre-spawns
 * every durably-known key ([spawnKnown]) into an empty cell that replay may or
 * may not ever touch, so once the host has drained, [SocialRecovery.complete]
 * calls [suppressUnwrittenKeys] to re-derive the same suppression from what
 * actually replayed. **The remaining limit:** the `keys` line itself stays on
 * disk forever — [KeyedCells] has no un-mint, and rewriting or compacting that
 * log is this feature's non-goal — so the suppression is recomputed, not
 * removed, on every restart.
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
import civictech.cell.host.KeyedCells
import civictech.cell.host.ManagedHost
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.View
import civictech.cell.observe.observe
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SocialGraph(
    private val host: ManagedHost,
    private val graph: SnbPipeline.Graph,
) {
    /**
     * Written by `getOrPut` in [personCell]/[forumCell]/[messageCell]/[authoredCell],
     * which only ever run on DemoShell's single HTTP dispatcher thread
     * (`server.executor = null`, `demo/shell/.../DemoShell.kt:99`, so `/op`,
     * `/state` and `/events` are serialized against each other). Read by
     * [personFacts]/[authored]/[forumFacts]/[messageFacts], which `/state`
     * calls from that same HTTP thread, but ALSO from `graph.onChange {
     * broadcast() }` (`SocialApp.kt`), which an [ObservationSink] fires from
     * its own dedicated single-thread listener executor — "never the host
     * thread" (`kernel/.../observe/Observe.kt:138-152`). So a `getOrPut`
     * insert on the HTTP thread can race a `get` from a sink's listener
     * thread; a plain `HashMap`/`LinkedHashMap` gives no safe-publication
     * guarantee across that race (`computenet-6x2d5`). `ConcurrentHashMap`
     * closes it: creation stays effectively single-threaded (only `/op`, on
     * the HTTP thread, ever calls `getOrPut`), so only the read side needed
     * the safe publication a `ConcurrentHashMap` gives; iteration order is
     * never relied on here (`/state` sorts by [personIds]/[forumIds]/
     * [messageIds], not by these maps).
     *
     * No deterministic test exercises the race itself: the hazard is a timing
     * window between an insert and a concurrent read racing the same key, and
     * the two candidate reproductions are both impractical here — a stress
     * loop is inherently non-deterministic (the failure is a torn/missing read
     * under resize, not something a fixed schedule can force), and forcing the
     * interleaving would mean whitebox-instrumenting `ObservationSink`'s
     * private dispatcher, which is kernel-internal and not a seam this class
     * owns. `SocialServerTest`/`SocialSchemaTest` cannot observe it either:
     * they poll `/state` on the HTTP thread via `HttpProbe.await`, which never
     * touches the sink-dispatcher thread.
     */
    private val personSinks = ConcurrentHashMap<Long, ObservationSink<Set<PersonFact>>>()
    private val forumSinks = ConcurrentHashMap<Long, ObservationSink<Set<ForumFact>>>()
    private val messageSinks = ConcurrentHashMap<Long, ObservationSink<Set<MessageFact>>>()
    private val authoredSinks = ConcurrentHashMap<Long, ObservationSink<Set<Message>>>()

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Set by the first [onChange]; until then no sink carries a listener
     * (computenet-v10ou.1). A listener is not free: each [ObservationSink]
     * with one owns a dedicated dispatcher thread, minted on its first fire
     * and never released (`kernel/.../observe/Observe.kt`, "The dispatcher is
     * minted lazily"). Registering `{ fireChange() }` on every sink
     * unconditionally cost one idle thread per keyed cell of every graph ever
     * built — also for graphs nobody listens to (every sim-scheduler test
     * graph) — and a test JVM building a handful of seed-42 graphs hit the
     * per-process native-thread ceiling. [SocialApp] registers its broadcast
     * only in `start()`, so an unstarted app now owns no sink threads.
     */
    private val listening = AtomicBoolean(false)

    /**
     * Per family: ids whose creating write threw *after*
     * [civictech.cell.host.KeyedCells.getOrSpawn] had already minted their key,
     * and which have had no successful write since (`computenet-5ab6f`; see
     * this class's KDoc, "Existence"). Subtracted from
     * [personIds]/[forumIds]/[messageIds] and from `require*`, so such an id is
     * neither reported by `/state` nor referenceable by a later op.
     *
     * Concurrent sets, like the sink maps above (`computenet-6x2d5`): these
     * are written by the op thread and read by [personIds] and friends, which
     * `/state` reaches from the SSE broadcast thread.
     */
    private val unadmittedPersons = ConcurrentHashMap.newKeySet<Long>()
    private val unadmittedForums = ConcurrentHashMap.newKeySet<Long>()
    private val unadmittedMessages = ConcurrentHashMap.newKeySet<Long>()

    /** Registers [listener] to fire on every settled change of every sink, present and future. */
    fun onChange(listener: () -> Unit) {
        changeListeners += listener
        // Flag BEFORE iterating: a sink inserted concurrently is either seen by
        // this iteration or sees the flag in [attach] (possibly both — a
        // doubly-attached sink fires fireChange twice, which is harmless).
        if (listening.compareAndSet(false, true)) {
            (personSinks.values + forumSinks.values + messageSinks.values + authoredSinks.values)
                .forEach { it.onChange { fireChange() } }
        }
    }

    /** Gives a newly created [sink] the change listener once any [onChange] exists. */
    private fun <S> attach(sink: ObservationSink<S>): ObservationSink<S> =
        sink.also { if (listening.get()) it.onChange { fireChange() } }

    private fun fireChange() {
        changeListeners.forEach { it() }
    }

    // --- cell access: getOrSpawn + register-once observe sink -------------

    private fun personCell(id: Long): CellRef {
        val ref = graph.families.person.getOrSpawn(id).ref
        if (!personSinks.containsKey(id)) personSinks.getOrPut(id) { host.observe(ref, View.set<PersonFact>()) }.let(::attach)
        return ref
    }

    private fun forumCell(id: Long): CellRef {
        val ref = graph.families.forum.getOrSpawn(id).ref
        if (!forumSinks.containsKey(id)) forumSinks.getOrPut(id) { host.observe(ref, View.set<ForumFact>()) }.let(::attach)
        return ref
    }

    private fun messageCell(id: Long): CellRef {
        val ref = graph.families.message.getOrSpawn(id).ref
        if (!messageSinks.containsKey(id)) messageSinks.getOrPut(id) { host.observe(ref, View.set<MessageFact>()) }.let(::attach)
        return ref
    }

    private fun authoredCell(id: Long): CellRef {
        val ref = graph.families.authored.getOrSpawn(id).ref
        if (!authoredSinks.containsKey(id)) authoredSinks.getOrPut(id) { host.observe(ref, View.set<Message>()) }.let(::attach)
        return ref
    }

    /**
     * Spawns every durably-known key of all four families THROUGH this class
     * (v10ou-D2): [personCell]/[forumCell]/[messageCell]/[authoredCell] for
     * each id in the family's `keys()`, so each cell's observe sink exists
     * before [SocialRecovery.stage] replays the host WAL into it. A bare
     * `families.x.getOrSpawn(id)` would spawn the cell but register no sink,
     * and every recovered cell would read as empty facts. Spawning a known key
     * appends nothing to its `keys` log. Writes nothing to any cell.
     */
    fun spawnKnown() {
        graph.families.person.keys().forEach { personCell(it) }
        graph.families.forum.keys().forEach { forumCell(it) }
        graph.families.message.keys().forEach { messageCell(it) }
        graph.families.authored.keys().forEach { authoredCell(it) }
    }

    /**
     * Re-derives the [unadmittedPersons]/[unadmittedForums]/[unadmittedMessages]
     * suppression after a restart (v10ou-D6, closes the durable half of
     * `computenet-2v3e4`): a key in a family's [KeyedCells.keys] whose cell
     * holds no facts once the host has drained replay is exactly a ghost — its
     * creating write minted the key and then failed, in some earlier process,
     * before anything was ever journaled for it — so it is added to the
     * unadmitted set the same way a live rejected write is ([creating]).
     *
     * **Valid only after the host has drained** every staged frame — the
     * facts in [personFacts]/[forumFacts]/[messageFacts] are meaningless
     * before then (see [SocialRecovery]'s KDoc). The only caller is
     * [SocialRecovery.complete].
     *
     * The authored family has no unadmitted set and is not enumerated by
     * `/state` ([SocialGraph]'s own KDoc, "Existence"), so it is left alone
     * here. A key already admitted (its facts are non-empty) is untouched,
     * and [creating] still clears a previously-suppressed id on its next
     * successful write, exactly as before a restart.
     */
    fun suppressUnwrittenKeys() {
        graph.families.person.keys().forEach { if (personFacts(it).isEmpty()) unadmittedPersons.add(it) }
        graph.families.forum.keys().forEach { if (forumFacts(it).isEmpty()) unadmittedForums.add(it) }
        graph.families.message.keys().forEach { if (messageFacts(it).isEmpty()) unadmittedMessages.add(it) }
    }

    private fun requirePerson(id: Long) {
        if (id !in personIds()) throw IllegalArgumentException("unknown person $id")
    }

    private fun requireForum(id: Long) {
        if (id !in forumIds()) throw IllegalArgumentException("unknown forum $id")
    }

    private fun requireMessage(id: Long) {
        if (id !in messageIds()) throw IllegalArgumentException("unknown message $id")
    }

    /**
     * Runs [write] as the creating write for [id]'s cell in [family]
     * (`computenet-5ab6f`). On success [id] is admitted — and any earlier
     * suppression of it cleared. On failure, when this call is what first
     * minted the key, [id] is recorded in [unadmitted] so the rejected op
     * leaves `/state` byte-identical; the failure is rethrown either way, so
     * the caller still reports it (a 400 at the HTTP layer).
     *
     * A failure on an id that already existed leaves the suppression set
     * alone: a later op that fails against an established entity must not make
     * that entity disappear.
     */
    private fun <T> creating(
        family: KeyedCells<Long>,
        unadmitted: MutableSet<Long>,
        id: Long,
        write: () -> T,
    ): T {
        val fresh = id !in family.keys()
        return try {
            write().also { unadmitted.remove(id) }
        } catch (failure: Throwable) {
            if (fresh) unadmitted.add(id)
            throw failure
        }
    }

    private inline fun <reified F : Any> writeApi(ref: CellRef): SetOps<F> =
        host.lookup(TypedRef<SetApi<F>>(ref))!!.inlet.call

    // --- writes -------------------------------------------------------------

    fun addPerson(p: Person) = creating(graph.families.person, unadmittedPersons, p.id) {
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
        creating(graph.families.forum, unadmittedForums, f.id) {
            val ref = forumCell(f.id)
            writeApi<ForumFact>(ref).add(ForumFact.Info(f))
        }
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
        creating(graph.families.message, unadmittedMessages, m.id) {
            val authoredRef = authoredCell(m.creatorId)
            val messageRef = messageCell(m.id)
            val forumRef = forumCell(forumId)
            writeApi<Message>(authoredRef).add(m)
            writeApi<MessageFact>(messageRef).add(MessageFact.Body(m))
            writeApi<ForumFact>(forumRef).add(ForumFact.Contains(m.id))
        }
    }

    /** `m.replyOfId` set: writes the author's and the message's cell, plus a `Reply` on the parent. */
    fun addComment(m: Message) {
        val replyOfId = requireNotNull(m.replyOfId) { "comment ${m.id} must set replyOfId" }
        requirePerson(m.creatorId)
        requireMessage(replyOfId)
        creating(graph.families.message, unadmittedMessages, m.id) {
            val authoredRef = authoredCell(m.creatorId)
            val messageRef = messageCell(m.id)
            val parentRef = messageCell(replyOfId)
            writeApi<Message>(authoredRef).add(m)
            writeApi<MessageFact>(messageRef).add(MessageFact.Body(m))
            writeApi<MessageFact>(parentRef).add(MessageFact.Reply(m.id))
        }
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

    fun personIds(): SortedSet<Long> = (graph.families.person.keys() - unadmittedPersons).toSortedSet()
    fun forumIds(): SortedSet<Long> = (graph.families.forum.keys() - unadmittedForums).toSortedSet()
    fun messageIds(): SortedSet<Long> = (graph.families.message.keys() - unadmittedMessages).toSortedSet()

    fun personFacts(id: Long): Set<PersonFact> = personSinks[id]?.current() ?: emptySet()
    fun authored(id: Long): Set<Message> = authoredSinks[id]?.current() ?: emptySet()
    fun forumFacts(id: Long): Set<ForumFact> = forumSinks[id]?.current() ?: emptySet()
    fun messageFacts(id: Long): Set<MessageFact> = messageSinks[id]?.current() ?: emptySet()
}
