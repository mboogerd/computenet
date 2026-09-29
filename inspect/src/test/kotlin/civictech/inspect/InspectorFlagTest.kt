package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.demo.shell.demoPort
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.edit.Capability
import civictech.inspect.edit.WritePlane
import civictech.testkit.HttpProbe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `InspectorFlag.parse` pins the union of both private parsers
 * `demo/shopping` and `demo/skillmatch` carried before this object existed
 * (computenet-3iv0w.1): the stripping rule that keeps an inspector value from
 * being mistaken for a demo's own positional port, the write plane's opt-in
 * (`[WKB2-06]`), and `--net-name`. One `serve` test proves `Options.serve`
 * actually starts a working [InspectorServer].
 */
class InspectorFlagTest {

    @Test
    fun `an inspector value after a positional port is stripped from rest`() {
        val parsed = InspectorFlag.parse(arrayOf("8080", "--inspect-port", "0")) { null }

        parsed.options.shouldNotBeNull().port shouldBe 0
        parsed.rest shouldBe arrayOf("8080")
    }

    @Test
    fun `the equals form is recognised`() {
        val parsed = InspectorFlag.parse(arrayOf("--inspect-port=17071")) { null }

        parsed.options.shouldNotBeNull().port shouldBe 17071
        parsed.rest shouldBe emptyArray<String>()
    }

    @Test
    fun `an env-only port is recognised`() {
        val parsed = InspectorFlag.parse(arrayOf("8080")) { name -> if (name == "INSPECT_PORT") "9090" else null }

        parsed.options.shouldNotBeNull().port shouldBe 9090
        parsed.rest shouldBe arrayOf("8080")
    }

    @Test
    fun `an inspector value BEFORE the positional never becomes the demo's port`() {
        val parsed = InspectorFlag.parse(arrayOf("--inspect-port", "0", "8080")) { null }

        parsed.options.shouldNotBeNull().port shouldBe 0
        // this is the bug both mains solved privately: a parse that fails to
        // strip the inspector's own value here would leave `demoPort` reading
        // "0" as this demo's port instead of "8080"
        demoPort(parsed.rest) shouldBe 8080
    }

    @Test
    fun `inspect-write without a port enables nothing and is still stripped`() {
        val parsed = InspectorFlag.parse(arrayOf("--inspect-write")) { null }

        parsed.options.shouldBeNull()
        parsed.rest shouldBe emptyArray<String>()
    }

    @Test
    fun `a port with inspect-write and a capability enables the write plane`() {
        val parsed = InspectorFlag.parse(
            arrayOf("--inspect-port", "0", "--inspect-write", "--inspect-write-capability", "abc"),
        ) { null }

        val writePlane = parsed.options.shouldNotBeNull().writePlane
        writePlane shouldBe WritePlane.Enabled(Capability("abc"))
        parsed.rest shouldBe emptyArray<String>()
    }

    @Test
    fun `net-name is returned both raw and defaulted onto options`() {
        val withName = InspectorFlag.parse(arrayOf("--inspect-port", "0", "--net-name", "jvm-a")) { null }
        withName.netName shouldBe "jvm-a"
        withName.options.shouldNotBeNull().netName shouldBe "jvm-a"

        val withoutName = InspectorFlag.parse(arrayOf("--inspect-port", "0")) { null }
        withoutName.netName.shouldBeNull()
        withoutName.options.shouldNotBeNull().netName shouldBe Node.LOCAL_NET
    }

    // --- Options.serve ---

    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val registry = LocationRegistry()
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private var server: InspectorServer? = null
    private var probe: HttpProbe? = null

    @AfterEach
    fun tearDown() {
        probe?.close()
        server?.close()
        hostScheduler.shutdown()
    }

    @Test
    fun `Options serve builds a working inspector off one host`() {
        val cell = SetCell<String>()
        host.managementInlet.call.spawn(cell)

        val started = InspectorFlag.Options(port = 0).serve(registry, mapOf("t" to host))
        server = started
        probe = HttpProbe("http://localhost:${started.boundPort}")

        probe!!.state(InspectorServer.TOPOLOGY_PATH) shouldContain "\"host\":\"t\""
    }
}
