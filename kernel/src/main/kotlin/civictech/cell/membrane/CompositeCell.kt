package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.Port
import civictech.cell.port.registerPort
import civictech.cell.proxy.Proxy
import java.util.UUID

/**
 * A surface mode of one [Exposure] (spec 10/11 "Decided model"):
 * - **FLATTEN** (green): the exposure delegates, off the per-message path —
 *   authority is link-time `onLink` policies only.
 * - **MEDIATE** (red-with-logic): a served proxy sits on the path, captures
 *   each `Invocation`, and forwards it — the sole flow-time enforcement
 *   point (10/11 "Boundary policy").
 */
enum class SurfaceMode { FLATTEN, MEDIATE }

/**
 * A wave scope of one [Exposure] (spec 10/11 "Decided model"): **PRESERVE**
 * (waves flow through per 20/22, transparent) or **REMINT** (Mediate only —
 * the proxy mints fresh waves from its own counter). Only PRESERVE is
 * realized today; REMINT's wave re-mint interplay with attention
 * propagation and late-join catch-up is explicitly open (G-52 residual,
 * 50/51) and is recorded here for correctness-by-construction but not yet
 * implemented by [CompositeCell.mediate].
 */
enum class WaveScope { PRESERVE, REMINT }

/**
 * One named seam of a composite cell's membrane (spec 10/11 "Decided
 * model"): [externalName] is the ONLY name external resolvers may use;
 * [organellePortName] documents which internal port it re-presents, for
 * diagnostics and future wire/DSL lowering (40/41, 50/51 — G-52 residual).
 * Couplings (Symport/Antiport) and an observe/trace outlet are part of the
 * decided model but out of scope here: coupling liveness is research-gated
 * (95 §R3, G-53) and this ticket ships without them (gated off) rather than
 * with the documented wait-forever caveat.
 */
data class Exposure(
    val externalName: String,
    val organellePortName: String,
    val mode: SurfaceMode,
    val waveScope: WaveScope = WaveScope.PRESERVE,
)

/**
 * A composite cell (spec 10/11 §"Hierarchy: organelles" and §"Membranes and
 * policies"): a cell that contains organelle cells and declares an
 * [exposureMap] naming the only ports external resolvers may reach —
 * **hidden by default (G-9)**.
 *
 * Enforcement: organelles are held only as direct in-process references by
 * the subclass (never spawned onto a [civictech.cell.host.ManagedHost] in
 * their own right) — "Organelles stay addressable internally via the direct
 * references the container holds from spawning them; hiding costs nothing
 * on the internal path" (10/11). A host's port/cell resolution
 * (`findPort`/`lookup`/`connect`, spec 30/31) walks each cell's own
 * [civictech.cell.port.PortRegistry] entry; since only [flatten]/[mediate]
 * register a port under this composite's name, that per-cell registry *is*
 * the containment record host resolution consults — an external connect
 * naming a non-exposed organelle port name, or the organelle's own
 * (unpublished) [CellRef], finds nothing and is refused as unknown-port,
 * exactly as 10/11 requires, without any new host-level bookkeeping. This
 * generalizes the existing G-28 parent/child pattern (host-nesting, M8.1)
 * to cell-granularity containment.
 */
abstract class CompositeCell(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {

    private val exposureMapMutable = linkedMapOf<String, Exposure>()

    /** The declared exposure map (spec 10/11) — read-only view for diagnostics/tests. */
    val exposureMap: Map<String, Exposure> get() = exposureMapMutable

    /**
     * Flatten-exposes an existing organelle [port] under [externalName]: the
     * SAME port object is re-registered in this composite's namespace (spec
     * 10/11: "an exposed link is the organelle outlet's own subscriber, not
     * a second consumer" — cardinality/SPSC stays counted once, at the
     * organelle port). This is `delegate`'s O(1) collapse (10/14): the
     * composite is not on the per-message path at all.
     */
    protected fun <P : Port> flatten(externalName: String, organellePortName: String, port: P): P {
        require(externalName !in exposureMapMutable) { "Duplicate exposure: $externalName" }
        exposureMapMutable[externalName] = Exposure(externalName, organellePortName, SurfaceMode.FLATTEN)
        return registerPort(externalName, port)
    }

    /**
     * Mediate-exposes [organelleInlet] under [externalName]: installs a NEW
     * [FanInlet] on this composite, served by a hand-written [MediateProxy]
     * (10/14 "mediate-is-serve": `serve(proxy)` is a real cell on the
     * per-message path) that captures each invocation and forwards it to
     * [organelleInlet] — budget/cardinality is counted at this proxy's own
     * external face (10/11), distinct from the organelle's.
     *
     * KSP-generating this proxy from a declarative membrane annotation is
     * G-52's residual (50/51); this is the hand-written realization the
     * ticket ships instead. Coupling gates (Symport/Antiport) are not
     * wired here (G-53, research-gated liveness) — this proxy is a
     * transparent forward only.
     */
    protected fun <Api : Any> mediate(
        externalName: String,
        organellePortName: String,
        organelleInlet: FanInlet<Api>,
        waveScope: WaveScope = WaveScope.PRESERVE,
    ): FanInlet<Api> {
        require(externalName !in exposureMapMutable) { "Duplicate exposure: $externalName" }
        val exposed = FanInlet(organelleInlet.clazz)
        exposed.serve(Proxy.fromClass(organelleInlet.clazz, MediateProxy(organelleInlet.call)))
        exposureMapMutable[externalName] = Exposure(externalName, organellePortName, SurfaceMode.MEDIATE, waveScope)
        return registerPort(externalName, exposed)
    }
}
