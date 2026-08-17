package civictech.demo.beadsmirror.e2e

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Feature computenet-7em.2's transport-parameterized convergence suite (task
 * computenet-7em.2.2): seeded two-sided [SeededSchedule]s run concurrently
 * against BOTH of a [TwoNodeRig]'s scratch workspaces, then, at idle, both
 * nodes' materialized folds must be equal.
 *
 * **Transport injection (feature rule 3, shared with computenet-7em.2.1).**
 * This class is constructed with [newRig] — a factory for the rig under
 * test — rather than naming a transport itself, and nothing in this file
 * imports `civictech.wire` or [civictech.demo.beadsmirror.WsMirrorTransport]:
 * a future transport (DSC0, epic computenet-7em §3) is a different [newRig]
 * supplied to a different concrete subclass, with zero edits here.
 * [TwoNodeRig.create] takes the binding as a parameter (computenet-7em.2.3)
 * and defaults it to [civictech.demo.beadsmirror.WsMirrorTransport], the only
 * one that exists; [WsConvergenceSuiteTest] is that default's only production
 * instantiation.
 *
 * **Partition and heal ride the same parameter** (task computenet-7em.2.3):
 * the mid-schedule partition case below drives [TwoNodeRig.partition] /
 * [TwoNodeRig.heal], which delegate to the injected binding, so it re-runs
 * over a future transport unedited like every other case here.
 *
 * **Idle, precisely.** [TwoNodeRig.Node.quiesce] on both nodes first (each
 * node's own persisted checkpoint reaches its own workspace's `dolt_log`
 * head — every record OF ITS OWN WORKSPACE applied), then [TwoNodeRig.await]
 * on fold equality — the residual wait is for the LAST cross-node deltas the
 * concurrent schedule produced to finish gossiping. Bounded, never a sleep.
 */
abstract class ConvergenceSuite(private val newRig: () -> TwoNodeRig) {

    @BeforeEach
    fun checkPrerequisites() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    /**
     * Recorded seed [SeededSchedule.SEED_1] — the design's own worked
     * example (`seed 42`).
     */
    @Test
    fun `seed 1 converges to equal folds at idle`() {
        assertConverges(SeededSchedule.SEED_1)
    }

    @Test
    fun `seed 2 converges to equal folds at idle`() {
        assertConverges(SeededSchedule.SEED_2)
    }

    @Test
    fun `seed 3 converges to equal folds at idle`() {
        assertConverges(SeededSchedule.SEED_3)
    }

    // Pinned regression seeds go here as one more `@Test` each, per
    // SeededSchedule's "pinned failing seeds" doc — never replacing one of
    // the three above.

    /**
     * Feature rule 2 / design example 2 (task computenet-7em.2.3): the
     * schedule half-applied, the peering severed, five more mutations landing
     * on EACH side while severed, the peering healed — and at idle both folds
     * equal *and* carrying all ten partition-era issues.
     *
     * **Why the ten ids are asserted explicitly and not only through
     * equality.** Two nodes that both dropped every partition-era mutation are
     * equal, so `fold(L) == fold(D)` alone cannot tell repair from mutual
     * amnesia. The check that carries the meaning is
     * [SeededSchedule.partitionIssueIds] present in BOTH folds with the field
     * value its minting side wrote ([SeededSchedule.partitionDesign]).
     *
     * **The non-arrival check is one bounded read, not a wait.** Proving the
     * partition actually severed anything needs a negative, and a negative
     * cannot be awaited — so this case waits (bounded) until the listener's
     * OWN fold carries its first partition-era issue, and then reads the
     * dialer's served status for that id exactly once: 404 there, at a moment
     * the listener demonstrably holds it, is a statement about the peering
     * with no sleep in it.
     *
     * **This case rides the transport parameter** like every other in this
     * class — it says "the peering is down", never "the socket is closed"
     * ([TwoNodeRig.partition] delegates to the injected binding), so a future
     * transport re-runs it unedited.
     */
    @Test
    fun `a partition mid-schedule heals into equal folds carrying every partition-era mutation`() {
        val seed = SeededSchedule.SEED_1
        val schedule = SeededSchedule.derive(seed)
        val rig = newRig()
        try {
            rig.startListener()
            rig.startDialer()

            // 1. the schedule to its halfway point, both sides concurrently.
            val listenerHalf = schedule.listenerSteps.size / 2
            val dialerHalf = schedule.dialerSteps.size / 2
            runConcurrently(
                rig,
                schedule.listenerSteps.take(listenerHalf),
                schedule.dialerSteps.take(dialerHalf),
            )

            // 2. sever, and land five more mutations on EACH severed side.
            rig.partition()
            runConcurrently(rig, schedule.listenerPartitionSteps, schedule.dialerPartitionSteps)

            // 3. the one bounded non-arrival check: the listener holds its own
            //    partition-era issue, and the dialer does not.
            val listenerOnly = schedule.listenerPartitionIssueIds.first()
            rig.listener.quiesce()
            rig.await("seed $seed: the listener's own fold carries $listenerOnly while severed") {
                listenerOnly in rig.listener.view()
            }
            rig.dialer.servedStatus(listenerOnly) shouldBe 404

            // 4. heal, then run the rest of the schedule across the healed peering.
            rig.heal()
            runConcurrently(
                rig,
                schedule.listenerSteps.drop(listenerHalf),
                schedule.dialerSteps.drop(dialerHalf),
            )

            // 5. idle, then the repair assertions.
            rig.listener.quiesce()
            rig.dialer.quiesce()
            rig.await("seed $seed: the healed peering converges to equal folds") {
                rig.listener.view() == rig.dialer.view() &&
                    rig.listener.edgeView() == rig.dialer.edgeView() &&
                    schedule.partitionIssueIds.all { it in rig.listener.view() && it in rig.dialer.view() }
            }

            rig.listener.view() shouldBe rig.dialer.view()
            rig.listener.edgeView() shouldBe rig.dialer.edgeView()

            schedule.partitionIssueIds.forEach { issueId ->
                val onListener = rig.listener.view()[issueId]
                    ?: error("seed $seed: partition-era issue $issueId never reached the listener's fold")
                val onDialer = rig.dialer.view()[issueId]
                    ?: error("seed $seed: partition-era issue $issueId never reached the dialer's fold")
                onListener["design"] shouldBe json(schedule.partitionDesign(issueId))
                onDialer["design"] shouldBe json(schedule.partitionDesign(issueId))
            }
        } finally {
            rig.close()
        }
    }

