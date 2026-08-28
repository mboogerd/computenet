package civictech.loader.fixture.flow

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCellBase
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.link.catchUpOnLinked
import java.util.UUID

/**
 * Fixture (j) of epic computenet-051's fixture set (task computenet-051.5.2): a real,
 * linkable dataflow module — every other fixture under `loader/fixtures/` is
 * deliberately portless (see `:loader:fixtures:valid-basic`'s KDoc), so nothing
 * loadable could be linked, flowed through, or promoted. This module carries two
 * cells, both declared through the real `buildsrc.convention.ksp-cell` pipeline
 * (epic risk 051-R7): [FlowSetCell] (SPAWN-01/02/03) and [FlowPromotionCandidateCell]
 * (SPAWN-04/B14).
 *
 * Both cells extend [SetCellBase] — the `@CellBase`-generated base of kernel's own
 * `civictech.cell.data.SetCell` — rather than `SetCell` itself, which is `final`
 * and cannot be subclassed. Extending the generated base gives both cells the
 * *exact* port shape `SetCell<String>` has (`inlet: Use<SetOps<String>>`,
 * `outlet: Subscribe<Propagate<SetDelta<String>>>`), built from the same
 * `civictech.cell.` contract and payload types kernel's own cells use — so a link
 * or a `Promotion.promote` swap between a module-spawned instance of either cell
 * here and a host-classpath `SetCell<String>` sees the identical `Class` on both
 * ends, guaranteed by `ModuleClassLoader`'s parent-first delegation of the shared
 * `civictech.cell.*` prefix.
 *
 * Enabler only (computenet-051.5.2): no loader API change, no B1/B14 test here —
 * sibling tasks under feature computenet-051.5 consume this jar for those.
 */
private fun mintedTag(prefix: String, ref: CellRef, counter: Long): Timestamp =
    Timestamp(UUID.nameUUIDFromBytes("$prefix:${ref.id}:${ref.instanceId}".toByteArray()), counter)

/**
 * SPAWN-01/02/03's dataflow cell: a minimal observed-remove set — the same
 * add-wins tag algebra `civictech.cell.data.SetCell` uses (a unique tag per add,
 * a remove carries the tags it observed), trimmed to what a linkable, flowable
 * fixture needs. It deliberately does not carry `SetCell`'s replication,
 * durability, or bounded-read machinery ([civictech.cell.Replicable],
 * [civictech.cell.BoundedStateful]) — those are orthogonal to what this fixture
 * exists to prove (a *module*-spawned cell flows and links like a host one) and
 * are not part of computenet-051.5's acceptance criteria.
 */
open class FlowSetCell(ref: CellRef = CellRef(UUID.randomUUID())) : SetCellBase<String>(ref) {
    private val stateLock = Any()
    private val adds = mutableMapOf<String, MutableSet<Timestamp>>()
    private val dels = mutableMapOf<String, MutableSet<Timestamp>>()
    private var tagCounter = 0L

    /** Normalization hook [FlowPromotionCandidateCell] overrides for an observable behavioural difference. */
    protected open fun normalize(element: String): String = element

    override fun inletHandler(): SetOps<String> = object : SetOps<String> {
        override fun add(element: String) {
            val normalized = normalize(element)
            val tag = synchronized(stateLock) {
                val minted = mintedTag("fixture-flow-tags", ref, ++tagCounter)
                adds.getOrPut(normalized) { mutableSetOf() } += minted
                minted
            }
            outlet.call.propagate(SetDelta(adds = mapOf(normalized to setOf(tag))))
        }

        override fun remove(element: String) {
            val normalized = normalize(element)
            val observed = synchronized(stateLock) {
                val seen = (adds[normalized] ?: emptySet()) - (dels[normalized] ?: emptySet())
                if (seen.isEmpty()) return // effective-only: removing an unobserved element is a no-op
                dels.getOrPut(normalized) { mutableSetOf() } += seen
                seen
            }
            outlet.call.propagate(SetDelta(dels = mapOf(normalized to observed)))
        }
    }

    init {
        // late-join catch-up, mirroring SetCell: a subscriber linked after
        // elements were added still observes the current state as one delta.
        outlet.catchUpOnLinked {
            synchronized(stateLock) {
                if (adds.isEmpty() && dels.isEmpty()) null
                else SetDelta(
                    adds = adds.mapValues { it.value.toSet() },
                    dels = dels.mapValues { it.value.toSet() },
                )
            }
        }
    }
}

/**
 * SPAWN-04/B14's promotion candidate: contract-identical to [FlowSetCell] and to
 * kernel's own `SetCell<String>` — same base, same `outlet` `Class` — but
 * observably different behaviour (every element is upper-cased before it is
 * folded and propagated), so a promotion swap away from an incumbent is
 * detectable by the *content* it emits, not by its port shape.
 */
class FlowPromotionCandidateCell(ref: CellRef = CellRef(UUID.randomUUID())) : FlowSetCell(ref) {
    override fun normalize(element: String): String = element.uppercase()
}
