package civictech.cell.data.delta

import civictech.cell.CellRef
import civictech.cell.Timestamp
import java.io.Serializable
import java.util.*

/**
 * Cell-owned output tags for non-monotone operators (M11.2, tag hygiene, 21):
 * an operator whose output membership can flip ON without a fresh input
 * add-tag (difference, semijoin — re-entry rides the *other* side's removal)
 * must mint a fresh tag per entry and delete exactly what it minted; reusing
 * a previously-deleted tag would leave the element dead forever under
 * tombstone-folding consumers.
 *
 * The source is DERIVED from the ref (SetCell M10.1 pattern) so a recovered
 * instance re-mints the exact tags the network observed; the counter is
 * snapshot state (M10.2) so a restored instance never reuses a spent tag.
 */
internal class MintedTags<E>(ref: CellRef, name: String) {
    private val source: UUID =
        UUID.nameUUIDFromBytes("$name:${ref.id}:${ref.instanceId}".toByteArray())
    private var counter = 0L
    private val advertised = mutableMapOf<E, Timestamp>()

    val isEmpty: Boolean get() = advertised.isEmpty()
    val entries: Map<E, Timestamp> get() = advertised
    operator fun contains(element: E): Boolean = element in advertised

    /** Fresh tag on entry; null if already advertised (idempotent). */
    fun enter(element: E): Timestamp? =
        if (element in advertised) null
        else Timestamp(source, ++counter).also { advertised[element] = it }

    /** The minted tag on exit; null if not advertised (idempotent). */
    fun exit(element: E): Timestamp? = advertised.remove(element)

    /** Current advertisements as a delta-from-empty — the catch-up emission (G-22). */
    fun asDelta(): SetDelta<E> = SetDelta(adds = advertised.mapValues { setOf(it.value) })

    fun snapshot(): Serializable = arrayListOf(HashMap(advertised), counter)

    @Suppress("UNCHECKED_CAST")
    fun restore(state: Serializable) {
        val (adv, count) = state as ArrayList<Serializable>
        advertised.clear()
        advertised.putAll(adv as Map<E, Timestamp>)
        counter = count as Long
    }
}
