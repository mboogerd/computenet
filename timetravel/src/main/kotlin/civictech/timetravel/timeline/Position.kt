package civictech.timetravel.timeline

import java.util.UUID

/**
 * Where in a [RunTimeline] to resolve a replay prefix to (TTD1 F2, feature D3): a record index,
 * or a per-source frontier cut.
 */
sealed interface Position {
    /** The prefix ending exactly after the record at [index]. */
    data class Index(val index: Int) : Position

    /** The longest prefix whose waved frames all lie at or behind [perSource] (`[TTD1-18]`). */
    data class Cut(val perSource: Map<UUID, Long>) : Position
}
