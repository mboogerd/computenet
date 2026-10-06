package civictech.cell

import civictech.cell.proxy.HostedPortInvocation
import java.io.Serializable
import java.util.IdentityHashMap

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

/**
 * A cell-owned holding tier whose accepted invocations have already left the host scheduler.
 * Checkpoint compaction carries these frames after the snapshot just like scheduler-, policy-
 * and cold-inlet-held work. Implementations return a stable, ordered copy and retain ownership
 * of their live queue; checkpointing is an observation, never a drain.
 *
 * This capability lives beside [Stateful], not in the host package: cells expose checkpoint
 * state without depending on the runtime that consumes it.
 */
internal interface CheckpointFrameSource {
    fun checkpointFrames(): List<HostedPortInvocation>
}

/** One exact replay position retained beside a checkpointed cell-owned frame. */
internal data class CheckpointReplayPosition(
    val cellRef: CellRef,
    val portName: String,
    val timestamp: Timestamp,
) : Serializable

private typealias CheckpointReplayCounts =
    MutableMap<Pair<CellRef, String>, MutableMap<Timestamp, Int>>

/**
 * Bridges exact replay suppression across a checkpoint that carries a cell-owned frame.
 *
 * A replay-derived frame parked in a cell can be either pending work or a copy of work also
 * present later in the same journal. Before compaction, the host's replay-position table decides
 * that per downstream target. The checkpoint keeps one matching target position with each
 * parked invocation; after recovery the cell registers those positions against the new replay's
 * journal token. Every asynchronous descendant keeps that token through [ReplayProvenance], so
 * the host can make the same per-target decision after the original downstream frame has been
 * folded into state.
 */
internal object CheckpointReplayPositions {
    private data class Capture(val journal: Any, val counts: CheckpointReplayCounts)

    private val capture = ThreadLocal<Capture?>()
    private val restored = IdentityHashMap<Any, CheckpointReplayCounts>()

    fun <T> capturing(
        journal: Any,
        replayed: Map<Pair<CellRef, String>, Map<Timestamp, Int>>,
        block: () -> T,
    ): T {
        val counts = replayed.mapValuesTo(mutableMapOf()) { (_, positions) -> positions.toMutableMap() }
        synchronized(restored) {
            restored[journal]?.forEach { (key, positions) ->
                val target = counts.getOrPut(key, ::mutableMapOf)
                positions.forEach { (timestamp, occurrences) ->
                    target[timestamp] = maxOf(target[timestamp] ?: 0, occurrences)
                }
            }
        }
        val previous = capture.get()
        capture.set(Capture(journal, counts))
        return try {
            block()
        } finally {
            capture.set(previous)
        }
    }

    /** Assign at most one occurrence per target to this parked invocation. */
    fun capture(replayOf: Any?, timestamp: Timestamp?): List<CheckpointReplayPosition> {
        val current = capture.get() ?: return emptyList()
        if (replayOf !== current.journal || timestamp == null) return emptyList()
        return current.counts.entries
            .sortedWith(compareBy({ it.key.first.toString() }, { it.key.second }))
            .mapNotNull { (key, positions) ->
                val occurrences = positions[timestamp] ?: return@mapNotNull null
                if (occurrences == 1) positions.remove(timestamp) else positions[timestamp] = occurrences - 1
                CheckpointReplayPosition(key.first, key.second, timestamp)
            }
    }

    fun register(replayOf: Any, positions: List<CheckpointReplayPosition>) {
        if (positions.isEmpty()) return
        synchronized(restored) {
            val counts = restored.getOrPut(replayOf, ::mutableMapOf)
            positions.forEach { position ->
                val byTimestamp = counts.getOrPut(position.cellRef to position.portName, ::mutableMapOf)
                byTimestamp[position.timestamp] = byTimestamp.getOrDefault(position.timestamp, 0) + 1
            }
        }
    }

    fun consume(replayOf: Any, cellRef: CellRef, portName: String, timestamp: Timestamp): Boolean =
        synchronized(restored) {
            val counts = restored[replayOf] ?: return@synchronized false
            val key = cellRef to portName
            val positions = counts[key] ?: return@synchronized false
            val occurrences = positions[timestamp] ?: return@synchronized false
            if (occurrences == 1) positions.remove(timestamp) else positions[timestamp] = occurrences - 1
            if (positions.isEmpty()) counts.remove(key)
            if (counts.isEmpty()) restored.remove(replayOf)
            true
        }

    fun clear(replayOf: Any) {
        synchronized(restored) { restored.remove(replayOf) }
    }
}
