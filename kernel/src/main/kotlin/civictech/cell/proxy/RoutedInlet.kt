package civictech.cell.proxy

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.data.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import java.lang.reflect.Method

/**
 * A first-class, routed write-handle to a named [Propagate] inlet on a cell
 * addressed by [CellRef] — the type-safe front door to the same wiring
 * [HostedCellProxy] builds, without a per-port proxy interface.
 *
 * `HostedCellProxy.create(ref, registry, XProxy::class.java).port.call` walks a
 * JDK proxy through *cell → port → api* to recover a `Propagate<D>` whose
 * `propagate(v)` enqueues a [HostedPortInvocation] on the host queue. The only
 * inputs that walk actually needs are the cell ref, the port name, and the fact
 * that the api is [Propagate]; a caller-declared `interface XProxy { val port:
 * Use<Propagate<D>> }` supplies nothing else. [inlet] takes those three inputs
 * directly and returns a reusable [RoutedPropagate] that builds the identical
 * invocation — same queue, same `PORT_API` type, same captured wave context —
 * so delivery is byte-for-byte the proxy path's, minus the interface and the
 * unchecked `as` cast (05, `agora-routed-inlet-handle-without-proxy-interface`).
 *
 * **Staging is preserved.** `propagate` enqueues through the registry/host sink
 * exactly as the proxy does; every hop stays staged for attention/magnitude
 * scheduling (`agora-scheduler-staged-links`) — this is a front door to the
 * routed path, never a fused synchronous call.
 */
class RoutedPropagate<D>(
    private val cellRef: CellRef,
    private val portName: String,
    private val sink: InvocationSink,
) : Propagate<D> {

    /**
     * Steady-state send: one [HostedPortInvocation] built and handed to the
     * sink, matching [HostedCellProxy]'s `apiInvocation` (data path carries the
     * wave context across the host boundary, G-4). No proxy dispatch, no extra
     * allocation beyond the per-send invocation the proxy already builds — the
     * resolved handle is reused across sends.
     */
    override fun propagate(value: D) {
        sink.deliver(
            HostedPortInvocation(
                cellRef = cellRef,
                portName = portName,
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(PROPAGATE, arrayOf<Any?>(value), CurrentContext.get()),
            )
        )
    }

    private companion object {
        /** The one method a `Propagate<D>` send targets — reflected once, reused every send. */
        val PROPAGATE: Method = Propagate::class.java.getMethod("propagate", Any::class.java)
    }
}

/**
 * Outcome of resolving a named inlet on a *locally-hosted* cell (the seam
 * [ManagedHost.resolveInlet] returns). Turns the proxy path's silent
 * mis-targeting — an unchecked cast that only surfaces as a `ClassCastException`
 * or a wrong-port dead letter at delivery — into an eager, typed rejection that
 * names the cell and port at resolve time.
 */
sealed interface RoutedInletResolution {
    /** This host does not host the cell (it relocated, despawned, or never was here). */
    data object NoCell : RoutedInletResolution

    /** The cell has no port under this name; [names] is what it does expose. */
    data class NoPort(val names: Set<String>) : RoutedInletResolution

    /** The port exists but is not a [civictech.cell.port.Use] — nothing to route a `propagate` to. */
    data object NotUsable : RoutedInletResolution

    /**
     * A usable inlet. [apiClass] is its erased api class when statically
     * recoverable (`FanInlet`/`Inlet` carry it), else null — the payload type
     * argument (`Propagate<D>`'s `D`) is erased and never recoverable here, so
     * the wrapper-class check is the strongest lookup-time guard available.
     */
    data class Usable(val apiClass: Class<*>?) : RoutedInletResolution
}

