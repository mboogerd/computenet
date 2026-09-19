package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.durability.Journal
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.HostScheduler
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash-restart of a `--journal` [SocialApp] (SOC1 F7, feature
 * `computenet-v10ou`): B16 here; B17 (a truncated `keys` log refuses to
 * serve) belongs to the same feature's refusal task. Rules: [SOC1-DUR-01]
 * (every family journals through the shared root WAL beside its own `keys`
 * log) and [SOC1-DUR-03] (a restart reproduces the graph a fresh process gets
 * from the same event prefix, with no removed element resurrected).
 *
 * **In-process, by decision 07k-D7.** Each "process" is a [SocialApp] on its
 * own [SimulationController] over one `@TempDir`; the crash is simply ceasing
 * to reference app 1 — no `stop()`, no checkpoint, nothing flushed beyond what
 * the WAL already holds. A multi-JVM (`JvmPeer`) variant is a non-goal: every
 * WAL append and every `keys` line is fsync'd at write time, so what survives
 * a real process death is exactly what app 2 reads here, and the property
 * under test — replay over pre-spawned keys equals the live graph — does not
 * depend on the process boundary.
 */
class SocialCrashRestartTest {

    /** App 1's run over [dir]: load + half the stream + one `removeKnows`, then dropped. */
    private class Crashed(
        val n: Int,
        val a: Long,
        val b: Long,
        val date: Long,
        val snapshot: BatchModel.Relations,
        val statics: SocialApp.StaticSets,
    )

    /**
     * Runs "process 1" on its own [SimulationController]: loads the seed-42
     * slice, applies N = half the update stream, removes one live `knows`
     * edge, settles, snapshots, asserts [SOC1-DUR-01]'s on-disk layout, and
     * then drops the app — no `stop()`, no checkpoint.
     */
    private fun crashAfterPrefix(dir: File): Crashed {
        val c1 = SimulationController(42)
        val app1 = SocialApp(port = 0, journalDir = dir, scheduler = c1.scheduler())
        SocialLoader.load(SOURCE, app1.graph)
        val stream1 = UpdateStream(SOURCE, app1.graph)
        val n = stream1.remaining / 2
        assertTrue(n >= 1, "the seed-42 stream must have at least two events, had ${stream1.remaining}")
        stream1.step(n)
        c1.runToIdle()

        val (a, edge) = BatchModel.observe(app1.graph).knows.entries
            .first { it.value.isNotEmpty() }
            .let { (a, edges) -> a to edges.first() }
        app1.graph.removeKnows(a, edge.otherId, edge.creationDate)
        c1.runToIdle()

        val snap1 = BatchModel.observe(app1.graph)
        val statics1 = app1.staticSets()
        assertTrue(snap1.personIds.isNotEmpty(), "the prefix produced persons to recover")
        assertTrue(
            statics1.tags.isNotEmpty() && statics1.tagClasses.isNotEmpty() &&
                statics1.places.isNotEmpty() && statics1.organisations.isNotEmpty(),
            "the seed-42 slice loaded every static set: $statics1",
        )
        assertEquals(0, app1.deadLetterCount(), "app 1's live run dead-lettered nothing")
        assertFalse(snap1.knows[a].orEmpty().any { it.otherId == edge.otherId }, "app 1 applied the removal")

        // [SOC1-DUR-01]: one root WAL, one keys log per family, all non-empty.
        val wal = File(dir, KeyedCells.HOST_JOURNAL)
        assertTrue(wal.isFile && wal.length() > 0, "WAL $wal exists and is non-empty")
        for (family in listOf("person", "authored", "forum", "message")) {
            val keys = File(File(dir, family), KeyedCells.KEYS_FILE)
            assertTrue(keys.isFile && keys.length() > 0, "keys log $keys exists and is non-empty")
        }
        return Crashed(n, a, edge.otherId, edge.creationDate, snap1, statics1)
    }

    /** Asserts [recovered] equals [expected] relation by relation, so a failure names the relation. */
    private fun assertRelations(expected: BatchModel.Relations, recovered: BatchModel.Relations, label: String) {
        expected.byName().zip(recovered.byName()).forEach { (e, r) ->
            assertEquals(e.second, r.second, "relation=${e.first}: recovered != $label")
        }
        assertEquals(expected, recovered)
    }

