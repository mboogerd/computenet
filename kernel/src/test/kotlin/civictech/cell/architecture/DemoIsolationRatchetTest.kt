package civictech.cell.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * T10 guardrail: demo applications may use any kernel package, but an
 * application's main sources may not import another application's package and
 * its build script may not depend on another application.
 *
 * The demo module list comes from `settings.gradle.kts`; package ownership is
 * derived from `package` declarations in each module's `src/main/kotlin` tree.
 * `:demo:shell` is shared infrastructure and is therefore exempt. The
 * intentional `deliberate`/`dialogue` -> `agora` edges are the only current
 * exceptions and live in the shrink-only allowlist resource.
 */
class DemoIsolationRatchetTest {

    private data class DemoModule(
        val projectPath: String,
        val directory: File,
        val mainSources: List<File>,
        val packages: Set<String>,
    )

    private data class Edge(val kind: String, val source: String, val target: String) {
        override fun toString(): String = "$kind|$source|$target"
    }

    companion object {
        private const val SHARED_SHELL = ":demo:shell"
        private val demoInclude = Regex("""include\(\s*"(:demo:[^"]+)"\s*\)""")
        private val projectInvocation = Regex("""(?s)\bproject\s*\((.*?)\)""")
        private val positionalProjectPath = Regex("""^\s*"(:demo:[^"]+)"""")
        private val namedProjectPath = Regex("""\bpath\s*=\s*"(:demo:[^"]+)"""")
        private val packageDeclaration = Regex("""(?m)^\s*package\s+(civictech(?:\.[A-Za-z_]\w*)+)""")
        private val importDeclaration = Regex("""(?m)^\s*import\s+(civictech(?:\.[A-Za-z_]\w*)+)""")
        private val packageDirective = Regex("""(?m)^\s*(?:package|import)\b[^\r\n]*""")
        private val qualifiedPackageReference = Regex(
            """\bcivictech\s*\.\s*([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)""",
        )
        private val whitespace = Regex("""\s+""")
        private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val lineComment = Regex("""//[^\n]*""")

        /**
         * Retain Kotlin code while blanking comments, character literals and string
         * contents. Code inside `${'$'}{...}` string templates is retained: it is an
         * executable reference even though the surrounding literal is not. Newlines
         * stay in place so declaration-line filtering cannot join unrelated tokens.
         */
        private fun codeOnly(source: String): String {
            val code = CharArray(source.length) { index -> if (source[index] == '\n') '\n' else ' ' }

            fun scanCode(start: Int, stopAtTemplateEnd: Boolean): Int {
                var index = start
                var nestedBraces = 0

                fun skipBlockComment(commentStart: Int): Int {
                    var cursor = commentStart + 2
                    var depth = 1
                    while (cursor < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", cursor) -> {
                                depth++
                                cursor += 2
                            }
                            source.startsWith("*/", cursor) -> {
                                depth--
                                cursor += 2
                            }
                            else -> cursor++
                        }
                    }
                    return cursor
                }

                fun skipCharacter(characterStart: Int): Int {
                    var cursor = characterStart + 1
                    while (cursor < source.length) {
                        when (source[cursor]) {
                            '\\' -> cursor = (cursor + 2).coerceAtMost(source.length)
                            '\'' -> return cursor + 1
                            else -> cursor++
                        }
                    }
                    return cursor
                }

                fun skipBacktickIdentifier(identifierStart: Int): Int {
                    var cursor = identifierStart + 1
                    while (cursor < source.length) {
                        if (source[cursor] == '`') return cursor + 1
                        cursor++
                    }
                    return cursor
                }

                fun skipString(stringStart: Int, raw: Boolean): Int {
                    var cursor = stringStart + if (raw) 3 else 1
                    while (cursor < source.length) {
                        when {
                            source.startsWith("${'$'}{", cursor) -> cursor = scanCode(cursor + 2, true)
                            raw && source[cursor] == '"' -> {
                                var quoteRunEnd = cursor
                                while (quoteRunEnd < source.length && source[quoteRunEnd] == '"') quoteRunEnd++
                                if (quoteRunEnd - cursor >= 3) return quoteRunEnd
                                cursor = quoteRunEnd
                            }
                            !raw && source[cursor] == '\\' -> cursor = (cursor + 2).coerceAtMost(source.length)
                            !raw && source[cursor] == '"' -> return cursor + 1
                            else -> cursor++
                        }
                    }
                    return cursor
                }

                while (index < source.length) {
                    when {
                        source.startsWith("//", index) -> {
                            val newline = source.indexOf('\n', index + 2)
                            index = if (newline == -1) source.length else newline
                        }
                        source.startsWith("/*", index) -> index = skipBlockComment(index)
                        source.startsWith("\"\"\"", index) -> index = skipString(index, raw = true)
                        source[index] == '"' -> index = skipString(index, raw = false)
                        source[index] == '\'' -> index = skipCharacter(index)
                        source[index] == '`' -> index = skipBacktickIdentifier(index)
                        stopAtTemplateEnd && source[index] == '}' && nestedBraces == 0 -> return index + 1
                        else -> {
                            if (stopAtTemplateEnd) {
                                when (source[index]) {
                                    '{' -> nestedBraces++
                                    '}' -> nestedBraces--
                                }
                            }
                            code[index] = source[index]
                            index++
                        }
                    }
                }
                return index
            }

            scanCode(0, stopAtTemplateEnd = false)
            return code.concatToString()
        }

        /** Strip comments before reading Gradle settings declarations. */
        private fun stripComments(source: String): String =
            source.replace(blockComment, "").replace(lineComment, "")

        private fun projectDependencies(source: String): List<String> {
            val code = codeOnly(source)
            return projectInvocation.findAll(code).flatMap { match ->
                val invocation = source.substring(match.range)
                val body = invocation.substringAfter('(').substringBeforeLast(')')
                listOfNotNull(
                    positionalProjectPath.find(body)?.groupValues?.get(1),
                    namedProjectPath.find(body)?.groupValues?.get(1),
                )
            }.toList()
        }
    }

