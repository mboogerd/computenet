package civictech.cell.data.delta

import civictech.cell.ReBaselineNotice
import civictech.cell.Timestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Snapshot/restore preserves the negative knowledge in [TagState.deadSources]
 * (93 I-22 R5): a restored convergent consumer must keep rejecting stragglers
 * from a superseded source lane.
 */
class TagStateSnapshotTest {

    @Test
    fun `snapshot restores dead source fence and rejects a late straggler`() {
        val deadSource = UUID(0, 1)
        val freshSource = UUID(0, 2)
        val state = TagState<String>()
        val oldTag = Timestamp(deadSource, 1)
        val freshTag = Timestamp(freshSource, 1)

        state.apply(SetDelta(adds = mapOf("item" to setOf(oldTag))))
        state.applyReBaseline(
            SetDelta(adds = mapOf("item" to setOf(freshTag))),
            ReBaselineNotice(supersedes = setOf(deadSource), supersede = true),
        )

        val snapshot = state.snapshot()
        assertEquals(setOf(deadSource), (snapshot as Map<*, *>)["dead"])

        val restored = TagState<String>()
        restored.restore(snapshot)

        val straggler = Timestamp(deadSource, 2)
        val rejected = restored.apply(SetDelta(adds = mapOf("item" to setOf(straggler))))

        assertEquals(SetDelta<String>(), rejected)
        assertEquals(setOf(freshTag), restored.tags("item"))
    }

    @Test
    fun `legacy snapshot without dead entry restores with an empty fence`() {
        val deadSource = UUID(0, 3)
        val state = TagState<String>()
        state.applyReBaseline(
            SetDelta(),
            ReBaselineNotice(supersedes = setOf(deadSource), supersede = true),
        )

        // This is the bare live-map shape written before the additive "dead"
        // entry existed. It must restore with no dead-source knowledge.
        val legacySnapshot = hashMapOf<String, Set<Timestamp>>(
            "item" to setOf(Timestamp(deadSource, 1)),
        )
        state.restore(legacySnapshot)

        val admitted = state.apply(
            SetDelta(adds = mapOf("item" to setOf(Timestamp(deadSource, 2)))),
        )

        assertEquals(mapOf("item" to setOf(Timestamp(deadSource, 2))), admitted.adds)
    }
}
