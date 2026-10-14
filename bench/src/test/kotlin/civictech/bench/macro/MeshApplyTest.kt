package civictech.bench.macro

import civictech.bench.macro.mesh.BenchMesh
import civictech.bench.macro.mesh.CrossHostEdges
import civictech.bench.macro.mesh.MeshBuild
import civictech.bench.macro.mesh.MeshRefs
import civictech.bench.macro.mesh.MeshTopology
import civictech.cell.BoundedStateful
import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.TaggedEntry
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.KeyedCellFactory
import civictech.cell.graph.KeyedFamily
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.TypedCellFactory
import civictech.cell.graph.UnlinkStep
import civictech.cell.link.PeerId
import civictech.cell.replication.WriteAuthority
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

class MeshApplyTest {

    @Test
    fun deltaWrittenAtPeerZeroReachesSinkOnLastPeer() {
        for (hostCount in listOf(2, 3, 5)) {
            val mesh = built(hostCount)
            mesh.apply(passThroughSpec(lastPeer = hostCount - 1))
            val edgeName = "peer0<->peer${hostCount - 1}"
            val framesBeforeWrite = mesh.world.traceEvents().count { it.port == edgeName }

            val expected = (1L..5L).toSet()
            expected.forEach(mesh.sourceInlet("source0")::add)
            mesh.runToIdle()

            operatorMembership(mesh, "sink") shouldBe expected
            (mesh.world.traceEvents().count { it.port == edgeName } > framesBeforeWrite) shouldBe true
            mesh.deadLetters.shouldBeEmpty()
        }
    }

    @Test
    fun singleHostChainDeliversZeroFramesThroughEdges() {
        val mesh = built(1)
        mesh.apply(passThroughSpec(lastPeer = 0, includeMiddle = true))

        val expected = (1L..5L).toSet()
        expected.forEach(mesh.sourceInlet("source0")::add)
        mesh.runToIdle()

        operatorMembership(mesh, "sink") shouldBe expected
        mesh.world.traceEvents().none { it.port?.contains("<->") == true } shouldBe true
        mesh.crossHostEdges shouldBe CrossHostEdges.NotPresentAtThisCoordinate
        mesh.deadLetters.shouldBeEmpty()
    }

    @Test
    fun crossHostEdgeWithUnexpectedInletIsRefused() {
        val crossHost = built(2)
        val error = shouldThrow<IllegalArgumentException> {
            crossHost.apply(directSetSpec(targetPeer = 1, inlet = "deltaInlet"))
        }
        error.message.orEmpty() shouldContain "sink"
        error.message.orEmpty() shouldContain "deltaInlet"

        val local = built(2)
        local.apply(directSetSpec(targetPeer = 0, inlet = "deltaInlet"))
        local.sourceInlet("source0").add(7L)
        local.runToIdle()
        setMembership(local, "sink") shouldBe setOf(7L)
    }

    @Test
    fun crossHostLeftAndRightInletsAreRouted() {
        val mesh = built(2)
        mesh.apply(
            GraphSpec(
                listOf(
                    spawnSet("source0", "host0"),
                    spawnSet("source1", "host0"),
                    SpawnStep(
                        handle = "join",
                        factory = TypedCellFactory<IntersectSetCell<Long>> { IntersectSetCell(it) },
                        placement = "host1",
                    ),
                    ConnectStep("source0", "outlet", "join", "left"),
                    ConnectStep("source1", "outlet", "join", "right"),
                ),
            ),
        )

        mesh.sourceInlet("source0").add(11L)
        mesh.sourceInlet("source1").add(11L)
        mesh.runToIdle()

        operatorMembership(mesh, "join", subState = "ledger") shouldBe setOf(11L)
        mesh.deadLetters.shouldBeEmpty()
    }

