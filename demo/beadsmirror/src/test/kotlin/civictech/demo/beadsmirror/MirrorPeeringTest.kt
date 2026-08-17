package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorCellRefs
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Task computenet-7em.1.2: [BeadsMirrorApp]'s opt-in two-node mode — the
 * peering flags, the role they imply, and the re-baseline swap seam that
 * re-points the replica mesh at the fresh projector's cells.
 *
 * **What is deliberately NOT here.** No two-node rig and no cross-node
 * assertion: an in-test two-host rig is task computenet-7em.1.3 and the
 * two-JVM launch test is computenet-7em.1.4, and this task's non-goals name
 * both. Everything below is pure JVM — no `bd`, no `dolt`, no socket, no
 * JUnit assumption — so it is a real CI gate rather than a
 * green-but-skipped one, exactly like [BeadsMirrorAppTest.Refusal].
 *
 * **Why [RebaselineSwap] is not vacuous.** `Replication.rebind` refuses a
 * candidate whose `CellRef` differs from the incumbent's, so
 * [RebaselineSwap]'s "different refs is refused by Replication" case fails
 * loudly *only* when the swap really travels through
 * `Replication.rebind`. Delete [MirrorPeering.rebind]'s body, or the
 * `onSwap` hook that calls it, and that test goes green-by-silence — which
 * is what makes it a check on the wiring rather than on the kernel.
 */
class MirrorPeeringTest {

    private fun projector(refs: MirrorCellRefs?) = when (refs) {
        null -> MirrorProjector(DotMinter("beads-scratch-solo"))
        else -> MirrorProjector(DotMinter("beads-scratch-solo"), refs)
    }

