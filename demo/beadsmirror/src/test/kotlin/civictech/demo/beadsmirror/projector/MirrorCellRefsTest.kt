package civictech.demo.beadsmirror.projector

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * computenet-7em.1.1: [MirrorCellRefs] mints the deterministic shared logical
 * `CellRef`s a mirror rig's two nodes need to be replicas of one logical
 * cell, and [MirrorProjector]'s new `(minter, refs)` construction path builds
 * its [MirrorProjector.cell] and [MirrorProjector.edges] under them without
 * disturbing the existing random-ref default.
 */
class MirrorCellRefsTest {

    private val minter = DotMinter("mirror-cell-refs-test")

    @Test
    fun `same rig name, different roles - equal logical ids, distinct instance ids`() {
        val listener = MirrorCellRefs(rigName = "rig-alpha", role = MirrorCellRefs.LISTENER)
        val dialer = MirrorCellRefs(rigName = "rig-alpha", role = MirrorCellRefs.DIALER)

        val listenerProjector = MirrorProjector(minter, listener)
        val dialerProjector = MirrorProjector(minter, dialer)

        // Map cells: same logical id, different instance id.
        listenerProjector.cell.ref.id shouldBe dialerProjector.cell.ref.id
        listenerProjector.cell.ref.instanceId shouldNotBe dialerProjector.cell.ref.instanceId

        // Edge cells: same logical id, different instance id.
        listenerProjector.edges.ref.id shouldBe dialerProjector.edges.ref.id
        listenerProjector.edges.ref.instanceId shouldNotBe dialerProjector.edges.ref.instanceId

        // The map's logical id and the edge set's logical id never collide.
        listenerProjector.cell.ref.id shouldNotBe listenerProjector.edges.ref.id
    }

    @Test
    fun `listener and dialer instance ids are the shopping-idiom convention`() {
        val listener = MirrorCellRefs(rigName = "rig-alpha", role = MirrorCellRefs.LISTENER)
        val dialer = MirrorCellRefs(rigName = "rig-alpha", role = MirrorCellRefs.DIALER)

        listener.instanceId shouldBe 0L
        dialer.instanceId shouldBe 1L
    }

    @Test
    fun `different rig names never collide on either logical id`() {
        val alpha = MirrorCellRefs(rigName = "rig-alpha", role = MirrorCellRefs.LISTENER)
        val beta = MirrorCellRefs(rigName = "rig-beta", role = MirrorCellRefs.LISTENER)

        alpha.mapId shouldNotBe beta.mapId
        alpha.edgeId shouldNotBe beta.edgeId
    }

    @Test
    fun `default no-arg path still mints random refs, unchanged`() {
        val a = MirrorProjector(minter)
        val b = MirrorProjector(minter)

        a.cell.ref.id shouldNotBe b.cell.ref.id
        a.edges.ref.id shouldNotBe b.edges.ref.id
    }

    @Test
    fun `a Rebaseline-style rebuild with the same refs reuses identical CellRefs`() {
        val refs = MirrorCellRefs(rigName = "rig-rebuild", role = MirrorCellRefs.LISTENER)

        // The pre-rebuild projector, as BeadsMirrorApp/Rebaseline would build it.
        val before = MirrorProjector(minter, refs)

        // A fresh projector built for re-baseline, the same way
        // BaselineBuilder.build(..., refs) constructs its replacement.
        val after = MirrorProjector(DotMinter("mirror-cell-refs-test"), refs)

        after.cell.ref shouldBe before.cell.ref
        after.edges.ref shouldBe before.edges.ref
    }
}
