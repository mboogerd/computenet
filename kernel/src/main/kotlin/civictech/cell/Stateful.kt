package civictech.cell

import java.io.Serializable

/**
 * A cell whose state can be captured and restored (P9; starts G-25). The drain
 * protocol (spec 33) snapshots on drain and forces a serialization round-trip
 * on migration; the same seam serves durability snapshots (24) and
 * cross-instance state migration (G-33) later.
 */
interface Stateful {
    fun snapshot(): Serializable
    fun restore(state: Serializable)
}
