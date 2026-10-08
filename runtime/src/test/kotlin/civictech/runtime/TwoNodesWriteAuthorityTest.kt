package civictech.runtime

import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DeadLetter
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.data.delta.SetDelta
import civictech.cell.replication.SignedWrite
import civictech.cell.replication.WriteAuthority
import civictech.cell.replication.WriteAuthorityBytes
import civictech.cell.wire.LoopbackPeerTransport
import civictech.identity.FilePeerKeyStore
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

class TwoNodesWriteAuthorityTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `restarted authority advances its durable counter incarnation`() {
        val aKeyDirectory = tempDir.resolve("restart-a-keys")
        val bKeyDirectory = tempDir.resolve("restart-b-keys")
        val aIdentity = FilePeerKeyStore(aKeyDirectory).loadOrGenerate()
        val bIdentity = FilePeerKeyStore(bKeyDirectory).loadOrGenerate()
        val cells = mutableListOf<SetCell<String>>()
        val spec = authoritySpec(aIdentity.peerId, cells)
        val manifest = Manifest(
            mapOf(
                "a" to NodeSpec(
                    transport = "loopback",
                    listen = "loopback://authority-a",
                    keyStore = aKeyDirectory.toString(),
                    principals = listOf(encoded(bIdentity.publicKey.encoded)),
                    peerName = "a",
                ),
                "b" to NodeSpec(
                    transport = "loopback",
                    dial = listOf("a"),
                    keyStore = bKeyDirectory.toString(),
                    principals = listOf(encoded(aIdentity.publicKey.encoded)),
                    peerName = "b",
                ),
            ),
        )
        val transport = LoopbackPeerTransport(backoff = { 0L })
        val firstA = Runtime.boot(manifest, "a", spec, transport = transport)
        val b = Runtime.boot(manifest, "b", spec, transport = transport)
        var restartedA: Runtime.Node? = null
        try {
            val bDeadLetters = java.util.concurrent.CopyOnWriteArrayList<DeadLetter>()
            b.mainHost.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            bDeadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            firstA.open()
            b.open()

            val firstACell = cells[0]
            val bCell = cells[1]
            ops(firstA, firstACell).add("before-restart")
            awaitUntil("node b retains the first incarnation's write", 15_000) {
                "before-restart" in bCell.membership()
            }

            firstA.close()
            restartedA = Runtime.boot(manifest, "a", spec, transport = transport)
            restartedA.open()
            val restartedACell = cells[2]
            awaitUntil("restarted node a reconnects and receives peer catch-up", 15_000) {
                b.connections.single().isCarrying && "before-restart" in restartedACell.membership()
            }

            val framesBeforeRestartedWrite = b.connections.single().stats.framesEnqueued
            ops(restartedA, restartedACell).add("after-restart")
            awaitUntil("the restarted authority's write crosses the transport", 15_000) {
                "after-restart" in restartedACell.membership() &&
                    b.connections.single().stats.framesEnqueued > framesBeforeRestartedWrite
            }
            requireNotNull(b.bridgeHost).quiescence().await(15_000, "draining node b's peering bridge")
            b.mainHost.quiescence().await(15_000, "draining node b after the restarted write")
            awaitUntil("node b admits the restarted authority's write", 15_000) {
                "after-restart" in bCell.membership()
            }

            assertTrue(
                "after-restart" in bCell.membership(),
                "the restarted authority's first write was refused instead of advancing its counter incarnation",
            )
            // Only a REPLAY of the restarted write counts. Catch-up can hand the
            // first incarnation's write back to node b, which correctly refuses
            // that echo as REPLAY of a retained pair.
            val restartedWriteReplays = bDeadLetters.filter { letter ->
                letter.denial?.reason == DenialReason.REPLAY &&
                    letter.invocation?.invocation?.args.orEmpty().any { arg ->
                        arg is SignedWrite &&
                            (WriteAuthorityBytes.decodePayload(arg.payload) as? SetDelta<*>)
                                ?.adds?.containsKey("after-restart") == true
                    }
            }
            assertTrue(
                restartedWriteReplays.isEmpty(),
                "the restarted authority's first write was classified as REPLAY: " +
                    restartedWriteReplays.map { it.denial?.detail },
            )
        } finally {
            restartedA?.close()
            b.close()
            firstA.close()
        }
    }

    @Test
    @Timeout(60)
    fun `two ws nodes enforce and transfer replicated write authority`() {
        val aKeyStore = FilePeerKeyStore(tempDir.resolve("a-keys"))
        val bKeyStore = FilePeerKeyStore(tempDir.resolve("b-keys"))
        val aIdentity = aKeyStore.loadOrGenerate()
        val bIdentity = bKeyStore.loadOrGenerate()
        val aId = aIdentity.peerId
        val bId = bIdentity.peerId
        val cells = mutableListOf<SetCell<String>>()
        val spec = authoritySpec(aId, cells)
        val manifest = Manifest(
            mapOf(
                "a" to NodeSpec(
                    transport = "ws",
                    listen = "ws://127.0.0.1:0",
                    keyStore = tempDir.resolve("a-keys").toString(),
                    principals = listOf(encoded(bIdentity.publicKey.encoded)),
                    peerName = "a",
                ),
                "b" to NodeSpec(
                    transport = "ws",
                    dial = listOf("a"),
                    keyStore = tempDir.resolve("b-keys").toString(),
                    principals = listOf(encoded(aIdentity.publicKey.encoded)),
                    peerName = "b",
                ),
            ),
        )

        val aRuntime = Runtime.boot(manifest, "a", spec)
        var bRuntime: Runtime.Node? = null
        try {
            assertEquals(aId, aRuntime.identity?.peerId)
            val aDeadLetters = mutableListOf<DeadLetter>()
            aRuntime.mainHost.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            aDeadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            aRuntime.open()
            val grantedAddress = requireNotNull(aRuntime.boundAddress) { "node a did not expose its ws address" }

            bRuntime = Runtime.boot(
                manifest,
                "b",
                spec,
                overrides = mapOf("a" to grantedAddress.text),
            )
            bRuntime.open()

            val aCell = cells[0]
            val bCell = cells[1]
            val aSink = sink(aRuntime)
            val bSink = sink(bRuntime)
            val aOps = ops(aRuntime, aCell)
            val bOps = ops(bRuntime, bCell)

            aOps.add("apple")
            awaitUntil("node b converges on the authorized apple write", 15_000) {
                "apple" in bCell.membership()
            }
            assertEquals(0L, aSink.denialCount)
            assertEquals(0L, bSink.denialCount)

            bOps.add("banana")
            awaitUntil("node b accounts its refused local banana write", 15_000) {
                bSink.denialCount == 1L
            }
            assertFalse("banana" in aCell.membership())
            assertFalse("banana" in bCell.membership())

            aRuntime.replication.authorityOf(aRuntime.refs.getValue("items"))!!.transfer(bId)
            awaitUntil("node b learns the transfer and admits cherry", 15_000) {
                bOps.add("cherry")
                "cherry" in bCell.membership()
            }
            awaitUntil("node a converges on b's transferred-authority write", 15_000) {
                "cherry" in aCell.membership()
            }

            val aDenialsBeforeDate = aSink.denialCount
            aOps.add("date")
            awaitUntil("node a accounts its refused post-transfer date write", 15_000) {
                aSink.denialCount == aDenialsBeforeDate + 1 && aDeadLetters.any { aId.name in it.description }
            }
            assertFalse("date" in aCell.membership())
            assertFalse("date" in bCell.membership())
            assertTrue(aDeadLetters.any { aId.name in it.description }, "the refusal did not name the old authority")
        } finally {
            bRuntime?.close()
            aRuntime.close()
        }
    }

    @Test
    fun `authority boot without a key store names the missing WriteSigner seam`() {
        val failure = assertThrows<IllegalStateException> {
            Runtime.boot(
                Manifest(mapOf("a" to NodeSpec())),
                "a",
                authoritySpec(PeerId("missing-authority"), mutableListOf()),
            )
        }

        assertTrue(failure.message!!.contains("'items'"), failure.message)
        assertTrue(failure.message!!.contains("WriteSigner"), failure.message)
    }

    private fun authoritySpec(authority: PeerId, cells: MutableList<SetCell<String>>): GraphSpec {
        val logicalId = UUID.randomUUID()
        return GraphSpec(
            listOf(
                SpawnStep(
                    handle = "items",
                    factory = CellFactory { ref -> SetCell<String>(ref).also(cells::add) },
                    identity = IdentityBinding.NewInstanceOf(logicalId),
                    replicated = true,
                    authority = WriteAuthority.Principal(authority),
                ),
            ),
        )
    }

    private fun ops(runtime: Runtime.Node, cell: SetCell<String>): civictech.cell.data.SetOps<String> =
        runtime.mainHost.lookup<SetApi<String>>(cell.ref)!!.inlet.call

    private fun sink(runtime: Runtime.Node) =
        runtime.replication.authorityOf(runtime.refs.getValue("items"))!!
            .boundaryDenials["write-authority"]!!

    private fun encoded(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

}
