package civictech.deliberate

import civictech.agora.cell.Polarity
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real Jev and CLI calls (SPEC §9.2). Runs only with `DELIBERATE_LIVE=1`
 * (and `TYPESAFE_API_KEY` set); skipped otherwise so the default build stays offline.
 */
class LiveSmokeTest {

    private val question = "Should cities make public transport free?"
    private val claim = "Free public transport reduces car traffic in city centres."
    private val ctx = ClaimContext(
        question, listOf(question), claim,
        pros = listOf("Removing fares makes buses and trams more attractive than driving."),
        cons = listOf("Most new riders on free transit previously walked or cycled rather than drove."),
    )

    private companion object {
        const val PARKS_A = "Freeing city centres from private cars creates space for parks, cafes, and pedestrian plazas " +
            "that boost local business revenue and quality of life."
        const val PARKS_B = "Banning private cars from city centres frees scarce street and parking space for parks, public " +
            "squares, and other amenities that serve more people."
    }

    private fun live() = assumeTrue(System.getenv("DELIBERATE_LIVE") == "1", "set DELIBERATE_LIVE=1 to run live smoke calls")

    private fun unit(x: Double) = assertTrue(x in 0.0..1.0, "$x not in [0,1]")

    @Test
    fun jev() {
        live()
        val jev = JevJudge()
        val plaus = jev.plausibility(question, listOf(question), claim).also(::unit)
        val strength = jev.relationStrength(question, question, claim, Polarity.SUPPORT).also(::unit)
        val assessed = jev.assess(question, listOf(question), claim, Polarity.SUPPORT)
        listOf(assessed.plausibility, assessed.strength, assessed.quality, assessed.relevance).forEach(::unit)
        val sat = jev.saturation(ctx, Polarity.ATTACK).also(::unit)
        val rel = jev.relevance(ctx).also(::unit)
        println("LIVE jev plausibility=$plaus strength=$strength assess=$assessed saturation(con)=$sat relevance=$rel")
    }

    /**
     * EXP-03 triage question design against live Jev: an intra-round near-duplicate,
     * a clear refinement of an existing argument, and an argument for the other side.
     */
    @Test
    fun jevTriage() {
        live()
        val q = "Should cities ban private cars from their centres?"
        val ctx = ClaimContext(
            q, emptyList(), q,
            pros = listOf("Banning cars from city centres cuts air pollution, which harms residents' health."),
            cons = listOf("A car ban makes the centre harder to reach for people with limited mobility."),
        )
        val candidates = listOf(
            Candidate(PARKS_A, Polarity.SUPPORT),
            Candidate(PARKS_B, Polarity.SUPPORT),
            Candidate(
                "After Oslo removed most cars from its centre, measured nitrogen dioxide levels there fell markedly.",
                Polarity.SUPPORT,
            ),
            Candidate(
                "Shops in car-free centres often lose customers who used to drive in from the suburbs.",
                Polarity.SUPPORT,
            ),
        )
        val out = JevJudge().triage(ctx, candidates)
        println("LIVE jev triage=$out")
        assertEquals(TriageAction.ADD, out[0].action)
        assertTrue(out[1].action in setOf(TriageAction.DUPLICATE, TriageAction.REPLACE, TriageAction.MERGE), out[1].toString())
        assertEquals(2, out[1].target) // pros 0, cons 1, candidate 0 = 2
        assertEquals(Triage(TriageAction.REFINE, 0), out[2])
        assertEquals(TriageAction.OTHER_SIDE, out[3].action)
    }

    /** EXP-03 MERGE: one real Claude rewrite of the park/space pair. */
    @Test
    fun claudeMerge() {
        live()
        val merged = CliMerger(CliProposer.claude(ProcessGate())).merge(
            "Should cities ban private cars from their centres?", Polarity.SUPPORT, PARKS_A, PARKS_B,
        )
        println("LIVE claude merge -> $merged")
        assertTrue(merged.isNotBlank() && '\n' !in merged)
    }

    @Test
    fun claude() = propose(CliProposer.claude(ProcessGate()))

    @Test
    fun codex() = propose(CliProposer.codex(ProcessGate()))

    private fun propose(proposer: CliProposer) {
        live()
        val args = proposer.propose(ctx, Polarity.ATTACK, 2)
        println("LIVE ${proposer.id} -> $args")
        assertTrue(args.size in 1..2)
    }
}
