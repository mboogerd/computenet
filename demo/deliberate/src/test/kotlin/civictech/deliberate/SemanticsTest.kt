package civictech.deliberate

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The credence layers (SPEC §2 "Credence layers and consensus"). Reference
 * values were computed with the prototype `semantics.js` under node
 * (`combine_<id>(base, attacks.map(energy_<id>(c, s)), …)` at the defaults).
 * The energy-only cases below use arguments of credence 1 and strength e,
 * so every layer's energy is e — except jnb's, which is `energy_jnb(1, e)`.
 */
class SemanticsTest {

    private data class Case(val base: Double, val attacks: List<Double>, val supports: List<Double>)

    /** Arguments whose source is certain, so their energy is the strength [e] (jnb: `energy_jnb(1, e)`). */
    private fun sure(e: List<Double>) = e.map { Arg(strength = it, credence = 1.0) }
    private fun Semantics.at(base: Double, attacks: List<Double>, supports: List<Double>) =
        evaluate(base, sure(attacks), sure(supports))

    private val cases = listOf(
        Case(0.8, listOf(0.95 * 0.9), emptyList()),
        Case(0.5, List(4) { 0.4 }, List(4) { 0.6 }),
        Case(0.3, listOf(0.2), listOf(0.7, 0.5)),
        Case(0.5, emptyList(), emptyList()),
        Case(0.9, listOf(0.9, 0.85), listOf(0.1)),
    )

    private val reference = mapOf(
        "dfquad" to listOf(0.11599999999999999, 0.55200000000000005, 0.75499999999999989, 0.5, 0.10350000000000004),
        "wlo" to listOf(0.36089065754885208, 0.73346192328260873, 0.65777234409665264, 0.5, 0.36862007558071008),
        "jnb" to listOf(0.46213194266167712, 0.68017912323104301, 0.57594371873408989, 0.5, 0.54054892923259579),
        "woe" to listOf(0.48538591265427544, 0.72574019845840698, 0.63460520460457437, 0.5, 0.56965595410448899),
        "euler" to listOf(0.73138869750773972, 0.64501586296664981, 0.49875640597824877, 0.5, 0.83800074747896569),
        "qe" to listOf(0.46215392614202572, 0.69512195121951215, 0.64999999999999991, 0.5, 0.24177300201477503),
        "mlp" to listOf(0.62978495051931360, 0.68997448112761250, 0.53810152622444896, 0.5, 0.63349143234382577),
    )

    @Test
    fun `every layer matches the prototype on recorded values`() {
        assertEquals(SemanticsCatalog.IDS.toSet(), reference.keys)
        for ((id, expected) in reference) {
            val s = SemanticsCatalog.of(id)
            cases.zip(expected).forEach { (c, want) ->
                assertEquals(want, s.at(c.base, c.attacks, c.supports), 1e-12, "$id $c")
            }
        }
    }

    @Test
    fun `weighted log-odds reference behaviour`() {
        val wlo = WeightedLogOdds()
        // prior 0.8 with one attack of energy 0.95 x 0.9 falls below one half
        assertTrue(wlo.combine(0.8, listOf(0.95 * 0.9), emptyList()) < 0.5)
        // prior 0.5, four supports of 0.6 against four attacks of 0.4 reaches 0.6
        assertTrue(wlo.combine(0.5, List(4) { 0.4 }, List(4) { 0.6 }) >= 0.6)
        // the parameters are the formula's: sigmoid(a*logit(b) + k*(|S^g|_p - |A^g|_p))
        val custom = WeightedLogOdds(alpha = 0.5, k = 1.0, p = 1.0, gamma = 1.0)
        val z = 0.5 * ln(0.7 / 0.3) + (0.5 + 0.25) - 0.4
        assertEquals(1 / (1 + exp(-z)), custom.combine(0.7, listOf(0.4), listOf(0.5, 0.25)), 1e-12)
        assertFailsWith<IllegalArgumentException> { WeightedLogOdds(p = 0.5) }
    }

