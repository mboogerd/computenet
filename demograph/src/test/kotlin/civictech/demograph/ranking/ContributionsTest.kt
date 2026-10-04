package civictech.demograph.ranking

import civictech.demograph.Aggregation
import civictech.demograph.Preference
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContributionsTest {

    @Test
    fun `pairwise preferences project to ordered signed contributions`() {
        val preference = PairwisePreference("alice", "x", "y")

        assertEquals(
            listOf(
                Contribution("x", "alice", "y", 1),
                Contribution("y", "alice", "x", -1),
            ),
            preference.contributions(),
        )
        assertTrue(preference is Preference)
        assertTrue(MeanOfSigns() is Aggregation)
    }
}
