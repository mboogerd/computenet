package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.durability.Journal
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `journalDir != null` half of `:demo:social` (bug `computenet-5ab6f`,
 * epic `computenet-07k`) — the configuration `SocialServerTest` never
 * constructs, and the one that was wholly broken when this file was written.
 *
 * Two independent defects are pinned here, because fixing either one alone
 * leaves the other live:
 *
 * 1. **Nothing could be journaled.** `ManagedHost` write-ahead-encodes every
 *    accepted invocation through `WireCodec`'s polymorphic `Any` scope, and
 *    `:demo:social`'s payload types were registered in no `SerializersModule`
 *    — so under `--journal` every `/op` threw
 *    `SerializationException: Serializer for subclass 'Profile' is not found
 *    in the polymorphic scope of 'Any'`, which (being an
 *    `IllegalArgumentException`) `SocialApp.handleOp` reported to the client
 *    as a 400. [journalled app serves the jo2jk-D6 op sequence and writes a non-empty host journal]
 *    and [SOC1-HTTP-04 a rejected op leaves journalled state byte-identical]
 *    cover that.
 * 2. **A failed write left a ghost entity.** `SocialGraph` mints a family key
 *    through `KeyedCells.getOrSpawn` *before* the inlet call that can throw,
 *    and the key is what `/state` enumerates — so a rejected op still changed
 *    `/state` ([SOC1-HTTP-04]).
 *    [a write that fails after the family key is minted leaves no entity behind]
 *    covers that one **without** relying on defect 1: it refuses the write at
 *    the journal itself, so it still discriminates once the serializers are
 *    registered.
 *
 * Observed at branch head `a24c26c9` (= `demo/social` as merged in PR #925):
 * all three fail, the first two on `HTTP 400 Serializer for subclass
 * 'Profile' …`, the third on `/state` reporting the ghost person.
 */
class SocialJournalTest {

    /** The op sequence `SocialServerTest`'s `happy path pins the exact state shape from jo2jk-D6` applies. */
    private val happyPath = listOf(
        "action=person&id=1&firstName=Ada&lastName=Lovelace",
        "action=person&id=2&firstName=Bob&lastName=Brown",
        "action=knows&a=1&b=2",
        "action=forum&id=100&title=f&moderator=1",
        "action=join&person=1&forum=100",
        "action=post&id=10&author=1&forum=100&content=hi",
        "action=comment&id=11&author=2&replyOf=10&content=reply",
        "action=like&person=2&message=10",
    )

    private val settledCounts =
        """"counts":{"persons":2,"knows":2,"forums":1,"messages":2,"likes":1,"tags":0}"""

    @Test
    fun `journalled app serves the jo2jk-D6 op sequence and writes a non-empty host journal`(@TempDir dir: File) {
        val journalled = SocialApp(port = 0, journalDir = dir).start()
        val ephemeral = SocialApp(port = 0).start()
        try {
            val onJournal = HttpProbe("http://localhost:${journalled.boundPort}")
            val onMemory = HttpProbe("http://localhost:${ephemeral.boundPort}")

            // journalRequests/memoryRequests double as the acceptance criterion's
            // demonstrable request log: each increment corresponds to one real
            // HTTP POST dispatched over the wire (postForm always sends), so the
            // counts below are direct evidence of how many /op requests reach
            // each app, not a restatement of the loop's own iteration count.
            // Before computenet-s0zdp's fix, kotlin.test.assertEquals evaluated
            // its message argument eagerly, so the failure message's own
            // `onJournal.postForm(op)` call fired a second, unwanted POST on
            // every one of these 8 ops — journalRequests read 16, not 8.
            val journalRequests = AtomicInteger(0)
            val memoryRequests = AtomicInteger(0)
            fun postToJournal(op: String): HttpResponse<String> {
                journalRequests.incrementAndGet()
                return onJournal.postForm(op)
            }
            fun postToMemory(op: String): HttpResponse<String> {
                memoryRequests.incrementAndGet()
                return onMemory.postForm(op)
            }

            happyPath.forEach { op ->
                val journalResponse = postToJournal(op)
                assertEquals(200, journalResponse.statusCode(), "journalled /op rejected: $op -> ${journalResponse.body()}")
                val memoryResponse = postToMemory(op)
                assertEquals(200, memoryResponse.statusCode(), "ephemeral /op rejected: $op -> ${memoryResponse.body()}")
            }

            assertEquals(
                happyPath.size,
                journalRequests.get(),
                "expected exactly one /op POST per happy-path op to reach the journalled app, saw ${journalRequests.get()}",
            )
            assertEquals(
                happyPath.size,
                memoryRequests.get(),
                "expected exactly one /op POST per happy-path op to reach the ephemeral app, saw ${memoryRequests.get()}",
            )

            val journalledState = onJournal.await { settledCounts in it }
            val ephemeralState = onMemory.await { settledCounts in it }
            assertEquals(ephemeralState, journalledState, "--journal /state must match the ephemeral app's /state")

            val wal = File(dir, KeyedCells.HOST_JOURNAL)
            assertTrue(wal.isFile, "expected a host WAL at ${wal.path}; journal tree was ${dir.walkTopDown().toList()}")
            assertTrue(wal.length() > 0, "host WAL ${wal.path} exists but is empty — nothing was journaled")

            // The layout SnbPipeline's KDoc describes, checked rather than asserted in prose
            // (`computenet-5ab6f`): the WAL is at the root, every family's `keys` log is one
            // level DOWN in that family's own subdirectory, and no family has a host journal
            // of its own — which is why `family.recover()` would replay nothing.
            listOf("person", "authored", "forum", "message").forEach { family ->
                val keys = File(File(dir, family), KeyedCells.KEYS_FILE)
                assertTrue(keys.isFile, "expected ${keys.path}; journal tree was ${dir.walkTopDown().toList()}")
                val familyWal = File(File(dir, family), KeyedCells.HOST_JOURNAL)
                assertTrue(!familyWal.exists(), "no per-family host journal should exist, found ${familyWal.path}")
            }
        } finally {
            journalled.stop()
            ephemeral.stop()
        }
    }

    @Test
    fun `SOC1-HTTP-04 a rejected op leaves journalled state byte-identical`(@TempDir dir: File) {
        val app = SocialApp(port = 0, journalDir = dir).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            assertEquals(200, probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace"))
            val before = probe.await { """"persons":1""" in it }

            // A missing required param, an unknown referenced id, and an unknown action.
            assertEquals(400, probe.post("action=person&id=7&firstName=Ghost"))
            assertEquals(400, probe.post("action=knows&a=1&b=99"))
            assertEquals(400, probe.post("action=nonsense&id=7"))

            assertEquals(before, probe.state(), "a rejected /op changed /state under --journal")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `a write that fails after the family key is minted leaves no entity behind`(@TempDir dir: File) {
        // The deterministic ref of person 2's cell ([SOC1-SCHEMA-05]): the one cell
        // whose journal refuses. Everything else — the management band that spawns
        // cells, person 1, the observe sinks — keeps the real file journal, so the
        // ONLY thing this injection breaks is the `add` invocation of person 2.
        val refused = CellRef(UUID.nameUUIDFromBytes("snb-person:2".toByteArray()))
        val wal = KeyedCells.hostJournal(dir)!!
        val refusing = object : Journal {
            override fun append(record: ByteArray) = throw IllegalStateException("refused by test")
            override fun replay(): List<ByteArray> = emptyList()
            override fun reset(records: List<ByteArray>) = Unit
        }
        val host = ManagedHost(
            registry = LocationRegistry(),
            journalFor = { ref -> if (ref == refused) refusing else wal },
        )
        val pipeline = SnbPipeline.build(host, dir)
        val graph = SocialGraph(host, pipeline)

        graph.addPerson(Person(1, "Ada", "Lovelace"))
        assertThrows<Exception>("the refused write must surface to the caller") {
            graph.addPerson(Person(2, "Ghost", "Ly"))
        }

        assertEquals(
            setOf(1L),
            graph.personIds(),
            "a person whose only write failed must not be an entity — /state enumerates exactly these ids",
        )
        // The durable key IS minted before the write (KeyedCells has no un-mint),
        // which is precisely why the id has to be suppressed at the read side.
        assertTrue(2L in pipeline.families.person.keys(), "the family key is expected to have been minted")
    }
}
