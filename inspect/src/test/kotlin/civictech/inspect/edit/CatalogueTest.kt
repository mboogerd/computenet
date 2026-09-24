package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.graph.InstanceSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.link.Interest
import civictech.cell.membrane.TrafficLightCell
import civictech.nature.ContractRegistry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * WKB2 F12 task 1 (va0c4-D1..D4): [Catalogue] registration, [ParamSchema]
 * validation, [Catalogue.resolve], and the [CatalogueFactory]/
 * [CatalogueInstanceFactory] construction and serialization contracts.
 *
 * Every registered id is prefixed `test.CatalogueTest.` and removed in
 * [tearDown] — [Catalogue] is a process-wide singleton and `:inspect` tests
 * share one JVM (feature breakdown note).
 */
class CatalogueTest {

    private val registeredIds = mutableListOf<String>()

    private fun register(entry: CatalogueEntry): CatalogueEntry {
        Catalogue.register(entry)
        registeredIds += entry.id
        return entry
    }

    @AfterEach
    fun tearDown() {
        registeredIds.forEach { Catalogue.unregister(it) }
        registeredIds.clear()
    }

    private val setCellFqn = "civictech.cell.data.SetCell"
    private val trafficLightFqn = "civictech.cell.membrane.TrafficLightCell"

    private fun trafficLightEntry(id: String, schema: ParamSchema = ParamSchema(emptyList())) =
        CatalogueEntry(
            id = id,
            descriptorFqn = trafficLightFqn,
            schema = schema,
            build = EntryBuilder { _, ref -> TrafficLightCell(Consumer::class.java, ref) },
        )

    // --- 1. registration refuses an fqn absent from ContractRegistry ---

    @Test
    fun `register refuses a descriptor fqn absent from ContractRegistry`() {
        val id = "test.CatalogueTest.missing"

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.register(
                CatalogueEntry(
                    id = id,
                    descriptorFqn = "civictech.nope.Missing",
                    schema = ParamSchema(emptyList()),
                    build = EntryBuilder { _, ref -> TrafficLightCell(Consumer::class.java, ref) },
                ),
            )
        }

