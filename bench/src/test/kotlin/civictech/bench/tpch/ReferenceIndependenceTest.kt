package civictech.bench.tpch

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceIndependenceTest {

    @Test
    fun `reference files have no runtime or maintained-query dependency`() {
        val root = Paths.get(
            requireNotNull(System.getProperty("computenet.repo.root")) {
                "bench must provide computenet.repo.root for actual-tree inspection"
            },
        )
        val files = listOf("TpchReference.kt", "ViewResult.kt", "QueryParams.kt")
            .map { root.resolve("bench/src/main/kotlin/civictech/bench/tpch").resolve(it) }
        files.forEach { file ->
            assertTrue(Files.isRegularFile(file), "missing source $file")
            val source = Files.readString(file)
            assertFalse(source.contains("import civictech.cell"), file.toString())
            assertFalse(source.contains("civictech.bench.tpch.queries"), file.toString())
        }
    }

    @Test
    fun `all query tiers are explicit`() {
        assertEquals(22, TpchQuery.entries.size)
        assertEquals(13, TpchQuery.entries.count { it.tier == TpchTier.HAND_1 })
        assertEquals(9, TpchQuery.entries.count { it.tier == TpchTier.BLOCKED_2 })
        assertTrue(TpchQuery.entries.filter { it.tier == TpchTier.BLOCKED_2 }.all { !it.blockedCause.isNullOrBlank() })
    }
}
