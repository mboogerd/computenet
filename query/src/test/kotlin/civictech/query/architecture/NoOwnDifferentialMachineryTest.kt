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
 *   failure taxonomy or difference shape), including an anonymous `object :` expression — this
 *   can never fire on code that compiles (see below) and is kept only because a false positive
 *   here is cheap and the rule doubles as documentation of the constraint;
 * - a `:query`-owned `sealed interface`/`sealed class` whose nested subtype carries both an
 *   `expected` and an `actual` constructor parameter — a *parallel* failure taxonomy, the gap
 *   left by the rule above;
 * - a read of a host's `deadLetterOutlet`, anywhere, not only a `.subscribe` call, outside
 *   [DEAD_LETTER_ALLOWLIST] (the runner owns that assertion; the allowlist is empty today — no
 *   `:query` file has a reason to read the outlet at all);
 * - an identifier containing `Shrink`, or a call to a `shrink…(` function — exempting the bare
 *   name `Shrinker` wherever it names ORA1's own `civictech.oracle.shrink.Shrinker` (a qualified
 *   reference to it, or any bare use once the file imports it), so *consuming* that shrinker is
 *   not mistaken for re-implementing one. A locally declared type or function whose name merely
 *   contains `Shrink` (`QueryShrinker`, `shrinkScript(`) is unaffected and still flagged.
 *
 * And a positive control: at least one test file calls `DifferentialRunner.check(`, so the
 * suite really does go through the runner rather than trivially avoiding the forbidden names.
 *
 * This file is excluded from the live scan by name: its synthetic sources and patterns spell
 * out the forbidden constructs on purpose. The synthetic-source controls prove each rule armed.
 *
 * **Chosen arm (computenet-ls5z2): strengthen the scan**, not review-only enforcement. Rule (a)
 * stays as a cheap, permanently-vacuous documentation check; the parallel-taxonomy, dead-letter-
 * read and Shrinker-exemption rules below close the gaps computenet-cab.6.2's review found
 * exploitable without extending a sealed type from another module or being blocked from
 * consuming ORA1's own shrinker.
 *
 * **What this scan still does not catch — it is a name/pattern tripwire, not a proof of
 * `[QRY1-ORA-01]`.**
 * - The parallel-taxonomy rule reads only a nested class's own primary-constructor parameter
 *   list for the literal names `expected` and `actual`; different names for the same shape
 *   (`want`/`got`), a taxonomy assembled outside a `sealed` body, or params carried across two
 *   nested types instead of one all pass.
 * - Rule (b) matches only `deadLetterOutlet` followed directly by `.subscribe`; reading the
 *   outlet through a local alias, or any other dead-letter inspection, passes.
 * - Rule (c) matches names, so a minimiser not spelled `Shrink…`/`shrink…(` passes. The
 *   `Shrinker` exemption is name-based too: a locally declared `typealias Shrinker = …` or an
 *   import alias (`import civictech.oracle.shrink.Shrinker as X`) is not recognised as the real
 *   one and is either wrongly exempted (the typealias case, unlikely to arise) or wrongly flagged
 *   (the alias case, the safe direction).
 * - The supertype-list reader skips no constructor modifier or annotation
 *   (`class X private constructor(…) : …`) and stops at a line break not preceded by a comma.
 *   The sealed-body reader has the same primary-constructor-only limit.
 * - The wave-prefix check is not scanned (cab.6-D4/D12: the BYO path has none to copy).
 *
 * Plain-JUnit source-text scan (cab.1-D2), the style of [RefImportBoundaryTest] and
 * [NoCellClassArchitectureTest].
 */
class NoOwnDifferentialMachineryTest {

