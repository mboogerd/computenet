package civictech.compmodel

import civictech.compmodel.check.Exploration
import civictech.compmodel.check.Explorer
import civictech.compmodel.check.Report
import civictech.compmodel.check.Spec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/** Explore exhaustively and require the invariants to hold, printing the full trace if not. */
fun <S : Any> holds(tag: String, spec: Spec<S>): Exploration {
    val e = Explorer.bfs(spec)
    Report.line("[$tag] ${e.summary()}")
    withClue(e.counterexample?.render() ?: "") { e.holds shouldBe true }
    withClue("exploration must be exhaustive, not cut by the state cap") { e.complete shouldBe true }
    return e
}

/** Explore and require a counterexample whose violation mentions one of [expect]. */
fun <S : Any> diverges(tag: String, spec: Spec<S>, vararg expect: String): Exploration {
    val e = Explorer.bfs(spec)
    Report.line("[$tag] ${e.summary()}")
    e.counterexample?.let { Report.line(it.render()) }
    withClue("$tag: the checker must find a counterexample") { e.holds shouldBe false }
    val v = e.counterexample!!.violation
    withClue("$tag: violation '$v' must mention one of ${expect.toList()}") { expect.any { v.contains(it) } shouldBe true }
    return e
}
