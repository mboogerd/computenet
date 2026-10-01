package civictech.runtime

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path

class TwoNodesOverIrohTest {

    @Test
    @Timeout(180)
    fun `two manifest nodes replicate over iroh and partition-heal through the exposed connection`() {
        val configured = System.getProperty(SIDECAR_PROPERTY)
        val binary = configured?.let(Path::of)
        assumeTrue(
            binary != null && Files.isRegularFile(binary),
            "no $SIDECAR_PROPERTY: run with -Piroh.enabled=true to build and configure the sidecar",
        )
        val transportConfig = mapOf("binary" to binary!!.toString())

        RuntimeTransportTestRig.exercise(
            Manifest(
                mapOf(
                    "a" to NodeSpec(
                        transport = "iroh",
                        transportConfig = transportConfig,
                        listen = "iroh://",
                        replica = 0,
                        peerName = "a",
                    ),
                    "b" to NodeSpec(
                        transport = "iroh",
                        transportConfig = transportConfig,
                        dial = listOf("a"),
                        replica = 1,
                        peerName = "b",
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val SIDECAR_PROPERTY = "iroh.sidecar.binary"
    }
}
