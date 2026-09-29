/**
 * The demo-layer join of `Interest` to `KeyedCells.getOrSpawn` (SOC1 F5,
 * feature `computenet-4q9is`, design 4q9is-D7; epic `computenet-07k` §3.2
 * "Honest statement of what does not exist", risk 7). Spec:
 * `doc/spec/00-foundations/01-vision.md:38` ("Execution is **interest-driven**"),
 * `[42-INT-01]`.
 *
 * **There is no interest-driven instantiation in the kernel.**
 * `KeyedCells.getOrSpawn(key)` is touch-driven — an explicit app call, with no
 * `Interest` parameter anywhere in the class — and
 * `LocationRegistry.setInterest`/`interestOf` only *record* a declaration
 * (`InstanceIndex.kt`); nothing links or spawns on it. So a friend who has
 * never posted is admitted into a viewer's scope but has no `snb-authored`
 * cell for a pull to find: the pull simply issues no leg for them
 * (`FeedSession.pull`'s `families.authored.contains(key)` filter). This class
 * is the demo-layer stand-in for the kernel seam that does not exist, and
 * `doc/demo-findings.md` `F-24` records the seam itself as a finding rather
 * than proposing to add it here.
 *
 * **What it does.** `admit(scope)` walks every key `scope` names and, for any
 * key [family] does not already know, calls `family.getOrSpawn(key)` —
 * durably (`authored/keys`, the family's append-on-first-spawn log), so the
 * spawn survives a restart like any other. The spawned cell's `SocialGraph`
 * observe sink is minted the same way as any other author's: the first
 * `addPost` through `authoredCell`, not this class, so nothing about the
 * observation path is lost or duplicated by spawning early. A cell admitted
 * this way is registered with its own per-author `Ranges` by
 * `SnbPipeline.build`'s factory (8eb53-D4), so it passes
 * `LocationRegistry.interestOf(ref).overlaps(scope)` and the next pull answers
 * it with an empty page at `since = null`.
 *
 * **Why it is opt-in.** The spawn is durable: every admitted-but-absent
 * friend grows `authored.keys()` forever, which is exactly what
 * `[SOC1-SREAD-03]` and `SocialFeedScatterGatherTest`'s AMENDS behaviour
 * assert against for the default path. `SocialApp(interestDriven = true)` is
 * the only caller that wires this in.
 */
package civictech.demo.social

import civictech.cell.host.KeyedCells
import civictech.cell.link.Interest

/**
 * Admits every key an [Interest.Ranges] names into [family], spawning the
 * ones it does not already know (4q9is-D7). `Interest.Empty` admits nothing.
 * Any other arm (`Total`, `Slots`, `Union`, `Intersect`, `Complement`) is
 * refused with [IllegalArgumentException] — the same rule
 * `FeedSession.keysOf` applies to a pull's scope (4q9is-D5): nothing here may
 * spawn against an unbounded interest.
 */
class InterestDrivenFamily(private val family: KeyedCells<Long>) {

    /** Returns the keys this call actually spawned (a subset of, or all of, [scope]'s keys). */
    fun admit(scope: Interest): Set<Long> = when (scope) {
        is Interest.Ranges -> {
            val keys = scope.ranges.flatMap { r -> (r.lo until r.hi).asIterable() }.distinct()
            val spawned = LinkedHashSet<Long>()
            for (key in keys) {
                if (!family.contains(key)) {
                    family.getOrSpawn(key)
                    spawned += key
                }
            }
            spawned
        }

        Interest.Empty -> emptySet()

        else -> throw IllegalArgumentException("interest-driven spawn needs Ranges or Empty, got $scope")
    }
}
