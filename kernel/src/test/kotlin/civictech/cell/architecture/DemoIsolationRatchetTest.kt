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
        private val projectDependency = Regex("""project\(\s*"(:demo:[^"]+)"\s*\)""")
        private val packageDeclaration = Regex("""(?m)^\s*package\s+(civictech(?:\.[A-Za-z_]\w*)+)""")
        private val importDeclaration = Regex("""(?m)^\s*import\s+(civictech(?:\.[A-Za-z_]\w*)+)""")
        private val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val lineComment = Regex("""//[^\n]*""")

        /** Strip comments before reading declarations from Kotlin or Gradle source. */
        private fun stripComments(source: String): String =
            source.replace(blockComment, "").replace(lineComment, "")
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
            packageDeclaration.findAll(stripComments(file.readText())).map { it.groupValues[1] }.toList()
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
                importDeclaration.findAll(stripComments(source.readText())).forEach { match ->
                    val importedPackage = match.groupValues[1]
                    val target = ownerOf(importedPackage, packageOwners)
                    if (target != null && target != module.projectPath && target != SHARED_SHELL) {
                        val edge = Edge("import", module.projectPath, target)
                        observedEdges += edge
                        if (edge !in allowlist) {
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
                projectDependency.findAll(stripComments(buildFile.readText())).forEach { match ->
                    val target = match.groupValues[1]
                    if (target == SHARED_SHELL) return@forEach

                    val edge = Edge("dependency", module.projectPath, target)
                    observedEdges += edge
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
