package civictech.cell.verify

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.util.*

/**
 * A detected invariant violation, emitted as data on the invariant cell's
 * `violations` outlet — one mechanism serves CI, live monitoring, and
 * promotion gates (52).
 */
data class Violation(
    val invariant: String,
    val message: String,
    val observed: Any?,
)

/**
 * An invariant as a cell (G-31): subscribes to the flows it constrains
 * (attaching an invariant to a subgraph is just linking), folds observations
 * into [state], and emits a [Violation] whenever [check] rejects. An ordinary
 * cell — no kernel privileges; meaningful in a single-threaded simulation (P1).
 */
class InvariantCell<T : Any, S>(
    val name: String,
    initial: S,
    private val fold: (S, T) -> S,
    private val check: (S, T) -> String?,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<T>>())
    val violations = registerPort("violations", FanOutlet.create<Propagate<Violation>>())

    var state: S = initial
        private set

    init {
        inlet.serve(object : Propagate<T> {
            override fun propagate(value: T) {
                state = fold(state, value)
                check(state, value)?.let {
                    violations.call.propagate(Violation(name, it, value))
                }
            }
        })
    }

    companion object {
        /** A stateless invariant: every observation must individually satisfy [check]. */
        fun <T : Any> observing(name: String, check: (T) -> String?): InvariantCell<T, Unit> =
            InvariantCell(name, Unit, { _, _ -> }, { _, value -> check(value) })
    }
}
