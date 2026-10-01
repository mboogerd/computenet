package civictech.concord.driver.kernel

import civictech.concord.oracle.Fx.list
import civictech.concord.oracle.Fx.s
import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

/**
 * Pins gyvli-D6 at the driver seam: named Concord hosts can use separate
 * registries joined only by the selected [civictech.cell.wire.PeerTransport].
 */
class TransportDistBindingTest {

    private val budget = 5_000_000

    @Test
    fun `ws transport carries replica convergence between separate host registries`() {
        val driver = KernelDriver(0L, transportScheme = "ws")
        try {
            driver.createHost("h1")
            driver.createHost("h2")
            driver.spawn("h1", "r1", "set-source", mapOf("replica-of" to Value.StrVal("shared")))
            driver.spawn("h2", "r2", "set-source", mapOf("replica-of" to Value.StrVal("shared")))

            val r1Registry = driver.registryOf(driver.cells.getValue("r1").host)
            val r2Registry = driver.registryOf(driver.cells.getValue("r2").host)
            (r1Registry === r2Registry) shouldBe false

            driver.apply("r1", "add", s("from-h1"))
            driver.apply("r2", "add", s("from-h2"))
            driver.quiesce(budget).settled shouldBe true

            val expected = list(s("from-h1"), s("from-h2"))
            driver.readView("r1") shouldBe expected
            driver.readView("r2") shouldBe expected
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ws transport reports cross-registry migration as unsupported`() {
        val driver = KernelDriver(0L, transportScheme = "ws")
        try {
            driver.createHost("h1")
            driver.createHost("h2")
            driver.spawn("h1", "moving", "set-source", emptyMap())
            val snapshot = driver.snapshot("moving")

            val refused = assertThrows<UnsupportedCatalogBinding> {
                driver.restore("h2", "moving", snapshot)
            }
            refused.message shouldBe CROSS_REGISTRY_MIGRATE_UNBUILT
        } finally {
            driver.close()
        }
    }
}
