package civictech.bench.tpch

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratorDeterminismTest {

    @Test
    fun `same seed is equal in process and across a fresh JVM while another seed differs`() {
        val first = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val second = TpchGenerator.generate(seed = 7L, sf = 0.001)
        val digest = TpchGenerator.digest(first)

        assertEquals(first, second)
        assertEquals(digest, TpchGenerator.digest(second))

        val childDigest = runDigestMain(seed = 7L)
        assertEquals(digest, childDigest)

        val other = TpchGenerator.generate(seed = 8L, sf = 0.001)
        assertNotEquals(first, other)
        assertNotEquals(digest, TpchGenerator.digest(other))
        assertNotEquals(digest, runDigestMain(seed = 8L))
    }

    private fun runDigestMain(seed: Long): String {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "civictech.bench.tpch.GeneratorDigestMain",
            seed.toString(),
            "0.001",
        ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "digest child JVM did not finish")
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}