    @Test
    fun replicationSpawnsEverySourceOnEveryPeerAndLinksLocally() {
        val mesh = built(hostCount = 3, replication = true)
        val refs = mesh.apply(passThroughSpec(lastPeer = 2, sourcePlacement = null))
        mesh.runToIdle()

        val primary = MeshRefs.ref("source0", 0, 3)
        refs.getValue("source0") shouldBe primary
        for (peerIndex in 0..2) {
            mesh.replicaRef("source0", peerIndex) shouldBe MeshRefs.ref("source0", peerIndex, 3)
            mesh.peers[peerIndex].registry.replicasOf(primary.id).size shouldBe 3
        }
        mesh.peers[2].registry.localLinks().any {
            it.from.cell == mesh.replicaRef("source0", 2) && it.to.cell == refs.getValue("sink")
        } shouldBe true

        val expected = (1L..5L).toSet()
        expected.forEach(mesh.sourceInlet("source0", peer = 0)::add)
        mesh.runToIdle()

        operatorMembership(mesh, "sink") shouldBe expected
        mesh.deadLetters.shouldBeEmpty()
    }

    @Test
    fun replicationOffConstructsNoReplicationAndRefusesReplicatedSteps() {
        val mesh = built(hostCount = 3, replication = false)
        mesh.peers.all { it.replication == null } shouldBe true

        val error = shouldThrow<IllegalArgumentException> {
            mesh.apply(
                GraphSpec(
                    listOf(
                        SpawnStep(
                            handle = "source0",
                            factory = setFactory(),
                            replicated = true,
                            placement = "host0",
                        ),
                    ),
                ),
            )
        }
        error.message.orEmpty() shouldContain "source0"
    }

    @Test
    fun replicationRefusesNonReplicableSourcesAndReplicatedTargets() {
        val nonReplicable = shouldThrow<IllegalArgumentException> {
            built(hostCount = 2, replication = true).apply(
                GraphSpec(
                    listOf(
                        SpawnStep(
                            handle = "source-filter",
                            factory = TypedCellFactory<FilterCell<Long>> { FilterCell(it) { true } },
                        ),
                    ),
                ),
            )
        }
        nonReplicable.message.orEmpty() shouldContain "source-filter"

        val replicatedTarget = shouldThrow<IllegalArgumentException> {
            built(hostCount = 2, replication = true).apply(
                GraphSpec(
                    listOf(
                        SpawnStep("source0", setFactory()),
                        SpawnStep("source1", setFactory()),
                        ConnectStep("source0", "outlet", "source1", "deltaInlet"),
                    ),
                ),
            )
        }
        replicatedTarget.message.orEmpty() shouldContain "source1"
    }

    @Test
    fun malformedPlacementIsRefused() {
        for (placement in listOf<String?>("host7", null)) {
            val mesh = built(2)
            val error = shouldThrow<IllegalArgumentException> {
                mesh.apply(
                    GraphSpec(
                        listOf(
                            SpawnStep("node", setFactory(), placement = placement),
                        ),
                    ),
                )
            }
            error.message.orEmpty() shouldContain "node"
        }
    }

    @Test
    fun identityBindingIsIgnoredInFavorOfMeshRefs() {
        val mesh = built(2)
        val otherRef = CellRef(UUID.randomUUID(), 99L)
        val refs = mesh.apply(
            GraphSpec(
                listOf(
                    SpawnStep(
                        handle = "node",
                        factory = setFactory(),
                        identity = IdentityBinding.Exact(otherRef),
                        placement = "host1",
                    ),
                ),
            ),
        )

        refs.getValue("node") shouldBe MeshRefs.ref("node", 1, 2)
        mesh.cell("node").ref shouldBe MeshRefs.ref("node", 1, 2)
    }

    @Test
    fun unsupportedSpawnParametersAreRefusedByHandle() {
        val cases = listOf(
            SpawnStep(
                handle = "family-node",
                factory = KeyedCellFactory { _, ref -> SetCell<Long>(ref) },
                family = KeyedFamily("family"),
                placement = "host0",
            ),
            SpawnStep(
                handle = "inputs-node",
                factory = setFactory(),
                journalId = "journal",
                inputs = setOf("writes"),
                placement = "host0",
            ),
            SpawnStep(
                handle = "authority-node",
                factory = setFactory(),
                replicated = true,
                authority = WriteAuthority.Principal(PeerId("writer")),
                placement = "host0",
            ),
            SpawnStep(
                handle = "replicated-node",
                factory = setFactory(),
                replicated = true,
                placement = "host0",
            ),
            SpawnStep(
                handle = "journal-node",
                factory = setFactory(),
                journalId = "journal",
                placement = "host0",
            ),
            SpawnStep(
                handle = "shadow-node",
                factory = setFactory(),
                shadow = true,
                placement = "host0",
            ),
        )

        for (step in cases) {
            val error = shouldThrow<IllegalArgumentException> {
                built(1).apply(GraphSpec(listOf(step)))
            }
            error.message.orEmpty() shouldContain step.handle
        }
    }

