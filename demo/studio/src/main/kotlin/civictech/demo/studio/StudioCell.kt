package civictech.demo.studio

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.evolve.StateMigrating
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.graph.CellFactory
import civictech.cell.TagFrontier
import civictech.cell.port.Use
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Strings are the journal wire envelope; Java Serializable alone is not a wire codec. */
@Serializable
internal data class StudioMsg(val fromNodeId: String, val change: String, val emissionId: String)

@Serializable
internal data class JobRecord(val runId: String, val inputsHash: String, val phase: String,
                              val result: RunDetail? = null)

@Serializable
internal data class StudioDefinition(val id: String, val kind: String, val config: kotlinx.serialization.json.JsonObject) {
    fun node(canvas: CanvasNode? = null) = StudioNode(id, kind, canvas?.title ?: kind,
        canvas?.x ?: 0.0, canvas?.y ?: 0.0, config, inputPorts(kind, config))
}
internal fun StudioNode.definition() = StudioDefinition(id, kind, config)

@Serializable
internal data class StudioCheckpoint(val definition: StudioDefinition, val state: NodeState,
    val job: JobRecord? = null, val pending: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
    val inbox: Map<String, StudioMsg> = emptyMap(), val outputId: String? = null,
    val liveCode: String? = null)

@Serializable
internal data class StudioCommit(val checkpoint: StudioCheckpoint, val acknowledge: List<String> = emptyList(),
                                 val emissionId: String? = null)

@CellBase
interface StudioCellApi {
    val inlet: Use<Propagate<String>>
    val commit: Use<Propagate<String>>
    val outlet: Subscribe<Propagate<String>>
    val notifications: Subscribe<Propagate<String>>
}

/** Only immutable construction data goes in topology records; services attach after recovery. */
internal data class StudioFactory(val definitionJson: String) : CellFactory {
    override fun create(ref: CellRef) = StudioCell(ref, json.decodeFromString(definitionJson))
}

/** Pure journaled handlers. Workers and callbacks are never part of a factory or checkpoint. */
internal class StudioCell(ref: CellRef, definition: StudioDefinition) : StudioCellCellBase(ref), Stateful, StateMigrating {
    private var held = StudioCheckpoint(definition, NodeState(definition.id))

    init {
        outlet.linking.onLinked = { link ->
            held.state.output?.let { output ->
                outlet.baselineTo(link.to, TagFrontier(emptyMap())) { propagate(envelope(output.toString())) }
            }
        }
    }

    private fun envelope(change: String) = json.encodeToString(StudioMsg(held.definition.id, change, checkNotNull(held.outputId)))

    override fun onInlet(value: String) {
        val input = json.decodeFromString<StudioMsg>(value)
        // An acknowledged delivery may be re-fired by checkpoint catch-up. Intake also
        // compares held values; the inbox keeps unacknowledged delivery crash-safe.
        held = held.copy(inbox = held.inbox + (input.emissionId to input))
        notifications.call.propagate(held.definition.id)
    }

    override fun onCommit(value: String) {
        val update = json.decodeFromString<StudioCommit>(value)
        held = update.checkpoint.copy(inbox = held.inbox - update.acknowledge.toSet(),
            outputId = update.emissionId ?: held.outputId)
        if (update.emissionId != null) held.state.output?.let { outlet.call.propagate(envelope(it.toString())) }
    }

    override fun importFrom(prior: java.io.Serializable) {
        val incumbent = json.decodeFromString<StudioCheckpoint>(prior as String)
        val code = checkNotNull(held.state.code)
        val hash = sha256(code)
        // Import continuity, never overwrite the reviewed candidate's implementation.
        // This runs both at COMMIT and during uncompacted Promote replay.
        held = incumbent.copy(state = incumbent.state.copy(code = code, codeHash = hash,
            approvedHash = hash, preview = null, status = "idle", error = null), liveCode = code,
            pending = emptyMap())
    }

    override fun snapshot(): java.io.Serializable = json.encodeToString(held)
    override fun restore(state: java.io.Serializable) { held = json.decodeFromString(state as String) }
}

/** Journal-reconstructible membrane; program inputs always traverse this gate. */
internal data object StudioGateFactory : CellFactory {
    @Suppress("UNCHECKED_CAST")
    override fun create(ref: CellRef) = TrafficLightCell(Propagate::class.java as Class<Propagate<String>>, ref)
}
