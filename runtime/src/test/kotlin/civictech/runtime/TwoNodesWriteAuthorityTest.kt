package civictech.runtime

import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.replication.WriteAuthority
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
