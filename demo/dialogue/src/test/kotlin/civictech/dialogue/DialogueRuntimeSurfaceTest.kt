package civictech.dialogue

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.host.SimulationController
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.segmentContentHash
import civictech.dialogue.mint.RelationMint
import civictech.dialogue.mint.claimKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DialogueRuntime]'s **read surface** — the additive seams F5's HTTP layer
 * consumes (task computenet-2aw.5.1; epic computenet-2aw §3.5
 * [AGO1-PROV-01]/[AGO1-PROV-02], §3.6 [AGO1-OBS-02]):
 *
 * - `onCredence`, forwarded verbatim into `AgoraService` (2aw.5-D10), which
 *   is how the surface learns a credence settled after a stance write.
 * - `claimProvenance` / `relationProvenance`, read from the two
 *   ProvenanceIndex `ObserveCell` sinks this runtime spawns (2aw.5-D9), and
 *   the durability half that goes with them: both sink names are in
 *   `SINK_NAMES`, so both refs are volatile and their non-`@Serializable`
 *   `MapDelta` payloads never reach the WAL.
 *
 * Deliberately **not** here: recovery order, BS-18 and BS-19 — those are
 * [DialogueRuntimeTest]'s, which this file does not touch. The worlds here
 * are two- and three-utterance cassettes, not that suite's 30-turn fixture.
 */
class DialogueRuntimeSurfaceTest {

    // ------------------------------------------------------------------
    // Fixture: three utterances, two claims, one relation
    // ------------------------------------------------------------------

    private val claimOneText = "Proposition one holds."
    private val claimTwoText = "Proposition two holds."

    private val keyOne = claimKey(claimOneText)
    private val keyTwo = claimKey(claimTwoText)
    private val relationKey = RelationMint.relationKey(keyOne, keyTwo, Polarity.ATTACK)

    private fun utteranceText(turn: Int) = "Turn $turn speaks."