/**
 * A routed [Propagate] write-handle to the named [port] on [cell], reified over
 * the payload type — the collapse of a per-port `interface XProxy { val port:
 * Use<Propagate<D>> }` plus its `HostedCellProxy.create(...) as XProxy` helper
 * to one call:
 *
 * ```kotlin
 * val stance: Propagate<StanceDelta> = registry.inlet(id, "stanceInlet")
 * stance.propagate(StanceDelta(user, value))
 * ```
 *
 * The handle routes through [LocationRegistry.deliver] — the re-resolving sink
 * that parks and replays on relocation (spec 33) — so it survives the target
 * moving hosts, exactly as a registry-built proxy does. Resolve once and reuse
 * it; every send reuses the one resolved handle (no steady-state allocation
 * beyond the invocation the proxy path already builds).
 *
 * Validation is eager where the metadata allows: if [cell] is currently local,
 * the port must exist and be a [Propagate]-shaped [civictech.cell.port.Use], or
 * this throws naming the cell and port. The **payload** type ([D]) is erased on
 * the registered port and cannot be checked — a `Propagate<A>` vs `Propagate<B>`
 * mismatch is not caught here (see [RoutedInletResolution.Usable]); the wrapper
 * shape is. A remote cell cannot be introspected across the wire (M5.4), so its
 * port validation is deferred to delivery (best-effort); an entirely unknown ref
 * is rejected.
 *
 * @throws IllegalArgumentException if no cell [cell] is published, or the cell
 *     is local but has no port named [port].
 * @throws IllegalStateException if the local port is not a usable [Propagate] inlet.
 */
inline fun <reified D : Any> LocationRegistry.inlet(cell: CellRef, port: String): Propagate<D> =
    inlet(cell, port, D::class.java)

/** Class-taking form of [inlet] — the reified overload's delegate; validation and routing live here. */
fun <D : Any> LocationRegistry.inlet(cell: CellRef, port: String, @Suppress("UNUSED_PARAMETER") payloadType: Class<D>): Propagate<D> {
    when (val location = location(cell)) {
        is LocationRegistry.Local -> validateRoutedInlet(location.host, cell, port)
        // A bridge egress cannot be asked for a remote cell's ports (M5.4, spec 41);
        // the send validates at the far side's delivery — the same best-effort the proxy has.
        is LocationRegistry.Remote -> {}
        null -> throw IllegalArgumentException(
            "cannot route inlet '$port': no cell $cell is published on this registry"
        )
    }
    return RoutedPropagate(cell, port, this::deliver)
}

/**
 * Fixed-host form of [inlet]: a handle bound to [this] host's intake, mirroring
 * `HostedCellProxy.create(ref, host, clazz)`. A closed intake surfaces
 * [civictech.cell.host.IntakeClosedException] at the send site (spec 33), not a
 * park — use the [LocationRegistry] overload for a re-resolving, relocation-safe
 * handle. The cell must live on [this] host at resolve time.
 */
inline fun <reified D : Any> ManagedHost.inlet(cell: CellRef, port: String): Propagate<D> =
    inlet(cell, port, D::class.java)

/** Class-taking form of the fixed-host [inlet]. */
fun <D : Any> ManagedHost.inlet(cell: CellRef, port: String, @Suppress("UNUSED_PARAMETER") payloadType: Class<D>): Propagate<D> {
    validateRoutedInlet(this, cell, port)
    return RoutedPropagate(cell, port, this::enqueueHostedInvocation)
}

/** Shared lookup-time guard: translate a host's [RoutedInletResolution] into a typed failure or a pass. */
@PublishedApi
internal fun validateRoutedInlet(host: ManagedHost, cell: CellRef, port: String) {
    when (val resolution = host.resolveInlet(cell, port)) {
        RoutedInletResolution.NoCell -> throw IllegalArgumentException(
            "cannot route inlet '$port': cell $cell is not hosted here"
        )
        is RoutedInletResolution.NoPort -> throw IllegalArgumentException(
            "unknown inlet '$port' on cell $cell (available ports: ${resolution.names.sorted()})"
        )
        RoutedInletResolution.NotUsable -> throw IllegalStateException(
            "port '$port' on cell $cell is not a usable inlet (not a Use<…>)"
        )
        is RoutedInletResolution.Usable -> {
            val api = resolution.apiClass
            if (api != null && api != Propagate::class.java) throw IllegalStateException(
                "inlet '$port' on cell $cell accepts ${api.simpleName}, not Propagate — " +
                    "registry.inlet resolves Propagate-shaped inlets only"
            )
        }
    }
}
