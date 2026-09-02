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
            file.forEachLine { line ->
                val content = contentOrNull(line) ?: return@forEachLine
                if (bindingInterfaceDeclaration.containsMatchIn(content)) return@forEachLine
                val isImplementation = bindingSamConversion.containsMatchIn(content) ||
                    bindingSupertype.containsMatchIn(content) ||
                    bindingSupertypeSpacedColon.containsMatchIn(content)
                if (isImplementation) {
                    paths += relativePath
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
}
