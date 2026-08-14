package civictech.concord.driver.kernel

import civictech.concord.value.Value
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * Regression pin for computenet-7xk.
 *
 * [KernelDriver.defaultHostId] used to be written as a literal NUL byte
 * (`"<NUL>default"`), which made git classify the whole source file as binary
 * (`git diff` reported `Bin ... bytes`, and GitHub's PR view showed nothing at
 * all). The fix rewrites the same value as the Kotlin escape `"\u0000default"`
 * so the file stays text — a correctness-neutral *encoding* change only.
 *
 * The NUL prefix itself is load-bearing: [KernelDriver.HostId] is an
 * unconstrained `String` (no reserved-word grammar anywhere in
 * `concord/schema`), and an unspecified `host:` on a cell lowers to the empty
 * string before [KernelDriver.hostFor] substitutes [KernelDriver.defaultHostId]
 * (`KernelDriver.kt`'s `spawn`). Without the NUL, a scenario author naming a
 * real host `default` would collide with that implicit bucket in the
 * `hosts` map. This test pins both halves: the escape decodes to the exact
 * same character sequence the NUL byte encoded, and the collision the prefix
 * exists to prevent still cannot happen.
 */
class DefaultHostIdSentinelTest {

    @Test
    fun `defaultHostId decodes to a NUL followed by 'default', unchanged by the NUL-to-escape rewrite`() {
        val field = KernelDriver::class.java.getDeclaredField("defaultHostId")
        field.isAccessible = true
        val value = field.get(KernelDriver(0L)) as String

        // The exact value the literal NUL byte encoded: one U+0000 control
        // character followed by the seven letters "default" - 8 chars total.
        value shouldBe "\u0000default"
        value.length shouldBe 8
        value[0] shouldBe '\u0000'
        value.substring(1) shouldBe "default"
    }

    @Test
    fun `a scenario-authored host literally named 'default' does not collide with the implicit host`() {
        val driver = KernelDriver(0L)

        // Cells with no host spec resolve to the implicit default host.
        driver.spawn("", "implicit", "counter-source", emptyMap())
        // A dist-profile author is free to name a real host "default" - nothing
        // in the scenario grammar reserves that word.
        driver.createHost("default")
        driver.spawn("default", "explicit", "counter-source", emptyMap())

        driver.apply("implicit", "increment", null)
        driver.apply("explicit", "increment", null)
        driver.apply("explicit", "increment", null)
        driver.quiesce(5_000_000)

        // If the sentinel were the plain string "default", both cells would
        // have landed on the same ManagedHost and shared its dead-letter/host
        // bookkeeping. hostFor keys a LinkedHashMap<HostId, ManagedHost> by
        // reference identity of the key string's value, so the only direct
        // observable is that the two spawns did not collapse into one host:
        // read each cell's bound host back out and confirm they differ.
        val implicitHost = driver.cells.getValue("implicit").host
        val explicitHost = driver.cells.getValue("explicit").host
        implicitHost shouldNotBe explicitHost
    }
}
