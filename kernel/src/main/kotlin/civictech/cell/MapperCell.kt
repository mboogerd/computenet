package civictech.cell

import civictech.cell.port.input
import civictech.cell.port.output
import civictech.cell.port.use
import java.util.*

/**
 * A Cell that maps inputs of type [A] to outputs of type [B].
 */
@Suppress("UNCHECKED_CAST")
class MapperCell<A, B>(
    private val f: (A) -> B,
    override val ref: CellRef = CellRef(UUID.randomUUID())
) : Cell {
    val inlet by input(Consumer::class.java as Class<Consumer<A>>)
    val outlet by output(Consumer::class.java as Class<Consumer<B>>)

    override fun onActivate(ctx: CellContext) {
        inlet.serve(object : Consumer<A> {
            override fun provide(input: A) {
                outlet.use { provide(f(input)) }
            }
        })
    }
}