    /**
     * A correct recovery lands every journaled frame: no `unknown cell` dead
     * letter (the four static sets used to carry fresh refs per build and lost
     * all their frames this way), and the static sets equal app 1's.
     */
    private fun assertStaticsAndNoDeadLetters(crashed: Crashed, app2: SocialApp) {
        assertEquals(0, app2.deadLetterCount(), "a correct recovery dead-letters nothing")
        assertEquals(crashed.statics, app2.staticSets(), "recovered static sets != pre-drop static sets")
    }

    private fun assertNotResurrected(crashed: Crashed, recovered: BatchModel.Relations) {
        assertFalse(recovered.knows[crashed.a].orEmpty().any { it.otherId == crashed.b }, "removed edge ${crashed.a}->${crashed.b} resurrected")
        assertFalse(recovered.knows[crashed.b].orEmpty().any { it.otherId == crashed.a }, "removed edge ${crashed.b}->${crashed.a} resurrected")
    }

    @Test
    fun `B16 an in-process restart equals the pre-drop snapshot and a fresh replay of the same prefix`(@TempDir dir: File) {
        val crashed = crashAfterPrefix(dir)

        // --- app 2: recover from the same directory -------------------------
        val c2 = SimulationController(42)
        val app2 = SocialApp(port = 0, journalDir = dir, scheduler = c2.scheduler())
        c2.runToIdle()
        app2.completeRecovery()
        app2.completeRecovery() // idempotent
        // Never started: no DemoShell was built, so no port is bound (v10ou-D3).
        assertFailsWith<IllegalStateException> { app2.boundPort }
        val snap2 = BatchModel.observe(app2.graph)

        // --- app 3: a fresh, ephemeral app fed the same prefix + removal ----
        val c3 = SimulationController(42)
        val app3 = SocialApp(port = 0, scheduler = c3.scheduler())
        SocialLoader.load(SOURCE, app3.graph)
        UpdateStream(SOURCE, app3.graph).step(crashed.n)
        app3.graph.removeKnows(crashed.a, crashed.b, crashed.date)
        c3.runToIdle()
        val snap3 = BatchModel.observe(app3.graph)

        assertEquals(crashed.snapshot.personIds, app2.graph.personIds(), "recovered personIds")
        assertRelations(crashed.snapshot, snap2, "pre-drop snapshot")
        assertRelations(snap3, snap2, "fresh replay of the prefix")
        assertNotResurrected(crashed, snap2)
        assertStaticsAndNoDeadLetters(crashed, app2)
        assertEquals(app3.staticSets(), app2.staticSets(), "recovered static sets != fresh replay's")
    }

    /**
     * The production path: app 2 on the default `VirtualThreadScheduler`,
     * whose drain thread runs WHILE [SocialRecovery.stage] replays. [SocialApp.start]
     * must drain the staged replay behind its quiescence fence and complete
     * recovery before it binds a port, so the graph is settled the moment
     * `start()` returns — read here with no polling.
     *
     * This is also the case that discriminates `stage()`'s order. On a
     * `SimulationController` both halves only enqueue (spawn at management
     * priority 0, replayed frames at data priority 20) and nothing runs until
     * the test steps, so swapping them is invisible there; here the live drain
     * thread dispatches replayed frames as they are submitted, and a frame that
     * reaches a not-yet-spawned cell is dead-lettered.
     */
    @Test
    fun `B16 on the production scheduler start drains the replay before it binds and serves the recovered graph`(@TempDir dir: File) {
        val crashed = crashAfterPrefix(dir)

        val app2 = SocialApp(port = 0, journalDir = dir)
        assertFailsWith<IllegalStateException> { app2.boundPort }
        app2.start()
        try {
            assertTrue(app2.boundPort > 0, "bound after recovery completed")
            val snap2 = BatchModel.observe(app2.graph)
            assertRelations(crashed.snapshot, snap2, "pre-drop snapshot")
            assertNotResurrected(crashed, snap2)
            assertStaticsAndNoDeadLetters(crashed, app2)
        } finally {
            app2.stop()
        }
    }