    /**
     * One seed, end to end: derive the schedule, run both sides concurrently
     * against a fresh rig's real `bd` workspaces, wait for idle, and assert
     * the three equalities the acceptance criteria name — [TwoNodeRig.Node.view],
     * [TwoNodeRig.Node.edgeView], and the served fold — plus the shared
     * issue's per-field merge (design example 1's "different fields, both
     * survive").
     */
    private fun assertConverges(seed: Long) {
        val schedule = SeededSchedule.derive(seed)
        val rig = newRig()
        try {
            rig.startListener()
            rig.startDialer()

            runConcurrently(rig, schedule.listenerSteps, schedule.dialerSteps)

            rig.listener.quiesce()
            rig.dialer.quiesce()
            rig.await("seed $seed: both nodes converge to equal folds") {
                rig.listener.view() == rig.dialer.view() && rig.listener.edgeView() == rig.dialer.edgeView()
            }

            rig.listener.view() shouldBe rig.dialer.view()
            rig.listener.edgeView() shouldBe rig.dialer.edgeView()
            rig.listener.servedView() shouldBe rig.dialer.servedView()

            val sharedFold = rig.listener.view()[schedule.sharedIssueId]
                ?: error("seed $seed: shared issue ${schedule.sharedIssueId} never reached the listener's fold")
            sharedFold["design"] shouldBe json("set by L, seed $seed")
            sharedFold["notes"] shouldBe json("set by D, seed $seed")
        } finally {
            rig.close()
        }
    }

    /**
     * Runs [SeededSchedule.listenerSteps] and [SeededSchedule.dialerSteps] on
     * two driver threads, one per workspace, and re-raises either side's
     * failure on the test thread once both have finished — a `bd` exit
     * failure from either side must fail the test, not vanish on a
     * background thread.
     */
    private fun runConcurrently(
        rig: TwoNodeRig,
        listenerSteps: List<ScheduleStep>,
        dialerSteps: List<ScheduleStep>,
    ) {
        var listenerFailure: Throwable? = null
        var dialerFailure: Throwable? = null

        val listenerThread = Thread({
            try {
                listenerSteps.forEach { it.apply(rig.listenerWorkspace) }
            } catch (t: Throwable) {
                listenerFailure = t
            }
        }, "seeded-schedule-listener")
        val dialerThread = Thread({
            try {
                dialerSteps.forEach { it.apply(rig.dialerWorkspace) }
            } catch (t: Throwable) {
                dialerFailure = t
            }
        }, "seeded-schedule-dialer")

        listenerThread.start()
        dialerThread.start()
        listenerThread.join()
        dialerThread.join()

        val failures = listOfNotNull(listenerFailure, dialerFailure)
        if (failures.isNotEmpty()) {
            val combined = AssertionError("the seeded schedule failed on ${failures.size} side(s): ${failures.map { it.message }}")
            failures.forEach { combined.addSuppressed(it) }
            throw combined
        }
    }

    /**
     * This node's fold AS SERVED (`GET /beads/issues`), parsed into the same
     * shape [TwoNodeRig.Node.view] returns, so it can be compared for
     * structural (order-independent) equality against the peer's — a raw
     * string comparison would be sensitive to JSON key ordering, which this
     * class does not claim anything about.
     */
    private fun TwoNodeRig.Node.servedView(): Map<String, Map<String, String>> =
        Json.parseToJsonElement(servedFold()).jsonObject.mapValues { (_, fields) ->
            fields.jsonObject.mapValues { (_, value) -> value.toString() }
        }

    private fun json(value: String): String = JsonPrimitive(value).toString()

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
