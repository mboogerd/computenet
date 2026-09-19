/**
 * LDBC SNB interactive **short reads** IS1-IS7 over `:demo:social`'s keyed
 * families (SOC1, epic `computenet-07k` §2.3/§4; feature `computenet-rx8om`
 * design rx8om-D2..D7).
 *
 * Every answer comes from the ONE keyed cell that owns the named entity,
 * reached through the [BoundedReader] seam — never from a scan over a family,
 * a static set, or another entity's stream ([SOC1-SREAD-02]). The cost of each
 * read is therefore stated in *pages of the owning cell*, plus, for IS6/IS7, a
 * bounded number of further per-entity cells:
 *
 * | read | cells touched | pages |
 * |------|---------------|-------|
 * | IS1  | the person's `snb-person` | until `Profile` is seen |
 * | IS2  | the person's `snb-authored` | to exhaustion (see below) |
 * | IS3  | the person's `snb-person` | to exhaustion |
 * | IS4  | the message's `snb-message` | until `Body` is seen |
 * | IS5  | the message's `snb-message` | until `Body` is seen |
 * | IS6  | one `snb-message` per reply hop, up to the root post | reply depth + 1 |
 * | IS7  | the parent `snb-message`, one per reply child, one `snb-person` | 2 + replyCount |
 *
 * **One page is one [BoundedReader.read] call**, and a cell that fits one page
 * (every cell of the hand-built test graph) costs exactly one. A page is
 * `limit = 200` by default; nothing here sets `since` or `scope`.
 *
 * **Why IS2 walks rather than asking for ten rows** (rx8om-D2):
 * [StateRead.limit] is a page size in the cell's frozen enumeration order, not
 * a SQL `LIMIT` over an ordering — `StateRead(limit = 10)` would return the
 * first ten *enumerated* messages, which is a wrong answer, not a bounded one.
 * The primitive offers no ORDER BY either. So IS2 pages the author's ONE
 * `snb-authored` cell to exhaustion, then sorts `(creationDate desc, id desc)`
 * and takes ten, demo-side. The cost is O(that author's messages) inside one
 * cell; the missing primitive is recorded as the `[SOC1-FIND-03]` finding.
 *
 * **Ids, not joined entities** (rx8om-D6): IS3 answers [Knows] (friend id +
 * friendship date) and IS5 answers the creator's id, where SNB returns the
 * other person's name fields. Resolving those names would be one further
 * `snb-person` read per friend / per creator, which [SOC1-SREAD-02]'s
 * "exactly one read" for IS5 forbids. A caller that wants them composes
 * [is1] per id.
 *
 * **Futures, never blocking.** A page lands on a scheduler task; on a
 * simulated host that is the next `step()`/`runToIdle()`, so a blocking short
 * read would deadlock a simulated test. IS6 and IS7 chain their hops with
 * `thenCompose`; the caller (the HTTP layer) is the only place that blocks,
 * under its own bound.
 *
 * **Empty vs Refused.** An id no live entity owns is [ReadOutcome.Empty] at
 * *zero* reads — the [EntityLocator] answers null without spawning anything
 * ([SOC1-SREAD-03]). A host refusal is [ReadOutcome.Refused] carrying the
 * kernel's own reason, on any hop, with no partial answer and no substitution
 * ([SOC1-SREAD-04]).
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import java.util.concurrent.CompletableFuture

/**
 * What one short read answered: a value, nothing (no such live entity), or a
 * refusal the host named. Deliberately three arms and not a nullable value —
 * "the graph does not have it" and "this host will not tell you right now" are
 * different answers and the demo never collapses them ([SOC1-SREAD-04]).
 */
sealed interface ReadOutcome<out T> {
    data class Found<T>(val value: T) : ReadOutcome<T>
    data object Empty : ReadOutcome<Nothing>
    data class Refused(val reason: StateReadResult.Reason) : ReadOutcome<Nothing>
}

/**
 * Key -> [CellRef] for the three families a short read owns, **without
 * spawning** (rx8om-D5): null means "no live entity with that id", and asking
 * must leave every family's `keys()` untouched ([SOC1-SREAD-03]).
 */
interface EntityLocator {
    fun person(id: Long): CellRef?
    fun authored(id: Long): CellRef?
    fun message(id: Long): CellRef?
}

