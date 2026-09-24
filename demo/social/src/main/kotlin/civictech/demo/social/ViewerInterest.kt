/**
 * Follows-as-interest: a viewer's `knows` set IS its feed scope (SOC1, epic
 * `computenet-07k` §4 "Follows-as-interest", B12/B13; feature
 * `computenet-4q9is`, designs 4q9is-D1..D4). Spec:
 * `doc/spec/40-distribution/42-replication.md` §"Interest-scoped instance
 * sets", `[42-INT-01]`.
 *
 * **What the scope is.** One singleton range `Range(id, id + 1)` per distinct
 * `Knows.otherId` in the viewer's `snb-person` cell, sorted, as an
 * [Interest.Ranges] (07k-D2: `Range` is half-open). A viewer who knows nobody
 * has the scope [Interest.Empty] — explicitly, never `Ranges(emptyList())`
 * (whose meaning nothing here should depend on) and never [Interest.Total],
 * which would fan a feed out to every author in the graph (`[SOC1-INT-05]`,
 * the control-b anti-pattern `[SOC1-FEED-02]`).
 *
 * **What it costs.** [FeedSession] asks its [ScopeSource] at the start of
 * EVERY pull (4q9is-D6), so a derived scope is one bounded read walk of the
 * viewer's person cell per pull — one page for any viewer with fewer than
 * `pageLimit` person facts. That one read is what makes an added edge widen
 * the next pull and a removed edge narrow it without any event plumbing
 * (`[SOC1-INT-02/03]`). The read goes through [BoundedReader], never through
 * `SocialGraph`'s in-memory fold: the app-level fold is not the feed's read
 * path (flfkm-D8).
 *
 * **Why the declaration is on the person ref (4q9is-D3).** Each derivation is
 * recorded with `registry.setInterest(personRef, scope)`, `Empty` included, so
 * the registry reflects follows-as-interest and a test can read it back. The
 * viewer's `snb-person` ref, not its `snb-authored` ref: a viewer who has
 * posted nothing has no authored cell, and spawning one just to carry a
 * declaration would grow `authored.keys()`, which `[SOC1-SREAD-03]` asserts
 * against. `setInterest` only records (kernel `InstanceIndex`); nothing links
 * or spawns on it here.
 *
 * **Why a refusal is not `Empty` (4q9is-D4).** A person-cell read answering
 * [StateReadResult.Unavailable] (or `Unbounded`) on any page completes
 * [ViewerInterest.scopeOf] exceptionally with [ScopeUnavailable]. Answering
 * `Empty` instead would make "the scope could not be read" indistinguishable
 * from "this viewer knows nobody" and silently empty the board — the
 * silent-narrow twin of the silent-widen `[SOC1-INT-05]` guards against. The
 * pull fails; nothing is registered; the caller sees the named reason.
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.link.Interest
import java.util.concurrent.CompletableFuture

/** Where a [FeedSession] gets its scope, once per pull (4q9is-D1). */
fun interface ScopeSource {
    fun scopeOf(viewer: Long): CompletableFuture<Interest>

    companion object {
        /** A caller-supplied scope that never changes; completes immediately with [scope]. */
        fun fixed(scope: Interest.Ranges): ScopeSource = ScopeSource { CompletableFuture.completedFuture(scope) }
    }
}

/** The viewer's scope could not be read: [reason] is the person-cell read's refusal, verbatim (4q9is-D4). */
class ScopeUnavailable(val reason: StateReadResult.Reason) :
    RuntimeException("viewer scope unavailable: person-cell read refused ($reason)")

/**
 * The derived [ScopeSource] (4q9is-D2): the viewer's `knows` set, read through
 * [reader], declared on the viewer's `snb-person` ref in [registry].
 *
 * [locate] rather than the person family, so an unknown or unadmitted viewer
 * resolves to no ref and spawns nothing: its scope is [Interest.Empty] at zero
 * reads and no registration (rx8om-D5, `[SOC1-SREAD-03]`).
 */
class ViewerInterest(
    private val locate: EntityLocator,
    private val reader: BoundedReader,
    private val registry: LocationRegistry,
    private val pageLimit: Int = 200,
) : ScopeSource {
    init {
        require(pageLimit > 0) { "pageLimit must be positive, got $pageLimit" }
    }

    override fun scopeOf(viewer: Long): CompletableFuture<Interest> {
        val personRef = locate.person(viewer) ?: return CompletableFuture.completedFuture(Interest.Empty)
        return knowsOf(personRef).thenApply { ids ->
            val scope: Interest =
                if (ids.isEmpty()) Interest.Empty
                else Interest.Ranges(ids.sorted().map { Interest.Ranges.Range(it, it + 1) })
            registry.setInterest(personRef, scope)
            scope
        }
    }

    /**
     * One walk of [ref] with `since = null`, following `next` to exhaustion,
     * collecting the distinct `Knows.otherId` of present entries. Any refused
     * page fails the walk with [ScopeUnavailable].
     */
    private fun knowsOf(ref: CellRef): CompletableFuture<Set<Long>> {
        fun step(cursor: Cursor?, seen: MutableSet<Long>): CompletableFuture<Set<Long>> =
            reader.read(ref, StateRead(cursor = cursor, limit = pageLimit)).thenCompose { result ->
                when (result) {
                    is StateReadResult.Page -> {
                        for (entry in result.page.entries) {
                            if (entry !is SetCell.SetStateEntry<*> || !entry.present) continue
                            val knows = entry.element as? Knows ?: continue
                            seen += knows.otherId
                        }
                        val next = result.page.next
                        if (next != null) step(next, seen) else CompletableFuture.completedFuture<Set<Long>>(seen)
                    }

                    is StateReadResult.Unavailable ->
                        CompletableFuture.failedFuture(ScopeUnavailable(result.reason))

                    // Unreachable without allowWholeCopy, which is never passed; refused, never decoded.
                    is StateReadResult.Unbounded ->
                        CompletableFuture.failedFuture(ScopeUnavailable(StateReadResult.Reason.READ_FAILED))
                }
            }
        return step(null, LinkedHashSet())
    }
}
