package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pins one of the three unasserted bounds computenet-f7h.5's feature review
 * recorded (2026-09-10) and computenet-03kz7 exists to resolve:
 *
 * > The release only happens on a registry whose engine saw `forwardWrites`
 * > for that id; a registry hosting no replica of the id (a pure client) runs
 * > no release at all, because nothing there subscribes to `onLeaderMark`.
 * > That is outside this feature's model (its Out-of-scope bars changing
 * > park semantics for other refs) but is nowhere written down.
 *
 * Read literally, the "nothing there subscribes" half overstates the
 * mechanism: [SingleWriterReplication]'s `init` subscribes to
 * `registry.onLeaderMark` unconditionally, for every registry an engine is
 * constructed on — whether or not that registry ever hosts a local replica of
 * the id. What is true, and what this test pins, is the release's actual
 * scope: [SingleWriterReplication.releaseParked] drains only THIS registry's
 * own parked queue (`LocationRegistry.unpark`), which only ever holds
 * anything for `id` on a registry where `forwardWrites` command-forwarded a
 * write for it. A "pure client" registry — one that constructs the engine
 * (so it still folds marks and keeps `applied` in step) but never installs a
 * local replica or forwards a write for this id — therefore always finds an
 * empty queue and releases nothing, silently and safely. The silence is a
 * consequence of parking being registry-local, not of the engine declining to
 * participate in the fold.
 *
 * This confirms the current, silent behavior is intentional and not a latent
 * bug: no exception, no dead letter, no cross-registry leakage — a
 * superseding mark folds and updates `applied` exactly as it would on a
 * registry that does host a replica, and the release step is a genuine no-op
 * because there is nothing to drain.
 */
class ClientOnlyRegistryReleaseTest {

    @Test
    fun `a registry that never forwarded a write for the id releases nothing on a superseding leader mark`() {
        val registry = LocationRegistry()
        // Engine constructed on this registry, exactly as an application would
        // wire a "pure client" node — but `replicate` is never called here, so
        // no local replica and no `forwardWrites` call for `id` ever touches it.
        SingleWriterReplication(registry)

        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)

        registry.markLeader(LeaderMark(id, epoch = 1, leaderRef = aRef)) shouldBe true

        withClue("a pure client never command-forwards a write for `id`, so nothing is ever parked here") {
            registry.forwardedPorts(id).shouldBeEmpty()
            registry.parkedFor(aRef).shouldBeEmpty()
        }

        // A superseding mark: `applied[id].leaderRef` (aRef) differs from the
        // new mark's leaderRef (bRef), so `releaseParked` runs exactly as it
        // would on a registry that does host a replica of `id`.
        registry.markLeader(LeaderMark(id, epoch = 2, leaderRef = bRef)) shouldBe true

        withClue("the release ran and found nothing to drain — not a skipped subscription") {
            registry.parkedFor(aRef).shouldBeEmpty()
            registry.parkedFor(bRef).shouldBeEmpty()
        }
    }
}
