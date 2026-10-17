package civictech.bench.nexmark

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceIndependenceTest {
    @Test
    fun `reference re-derives query window and waterline semantics`() {
        val root = Paths.get(
            requireNotNull(System.getProperty("computenet.repo.root")) {
                "bench must provide computenet.repo.root for actual-tree inspection"
            },
        )
        val file = root.resolve(
            "bench/src/main/kotlin/civictech/bench/nexmark/NexmarkReference.kt",
        )

        assertTrue(Files.isRegularFile(file), "missing source $file")
        val source = Files.readString(file)
        assertFalse(source.contains("import civictech.cell.data.op."), file.toString())
        assertFalse(source.contains("import civictech.cell.data.Windows"), file.toString())
        assertFalse(source.contains("import civictech.cell.data.Waterline"), file.toString())
    }
}