        failure.message shouldBe "catalogue entry '$id': descriptor fqn 'civictech.nope.Missing' " +
            "is not present in ContractRegistry"
        Catalogue.entry(id) shouldBe null
    }

    // --- 2. descriptor() reads the live registry, never a cached copy ---

    @Test
    fun `descriptor reads the registry's own CellDescriptor, not a stored copy`() {
        val entry = register(
            CatalogueEntry(
                id = "test.CatalogueTest.setDescriptor",
                descriptorFqn = setCellFqn,
                schema = ParamSchema(emptyList()),
                build = EntryBuilder { _, ref -> civictech.cell.data.SetCell<String>(ref) },
            ),
        )

        val expected = ContractRegistry.cells.first { it.fqn == setCellFqn }
        entry.descriptor() shouldBe expected
    }

    // --- 3. ParamSchema registration refusals ---

    @Test
    fun `register refuses a duplicate entry id`() {
        val id = "test.CatalogueTest.dup"
        register(trafficLightEntry(id))

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.register(trafficLightEntry(id))
        }
        failure.message shouldBe "catalogue entry '$id': already registered"
    }

    @Test
    fun `register refuses a duplicate parameter name`() {
        val id = "test.CatalogueTest.dupParam"

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.register(
                trafficLightEntry(
                    id,
                    ParamSchema(
                        listOf(
                            ParamSpec("prefix", ParamKind.STRING),
                            ParamSpec("prefix", ParamKind.INT),
                        ),
                    ),
                ),
            )
        }
        failure.message shouldBe "catalogue entry '$id': duplicate parameter name 'prefix'"
        Catalogue.entry(id) shouldBe null
    }

    @Test
    fun `register refuses an ENUM parameter with empty values`() {
        val id = "test.CatalogueTest.emptyEnum"

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.register(
                trafficLightEntry(id, ParamSchema(listOf(ParamSpec("mode", ParamKind.ENUM, emptyList())))),
            )
        }
        failure.message shouldBe "catalogue entry '$id': ENUM parameter 'mode' declares no values"
        Catalogue.entry(id) shouldBe null
    }

    @Test
    fun `register refuses a non-ENUM parameter that declares values`() {
        val id = "test.CatalogueTest.strayValues"

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.register(
                trafficLightEntry(
                    id,
                    ParamSchema(listOf(ParamSpec("prefix", ParamKind.STRING, listOf("a", "b")))),
                ),
            )
        }
        failure.message shouldBe "catalogue entry '$id': non-ENUM parameter 'prefix' (kind STRING) declares values [a, b]"
        Catalogue.entry(id) shouldBe null
    }

    // --- 4. resolve() validation ---

    private fun schemaEntry(id: String): CatalogueEntry = register(
        trafficLightEntry(
            id,
            ParamSchema(
                listOf(
                    ParamSpec("prefix", ParamKind.STRING),
                    ParamSpec("limit", ParamKind.INT, required = false),
                    ParamSpec("mode", ParamKind.ENUM, listOf("fast", "slow")),
                ),
            ),
        ),
    )

    @Test
    fun `resolve refuses an unknown id`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.resolve("test.CatalogueTest.absent", emptyMap())
        }
        failure.message shouldBe "catalogue entry 'test.CatalogueTest.absent': not registered"
    }

    @Test
    fun `resolve refuses a missing required parameter`() {
        val id = "test.CatalogueTest.missingRequired"
        schemaEntry(id)

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.resolve(id, mapOf("mode" to ParamValue.Enum("fast")))
        }
        failure.message shouldBe "catalogue entry '$id': missing required parameter 'prefix'"
    }

    @Test
    fun `resolve refuses an unknown parameter name`() {
        val id = "test.CatalogueTest.unknownName"
        schemaEntry(id)

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.resolve(
                id,
                mapOf(
                    "prefix" to ParamValue.Str("a"),
                    "mode" to ParamValue.Enum("fast"),
                    "bogus" to ParamValue.Str("x"),
                ),
            )
        }
        failure.message shouldBe "catalogue entry '$id': unknown parameter(s) [bogus]"
    }

    @Test
    fun `resolve refuses a value whose kind does not match its ParamSpec`() {
        val id = "test.CatalogueTest.kindMismatch"
        schemaEntry(id)

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.resolve(
                id,
                mapOf(
                    "prefix" to ParamValue.I32(3),
                    "mode" to ParamValue.Enum("fast"),
                ),
            )
        }
        failure.message shouldBe "catalogue entry '$id': parameter 'prefix' expects STRING, got I32(value=3)"
    }

    @Test
    fun `resolve refuses an enum value outside its declared values`() {
        val id = "test.CatalogueTest.enumOutside"
        schemaEntry(id)

        val failure = assertFailsWith<IllegalArgumentException> {
            Catalogue.resolve(
                id,
                mapOf(
                    "prefix" to ParamValue.Str("a"),
                    "mode" to ParamValue.Enum("turbo"),
                ),
            )
        }
        failure.message shouldBe "catalogue entry '$id': parameter 'mode' value 'turbo' is not one of [fast, slow]"
    }

    @Test
    fun `resolve accepts a full valid map and returns an equal hand-built CatalogueFactory`() {
        val id = "test.CatalogueTest.validResolve"
        schemaEntry(id)
        val params = mapOf(
            "prefix" to ParamValue.Str("a"),
            "limit" to ParamValue.I32(5),
            "mode" to ParamValue.Enum("slow"),
        )

        val resolved = Catalogue.resolve(id, params)

        resolved shouldBe CatalogueFactory(id, params)
    }

    @Test
    fun `resolve accepts an omitted optional parameter`() {
        val id = "test.CatalogueTest.optionalOmitted"
        schemaEntry(id)
        val params = mapOf(
            "prefix" to ParamValue.Str("a"),
            "mode" to ParamValue.Enum("fast"),
        )

        Catalogue.resolve(id, params) shouldBe CatalogueFactory(id, params)
    }

    // --- 5. CatalogueFactory.create ---

    @Test
    fun `CatalogueFactory create builds a cell carrying the given ref through the entry's builder`() {
        val id = "test.CatalogueTest.createsWithRef"
        register(trafficLightEntry(id))
        val ref = CellRef(UUID.randomUUID())

        val built = CatalogueFactory(id, emptyMap()).create(ref)

        built.ref shouldBe ref
    }

    @Test
    fun `CatalogueFactory create for an unregistered id throws IllegalStateException at create, not at construction`() {
        val factory = CatalogueFactory("test.CatalogueTest.neverRegistered", emptyMap())

        val failure = assertFailsWith<IllegalStateException> {
            factory.create(CellRef(UUID.randomUUID()))
        }
        failure.message shouldBe "catalogue entry 'test.CatalogueTest.neverRegistered' is not registered"
    }

    // --- 6. java.io serialization round-trip of a SpawnStep carrying a CatalogueFactory ---

    @Test
    fun `a SpawnStep carrying a CatalogueFactory with a Ref param round-trips through java-io serialization`() {
        val id = "test.CatalogueTest.serializable"
        val someRef = CellRef(UUID.randomUUID(), 7L)
        val step = SpawnStep("h", CatalogueFactory(id, mapOf("p" to ParamValue.Ref(someRef))))

        val bytes = ByteArrayOutputStream().also { out ->
            ObjectOutputStream(out).use { it.writeObject(step) }
        }.toByteArray()
        val roundTripped = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

        roundTripped shouldBe step
    }

    // --- 7. CatalogueInstanceFactory.build ---

    @Test
    fun `CatalogueInstanceFactory build builds through the entry's builder, carrying the given ref`() {
        val id = "test.CatalogueTest.instanceFactory"
        register(trafficLightEntry(id))
        val ref = CellRef(UUID.randomUUID())

        val built = CatalogueInstanceFactory(id, emptyMap())
            .build(ref, InstanceSpec(Interest.Total, 0))

        built.ref shouldBe ref
    }
}
