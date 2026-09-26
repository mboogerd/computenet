package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.inspect.CatalogueDto
import civictech.inspect.CatalogueEntryDto
import civictech.inspect.InspectorServer
import civictech.nature.ContractRegistry
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WKB2 F12 task 3 (va0c4-D9): `GET /api/inspect/catalogue`. Every registered
 * id is prefixed `test.CatalogueRouteTest.` and removed in [tearDown] —
 * [Catalogue] is a process-wide singleton and `:inspect` tests share one JVM
 * (feature breakdown note); [entries] assertions filter to this test's own
 * prefix since other suites in the same JVM may hold entries of their own.
 */
class CatalogueRouteTest {

    private val registeredIds = mutableListOf<String>()

    private fun register(entry: CatalogueEntry): CatalogueEntry {
        Catalogue.register(entry)
        registeredIds += entry.id
        return entry
    }

    private val registry = LocationRegistry()
    private val hostRef = CellRef(UUID.randomUUID())
    private val hostScheduler = VirtualThreadScheduler("ManagedHost-${hostRef.id}")
    private val host = ManagedHost(ref = hostRef, scheduler = hostScheduler, registry = registry)
    private var server: InspectorServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
        hostScheduler.shutdown()
        registeredIds.forEach { Catalogue.unregister(it) }
        registeredIds.clear()
    }

    private val filterFqn = "civictech.cell.data.op.FilterCell"
    private val setFqn = "civictech.cell.data.SetCell"

    private fun filterEntry(id: String): CatalogueEntry = register(
        CatalogueEntry(
            id = id,
            descriptorFqn = filterFqn,
            schema = ParamSchema(
                listOf(
                    ParamSpec("prefix", ParamKind.STRING),
                    ParamSpec("mode", ParamKind.ENUM, values = listOf("startsWith", "contains")),
                ),
            ),
            build = EntryBuilder { params, ref ->
                val prefix = (params.getValue("prefix") as ParamValue.Str).value
                FilterCell<String>(ref = ref) { it.startsWith(prefix) }
            },
        ),
    )

    private fun setEntry(id: String): CatalogueEntry = register(
        CatalogueEntry(
            id = id,
            descriptorFqn = setFqn,
            schema = ParamSchema(emptyList()),
            build = EntryBuilder { _, ref -> SetCell<Any>(ref = ref) },
        ),
    )

    private fun started(writePlane: WritePlane = WritePlane.Disabled): InspectorServer =
        InspectorServer(registry, mapOf("h" to host), port = 0, writePlane = writePlane)
            .startUnscheduled()
            .also { server = it }

    @Test
    fun `GET catalogue lists every registered entry with its live descriptor metadata and schema`() {
        val filter = filterEntry("test.CatalogueRouteTest.filter")
        val set = setEntry("test.CatalogueRouteTest.set")
        started()

        val response = sendTo(server!!.boundPort, "GET", InspectorServer.CATALOGUE_PATH)

        response.statusCode() shouldBe 200
        val dto = Json.decodeFromString(CatalogueDto.serializer(), response.body())
        val ours = dto.entries.filter { it.id.startsWith("test.CatalogueRouteTest.") }
        ours.map { it.id } shouldBe listOf(filter.id, set.id) // sorted by id

        val filterDescriptor = ContractRegistry.cells.first { it.fqn == filterFqn }
        val expectedFilter = CatalogueEntryDto(
            id = filter.id,
            fqn = filterDescriptor.fqn,
            color = filterDescriptor.color.name,
            manifests = filterDescriptor.manifest.map { it.name }.sorted(),
            ports = filterDescriptor.ports.map { civictech.inspect.NodePort(it.name, it.direction.name, it.contractFqn) },
            schema = filter.schema,
        )
        ours.first { it.id == filter.id } shouldBe expectedFilter

        val setDescriptor = ContractRegistry.cells.first { it.fqn == setFqn }
        val expectedSet = CatalogueEntryDto(
            id = set.id,
            fqn = setDescriptor.fqn,
            color = setDescriptor.color.name,
            manifests = setDescriptor.manifest.map { it.name }.sorted(),
            ports = setDescriptor.ports.map { civictech.inspect.NodePort(it.name, it.direction.name, it.contractFqn) },
            schema = set.schema,
        )
        ours.first { it.id == set.id } shouldBe expectedSet

        // the schema round-trips equal
        ours.first { it.id == filter.id }.schema shouldBe filter.schema
    }

    @Test
    fun `an empty registry answers 200 with an empty list, never 404`() {
        started()

        // no entries registered by this test (any left by other suites in the
        // same JVM are irrelevant — this only checks status and shape)
        val response = sendTo(server!!.boundPort, "GET", InspectorServer.CATALOGUE_PATH)

        response.statusCode() shouldBe 200
        val dto = Json.decodeFromString(CatalogueDto.serializer(), response.body())
        dto.entries.none { it.id.startsWith("test.CatalogueRouteTest.") } shouldBe true
    }

    @Test
    fun `a POST to catalogue is a 404`() {
        setEntry("test.CatalogueRouteTest.postCheck")
        started()

        val response = sendTo(server!!.boundPort, "POST", InspectorServer.CATALOGUE_PATH, body = "{}")

        response.statusCode() shouldBe 404
        reasonOf(response) shouldBe "expected GET /catalogue"
    }

    @Test
    fun `the route is ungated - a Disabled write plane serves it identically to an Enabled one`() {
        val id = "test.CatalogueRouteTest.gateCheck"
        setEntry(id)
        started(WritePlane.Disabled)

        val disabledResponse = sendTo(server!!.boundPort, "GET", InspectorServer.CATALOGUE_PATH)
        server?.close()

        started(WritePlane.Enabled(Capability("t")))
        val enabledResponse = sendTo(server!!.boundPort, "GET", InspectorServer.CATALOGUE_PATH)

        disabledResponse.statusCode() shouldBe 200
        enabledResponse.statusCode() shouldBe 200
        val disabledOurs = Json.decodeFromString(CatalogueDto.serializer(), disabledResponse.body())
            .entries.filter { it.id == id }
        val enabledOurs = Json.decodeFromString(CatalogueDto.serializer(), enabledResponse.body())
            .entries.filter { it.id == id }
        disabledOurs shouldBe enabledOurs
    }
}
