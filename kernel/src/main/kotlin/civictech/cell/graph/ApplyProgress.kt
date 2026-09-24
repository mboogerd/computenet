package civictech.cell.graph

import java.io.Serializable

/**
 * One reported outcome of [GraphSpec.applyRemote]'s per-step application
 * (computenet-4jdw0-D1): [index] is the 0-based position of the step in
 * [GraphSpec.lowered], [handle] is exactly the key that step's [StepResult]
 * is folded under in the returned [ApplyReport] (the [SpawnStep.handle], or
 * `"$from.$outlet->$to.$inlet"` for a [ConnectStep]), and [result] is that
 * step's outcome.
 */
data class StepEvent(val index: Int, val handle: String, val result: StepResult) : Serializable

/**
 * Callback invoked once per [GraphSpec.lowered] step during
 * [GraphSpec.applyRemote], synchronously on the applier's thread, in step
 * order, immediately after that step's [StepResult] is known and before the
 * next step starts. A throw from [onStep] propagates to the applier — it is
 * the applier's own callback, not one the apply machinery guards.
 */
fun interface ApplyProgress {
    fun onStep(event: StepEvent)
}