    @Test
    fun unsupportedGraphStepAndFactoryRefMismatchAreRefused() {
        val unsupported = shouldThrow<IllegalArgumentException> {
            built(1).apply(GraphSpec(listOf(UnlinkStep("from", "outlet", "to", "inlet"))))
        }
        unsupported.message.orEmpty() shouldContain "UnlinkStep"

        val mismatch = shouldThrow<IllegalArgumentException> {
            built(1).apply(
                GraphSpec(
                    listOf(
                        SpawnStep(
                            handle = "wrong-ref",
                            factory = TypedCellFactory<SetCell<Long>> { SetCell() },
                            placement = "host0",
                        ),
                    ),
                ),
            )
        }
        mismatch.message.orEmpty() shouldContain "wrong-ref"
    }

    /** `apply`'s KDoc declares a mesh one-shot: the second call is refused, not merged. */
    @Test
    fun secondApplyIsRefused() {
        val mesh = built(1)
        mesh.apply(GraphSpec(listOf(spawnSet("first", "host0"))))

        val error = shouldThrow<IllegalStateException> {
            mesh.apply(GraphSpec(listOf(spawnSet("second", "host0"))))
        }
        error.message.orEmpty() shouldContain "only be called once"
    }

    private fun built(hostCount: Int, replication: Boolean = false): BenchMesh =
        (BenchMesh.build(hostCount, 1, MeshTopology.FULL_MESH, { null }, replication) as MeshBuild.Built).mesh

    private fun passThroughSpec(
        lastPeer: Int,
        sourcePlacement: String? = "host0",
        includeMiddle: Boolean = false,
    ): GraphSpec {
        val steps = mutableListOf<GraphStep>(
            SpawnStep("source0", setFactory(), placement = sourcePlacement),
        )
        if (includeMiddle) {
            steps += spawnFilter("middle", "host$lastPeer")
        }
        steps += spawnFilter("sink", "host$lastPeer")
        steps += if (includeMiddle) {
            listOf(
                ConnectStep("source0", "outlet", "middle", "inlet"),
                ConnectStep("middle", "outlet", "sink", "inlet"),
            )
        } else {
            listOf(ConnectStep("source0", "outlet", "sink", "inlet"))
        }
        return GraphSpec(steps)
    }

    private fun directSetSpec(targetPeer: Int, inlet: String): GraphSpec =
        GraphSpec(
            listOf(
                spawnSet("source0", "host0"),
                spawnSet("sink", "host$targetPeer"),
                ConnectStep("source0", "outlet", "sink", inlet),
            ),
        )

    private fun spawnSet(handle: String, placement: String): SpawnStep =
        SpawnStep(handle, setFactory(), placement = placement)

    private fun spawnFilter(handle: String, placement: String): SpawnStep =
        SpawnStep(
            handle = handle,
            factory = TypedCellFactory<FilterCell<Long>> { FilterCell(it) { true } },
            placement = placement,
        )

    private fun setFactory(): TypedCellFactory<SetCell<Long>> =
        TypedCellFactory { SetCell(it) }

    @Suppress("UNCHECKED_CAST")
    private fun setMembership(mesh: BenchMesh, handle: String): Set<Long> =
        (mesh.cell(handle) as SetCell<Long>).membership()

    private fun operatorMembership(mesh: BenchMesh, handle: String, subState: String = "live"): Set<Long> =
        (mesh.cell(handle) as BoundedStateful)
            .readBounded(StateRead(limit = 100))
            .entries
            .filterIsInstance<TaggedEntry>()
            .filter { it.subState == subState }
            .map { it.element as Long }
            .toSet()
}
