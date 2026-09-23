/**
 * LDBC SNB interactive **complex reads** IC2, IC8 and IC3 (fixed two hops) for
 * `:demo:social` (SOC1, epic `computenet-07k` §2.3/§4; feature
 * `computenet-flfkm` design flfkm-D4..D7).
 *
 * Every read here goes through the [BoundedReader] seam against a cell
 * [EntityLocator] admits — never a scan over a family, a static set, or the
 * loaded dataset ([SOC1-CREAD-04]). This file names none of the write-side or
 * ingest-side types, and enumerates no family's key set directly
 * (`SocialComplexReadTest` greps for that).
 *
 * | query | cells touched | reads |
 * |-------|---------------|-------|
 * | IC2   | (served by [Feed.kt]'s `FeedSession.board`, not this file) | — |
 * | IC8   | the person's `snb-authored`, one `snb-message` per own message, one `snb-message` per reply | `1 + ownMessages + replies` |
 * | IC3   | the viewer's `snb-person`, one `snb-person` per friend, one `snb-authored` per candidate that has one | `1 + friends + candidatesWithAuthoredCell` |
 * | IC5   | dropped to finding `F-<next>` (`doc/demo-findings.md`) — forum membership lives only on the forum's cell | — |
 * | IC6   | dropped to finding `F-<next>` — no source writes `MessageFact.HasTag`/`ForumFact.HasTag` | — |
 * | IC12  | dropped to finding `F-<next>` — needs tag facts and an ordered top-K over counted rows | — |
 *
 * **Own page walk, deliberately duplicated from `ShortReads.kt`**: that file
 * is claimed by open sibling tasks in this feature, so [walk]/[membersOf] and
 * the `done`/`mapFound`/`composeFound` plumbing are copied here rather than
 * shared. A later task may extract a common walker once the siblings land.
 *
 * **Futures, never blocking.** Both queries chain their hops with
 * `thenCompose`; correctness first, fan-out is not required (flfkm-D5/D6).
 * A refusal on any hop ends the whole query as that [ReadOutcome.Refused],
 * with no partial answer.
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import java.util.concurrent.CompletableFuture

/** One row of IC3: a friend or friend-of-friend with country-window message counts. */
data class Ic3Row(val personId: Long, val countA: Int, val countB: Int)

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

/**
 * IC8 and IC3 (fixed two hops) against [locate]'s entities, served through
 * [reader]. Holds no host and no cell: everything it can reach is a ref the
 * locator admits.
 */
