package civictech.demo.beadsmirror

import civictech.cell.wire.PeerAddress
import civictech.cell.graph.DespawnStep
import civictech.cell.graph.GraphSpec
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path

/**
 * Task computenet-7em.1.2: [BeadsMirrorApp]'s opt-in two-node mode — the
 * peering flags, the role they imply, and the re-baseline swap seam that
 * re-points the replica mesh at the fresh projector's cells.
 *
 * **What is deliberately NOT here.** No two-node rig and no cross-node
 * assertion: an in-test two-host rig is task computenet-7em.1.3 and the
 * two-JVM launch test is computenet-7em.1.4, and this task's non-goals name
 * both. Everything below is pure JVM — no `bd`, no `dolt`, no socket, no
 * JUnit assumption — so it is a real CI gate rather than a
 * green-but-skipped one, exactly like [BeadsMirrorAppTest.Refusal].
 *
 * **Why [RebaselineSwap] is not vacuous.** `Replication.rebind` refuses a
 * candidate whose `CellRef` differs from the incumbent's, so
 * [RebaselineSwap]'s "different refs is refused by Replication" case fails
 * loudly *only* when the swap really travels through
 * `Replication.rebind`. Delete [MirrorPeering.rebind]'s body, or the
 * `onSwap` hook that calls it, and that test goes green-by-silence — which
 * is what makes it a check on the wiring rather than on the kernel.
 */
class MirrorPeeringTest {

    @TempDir
    lateinit var runDir: Path

