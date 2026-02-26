package civictech.kernel.germ

import civictech.kernel.germ.port.*
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.Proxy
import java.util.*
import java.util.concurrent.*

/**
 * A Runner that manages the lifecycle and connectivity of [Cell]s.
 */
class ManagedRunner(
    override val ref: CellRef = CellRef(UUID.randomUUID())
) : Runner {
    override val managementInlet = FanInlet.create<RunnerApi>()
    override val routerInlet = FanInlet.create<RouterApi>()

    private val cells = mutableMapOf<CellRef, Cell>()
    private val ctx = object : CellContext {}

    private class PrioritizedInvocation(val priority: Int, val invocation: Invocation) : Comparable<PrioritizedInvocation> {
        override fun compareTo(other: PrioritizedInvocation): Int = priority.compareTo(other.priority)
    }

    private val queue = PriorityBlockingQueue<PrioritizedInvocation>()
    private val thread: Thread

    init {
        val internalApi = object : RunnerApi {
            override fun spawn(cell: Cell): CellRef {
                cells[cell.ref] = cell
                cell.onActivate(ctx)
                return cell.ref
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
        }

        val internalRouterApi = object : RouterApi {
            override fun route(target: CellRef, inletName: String, invocation: Invocation) {
                val toCell = cells[target] ?: throw IllegalArgumentException("Target cell not found: $target")
                val inlet = findPort(toCell, inletName) as? Use<*>
                    ?: throw IllegalArgumentException("Inlet not found or not usable: $inletName on $target")

                invocation.invoke(inlet.call)
            }
        }

        // The public API proxy puts invocations into the queue
        managementInlet.serve(Proxy.fromClass(RunnerApi::class.java) { _, method, args ->
            queue.put(PrioritizedInvocation(0, Invocation.of(method, args).withTarget(internalApi)))
            null // Management methods are void or return CellRef (which we can't easily return synchronously)
        })

        routerInlet.serve(Proxy.fromClass(RouterApi::class.java) { _, method, args ->
            queue.put(PrioritizedInvocation(10, Invocation.of(method, args).withTarget(internalRouterApi)))
            null
        })

        // Start a virtual thread to process the queue
        thread = Thread.ofVirtual().name("ManagedRunner-${ref.id}").start {
            try {
                while (!Thread.interrupted()) {
                    val prioritized = queue.take()
                    try {
                        prioritized.invocation.invoke()
                    } catch (e: Exception) {
                        e.printStackTrace() // TODO: Better error handling
                    }
                }
            } catch (e: InterruptedException) {
                // stop thread
            }
        }
    }

    private fun findPort(cell: Cell, name: String): Port? {
        // 1. Try finding a direct getter method (standard for Kotlin properties)
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        try {
            val getter = cell.javaClass.methods.find { it.name == getterName }
            if (getter != null) {
                val value = getter.invoke(cell)
                if (value is Port) return value
            }
        } catch (_: Exception) {
            // ignore
        }

        // 2. Try direct field access (fallback)
        var currentClass: Class<*>? = cell.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            try {
                val field = currentClass.getDeclaredField(name)
                field.isAccessible = true
                val value = field.get(cell)
                if (value is Port) return value
            } catch (_: Exception) {
                // ignore
            }
            currentClass = currentClass.superclass
        }
        return null
    }
}