    @Test
    fun `every layer is bounded, neutral without arguments and monotone in each energy`() {
        val grid = listOf(0.0, 0.1, 0.35, 0.6, 0.9, 1.0)
        for (id in SemanticsCatalog.IDS) {
            val s = SemanticsCatalog.of(id)
            for (b in listOf(0.01, 0.2, 0.5, 0.8, 0.99)) {
                assertEquals(b, s.at(b, emptyList(), emptyList()), 1e-12, "$id keeps its base")
                for (x in grid) for (y in grid) {
                    val v = s.at(b, listOf(x), listOf(y))
                    assertTrue(v in 0.0..1.0, "$id($b, $x, $y) = $v")
                    // more support never lowers, more attack never raises
                    if (y < 1.0) assertTrue(s.at(b, listOf(x), listOf(y, 0.3)) >= v - 1e-12, "$id support monotone")
                    if (x < 1.0) assertTrue(s.at(b, listOf(x, 0.3), listOf(y)) <= v + 1e-12, "$id attack monotone")
                    // a more credible source never weakens its argument
                    for (c in grid) {
                        val sup = s.evaluate(b, emptyList(), listOf(Arg(y, c)))
                        assertTrue(s.evaluate(b, emptyList(), listOf(Arg(y, minOf(c + 0.1, 1.0)))) >= sup - 1e-12, "$id credence monotone")
                    }
                }
                // a higher base never lowers the result
                assertTrue(s.at(minOf(b + 0.05, 0.99), listOf(0.4), listOf(0.3)) >= s.at(b, listOf(0.4), listOf(0.3)) - 1e-12, id)
            }
        }
    }

    @Test
    fun `symmetric layers treat attack as mirrored support`() {
        // Euler-based semantics is not symmetric by design and is left out.
        for (id in listOf("dfquad", "wlo", "jnb", "woe", "qe", "mlp")) {
            val s = SemanticsCatalog.of(id)
            for (b in listOf(0.2, 0.5, 0.7)) {
                val a = listOf(0.3, 0.8)
                val sup = listOf(0.5)
                assertEquals(1 - s.at(1 - b, sup, a), s.at(b, a, sup), 1e-12, "$id at $b")
            }
        }
    }

    /**
     * jnb is exact: the edge's strength and its source's credence reach the
     * layer separately, and Jeffrey conditioning is applied to them as the
     * prototype does (`energy_jnb(c, s)` = ln(c·LR(s) + (1−c)·LR(s)^−r)),
     * not to their product. Reference values: node, prototype `semantics.js`.
     */
    @Test
    fun `jnb matches the prototype's Jeffrey conditioning exactly`() {
        data class J(val base: Double, val attacks: List<Arg>, val supports: List<Arg>)
        // Arg(strength, credence) = the prototype's energy_jnb(credence, strength).
        val js = listOf(
            J(0.8, listOf(Arg(0.95, 0.9)), emptyList()),
            J(0.5, listOf(Arg(0.6, 0.3), Arg(0.2, 0.7)), listOf(Arg(0.9, 0.4), Arg(0.5, 1.0))),
            J(0.3, emptyList(), listOf(Arg(0.7, 0.5))),
            J(0.9, listOf(Arg(0.9, 0.1), Arg(0.85, 0.95)), listOf(Arg(0.1, 0.6))),
        )
        val r0 = listOf(0.48251003362507106, 0.67576802703019578, 0.48345213537677784, 0.68000351754621557)
        val r1 = listOf(0.48712392335374854, 0.70629345097282104, 0.43986176237851315, 0.75527207436080979)
        js.zip(r0).forEach { (j, want) -> assertEquals(want, JeffreyNaiveBayes().evaluate(j.base, j.attacks, j.supports), 1e-12, "$j") }
        js.zip(r1).forEach { (j, want) -> assertEquals(want, JeffreyNaiveBayes(r = 1.0).evaluate(j.base, j.attacks, j.supports), 1e-12, "r=1 $j") }
        // The product reading the agora port used is not the same thing.
        val doubted = listOf(Arg(0.9, 0.5))
        assertTrue(abs(JeffreyNaiveBayes().evaluate(0.5, emptyList(), doubted) -
            JeffreyNaiveBayes().evaluate(0.5, emptyList(), listOf(Arg(0.45, 1.0)))) > 1e-3)
    }

    @Test
    fun `consensus is the geometric mean of the members' odds`() {
        val credences = mapOf("dfquad" to 0.1, "wlo" to 0.2, "jnb" to 0.5, "woe" to 0.9, "mlp" to 0.99)
        // recorded with node: sigmoid(mean(logit([0.2, 0.5, 0.9])))
        assertEquals(0.56716902562290783, Consensus.of(credences, listOf("wlo", "jnb", "woe")), 1e-12)
        // clamped to [0.001, 0.999] before the logit: a 0 cannot veto
        assertEquals(0.037303723009214855, Consensus.of(mapOf("a" to 0.0, "b" to 0.6), listOf("a", "b")), 1e-12)
        assertEquals(0.7, Consensus.of(mapOf("wlo" to 0.7), listOf("wlo", "jnb")), 1e-12)
        // no member running: every layer counts
        assertEquals(0.3, Consensus.of(mapOf("dfquad" to 0.3), listOf("wlo")), 1e-12)
        assertEquals(0.5, Consensus.of(emptyMap(), listOf("wlo")))
    }

    @Test
    fun `unknown semantics are refused`() {
        assertFailsWith<IllegalArgumentException> { SemanticsCatalog.of("nope") }
    }
}
