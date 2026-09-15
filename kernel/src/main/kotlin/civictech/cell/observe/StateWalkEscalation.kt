package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.TagFrontier
import civictech.cell.host.LocationRegistry
import java.time.Instant
import java.time.InstantSource

/**
 * The `since` escalation for a caller who needs more than a [walkRouted]
 * walk's smeared union, as a supported routed operation (computenet-t6b.3.4,
 * t6b.3.4-D4/D5; KRD-22, KRD-23, KRD-29).
 *
 * [civictech.cell.StatePage]'s KDoc ("The escalation path for a caller who
 * needs more than the smeared union") documents the recipe: record the
 * opening frontier, walk to completion, then issue one further read with
 * `since` set to that frontier. This function is that recipe, once, so every
 * caller drives the same operation instead of hand-rolling it:
 *
 * ```
 * val base = walkRouted(registry, ref, request)
 * // ... await base.outcome, check base.outcome.isComplete ...
 * val delta = escalateRouted(registry, ref, request, base.outcome.get())
 * ```
 *
 * ### What it does
 *
 * Issues one further [walkRouted] walk with [request] unchanged except for
 * [StateRead.since], which becomes [base]'s [StateWalkOutcome.openingFrontier]
 * — or [TagFrontier] over an empty map when that frontier is `null`. Nothing
 * else: no second loop of its own, no fold, no new outcome type. The delta
 * walk's own [StateWalkOutcome] — its termination, entries, caveats and
 * stamps — **is** the result, computed exactly as any other walk's is
 * (including its own [civictech.cell.observe.StateWalkOutcome.stability], the
 * sibling verdict this function does not touch).
 *
 * ### Why `TagFrontier(emptyMap())` and never `since = null` for a null base
 *
 * [base.openingFrontier][StateWalkOutcome.openingFrontier] is `null` for a
 * family with no tag frontier at all
 * ([civictech.cell.BoundedStateful.supportsSince]'s KDoc: honouring `since`
 * means stamping a real frontier — no null-frontier family overrides it, so
 * every one of them declares `supportsSince == false`). Passing that `null`
 * straight through as `since` would ask for `since = null`, which is a
 * **full second walk of the whole state**, not a delta — exactly what KRD-23
 * forbids emulating. `TagFrontier(emptyMap())` is instead a real, non-null
 * `since` naming "nothing observed of any source yet", so the request still
 * reaches [civictech.cell.host.ManagedHost.readState] as a `since`-bounded
 * read. `readState` then refuses it with
 * [civictech.cell.StateReadResult.Reason.SINCE_UNSUPPORTED] on the caller's
 * thread — before any task reaches the cell's host — because the target
 * family's `supportsSince` is `false`. That refusal is `readState`'s own:
 * this function never pre-empts it with a reason of its own, and the
 * returned [StateWalk]'s `outcome` is already done, with zero pages and zero
 * host tasks, by the time this function returns.
 *
 * ### What the escalation repairs, and what it inherits
 *
 * **Repairs:** an element added after the base walk opened. A family such as
 * [civictech.cell.data.SetCell] freezes its enumeration order at walk open,
 * so such an element is never paged by the base walk at all — the delta
 * names it with an add-tag beyond [base]'s opening frontier, and a
 * per-element union of add/del tag sets over the base entries plus the delta
 * entries makes it present.
 *
 * **Inherits — this is not a real snapshot.** An element removed mid-walk
 * reaches the delta as an entry carrying only its tombstone's dot and **no**
 * add-tags: the tombstone's own covered add-tags are at or below `since` and
 * are filtered out along with them (`SetCell.tagsBeyond`), so no fold over
 * the delta can learn which adds the tombstone covers or retract them. A
 * reordered remote deletion whose dot sits below a per-source max this
 * replica already holds moves neither the base walk's closing stamp nor the
 * delta's own stamps, so it is invisible to either walk. Both limits are
 * exactly the ones documented on the family-qualification paragraph of
 * `doc/spec/20-dataflow-semantics/21-propagation.md` [21-PULL-03] ("the
 * `since` escalation path inherits the same limit, because it filters out
 * the tombstone's re-used tags along with the adds they cover"). The
 * operation this function offers is therefore **"the delta of tag gains
 * since the opening frontier"**, never "a real snapshot" — a caller that
 * needs the latter has no primitive that gives it one; closing that gap is
 * the frontier-representation research item, out of scope here.
 *
 * ### Preconditions
 *
 * Both are caller-thread checks, mirroring [walkRouted]'s own `cursor`
 * precondition, and both fail before any read is issued:
 *
 * - [base] must be complete ([StateWalkOutcome.isComplete]) — escalating a
 *   walk that was refused, cancelled, deadline-exceeded, unbounded or failed
 *   is a programming error, not a read outcome: there is no opening frontier
 *   to trust and no base union to fold the delta over.
 * - [request.cursor][StateRead.cursor] must be `null` — an escalation is a
 *   fresh walk, exactly as [walkRouted] requires.
 *
 * @param registry Where [ref] is looked up, exactly as [walkRouted] uses it.
 * @param ref The same cell [base] was walked over.
 * @param request The caller's bounds for the delta walk; every field except
 *   [StateRead.since] passes through as given (and [StateRead.since] itself
 *   is expected to be unset here — it is overwritten regardless).
 * @param base The completed outcome of the walk being escalated.
 * @param deadline Passed through to the delta [walkRouted] call, unchanged.
 * @param clock Passed through to the delta [walkRouted] call, unchanged.
 * @return a [StateWalk] handle for the delta walk — refused synchronously
 *   when the target family does not support `since`, in flight otherwise.
 * @throws IllegalArgumentException if [base] did not complete, or if
 *   [request.cursor][StateRead.cursor] is non-null.
 */
fun escalateRouted(
    registry: LocationRegistry,
    ref: CellRef,
    request: StateRead,
    base: StateWalkOutcome,
    deadline: Instant? = null,
    clock: InstantSource = InstantSource.system(),
): StateWalk {
    require(base.isComplete) {
        "escalateRouted requires a completed base walk; was terminated by ${base.termination}"
    }
    require(request.cursor == null) {
        "a walk starts from a fresh cursor; request.cursor must be null (was ${request.cursor})"
    }
    return walkRouted(
        registry,
        ref,
        request.copy(since = base.openingFrontier ?: TagFrontier(emptyMap())),
        deadline,
        clock,
    )
}
