package civictech.oracle.tagged

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.cell.replication.Replication
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * DIAGNOSTIC PROBE for computenet-9892 — is a **directed, one-edge-at-a-time** gossip schedule
 * constructible over the real `Replication` mesh with no kernel change?
 *
 * computenet-9892's open sub-problem is *"there is no way today to keep the real mesh from
 * delivering MORE gossip than the script states"*. Reading `Replication` says its own public
 * surface cannot: `maybeLink` links every interest-overlapping pair (overlap is symmetric, so
 * formation is never directional), and there is no unlink — its `onUnpublish` hook drops the
 * `linked` bookkeeping and, in `gossipRef`'s own words, leaves the attachment "WITHOUT
 * unsubscribing the outlet".
 *
 * One level down there is a seam. `Replicable.outlet` is a public `Subscribe`, which declares
 * `unsubscribe(PortRef)`, and since T21 the gossip subscription's `PortRef` is **derived**, not
 * generated: `UUID.nameUUIDFromBytes("gossip:<local.id>:<local.instanceId>:<other.id>:<other.instanceId>")`.
 * Deriving a kernel identity in the harness is the sanctioned pattern here (`[ORA2-MODEL-12]`;
 * `ConvergenceCheckTest.Mesh.dotOrder()` derives `"or-map-tags:..."` the same way).
 *
 * This file asserts the two halves that decide it, on a real two-replica `OrMapCell` mesh:
 *
 *  1. **Withholding works.** With both derived gossip refs unsubscribed, two concurrent puts at
 *     one key leave each replica holding ONLY its own dot, through a full `runToIdle`.
 *  2. **One directed edge delivers, and does not leak back.** Re-streaming `from`'s outlet at the
 *     derived ref hands `into` the whole of `from`'s state via `streamTo`'s `fireLinked`
 *     catch-up — which is exactly what a `civictech.oracle.model.Delivery` means — while `from`
 *     still holds only its own dot.
 *
 * Together those are "the real mesh delivers exactly what the schedule states, and nothing more".
 */
class DirectedGossipProbeTest {

    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    /** `Replication.gossipRef`'s derivation, restated in the harness (`[ORA2-MODEL-12]`'s pattern). */
    private fun gossipRef(local: CellRef, other: CellRef): PortRef = PortRef(
        UUID.nameUUIDFromBytes(
            "gossip:${local.id}:${local.instanceId}:${other.id}:${other.instanceId}".toByteArray(),
        ),
    )

    private fun liveDotCount(cell: OrMapCell<String, String>, key: String): Int =
        cell.state().liveDots(key).size

    @Test
    fun `a derived-ref unsubscribe withholds gossip, and a single re-stream delivers exactly one direction`() {
        val world = SimWorld(seed = 7L)
        val logicalId = UUID.nameUUIDFromBytes("directed-gossip-probe".toByteArray())
        val replication = Replication(world.registry)

        val r0 = OrMapCell<String, String>(CellRef(logicalId, 0L))
        val r1 = OrMapCell<String, String>(CellRef(logicalId, 1L))
        replication.replicate(r0, world.host)
        replication.replicate(r1, world.host)
        world.runToIdle()

        @Suppress("UNCHECKED_CAST")
        fun opsOf(cell: OrMapCell<String, String>): MapOps<String, String> =
            (
                HostedCellProxy.create(cell.ref, world.registry, OrMapInletProxy::class.java)
                    as OrMapInletProxy
                ).inlet.call

        val ops0 = opsOf(r0)
        val ops1 = opsOf(r1)

        // ---- half 1: withhold ------------------------------------------------------------
        r0.outlet.unsubscribe(gossipRef(r0.ref, r1.ref))
        r1.outlet.unsubscribe(gossipRef(r1.ref, r0.ref))

        ops0.put("k", "v0")
        ops1.put("k", "v1")
        world.runToIdle()

        withClue("r0 must hold only its own dot at k — gossip is supposed to be withheld") {
            liveDotCount(r0, "k") shouldBe 1
        }
        withClue("r1 must hold only its own dot at k — gossip is supposed to be withheld") {
            liveDotCount(r1, "k") shouldBe 1
        }
        withClue("the two replicas must actually disagree, or this probe proves nothing") {
            (r0.value("k") != r1.value("k")) shouldBe true
        }

        // ---- half 2: one directed edge, r0 -> r1 -----------------------------------------
        @Suppress("UNCHECKED_CAST")
        val intoInlet = (
            HostedCellProxy.create(r1.ref, world.registry, Replication.ReplicaDeltaInlet::class.java)
                as Replication.ReplicaDeltaInlet
            ).deltaInlet.call
        @Suppress("UNCHECKED_CAST")
        (r0.outlet as FanOutlet<Propagate<Any?>>).streamTo(intoInlet, at = gossipRef(r0.ref, r1.ref))
        world.runToIdle()

        withClue("r1 absorbed r0's whole state through the catch-up, so k now holds two live dots") {
            liveDotCount(r1, "k") shouldBe 2
        }
        withClue("r0 must NOT have learned anything back — the edge is directed") {
            liveDotCount(r0, "k") shouldBe 1
        }

        // ---- half 3: retract the edge again, and prove it stays retracted -----------------
        r0.outlet.unsubscribe(gossipRef(r0.ref, r1.ref))
        ops0.put("k2", "v0b")
        world.runToIdle()
        withClue("after the edge is unsubscribed again, r1 must not see r0's later write") {
            r1.membership().contains("k2") shouldBe false
        }

        println(
            "[9892-probe] withheld: r0=${liveDotCount(r0, "k")} r1=1; after one directed edge: " +
                "r1=${liveDotCount(r1, "k")}; after retraction r1 sees k2 = ${r1.membership().contains("k2")}",
        )
    }
}
