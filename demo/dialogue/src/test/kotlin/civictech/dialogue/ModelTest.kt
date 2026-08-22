package civictech.dialogue

import civictech.agora.cell.Polarity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test for `:demo:dialogue`: the module compiles, its §2.3 data types
 * round-trip through kotlinx JSON, and `CanonicalRelation.polarity` is
 * agora's `Polarity` — not a locally-minted enum.
 */
class ModelTest {

    private val json = Json

    @Test
    fun `Utterance round-trips through kotlinx JSON`() {
        val u = Utterance(id = "u1", turn = 1, speaker = "alice", tsMillis = 1000L, text = "hello")
        val encoded = json.encodeToString(u)
        val decoded = json.decodeFromString<Utterance>(encoded)
        assertEquals(u, decoded)
    }

    @Test
    fun `Segment round-trips through kotlinx JSON`() {
        val s = Segment(id = "s1", utteranceId = "u1", ordinal = 0, speaker = "alice", text = "hello")
        val encoded = json.encodeToString(s)
        val decoded = json.decodeFromString<Segment>(encoded)
        assertEquals(s, decoded)
    }

    @Test
    fun `CanonicalClaim round-trips through kotlinx JSON`() {
        val c = CanonicalClaim(
            key = ClaimKey("c1"),
            text = "the sky is blue",
            fromUtterances = setOf("u1", "u2"),
        )
        val encoded = json.encodeToString(c)
        val decoded = json.decodeFromString<CanonicalClaim>(encoded)
        assertEquals(c, decoded)
    }

    @Test
    fun `CanonicalRelation round-trips and reuses agora's Polarity`() {
        val r = CanonicalRelation(
            key = RelationKey("r1"),
            source = ClaimKey("c1"),
            target = ClaimKey("c2"),
            polarity = Polarity.ATTACK,
            fromUtterances = setOf("u3"),
        )
        val encoded = json.encodeToString(r)
        val decoded = json.decodeFromString<CanonicalRelation>(encoded)
        assertEquals(r, decoded)
        // Type-level assertion: this only compiles because CanonicalRelation.polarity
        // is civictech.agora.cell.Polarity, not a type local to this module.
        val polarity: Polarity = decoded.polarity
        assertEquals(Polarity.ATTACK, polarity)
    }

    @Test
    fun `ProjectedStance round-trips through kotlinx JSON, including null value`() {
        val withValue = ProjectedStance(claim = ClaimKey("c1"), speaker = "alice", value = 0.5)
        val withNull = ProjectedStance(claim = ClaimKey("c1"), speaker = "bob", value = null)

        assertEquals(withValue, json.decodeFromString<ProjectedStance>(json.encodeToString(withValue)))
        assertEquals(withNull, json.decodeFromString<ProjectedStance>(json.encodeToString(withNull)))
    }
}
