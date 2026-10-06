package civictech.cell.host

import civictech.cell.BudgetOutcome
import civictech.cell.BudgetRefusedException
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.Consumer
import civictech.cell.DenialReason
import civictech.cell.RecordingLedger
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.KeyCodec
import civictech.cell.graph.KeyedCellFactory
import civictech.cell.graph.KeyedFamily
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TopoEvent
import civictech.cell.graph.graphOf
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.Interest
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** vb7aq-D1..D4/D7..D12 — admitted bounded interest materializes durable keyed members. */
class InterestSpawnTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class TriggerCell(
        private val action: () -> Unit,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet by input<Consumer<Unit>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Unit> {
                override fun provide(input: Unit) = action()
            })
        }
    }

    private val provide = Consumer::class.java.methods.first { it.name == "provide" }

    private fun longFamily(
        host: ManagedHost,
        journalDir: File? = null,
        namespace: String = "authored",
    ): KeyedCells<Long> = KeyedCells(
        host = host,
        journalDir = journalDir,
        namespace = namespace,
        factory = { _, ref -> SetCell<String>(ref) },
        render = Long::toString,
        parse = String::toLong,
        spawnOnInterest = true,
    )

    private fun opsFor(registry: LocationRegistry, cell: Cell): SetOps<String> =
        (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun membership(cell: Cell): Set<String> = (cell as SetCell<String>).membership()

    private fun familyKeys(journal: Journal): List<TopoEvent.FamilyKey> =
        journal.replay()
            .map(JournalRecords::decode)
            .filterIsInstance<DecodedJournalRecord.Topology>()
            .flatMap { it.events }
            .filterIsInstance<TopoEvent.FamilyKey>()

    private fun failed(future: CompletableFuture<*>): Throwable {
        var failure: Throwable = shouldThrow<ExecutionException> {
            future.get(5, TimeUnit.SECONDS)
        }.cause!!
        while (failure is CompletionException && failure.cause != null) failure = failure.cause!!
        return failure
    }

    @Test
    fun `a DSL family spawns exactly the keys in a bounded admitted range`() {
        val controller = SimulationController(seed = 1)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val (family, spec) = graphOf(ApplyContext(host)) {
            family(
                name = "authored",
                namespace = "authored",
                keys = KeyCodec.Longs,
                spawnOnInterest = true,
                factory = KeyedCellFactory { _, ref -> SetCell<String>(ref) },
            )
        }
        val interest = Interest.Ranges(listOf(Interest.Ranges.Range(2, 4)))
        val declaringRef = CellRef(UUID.randomUUID())

        val admission = registry.setInterest(declaringRef, interest)
        controller.runToIdle()

        (spec.steps.single() as SpawnStep).family!!.spawnOnInterest.shouldBeTrue()
        admission.ref shouldBe declaringRef
        admission.interest shouldBe interest
        family.keys() shouldBe setOf(2L, 3L)
        family.contains(1L).shouldBeFalse()
        family.contains(4L).shouldBeFalse()
        val expected = setOf(family.getOrSpawn(2L).ref, family.getOrSpawn(3L).ref)
        admission.spawned.get(5, TimeUnit.SECONDS) shouldBe expected
        expected.forEach { registry.location(it).shouldNotBeNull() }
    }

    @Test
    fun `repeated declarations join pending or live members and journal each key once`() {
        val controller = SimulationController(seed = 2)
        val registry = LocationRegistry()
        val journal = InMemoryJournal()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journal)
        val family = longFamily(host)
        family.getOrSpawn(5L)
        val declaringRef = CellRef(UUID.randomUUID())
        val range = Interest.Ranges(listOf(Interest.Ranges.Range(2, 4)))

        val first = registry.setInterest(declaringRef, range)
        val pendingDuplicate = registry.setInterest(declaringRef, range)
        val alreadyTouched = registry.setInterest(
            declaringRef,
            Interest.Ranges(listOf(Interest.Ranges.Range(5, 6))),
        )
        controller.runToIdle()

        first.spawned.get(5, TimeUnit.SECONDS).size shouldBe 2
        pendingDuplicate.spawned.get(5, TimeUnit.SECONDS) shouldBe first.spawned.get(5, TimeUnit.SECONDS)
        alreadyTouched.spawned.get(5, TimeUnit.SECONDS) shouldBe setOf(family.getOrSpawn(5L).ref)
        family.keys() shouldBe setOf(2L, 3L, 5L)
        familyKeys(journal).groupingBy { it.key }.eachCount() shouldBe mapOf("2" to 1, "3" to 1, "5" to 1)
    }

    @Test
    fun `unbounded arms stay recorded and fail the admission without spawning`() {
        val registry = LocationRegistry()
        val family = longFamily(ManagedHost(registry = registry))
        val declaringRef = CellRef(UUID.randomUUID())
        val arms = listOf(
            Interest.Total,
            Interest.Slots(setOf(0), 2),
            Interest.Union(listOf(Interest.Ranges(listOf(Interest.Ranges.Range(1, 2))))),
            Interest.Intersect(listOf(Interest.Ranges(listOf(Interest.Ranges.Range(1, 2))))),
            Interest.Complement(Interest.Empty),
        )

        arms.forEach { arm ->
            val admission = registry.setInterest(declaringRef, arm)

            registry.interestOf(declaringRef) shouldBe arm
            val refusal = failed(admission.spawned).shouldBeInstanceOf<InterestSpawnRefused>()
            refusal.namespace shouldBe "authored"
            refusal.arm shouldBe arm.javaClass.simpleName
            family.keys() shouldBe emptySet()
        }
    }

    @Test
    fun `interest declared on the host thread spawns asynchronously without dead letter`() {
        val scheduler = VirtualThreadScheduler("interest-spawn")
        try {
            val registry = LocationRegistry()
            val host = ManagedHost(scheduler = scheduler, registry = registry)
            val family = longFamily(host)
            val declared = CompletableFuture<InterestAdmission>()
            val declaringRef = CellRef(UUID.randomUUID())
            val trigger = TriggerCell(action = {
                declared.complete(
                    registry.setInterest(
                        declaringRef,
                        Interest.Ranges(listOf(Interest.Ranges.Range(7, 8))),
                    ),
                )
            })
            host.managementInlet.call.spawn(trigger)

            host.routerInlet.call.route(
                trigger.ref,
                "inlet",
                Invocation.of(provide, arrayOf(Unit)),
            )

            val admission = declared.get(5, TimeUnit.SECONDS)
            admission.spawned.get(5, TimeUnit.SECONDS).size shouldBe 1
            family.contains(7L).shouldBeTrue()
            host.supervisionAccounting().deadLetters shouldBe 0L
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `interest-spawned membership and state recover without redeclaring interest`(@TempDir dir: File) {
        val firstController = SimulationController(seed = 3)
        val firstRegistry = LocationRegistry()
        val firstJournal = KeyedCells.hostJournal(dir)!!
        val firstHost = ManagedHost(
            scheduler = firstController.scheduler(),
            registry = firstRegistry,
            journal = firstJournal,
        )
        val firstFamily = longFamily(firstHost, dir)
        val declaringRef = CellRef(UUID.randomUUID())
        val admission = firstRegistry.setInterest(
            declaringRef,
            Interest.Ranges(listOf(Interest.Ranges.Range(5, 6))),
        )
        firstController.runToIdle()
        admission.spawned.get(5, TimeUnit.SECONDS)
        opsFor(firstRegistry, firstFamily.getOrSpawn(5L)).add("kept")
        firstController.runToIdle()

        val decoded = firstJournal.replay().map(JournalRecords::decode)
        val keyIndex = decoded.indexOfFirst { record ->
            record is DecodedJournalRecord.Topology && record.events.any { it is TopoEvent.FamilyKey }
        }
        val frameIndex = decoded.indexOfFirst { it is DecodedJournalRecord.Frame }
        keyIndex shouldBe 0
        (frameIndex > keyIndex).shouldBeTrue()
        familyKeys(firstJournal) shouldBe listOf(TopoEvent.FamilyKey("authored", "5"))

        val recoveredController = SimulationController(seed = 4)
        val recoveredRegistry = LocationRegistry()
        val recoveredHost = ManagedHost(
            scheduler = recoveredController.scheduler(),
            registry = recoveredRegistry,
            journal = KeyedCells.hostJournal(dir),
        )
        val recoveredFamily = longFamily(recoveredHost, dir)
        recoveredFamily.recover()
        recoveredController.runToIdle()

        recoveredRegistry.interestOf(declaringRef) shouldBe Interest.Total
        recoveredFamily.keys() shouldBe setOf(5L)
        membership(recoveredFamily.getOrSpawn(5L)) shouldBe setOf("kept")
    }

    @Test
    fun `budget refusal leaves the recorded interest but no member or family key`() {
        val controller = SimulationController(seed = 5)
        val registry = LocationRegistry()
        val journal = InMemoryJournal()
        val ledger = RecordingLedger("host", refuse = { claim ->
            if (claim.claimClass == ClaimClass.Spawn) {
                BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "host", shortfall = 1)
            } else {
                null
            }
        })
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journal = journal,
            budget = ledger,
        )
        val family = longFamily(host)
        val declaringRef = CellRef(UUID.randomUUID())
        val interest = Interest.Ranges(listOf(Interest.Ranges.Range(9, 10)))
        val stamp = PeerStamp(PeerId("p"), AuthLevel.Authenticated)

        val admission = CurrentPeer.withStamp(stamp) { registry.setInterest(declaringRef, interest) }
        controller.runToIdle()

        val refusal = failed(admission.spawned).shouldBeInstanceOf<BudgetRefusedException>()
        refusal.denial.reason.name.startsWith("BUDGET_").shouldBeTrue()
        registry.interestOf(declaringRef) shouldBe interest
        family.keys() shouldBe emptySet()
        family.contains(9L).shouldBeFalse()
        familyKeys(journal) shouldBe emptyList()
        ledger.charges.map { it.claimClass } shouldBe listOf(ClaimClass.Spawn)
        ledger.charges.single().stamp shouldBe stamp

        val localController = SimulationController(seed = 6)
        val localRegistry = LocationRegistry()
        val localLedger = RecordingLedger("local")
        val localFamily = longFamily(
            ManagedHost(
                scheduler = localController.scheduler(),
                registry = localRegistry,
                budget = localLedger,
            ),
            namespace = "local-authored",
        )
        val localAdmission = localRegistry.setInterest(declaringRef, interest)
        localController.runToIdle()

        localAdmission.spawned.get(5, TimeUnit.SECONDS).size shouldBe 1
        localFamily.keys() shouldBe setOf(9L)
        localLedger.charges shouldBe emptyList()
    }

    @Test
    fun `pre-change KeyedFamily fixture defaults interest spawning off`() {
        val fixture = Base64.getDecoder().decode(
            "rO0ABXNyACBjaXZpY3RlY2guY2VsbC5ncmFwaC5LZXllZEZhbWlseSP6FdaHA2qrAgADTAAJam91cm5hbElkdAASTGphdmEvbGFuZy9TdHJpbmc7TAAEa2V5c3QAH0xjaXZpY3RlY2gvY2VsbC9ncmFwaC9LZXlDb2RlYztMAAluYW1lc3BhY2VxAH4AAXhwdAABanNyAB1jaXZpY3RlY2guY2VsbC5ncmFwaC5LZXlDb2RlYy+O2odosrVMAgACTAAFcGFyc2V0ACBMa290bGluL2p2bS9mdW5jdGlvbnMvRnVuY3Rpb24xO0wABnJlbmRlcnEAfgAGeHBzcgAvY2l2aWN0ZWNoLmNlbGwuZ3JhcGguS2V5Q29kZWMkQ29tcGFuaW9uJExvbmdzJDJBvyMsrfh38AIAAHhyABprb3RsaW4uanZtLmludGVybmFsLkxhbWJkYZFOGvDP6Ts3AgABSQAFYXJpdHl4cAAAAAFzcgAvY2l2aWN0ZWNoLmNlbGwuZ3JhcGguS2V5Q29kZWMkQ29tcGFuaW9uJExvbmdzJDGSlK519X/AZwIAAHhxAH4ACQAAAAF0AAJucw==",
        )
        val decoded = ObjectInputStream(ByteArrayInputStream(fixture)).use { it.readObject() as KeyedFamily }

        ObjectStreamClass.lookup(KeyedFamily::class.java).serialVersionUID shouldBe 2592408546637474475L
        ObjectStreamClass.lookup(KeyCodec::class.java).serialVersionUID shouldBe 3426916641587508556L
        decoded.namespace shouldBe "ns"
        decoded.journalId shouldBe "j"
        decoded.keys.parse("7") shouldBe 7L
        decoded.keys.render(8L) shouldBe "8"
        decoded.spawnOnInterest.shouldBeFalse()
        KeyedFamily("new").spawnOnInterest.shouldBeFalse()
    }

    @Test
    fun `interest spawning requires a host registry and does not replay old declarations`() {
        val missing = shouldThrow<IllegalStateException> {
            longFamily(ManagedHost())
        }
        missing.message shouldContain "spawnOnInterest needs a host with a registry"

        val controller = SimulationController(seed = 7)
        val registry = LocationRegistry()
        val declaringRef = CellRef(UUID.randomUUID())
        registry.setInterest(
            declaringRef,
            Interest.Ranges(listOf(Interest.Ranges.Range(1, 2))),
        ).spawned.get(5, TimeUnit.SECONDS) shouldBe emptySet()

        val family = longFamily(ManagedHost(scheduler = controller.scheduler(), registry = registry))
        controller.runToIdle()
        family.keys() shouldBe emptySet()
    }
}
