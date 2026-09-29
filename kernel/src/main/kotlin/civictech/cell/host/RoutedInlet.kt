package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.graph.InletId
import civictech.cell.link.Link
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.streamTo
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.InvocationSink
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
 * the registered port and cannot be checked at runtime — a `Propagate<A>` vs
 * `Propagate<B>` mismatch is not caught here (see [RoutedInletResolution.Usable]);
 * the wrapper shape is. The generated typed-id overload below closes that gap at
 * the call site instead, checking `D` at compile time. A remote cell cannot be
 * introspected across the wire (M5.4), so its port validation is deferred to
 * delivery (best-effort); an entirely unknown ref is rejected.
 *
 * @throws IllegalArgumentException if no cell [cell] is published, or the cell
 *     is local but has no port named [port].
 * @throws IllegalStateException if the local port is not a usable [Propagate] inlet.
 */
inline fun <reified D : Any> LocationRegistry.inlet(cell: CellRef, port: String): Propagate<D> =
    routedInlet(cell, port)

/**
 * Typed front door to [inlet]: [port] is a generated `<CellName>Ports.<port>`
 * id (`civictech.cell.graph.InletId<Propagate<D>>`), so `D` is bound from the
 * id at the call site — a payload-type mismatch (`Propagate<A>` expected,
 * `Propagate<B>` returned) is a compile error, not a delivery-time surprise
 * (jnkvu-D1/D2/R1). Lowers to the exact same [RoutedPropagate] over the same
 * name; nothing about validation, staging or delivery changes from the string
 * form — only the type-checking moves earlier.
 */
fun <D : Any> LocationRegistry.inlet(cell: CellRef, port: InletId<Propagate<D>>): Propagate<D> =
    routedInlet(cell, port.name)

/** Shared delegate for both [LocationRegistry.inlet] overloads — validation and routing live here. */
@PublishedApi
internal fun <D : Any> LocationRegistry.routedInlet(cell: CellRef, port: String): Propagate<D> {
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
    routedInlet(cell, port)

/** Typed front door to the fixed-host [inlet] — same [InletId]-bound `D` as the [LocationRegistry] overload. */
fun <D : Any> ManagedHost.inlet(cell: CellRef, port: InletId<Propagate<D>>): Propagate<D> =
    routedInlet(cell, port.name)

/** Shared delegate for both [ManagedHost.inlet] overloads. */
@PublishedApi
internal fun <D : Any> ManagedHost.routedInlet(cell: CellRef, port: String): Propagate<D> {
    validateRoutedInlet(this, cell, port)
    return RoutedPropagate(cell, port, this::enqueueHostedInvocation)
}

/**
 * Routes [this] outlet's emissions to a target inlet addressed by [cell] and
 * [port] — `outlet.routeTo(registry, ref, <Cell>Ports.port)` collapses
 * `outlet.streamTo(registry.inlet(ref, port))` to one call (jnkvu-D2/R4).
 * Delivery is staged through [LocationRegistry.inlet]/[streamTo] exactly as a
 * hand-built `streamTo(registry.inlet(...))` call would be — never a fused
 * synchronous call — and the returned [Link] tears the hop down on
 * [Link.unlink] like any `streamTo` link. Takes [registry] as a parameter
 * because an outlet holds no registry of its own; there is no
 * [ManagedHost]-bound form (a fixed-host handle from [ManagedHost.inlet] can
 * still be wired with `streamTo` directly).
 */
fun <D : Any> FanOutlet<Propagate<D>>.routeTo(
    registry: LocationRegistry,
    cell: CellRef,
    port: InletId<Propagate<D>>,
    at: PortRef = PortRef.generate(),
): Link = streamTo(registry.inlet(cell, port), at)

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
