package civictech.compmodel

import civictech.compmodel.check.Exploration
import civictech.compmodel.check.Explorer
import civictech.compmodel.check.Report
import civictech.compmodel.check.Spec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Explore exhaustively and require the invariants to hold, printing the full trace if not. */
fun <S : Any> holds(tag: String, spec: Spec<S>): Exploration {
    val e = Explorer.bfs(spec)
    Report.line("[$tag] ${e.summary()}")
    withClue(e.counterexample?.render() ?: "") { e.holds shouldBe true }
    withClue("exploration must be exhaustive, not cut by the state cap") { e.complete shouldBe true }
    return e
}

/** Explore and require a counterexample whose violation mentions [expect]. */
fun <S : Any> diverges(tag: String, spec: Spec<S>, expect: String): Exploration {
    val e = Explorer.bfs(spec)
    Report.line("[$tag] ${e.summary()}")
    e.counterexample?.let { Report.line(it.render()) }
    withClue("$tag: the checker must find a counterexample") { e.holds shouldBe false }
    e.counterexample!!.violation shouldContain expect
    return e
}
