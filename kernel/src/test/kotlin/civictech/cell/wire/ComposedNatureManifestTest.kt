package civictech.cell.wire

import civictech.cell.Consumer
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.data.PartitionedCell
import civictech.cell.data.SetCell
import civictech.cell.data.ShardCell
import civictech.cell.data.UnionSetCell
import civictech.cell.port.FanInlet
import civictech.cell.port.NatureNegotiation
import civictech.cell.port.Reconciliation
import civictech.cell.proxy.Invocation
import civictech.nature.Color
import civictech.nature.ContractRegistry
import civictech.nature.Manifest
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import civictech.nature.WaveParticipation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-12 (plan §4). The exchange cells report their **composed structural natures**
 * as a [Manifest] set on the generated [civictech.nature.CellDescriptor] —
 * KSP-derived from the marker interfaces each cell implements. This is the
 * declaration surface for structural natures (glitch-free / durable / replicated /
 * partitioned), which — unlike a link-flow [NatureAxis] — are **deliberately never
 * consulted by `reconcile`**: a volatile consumer of a durable producer is normal.
 *
 * Two controls diverge:
 *  (a) were the structural natures treated as link-flow refusing axes, a normal
 *      crossing link (COLOR here — the established stand-in the ticket names) is
 *      refused — the COLOR mistake, executable;
 *  (b) with `WAVE_PARTICIPATION` in the link-flow set, an unwaved producer onto an
 *      ALIGN inlet is a loud refusal; remove it and the frontier drops it silently
 *      (today's F1).
 */
class ComposedNatureManifestTest {

    private fun manifestOf(clazz: Class<*>): Set<Manifest> =
        ContractRegistry.cellDescriptor(clazz).shouldNotBeNull().manifest

    @Test
    fun `the exchange cells report their composed structural natures`() {
        // board: a glitch-free fan-in join
        manifestOf(GlitchFreeCell::class.java) shouldContain Manifest.GLITCH_FREE
        // writers: durable per-region intake
        manifestOf(SetCell::class.java) shouldContain Manifest.DURABLE
        // union: replicated over the mesh (ReBaselineEmitting / Replicable)
        manifestOf(UnionSetCell::class.java) shouldContain Manifest.REPLICATED
        // shards: interest-partitioned instances
        manifestOf(ShardCell::class.java) shouldContain Manifest.PARTITIONED
        manifestOf(PartitionedCell::class.java) shouldContain Manifest.PARTITIONED
    }

    @Test
    fun `a glitch-free cell offers WAVED on its outlets (the ALIGN producer side)`() {
        val outlet = ContractRegistry.cellDescriptor(GlitchFreeCell::class.java)!!
            .ports.first { it.name == "outlet" }
        outlet.natures.level(NatureAxis.WAVE_PARTICIPATION) shouldBe WaveParticipation.WAVED
    }

    // --- control (a): structural natures must never be link-flow ---

    @Test
    fun `control (a) - moving a structural nature into the link-flow set refuses a normal crossing link`() {
        // the fix: every CellManifest tag is disjoint from the reconcile axes.
        Manifest.entries.forEach { tag ->
            NatureNegotiation.LINK_FLOW_AXES.none { it.name == tag.name } shouldBe true
        }
        // COLOR is the ticket's named stand-in for a structural nature (a placement
        // property, validated at spawn, that a link legitimately crosses).
        // PINNED: it is excluded from the link-flow set, so the demo's cross-color
        // (≈ durable→volatile) link composes.
        NatureNegotiation.reconcile(
            offered = NatureVector.DEFAULT,
            required = NatureVector.of(Color.SUSPENDING),
        ).shouldBeInstanceOf<Reconciliation.Direct>()

        // CONTROL: move that structural axis INTO the link-flow set — the same
        // normal link is now refused (the COLOR mistake, executable).
        NatureNegotiation.reconcile(
            offered = NatureVector.DEFAULT,
            required = NatureVector.of(Color.SUSPENDING),
            linkFlowAxes = NatureNegotiation.LINK_FLOW_AXES + NatureAxis.COLOR,
        ).shouldBeInstanceOf<Reconciliation.Refuse>()
    }

    // --- control (b): WAVE_PARTICIPATION turns a silent drop into a refusal ---

    private fun wave(sourcePort: civictech.cell.port.PortRef, sourceId: UUID, counter: Long) = Invocation(
        methodName = "provide",
        parameterTypes = listOf("java.lang.Object"),
        args = listOf(counter.toInt()),
        context = MessageContext(Timestamp(sourceId, counter), sourcePort),
    )

    @Test
    fun `control (b) - WAVE_PARTICIPATION makes an unwaved producer onto an ALIGN inlet a refusal, not a silent drop`() {
        // PINNED: an unwaved producer (default) reconciled against a WAVED-requiring
        // ALIGN inlet is a loud typed refusal on WAVE_PARTICIPATION.
        val refuse = NatureNegotiation.reconcile(
            offered = NatureVector.DEFAULT,
            required = NatureVector.of(WaveParticipation.WAVED),
        ).shouldBeInstanceOf<Reconciliation.Refuse>()
        refuse.mismatch.axis shouldBe NatureAxis.WAVE_PARTICIPATION

        // CONTROL: remove WAVE_PARTICIPATION from the link-flow set — the same pair
        // reconciles Direct (the link forms) ...
        NatureNegotiation.reconcile(
            offered = NatureVector.DEFAULT,
            required = NatureVector.of(WaveParticipation.WAVED),
            linkFlowAxes = NatureNegotiation.LINK_FLOW_AXES - NatureAxis.WAVE_PARTICIPATION,
        ).shouldBeInstanceOf<Reconciliation.Direct>()

        // ... and then the ALIGN frontier silently drops the unwaved producer's
        // wave: no EdgeOpen was announced for it, so it is neither delivered nor
        // buffered — exactly today's F1 (now counted, but still not delivered).
        val delivered = mutableListOf<Invocation>()
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet = FanInlet.create<Consumer<Int>>()
        frontier.attach(inlet) { delivered += it }

        val unannouncedProducer = civictech.cell.port.PortRef.generate()
        frontier.offer(wave(unannouncedProducer, UUID.randomUUID(), 1L))

        (delivered.isEmpty()) shouldBe true          // silently not delivered
        (frontier.unmatchedDrops >= 1L) shouldBe true // the F1 drop, counted
    }
}