    /**
     * Delays the first [delayed] data-band (priority 20) tasks by [delayMs]
     * each, so the replay's drain is observably slow on a live
     * [VirtualThreadScheduler]. Everything else passes straight through.
     */
    private class SlowDataScheduler(
        private val inner: HostScheduler,
        delayed: Int,
        private val delayMs: Long,
    ) : HostScheduler by inner {
        private val remaining = AtomicInteger(delayed)
        val slowedTasks = AtomicLong()

        override fun submit(priority: Int, action: suspend () -> Unit) {
            if (priority == DATA_BAND && remaining.getAndDecrement() > 0) {
                inner.submit(priority) {
                    Thread.sleep(delayMs)
                    slowedTasks.incrementAndGet()
                    action()
                }
            } else {
                inner.submit(priority, action)
            }
        }
    }

    /**
     * v10ou-D3's fence, made observable: with the replay's data-band dispatch
     * slowed to ~1 s, `start()` must still return only once every replayed
     * frame has landed. Without the fence `start()` returns while most frames
     * are still queued, and the immediate read sees a partial graph.
     */
    @Test
    fun `start waits for a slow replay to drain before it binds`(@TempDir dir: File) {
        val crashed = crashAfterPrefix(dir)

        val slow = SlowDataScheduler(VirtualThreadScheduler("SocialCrashRestartTest-slow"), delayed = 500, delayMs = 2)
        val app2 = SocialApp(port = 0, journalDir = dir, scheduler = slow)
        try {
            app2.start()
            assertTrue(slow.slowedTasks.get() > 0, "the decorator slowed the replay")
            val snap2 = BatchModel.observe(app2.graph)
            assertRelations(crashed.snapshot, snap2, "pre-drop snapshot, read the instant start() returned")
            assertStaticsAndNoDeadLetters(crashed, app2)
        } finally {
            app2.stop()
        }
    }

    /**
     * `computenet-2v3e4`'s durable half (v10ou-D6): a family key minted by a
     * creating write that then fails stays on disk with no journal record to
     * match it ([KeyedCells] has no un-mint), and a naive restart pre-spawns
     * that key into an empty cell nothing ever replays into — resurrecting
     * the ghost into `/state`. Built from PIECES rather than [SocialApp],
     * since [SocialApp] has no `journalFor` seam: person 2's cell alone
     * refuses its journal append (the same injection
     * [SocialJournalTest]'s "a write that fails after the family key is
     * minted leaves no entity behind" uses to pin the live half), while
     * everything else — including person 1 — keeps the real host WAL. Then a
     * real restart follows: a plain [SocialApp] recovers the same [dir].
     *
     * Mutation check: with [SocialGraph.suppressUnwrittenKeys] removed from
     * [SocialRecovery.complete], `personIds()` reads `{1, 2}` after the
     * restart — prove the mutation landed (`git diff HEAD --
     * SocialRecovery.kt` non-empty) before trusting that result, then
     * restore.
     */
    @Test
    fun `computenet-2v3e4 a ghost key stays hidden across a restart`(@TempDir dir: File) {
        // --- app 1, from pieces: person 2's cell alone refuses its journal --
        val refused = CellRef(UUID.nameUUIDFromBytes("snb-person:2".toByteArray()))
        val wal = KeyedCells.hostJournal(dir)!!
        val refusing = object : Journal {
            override fun append(record: ByteArray) = throw IllegalStateException("refused by test")
            override fun replay(): List<ByteArray> = emptyList()
            override fun reset(records: List<ByteArray>) = Unit
        }
        val host1 = ManagedHost(
            registry = LocationRegistry(),
            journalFor = { ref -> if (ref == refused) refusing else wal },
        )
        val pipeline1 = SnbPipeline.build(host1, dir)
        val graph1 = SocialGraph(host1, pipeline1)

        graph1.addPerson(Person(1, "Ada", "Lovelace"))
        assertFailsWith<Exception>("the refused write must surface to the caller") {
            graph1.addPerson(Person(2, "Ghost", "Ly"))
        }

        assertEquals(setOf(1L), graph1.personIds(), "the ghost must not be an entity before the restart")
        assertTrue(2L in pipeline1.families.person.keys(), "the family key is expected to have been minted")

        // --- app 2: a real restart, through a plain SocialApp on the same dir
        val c2 = SimulationController(42)
        val app2 = SocialApp(port = 0, journalDir = dir, scheduler = c2.scheduler())
        c2.runToIdle()
        app2.completeRecovery()

        assertEquals(sortedSetOf(1L), app2.graph.personIds(), "the ghost key must stay hidden across a restart")
        assertTrue(
            app2.graph.personFacts(1).any { it is PersonFact.Profile },
            "person 1's own facts must survive the restart",
        )
    }