    companion object {
        private const val SELF = "NoOwnDifferentialMachineryTest.kt"

        /**
         * `:query` file paths (relative to the `:query` project directory, as walked by the live
         * scan below) permitted to read `deadLetterOutlet` without subscribing to it. Empty: no
         * file has a reason yet. Add an entry only with a recorded review reason next to it.
         */
        val DEAD_LETTER_ALLOWLIST: Set<String> = emptySet()

        private val declaration = Regex("""\b(?:class|interface|object)\b""")
        private val forbiddenSupertype = Regex("""\b(RunOutcome|StateDifference)\b""")
        private val sealedTypeHeader = Regex("""\bsealed\s+(?:interface|class)\b""")
        private val nestedClass = Regex("""\bclass\b""")
        private val expectedParam = Regex("""\bexpected\s*:""")
        private val actualParam = Regex("""\bactual\s*:""")
        private val deadLetterOutletRead = Regex("""\bdeadLetterOutlet\b""")
        private val shrinkIdentifier = Regex("""\b[A-Za-z0-9_]*Shrink[A-Za-z0-9_]*\b""")
        private val shrinkCall = Regex("""\bshrink[A-Za-z0-9_]*\s*\(""")
        private val runnerCall = Regex("""\bDifferentialRunner\s*\.\s*check\s*\(""")
        private val qualifiedShrinker = Regex("""\bcivictech\.oracle\.shrink\.Shrinker\b""")
        private val importsShrinker = Regex("""^\s*import\s+civictech\.oracle\.shrink\.Shrinker\s*$""", RegexOption.MULTILINE)

        /**
         * Every violation in [source], one human-readable line each. [path] identifies the file
         * for [deadLetterAllowlist] purposes only; leave it blank for a synthetic source that is
         * never on an allowlist.
         */
        fun classify(
            source: String,
            path: String = "",
            deadLetterAllowlist: Set<String> = DEAD_LETTER_ALLOWLIST,
        ): List<String> {
            val found = mutableListOf<String>()
            supertypeLists(source).forEach { supertypes ->
                forbiddenSupertype.findAll(supertypes).forEach { found += "subtype of ${it.groupValues[1]}" }
            }
            sealedBodies(source).forEach { body ->
                nestedConstructorParamLists(body).forEach { params ->
                    if (expectedParam.containsMatchIn(params) && actualParam.containsMatchIn(params)) {
                        found += "parallel failure taxonomy (expected/actual subtype)"
                    }
                }
            }
            if (path !in deadLetterAllowlist) {
                deadLetterOutletRead.findAll(source).forEach { _ -> found += "deadLetterOutlet read" }
            }
            val exemptShrinkerRanges = qualifiedShrinker.findAll(source).map { it.range }.toList()
            val fileImportsShrinker = importsShrinker.containsMatchIn(source)
            shrinkIdentifier.findAll(source).forEach { m ->
                val isRealShrinker = m.value == "Shrinker" &&
                    (fileImportsShrinker || exemptShrinkerRanges.any { it.last == m.range.last })
                if (!isRealShrinker) found += "shrink identifier ${m.value}"
            }
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

        /**
         * The body (between the outermost balanced `{` `}`) of every `sealed interface`/`sealed
         * class` declaration in [source]: after the keyword, skip the name, balanced type
         * parameters, a balanced primary constructor and an optional supertype list, then take
         * the balanced brace body.
         */
        private fun sealedBodies(source: String): List<String> = sealedTypeHeader.findAll(source).mapNotNull { match ->
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
            skipBalanced('(', ')')
            skipSpace()
            if (i < source.length && source[i] == ':') {
                i++
                while (i < source.length && source[i] != '{') i++
            }
            skipSpace()
            if (i >= source.length || source[i] != '{') return@mapNotNull null
            val bodyStart = i
            skipBalanced('{', '}')
            source.substring(bodyStart, i)
        }.toList()

        /**
         * The primary-constructor parameter list of every `class` declaration nested in [body]:
         * after the keyword, skip the name and balanced type parameters, then take the balanced
         * parenthesised list. A nested type with no primary constructor (an `object`, or a
         * `class` whose members are all in the body) contributes nothing.
         */
        private fun nestedConstructorParamLists(body: String): List<String> = nestedClass.findAll(body).mapNotNull { match ->
            var i = match.range.last + 1
            fun skipSpace() { while (i < body.length && body[i].isWhitespace()) i++ }
            fun skipBalancedCapture(open: Char, close: Char): String? {
                if (i >= body.length || body[i] != open) return null
                val start = i
                var depth = 0
                while (i < body.length) {
                    when (body[i]) {
                        open -> depth++
                        close -> if (--depth == 0) { i++; return body.substring(start + 1, i - 1) }
                    }
                    i++
                }
                return null
            }
            skipSpace()
            while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_')) i++
            skipSpace()
            if (i < body.length && body[i] == '<') {
                var depth = 0
                while (i < body.length) {
                    when (body[i]) {
                        '<' -> depth++
                        '>' -> if (--depth == 0) { i++; break }
                    }
                    i++
                }
            }
            skipSpace()
            skipBalancedCapture('(', ')')
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
        classify(src) shouldContainExactly listOf("deadLetterOutlet read")
    }

    @Test
    fun `QRY1 §ORA-01 synthetic deadLetterOutlet read without subscribe is flagged`() {
        val src = """
            package civictech.query.run

            fun watch(world: SimWorld) {
                val letters = world.host.deadLetterOutlet
            }
        """.trimIndent()
        classify(src) shouldContainExactly listOf("deadLetterOutlet read")
    }

    @Test
    fun `QRY1 §ORA-01 a deadLetterOutlet read on an allow-listed path is not flagged`() {
        val src = """
            package civictech.query.run

            fun watch(world: SimWorld) {
                val letters = world.host.deadLetterOutlet
            }
        """.trimIndent()
        val path = "src/test/kotlin/civictech/query/run/Allowed.kt"
        classify(src, path = path, deadLetterAllowlist = setOf(path)).shouldBeEmpty()
    }

    @Test
    fun `QRY1 §ORA-01 synthetic parallel failure taxonomy sealed type is flagged`() {
        val src = """
            package civictech.query.run

            sealed interface ProbeVerdict {
                data class ProbeMismatch(val expected: String, val actual: String) : ProbeVerdict
                object ProbeMatch : ProbeVerdict
            }
        """.trimIndent()
        classify(src) shouldContainExactly listOf("parallel failure taxonomy (expected/actual subtype)")
    }

    @Test
    fun `QRY1 §ORA-01 an expected-actual holder outside a sealed type is not a parallel taxonomy`() {
        val src = """
            package civictech.query.run

            data class Comparison(val expected: String, val actual: String)

            sealed interface Status {
                data class Failure(val reason: String) : Status
                object Success : Status
            }
        """.trimIndent()
        classify(src).shouldBeEmpty()
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
    fun `QRY1 §ORA-01 ORA1's own Shrinker import and use are exempt but a local Shrinker-named class is not`() {
        val src = """
            package civictech.query.run

            import civictech.oracle.shrink.Shrinker

            class QueryShrinker

            fun go(case: GeneratedCase) = Shrinker.run(case, reference, budget)
        """.trimIndent()
        classify(src) shouldContainExactly listOf("shrink identifier QueryShrinker")
    }

    @Test
    fun `QRY1 §ORA-01 a fully-qualified Shrinker use is exempt even without a bare import`() {
        val src = """
            package civictech.query.run

            fun go(case: GeneratedCase) = civictech.oracle.shrink.Shrinker.run(case, reference, budget)
        """.trimIndent()
        classify(src).shouldBeEmpty()
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
            classify(file.readText(), path = file.path).map { "${file.path}: $it" }
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
