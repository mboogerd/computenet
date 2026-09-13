package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Feature `computenet-376c` (DSC4) makes true: "the interim identity-from-key
 * binding exists in exactly ONE named place, and a test or an architecture
 * guard demonstrates there is no second site deriving an identity from key
 * material" — `civictech.cell.link.PeerIdentityBinding.Companion.Interim`'s
 * lambda body (see `Identity.kt`'s KDoc there).
 *
 * Same style as [ArchitectureRatchetTest]: a plain-file line scan, no Kotlin
 * parser, no new dependency, checked-in baseline that is a **ratchet, not an
 * allowlist to grow silently** — a new site the scan finds either gets
 * reverted, or gets added to the baseline in the same PR with its why-comment,
 * for the reviewer to judge whether it is really innocent.
 *
 * Three properties, all evaluated over every `<module>/src/main/kotlin` tree
 * `settings.gradle.kts` includes (module roots discovered the same way
 * [ModuleInventoryTest] discovers module names, so a module added to the
 * build is covered without editing this file):
 *
 * (a) the set of files containing a `PeerId(` **construction** — deliberately
 *     distinguished from a `PeerId(` **mention**: a line matching `PeerId(`
 *     after stripping `//` comments, skipping KDoc lines (trimmed line starts
 *     with `*`), and excluding the `data class PeerId(` declaration line
 *     itself — must equal the checked-in baseline
 *     `kernel/src/test/resources/architecture/peerid-constructions.txt`.
 *     Constructing a `PeerId` from a hello/frame token, or from a configured
 *     name, is not a derivation from key material and is fine to baseline;
 *     constructing one from a `KeyId`, a fingerprint, or key bytes is exactly
 *     the second site this feature forbids.
 * (b) exactly one production file implements `PeerIdentityBinding` (a line
 *     matching `PeerIdentityBinding\s*\{` as a SAM conversion, or a
 *     `class`/`object` declaration line whose supertype list names
 *     `PeerIdentityBinding` after a colon, or a supertype colon written
 *     `\s:\s*PeerIdentityBinding` — the two supertype forms are
 *     complementary, see [bindingSupertypeSpacedColon] — excluding the
 *     interface's own `fun interface PeerIdentityBinding {` declaration
 *     line) — and it is
 *     `civictech.cell.link.Identity.kt`.
 * (c) exactly one production `fun fingerprint(` declaration exists, and its
 *     return type on that line is `KeyId`.
 */
class IdentityDerivationRatchetTest {

    // ---- scanning, factored to take a root so the fixture self-check can
    // exercise the exact same logic over a synthetic tree. ----

    private val includeLine = Regex("""^\s*include\("(:[^"]+)"\)""")
    private val peerIdConstruction = Regex("""\bPeerId\(""")
    private val peerIdDeclaration = Regex("""\bclass\s+PeerId\(""")
    private val bindingSamConversion = Regex("""\bPeerIdentityBinding\s*\{""")

    // A SUPERTYPE declaration ("class Foo : PeerIdentityBinding {", or
    // "class Foo: PeerIdentityBinding, Marker {" with no space before the
    // colon and a further supertype after) is recognised by the `class`/
    // `object` keyword that introduces the declared type, not by whitespace
    // around the colon — whitespace-before-colon is a ktlint convention this
    // repo does not mechanically enforce (no .editorconfig, no ktlint plugin
    // in any build.gradle.kts, buildSrc or build-logic), so a supertype list
    // can legally omit the leading space. Requiring the keyword instead is
    // what keeps `val x: PeerIdentityBinding = PeerIdentityBinding.Interim`
    // (a type usage, not an implementation) from being misread as a second
    // implementation site: that line names no `class`/`object`.
    private val bindingSupertype = Regex(
        """^\s*(?:\w+\s+)*(?:class|object)\s+\w+\s*:\s*[^{]*\bPeerIdentityBinding\b""",
    )

    // ...and the complementary form the keyword anchor structurally cannot
    // reach: a declaration header whose colon is separated from the type name
    // by a primary constructor ("class Foo(val n: Int) : PeerIdentityBinding,
    // Marker {"), by an annotation before the modifiers ("@Suppress("x")
    // class Foo : ..."), or which names no type at all (an anonymous
    // "companion object : ..." / "val impl = object : ..."). This is the
    // ORIGINAL whitespace-before-colon regex, kept as a second alternative
    // rather than replaced: on each of those four shapes with a trailing
    // supertype after the interface name, the keyword-anchored regex above
    // does not match and neither does [bindingSamConversion] (no `{` follows
    // the interface name), so dropping it would trade the escape it closes
    // for four it opens. Measured 2026-09-02 (computenet-lusi review).
    // Known residual, matched by NEITHER: a supertype list wrapped onto its
    // own line with a comma after the interface name
    // ("class Foo :\n    PeerIdentityBinding,\n    Marker {") — a line scan
    // sees no declaration keyword and no colon on the continuation line.
    private val bindingSupertypeSpacedColon = Regex("""\s:\s*PeerIdentityBinding\b""")
    private val bindingInterfaceDeclaration = Regex("""^\s*fun\s+interface\s+PeerIdentityBinding\b""")

    // ...and the shape none of the above can reach at all: a supertype list
    // wrapped onto its own line ("class Escape :\n    PeerIdentityBinding,\n
    // Marker {"). A line scan sees the class/object keyword and colon on one
    // physical line with nothing after the colon, the interface name alone on
    // the next with no keyword/colon/brace, and the brace on a third line
    // with no interface name — none of [bindingSamConversion], [bindingSupertype]
    // or [bindingSupertypeSpacedColon] can match any single line. Detected by
    // matching the header REGION instead of a single line: a class/object
    // header line whose supertype list is empty on that line (trimmed content
    // ends in the colon) opens an accumulation that folds subsequent lines in
    // for as long as the supertype list is unfinished — see the fold's own
    // bound in [scanPeerIdentityBindingImplementations], which is what keeps a
    // body-less header from sweeping later code in — then the fold is checked
    // as a whole for the interface name. Measured 2026-09-02 (computenet-6jkz).
    private val bindingHeaderWrapStart = Regex("""^\s*(?:\w+\s+)*(?:class|object)\s+\S.*:\s*$""")

    // The interface name, matched against a FOLDED header region rather than a
    // single line. Hoisted out of the scan loop; see
    // [scanPeerIdentityBindingImplementations].
    private val bindingHeaderName = Regex("""\bPeerIdentityBinding\b""")

    // The fold's continuation test (see [scanPeerIdentityBindingImplementations])
    // originally recognised only a trailing comma or an unbalanced '(' as
    // "the supertype list is not finished yet", then grew a run of
    // special-cased bracket/keyword checks over three successive patches
    // (computenet-3dt4t, computenet-s8ige, computenet-omm5p) — each closing
    // one probe by adding another unordered total over the WHOLE folded
    // text, which is why the paren-nested cases (a generic argument INSIDE a
    // supertype constructor call, a comparison operator using '<'/'>')  kept
    // reopening new escapes: an unordered count cannot tell a bracket seen
    // while already inside a constructor call's arguments from one seen at
    // the top level of the supertype list (computenet-zfdsw, replacing all
    // three: PROBEs S1/S2/S3 below). [headerBracketDepths] replaces the
    // bracket half of that history with one left-to-right walk that tracks
    // real nesting instead of a running total; `by` and the function-type
    // arrow `->` remain plain trailing-token checks below since neither is a
    // bracket.
    //
    // Known residual, out of scope: a generic supertype whose OPEN bracket
    // sits alone on its own line ("Handler\n    <\n        String\n    >,"),
    // with no continuation cue at all on the line carrying just "Handler" —
    // no trailing comma, bracket, `by` or `->` — reads as a complete
    // body-less header and the fold stops before the '<' is ever seen
    // (PROBE S4, pre-existing on both sides of this fix; not attested in any
    // production file at 65756a102).
    private val bindingHeaderInfixContinuation = Regex("""\bby$""")

    // A line ending in the function-type arrow ("(Int) ->") is a
    // continuation exactly like one ending in `by` — the supertype list is
    // not finished, it is split across the arrow itself (computenet-s8ige
    // PROBE P3). Without this, a header whose FIRST continuation line ends
    // bare at `->` reads as complete (no comma, no unbalanced bracket, no
    // trailing `by`) and the fold stops one line early.
    private val bindingHeaderArrowContinuation = Regex("""->$""")

    /**
     * Walks the folded header text left to right, tracking '(' nesting
     * depth and, independently, a generic ('<'/'>') nesting depth that is
     * only adjusted while paren depth is zero — i.e. at the top level of the
     * supertype list, never inside a supertype constructor call's argument
     * list. That single distinction is what an unordered bracket TOTAL over
     * the whole folded text cannot make, and it is what every prior probe
     * in this fold's history turns out to need:
     *
     * - A generic argument nested inside a constructor call
     *   ("Base(\n    listOf<Int>(),\n),") never touches the generic depth at
     *   all — its brackets are seen at paren depth > 0 — and the fold still
     *   spans it correctly because the paren balance alone drives the
     *   continuation.
     * - A bare comparison operator inside a constructor call
     *   ("Base(a < b)", "Base(a<b)", "Base(a< b)") likewise never touches
     *   generic depth, REGARDLESS of spacing — closing PROBEs o4/o5
     *   (computenet-omm5p) and S3 (computenet-zfdsw) together without a
     *   spacing-based exclusion (the prior [genericOpenNotOperator] fix,
     *   which is why that regex and [genericCloseNotArrow] are gone: the
     *   depth walk supersedes both).
     * - A top-level generic supertype opens and closes generic depth
     *   whether its brackets are spaced or not ("Handler<", "Handler <"),
     *   closing PROBE S1 — the prior fix's spacing requirement is exactly
     *   what made it miss a spaced open.
     * - An operator '>' occurring at paren depth > 0 ("Base(a > b),") never
     *   decrements generic depth, so it can no longer end a LATER top-level
     *   generic's fold early — closing PROBE S2.
     * - A '>' immediately preceded by '-' (the function-type arrow) is
     *   still excluded from the generic-close count, exactly as
     *   [genericCloseNotArrow] did, so `(Int) -> Unit` contributes no real
     *   generic close (PROBEs P2/P3/i, computenet-s8ige) — carried over
     *   unchanged; the arrow was never the paren/generic-depth confusion
     *   this walk fixes.
     *
     * Generic depth is clamped at zero on close (an operator '>' at paren
     * depth 0 with no matching open, e.g. a bare `Base > Other`, decrements
     * nothing rather than going negative) — not attested in any fixture or
     * production file, but a walk should not let one adversarial line poison
     * the running depth for the rest of the header.
     */
    private fun headerBracketDepths(text: String): Pair<Int, Int> {
        var parenDepth = 0
        var genericDepth = 0
        for (index in text.indices) {
            when (text[index]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '<' -> if (parenDepth == 0) genericDepth++
                '>' -> if (parenDepth == 0 && genericDepth > 0 && (index == 0 || text[index - 1] != '-')) genericDepth--
            }
        }
        return parenDepth to genericDepth
    }
    private val fingerprintDeclaration = Regex("""\bfun\s+fingerprint\([^)]*\)\s*:\s*([\w.]+)""")

    /** Repo-relative module `src/main/kotlin` roots, parsed from `settings.gradle.kts`. */
    fun moduleMainRoots(root: File): List<File> {
        val settingsFile = File(root, "settings.gradle.kts")
        val includes = settingsFile.readLines().mapNotNull { includeLine.find(it)?.groupValues?.get(1) }
        return includes
            .map { it.removePrefix(":").replace(':', '/') }
            .map { File(root, "$it/src/main/kotlin") }
            .filter { it.isDirectory }
    }

    /** Non-blank, non-comment "content" form of a line: `//`-comments stripped, or null for a KDoc line. */
    private fun contentOrNull(line: String): String? {
        if (line.trim().startsWith("*")) return null
        return line.substringBefore("//")
    }

    private fun eachKotlinFile(root: File, moduleRoots: List<File>, action: (File, relativePath: String) -> Unit) {
        moduleRoots.forEach { moduleRoot ->
            moduleRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                action(file, file.relativeTo(root).path.replace(File.separatorChar, '/'))
            }
        }
    }

    /** Repo-relative paths of production files containing a `PeerId(` construction. */
    fun scanPeerIdConstructions(root: File, moduleRoots: List<File>): Set<String> {
        val paths = mutableSetOf<String>()
        eachKotlinFile(root, moduleRoots) { file, relativePath ->
            file.forEachLine { line ->
                val content = contentOrNull(line) ?: return@forEachLine
                if (peerIdDeclaration.containsMatchIn(content)) return@forEachLine
                if (peerIdConstruction.containsMatchIn(content)) paths += relativePath
            }
        }
        return paths
    }

    /** Repo-relative paths of production files implementing `PeerIdentityBinding`. */
    fun scanPeerIdentityBindingImplementations(root: File, moduleRoots: List<File>): Set<String> {
        val paths = mutableSetOf<String>()
        eachKotlinFile(root, moduleRoots) { file, relativePath ->
            // Non-null while folding a wrapped header (see [bindingHeaderWrapStart])
            // into one logical line, from the class/object keyword's line up to
            // (and including) the line carrying the opening brace.
            var pendingHeader: MutableList<String>? = null
            file.forEachLine { line ->
                val content = contentOrNull(line) ?: return@forEachLine
                if (bindingInterfaceDeclaration.containsMatchIn(content)) {
                    pendingHeader = null
                    return@forEachLine
                }
                val header = pendingHeader
                if (header != null) {
                    header.add(content)
                    val trimmed = content.trim()
                    // A blank or comment-only line inside a header carries no
                    // supertype information and does not end it — `SetCell.kt`
                    // wraps a real supertype list with a `//` block in exactly
                    // that position. Fold on without deciding.
                    if (trimmed.isNotEmpty()) {
                        val folded = header.joinToString(" ")
                        // The header ends at the opening brace; short of that
                        // the supertype list only CONTINUES while it is
                        // visibly unfinished — a trailing comma, an unbalanced
                        // supertype constructor call `(...)` or top-level
                        // generic argument `<...>` spanning lines, or a
                        // continuation split across `by` or the function-type
                        // arrow `->`. Anything else is a body-less declaration
                        // that ended on this line, and folding past it would
                        // sweep unrelated code into the match: measured on
                        // "class Wrapped :\n    Base()" followed by a plain
                        // "fun consume(b: PeerIdentityBinding) {" type usage,
                        // which a fold-until-brace form flagged as an
                        // implementation (computenet-6jkz review). A ratchet
                        // that cries wolf is weakened by the next agent, so
                        // the fold is bounded by the header, not by the file.
                        //
                        // The paren and generic depths come from
                        // [headerBracketDepths]'s left-to-right walk over the
                        // whole folded text (computenet-zfdsw) rather than
                        // from unordered bracket totals — see that function's
                        // KDoc for the probes (S1-S3, o4/o5, P2/P3) this
                        // replaces three successive patches with. `by` and
                        // `->` remain plain trailing-token checks: neither is
                        // a bracket, so neither needs the depth walk.
                        val (parenDepth, genericDepth) = headerBracketDepths(folded)
                        val listContinues = trimmed.endsWith(",") ||
                            parenDepth > 0 ||
                            genericDepth > 0 ||
                            bindingHeaderInfixContinuation.containsMatchIn(trimmed) ||
                            bindingHeaderArrowContinuation.containsMatchIn(trimmed)
                        if (trimmed.contains("{") || !listContinues) {
                            if (bindingHeaderName.containsMatchIn(folded)) {
                                paths += relativePath
                            }
                            pendingHeader = null
                        }
                    }
                    return@forEachLine
                }
                val isImplementation = bindingSamConversion.containsMatchIn(content) ||
                    bindingSupertype.containsMatchIn(content) ||
                    bindingSupertypeSpacedColon.containsMatchIn(content)
                if (isImplementation) {
                    paths += relativePath
                } else if (bindingHeaderWrapStart.containsMatchIn(content)) {
                    pendingHeader = mutableListOf(content)
                }
            }
        }
        return paths
    }

    /** (repo-relative path, declared return type) for every `fun fingerprint(...)` declaration. */
    fun scanFingerprintDeclarations(root: File, moduleRoots: List<File>): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        eachKotlinFile(root, moduleRoots) { file, relativePath ->
            file.forEachLine { line ->
                val content = contentOrNull(line) ?: return@forEachLine
                val match = fingerprintDeclaration.find(content) ?: return@forEachLine
                found += relativePath to match.groupValues[1]
            }
        }
        return found
    }

    private fun parseBaseline(resource: File): Set<String> {
        val lines = resource.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val paths = mutableSetOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("#")) {
                index++
                continue
            }
            require(index + 1 < lines.size && lines[index + 1].startsWith("#")) {
                "Baseline entry '$line' has no why-comment on the following line"
            }
            paths += line
            index += 2
        }
        return paths
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    @Test
    fun `the interim key-to-identity binding has exactly one production site`() {
        val root = repoRoot()
        val moduleRoots = moduleMainRoots(root)
        assertTrue(moduleRoots.size >= 8) {
            "discovered only ${moduleRoots.size} module src/main/kotlin roots from settings.gradle.kts — " +
                "parser broken, or run from the wrong working directory?"
        }

        // (a) PeerId( constructions match the checked-in baseline exactly.
        val baselineFile = File(root, "kernel/src/test/resources/architecture/peerid-constructions.txt")
        assertTrue(baselineFile.isFile) { "Missing baseline resource: ${baselineFile.path}" }
        val baseline = parseBaseline(baselineFile)
        val actual = scanPeerIdConstructions(root, moduleRoots)

        val added = (actual - baseline).sorted()
        val removed = (baseline - actual).sorted()
        assertTrue(added.isEmpty() && removed.isEmpty()) {
            buildString {
                appendLine(
                    "PeerId( construction sites drifted from the baseline " +
                        "(kernel/src/test/resources/architecture/peerid-constructions.txt).",
                )
                if (added.isNotEmpty()) {
                    appendLine("Added (not in baseline):")
                    added.forEach { appendLine("  + $it") }
                }
                if (removed.isNotEmpty()) {
                    appendLine("Removed (in baseline, no longer in code — delete the stale entry by hand):")
                    removed.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine(
                    "civictech.cell.link.PeerIdentityBinding.Companion.Interim's KDoc: \"a new site that " +
                        "constructs an identity from a key, a KeyId or a fingerprint is forbidden\" — if an " +
                        "added site is a real key-to-identity derivation, route it through the binding instead. " +
                        "If it is an innocent token parse or configured name, add it to the baseline WITH its " +
                        "why-comment in the same PR, for the reviewer to judge.",
                )
            }
        }

        // (b) exactly one production PeerIdentityBinding implementation, and it is Identity.kt.
        val implementations = scanPeerIdentityBindingImplementations(root, moduleRoots)
        assertEquals(setOf("kernel/src/main/kotlin/civictech/cell/link/Identity.kt"), implementations) {
            "expected exactly one PeerIdentityBinding implementation (Identity.kt's Interim); found: $implementations"
        }

        // (c) exactly one fingerprint() declaration, returning KeyId.
        val fingerprints = scanFingerprintDeclarations(root, moduleRoots)
        assertEquals(1, fingerprints.size) {
            "expected exactly one fun fingerprint(...) declaration in production sources; found: $fingerprints"
        }
        assertEquals("KeyId", fingerprints.single().second) {
            "fingerprint() must return KeyId; found: ${fingerprints.single()}"
        }
    }

    /**
     * Non-vacuousness route (test-only task — no production edit is in this
     * claim to prove discrimination against, so the test carries its own
     * fixture). Builds a synthetic two-module tree with one legitimate
     * construction, one KDoc-only mention of the same text, and one stray
     * construction shaped exactly like the derivation the feature forbids
     * (`PeerId(fingerprint(key).name)`), and asserts [scanPeerIdConstructions]
     * reports the legitimate site and the stray, but never the KDoc mention —
     * so a scanner that matched every occurrence of the substring `PeerId(`
     * indiscriminately (which would also flag the KDoc line) is caught, and
     * a scanner that matched nothing (which would also miss the stray) is
     * caught too.
     */
    @Test
    fun `fixture self-check - the scanner flags a stray construction and ignores a KDoc mention`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-a")
            include(":fixture-b")
            """.trimIndent(),
        )

        val moduleADir = File(tempDir, "fixture-a/src/main/kotlin/fixture/a").apply { mkdirs() }
        val moduleBDir = File(tempDir, "fixture-b/src/main/kotlin/fixture/b").apply { mkdirs() }

        File(moduleADir, "Legit.kt").writeText(
            """
            package fixture.a

            /** Builds an identity from an asserted name (a configured-name style site). */
            class Legit {
                fun make(name: String) = PeerId(name)
            }
            """.trimIndent(),
        )

        File(moduleADir, "DocOnly.kt").writeText(
            """
            package fixture.a

            /**
             * Never write `PeerId(fingerprint(key).name)` here — see [Legit]
             * for the sanctioned shape instead.
             */
            class DocOnly
            """.trimIndent(),
        )

        File(moduleBDir, "Stray.kt").writeText(
            """
            package fixture.b

            class Stray {
                fun leak(key: KeyId) = PeerId(fingerprint(key).name)
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        assertEquals(2, moduleRoots.size) {
            "expected 2 fixture module roots, found $moduleRoots — moduleMainRoots is broken against this tree"
        }

        val actual = scanPeerIdConstructions(tempDir, moduleRoots)

        assertEquals(
            setOf(
                "fixture-a/src/main/kotlin/fixture/a/Legit.kt",
                "fixture-b/src/main/kotlin/fixture/b/Stray.kt",
            ),
            actual,
        ) {
            "scanner should report exactly the legitimate site and the stray, never the KDoc-only mention; found: $actual"
        }
    }

    /**
     * Non-vacuousness route for assertion (b)'s [bindingSupertype] regex
     * (test-only task — no production edit is in this claim to prove
     * discrimination against, so the test carries its own fixture, same
     * pattern as the `PeerId(` scanner's fixture self-check above).
     *
     * Pins the measured escape from `computenet-lusi`: a supertype
     * declaration with no whitespace before the colon AND a second
     * supertype after `PeerIdentityBinding` (`private class Escape:
     * PeerIdentityBinding, ProbeMarker {`) must still be recognised as an
     * implementation. In the SAME fixture, a type USAGE (`val binding:
     * PeerIdentityBinding = PeerIdentityBinding.Interim`) must NOT be
     * recognised — proving the widened regex does not trade the false
     * negative for a false positive on the type-usage shape the whitespace
     * requirement used to (incompletely) guard against.
     */
    @Test
    fun `fixture self-check - the binding scanner flags a no-space multi-supertype declaration and ignores a type usage`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-c")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-c/src/main/kotlin/fixture/c").apply { mkdirs() }

        File(moduleDir, "Escape.kt").writeText(
            """
            package fixture.c

            private interface ProbeMarker

            private class Escape: PeerIdentityBinding, ProbeMarker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        // Shapes the keyword-anchored regex alone cannot reach, in their OWN
        // file so this assertion discriminates: delete
        // [bindingSupertypeSpacedColon] and only this file drops out of the
        // expected set. Each has a trailing supertype after the interface
        // name, so [bindingSamConversion] does not see them either.
        File(moduleDir, "Parity.kt").writeText(
            """
            package fixture.c

            private class CtorEscape(private val n: Int) : PeerIdentityBinding, ProbeMarker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }

            @Suppress("unused") class AnnotatedEscape : PeerIdentityBinding, ProbeMarker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }

            private class Holder {
                companion object : PeerIdentityBinding, ProbeMarker {
                    override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
                }
            }
            """.trimIndent(),
        )

        File(moduleDir, "Usage.kt").writeText(
            """
            package fixture.c

            class Usage {
                fun make(binding: PeerIdentityBinding = PeerIdentityBinding.Interim): PeerIdentityBinding = binding
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        assertEquals(1, moduleRoots.size) {
            "expected 1 fixture module root, found $moduleRoots — moduleMainRoots is broken against this tree"
        }

        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        val expected = setOf(
            "fixture-c/src/main/kotlin/fixture/c/Escape.kt",
            "fixture-c/src/main/kotlin/fixture/c/Parity.kt",
        )
        assertEquals(expected, actual) {
            "scanner should report the no-space multi-supertype escape AND the constructor/annotation/anonymous-" +
                "object shapes, never the type-usage-only file; found: $actual"
        }
    }

    /**
     * Non-vacuousness route for assertion (b) (test-only task, computenet-6jkz —
     * no production edit is in this claim to prove discrimination against, so
     * the test carries its own fixture, same pattern as the other fixture
     * self-checks above).
     *
     * Pins the residual identified in the computenet-lusi review: a supertype
     * list wrapped onto its own line escapes all three predicates —
     * [bindingSupertype] and [bindingSupertypeSpacedColon] both require the
     * interface name and a preceding colon on the SAME physical line, and
     * [bindingSamConversion] requires the interface name immediately followed
     * by `{`. A declaration shaped
     * ```
     * class Escape :
     *     PeerIdentityBinding,
     *     Marker {
     * ```
     * has none of that on one line: line 1 has the colon but not the
     * interface name, line 2 has the interface name but no colon, keyword or
     * brace, line 3 has the brace but not the interface name.
     */
    @Test
    fun `fixture self-check - the binding scanner flags a supertype list wrapped onto its own line`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-d")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-d/src/main/kotlin/fixture/d").apply { mkdirs() }

        File(moduleDir, "Escape.kt").writeText(
            """
            package fixture.d

            private interface Marker

            class Escape :
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        assertEquals(1, moduleRoots.size) {
            "expected 1 fixture module root, found $moduleRoots — moduleMainRoots is broken against this tree"
        }

        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-d/src/main/kotlin/fixture/d/Escape.kt"), actual) {
            "scanner should report the declaration whose supertype list is wrapped onto its own line " +
                "(class/object header on one line, PeerIdentityBinding on the next); found: $actual"
        }
    }

    /**
     * The false-positive half of the wrapped-header fold (computenet-6jkz
     * review). A ratchet that flags innocent code is a worse instrument than
     * one that misses a shape, because the next agent weakens it — so the fold
     * opened by [bindingHeaderWrapStart] has to stop at the end of the
     * supertype list, not at the next `{` anywhere in the file.
     *
     * The shape is real: `SetCell.kt:273` wraps its supertype list exactly
     * this way. A body-less variant
     * ```
     * class Wrapped :
     *     Base()
     * ```
     * ends the header without ever carrying a brace, so a fold-until-brace
     * scan runs on into the file and, on the first later line that happens to
     * carry both a `{` and the interface name — an ordinary
     * `fun consume(binding: PeerIdentityBinding) {` type usage — flags the
     * file as an implementation site. Measured: it did.
     */
    @Test
    fun `fixture self-check - the header fold stops at the end of the supertype list`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-e")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-e/src/main/kotlin/fixture/e").apply { mkdirs() }

        File(moduleDir, "Wrapped.kt").writeText(
            """
            package fixture.e

            private open class Base

            class Wrapped :
                Base()

            fun consume(binding: PeerIdentityBinding) {
                println(binding)
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(emptySet<String>(), actual) {
            "a class header ending bare at the colon with a body-less supertype must not fold " +
                "forward into an unrelated type usage; found: $actual"
        }
    }

    /**
     * The other side of the same bound: stopping the fold at the first line
     * that does not end in a comma must not lose a supertype list whose FIRST
     * entry is a constructor call spanning several lines. The fold continues
     * while the header's parentheses are unbalanced.
     */
    @Test
    fun `fixture self-check - the header fold spans a multi-line supertype constructor call`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-f")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-f/src/main/kotlin/fixture/f").apply { mkdirs() }

        File(moduleDir, "Spanning.kt").writeText(
            """
            package fixture.f

            private open class Base(val n: Int)

            class Spanning :
                Base(
                    1,
                ),
                PeerIdentityBinding {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-f/src/main/kotlin/fixture/f/Spanning.kt"), actual) {
            "the fold must span an unbalanced supertype constructor call and still see the " +
                "interface name later in the same header; found: $actual"
        }
    }

    /**
     * Follow-up from the computenet-6jkz review (computenet-3dt4t): the fold's
     * continuation test — a trailing comma, or an unbalanced '(' — stops early
     * on a supertype list broken by a multi-line generic type argument, so a
     * later `PeerIdentityBinding` entry in the SAME list is never seen. The
     * SAM regex ([bindingSamConversion]) cannot rescue this: it only matches
     * when the interface name is immediately followed by `{`, which is not
     * the case here.
     */
    @Test
    fun `fixture self-check - the header fold spans a multi-line generic supertype argument`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-g")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-g/src/main/kotlin/fixture/g").apply { mkdirs() }

        File(moduleDir, "Generic.kt").writeText(
            """
            package fixture.g

            private interface Marker

            class Generic :
                Comparable<
                    String
                >,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
                override fun compareTo(other: String): Int = 0
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-g/src/main/kotlin/fixture/g/Generic.kt"), actual) {
            "the fold must span a multi-line generic supertype argument and still see the " +
                "interface name later in the same header; found: $actual"
        }
    }

    /**
     * Follow-up from the computenet-6jkz review (computenet-3dt4t): the same
     * early-stop, this time from a delegation split across the `by` keyword
     * ("Base by\n    delegate,") rather than a generic argument.
     */
    @Test
    fun `fixture self-check - the header fold spans a supertype delegation split across 'by'`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-h")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-h/src/main/kotlin/fixture/h").apply { mkdirs() }

        File(moduleDir, "Delegating.kt").writeText(
            """
            package fixture.h

            private interface Marker
            private interface Base

            class Delegating(delegate: Base) :
                Base by
                    delegate,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-h/src/main/kotlin/fixture/h/Delegating.kt"), actual) {
            "the fold must span a supertype delegation split across 'by' and still see the " +
                "interface name later in the same header; found: $actual"
        }
    }

    /**
     * Guards the fix above against the naive mistake it must not make: a
     * function-type supertype ("(Int) -> Unit") contributes a lone '>' via
     * `->` and no '<' at all, so a continuation test that checked bracket
     * counts were merely UNEQUAL (rather than opens strictly outnumbering
     * closes) would misread it as an unbalanced generic and fold forward
     * past the header, sweeping the unrelated `PeerIdentityBinding` type
     * usage below into the match.
     */
    @Test
    fun `fixture self-check - a function-type supertype does not open a runaway fold`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-i")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-i/src/main/kotlin/fixture/i").apply { mkdirs() }

        File(moduleDir, "Wrapped.kt").writeText(
            """
            package fixture.i

            class WrappedFn :
                (Int) ->
                    Unit

            fun consume(binding: PeerIdentityBinding) {
                println(binding)
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(emptySet<String>(), actual) {
            "a function-type supertype must not fold forward into an unrelated type usage; found: $actual"
        }
    }

    /**
     * PROBE P2 (computenet-s8ige): a multi-line generic supertype argument
     * whose closing '>' shares a physical line with a function-type arrow
     * ("(Int) -> Unit"). The arrow's own '>' feeds the same unordered total
     * as the generic's real closing bracket, so the counts can balance one
     * line BEFORE the generic actually closes, and the fold dies early —
     * losing the later `PeerIdentityBinding` entry in the same supertype
     * list. Measured 2026-09-12 in the computenet-3dt4t review.
     */
    @Test
    fun `fixture self-check - the header fold spans a multi-line generic supertype argument holding a function type`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-j")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-j/src/main/kotlin/fixture/j").apply { mkdirs() }

        File(moduleDir, "Mixed.kt").writeText(
            """
            package fixture.j

            private interface Handler<T>
            private interface Marker

            class Mixed :
                Handler<
                    (Int) -> Unit
                >,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-j/src/main/kotlin/fixture/j/Mixed.kt"), actual) {
            "the fold must span a multi-line generic supertype argument whose closing '>' shares a line " +
                "with a function-type arrow, and still see the interface name later in the same header; " +
                "found: $actual"
        }
    }

    /**
     * PROBE P3 (computenet-s8ige): a supertype list split across the arrow
     * of a function-type supertype itself ("(Int) ->" / "    Unit,"). The
     * continuation test recognised a trailing comma or an unbalanced
     * bracket/`by`, but not a line ending in `->` — so the fold dies on
     * "(Int) ->" ('<'=0, '>'=1 from the arrow) and the later
     * `PeerIdentityBinding` entry in the same list is never seen. Measured
     * 2026-09-12 in the computenet-3dt4t review.
     */
    @Test
    fun `fixture self-check - the header fold spans a supertype list split across the function arrow`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-k")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-k/src/main/kotlin/fixture/k").apply { mkdirs() }

        File(moduleDir, "FnSplit.kt").writeText(
            """
            package fixture.k

            private interface Marker

            class FnSplit :
                (Int) ->
                    Unit,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-k/src/main/kotlin/fixture/k/FnSplit.kt"), actual) {
            "the fold must span a supertype list split across the function arrow itself, and still see " +
                "the interface name later in the same header; found: $actual"
        }
    }

    /**
     * PROBE o5 (computenet-omm5p): a body-less header whose supertype
     * constructor call contains a LESS-THAN OPERATOR ("Base(a < b)"), not a
     * generic. The continuation test counted every '<' in the folded text as
     * if it opened a generic, so this header — which never carries a brace —
     * never balances, and the fold runs forward into the unrelated
     * `PeerIdentityBinding` type usage below. Measured 2026-09-13 in
     * worktree computenet-s8ige (both halves of the s8ige fix reverted, and
     * at d6be2ec35 alike): flagged.
     */
    @Test
    fun `fixture self-check - a less-than operator in a body-less header's supertype call does not open a runaway fold`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-l")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-l/src/main/kotlin/fixture/l").apply { mkdirs() }

        File(moduleDir, "Wrapped.kt").writeText(
            """
            package fixture.l

            private open class Base(a: Int, b: Int)

            class Wrapped :
                Base(a < b)

            fun consume(binding: PeerIdentityBinding) {
                println(binding)
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(emptySet<String>(), actual) {
            "a less-than operator inside a body-less header's supertype constructor call must not open a " +
                "runaway fold into an unrelated type usage; found: $actual"
        }
    }

    /**
     * PROBE o4 (computenet-omm5p): the same less-than operator alongside a
     * function-type argument in the same constructor call
     * ("Base(a < b, null as (() -> Unit)?)"). Before the arrow-aware
     * generic-close exclusion ([genericCloseNotArrow], computenet-s8ige),
     * the arrow's own '>' happened to balance the operator's '<' by
     * coincidence; excluding it unmasks the same pre-existing escape as the
     * o5 probe above, on a shape that also carries a function-type argument.
     */
    @Test
    fun `fixture self-check - a less-than operator alongside a function-type argument does not open a runaway fold`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-m")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-m/src/main/kotlin/fixture/m").apply { mkdirs() }

        File(moduleDir, "Wrapped.kt").writeText(
            """
            package fixture.m

            private open class Base(a: Int, b: Int, f: (() -> Unit)?)

            class Wrapped :
                Base(a < b, null as (() -> Unit)?)

            fun consume(binding: PeerIdentityBinding) {
                println(binding)
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(emptySet<String>(), actual) {
            "a less-than operator alongside a function-type supertype argument must not open a runaway " +
                "fold into an unrelated type usage; found: $actual"
        }
    }

    /**
     * PROBE S1 (computenet-zfdsw): a multi-line generic supertype argument
     * whose open bracket is SPACED ("Handler <" rather than "Handler<"). The
     * unordered-total heuristic's generic-open count ([genericOpenNotOperator]
     * at the time this was measured) only credited a '<' immediately
     * following an identifier character with no whitespace, so a spaced open
     * never registered — the fold's counts stayed balanced (0 opens, 0 real
     * closes) after the first continuation line and the header was read as
     * closed one line early, losing the later `PeerIdentityBinding` entry in
     * the same supertype list. A silent false negative introduced by
     * computenet-omm5p; measured 2026-09-13 against 65756a102 (this bead's
     * base commit): NOT flagged.
     */
    @Test
    fun `fixture self-check - a spaced generic open still spans the fold to a later PeerIdentityBinding entry`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-n")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-n/src/main/kotlin/fixture/n").apply { mkdirs() }

        File(moduleDir, "Spaced.kt").writeText(
            """
            package fixture.n

            private interface Marker

            class Spaced :
                Handler <
                    String
                >,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-n/src/main/kotlin/fixture/n/Spaced.kt"), actual) {
            "the fold must span a SPACED multi-line generic supertype open and still see the interface " +
                "name later in the same header; found: $actual"
        }
    }

    /**
     * PROBE S2 (computenet-zfdsw): a greater-than COMPARISON OPERATOR
     * ("Base(a > b),") ahead of a later multi-line generic supertype
     * argument in the same list. The unordered-total heuristic fed every
     * '>' not immediately preceded by '-' into the generic-close count
     * regardless of where it occurred, so the operator's '>' credited a
     * close before the generic's own open had even been seen; by the time
     * the real generic's '<' arrived the counts could read as balanced one
     * line early, ending the fold before the later `PeerIdentityBinding`
     * entry. Pre-existing (not introduced by computenet-omm5p); measured
     * 2026-09-13 against 65756a102: NOT flagged.
     */
    @Test
    fun `fixture self-check - a greater-than operator ahead of a later generic still spans the fold`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-o")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-o/src/main/kotlin/fixture/o").apply { mkdirs() }

        File(moduleDir, "Mixed.kt").writeText(
            """
            package fixture.o

            private interface Marker
            private open class Base(a: Int, b: Int)

            class Mixed :
                Base(a > b),
                Handler<
                    String
                >,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-o/src/main/kotlin/fixture/o/Mixed.kt"), actual) {
            "the fold must span a later multi-line generic even after an earlier greater-than operator " +
                "entry, and still see the interface name later in the same header; found: $actual"
        }
    }

    /**
     * PROBE S3 (computenet-zfdsw): an UNSPACED less-than operator inside a
     * body-less header's supertype constructor call ("Base(a<b)",
     * "Base(a< b)") still opens a runaway fold, exactly like PROBEs o5/o4
     * but without the space before '<' that those relied on to distinguish
     * the operator from a generic open under the old unordered-total
     * heuristic — the heuristic never distinguished by paren nesting at
     * all, only by whether '<' immediately followed an identifier
     * character, so an unspaced operator use was indistinguishable from a
     * real generic open either way. Deliberately does NOT end the header
     * with "PeerIdentityBinding {" — that shape is caught directly by
     * [bindingSamConversion] outside the fold and would mask whether the
     * fold itself over-matches. Pre-existing on both sides of
     * computenet-omm5p; measured 2026-09-13 against 65756a102: FLAGGED
     * (should be empty).
     */
    @Test
    fun `fixture self-check - an unspaced less-than operator does not open a runaway fold`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-p")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-p/src/main/kotlin/fixture/p").apply { mkdirs() }

        File(moduleDir, "Wrapped.kt").writeText(
            """
            package fixture.p

            private open class Base(a: Int, b: Int)

            class Wrapped1 :
                Base(a<b)

            class Wrapped2 :
                Base(a< b)

            fun consume(binding: PeerIdentityBinding) {
                println(binding)
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(emptySet<String>(), actual) {
            "an unspaced less-than operator inside a body-less header's supertype constructor call must " +
                "not open a runaway fold into an unrelated type usage; found: $actual"
        }
    }

    /**
     * Pins [headerBracketDepths]'s paren-depth guard on a CLOSING '>'
     * (computenet-zfdsw review). Fixture o does not: its operator '>' arrives
     * while generic depth is still zero, so the zero clamp alone already
     * ignores it, and every other fixture stayed green with the guard
     * removed. The guard is load-bearing when a '>' inside parens arrives
     * while a top-level generic is OPEN — a generic type argument inside a
     * function-type parameter list ("Handler<" / "(List<Int>) -> Unit" /
     * ">,"): its '<' is skipped at paren depth 1, so without the guard its
     * '>' closes the OUTER generic, depth reads 0 on the middle line, and the
     * fold ends before the later `PeerIdentityBinding` entry. Measured
     * 2026-09-13: flagged with the guard, NOT flagged without it.
     */
    @Test
    fun `fixture self-check - a generic inside a function-type parameter does not close the enclosing generic`(
        @TempDir tempDir: File,
    ) {
        File(tempDir, "settings.gradle.kts").writeText(
            """
            include(":fixture-q")
            """.trimIndent(),
        )

        val moduleDir = File(tempDir, "fixture-q/src/main/kotlin/fixture/q").apply { mkdirs() }

        File(moduleDir, "Nested.kt").writeText(
            """
            package fixture.q

            private interface Handler<T>
            private interface Marker

            class Nested :
                Handler<
                    (List<Int>) -> Unit
                >,
                PeerIdentityBinding,
                Marker {
                override fun identityOf(key: KeyId): PeerId = error("probe body constructs no PeerId")
            }
            """.trimIndent(),
        )

        val moduleRoots = moduleMainRoots(tempDir)
        val actual = scanPeerIdentityBindingImplementations(tempDir, moduleRoots)

        assertEquals(setOf("fixture-q/src/main/kotlin/fixture/q/Nested.kt"), actual) {
            "a '>' inside a function-type parameter list must not close the enclosing top-level generic " +
                "supertype; the fold must still see the interface name later in the same header; found: $actual"
        }
    }
}