    private fun repoRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return directory
    }

    private fun mainKotlinFiles(sourceRoot: File): List<File> =
        if (!sourceRoot.isDirectory) emptyList()
        else sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun packageDeclarations(files: List<File>): Set<String> =
        files.flatMap { file ->
            packageDeclaration.findAll(codeOnly(file.readText())).map { it.groupValues[1] }.toList()
        }.toSet()

    private fun moduleFromDirectory(root: File, projectPath: String): DemoModule {
        val directory = File(root, projectPath.removePrefix(":").replace(':', File.separatorChar))
        val sources = mainKotlinFiles(File(directory, "src/main/kotlin"))
        return DemoModule(projectPath, directory, sources, packageDeclarations(sources))
    }

    private fun demoModules(root: File): List<DemoModule> {
        val settings = File(root, "settings.gradle.kts")
        assertTrue(settings.isFile) { "Missing settings.gradle.kts under ${root.path}" }
        return demoInclude.findAll(stripComments(settings.readText()))
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .map { moduleFromDirectory(root, it) }
            .toList()
    }

    private fun parseAllowlist(file: File): Pair<Set<Edge>, List<String>> {
        val edges = mutableSetOf<Edge>()
        val malformed = mutableListOf<String>()
        file.readLines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val fields = line.split('|')
            if (fields.size != 3 || fields.any { it.isBlank() } || fields[0] !in setOf("import", "dependency")) {
                malformed += "${file.path}:${index + 1}: malformed allowlist entry '$line'"
            } else {
                edges += Edge(fields[0], fields[1], fields[2])
            }
        }
        return edges to malformed
    }

    private fun ownerOf(importedPackage: String, packageOwners: Map<String, String>): String? =
        packageOwners.entries
            .filter { (declaredPackage, _) ->
                importedPackage == declaredPackage || importedPackage.startsWith("$declaredPackage.")
            }
            .maxByOrNull { it.key.length }
            ?.value

    private fun scan(root: File, modules: List<DemoModule>, allowlist: Set<Edge>): List<String> {
        val packageOwners = modules
            .flatMap { module -> module.packages.map { declaredPackage -> declaredPackage to module.projectPath } }
            .toMap()
        val knownProjects = modules.map { it.projectPath }.toSet()
        val observedEdges = mutableSetOf<Edge>()
        val violations = mutableListOf<String>()

        modules.filterNot { it.projectPath == SHARED_SHELL }.forEach { module ->
            module.mainSources.forEach { source ->
                val relativePath = source.relativeTo(root).invariantSeparatorsPath
                val code = codeOnly(source.readText())
                val codeWithoutDirectives = packageDirective.replace(code) { " ".repeat(it.value.length) }
                val importedPackages = sequence {
                    yieldAll(importDeclaration.findAll(code).map { it.groupValues[1] })
                    yieldAll(
                        qualifiedPackageReference.findAll(codeWithoutDirectives).map { match ->
                            "civictech." + match.groupValues[1].replace(whitespace, "")
                        },
                    )
                }.toSet()
                importedPackages.forEach { importedPackage ->
                    val target = ownerOf(importedPackage, packageOwners)
                    if (target != null && target != module.projectPath && target != SHARED_SHELL) {
                        val edge = Edge("import", module.projectPath, target)
                        if (observedEdges.add(edge) && edge !in allowlist) {
                            violations += "$relativePath: imports $importedPackage from $target, " +
                                "but ${edge} is not on the shrink-only allowlist"
                        }
                    }
                }
            }

            val buildFile = File(module.directory, "build.gradle.kts")
            if (!buildFile.isFile) {
                violations += "${module.projectPath} has no build.gradle.kts to inspect"
            } else {
                projectDependencies(buildFile.readText()).forEach { target ->
                    if (target == SHARED_SHELL) return@forEach

                    val edge = Edge("dependency", module.projectPath, target)
                    if (observedEdges.add(edge)) {
                        if (target !in knownProjects) {
                            violations += "${buildFile.relativeTo(root).invariantSeparatorsPath}: $edge names " +
                                "a demo project not listed in settings.gradle.kts"
                        }
                        if (edge !in allowlist) {
                            violations += "${buildFile.relativeTo(root).invariantSeparatorsPath}: $edge is not on " +
                                "the shrink-only allowlist"
                        }
                    }
                }
            }
        }

        allowlist.filter { it !in observedEdges }.forEach { edge ->
            violations += "stale allowlist entry $edge has no matching cross-demo edge — remove it (shrink-only)"
        }
        return violations.sorted()
    }

    @Test
    fun `demo applications do not import or depend on another demo application`() {
        val root = repoRoot()
        val modules = demoModules(root)
        val applicationModules = modules.filterNot { it.projectPath == SHARED_SHELL }

        assertTrue(modules.size >= 10) {
            "found only ${modules.size} :demo projects in settings.gradle.kts — the module scan is broken"
        }
        assertTrue(modules.any { it.projectPath == SHARED_SHELL }) {
            "settings.gradle.kts no longer lists the shared :demo:shell module"
        }
        assertTrue(applicationModules.all { it.mainSources.isNotEmpty() }) {
            "at least one demo application has no src/main/kotlin files"
        }
        assertTrue(applicationModules.all { it.packages.isNotEmpty() }) {
            "at least one demo application has no package declarations in its main sources"
        }

        val allowlistFile = File(root, "kernel/src/test/resources/architecture/demo-isolation-allowlist.txt")
        assertTrue(allowlistFile.isFile) { "Missing allowlist resource: ${allowlistFile.path}" }
        val (allowlist, malformed) = parseAllowlist(allowlistFile)
        val violations = malformed + scan(root, modules, allowlist)

        assertTrue(violations.isEmpty()) {
            "demo isolation ratchet violated (imports and build dependencies may not cross " +
                "application boundaries; :demo:shell is shared infrastructure):\n" +
                violations.joinToString("\n") { "  $it" }
        }
    }

    @Test
    fun `scratch import of another demo package is rejected`() {
        val root = Files.createTempDirectory("demo-isolation-import").toFile()
        try {
            val producer = writeScratchModule(root, ":demo:producer", "civictech.producer")
            val consumer = writeScratchModule(
                root,
                ":demo:consumer",
                "civictech.consumer",
                sourceBody = "import civictech.producer.Secret\nclass Consumer",
            )

            val violations = scan(root, listOf(producer, consumer), emptySet())

            assertEquals(1, violations.size)
            assertTrue(violations.single().contains("import|:demo:consumer|:demo:producer")) {
                "scratch import did not produce the expected cross-demo violation: $violations"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scratch project dependency on another demo is rejected`() {
        val root = Files.createTempDirectory("demo-isolation-dependency").toFile()
        try {
            val producer = writeScratchModule(root, ":demo:producer", "civictech.producer")
            val consumer = writeScratchModule(
                root,
                ":demo:consumer",
                "civictech.consumer",
                buildBody = "dependencies { implementation(project(\":demo:producer\")) }",
            )

            val violations = scan(root, listOf(producer, consumer), emptySet())

            assertEquals(1, violations.size)
            assertTrue(violations.single().contains("dependency|:demo:consumer|:demo:producer")) {
                "scratch project dependency did not produce the expected cross-demo violation: $violations"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scratch two argument project dependency on another demo is rejected`() {
        val root = Files.createTempDirectory("demo-isolation-two-argument-dependency").toFile()
        try {
            val producer = writeScratchModule(root, ":demo:producer", "civictech.producer")
            val consumer = writeScratchModule(
                root,
                ":demo:consumer",
                "civictech.consumer",
                buildBody = "dependencies { implementation(project(\":demo:producer\", \"default\")) }",
            )

            val violations = scan(root, listOf(producer, consumer), emptySet())

            assertEquals(1, violations.size)
            assertTrue(violations.single().contains("dependency|:demo:consumer|:demo:producer")) {
                "scratch two-argument project dependency did not produce the expected cross-demo violation: $violations"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scratch named path project dependency on another demo is rejected`() {
        val root = Files.createTempDirectory("demo-isolation-named-path-dependency").toFile()
        try {
            val producer = writeScratchModule(root, ":demo:producer", "civictech.producer")
            val consumer = writeScratchModule(
                root,
                ":demo:consumer",
                "civictech.consumer",
                buildBody = "dependencies { implementation(project(path = \":demo:producer\")) }",
            )

            val violations = scan(root, listOf(producer, consumer), emptySet())

            assertEquals(1, violations.size)
            assertTrue(violations.single().contains("dependency|:demo:consumer|:demo:producer")) {
                "scratch named-path project dependency did not produce the expected cross-demo violation: $violations"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scratch fully qualified reference to another demo package is rejected`() {
        val root = Files.createTempDirectory("demo-isolation-qualified-reference").toFile()
        try {
            val producer = writeScratchModule(root, ":demo:producer", "civictech.producer")
            val consumer = writeScratchModule(
                root,
                ":demo:consumer",
                "civictech.consumer",
                sourceBody = """
                    // civictech.producer.CommentOnly
                    val text = "civictech.producer.StringOnly"
                    class Consumer { val secret = civictech.producer.Secret() }
                """.trimIndent(),
            )

            val violations = scan(root, listOf(producer, consumer), emptySet())

            assertEquals(1, violations.size)
            assertTrue(violations.single().contains("import|:demo:consumer|:demo:producer")) {
                "scratch fully qualified reference did not produce the expected cross-demo violation: $violations"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeScratchModule(
        root: File,
        projectPath: String,
        packageName: String,
        sourceBody: String = "class Demo",
        buildBody: String = "dependencies { }",
    ): DemoModule {
        val moduleDirectory = File(root, projectPath.removePrefix(":").replace(':', File.separatorChar))
        val sourceDirectory = File(moduleDirectory, "src/main/kotlin/$packageName")
        sourceDirectory.mkdirs()
        File(sourceDirectory, "Demo.kt").writeText("package $packageName\n$sourceBody\n")
        File(moduleDirectory, "build.gradle.kts").writeText(buildBody)
        return moduleFromDirectory(root, projectPath)
    }
}
