package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkResult
import civictech.cell.link.LinkRole
import civictech.cell.link.Linked
import civictech.cell.link.PortLink
import civictech.cell.port.Port
import civictech.cell.port.PortRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.UUID

class UpstreamAncestryTest {

    private class Fixture(vararg cells: Cell) {
        val host = ManagedHost(scheduler = SimulationController().scheduler())
        val management = host.managementInlet.call

        init {
            cells.forEach(management::spawn)
        }

        fun connect(
            from: Cell,
            outlet: String,
            to: Cell,
            inlet: String,
            options: LinkOptions = LinkOptions.DEFAULT,
        ): LinkResult.Connected = management.connect(from.ref, outlet, to.ref, inlet, options)
            .shouldBeInstanceOf<LinkResult.Connected>()

        fun attachOpaque(target: Cell, fromPort: Port? = null): PortRef {
            val inlet = host.portAt(target.ref, "inlet")!!
            val linked = inlet as Linked
            val from = fromPort?.ref ?: PortRef.generate()
            val link = PortLink(
                from = from,
                to = inlet.ref,
                fromPort = fromPort,
                toPort = inlet,
                role = LinkRole.Consume,
            ) { linked.linking.remove(it) }
            linked.linking.register(link)
            return from
        }
    }

    @Test
    fun `a chain reports every local Consume ancestor and the first producer outlet`() {
        val root = SetCell<Int>()
        val first = FilterCell<Int> { true }
        val second = FilterCell<Int> { true }
        val fixture = Fixture(root, first, second)
        fixture.connect(root, "outlet", first, "inlet")
        fixture.connect(first, "outlet", second, "inlet")

        val ancestry = fixture.management.upstreamConsumeAncestors(second.ref)

        ancestry.self shouldBeSameInstanceAs second
        ancestry.local.keys shouldBe setOf(first.ref, root.ref)
        ancestry.local.getValue(first.ref).cell shouldBeSameInstanceAs first
        ancestry.local.getValue(first.ref).viaOutlet shouldBe fixture.host.portAt(first.ref, "outlet")!!.ref
        ancestry.local.getValue(root.ref).cell shouldBeSameInstanceAs root
        ancestry.local.getValue(root.ref).viaOutlet shouldBe fixture.host.portAt(root.ref, "outlet")!!.ref
        ancestry.opaque.shouldBeEmpty()
    }

    @Test
    fun `a diamond reaches its shared root once at the transitive fixpoint`() {
        val root = SetCell<Int>()
        val left = FilterCell<Int> { true }
        val right = FilterCell<Int> { true }
        val join = IntersectSetCell<Int>()
        val fixture = Fixture(root, left, right, join)
        fixture.connect(root, "outlet", left, "inlet")
        fixture.connect(root, "outlet", right, "inlet")
        fixture.connect(left, "outlet", join, "left")
        fixture.connect(right, "outlet", join, "right")

        val ancestry = fixture.management.upstreamConsumeAncestors(join.ref)

        ancestry.local.keys shouldBe setOf(left.ref, right.ref, root.ref)
        ancestry.local.size shouldBe 3
        ancestry.opaque.shouldBeEmpty()
    }

    @Test
    fun `an Observe link is excluded even when its producer is hosted`() {
        val root = SetCell<Int>()
        val filter = FilterCell<Int> { true }
        val fixture = Fixture(root, filter)
        fixture.connect(
            root,
            "outlet",
            filter,
            "inlet",
            LinkOptions(role = LinkRole.Observe),
        )

        val ancestry = fixture.management.upstreamConsumeAncestors(filter.ref)

        ancestry.local shouldBe emptyMap()
        ancestry.opaque.shouldBeEmpty()
    }

    @Test
    fun `a Consume link with no producer object is an opaque boundary`() {
        val filter = FilterCell<Int> { true }
        val fixture = Fixture(filter)
        val opaque = fixture.attachOpaque(filter)

        val ancestry = fixture.management.upstreamConsumeAncestors(filter.ref)

        ancestry.local shouldBe emptyMap()
        ancestry.opaque shouldBe setOf(opaque)
    }

    @Test
    fun `a Consume link whose producer object is not hosted is an opaque boundary`() {
        val filter = FilterCell<Int> { true }
        val fixture = Fixture(filter)
        val external = object : Port {
            override val ref = PortRef.generate()
        }
        val opaque = fixture.attachOpaque(filter, external)

        val ancestry = fixture.management.upstreamConsumeAncestors(filter.ref)

        ancestry.local shouldBe emptyMap()
        ancestry.opaque shouldBe setOf(opaque)
    }

    @Test
    fun `an unhosted ref has no self local ancestors or opaque boundaries`() {
        val fixture = Fixture()

        val ancestry = fixture.management.upstreamConsumeAncestors(CellRef(UUID.randomUUID()))

        ancestry.self shouldBe null
        ancestry.local shouldBe emptyMap()
        ancestry.opaque.shouldBeEmpty()
    }

    @Test
    fun `each read captures the live link set at that call`() {
        val root = SetCell<Int>()
        val filter = FilterCell<Int> { true }
        val fixture = Fixture(root, filter)

        val before = fixture.management.upstreamConsumeAncestors(filter.ref)
        fixture.connect(root, "outlet", filter, "inlet")
        val after = fixture.management.upstreamConsumeAncestors(filter.ref)

        before.local shouldBe emptyMap()
        before.opaque.shouldBeEmpty()
        after.local.keys shouldBe setOf(root.ref)
        after.opaque.shouldBeEmpty()
    }
}
