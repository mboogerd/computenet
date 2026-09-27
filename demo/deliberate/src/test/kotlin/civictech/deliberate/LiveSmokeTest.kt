package civictech.deliberate

import civictech.agora.cell.Polarity
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
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

    private fun live() = assumeTrue(System.getenv("DELIBERATE_LIVE") == "1", "set DELIBERATE_LIVE=1 to run live smoke calls")

    private fun unit(x: Double) = assertTrue(x in 0.0..1.0, "$x not in [0,1]")

    @Test
    fun jev() {
        live()
        val jev = JevJudge()
        val plaus = jev.plausibility(question, listOf(question), claim).also(::unit)
        val strength = jev.relationStrength(question, question, claim, Polarity.SUPPORT).also(::unit)
        val dups = jev.duplicates(
            claim, Polarity.SUPPORT, ctx.pros,
            listOf("Without ticket prices, more drivers switch to buses and trams.", "Free transit frees up road space for cyclists."),
        )
        val sat = jev.saturation(ctx, Polarity.ATTACK).also(::unit)
        val rel = jev.relevance(ctx).also(::unit)
        println("LIVE jev plausibility=$plaus strength=$strength duplicates=$dups saturation(con)=$sat relevance=$rel")
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
