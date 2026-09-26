package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.Direction
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.HostLiveView
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.InstanceCellFactory
import civictech.cell.graph.InstanceSetStep
import civictech.cell.graph.InstanceSpec
import civictech.cell.graph.PlannedAction
import civictech.cell.graph.RefusalCode
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.StepCheck
import civictech.cell.graph.Verdict
import civictech.cell.graph.precheck
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.membrane.TrafficLightCell
import civictech.inspect.InspectorServer.Companion.encodeRef
import civictech.inspect.inspectorJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * WKB2 F12 task 2 (va0c4-D5..D8): [DraftCompiler] lowers a browser draft to a
 * kernel [GraphSpec] plus [BoundaryLink]s, and refuses unresolvable nodes as a
 * precheck-shaped [civictech.cell.graph.Plan].
 *
 * Every registered id is prefixed `test.DraftCompilerTest.` and removed in
 * [tearDown]: [Catalogue] is process-wide and `:inspect` tests share one JVM.
 */
class DraftCompilerTest {

    private val registeredIds = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        registeredIds.forEach { Catalogue.unregister(it) }
        registeredIds.clear()
    }

    private fun register(id: String, fqn: String, schema: ParamSchema = ParamSchema(), build: EntryBuilder): String {
        Catalogue.register(CatalogueEntry(id, fqn, schema, build))
        registeredIds += id
        return id
    }

    private val set = "test.DraftCompilerTest.set"
    private val light = "test.DraftCompilerTest.light"
    private val typed = "test.DraftCompilerTest.typed"

    private fun registerSet() =
        register(set, "civictech.cell.data.SetCell") { _, ref -> SetCell<String>(ref = ref) }

    private fun registerLight() =
        register(light, "civictech.cell.membrane.TrafficLightCell") { _, ref -> TrafficLightCell(Consumer::class.java, ref) }

    /** One parameter of every kind, all required. */
    private fun registerTyped() = register(
        typed,
        "civictech.cell.membrane.TrafficLightCell",
        ParamSchema(
            listOf(
                ParamSpec("s", ParamKind.STRING),
                ParamSpec("i", ParamKind.INT),
                ParamSpec("l", ParamKind.LONG),
                ParamSpec("b", ParamKind.BOOLEAN),
                ParamSpec("e", ParamKind.ENUM, values = listOf("RED", "GREEN")),
                ParamSpec("r", ParamKind.REF),
            ),
        ),
    ) { _, ref -> TrafficLightCell(Consumer::class.java, ref) }

    private val someRef = CellRef(UUID.fromString("00000000-0000-0000-0000-00000000abcd"), 7)

    private fun goodTypedParams(): JsonObject = buildJsonObject {
        put("s", "hello")
        put("i", 42)
        put("l", 9_000_000_000L)
        put("b", true)
        put("e", "GREEN")
        put("r", encodeRef(someRef))
    }

    private fun JsonObject.with(name: String, value: kotlinx.serialization.json.JsonElement?) =
        JsonObject(if (value == null) this - name else this + (name to value))

    private fun ok(draft: DraftDto): Compiled.Ok = DraftCompiler.compile(draft).shouldBeInstanceOf<Compiled.Ok>()

    private fun refused(draft: DraftDto): Compiled.Refused = DraftCompiler.compile(draft).shouldBeInstanceOf<Compiled.Refused>()

    private fun <T> roundTrip(value: T): Any? {
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(value) } }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }

    // --- [WKB2-02] lowering ---

    @Test
    fun `a catalogue node lowers to a SpawnStep structurally equal to a hand-built one and round-trips java io`() {
        registerSet()
        val compiled = ok(DraftDto(nodes = listOf(DraftNodeDto(handle = "s", catalogueId = set))))

        compiled.spec.steps shouldBe listOf(SpawnStep("s", CatalogueFactory(set, emptyMap())))
        compiled.boundary shouldBe emptyList()
        roundTrip(compiled.spec) shouldBe compiled.spec
    }

    @Test
    fun `decoded params of every kind ride the factory and survive a java io round trip`() {
        registerTyped()
        val compiled = ok(DraftDto(listOf(DraftNodeDto("t", typed, params = goodTypedParams(), parent = null))))

        val expected = mapOf(
            "s" to ParamValue.Str("hello"),
            "i" to ParamValue.I32(42),
            "l" to ParamValue.I64(9_000_000_000L),
            "b" to ParamValue.Bool(true),
            "e" to ParamValue.Enum("GREEN"),
            "r" to ParamValue.Ref(someRef),
        )
        compiled.spec.steps shouldBe listOf(SpawnStep("t", CatalogueFactory(typed, expected)))
        roundTrip(compiled.spec) shouldBe compiled.spec
    }

    @Test
    fun `replaces binds NewInstanceOf the incumbent's logical id, parent passes through`() {
        registerSet()
        val incumbent = CellRef(UUID.randomUUID(), 3)
        val compiled = ok(
            DraftDto(
                listOf(
                    DraftNodeDto("p", set),
                    DraftNodeDto("s", set, replaces = encodeRef(incumbent), parent = "p"),
                ),
            ),
        )

        compiled.spec.steps shouldBe listOf(
            SpawnStep("p", CatalogueFactory(set, emptyMap()), IdentityBinding.FreshLogical),
            SpawnStep("s", CatalogueFactory(set, emptyMap()), IdentityBinding.NewInstanceOf(incumbent.id), parent = "p"),
        )
    }

    @Test
    fun `only kernel step types are produced`() {
        registerSet()
        registerLight()
        val compiled = ok(
            DraftDto(
                nodes = listOf(DraftNodeDto("a", set), DraftNodeDto("b", light, replicas = 2)),
                edges = listOf(DraftEdgeDto(DraftEndpointDto(handle = "a", port = "outlet"), DraftEndpointDto(handle = "b", port = "dataInlet"))),
            ),
        )
        compiled.spec.steps.map { it::class } shouldBe listOf(SpawnStep::class, InstanceSetStep::class, ConnectStep::class)
    }

    // --- [WKB2-03] replicas ---

    @Test
    fun `replicas 3 compiles to one unlowered InstanceSetStep that lowers to three NewInstanceOf spawns`() {
        registerLight()
        val compiled = ok(DraftDto(listOf(DraftNodeDto("light", light, replicas = 3))))

        val setStep = compiled.spec.steps.single().shouldBeInstanceOf<InstanceSetStep>()
        setStep.handle shouldBe "light"
        setStep.factory shouldBe CatalogueInstanceFactory(light, emptyMap())
        setStep.instances shouldBe (0 until 3).map { InstanceSpec(Interest.Total, it) }

        val lowered = compiled.spec.lowered()
        lowered shouldBe (0 until 3).map { i ->
            SpawnStep(
                handle = "light-$i",
                factory = InstanceCellFactory(CatalogueInstanceFactory(light, emptyMap()), InstanceSpec(Interest.Total, i)),
                identity = IdentityBinding.NewInstanceOf(setStep.logicalId),
            )
        }
        roundTrip(compiled.spec) shouldBe compiled.spec
    }

    @Test
    fun `a replaced instance set takes the incumbent's id as its logicalId`() {
        registerLight()
        val incumbent = CellRef(UUID.randomUUID(), 11)
        val compiled = ok(DraftDto(listOf(DraftNodeDto("light", light, replaces = encodeRef(incumbent), replicas = 2))))

        compiled.spec.steps.single().shouldBeInstanceOf<InstanceSetStep>().logicalId shouldBe incumbent.id
        compiled.spec.lowered().map { (it as SpawnStep).identity }.toSet() shouldBe setOf(IdentityBinding.NewInstanceOf(incumbent.id))
    }

    @Test
    fun `control - a journal-less DURABLE replica set compiles, and lowering refuses it on the DURABLE nature`() {
        registerSet()
        val compiled = ok(DraftDto(listOf(DraftNodeDto("s", set, replicas = 3))))

        val failure = assertFailsWith<IllegalArgumentException> { compiled.spec.lowered() }
        failure.message!! shouldContain "refused on the DURABLE nature"
    }

    // --- edges ---

    @Test
    fun `edges lower to ConnectStep, INBOUND and OUTBOUND boundary links`() {
        registerLight()
        val liveIn = CellRef(UUID.randomUUID(), 1)
        val liveOut = CellRef(UUID.randomUUID(), 2)
        val compiled = ok(
            DraftDto(
                nodes = listOf(DraftNodeDto("a", light), DraftNodeDto("b", light)),
                edges = listOf(
                    DraftEdgeDto(DraftEndpointDto(handle = "a", port = "dataOutlet"), DraftEndpointDto(handle = "b", port = "dataInlet")),
                    DraftEdgeDto(DraftEndpointDto(ref = encodeRef(liveIn), port = "dataOutlet"), DraftEndpointDto(handle = "a", port = "dataInlet")),
                    DraftEdgeDto(DraftEndpointDto(handle = "b", port = "dataOutlet"), DraftEndpointDto(ref = encodeRef(liveOut), port = "dataInlet")),
                ),
            ),
        )

        compiled.spec.steps.drop(2) shouldBe listOf(ConnectStep("a", "dataOutlet", "b", "dataInlet"))
        compiled.boundary shouldBe listOf(
            BoundaryLink(liveIn, "dataOutlet", "a", "dataInlet", Direction.INBOUND),
            BoundaryLink(liveOut, "dataInlet", "b", "dataOutlet", Direction.OUTBOUND),
        )
    }

    @Test
    fun `an edge naming an undeclared handle compiles, and precheck refuses it UNRESOLVED_HANDLE`() {
        registerLight()
        val compiled = ok(
            DraftDto(
                nodes = listOf(DraftNodeDto("a", light)),
                edges = listOf(DraftEdgeDto(DraftEndpointDto(handle = "a", port = "dataOutlet"), DraftEndpointDto(handle = "ghost", port = "dataInlet"))),
            ),
        )
        compiled.spec.steps.last() shouldBe ConnectStep("a", "dataOutlet", "ghost", "dataInlet")

        val f = Fixture()
        val plan = compiled.spec.precheck(compiled.boundary, f.view)
        val step = plan.steps.single { it.key == "a.dataOutlet->ghost.dataInlet" }
        step.result.shouldBeInstanceOf<StepCheck.Refused>().code shouldBe RefusalCode.UNRESOLVED_HANDLE
    }

    // --- refusals: UNKNOWN_CATALOGUE_ID and every INVALID_PARAMS arm, all reported together ---

    @Test
    fun `an unknown catalogue id is refused as a SPAWN step keyed by the handle`() {
        val absent = "test.DraftCompilerTest.absent"
        val plan = refused(DraftDto(listOf(DraftNodeDto("x", absent)))).plan

        plan.verdict.shouldBeInstanceOf<Verdict.NotAppliable>().refusals shouldBe plan.steps
        val step = plan.steps.single()
        step.key shouldBe "x"
        step.handle shouldBe "x"
        step.action shouldBe PlannedAction.SPAWN
        step.touches shouldBe emptySet()
        val result = step.result.shouldBeInstanceOf<StepCheck.Refused>()
        result.code shouldBe RefusalCode.UNKNOWN_CATALOGUE_ID
        result.reason shouldContain absent
        result.reason shouldContain "'x'"
    }

    @Test
    fun `every INVALID_PARAMS arm and an unknown id are refused together, good nodes unplanned`() {
        registerTyped()
        registerSet()
        val base = goodTypedParams()
        val bad = linkedMapOf(
            "missing" to base.with("i", null),
            "unknown" to base.with("zzz", JsonPrimitive("x")),
            "stringForInt" to base.with("i", JsonPrimitive("42")),
            "fractionalLong" to base.with("l", JsonPrimitive(1.5)),
            "intOverflow" to base.with("i", JsonPrimitive(9_000_000_000L)),
            "numberForString" to base.with("s", JsonPrimitive(1)),
            "stringForBoolean" to base.with("b", JsonPrimitive("true")),
            "objectForString" to base.with("s", buildJsonObject { put("x", 1) }),
            "nullForString" to base.with("s", kotlinx.serialization.json.JsonNull),
            "enumOutside" to base.with("e", JsonPrimitive("BLUE")),
            "malformedRef" to base.with("r", JsonPrimitive("not-a-ref")),
        )
        val nodes = listOf(DraftNodeDto("good", typed, params = base), DraftNodeDto("fine", set)) +
            bad.map { (handle, params) -> DraftNodeDto(handle, typed, params = params) } +
            DraftNodeDto("nowhere", "test.DraftCompilerTest.absent")

        val plan = refused(DraftDto(nodes)).plan

        plan.steps.map { it.key } shouldBe bad.keys.toList() + "nowhere"
        plan.verdict shouldBe Verdict.NotAppliable(plan.steps)
        plan.steps.forEach { it.action shouldBe PlannedAction.SPAWN }
        val codes = plan.steps.associate { it.key to (it.result as StepCheck.Refused).code }
        codes.filterKeys { it != "nowhere" }.values.toSet() shouldBe setOf(RefusalCode.INVALID_PARAMS)
        codes["nowhere"] shouldBe RefusalCode.UNKNOWN_CATALOGUE_ID

        fun reason(handle: String) = (plan.steps.single { it.key == handle }.result as StepCheck.Refused).reason
        bad.keys.forEach { reason(it) shouldContain "draft node '$it'" }
        bad.keys.forEach { reason(it) shouldContain typed }
        reason("missing") shouldContain "'i'"
        reason("unknown") shouldContain "'zzz'"
        reason("stringForInt") shouldContain "'i' expects INT"
        reason("fractionalLong") shouldContain "'l' expects LONG"
        reason("intOverflow") shouldContain "'i' expects INT"
        reason("numberForString") shouldContain "'s' expects STRING"
        reason("stringForBoolean") shouldContain "'b' expects BOOLEAN"
        reason("objectForString") shouldContain "'s' expects STRING"
        reason("nullForString") shouldContain "'s' expects STRING"
        reason("enumOutside") shouldContain "'e'"
        reason("enumOutside") shouldContain "BLUE"
        reason("malformedRef") shouldContain "'r' expects REF"
    }

    @Test
    fun `an optional parameter may be omitted`() {
        val id = register(
            "test.DraftCompilerTest.optional",
            "civictech.cell.membrane.TrafficLightCell",
            ParamSchema(listOf(ParamSpec("tag", ParamKind.STRING, required = false))),
        ) { _, ref -> TrafficLightCell(Consumer::class.java, ref) }

        ok(DraftDto(listOf(DraftNodeDto("o", id)))).spec.steps shouldBe listOf(SpawnStep("o", CatalogueFactory(id, emptyMap())))
    }

    // --- DraftException: every va0c4-D7 DTO-level fault ---

    @Test
    fun `DTO-level faults throw DraftException naming the fault`() {
        registerLight()
        val a = DraftNodeDto("a", light)
        val live = encodeRef(CellRef(UUID.randomUUID(), 1))
        fun edge(from: DraftEndpointDto, to: DraftEndpointDto) = DraftDto(listOf(a), listOf(DraftEdgeDto(from, to)))
        val cases = linkedMapOf(
            "both" to edge(DraftEndpointDto(handle = "a", ref = live, port = "dataOutlet"), DraftEndpointDto(handle = "a", port = "dataInlet")),
            "neither" to edge(DraftEndpointDto(port = "dataOutlet"), DraftEndpointDto(handle = "a", port = "dataInlet")),
            "joins two live refs" to edge(DraftEndpointDto(ref = live, port = "dataOutlet"), DraftEndpointDto(ref = live, port = "dataInlet")),
            "'garbage' is not an encoded cell ref" to edge(DraftEndpointDto(ref = "garbage", port = "dataOutlet"), DraftEndpointDto(handle = "a", port = "dataInlet")),
            "replaces of node 'r'" to DraftDto(listOf(DraftNodeDto("r", light, replaces = "nope"))),
            "replicas must be >= 1, got 0" to DraftDto(listOf(DraftNodeDto("z", light, replicas = 0))),
            "duplicate draft handle 'a'" to DraftDto(listOf(a, a)),
            "parent is not expressible on an instance set" to DraftDto(listOf(a, DraftNodeDto("set", light, parent = "a", replicas = 2))),
        )
        cases.forEach { (fragment, draft) ->
            val failure = assertFailsWith<DraftException>(fragment) { DraftCompiler.compile(draft) }
            failure.reason shouldContain fragment
        }
    }

    @Test
    fun `a shape fault wins over a node refusal - the draft is rejected before any node resolves`() {
        assertFailsWith<DraftException> {
            DraftCompiler.compile(DraftDto(listOf(DraftNodeDto("x", "test.DraftCompilerTest.absent", replicas = -1))))
        }
    }

    // --- the wire form ---

    @Test
    fun `DraftDto decodes from the browser's JSON through inspectorJson`() {
        registerTyped()
        val json = """
            {"nodes":[{"handle":"t","catalogueId":"$typed","params":{"s":"hello","i":42,"l":9000000000,"b":true,"e":"GREEN","r":"${encodeRef(someRef)}"}}],
             "edges":[{"from":{"handle":"t","port":"dataOutlet"},"to":{"ref":"${encodeRef(someRef)}","port":"dataInlet"}}]}
        """.trimIndent()
        val draft = inspectorJson.decodeFromString(DraftDto.serializer(), json)
        inspectorJson.decodeFromString(DraftDto.serializer(), inspectorJson.encodeToString(DraftDto.serializer(), draft)) shouldBe draft

        val compiled = ok(draft)
        compiled.spec.steps.single().shouldBeInstanceOf<SpawnStep>().factory.shouldBeInstanceOf<CatalogueFactory>().params["l"] shouldBe
            ParamValue.I64(9_000_000_000L)
        compiled.boundary.single().direction shouldBe Direction.OUTBOUND
    }

    // --- end to end: compile Ok, precheck Appliable, nothing spawned (feature rule 4) ---

    private class Fixture(seed: Long = 91L) {
        val controller = SimulationController(seed = seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val view = HostLiveView(host, registry)

        /**
         * `ManagedHost.subtreeCellCount()` (PrecheckTest's witness) is
         * kernel-internal; from `:inspect` the registry's located refs and
         * links, plus every lifecycle transition, are the public witnesses.
         */
        fun footprint() = Pair(registry.all(), registry.localRefs())
    }

    /**
     * Feature seam (task 4 -> task 2 -> task 1): a draft naming the three
     * [KernelEntries] the demos register compiles, and every lowered factory
     * constructs its cell through `(id, params)` and the handed-in ref alone
     * (va0c4.4's risk-3 criterion). The kernel ids carry no test prefix, so
     * they are removed afterwards only if this test was the one to add them.
     */
    @Test
    fun `a draft over the demos' KernelEntries compiles and every lowered factory constructs its cell`() {
        val ids = listOf(KernelEntries.SET_STRING, KernelEntries.FILTER_STRING_PREFIX, KernelEntries.TRAFFIC_LIGHT_STRING)
        val added = ids.filter { Catalogue.entry(it) == null }
        KernelEntries.register()
        try {
            val compiled = ok(
                DraftDto(
                    nodes = listOf(
                        DraftNodeDto("s", KernelEntries.SET_STRING),
                        DraftNodeDto("f", KernelEntries.FILTER_STRING_PREFIX, params = buildJsonObject { put("prefix", "ab") }),
                        DraftNodeDto("t", KernelEntries.TRAFFIC_LIGHT_STRING),
                    ),
                ),
            )
            val spawns = compiled.spec.steps.map { it.shouldBeInstanceOf<SpawnStep>() }
            spawns.map { it.handle } shouldBe listOf("s", "f", "t")
            spawns[1].factory shouldBe CatalogueFactory(KernelEntries.FILTER_STRING_PREFIX, mapOf("prefix" to ParamValue.Str("ab")))

            val built = spawns.map { step ->
                val ref = CellRef(UUID.randomUUID())
                step.factory.create(ref).also { it.ref shouldBe ref }
            }
            built.map { it::class } shouldBe listOf(SetCell::class, FilterCell::class, TrafficLightCell::class)
        } finally {
            added.forEach { Catalogue.unregister(it) }
        }
    }

    @Test
    fun `a compiled draft prechecks Appliable against a live host and spawns nothing`() {
        registerLight()
        val f = Fixture()
        val liveSrc = TrafficLightCell(Consumer::class.java).also { f.host.managementInlet.call.spawn(it) }
        val liveSink = TrafficLightCell(Consumer::class.java).also { f.host.managementInlet.call.spawn(it) }
        val before = f.footprint()
        val transitions = mutableListOf<Any>()
        f.host.onLifecycle { ref, transition -> transitions += ref to transition }

        val compiled = ok(
            DraftDto(
                nodes = listOf(DraftNodeDto("a", light), DraftNodeDto("b", light), DraftNodeDto("r", light, replicas = 2)),
                edges = listOf(
                    DraftEdgeDto(DraftEndpointDto(handle = "a", port = "dataOutlet"), DraftEndpointDto(handle = "b", port = "dataInlet")),
                    DraftEdgeDto(DraftEndpointDto(ref = encodeRef(liveSrc.ref), port = "dataOutlet"), DraftEndpointDto(handle = "a", port = "dataInlet")),
                    DraftEdgeDto(DraftEndpointDto(handle = "b", port = "dataOutlet"), DraftEndpointDto(ref = encodeRef(liveSink.ref), port = "dataInlet")),
                ),
            ),
        )
        val plan = compiled.spec.precheck(compiled.boundary, f.view)

        plan.verdict shouldBe Verdict.Appliable
        plan.steps.map { it.key } shouldBe listOf(
            "a",
            "b",
            "r-0",
            "r-1",
            "a.dataOutlet->b.dataInlet",
            "${liveSrc.ref}.dataOutlet->a.dataInlet",
            "b.dataOutlet->${liveSink.ref}.dataInlet",
        )
        f.footprint() shouldBe before
        f.registry.localRefs() shouldBe setOf(liveSrc.ref, liveSink.ref)
        transitions shouldBe emptyList()
    }
}
