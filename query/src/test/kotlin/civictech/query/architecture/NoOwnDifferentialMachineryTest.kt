package civictech.query.architecture

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `[QRY1-ORA-01]`'s second half: `:query` drives the kernel through ORA1's
 * `DifferentialRunner` and SHALL NOT re-implement diffing, the failure taxonomy, the dead-letter
 * assertion or shrinking. Every `.kt` file under `src/main/kotlin` and `src/test/kotlin` is
 * scanned for:
 *
 * - a declaration whose supertype list names `RunOutcome` or `StateDifference` (a private
 *   failure taxonomy or difference shape), including an anonymous `object :` expression;
 * - a subscription to a host's dead-letter outlet (the runner owns that assertion);
 * - an identifier containing `Shrink`, or a call to a `shrink…(` function.
 *
 * And a positive control: at least one test file calls `DifferentialRunner.check(`, so the
 * suite really does go through the runner rather than trivially avoiding the forbidden names.
 *
 * This file is excluded from the live scan by name: its synthetic sources and patterns spell
 * out the forbidden constructs on purpose. The synthetic-source controls prove each rule armed.
 *
 * **What this scan does not catch — it is a name tripwire, not a proof of `[QRY1-ORA-01]`.**
 * - Rule (a) can never fire on code that compiles: `RunOutcome` and `StateDifference` are
 *   `sealed` in `:oracle`, and Kotlin already refuses a subtype from another module (observed
 *   in review of computenet-cab.6.2: "Extending sealed classes or interfaces from a different
 *   module is prohibited"). A *parallel* taxonomy — a `:query`-owned sealed verdict type with
 *   its own names — and a hand-written `expected != actual` comparison of folds are not
 *   flagged at all; "no diffing" is held by review, not by this test.
 * - Rule (b) matches only `deadLetterOutlet` followed directly by `.subscribe`; reading the
 *   outlet through a local alias, or any other dead-letter inspection, passes.
 * - Rule (c) matches names, so a minimiser not spelled `Shrink…`/`shrink…(` passes, and using
 *   ORA1's own `civictech.oracle.shrink.Shrinker` (consumption, not re-implementation) FAILS.
 * - The supertype-list reader skips no constructor modifier or annotation
 *   (`class X private constructor(…) : …`) and stops at a line break not preceded by a comma.
 * - The wave-prefix check is not scanned (cab.6-D4/D12: the BYO path has none to copy).
 *
 * Plain-JUnit source-text scan (cab.1-D2), the style of [RefImportBoundaryTest] and
 * [NoCellClassArchitectureTest].
 */
class NoOwnDifferentialMachineryTest {

    companion object {
        private const val SELF = "NoOwnDifferentialMachineryTest.kt"

        private val declaration = Regex("""\b(?:class|interface|object)\b""")
        private val forbiddenSupertype = Regex("""\b(RunOutcome|StateDifference)\b""")
        private val deadLetterSubscription = Regex("""\bdeadLetterOutlet\s*\.\s*subscribe\b""")
        private val shrinkIdentifier = Regex("""\b[A-Za-z0-9_]*Shrink[A-Za-z0-9_]*\b""")
        private val shrinkCall = Regex("""\bshrink[A-Za-z0-9_]*\s*\(""")
        private val runnerCall = Regex("""\bDifferentialRunner\s*\.\s*check\s*\(""")

        /** Every violation in [source], one human-readable line each. */
        fun classify(source: String): List<String> {
            val found = mutableListOf<String>()
            supertypeLists(source).forEach { supertypes ->
                forbiddenSupertype.findAll(supertypes).forEach { found += "subtype of ${it.groupValues[1]}" }
            }
            deadLetterSubscription.findAll(source).forEach { _ -> found += "deadLetterOutlet subscription" }
            shrinkIdentifier.findAll(source).forEach { found += "shrink identifier ${it.value}" }
            shrinkCall.findAll(source).forEach { found += "shrink call ${it.value.trimEnd('(', ' ')}" }
            return found
        }

        /** Whether [source] calls the runner's check entry point. */
        fun callsRunner(source: String): Boolean = runnerCall.containsMatchIn(source)

        /**
         * The supertype list of every `class`/`interface`/`object` declaration in [source]: after
         * the keyword, skip the name, balanced type parameters and a balanced primary constructor;
         * if a `:` follows, the list runs to the body `{`, or to a line end not continued by a
         * comma.
         */
        private fun supertypeLists(source: String): List<String> = declaration.findAll(source).mapNotNull { match ->
            var i = match.range.last + 1
            fun skipSpace() { while (i < source.length && source[i].isWhitespace()) i++ }
            fun skipBalanced(open: Char, close: Char) {
                if (i >= source.length || source[i] != open) return
                var depth = 0
                while (i < source.length) {
                    when (source[i]) {
                        open -> depth++
                        close -> if (--depth == 0) { i++; return }
                    }
                    i++
                }
            }
            skipSpace()
            while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
            skipSpace()
            skipBalanced('<', '>')
            skipSpace()
            // A modifier or annotation before the constructor (`private constructor`) is rare
            // enough in this module to leave out; the plain primary constructor is skipped.
            skipBalanced('(', ')')
            skipSpace()
            if (i >= source.length || source[i] != ':') return@mapNotNull null
            val start = ++i
            while (i < source.length && source[i] != '{') {
                if (source[i] == '\n') {
                    val rest = source.substring(start, i).trimEnd()
                    if (!rest.endsWith(",")) break
                }
                i++
            }
            source.substring(start, i)
        }.toList()
    }

    @Test
    fun `QRY1 §ORA-01 synthetic RunOutcome subtype is flagged`() {
        val src = """
            package civictech.query.run

            import civictech.oracle.run.RunOutcome

            data class MyOutcome(
                val seed: Long,
                val note: String = listOf("x").joinToString(),
            ) : java.io.Serializable,
                RunOutcome
        """.trimIndent()
        classify(src) shouldContainExactly listOf("subtype of RunOutcome")
    }

    @Test
    fun `QRY1 §ORA-01 synthetic StateDifference subtype and anonymous object are flagged`() {
        val src = """
            package civictech.query.run

            class MyDiff<T> : StateDifference { }
            val x = object : StateDifference {}
        """.trimIndent()
        classify(src) shouldContainExactly listOf("subtype of StateDifference", "subtype of StateDifference")
    }

    @Test
    fun `QRY1 §ORA-01 a RunOutcome used as a property or return type is not a subtype`() {
        val src = """
            package civictech.query.run

            data class Holder(val outcome: RunOutcome, val diff: StateDifference)
            class Other : java.io.Serializable
            fun run(): RunOutcome = RunOutcome.Success
        """.trimIndent()
        classify(src).shouldBeEmpty()
    }

    @Test
    fun `QRY1 §ORA-01 synthetic deadLetterOutlet subscription is flagged`() {
        val src = """
            package civictech.query.run

            fun watch(world: SimWorld) {
                world.host.deadLetterOutlet
                    .subscribe(Use.fixed(sink, PortRef.generate()))
            }
        """.trimIndent()
        classify(src) shouldContainExactly listOf("deadLetterOutlet subscription")
    }

    @Test
    fun `QRY1 §ORA-01 synthetic shrinker names are flagged`() {
        val src = """
            package civictech.query.run

            class QueryShrinker
            fun go() = shrinkScript (script)
        """.trimIndent()
        classify(src) shouldContainExactly listOf("shrink identifier QueryShrinker", "shrink call shrinkScript")
    }

    @Test
    fun `QRY1 §ORA-01 the runner-call control recognises a call and rejects a mention`() {
        callsRunner("val o = DifferentialRunner.check(seed, marker, script, ref, budget, ::build)") shouldBe true
        callsRunner("// see DifferentialRunner's KDoc on check") shouldBe false
    }

    @Test
    fun `QRY1 §ORA-01 the query source trees own no differential machinery and a test calls the runner`() {
        val roots = listOf(File("src/main/kotlin"), File("src/test/kotlin"))
        roots.forEach { root ->
            withClue("expected ${root.absolutePath} to exist relative to the :query project directory") {
                root.isDirectory shouldBe true
            }
        }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        withClue("non-vacuity: the scan must read this file and the rest of the module") {
            files.any { it.name == SELF } shouldBe true
            (files.size > 10) shouldBe true
        }

        val offenders = files.filter { it.name != SELF }.flatMap { file ->
            classify(file.readText()).map { "${file.path}: $it" }
        }
        withClue("[QRY1-ORA-01] :query re-implements differential machinery the runner owns") {
            offenders.shouldBeEmpty()
        }

        val callers = files.filter { it.path.startsWith("src/test/kotlin") && callsRunner(it.readText()) }
        withClue("[QRY1-ORA-01] no :query test calls DifferentialRunner.check(") {
            callers.isEmpty() shouldBe false
        }
    }
}
