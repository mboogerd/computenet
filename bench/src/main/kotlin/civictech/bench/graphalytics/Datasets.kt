package civictech.bench.graphalytics

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The algorithms and validation datasets shipped with the Graphalytics fixture. */
object Datasets {

    val EXAMPLE_DIRECTED = DatasetRef("example-directed", "/graphalytics/example-directed")
    val EXAMPLE_UNDIRECTED = DatasetRef("example-undirected", "/graphalytics/example-undirected")

    fun all(): List<DatasetRef> = listOf(EXAMPLE_DIRECTED, EXAMPLE_UNDIRECTED)

    /** Return the expected output tokens without applying an algorithm's comparison rule. */
    fun referenceOutput(dataset: DatasetRef, algorithm: Algorithm): Map<Long, String> {
        val output = dataset.open("${dataset.name}-${algorithm.suffix}")
        return output.bufferedReader().useLines { lines ->
            lines
                .map(String::trim)
                .filter(String::isNotEmpty)
                .associate { line ->
                    val fields = line.split(Regex("\\s+"), limit = 3)
                    require(fields.size == 2) {
                        "Malformed ${algorithm.suffix} output for ${dataset.name}: '$line'"
                    }
                    fields[0].toLong() to fields[1]
                }
        }
    }
}

/** A Graphalytics dataset directory, normally rooted in the runtime classpath. */
data class DatasetRef(val name: String, val resourceDir: String) {

    /**
     * Open a file in this dataset. The filesystem branch is intentionally useful for
     * loader tests that mutate a scratch copy; the checked-in datasets always use the
     * classpath branch above it.
     */
    fun open(fileName: String): InputStream {
        val classpathDir = "/${resourceDir.trimStart('/').trimEnd('/')}"
        Datasets::class.java.getResourceAsStream("$classpathDir/$fileName")?.let { return it }

        val file = Paths.get(resourceDir).resolve(fileName).normalize()
        if (Files.isRegularFile(file)) return Files.newInputStream(file)

        throw IllegalArgumentException(
            "Missing Graphalytics resource '$fileName' for dataset '$name' " +
                "under '$resourceDir'",
        )
    }
}

/** The suffix used by the upstream validation-output files. */
enum class Algorithm(val suffix: String) {
    BFS("BFS"),
    CDLP("CDLP"),
    LCC("LCC"),
    PR("PR"),
    SSSP("SSSP"),
    WCC("WCC"),
}
