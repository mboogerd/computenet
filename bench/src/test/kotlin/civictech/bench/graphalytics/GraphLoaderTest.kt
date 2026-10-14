package civictech.bench.graphalytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GraphLoaderTest {

    @Test
    fun `loads the directed example and every declared parameter`() {
        val graph = GraphLoader.load(Datasets.EXAMPLE_DIRECTED)

        assertTrue(graph.directed)
        assertFalse(graph.symmetrised)
        assertEquals(10, graph.vertices.size)
        assertEquals(17, graph.edges.size)
        assertEquals(10, graph.declaredVertices)
        assertEquals(17, graph.declaredEdges)
        assertEquals(17, graph.weights?.size)
        assertEquals(
            GraphParameters(
                bfsSourceVertex = 1,
                cdlpMaxIterations = 2,
                prDampingFactor = 0.85,
                prNumIterations = 2,
                ssspWeightProperty = "weight",
                ssspSourceVertex = 1,
                algorithms = setOf(Algorithm.BFS, Algorithm.CDLP, Algorithm.LCC, Algorithm.PR, Algorithm.SSSP, Algorithm.WCC),
                edgePropertyNames = listOf("weight"),
                edgePropertyTypes = listOf("real"),
            ),
            graph.parameters,
        )
    }

    @Test
    fun `loads undirected files once and symmetrises their edge relation`() {
        val graph = GraphLoader.load(Datasets.EXAMPLE_UNDIRECTED)

        assertFalse(graph.directed)
        assertTrue(graph.symmetrised)
        assertEquals((2L..10L).toList(), graph.vertices)
        assertEquals(24, graph.edges.size)
        assertEquals(12, graph.declaredEdges)
        assertEquals(24, graph.weights?.size)
        assertEquals(2L, graph.parameters.bfsSourceVertex)
        assertEquals(2L, graph.parameters.ssspSourceVertex)
    }

    @Test
    fun `missing algorithm properties stay null rather than receiving loader defaults`() {
        val dataset = scratchDataset("missing-parameters")
        val properties = Files.readString(dataset.resolve("missing-parameters.properties"))
            .lineSequence()
            .filterNot { line ->
                line.contains(".bfs.source-vertex") ||
                    line.contains(".cdlp.max-iterations") ||
                    line.contains(".pr.damping-factor") ||
                    line.contains(".pr.num-iterations") ||
                    line.contains(".sssp.weight-property") ||
                    line.contains(".sssp.source-vertex")
            }
            .joinToString("\n", postfix = "\n")
        Files.writeString(dataset.resolve("missing-parameters.properties"), properties)

        val parameters = GraphLoader.load(DatasetRef("missing-parameters", dataset.toString())).parameters
        assertNull(parameters.bfsSourceVertex)
        assertNull(parameters.cdlpMaxIterations)
        assertNull(parameters.prDampingFactor)
        assertNull(parameters.prNumIterations)
        assertNull(parameters.ssspWeightProperty)
        assertNull(parameters.ssspSourceVertex)
    }

    @Test
    fun `declared edge count rejects both altered metadata and a truncated edge file`() {
        val alteredProperties = scratchDataset("altered-properties")
        val properties = alteredProperties.resolve("altered-properties.properties")
        Files.writeString(
            properties,
            Files.readString(properties).replace("meta.edges = 17", "meta.edges = 16"),
        )
        val metadataFailure = assertThrows(LoadFidelityException::class.java) {
            GraphLoader.load(DatasetRef("altered-properties", alteredProperties.toString()))
        }
        assertTrue(metadataFailure.message.orEmpty().contains("altered-properties"))
        assertTrue(metadataFailure.message.orEmpty().contains("meta.edges"))
        assertTrue(metadataFailure.message.orEmpty().contains("16"))
        assertTrue(metadataFailure.message.orEmpty().contains("17"))

        val truncated = scratchDataset("truncated")
        val edgeFile = truncated.resolve("truncated.e")
        val lines = Files.readAllLines(edgeFile)
        Files.write(edgeFile, lines.dropLast(1))
        val truncationFailure = assertThrows(LoadFidelityException::class.java) {
            GraphLoader.load(DatasetRef("truncated", truncated.toString()))
        }
        assertTrue(truncationFailure.message.orEmpty().contains("truncated"))
        assertTrue(truncationFailure.message.orEmpty().contains("meta.edges"))
        assertTrue(truncationFailure.message.orEmpty().contains("17"))
        assertTrue(truncationFailure.message.orEmpty().contains("16"))
    }

    @Test
    fun `reference output preserves raw tokens`() {
        val output = Datasets.referenceOutput(Datasets.EXAMPLE_DIRECTED, Algorithm.LCC)

        assertEquals(10, output.size)
        assertEquals("6.666666666666666e-01", output[1L])
    }

    @Test
    fun `all registered datasets expose classpath resources`() {
        assertEquals(
            setOf("example-directed", "example-undirected"),
            Datasets.all().map(DatasetRef::name).toSet(),
        )
        Datasets.all().forEach { dataset ->
            dataset.open("${dataset.name}.v").use { assertTrue(it.readBytes().isNotEmpty()) }
            dataset.open("${dataset.name}.e").use { assertTrue(it.readBytes().isNotEmpty()) }
            dataset.open("${dataset.name}.properties").use { assertTrue(it.readBytes().isNotEmpty()) }
        }
    }

    private fun scratchDataset(name: String): Path {
        val root = Files.createTempDirectory("graphalytics-$name-")
        val source = Datasets.EXAMPLE_DIRECTED
        listOf(
            "${source.name}.v",
            "${source.name}.e",
            "${source.name}.properties",
        ).forEach { fileName ->
            val targetName = fileName.replace(source.name, name)
            source.open(fileName).use { input ->
                Files.copy(input, root.resolve(targetName))
            }
            if (targetName.endsWith(".properties")) {
                val target = root.resolve(targetName)
                Files.writeString(
                    target,
                    Files.readString(target).replace(source.name, name),
                )
            }
        }
        return root
    }
}