    /**
     * u1 mints claim one with its author's stance; u2 re-asserts claim one
     * (so it has TWO justifying utterances) and mints claim two; u3 draws the
     * relation between them.
     */
    private val cassetteEntries: Map<String, List<ExtractedItem>> = buildMap {
        put(
            utteranceText(1),
            listOf(
                ExtractedClaim(text = claimOneText, speaker = "alice", utteranceId = "u1"),
                ExtractedStance(
                    claimText = claimOneText,
                    speaker = "alice",
                    value = 0.8,
                    utteranceId = "u1",
                ),
            ),
        )
        put(
            utteranceText(2),
            listOf(
                ExtractedClaim(text = claimOneText, speaker = "bob", utteranceId = "u2"),
                ExtractedClaim(text = claimTwoText, speaker = "bob", utteranceId = "u2"),
            ),
        )
        put(
            utteranceText(3),
            listOf(
                ExtractedRelation(
                    sourceText = claimOneText,
                    targetText = claimTwoText,
                    polarity = Polarity.ATTACK.name,
                    utteranceId = "u3",
                ),
            ),
        )
    }.mapKeys { (text, _) ->
        segmentContentHash(
            Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text),
        )
    }

    private fun cassette(): CassetteExtractor {
        val json = Json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
            cassetteEntries,
        )
        return CassetteExtractor.load(StringReader(json))
    }

    private fun utterance(turn: Int) = Utterance(
        id = "u$turn",
        turn = turn,
        speaker = if (turn == 1) "alice" else "bob",
        tsMillis = 1_000L * turn,
        text = utteranceText(turn),
    )

    private val transcript: List<Utterance> = (1..3).map { utterance(it) }

    // ------------------------------------------------------------------
    // World harness — [DialogueRuntimeTest]'s idiom, three utterances wide
    // ------------------------------------------------------------------

    private inner class World(
        dir: File? = null,
        onCredence: (CellRef, Double) -> Unit = { _, _ -> },
    ) {
        val controller = SimulationController(7L)
        val runtime = DialogueRuntime(
            extractor = cassette(),
            transcript = transcript,
            journalDir = dir,
            scheduler = controller.scheduler(),
            onCredence = onCredence,
        )

        init {
            runtime.recover()
            drain()
            runtime.completeRecovery()
        }

        fun drain(budget: Int = 1_000_000): Int {
            var steps = 0
            while (controller.step()) {
                check(++steps < budget) { "no quiescence within $budget steps" }
            }
            return steps
        }

        /** Admit [through] turns, drain, reconcile, drain again. */
        fun admit(through: Int): World = apply {
            runtime.source.replay(from = 1, to = through)
            drain()
            runtime.reconcile()
            drain()
        }
    }

    // ------------------------------------------------------------------
    // 2aw.5-D10 — onCredence is forwarded to AgoraService
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-OBS-02 - onCredence is forwarded to AgoraService and fires for the applied claim's ref`() {
        val moves = mutableListOf<Pair<CellRef, Double>>()
        val world = World(onCredence = { ref, credence -> moves += ref to credence })

        world.admit(through = 1)

        val claimRef = BindingTable.refFor(keyOne)
        assertTrue(
            moves.any { it.first == claimRef },
            "the credence hook fired for the applied claim $claimRef; saw ${moves.map { it.first }}",
        )
    }

    @Test
    fun `2aw_5-D10 - the default onCredence leaves the runtime exactly as it was`() {
        // The whole point of the default is that every pre-existing call site
        // still compiles and behaves identically: this world names no hook.
        val world = World()
        world.admit(through = 3)

        assertEquals(setOf(keyOne, keyTwo), world.runtime.bindings.boundClaims())
        assertEquals(setOf(relationKey), world.runtime.bindings.boundRelations())
    }

    // ------------------------------------------------------------------
    // 2aw.5-D9 / [AGO1-PROV-01] — the ProvenanceIndex read surface
    // ------------------------------------------------------------------

    @Test
    fun `AGO1-PROV-01 - claimProvenance returns every utterance that justifies the claim`() {
        val world = World().admit(through = 3)

        assertEquals(setOf("u1", "u2"), world.runtime.claimProvenance(keyOne))
        assertEquals(setOf("u2"), world.runtime.claimProvenance(keyTwo))
    }

    @Test
    fun `AGO1-PROV-01 - relationProvenance returns the utterance that drew the relation`() {
        val world = World().admit(through = 3)

        assertEquals(setOf("u3"), world.runtime.relationProvenance(relationKey))
    }

    @Test
    fun `AGO1-PROV-04 - a key the index has never seen reads as null, not as an empty set`() {
        val world = World().admit(through = 1)

        assertNull(world.runtime.claimProvenance(claimKey("Nobody ever said this.")))
        assertNull(
            world.runtime.relationProvenance(
                RelationMint.relationKey(keyTwo, keyOne, Polarity.SUPPORT),
            ),
        )
        // …while a key the index HAS seen is non-null, so the two answers are
        // genuinely distinguishable.
        assertNotNull(world.runtime.claimProvenance(keyOne))
    }

    @Test
    fun `AGO1-PROV-01 - retracting the only justifying utterance empties the claim's provenance`() {
        val world = World().admit(through = 1)
        assertEquals(setOf("u1"), world.runtime.claimProvenance(keyOne))

        world.runtime.reset()
        world.drain()
        world.runtime.reconcile()
        world.drain()

        val after = world.runtime.claimProvenance(keyOne)
        assertTrue(
            after == null || after.isEmpty(),
            "after retraction the claim has no justifying utterances, was $after",
        )
    }

    // ------------------------------------------------------------------
    // The durability half: both sinks are volatile (isDurable's KDoc)
    // ------------------------------------------------------------------

    @Test
    fun `2aw_5-D9 - a journalled world drives both provenance sinks without a journal encode failure`(
        @TempDir dir: File,
    ) {
        // The provenance sinks carry MapDelta<_, Set<Claim|RelationProvenanceEntry>>,
        // and those entry types have no polymorphic WireCodec registration. If
        // either sink name were missing from SINK_NAMES, isDurable() would call
        // it durable and the first frame it accepted would fail to encode —
        // this world would not reach its assertions at all.
        val world = World(dir = dir).admit(through = 3)

        assertEquals(setOf("u1", "u2"), world.runtime.claimProvenance(keyOne))
        assertEquals(setOf("u3"), world.runtime.relationProvenance(relationKey))
        assertTrue(File(dir, "host.journal").exists(), "the world really was journalled")
    }
}
