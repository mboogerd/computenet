/**
 * The `:demo:social` read seam (SOC1, epic `computenet-07k` decision 07k-D4;
 * feature `computenet-rx8om` design rx8om-D7).
 *
 * **This interface is the KRD stand-in** `[SOC1-FIND-03]` names. The kernel's
 * only app-facing bounded read today is
 * [civictech.cell.host.ManagedHost.readState], a *host* method: a demo that
 * calls it directly binds its query layer to a host object, which is exactly
 * what a Kernel Request/Response Dispatch primitive would remove. So
 * [ShortReads] depends on this one-method interface and on nothing else —
 * `ShortReads.kt` does not name `ManagedHost` at all (grep-asserted by the
 * task's verification) — and [HostBoundedReader] is the single adapter that
 * does.
 *
 * What a public request/response primitive would have to offer to replace
 * this seam, as this demo exercises it:
 *
 * - a **per-request completion** (a future per page, not a link and not a
 *   subscription): a read must install no [civictech.cell.MessageContext]
 *   baseline and must not need a `PullOnOpen` handshake;
 * - **named refusal reasons** rather than a null or a timeout — the demo
 *   surfaces [civictech.cell.StateReadResult.Reason] verbatim
 *   ([ReadOutcome.Refused]) and never substitutes an empty or cached answer;
 * - the three orthogonal bounds ([civictech.cell.StateRead.since],
 *   `scope`, `cursor`/`limit`) with *explicit* refusal when a family cannot
 *   honour one;
 * - and — the gap this demo actually feels — **ordering and a true row
 *   limit**. [civictech.cell.StateRead.limit] is a page size in the cell's
 *   frozen enumeration order (rx8om-D2), so IS2's "ten newest messages"
 *   cannot be pushed into the request; the demo walks the owning cell to
 *   exhaustion and sorts on its own side.
 *
 * **Bounding is the caller's.** [read] hands back a
 * [java.util.concurrent.CompletableFuture] and imposes no deadline of its
 * own: on a [civictech.cell.host.SimulationController] host a page lands only
 * on the next `step()`/`runToIdle()`, so a seam that blocked would deadlock a
 * simulated caller. A caller that needs a bound applies its own
 * (`get(timeout, unit)` / `orTimeout`), which is what the HTTP layer does.
 */
package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.host.ManagedHost
import java.util.concurrent.CompletableFuture

/**
 * One bounded page read of the cell at [ref]. The only kernel capability
 * [ShortReads] needs, and the seam a test replaces to count reads
 * (`CountingReader`) or to force a refusal (`RefusingReader`).
 */
fun interface BoundedReader {
    fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult>
}

/**
 * The one adapter onto the kernel primitive: a straight delegation to
 * [ManagedHost.readState], which never completes exceptionally — every way a
 * read can fail to produce a page arrives as
 * [StateReadResult.Unavailable] with a named reason (rx8om-D1, observed
 * `ManagedHost.kt:1806-1851`). This class is the only place in `:demo:social`'s
 * read path that names [ManagedHost].
 */
class HostBoundedReader(private val host: ManagedHost) : BoundedReader {
    override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> =
        host.readState(ref, request)
}
