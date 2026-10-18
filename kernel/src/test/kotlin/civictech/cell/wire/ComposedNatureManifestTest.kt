package civictech.cell.wire

import civictech.cell.data.SetCell
import civictech.cell.nature.NatureNegotiation
import civictech.cell.nature.Reconciliation
import civictech.nature.Color
import civictech.nature.ContractRegistry
import civictech.nature.Manifest
import civictech.nature.NatureAxis
import civictech.nature.NatureVector
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import civictech.cell.data.op.UnionSetCell
import civictech.cell.partition.PartitionedCell
import civictech.cell.partition.ShardCell

/**
 * PN-12 (plan §4). The exchange cells report their **composed structural natures**
 * as a [Manifest] set on the generated [civictech.nature.CellDescriptor] —
 * KSP-derived from the marker interfaces each cell implements. This is the
 * declaration surface for structural natures (durable / replicated /
 * partitioned), which — unlike a link-flow [NatureAxis] — are **deliberately never
 * consulted by `reconcile`**: a volatile consumer of a durable producer is normal.
 *
 * The control diverges: were the structural natures treated as link-flow refusing
 * axes, a normal crossing link (COLOR here — the established stand-in the ticket
 * names) is refused — the COLOR mistake, executable.
 */
class ComposedNatureManifestTest {

    private fun manifestOf(clazz: Class<*>): Set<Manifest> =
        ContractRegistry.cellDescriptor(clazz).shouldNotBeNull().manifest

    @Test
    fun `the exchange cells report their composed structural natures`() {
        // writers: durable per-region intake
        manifestOf(SetCell::class.java) shouldContain Manifest.DURABLE
        // union: replicated over the mesh (ReBaselineEmitting / Replicable)
        manifestOf(UnionSetCell::class.java) shouldContain Manifest.REPLICATED
        // shards: interest-partitioned instances
        manifestOf(ShardCell::class.java) shouldContain Manifest.PARTITIONED
        manifestOf(PartitionedCell::class.java) shouldContain Manifest.PARTITIONED
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

}
