package civictech.cell.data.op

/**
 * T05 finding 2 — the CP-A3 emit-or-absorb-ack shape (spec 20/22
 * §Completeness over silent or stuck edges, G-40): propagate a non-empty
 * delta, or absorb-ack the swallowed wave so a downstream glitch-free join's
 * per-source watermark still advances past a wave this operator silently
 * consumed. Without the ack, a `GlitchFreeCell` downstream of an absorbing
 * arm can stall forever on a membership-neutral final wave.
 *
 * One free function so every operator's emit-or-absorb check funnels through
 * the same logic: [TaggedSetOperator.emitOrAbsorb] and
 * [KeyedBinarySetJoin.emitOrAbsorb] both now delegate to this (previously
 * two independent copies of the identical `if (nonEmpty) propagate() else
 * absorbAck()` check); [IntersectSetCell], [QuorumSetCell], and [CountCell]
 * route through it directly instead of inlining the propagate-only half —
 * closing the ack gap those three used to leave open.
 */
fun emitOrAbsorb(isEmpty: Boolean, emit: () -> Unit, absorbAck: () -> Unit) {
    if (isEmpty) absorbAck() else emit()
}
