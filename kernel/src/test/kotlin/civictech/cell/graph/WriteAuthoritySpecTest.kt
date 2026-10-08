package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.replication.AuthorityGossip
import civictech.cell.replication.Replication
import civictech.cell.replication.StubWriteSigning
import civictech.cell.replication.WriteAuthority
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ObjectInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WriteAuthoritySpecTest {
    private val principal = PeerId("graph-authority")

    @Test
    fun `authority-bearing spawn installs replication adapter with declared authority`() {
        val controller = SimulationController(seed = 71)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val signing = StubWriteSigning(principal)
        val ref = CellRef(UUID.randomUUID(), 1)
        lateinit var cell: SetCell<String>

        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "owned",
                    factory = CellFactory { chosen -> SetCell<String>(chosen).also { cell = it } },
                    identity = IdentityBinding.Exact(ref),
                    replicated = true,
                    authority = WriteAuthority.Principal(principal),
                ),
            ),
        ).apply(
            ApplyContext(
                host = host,
                replication = replication,
                writeSigner = signing.signer(principal),
                signatureVerifier = signing.verifier,
            ),
        )
        controller.runToIdle()

        val adapter = replication.authorityOf(ref).shouldNotBeNull()
        host.lookup<SetCell<String>>(ref) shouldBe cell
        host.lookup<AuthorityGossip>(adapter.ref).shouldNotBeNull()
    }

    @Test
    fun `authority requires replication and cannot be declared on a keyed family`() {
        val authority = WriteAuthority.Principal(principal)

        shouldThrow<IllegalArgumentException> {
            SpawnStep(
                handle = "unreplicated",
                factory = CellFactory { SetCell<String>(it) },
                authority = authority,
            )
        }.message shouldBe "spawn step 'unreplicated': parameter 'authority' requires 'replicated'"

        shouldThrow<IllegalArgumentException> {
            SpawnStep(
                handle = "family",
                factory = civictech.cell.graph.KeyedCellFactory { _, ref -> SetCell<String>(ref) },
                family = KeyedFamily("authority-family"),
                replicated = true,
                authority = authority,
            )
        }.message shouldBe "spawn step 'family': a keyed family cannot declare authority"
    }

    @Test
    fun `missing authority seams fail before applySpawn creates or hosts a cell`() {
        val controller = SimulationController(seed = 72)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val context = ApplyContext(host = host, replication = Replication(registry))
        val ref = CellRef(UUID.randomUUID(), 1)
        var factoryCalls = 0
        val event = TopoEvent.Spawn(
            handle = "owned",
            ref = ref,
            factory = CellFactory {
                factoryCalls++
                SetCell<String>(it)
            },
            parent = null,
            replicated = true,
            journalId = null,
            shadow = false,
            authority = WriteAuthority.Principal(principal),
        )

        val failure = shouldThrow<IllegalStateException> { context.applySpawn(event) }
        failure.message shouldBe
            "spawn step 'owned': parameter 'authority' requires a WriteSigner and a " +
            "SignatureVerifier on the ApplyContext"
        factoryCalls shouldBe 0
        host.lookup<SetCell<String>>(ref) shouldBe null
    }

    @Test
    fun `remote authority spawn is refused by parameter and use builders refuse it too`() {
        val ref = CellRef(UUID.randomUUID(), 1)
        val authority = WriteAuthority.Principal(principal)
        val step = SpawnStep(
            handle = "owned",
            factory = CellFactory { SetCell<String>(it) },
            identity = IdentityBinding.Exact(ref),
            replicated = true,
            authority = authority,
        )
        val spec = GraphSpec(listOf(step))
        val host = ManagedHost()

        val localFailure = shouldThrow<IllegalStateException> { spec.applyTo(host.managementInlet) }
        localFailure.message shouldContain "owned"
        localFailure.message shouldContain "authority"

        val report = spec.applyRemote(host.managementInlet)
        val rejection = report.results.getValue("owned") as StepResult.Rejected
        rejection.reason shouldBe "spawn step 'owned': parameter 'authority' is not supported by applyRemote"
        host.lookup<SetCell<String>>(ref) shouldBe null

        val builderFailure = shouldThrow<IllegalStateException> {
            graph(host.managementInlet) {
                spawn(
                    "builder-owned",
                    replicated = true,
                    authority = authority,
                ) { SetCell<String>(it) }
            }
        }
        builderFailure.message shouldBe
            "spawn step 'builder-owned': parameter 'authority' cannot be applied by " +
            "graph(Use<HostManagementApi>); use apply(ApplyContext)"
    }

    @Test
    fun `journaled authority spawn recovers with its adapter reinstalled`() {
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID(), 1)
        val signing = StubWriteSigning(principal)

        val firstController = SimulationController(seed = 73)
        val firstRegistry = LocationRegistry()
        lateinit var firstContext: ApplyContext
        val firstHost = ManagedHost(
            scheduler = firstController.scheduler(),
            registry = firstRegistry,
            journalFor = { cellRef -> firstContext.journalFor(cellRef) },
        )
        firstContext = ApplyContext(
            host = firstHost,
            replication = Replication(firstRegistry),
            topology = journal,
            writeSigner = signing.signer(principal),
            signatureVerifier = signing.verifier,
        )
        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "owned",
                    factory = DurableAuthorityFactory,
                    identity = IdentityBinding.Exact(ref),
                    replicated = true,
                    authority = WriteAuthority.Principal(principal),
                ),
            ),
        ).apply(firstContext)
        firstController.runToIdle()
        firstContext.live().spawns.getValue(ref).authority shouldBe WriteAuthority.Principal(principal)

        val recoveredController = SimulationController(seed = 74)
        val recoveredRegistry = LocationRegistry()
        lateinit var recoveredContext: ApplyContext
        val recoveredHost = ManagedHost(
            scheduler = recoveredController.scheduler(),
            registry = recoveredRegistry,
            journalFor = { cellRef -> recoveredContext.journalFor(cellRef) },
        )
        recoveredContext = ApplyContext(
            host = recoveredHost,
            replication = Replication(recoveredRegistry),
            topology = journal,
            writeSigner = signing.signer(principal),
            signatureVerifier = signing.verifier,
        )
        val recovery = recoveredContext.recover(journal)
        recoveredController.runToIdle()
        recovery.awaitApplied(30_000)

        val adapter = recoveredContext.replication!!.authorityOf(ref).shouldNotBeNull()
        recoveredHost.lookup<AuthorityGossip>(adapter.ref).shouldNotBeNull()
        recoveredContext.live().spawns.getValue(ref).authority shouldBe WriteAuthority.Principal(principal)
    }

    @Test
    fun `base topology spawn fixture decodes with absent authority as open`() {
        val event = javaClass.getResourceAsStream("/graph/topo-spawn-7d68c57f.ser")!!.use { input ->
            ObjectInputStream(input).use { it.readObject() as TopoEvent.Spawn }
        }

        event.authority shouldBe null
        (event.authority ?: WriteAuthority.Open) shouldBe WriteAuthority.Open
    }

    private object DurableAuthorityFactory : CellFactory {
        private val cells = ConcurrentHashMap<CellRef, SetCell<String>>()

        override fun create(ref: CellRef): SetCell<String> = SetCell<String>(ref).also { cells[ref] = it }
    }
}
