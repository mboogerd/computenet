package civictech.cell.data.op

/**
 * A two-inlet, non-monotone operator that can run with the opt-in
 * `emitOnFrontier` gate (`[24-OP-SEMIJOIN-04]` and its family extension, KE2
 * §5.2 / `computenet-0favn`): constructed with `emitOnFrontier = true` it
 * buffers each input wave in a [WaveGate] and emits only that wave's net effect
 * at completeness.
 *
 * The family is six cells — [SemiJoinCell], [CombineLatestCell],
 * [JoinSetCell], [IntersectSetCell], `JoinCell` and `LookupJoinCell` — and
 * [frontierGated] is `true` iff the instance was constructed with
 * `emitOnFrontier = true`. It is a marker for readers, not an operator: F3's
 * admission check (`computenet-lw0mv`) walks a live graph's ancestors and needs
 * to ask "is this cell gated?" without knowing the concrete class, and
 * `cell is FrontierGateable` is that class list, kept next to the cells.
 */
interface FrontierGateable {
    /** `true` iff this cell was constructed with `emitOnFrontier = true`. */
    val frontierGated: Boolean
}