class ComplexReads(
    private val reader: BoundedReader,
    private val locate: EntityLocator,
) {

    /**
     * IC8: the person's most recent replies, newest first, ties by comment id
     * ascending (LDBC's tie rule for IC8, deliberately unlike IC2's
     * descending). `1 + ownMessages + replies` reads (flfkm-D5): the
     * `snb-authored` walk, one `snb-message` walk per own message (collecting
     * `Reply.childId`), then one `snb-message` walk per reply (its `Body`). A
     * child the locator no longer admits, or whose cell holds no `Body`, is
     * skipped, not an error.
     *
     * A known person who has authored nothing has no `snb-authored` cell at
     * all: that is `Found(emptyList())` at zero reads. An unknown person is
     * `Empty` at zero reads.
     */
    fun ic8(person: Long, limit: Int = 20): CompletableFuture<ReadOutcome<List<Message>>> {
        require(limit > 0) { "limit must be positive (was $limit)" }
        if (locate.person(person) == null) return done(ReadOutcome.Empty)
        val authoredRef = locate.authored(person) ?: return done(ReadOutcome.Found(emptyList()))
        return walk(authoredRef).composeFound { items ->
            val ownIds = items.filterIsInstance<Message>().map { it.id }.sorted()
            childIdsOf(ownIds).composeFound { childIds ->
                bodiesOf(childIds.sorted()).mapFound { messages ->
                    ReadOutcome.Found(
                        messages
                            .sortedWith(compareByDescending<Message> { it.creationDate }.thenBy { it.id })
                            .take(limit)
                    )
                }
            }
        }
    }

    /**
     * IC3 at fixed two hops: friends and friends-of-friends (never the
     * viewer) with at least one message in each of [countryA]/[countryB]
     * inside the half-open window `[from, to)`, counted and ranked by the
     * combined count, ties by person id ascending. `1 + friends +
     * candidatesWithAuthoredCell` reads (flfkm-D6): the viewer's `snb-person`
     * walk, one `snb-person` walk per friend (collecting `Knows.otherId` for
     * the friend-of-friend set), then one `snb-authored` walk per candidate
     * that has one. A friend the locator no longer admits contributes no
     * friend-of-friend, at no read; a candidate with no `snb-authored` cell
     * contributes no row, at no read.
     */
    fun ic3(
        person: Long,
        countryA: Long,
        countryB: Long,
        from: Long,
        to: Long,
        limit: Int = 20,
    ): CompletableFuture<ReadOutcome<List<Ic3Row>>> {
        require(countryA != countryB) { "countryA and countryB must differ (both $countryA)" }
        require(from <= to) { "from ($from) must be <= to ($to)" }
        require(limit > 0) { "limit must be positive (was $limit)" }
        val viewerRef = locate.person(person) ?: return done(ReadOutcome.Empty)
        return walk(viewerRef).composeFound { facts ->
            val friends = facts.filterIsInstance<Knows>().map { it.otherId }.toSet()
            knowsOf(friends.sorted()).composeFound { friendsOfFriends ->
                val candidates = ((friends + friendsOfFriends) - person).toSortedSet().toList()
                countsFor(candidates, countryA, countryB, from, to).mapFound { rows ->
                    ReadOutcome.Found(
                        rows
                            .filter { it.countA > 0 && it.countB > 0 }
                            .sortedWith(compareByDescending<Ic3Row> { it.countA + it.countB }.thenBy { it.personId })
                            .take(limit)
                    )
                }
            }
        }
    }

    // --- IC8 helpers ---------------------------------------------------------

    /** One `snb-message` walk per own message id, collecting `Reply.childId`. */
    private fun childIdsOf(ownIds: List<Long>): CompletableFuture<ReadOutcome<List<Long>>> {
        var chain: CompletableFuture<ReadOutcome<List<Long>>> = done(ReadOutcome.Found(emptyList()))
        for (id in ownIds) {
            chain = chain.composeFound { soFar ->
                val ref = locate.message(id)
                if (ref == null) {
                    done(ReadOutcome.Found(soFar))
                } else {
                    walk(ref).mapFound { facts ->
                        ReadOutcome.Found(soFar + facts.filterIsInstance<MessageFact.Reply>().map { it.childId })
                    }
                }
            }
        }
        return chain
    }

    /** One `snb-message` walk per admitted id, to its `Body`; ids with no live cell or no `Body` are skipped. */
    private fun bodiesOf(ids: List<Long>): CompletableFuture<ReadOutcome<List<Message>>> {
        var chain: CompletableFuture<ReadOutcome<List<Message>>> = done(ReadOutcome.Found(emptyList()))
        for (id in ids) {
            chain = chain.composeFound { soFar ->
                val ref = locate.message(id)
                if (ref == null) {
                    done(ReadOutcome.Found(soFar))
                } else {
                    walk(ref) { seen -> seen.any { it is MessageFact.Body } }.mapFound { facts ->
                        val body = facts.filterIsInstance<MessageFact.Body>().firstOrNull()
                        ReadOutcome.Found(if (body == null) soFar else soFar + body.message)
                    }
                }
            }
        }
        return chain
    }

    // --- IC3 helpers ---------------------------------------------------------

    /** One `snb-person` walk per admitted friend id, collecting `Knows.otherId`. */
    private fun knowsOf(friendIds: List<Long>): CompletableFuture<ReadOutcome<Set<Long>>> {
        var chain: CompletableFuture<ReadOutcome<Set<Long>>> = done(ReadOutcome.Found(emptySet()))
        for (id in friendIds) {
            chain = chain.composeFound { soFar ->
                val ref = locate.person(id)
                if (ref == null) {
                    done(ReadOutcome.Found(soFar))
                } else {
                    walk(ref).mapFound { facts ->
                        ReadOutcome.Found(soFar + facts.filterIsInstance<Knows>().map { it.otherId })
                    }
                }
            }
        }
        return chain
    }

    /** One `snb-authored` walk per candidate that has one, counting messages in the half-open `[from, to)` window. */
    private fun countsFor(
        candidates: List<Long>,
        countryA: Long,
        countryB: Long,
        from: Long,
        to: Long,
    ): CompletableFuture<ReadOutcome<List<Ic3Row>>> {
        var chain: CompletableFuture<ReadOutcome<List<Ic3Row>>> = done(ReadOutcome.Found(emptyList()))
        for (id in candidates) {
            chain = chain.composeFound { soFar ->
                val ref = locate.authored(id)
                if (ref == null) {
                    done(ReadOutcome.Found(soFar))
                } else {
                    walk(ref).mapFound { items ->
                        var countA = 0
                        var countB = 0
                        for (m in items.filterIsInstance<Message>()) {
                            if (m.creationDate in from until to) {
                                when (m.locationCountryId) {
                                    countryA -> countA++
                                    countryB -> countB++
                                }
                            }
                        }
                        ReadOutcome.Found(soFar + Ic3Row(id, countA, countB))
                    }
                }
            }
        }
        return chain
    }

    // --- the page walk (duplicated from ShortReads.kt; see file KDoc) --------

    /**
     * Pages the cell at [ref] from the start, one [BoundedReader.read] per
     * page, accumulating the present members of every page until [stop]
     * admits what has been seen or the cell reports no further page. Any
     * refused page ends the walk as that refusal — never a partial answer.
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

    /** The present elements of one page (see `ShortReads.membersOf` for why only `SetStateEntry` counts). */
    private fun membersOf(page: StatePage): List<Any> =
        page.entries
            .filterIsInstance<SetCell.SetStateEntry<*>>()
            .filter { it.present }
            .mapNotNull { it.element }
}
