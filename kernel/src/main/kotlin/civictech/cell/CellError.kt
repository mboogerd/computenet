package civictech.cell

import civictech.cell.data.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.proxy.HostedPortInvocation

/**
 * A cell-scoped failure, emitted on the failing cell's own [ErrorReporting.errorOutlet]
 * by its host (in addition to the host's dead-letter outlet — observability is
 * not a policy). This is the narrowed remainder of G-26: errors flow through
 * visible topology to whoever links a consumer, e.g. an invariant cell (52).
 */
data class CellError(
    val cellRef: CellRef,
    val cause: Throwable,
    val invocation: HostedPortInvocation? = null,
)

/**
 * Opt-in marker (P6 — cells declare capabilities, the kernel imposes none):
 * a cell exposing this port receives its own invocation failures as data.
 */
interface ErrorReporting {
    val errorOutlet: FanOutlet<Propagate<CellError>>
}