/**
 * The [EntityLocator] over a live [SocialGraph].
 *
 * `KeyedCells.refFor` is private, so `getOrSpawn(key).ref` is the *only*
 * public key->ref path there is (rx8om-D5). It is called here strictly behind
 * the admitted-id guard: for a key already in the family's `keys()` it returns
 * the live cell and mints nothing — neither a second spawn nor a second
 * durable key record — so a read of a known id spawns nothing, and an id that
 * is not known never reaches `getOrSpawn` at all.
 *
 * [SocialGraph.personIds]/[SocialGraph.messageIds] already subtract the ids
 * whose creating write failed, so an "unadmitted" id is not locatable either.
 * [authored] is guarded **twice** — a person with no messages has no
 * `snb-authored` cell, and spawning one would grow `authored.keys()`, which
 * [SOC1-SREAD-03] asserts against directly.
 */
class GraphLocator(
    private val graph: SocialGraph,
    private val families: SnbPipeline.Families,
) : EntityLocator {

    override fun person(id: Long): CellRef? =
        if (id in graph.personIds()) families.person.getOrSpawn(id).ref else null

    override fun authored(id: Long): CellRef? =
        if (id in graph.personIds() && id in families.authored.keys()) {
            families.authored.getOrSpawn(id).ref
        } else {
            null
        }

    override fun message(id: Long): CellRef? =
        if (id in graph.messageIds()) families.message.getOrSpawn(id).ref else null
}

/**
 * The demo's reading of a kernel read result (rx8om-D1): a page is the answer,
 * `Unavailable(r)` is that refusal verbatim, and an `Unbounded` whole copy —
 * unreachable without `allowWholeCopy`, which nothing here passes — is refused
 * defensively as `READ_FAILED` rather than decoded as if it were a page.
 */
internal fun StateReadResult.asOutcome(): ReadOutcome<StatePage> = when (this) {
    is StateReadResult.Page -> ReadOutcome.Found(page)
    is StateReadResult.Unavailable -> ReadOutcome.Refused(reason)
    is StateReadResult.Unbounded -> ReadOutcome.Refused(StateReadResult.Reason.READ_FAILED)
}

private fun <T> done(outcome: ReadOutcome<T>): CompletableFuture<ReadOutcome<T>> =
    CompletableFuture.completedFuture(outcome)

private fun <A, B> CompletableFuture<ReadOutcome<A>>.mapFound(
    f: (A) -> ReadOutcome<B>,
): CompletableFuture<ReadOutcome<B>> = thenApply { outcome ->
    val next: ReadOutcome<B> = when (outcome) {
        is ReadOutcome.Found -> f(outcome.value)
        ReadOutcome.Empty -> ReadOutcome.Empty
        is ReadOutcome.Refused -> outcome
    }
    next
}

private fun <A, B> CompletableFuture<ReadOutcome<A>>.composeFound(
    f: (A) -> CompletableFuture<ReadOutcome<B>>,
): CompletableFuture<ReadOutcome<B>> = thenCompose { outcome ->
    when (outcome) {
        is ReadOutcome.Found -> f(outcome.value)
        ReadOutcome.Empty -> done<B>(ReadOutcome.Empty)
        is ReadOutcome.Refused -> done<B>(outcome)
    }
}

/** One reply to a message, with the "does its author know the message's creator" bit IS7 asks for. */
data class Reply(val message: Message, val authorKnowsCreator: Boolean)

/**
 * IS1-IS7 against [locate]'s entities, served through [reader]. Holds no host
 * and no cell: everything it can reach is a ref the locator admits.
 */
