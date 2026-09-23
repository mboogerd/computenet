package civictech.timetravel.journal

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.WireCodec
import civictech.timetravel.fidelity.Reason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads a journaled invocation frame (the bytes after the frame record's type byte, exactly as
 * `WireCodec.encode` wrote them) in two layers (TTD1 F1, features D3 and D4):
 *
 * - **structural** — the `WireFrame` keys that need no descriptor, parsed with a plain [Json]
 *   and the kernel's own `@Serializable` types (`[TTD1-03]`);
 * - **hydrated** — `WireCodec.decodeFrame`, which needs the contract's descriptor on this
 *   classpath and every polymorphic argument type registered (`[TTD1-04]`). Its failure is
 *   [Reason.NO_DESCRIPTOR], never an exception out of the reader (`[TTD1-05]`).
 */
internal object FrameJson {

    /**
     * No serializers module and no registry: only the kernel's plain `@Serializable` types are
     * decoded through it (`CellRef`, `MessageContext` — whose `UUID` fields bind their
     * serializer on the property, so they need no module). The two structural flags are the
     * ones `WireCodec`'s own `Json` sets, so a `context` element decodes here exactly as the
     * codec decodes it; `JournalReaderTest`'s structural-vs-hydrated agreement test pins that.
     */
    private val structuralJson = Json {
        allowStructuredMapKeys = true
        useArrayPolymorphism = true
        ignoreUnknownKeys = true
    }

    private class Unparseable(message: String) : Exception(message)

    fun read(index: Int, journalId: String, frameType: Byte, payload: ByteArray): JournalRecord =
        try {
            parse(index, journalId, payload)
        } catch (e: Exception) {
            val message = if (e is Unparseable) e.message.orEmpty() else e.toString()
            MalformedRecord(index, journalId, frameType, Reason.FRAME_UNPARSEABLE, message)
        }

    private fun parse(index: Int, journalId: String, payload: ByteArray): FrameRecord {
        val root = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject
            ?: throw Unparseable("frame is not a JSON object")

        val contractId = root.long("contractId")
        val methodId = root.long("methodId")
        val cellRef = structuralJson.decodeFromJsonElement(
            CellRef.serializer(),
            root["cellRef"] as? JsonObject ?: throw Unparseable("missing or ill-typed cellRef"),
        )
        val portName = root.string("portName")
        val typeName = root.string("type")
        val type = HostedPortInvocation.Type.entries.firstOrNull { it.name == typeName }
            ?: throw Unparseable("unknown invocation type '$typeName'")
        val context = root["context"]
            ?.takeUnless { it is JsonNull }
            ?.let { structuralJson.decodeFromJsonElement(MessageContext.serializer(), it) }
        val wireVersion = root["version"]?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
                ?: throw Unparseable("ill-typed version")
        }

        val reasons = mutableSetOf<Reason>()
        var hydrated: HydratedFrame? = null
        var hydrationFailure: String? = null
        if (wireVersion != null && wireVersion != WireCodec.VERSION) {
            // [TTD1-06]: an explicit foreign version is reported, and the codec is not asked.
            reasons += Reason.WIRE_VERSION_MISMATCH
        } else {
            try {
                hydrated = HydratedFrame(WireCodec.decodeFrame(payload).invocation)
            } catch (e: Exception) {
                hydrationFailure = e.toString()
                reasons += Reason.NO_DESCRIPTOR
            }
        }

        return FrameRecord(
            index = index,
            journalId = journalId,
            cellRef = cellRef,
            portName = portName,
            type = type,
            contractId = contractId,
            methodId = methodId,
            context = context,
            args = root["args"] ?: JsonNull,
            wireVersion = wireVersion,
            hydrated = hydrated,
            hydrationFailure = hydrationFailure,
            reasons = reasons,
        )
    }

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw Unparseable("missing or ill-typed $key")

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw Unparseable("missing or ill-typed $key")
}