    /** A one-issue create record, so a projector can be given observable state. */
    private fun createRecord(height: Long, issue: String) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, 0),
        issueId = issue,
        diffType = DiffType.ADDED,
        fieldDiffs = listOf(FieldDiff("status", old = null, new = JsonPrimitive("open"))),
        edgeDiffs = emptyList(),
    )

    private fun address(text: String): PeerAddress = object : PeerAddress {
        override val scheme: String = text.substringBefore("://")
        override val text: String = text
    }

    @Nested
    inner class FlagParsing {

        @Test
        fun `--rig with --listen is the listener, and the remaining args survive`() {
            val (peering, rest) = arrayOf("--rig", "bds2", "--listen", "0", "8080").extractPeering()
            peering shouldBe MirrorPeeringSettings("bds2", MirrorWire.Listen(0))
            peering!!.role shouldBe MirrorCellRefs.LISTENER
            rest shouldBe arrayOf("8080")
        }

        @Test
        fun `--rig with --peer is the dialer, in the inline equals form too`() {
            val (peering, rest) = arrayOf("--rig=bds2", "--peer=ws://localhost:9001").extractPeering()
            peering shouldBe MirrorPeeringSettings("bds2", MirrorWire.Dial("ws://localhost:9001"))
            peering!!.role shouldBe MirrorCellRefs.DIALER
            rest shouldBe arrayOf<String>()
        }

        /** Solo mode is what "no peering flags at all" means — the default this task must not disturb. */
        @Test
        fun `no peering flag at all is solo mode and leaves the arguments untouched`() {
            val (peering, rest) = arrayOf("--workspace", "/tmp/ws", "8080").extractPeering()
            peering shouldBe null
            rest shouldBe arrayOf("--workspace", "/tmp/ws", "8080")
        }

        @Test
        fun `a rig name with no endpoint is refused rather than guessed`() {
            val failure = shouldThrow<IllegalArgumentException> { arrayOf("--rig", "bds2").extractPeering() }
            failure.message!! shouldContain "--listen"
        }

        @Test
        fun `an endpoint with no rig name is refused - there is no default that could match a peer`() {
            val failure = shouldThrow<IllegalArgumentException> { arrayOf("--listen", "0").extractPeering() }
            failure.message!! shouldContain "--rig"
        }

        @Test
        fun `--listen and --peer together name two roles and are refused`() {
            shouldThrow<IllegalArgumentException> {
                arrayOf("--rig", "bds2", "--listen", "0", "--peer", "ws://localhost:9001").extractPeering()
            }
        }

        @Test
        fun `a non-numeric --listen is refused`() {
            val failure = shouldThrow<IllegalArgumentException> {
                arrayOf("--rig", "bds2", "--listen", "nine").extractPeering()
            }
            failure.message!! shouldContain "nine"
        }

        /** Task computenet-63um5.4 (DSC2): `--discover`'s effect on the parser. */
        @Test
        fun `discover with a rig and no endpoint is the discovering end`() {
            val (peering, rest) = arrayOf("--rig", "bds2").extractPeering(discover = true)
            peering shouldBe MirrorPeeringSettings("bds2", MirrorWire.Dial(MirrorWire.Dial.DISCOVERED))
            peering!!.role shouldBe MirrorCellRefs.DIALER
            rest shouldBe arrayOf<String>()
        }

        @Test
        fun `discover with a rig and --listen is still the accepting end`() {
            val (peering, rest) = arrayOf("--rig", "bds2", "--listen", "0").extractPeering(discover = true)
            peering shouldBe MirrorPeeringSettings("bds2", MirrorWire.Listen(0))
            peering!!.role shouldBe MirrorCellRefs.LISTENER
            rest shouldBe arrayOf<String>()
        }

        @Test
        fun `discover with --peer is refused - discovery finds the peer`() {
            val failure = shouldThrow<IllegalArgumentException> {
                arrayOf("--rig", "bds2", "--peer", "ws://localhost:9001").extractPeering(discover = true)
            }
            failure.message!! shouldContain "--discover"
            failure.message!! shouldContain "--peer"
        }

        @Test
        fun `discover with no rig is refused the same way as without discover`() {
            val failure = shouldThrow<IllegalArgumentException> {
                arrayOf<String>().extractPeering(discover = true)
            }
            failure.message!! shouldContain "--rig"
        }
    }

    /**
     * computenet-emn9z: the accepting end's ([MirrorWire.Listen]) startup
     * banner under `--discover` must not claim a `ws://` endpoint built from
     * the synthetic port, and must not announce a `ws` port for `main` to
     * report to a caller. The non-discover branches are unchanged, proven by
     * asserting they still return the same line and the same announced port.
     */
    @Nested
    inner class PeeringBanner {

        @Test
        fun `discover accepting end advertises for discovery, announces no ws port`() {
            val settings = MirrorPeeringSettings("bds2", MirrorWire.Listen(0))
            val (line, announced) = peeringBanner(settings, discover = true, boundAddress = address("iroh+mdns://"))
            line shouldContain "advertising for discovery on the local segment"
            line shouldNotContain "ws://"
            announced shouldBe null
        }

        @Test
        fun `non-discover accepting end still announces the bound ws port`() {
            val settings = MirrorPeeringSettings("bds2", MirrorWire.Listen(0))
            val (line, announced) = peeringBanner(settings, discover = false, boundAddress = address("ws://localhost:54321"))
            line shouldContain "ws://localhost:54321"
            announced shouldBe 54321
        }

        @Test
        fun `discovering end is unchanged by the discover flag`() {
            val settings = MirrorPeeringSettings("bds2", MirrorWire.Dial(MirrorWire.Dial.DISCOVERED))
            val (line, announced) = peeringBanner(settings, discover = true, boundAddress = null)
            line shouldContain "discovering a peer on the local segment"
            announced shouldBe null
        }

        @Test
        fun `dialer with an explicit peer uri is unchanged`() {
            val settings = MirrorPeeringSettings("bds2", MirrorWire.Dial("ws://localhost:9001"))
            val (line, announced) = peeringBanner(settings, discover = false, boundAddress = null)
            line shouldContain "peered with ws://localhost:9001"
            announced shouldBe null
        }

        @Test
        fun `solo mode is unchanged`() {
            val (line, announced) = peeringBanner(null, discover = false, boundAddress = null)
            line shouldContain "single-node mode"
            announced shouldBe null
        }
    }

    @Nested
    inner class RoleImpliesTheSharedRefs {

        /**
         * The identity precondition of feature computenet-7em.1's rule 1, read
         * through *this* task's surface: the operator never names a role, so it
         * is the endpoint flag alone that has to produce distinct instance ids
         * for one rig name.
         */
        @Test
        fun `one rig name across the two endpoint flags yields equal logical ids and distinct instances`() {
            val listener = MirrorPeeringSettings("bds2", MirrorWire.Listen(0)).refs
            val dialer = MirrorPeeringSettings("bds2", MirrorWire.Dial("ws://localhost:9001")).refs

            listener.mapRef.id shouldBe dialer.mapRef.id
            listener.edgeRef.id shouldBe dialer.edgeRef.id
            listener.mapRef.instanceId shouldNotBe dialer.mapRef.instanceId
            listener.edgeRef.instanceId shouldNotBe dialer.edgeRef.instanceId
        }

        @Test
        fun `two rig names never share a logical id`() {
            val one = MirrorPeeringSettings("bds2", MirrorWire.Listen(0)).refs
            val other = MirrorPeeringSettings("other-rig", MirrorWire.Listen(0)).refs
            one.mapRef.id shouldNotBe other.mapRef.id
        }
    }

    @Nested
    inner class RebaselineSwap {

        private val settings = MirrorPeeringSettings("bds2-swap", MirrorWire.Listen(0))

        @Test
        fun `one topology delta respawns fresh cells under the same refs and one local replica remains`() {
            MirrorPeering(settings, runDir).use { peering ->
                val graph = peering.graph
                val incumbent = graph.projector(DotMinter("beads-scratch-solo"))
                incumbent.apply(createRecord(1, "ZOMBIE"))
                graph.host.quiescence().await(30_000, "incumbent write")
                val state = MirrorState(incumbent)

                val applied = graph.apply(
                    GraphSpec(
                        listOf(
                            DespawnStep(MirrorGraph.MAP_HANDLE),
                            DespawnStep(MirrorGraph.EDGES_HANDLE),
                        ) + graph.spec().steps,
                    ),
                )
                val rebuilt = graph.projector(DotMinter("beads-scratch-solo"), applied)
                rebuilt.apply(createRecord(2, "FRESH"))
                graph.host.quiescence().await(30_000, "rebuilt write")
                state.swap(rebuilt)

                rebuilt.cell.ref shouldBe incumbent.cell.ref
                rebuilt.edges.ref shouldBe incumbent.edges.ref
                rebuilt.view().keys shouldBe setOf("FRESH")
                peering.registry.replicasOf(peering.refs.mapRef.id) shouldBe setOf(peering.refs.mapRef)
                peering.registry.replicasOf(peering.refs.edgeRef.id) shouldBe setOf(peering.refs.edgeRef)
                state.rebaselineCount shouldBe 1
            }
        }
    }

    @Nested
    inner class BoundPort {

        /**
         * The `--listen 0` half of the flag clause (computenet-dqy.25): the
         * port a listening node announces must be the one it **bound**, never
         * the `0` it asked for. The ws binding awaits its own start
         * before returning, so this is deterministic rather than a race.
         *
         * The only test here that opens a socket — a bare local listen with no
         * peer, not the two-node rig (computenet-7em.1.3/.4).
         */
        @Test
        fun `--listen 0 reports the bound port, not the requested one`() {
            MirrorPeering(MirrorPeeringSettings("bds2-port", MirrorWire.Listen(0)), runDir).use { peering ->
                peering.boundAddress shouldBe null // nothing is bound before connect()

                peering.connect()

                val bound = peering.boundAddress
                bound.shouldNotBeNull()
                URI(bound.text).port shouldNotBe 0
            }
        }

        /** A dialer has no listener of its own, so it has no port to announce. */
        @Test
        fun `a dialer has no bound ws port`() {
            MirrorPeering(
                MirrorPeeringSettings("bds2-port", MirrorWire.Dial("ws://localhost:1")),
                runDir,
            ).use { peering ->
                peering.boundAddress shouldBe null
            }
        }
    }

    @Nested
    inner class SoloModeIsUnchanged {

        /**
         * The default [BeadsMirrorConfig] is solo: no peering settings, so
         * [BeadsMirrorApp] constructs no [MirrorPeering], no registry, no host
         * and no transport. (The rest of the equivalence claim is carried by
         * the module's whole pre-existing suite, which is unmodified by this
         * task and runs against this same default.)
         */
        @Test
        fun `a config with no peering settings is solo`() {
            BeadsMirrorConfig(workspace = java.nio.file.Path.of("/tmp/does-not-matter")).peering shouldBe null
        }

        /**
         * [MirrorState]'s swap hook defaults to a no-op, so a solo swap is the
         * plain state replacement it always was.
         */
        @Test
        fun `a MirrorState built without a swap hook just swaps`() {
            val initial = MirrorProjector(DotMinter("beads-scratch-solo"))
            val state = MirrorState(initial)
            val rebuilt = MirrorProjector(DotMinter("beads-scratch-solo"))

            state.swap(rebuilt)

            state.current shouldBe rebuilt
            state.rebaselineCount shouldBe 1
        }
    }
}