    /** A one-issue create record, so a projector can be given observable state. */
    private fun createRecord(height: Long, issue: String) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, 0),
        issueId = issue,
        diffType = DiffType.ADDED,
        fieldDiffs = listOf(FieldDiff("status", old = null, new = JsonPrimitive("open"))),
        edgeDiffs = emptyList(),
    )

    @Nested
    inner class FlagParsing {

        @Test
        fun `--rig with --listen is the listener, and the remaining args survive`() {
            val (peering, rest) = arrayOf("--rig", "bds2", "--listen", "0", "8080").extractPeering()
            peering shouldBe MirrorPeeringSettings("bds2", MirrorWire.Listen(0))
            peering!!.role shouldBe MirrorCellRefs.LISTENER
            rest shouldBe arrayOf("8080")
        }

        @Test
        fun `--rig with --peer is the dialer, in the inline equals form too`() {
            val (peering, rest) = arrayOf("--rig=bds2", "--peer=ws://localhost:9001").extractPeering()
            peering shouldBe MirrorPeeringSettings("bds2", MirrorWire.Dial("ws://localhost:9001"))
            peering!!.role shouldBe MirrorCellRefs.DIALER
            rest shouldBe arrayOf<String>()
        }

        /** Solo mode is what "no peering flags at all" means — the default this task must not disturb. */
        @Test
        fun `no peering flag at all is solo mode and leaves the arguments untouched`() {
            val (peering, rest) = arrayOf("--workspace", "/tmp/ws", "8080").extractPeering()
            peering shouldBe null
            rest shouldBe arrayOf("--workspace", "/tmp/ws", "8080")
        }

        @Test
        fun `a rig name with no endpoint is refused rather than guessed`() {
            val failure = shouldThrow<IllegalArgumentException> { arrayOf("--rig", "bds2").extractPeering() }
            failure.message!! shouldContain "--listen"
        }

        @Test
        fun `an endpoint with no rig name is refused - there is no default that could match a peer`() {
            val failure = shouldThrow<IllegalArgumentException> { arrayOf("--listen", "0").extractPeering() }
            failure.message!! shouldContain "--rig"
        }

        @Test
        fun `--listen and --peer together name two roles and are refused`() {
            shouldThrow<IllegalArgumentException> {
                arrayOf("--rig", "bds2", "--listen", "0", "--peer", "ws://localhost:9001").extractPeering()
            }
        }

        @Test
        fun `a non-numeric --listen is refused`() {
            val failure = shouldThrow<IllegalArgumentException> {
                arrayOf("--rig", "bds2", "--listen", "nine").extractPeering()
            }
            failure.message!! shouldContain "nine"
        }
    }

    @Nested
    inner class RoleImpliesTheSharedRefs {

        /**
         * The identity precondition of feature computenet-7em.1's rule 1, read
         * through *this* task's surface: the operator never names a role, so it
         * is the endpoint flag alone that has to produce distinct instance ids
         * for one rig name.
         */
        @Test
        fun `one rig name across the two endpoint flags yields equal logical ids and distinct instances`() {
            val listener = MirrorPeeringSettings("bds2", MirrorWire.Listen(0)).refs
            val dialer = MirrorPeeringSettings("bds2", MirrorWire.Dial("ws://localhost:9001")).refs

            listener.mapRef.id shouldBe dialer.mapRef.id
            listener.edgeRef.id shouldBe dialer.edgeRef.id
            listener.mapRef.instanceId shouldNotBe dialer.mapRef.instanceId
            listener.edgeRef.instanceId shouldNotBe dialer.edgeRef.instanceId
        }

        @Test
        fun `two rig names never share a logical id`() {
            val one = MirrorPeeringSettings("bds2", MirrorWire.Listen(0)).refs
            val other = MirrorPeeringSettings("other-rig", MirrorWire.Listen(0)).refs
            one.mapRef.id shouldNotBe other.mapRef.id
        }
    }

    @Nested
    inner class RebaselineSwap {

        private val settings = MirrorPeeringSettings("bds2-swap", MirrorWire.Listen(0))

        /**
         * The whole of this task's swap clause: a re-baseline builds a fresh
         * projector under the SAME refs and [MirrorState.swap] hands it to
         * [MirrorPeering.rebind], which re-points the mesh at the new cells.
         *
         * No socket is opened — [MirrorPeering.connect] is never called — so
         * this exercises the replication half alone, which is the half the
         * swap touches.
         */
        @Test
        fun `swapping a same-refs projector re-points the mesh at the new cells`() {
            MirrorPeering(settings).use { peering ->
                val initial = projector(peering.refs)
                peering.attach(initial)
                val state = MirrorState(initial, onSwap = peering::rebind)

                val rebuilt = projector(peering.refs)
                shouldNotThrowAny { state.swap(rebuilt) }

                peering.attachedProjector shouldBe rebuilt
                state.current shouldBe rebuilt
                state.rebaselineCount shouldBe 1
            }
        }

        /**
         * The negative control that makes the test above a check on the
         * *wiring*: `Replication.rebind` requires the candidate to reuse the
         * incumbent's `CellRef`, so a swap to a differently-ref'd projector
         * throws — and it can only throw if the swap really reaches
         * `Replication.rebind`. With the `onSwap` hook removed (or
         * [MirrorPeering.rebind] emptied) this swap succeeds silently.
         */
        @Test
        fun `a swap to a projector under different refs is refused by Replication`() {
            MirrorPeering(settings).use { peering ->
                val initial = projector(peering.refs)
                peering.attach(initial)
                val state = MirrorState(initial, onSwap = peering::rebind)

                // what a rebuild that forgot to thread the refs through produces
                val strayRefs = MirrorCellRefs("a-different-rig", MirrorCellRefs.LISTENER)
                val failure = shouldThrow<IllegalArgumentException> { state.swap(projector(strayRefs)) }
                failure.message!! shouldContain "CellRef"
            }
        }

        /**
         * The observable half of the rebind clause, and the check on
         * [MirrorPeering.rebind]'s `carryTagState = false`: a re-baseline
         * exists to *discard* the projector it replaces, so the cells the mesh
         * is re-pointed at must still hold the fresh baseline alone.
         *
         * `Replication.rebind`'s default (`carryTagState = true`) restores the
         * incumbent's [civictech.cell.Stateful] snapshot into the candidate,
         * and `OrMapCell.restore` *clears and replaces* rather than merging —
         * so with the default this swap would leave the mesh serving the
         * discarded projector's issue and lose the rebuilt one. Flip either
         * call in [MirrorPeering.rebind] to the default and this test goes red
         * on exactly that substitution.
         */
        @Test
        fun `the rebound cells hold the fresh baseline, not the discarded projector's state`() {
            MirrorPeering(settings).use { peering ->
                val incumbent = projector(peering.refs)
                incumbent.apply(createRecord(1, "ZOMBIE"))
                peering.attach(incumbent)
                val state = MirrorState(incumbent, onSwap = peering::rebind)

                val rebuilt = projector(peering.refs)
                rebuilt.apply(createRecord(2, "FRESH"))

                state.swap(rebuilt)

                rebuilt.view().keys shouldBe setOf("FRESH")
            }
        }

        /** [MirrorPeering.attach] is one-shot: a second attach is a wiring bug, not a rebind. */
        @Test
        fun `attaching twice is refused`() {
            MirrorPeering(settings).use { peering ->
                peering.attach(projector(peering.refs))
                shouldThrow<IllegalStateException> { peering.attach(projector(peering.refs)) }
            }
        }

        /** Nothing has been attached yet, so there is no incumbent to re-point. */
        @Test
        fun `rebind before attach is a no-op`() {
            MirrorPeering(settings).use { peering ->
                shouldNotThrowAny { peering.rebind(projector(peering.refs)) }
                peering.attachedProjector shouldBe null
            }
        }
    }

    @Nested
    inner class SoloModeIsUnchanged {

        /**
         * The default [BeadsMirrorConfig] is solo: no peering settings, so
         * [BeadsMirrorApp] constructs no [MirrorPeering], no registry, no host
         * and no transport. (The rest of the equivalence claim is carried by
         * the module's whole pre-existing suite, which is unmodified by this
         * task and runs against this same default.)
         */
        @Test
        fun `a config with no peering settings is solo`() {
            BeadsMirrorConfig(workspace = java.nio.file.Path.of("/tmp/does-not-matter")).peering shouldBe null
        }

        /**
         * [MirrorState]'s swap hook defaults to a no-op, so a solo swap is the
         * plain state replacement it always was.
         */
        @Test
        fun `a MirrorState built without a swap hook just swaps`() {
            val initial = projector(null)
            val state = MirrorState(initial)
            val rebuilt = projector(null)

            state.swap(rebuilt)

            state.current shouldBe rebuilt
            state.rebaselineCount shouldBe 1
        }
    }
}
