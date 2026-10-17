package civictech.bench.nexmark

import civictech.cell.data.Windows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal val WINDOWED_TEST_SCALE = NexmarkScale(events = 2_000, eventsPerSecond = 50)
internal const val WINDOWED_TEST_SEED = 7L
internal const val WINDOWED_BATCH_SIZE = 100

internal fun windowedEvents(): List<Event> =
    NexmarkGenerator.generate(WINDOWED_TEST_SEED, WINDOWED_TEST_SCALE)

internal fun windowedBatches(events: List<Event> = windowedEvents()): List<Batch> =
    events.chunked(WINDOWED_BATCH_SIZE).mapIndexed { index, batch ->
        Batch(index + 1, batch.map(StreamOp::Add))
    }

internal fun runWindowed(
    query: NexmarkQuery,
    policy: LatenessPolicy?,
    batches: List<Batch> = windowedBatches(),
    hooks: NexmarkRunHooks = NexmarkRunHooks(),
): NexmarkRecord.Verified = assertInstanceOf(
    NexmarkRecord.Verified::class.java,
    NexmarkRun.run(
        query = query,
        seed = WINDOWED_TEST_SEED,
        scale = WINDOWED_TEST_SCALE,
        policy = policy,
        drive = Drive.SIM,
        batchSize = WINDOWED_BATCH_SIZE,
        retraction = RetractionPlan(0.0),
        batches = batches,
        env = nexmarkTestEnvironment(),
        hooks = hooks,
    ),
)

internal fun assertClockAfterEveryBatch(phases: List<String>, batchCount: Int) {
    val expected = buildList {
        add("attach")
        (1..batchCount).forEach { index ->
            add("insert-$index")
            add("clock-$index")
        }
    }
    assertEquals(expected, phases)
}

class Q7Q8ReferenceTest {
    @Test
    fun `Q7 and Q8 without a policy verify as unbounded state`() {
        listOf(NexmarkQuery.Q7, NexmarkQuery.Q8).forEach { query ->
            val verified = runWindowed(query, policy = null)

            assertEquals(StateLabel.UNBOUNDED_STATE, verified.header.stateLabel, query.name)
            assertNull(verified.header.lateness, query.name)
        }
    }

    @Test
    fun `Q7 with a policy verifies evicts old windows and clocks every batch`() {
        val events = windowedEvents()
        val batches = windowedBatches(events)
        val phases = mutableListOf<String>()
        val policy = LatenessPolicy(5_000)
        val verified = runWindowed(
            query = NexmarkQuery.Q7,
            policy = policy,
            batches = batches,
            hooks = NexmarkRunHooks(
                rigHooks = NexmarkRigHooks(phaseQuiesced = phases::add),
            ),
        )
        val reference = NexmarkReference.evaluate(NexmarkQuery.Q7, batches, policy)
        val accounting = requireNotNull(verified.header.lateness)
        val tumbling = Windows.tumbling(NexmarkSpec.WINDOW_MS)
        val windowsTouched = events.filterIsInstance<Bid>()
            .map { bid -> tumbling(bid.dateTime) }
            .toSet()
            .size.toLong()
        val windowsLive = verified.header.retainedState.liveRows.getValue("q7-windows")

        assertEquals(StateLabel.Evicting(5_000), verified.header.stateLabel)
        assertEquals(reference.floor, accounting.floor)
        assertTrue(
            windowsLive < windowsTouched,
            "Q7 eviction expected fewer live windows than touched: live=$windowsLive touched=$windowsTouched",
        )
        assertTrue(accounting.evicted > 0, "Q7 expected at least one evicted window")
        assertClockAfterEveryBatch(phases, batches.size)
    }

    @Test
    fun `Q8 with a policy verifies evicts old rows and clocks every batch`() {
        val events = windowedEvents()
        val batches = windowedBatches(events)
        val phases = mutableListOf<String>()
        val policy = LatenessPolicy(5_000)
        val verified = runWindowed(
            query = NexmarkQuery.Q8,
            policy = policy,
            batches = batches,
            hooks = NexmarkRunHooks(
                rigHooks = NexmarkRigHooks(phaseQuiesced = phases::add),
            ),
        )
        val reference = NexmarkReference.evaluate(NexmarkQuery.Q8, batches, policy)
        val accounting = requireNotNull(verified.header.lateness)
        val tumbling = Windows.tumbling(NexmarkSpec.WINDOW_MS)
        val windowsTouched = events.mapNotNull { event ->
            when (event) {
                is Person -> WindowKey(event.id, tumbling(event.dateTime))
                is Auction -> WindowKey(event.seller, tumbling(event.dateTime))
                is Bid -> null
            }
        }.toSet().size.toLong()
        val rowsLive = verified.header.retainedState.liveRows.getValue("q8-output")

        assertEquals(StateLabel.Evicting(5_000), verified.header.stateLabel)
        assertEquals(reference.floor, accounting.floor)
        assertTrue(reference.evicted > 0, "Q8 reference expected evicted input rows")
        assertTrue(
            rowsLive < windowsTouched,
            "Q8 eviction expected fewer live result rows than touched window keys: live=$rowsLive touched=$windowsTouched",
        )
        assertClockAfterEveryBatch(phases, batches.size)
    }
}
