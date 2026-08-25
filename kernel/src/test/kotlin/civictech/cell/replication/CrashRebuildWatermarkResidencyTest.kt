package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.WatermarkCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What a crash-and-rebuild at the same instance id leaves behind on the
 * delivered-watermark companion (computenet-2h68 and computenet-nf8w — the two
 * residues computenet-h50w's fix did not cover).
 *
 * Both were filed as code readings, not measurements. Both are pinned here at
 * the level they are actually observable — [Replication]'s own bookkeeping —
 * rather than through a frontier property, because the frontier-level
 * consequence computenet-2h68 predicted does **not** reproduce: the sibling
 * `ExchangeReplicatedCrashFrontierDstTest` sweep agrees on both peers across
 * exactly this crash on all ten seeds with or without the repair below.
 *
 * The invariant these two tests state is narrow and deliberate: a crash gives
 * back no delivered-watermark *state* (the row stays frozen — the decided
 * disposition for an unclean departure, 96-incremental-engines-plan E3.6(c),
 * R13), but it must not leave the companion pointing at a host that is gone,
 * nor a PN-19 Stall latched with no path left to retract it.
 */
class CrashRebuildWatermarkResidencyTest {

    private class Fixture {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val replication = Replication(registry)
        val id: UUID = UUID.randomUUID()
        val ref = CellRef(id, 0)
        fun host() = ManagedHost(scheduler = controller.scheduler(), registry = registry)
    }

    /**
     * computenet-2h68: the memoised companion is reused (its row must survive —
     * the same ref returns), but it is re-spawned on the rebuilt host, so the
     * registry no longer resolves companion gossip to the discarded one.
     */
    @Test
    fun `a rebuilt replica re-homes its delivered-watermark companion without minting a new one`() {
        val f = Fixture()
        val hostA = f.host()
        f.replication.replicate(SetCell<String>(f.ref), hostA)
        f.controller.runToIdle()

        val companionRef = f.replication.watermarkRef(f.ref)
        val companion = f.replication.watermarkOf(f.id)
        f.registry.locate(companionRef) shouldBe hostA

        // the crash: hostA is discarded and a DIFFERENT object is replicated at the same ref
        val hostB = f.host()
        f.replication.replicate(SetCell<String>(f.ref), hostB)
        f.controller.runToIdle()

        // the row survives — a fresh companion would read as a member restarted at bottom
        f.replication.watermarkOf(f.id) shouldBe companion
        // ...but its residency moved with the replica, rather than staying on the dead host
        f.registry.locate(f.ref) shouldBe hostB
        f.registry.locate(companionRef) shouldBe hostB
    }

    /**
     * computenet-nf8w: `supersedeLocalInstance` clears `partitionSuspended`, which is
     * the only gate `linkOut`'s heal branch fires behind — so the paired PN-19
     * suspend epoch has to be retracted here or it latches forever.
     */
    @Test
    fun `a rebuilt replica retracts the PN-19 Stall its partition-suspend latched`() {
        val f = Fixture()
        val hostA = f.host()
        val a = SetCell<String>(f.ref)
        f.replication.replicate(a, hostA)
        f.controller.runToIdle()

        // evict with no reachable peer: parks rather than despawns, and raises the
        // recoverable Stall on the companion (odd suspend epoch).
        f.replication.evict(a, hostA) shouldBe false
        f.controller.runToIdle()
        val slot = WatermarkCell.slotId(f.replication.watermarkRef(f.ref))
        f.replication.watermarkOf(f.id)!!.suspended() shouldBe setOf(slot)

        // the crash: hostA is discarded, a different object returns at the same ref, live.
        val hostB = f.host()
        f.replication.replicate(SetCell<String>(f.ref), hostB)
        f.controller.runToIdle()

        // the member is live again, so a DEGRADE covering-quorum read must re-admit it.
        // Unfixed, `linkOut`'s heal branch can never fire for this ref again (its
        // `partitionSuspended` gate was cleared) and the epoch stays odd forever.
        f.replication.watermarkOf(f.id)!!.suspended().shouldBeEmpty()
    }
}
