package civictech.kernel.germ

import civictech.kernel.germ.port.*
import java.util.*

/**
 * A Runner that manages the lifecycle and connectivity of [Cell]s.
 */
class ManagedRunner(
    override val ref: CellRef = CellRef(UUID.randomUUID())
) : Runner {
    override val managementInlet = FanInlet.create<RunnerApi>()

    private val cells = mutableMapOf<CellRef, Cell>()
    private val ctx = object : CellContext {}

    init {
        managementInlet.serve(object : RunnerApi {
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
        })
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
