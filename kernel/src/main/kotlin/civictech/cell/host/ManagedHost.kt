package civictech.cell.host



import civictech.cell.port.*
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * A Host that manages the lifecycle and connectivity of [Cell]s.
 *
 * Execution is delegated to a [HostScheduler]: threaded ([VirtualThreadScheduler], the default)
 * or deterministic ([civictech.cell.host.SimulationController]).
 */
open class ManagedHost(
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    scheduler: HostScheduler? = null,
) : Host {
    override val managementInlet = registerPort("managementInlet", FanInlet.create<HostManagementApi>())
    override val routerInlet = registerPort("routerInlet", FanInlet.create<HostRoutingApi>())

    /** Failed/undeliverable invocations are published here instead of being dropped (G-26). */
    val deadLetterOutlet = registerPort("deadLetterOutlet", FanOutlet.create<Propagate<DeadLetter>>())

    private val scheduler: HostScheduler = scheduler ?: VirtualThreadScheduler("ManagedHost-${ref.id}")

    private val cells = mutableMapOf<CellRef, Cell>()
    private val ctx = object : CellContext {}

    private fun deadLetter(cause: Throwable?, description: String, invocation: HostedPortInvocation? = null) {
        System.err.println("[ManagedHost ${ref.id}] dead letter: $description" + (cause?.let { " ($it)" } ?: ""))
        deadLetterOutlet.call.propagate(DeadLetter(ref, cause, description, invocation))
    }

    private fun enqueue(priority: Int, action: () -> Any?) {
        scheduler.submit(priority) {
            try {
                action()
            } catch (e: Exception) {
                deadLetter(e, "invocation failed: $e")
            }
        }
    }

    private fun <T> enqueueAwaiting(priority: Int, action: () -> T): T {
        val future = CompletableFuture<T>()
        scheduler.submit(priority) {
            try {
                future.complete(action())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return scheduler.await(future)
    }

    open fun enqueueHostedInvocation(hostedInvocation: HostedPortInvocation) {
        enqueue(20) {
            val cell = cells[hostedInvocation.cellRef] ?: return@enqueue deadLetter(
                null, "unknown cell ${hostedInvocation.cellRef}", hostedInvocation
            )
            val port = findPort(cell, hostedInvocation.portName) ?: return@enqueue deadLetter(
                null, "unknown port '${hostedInvocation.portName}' on ${hostedInvocation.cellRef}", hostedInvocation
            )

            when (hostedInvocation.type) {
                HostedPortInvocation.Type.PORT_MANAGEMENT -> {
                    hostedInvocation.invocation.invoke(port)
                }

                HostedPortInvocation.Type.PORT_API -> {
                    if (port is Use<*>) {
                        hostedInvocation.invocation.invoke(port.call)
                    }
                }
            }
        }
    }

    /**
     * Returns a managed reference to the API of a hosted cell.
     */
    fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
        if (!cells.containsKey(ref)) return null
        @Suppress("UNCHECKED_CAST")
        return HostedCellProxy.create(ref, this, clazz) as T
    }

    inline fun <reified T : Any> lookup(ref: CellRef): T? = lookup(ref, T::class.java)

    init {
        val internalApi = object : HostManagementApi {
            override fun spawn(cell: Cell): CellRef {
                require(!cells.containsKey(cell.ref)) { "Cell already spawned: ${cell.ref}" }
                cells[cell.ref] = cell
                cell.onActivate(ctx)
                return cell.ref
            }

            override fun <T : Any> lookup(ref: CellRef, clazz: Class<T>): T? {
                return this@ManagedHost.lookup(ref, clazz)
            }

            override fun despawn(ref: CellRef) {
                val cell = cells.remove(ref) ?: throw IllegalArgumentException("Cell not found: $ref")
                cell.onDeactivate(ctx)
            }

            override fun connect(from: CellRef, outletName: String, to: CellRef, inletName: String) {
                val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
                val toCell = cells[to] ?: throw IllegalArgumentException("Target cell not found: $to")

                val outlet = findPort(fromCell, outletName) as? LinkTo<*>
                    ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")
                val inlet = findPort(toCell, inletName) as? LinkFrom<*>
                    ?: throw IllegalArgumentException("Inlet not found or not linkable: $inletName on $to")

                @Suppress("UNCHECKED_CAST")
                (outlet as LinkTo<Any>).linkTo(inlet as LinkFrom<Any>)
            }

            override fun connect(from: CellRef, outletName: String, to: Use<*>) {
                val fromCell = cells[from] ?: throw IllegalArgumentException("Source cell not found: $from")
                val outlet = findPort(fromCell, outletName) as? LinkTo<*>
                    ?: throw IllegalArgumentException("Outlet not found or not linkable: $outletName on $from")

                @Suppress("UNCHECKED_CAST")
                (outlet as LinkTo<Any>).linkTo(to as Use<Any>)
            }
        }

        val internalHostRoutingApi = object : HostRoutingApi {
            override fun route(target: CellRef, inletName: String, invocation: Invocation) {
                val toCell = cells[target] ?: throw IllegalArgumentException("Target cell not found: $target")
                val inlet = findPort(toCell, inletName) as? Use<*>
                    ?: throw IllegalArgumentException("Inlet not found or not usable: $inletName on $target")

                invocation.invoke(inlet.call)
            }
        }

        managementInlet.serve(Proxy.fromClass(HostManagementApi::class.java) { _, method, args ->
            val invocation = Invocation.of(method, args).withTarget(internalApi)
            if (method.name.startsWith("spawn")) {
                enqueueAwaiting(0) { internalApi.spawn(args!![0] as Cell) }
            } else if (method.name.startsWith("lookup")) {
                @Suppress("UNCHECKED_CAST")
                enqueueAwaiting(0) { internalApi.lookup(args!![0] as CellRef, args[1] as Class<Any>) }
            } else {
                enqueue(0) { invocation.invoke() }
                null
            }
        })

        routerInlet.serve(Proxy.fromClass(HostRoutingApi::class.java) { _, method, args ->
            val invocation = Invocation.of(method, args).withTarget(internalHostRoutingApi)
            enqueue(10) { invocation.invoke() }
            null
        })
    }

    private fun findPort(cell: Cell, name: String): Port? = PortRegistry.of(cell)[name]
}