    /**
     * A [Journal] decorator (v10ou-D8) over [inner] whose [replay] records, at
     * the moment it is called, whether every on-disk key of every family
     * under [dir] resolves to a live cell on [host] — the [SOC1-DUR-02] order
     * assertion: pre-spawn ([SocialGraph.spawnKnown]) must have run before
     * [ManagedHost.recoverFrom] ever calls [Journal.replay]. `append`/`reset`
     * delegate straight through; only [replay] is observed.
     */
    private class RecordingJournal(
        private val inner: Journal,
        private val host: ManagedHost,
        private val dir: File,
    ) : Journal {
        var replayCalls = 0
            private set

        var allLiveAtReplay: Boolean? = null
            private set

        override fun append(record: ByteArray) = inner.append(record)

        override fun replay(): List<ByteArray> {
            replayCalls++
            allLiveAtReplay = onDiskKeys(dir).all { (namespace, key) ->
                host.lookup(TypedRef<SetApi<Any>>(CellRef(UUID.nameUUIDFromBytes("$namespace:$key".toByteArray())))) != null
            }
            return inner.replay()
        }

        override fun reset(records: List<ByteArray>) = inner.reset(records)
    }

    /**
     * [SOC1-DUR-02]: every key in every family's `keys` file must have a live
     * cell at the moment [Journal.replay] is called — pre-spawn before
     * replay, not after. Pieces-level, mirroring `SocialJournalTest`'s shape
     * (`:169-174`).
     *
     * The positive case builds [SocialRecovery] directly (its own contract:
     * [SocialGraph.spawnKnown] before `host.recoverFrom`) over a
     * [RecordingJournal] — `replay()` must run exactly once and every
     * on-disk key must already be live when it does.
     *
     * The discriminator is a THIRD host that calls `host3.recoverFrom` over
     * its own [RecordingJournal] with no [SocialGraph]/`spawnKnown` ever run
     * against it: the same on-disk keys read NOT live at `replay()`, and
     * after `runToIdle` the host has dead-lettered every replayed frame.
     */
    @Test
    fun `SOC1-DUR-02 keys are live before the first replayed frame is delivered`(@TempDir dir: File) {
        crashAfterPrefix(dir)

        // --- correct order: SocialRecovery.stage() spawns known keys first ---
        val c2 = SimulationController(42)
        val host2 = ManagedHost(scheduler = c2.scheduler(), registry = LocationRegistry(), journal = KeyedCells.hostJournal(dir))
        val pipeline2 = SnbPipeline.build(host2, dir)
        val graph2 = SocialGraph(host2, pipeline2)
        val recording2 = RecordingJournal(KeyedCells.hostJournal(dir)!!, host2, dir)
        SocialRecovery(host2, graph2, recording2).stage()
        c2.runToIdle()

        assertEquals(1, recording2.replayCalls, "replay() must run exactly once")
        assertEquals(
            true,
            recording2.allLiveAtReplay,
            "every on-disk key must be live at replay() when spawnKnown ran first",
        )

        // --- discriminator: recoverFrom with no cell ever pre-spawned --------
        val c3 = SimulationController(42)
        val host3 = ManagedHost(scheduler = c3.scheduler(), registry = LocationRegistry())
        val recording3 = RecordingJournal(KeyedCells.hostJournal(dir)!!, host3, dir)
        host3.recoverFrom(recording3)
        c3.runToIdle()

        assertEquals(1, recording3.replayCalls, "replay() must run exactly once")
        assertEquals(
            false,
            recording3.allLiveAtReplay,
            "no on-disk key can be live at replay() when recoverFrom ran before any cell was spawned",
        )
        assertTrue(
            host3.supervisionAccounting().deadLetters > 0,
            "the wrong order dead-letters every replayed frame",
        )
    }

