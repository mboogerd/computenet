package civictech.cell.port

import civictech.cell.Consumer
import civictech.cell.link.LinkResult
import civictech.cell.link.handshake
import civictech.nature.Color
import civictech.nature.MergeClass
import civictech.nature.Monotonicity
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import civictech.nature.Ownership
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * CP-F3: a scoped-axis nature conflict becomes a **loud typed refusal** at link
 * time — `LinkResult.Rejected(mismatch)` — where today the mismatch drops
 * silently (a non-idempotent stream fed onto a gossip-merge inlet, a monotone
 * demand met by a non-monotone producer, an exclusive-required consumer fed a
 * shared payload). There is deliberately no `Adapt` arm: reconcile only ever
 * accepts (Direct) or refuses.
 */
class TypedRefusalTest {

    // --- the pure function: the whole type system, unit-tested per axis ---

    @Test
    fun `reconcile is Direct on the all-default fast path`() {
        NatureNegotiation.reconcile(NatureVector.DEFAULT, NatureVector.DEFAULT)
            .shouldBeInstanceOf<Reconciliation.Direct>()
    }

    @Test
    fun `a strictly stronger producer subsumes the requirement`() {
        // consumer requires idempotent merge; producer offers it -> compose
        NatureNegotiation.reconcile(
            offered = NatureVector.of(MergeClass.IDEMPOTENT),
            required = NatureVector.of(MergeClass.IDEMPOTENT),
        ).shouldBeInstanceOf<Reconciliation.Direct>()
        // consumer requires nothing; a stronger producer still composes
        NatureNegotiation.reconcile(
            offered = NatureVector.of(MergeClass.IDEMPOTENT),
            required = NatureVector.DEFAULT,
        ).shouldBeInstanceOf<Reconciliation.Direct>()
    }

    @Test
    fun `each link-flow axis refuses with a typed mismatch when unmet`() {
        for ((offered, required, axis) in listOf(
            Triple(NatureVector.DEFAULT, NatureVector.of(MergeClass.IDEMPOTENT), NatureAxis.MERGE_IDEMPOTENCE),
            Triple(NatureVector.DEFAULT, NatureVector.of(Monotonicity.MONOTONE), NatureAxis.MONOTONICITY),
            Triple(NatureVector.DEFAULT, NatureVector.of(Ownership.EXCLUSIVE), NatureAxis.OWNERSHIP),
        )) {
            val refuse = NatureNegotiation.reconcile(offered, required)
                .shouldBeInstanceOf<Reconciliation.Refuse>()
            refuse.mismatch.axis shouldBe axis
            refuse.mismatch.required shouldBe required.level(axis)
            refuse.mismatch.offered shouldBe offered.level(axis)
        }
    }

    @Test
    fun `color is not a link-flow axis and never refuses a crossing link`() {
        // a pure producer feeding a suspending consumer is normal (different
        // hosts) — color is a placement property, checked at spawn, not here.
        NatureNegotiation.reconcile(
            offered = NatureVector.DEFAULT,
            required = NatureVector.of(Color.SUSPENDING),
        ).shouldBeInstanceOf<Reconciliation.Direct>()
    }

    // --- the handshake hook: the refusal surfaces at link() time ---

    @Test
    fun `a non-idempotent producer onto a gossip-merge inlet is refused at link time`() {
        val consumer = FanInlet.create<Consumer<String>>()
        val producer = FanInlet.create<Consumer<String>>()
        // the consumer merges by gossip and requires idempotent deltas
        PortNatures.stamp(consumer, NatureVector.of(MergeClass.IDEMPOTENT))
        // producer stays DEFAULT (non-idempotent)

        val result = consumer.linkFrom(producer)

        val rejected = result.shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.mismatch!!.axis shouldBe NatureAxis.MERGE_IDEMPOTENCE
        rejected.mismatch!!.offered shouldBe MergeClass.NON_IDEMPOTENT
        rejected.mismatch!!.required shouldBe MergeClass.IDEMPOTENT
        // the human reason is still populated (backward-compatible field)
        (rejected.reason.contains("MERGE_IDEMPOTENCE")) shouldBe true
    }

    @Test
    fun `an idempotent producer onto the same inlet composes silently as before`() {
        val consumer = FanInlet.create<Consumer<String>>()
        val producer = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(consumer, NatureVector.of(MergeClass.IDEMPOTENT))
        PortNatures.stamp(producer, NatureVector.of(MergeClass.IDEMPOTENT))

        val result = consumer.linkFrom(producer)

        result.shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `default-nature ports link exactly as today (zero behavior change)`() {
        val consumer = FanInlet.create<Consumer<String>>()
        val producer = FanInlet.create<Consumer<String>>()
        // no stamping: both DEFAULT
        consumer.linkFrom(producer).shouldBeInstanceOf<LinkResult.Connected>()
    }

    @Test
    fun `a monotone-required inlet refuses a non-monotone producer at link time`() {
        val consumer = FanInlet.create<Consumer<String>>()
        val producer = FanInlet.create<Consumer<String>>()
        PortNatures.stamp(consumer, NatureVector.of(Monotonicity.MONOTONE))

        val rejected = consumer.linkFrom(producer).shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.mismatch!!.axis shouldBe NatureAxis.MONOTONICITY
    }
}