class ShortReads(
    private val reader: BoundedReader,
    private val locate: EntityLocator,
) {

    // --- IS1-IS7 -----------------------------------------------------------

    /** IS1: the person's profile. One page of the person's cell, up to the one holding `Profile`. */
    fun is1(personId: Long): CompletableFuture<ReadOutcome<Person>> {
        val ref = locate.person(personId) ?: return done(ReadOutcome.Empty)
        return walk(ref) { seen -> seen.any { it is PersonFact.Profile } }.mapFound { facts ->
            val profile = facts.filterIsInstance<PersonFact.Profile>().firstOrNull()
            if (profile == null) ReadOutcome.Empty else ReadOutcome.Found(profile.person)
        }
    }

    /**
     * IS2: the person's ten most recent messages, newest first. Walks the
     * author's `snb-authored` cell to exhaustion and sorts demo-side — see this
     * file's KDoc for why `limit` cannot do it (rx8om-D2).
     *
     * A known person who has authored nothing has no `snb-authored` cell at
     * all: that is `Found(emptyList())` at zero reads, not `Empty`, which is
     * reserved for an unknown person.
     */
    fun is2(personId: Long): CompletableFuture<ReadOutcome<List<Message>>> {
        if (locate.person(personId) == null) return done(ReadOutcome.Empty)
        val ref = locate.authored(personId) ?: return done(ReadOutcome.Found(emptyList()))
        return walk(ref).mapFound { items ->
            ReadOutcome.Found(
                items.filterIsInstance<Message>()
                    .sortedWith(compareByDescending<Message> { it.creationDate }.thenByDescending { it.id })
                    .take(10)
            )
        }
    }

    /** IS3: the person's friends with friendship date, `(creationDate desc, otherId asc)`. */
    fun is3(personId: Long): CompletableFuture<ReadOutcome<List<Knows>>> {
        val ref = locate.person(personId) ?: return done(ReadOutcome.Empty)
        return walk(ref).mapFound { facts ->
            ReadOutcome.Found(
                facts.filterIsInstance<Knows>()
                    .sortedWith(compareByDescending<Knows> { it.creationDate }.thenBy { it.otherId })
            )
        }
    }

    /** IS4: the message itself (content, date, and the rest of its [Message] fields). */
    fun is4(messageId: Long): CompletableFuture<ReadOutcome<Message>> {
        val ref = locate.message(messageId) ?: return done(ReadOutcome.Empty)
        return bodyOf(ref)
    }

    /** IS5: the message's creator, as an id (rx8om-D6 — resolving the name is a second read). */
    fun is5(messageId: Long): CompletableFuture<ReadOutcome<Long>> {
        val ref = locate.message(messageId) ?: return done(ReadOutcome.Empty)
        return bodyOf(ref).mapFound { message -> ReadOutcome.Found(message.creatorId) }
    }

    /**
     * IS6: the forum of the message — the `forumId` of the root post the
     * message hangs under, found by following `replyOfId` one `snb-message`
     * read per hop. Cost is reply **depth** plus one, never graph size.
     *
     * **No cycle guard, by construction**: `SocialGraph.addComment` requires
     * the parent message to already exist before the child's key is minted, so
     * every `replyOfId` points strictly backwards in creation order and the
     * chain is acyclic. A hop onto a parent the locator no longer admits ends
     * the walk as [ReadOutcome.Empty] rather than looping.
     */
    fun is6(messageId: Long): CompletableFuture<ReadOutcome<Long>> {
        val ref = locate.message(messageId) ?: return done(ReadOutcome.Empty)
        return bodyOf(ref).composeFound { message ->
            val forumId = message.forumId
            val parentId = message.replyOfId
            when {
                forumId != null -> done(ReadOutcome.Found(forumId))
                parentId != null -> is6(parentId)
                else -> done(ReadOutcome.Empty)
            }
        }
    }

    /**
     * IS7: the message's replies, newest first, each with whether its author
     * knows the original message's creator.
     *
     * `2 + replyCount` reads (rx8om-D4), because `MessageFact.Reply` carries
     * only the child's id: the parent cell (its `Body` and its `Reply`
     * children), then one read per child cell for that child's `Body`, then one
     * read of the creator's `snb-person` cell for their `Knows` set. A child
     * the locator does not admit, or whose cell holds no `Body`, is skipped —
     * not an error, and not a refusal.
     */
    fun is7(messageId: Long): CompletableFuture<ReadOutcome<List<Reply>>> {
        val ref = locate.message(messageId) ?: return done(ReadOutcome.Empty)
        // to exhaustion: the parent's Body and ALL its Reply children live in
        // this one cell, so stopping at Body could drop replies.
        return walk(ref).composeFound { facts ->
            val body = facts.filterIsInstance<MessageFact.Body>().firstOrNull()
            if (body == null) {
                done<List<Reply>>(ReadOutcome.Empty)
            } else {
                val childIds = facts.filterIsInstance<MessageFact.Reply>().map { it.childId }.sorted()
                bodiesOf(childIds).composeFound { children ->
                    knownBy(body.message.creatorId).mapFound { creatorKnows ->
                        ReadOutcome.Found(
                            children
                                .map { child -> Reply(child, child.creatorId in creatorKnows) }
                                .sortedWith(
                                    compareByDescending<Reply> { it.message.creationDate }
                                        .thenByDescending { it.message.id }
                                )
                        )
                    }
                }
            }
        }
    }

    // --- the page walk ------------------------------------------------------

    /**
     * Pages the cell at [ref] from the start, one [BoundedReader.read] per
     * page, accumulating the present members of every page until [stop] admits
     * what has been seen or the cell reports no further page. Any refused page
     * ends the walk as that refusal — never a partial answer.
     */
    private fun walk(
        ref: CellRef,
        stop: (List<Any>) -> Boolean = { false },
    ): CompletableFuture<ReadOutcome<List<Any>>> {
        fun step(cursor: Cursor?, seen: MutableList<Any>): CompletableFuture<ReadOutcome<List<Any>>> =
            reader.read(ref, StateRead(cursor = cursor)).thenCompose { result ->
                when (val outcome = result.asOutcome()) {
                    is ReadOutcome.Found -> {
                        val page = outcome.value
                        seen.addAll(membersOf(page))
                        val next = page.next
                        if (next == null || stop(seen)) done<List<Any>>(ReadOutcome.Found(seen.toList()))
                        else step(next, seen)
                    }

                    ReadOutcome.Empty -> done<List<Any>>(ReadOutcome.Empty)
                    is ReadOutcome.Refused -> done<List<Any>>(outcome)
                }
            }
        return step(null, mutableListOf())
    }

    /**
     * The present elements of one page. Every family a short read touches is a
     * `SetCell`, whose entries are `SetStateEntry`; a tombstoned element is
     * carried in the page with `present = false` and is not a member. Anything
     * else on a page (an `ExclusiveEntry` descriptor) is not a fact of this
     * schema and is ignored.
     */
    private fun membersOf(page: StatePage): List<Any> =
        page.entries
            .filterIsInstance<SetCell.SetStateEntry<*>>()
            .filter { it.present }
            .mapNotNull { it.element }

    /** The message held by the `snb-message` cell at [ref], or [ReadOutcome.Empty] if it holds no `Body`. */
    private fun bodyOf(ref: CellRef): CompletableFuture<ReadOutcome<Message>> =
        walk(ref) { seen -> seen.any { it is MessageFact.Body } }.mapFound { facts ->
            val body = facts.filterIsInstance<MessageFact.Body>().firstOrNull()
            if (body == null) ReadOutcome.Empty else ReadOutcome.Found(body.message)
        }

    /** One read per admitted id, in order; ids with no live cell or no `Body` are skipped. */
    private fun bodiesOf(ids: List<Long>): CompletableFuture<ReadOutcome<List<Message>>> {
        var chain: CompletableFuture<ReadOutcome<List<Message>>> = done(ReadOutcome.Found(emptyList()))
        for (id in ids) {
            chain = chain.composeFound { soFar ->
                val ref = locate.message(id)
                if (ref == null) {
                    done(ReadOutcome.Found(soFar))
                } else {
                    bodyOf(ref).thenApply { outcome ->
                        val next: ReadOutcome<List<Message>> = when (outcome) {
                            is ReadOutcome.Found -> ReadOutcome.Found(soFar + outcome.value)
                            ReadOutcome.Empty -> ReadOutcome.Found(soFar)
                            is ReadOutcome.Refused -> outcome
                        }
                        next
                    }
                }
            }
        }
        return chain
    }

    /**
     * The ids [personId] knows — one walk of their `snb-person` cell. A person
     * the locator does not admit knows nobody, at zero reads: IS7's bit is then
     * `false` for every reply, which is the honest answer for a creator who is
     * no longer a live entity.
     */
    private fun knownBy(personId: Long): CompletableFuture<ReadOutcome<Set<Long>>> {
        val ref = locate.person(personId) ?: return done(ReadOutcome.Found(emptySet()))
        return walk(ref).mapFound { facts ->
            ReadOutcome.Found(facts.filterIsInstance<Knows>().map { it.otherId }.toSet())
        }
    }
}
