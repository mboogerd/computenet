package civictech.bench.nexmark

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratorDeterminismTest {
    @Test
    fun `same seed is equal in process and across a fresh JVM while another seed differs`() {
        val scale = NexmarkScale(events = 2_000, eventsPerSecond = 50)
        val first = NexmarkGenerator.generate(seed = 7L, scale = scale)
        val second = NexmarkGenerator.generate(seed = 7L, scale = scale)
        val digest = NexmarkGenerator.digest(first)

        assertEquals(first, second)
        assertEquals(digest, NexmarkGenerator.digest(second))
        assertEquals(digest, runDigestMain(seed = 7L))

        val other = NexmarkGenerator.generate(seed = 8L, scale = scale)
        assertNotEquals(digest, NexmarkGenerator.digest(other))
        assertNotEquals(digest, runDigestMain(seed = 8L))
    }

    private fun runDigestMain(seed: Long): String {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "civictech.bench.nexmark.GeneratorDigestMain",
            seed.toString(),
            "2000",
            "50",
        ).redirectErrorStream(true).start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "digest child JVM did not finish")
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}