    /**
     * B17 ([SOC1-DUR-04]): a `keys` file missing entries the WAL references
     * makes recovery refuse loudly, naming the missing person, and the app
     * never binds a port. [SocialApp.start] completes recovery ([SocialApp.completeRecovery])
     * before it ever constructs `DemoShell` (v10ou-D3) — a thrown
     * `completeRecovery()` propagates straight out of `start()`, so no shell
     * is built and [SocialApp.boundPort] still throws "not started".
     */
    @Test
    fun `B17 a truncated keys file fails recovery loudly and binds no port`(@TempDir dir: File) {
        crashAfterPrefix(dir)

        val keysFile = File(File(dir, "person"), KeyedCells.KEYS_FILE)
        val lines = keysFile.readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 2, "the prefix must have minted at least two persons to truncate, had ${lines.size}")
        val kept = lines.size / 2
        val dropped = lines.drop(kept)
        assertTrue(dropped.isNotEmpty(), "truncation must drop at least one line")
        keysFile.writeText(lines.take(kept).joinToString("\n", postfix = "\n"))
        val droppedId = dropped.first().trim()

        val c2 = SimulationController(42)
        val app2 = SocialApp(port = 0, journalDir = dir, scheduler = c2.scheduler())
        c2.runToIdle()

        val refusal = assertFailsWith<IllegalStateException> { app2.completeRecovery() }
        assertTrue(
            refusal.message?.contains("snb-person:$droppedId") == true,
            "refusal must name the dropped person snb-person:$droppedId: ${refusal.message}",
        )

        // A refused recovery is not retried into serving: start() re-attempts
        // completeRecovery(), throws the same way, and never builds DemoShell.
        assertFailsWith<IllegalStateException> { app2.start() }
        assertFailsWith<IllegalStateException> { app2.boundPort }
    }

    /**
     * The change broadcast after v10ou.1's lazy per-sink listeners: an app
     * whose sinks all exist BEFORE `start()` (a preloaded source) must still
     * push a change frame over `/events` for a write to one of those
     * pre-existing cells — the retro-attach path in [SocialGraph.onChange].
     * The first frame is only the catch-up snapshot, so the test waits for a
     * LATER frame that carries the change.
     */
    @Test
    fun `after start a write to a preloaded cell reaches SSE subscribers as a change frame`() {
        val app = SocialApp(port = 0, source = SOURCE).start()
        try {
            val (a, b) = app.graph.personIds().let { ids ->
                ids.asSequence().flatMap { x -> ids.asSequence().map { y -> x to y } }
                    .first { (x, y) -> x < y && app.graph.personFacts(x).none { it is Knows && it.otherId == y } }
            }
            val url = "http://localhost:${app.boundPort}"
            val posted = AtomicBoolean(false)
            val frame = awaitSseData("$url/events", timeoutMs = 20_000) { line ->
                if (posted.compareAndSet(false, true)) {
                    // The first frame is the catch-up; write only once subscribed.
                    Thread { HttpProbe(url).use { it.post("action=knows&a=$a&b=$b&date=7") } }.start()
                    false
                } else {
                    knowsOf(line, a).contains(b)
                }
            }
            assertTrue(knowsOf(frame, a).contains(b), "change frame carries $a knows $b")
        } finally {
            app.stop()
        }
    }

    /** The `knows` ids of person [id] in one `/state` JSON frame (jo2jk-D6 shape). */
    private fun knowsOf(frame: String, id: Long): List<Long> =
        Regex("""\{"id":$id,"name":"[^"]*","knows":\[([0-9,]*)]""").find(frame)
            ?.groupValues?.get(1)?.split(",")?.filter { it.isNotEmpty() }?.map { it.toLong() }
            ?: emptyList()

    private companion object {
        val SOURCE = SnbGenerator(42, 0.05)

        /** ManagedHost's data band: every hosted-invocation dispatch, replayed frames included. */
        const val DATA_BAND = 20
    }
}

/**
 * Every `(namespace, key)` pair on disk under [dir], one per line of each
 * family's `keys` file — file-scope so [SocialCrashRestartTest.RecordingJournal],
 * a plain nested class with no implicit outer access, can call it too.
 */
private fun onDiskKeys(dir: File): List<Pair<String, String>> =
    listOf("person" to "snb-person", "authored" to "snb-authored", "forum" to "snb-forum", "message" to "snb-message")
        .flatMap { (folder, namespace) ->
            File(File(dir, folder), KeyedCells.KEYS_FILE)
                .takeIf { it.isFile }
                ?.readLines()
                ?.filter { it.isNotBlank() }
                ?.map { namespace to it.trim() }
                .orEmpty()
        }
