package civictech.bench.nexmark

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IncrementATest {
    @Test
    fun `Q1 Q2 Q3 are verified without a lateness policy`() {
        listOf(NexmarkQuery.Q1, NexmarkQuery.Q2, NexmarkQuery.Q3).forEach { query ->
            val record = NexmarkRun.run(
                query = query,
                seed = if (query == NexmarkQuery.Q3) Q3_SEED else 7L,
                scale = NexmarkScale(2_000, 50),
                policy = null,
                drive = Drive.SIM,
                env = nexmarkTestEnvironment(),
            )
            assertInstanceOf(NexmarkRecord.Verified::class.java, record, "$query")
        }
    }

    @Test
    fun `Increment A builders import nothing from Windows or Waterline`() {
        val root = File(System.getProperty("computenet.repo.root") ?: error("computenet.repo.root unset"))
        listOf("Q1", "Q2", "Q3").forEach { name ->
            val source = File(root, "bench/src/main/kotlin/civictech/bench/nexmark/queries/$name.kt").readText()
            val imports = source.lines().filter { it.startsWith("import ") }
            assertFalse(
                imports.any { it.startsWith("import civictech.cell.data.Windows") || it.startsWith("import civictech.cell.data.Waterline") },
                "$name.kt imports windowing: $imports",
            )
        }
    }
}
