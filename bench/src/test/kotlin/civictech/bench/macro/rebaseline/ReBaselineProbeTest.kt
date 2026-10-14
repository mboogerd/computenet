package civictech.bench.macro.rebaseline

import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.macro.PointBudget
import civictech.cell.ReBaselineNotice
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/** Fast semantic pins for the G-43 RESTART re-baseline probe ([BEN2-36], [BEN2-41]). */
class ReBaselineProbeTest {

    @Test
    fun `remote taps observe one ReBaselineNotice carrying the superseded epoch and re-assert every element`() {
        val at = ReBaselineProbe.coordinate(ReBaselineProbe.REMOTE_TAPS, 10, 2, 2)
        lateinit var preRestartSourceId: UUID

        val measured = ReBaselineProbe.run(
            at = at,
            beforePoison = { rig -> preRestartSourceId = rig.source.sourceId },
            inspect = { rig ->
                rig.mesh.peers[0].host.supervisionAccounting().restarts shouldBe 1
                rig.mesh.peers[0].host.generationOf(rig.sourceRef) shouldBe 1L
                (rig.source.sourceId == preRestartSourceId) shouldBe false
                rig.taps.forEach { tap ->
                    tap.deliveries.filter { it.notice == null }.single().tagSourceIds shouldBe
                        setOf(preRestartSourceId)
                    tap.noticeDeliveries.single().notice shouldBe ReBaselineNotice(
                        supersedes = setOf(preRestartSourceId),
                        supersede = true,
                    )
                    tap.membership() shouldBe (0L until 10L).toSet()
                }
            },
        )
        val outcome = measured.outcome.shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.at shouldBe at
        outcome.subject shouldBe CostSubject.REBASELINE
        outcome.counts.messages shouldBe 2L
        outcome.counts.crossHostFrames shouldBeGreaterThan 1L
        (outcome.counts.wireBytes > 0) shouldBe true
        measured.reassertedElements shouldBe 20L
        measured.reassertedTags shouldBe 20L
        measured.notices shouldBe 2
    }

    @Test
    fun `local taps on one host cross no edge`() {
        val measured = ReBaselineProbe.run(
            ReBaselineProbe.coordinate(ReBaselineProbe.LOCAL_TAPS, 10, 2, 1),
        )
        val outcome = measured.outcome.shouldBeInstanceOf<PointOutcome.Ok>()

        outcome.counts.crossHostFrames shouldBe 0L
        outcome.counts.wireBytes shouldBe 0L
        outcome.counts.messages shouldBe 2L
        measured.reassertedElements shouldBe 20L
    }

    @Test
    fun `a tap forwards a plain delta without fabricating a notice`() {
        val sourceId = UUID.randomUUID()
        val tap = ReBaselineTapCell()

        tap.inlet.call.propagate(
            SetDelta(
                adds = mapOf(
                    7L to setOf(Timestamp(sourceId, 1)),
                    9L to setOf(Timestamp(sourceId, 2)),
                ),
            ),
        )

        tap.deliveries.single().notice shouldBe null
        tap.deliveries.single().tagSourceIds shouldBe setOf(sourceId)
        tap.membership() shouldBe setOf(7L, 9L)
    }

    @Test
    fun `a tap shortfall is Failed, not Ok`() {
        val at = ReBaselineProbe.coordinate(ReBaselineProbe.LOCAL_TAPS, 10, 2, 1)

        val outcome = ReBaselineProbe.run(
            at = at,
            determinismCheck = false,
            beforePoison = { rig ->
                rig.source.outlet.unsubscribe(rig.taps.last().inlet.ref)
            },
        ).outcome.shouldBeInstanceOf<PointOutcome.Failed>()

        outcome.cause shouldContain "re-baseline reached 1 of 2 taps"
    }

    @Test
    fun `coordinate refuses remote taps on a single host`() {
        shouldThrow<IllegalArgumentException> {
            ReBaselineProbe.coordinate(ReBaselineProbe.REMOTE_TAPS, 10, 2, 1)
        }.message shouldContain "at least two hosts"
    }

    @Test
    fun `budget exhaustion is NonConvergent rather than a partial Ok`() {
        val at = ReBaselineProbe.coordinate(ReBaselineProbe.LOCAL_TAPS, 10, 2, 1)

        ReBaselineProbe.run(
            at = at,
            budget = PointBudget(simSteps = 0),
            determinismCheck = false,
        ).outcome shouldBe PointOutcome.NonConvergent(
            at = at,
            subject = CostSubject.REBASELINE,
            budget = "simSteps=0",
        )
    }

    @Test
    fun `OutOfMemoryError is Unschedulable and carries maxHeap`() {
        val at = ReBaselineProbe.coordinate(ReBaselineProbe.LOCAL_TAPS, 1, 1, 1)

        val outcome = ReBaselineProbe.run(
            at = at,
            determinismCheck = false,
            beforePoison = { throw OutOfMemoryError("simulated") },
        ).outcome.shouldBeInstanceOf<PointOutcome.Unschedulable>()

        outcome.reason shouldBe "java.lang.OutOfMemoryError: simulated"
        outcome.observedLimit shouldContain "maxHeap="
    }

    @Test
    fun `determinism pair exposes the second attempt failure instead of the first Ok`() {
        val at = ReBaselineProbe.coordinate(ReBaselineProbe.LOCAL_TAPS, 10, 2, 1)
        var attempts = 0

        val outcome = ReBaselineProbe.run(
            at = at,
            beforePoison = { rig ->
                attempts++
                if (attempts == 2) {
                    rig.source.outlet.unsubscribe(rig.taps.last().inlet.ref)
                }
            },
        ).outcome.shouldBeInstanceOf<PointOutcome.Failed>()

        attempts shouldBe 2
        outcome.cause shouldContain "re-baseline reached 1 of 2 taps"
    }
}
