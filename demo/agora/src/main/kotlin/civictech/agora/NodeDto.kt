package civictech.agora

import civictech.agora.cell.Polarity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The wire shape of one graph node — a claim or an edge — as served by
 * `GET /graph` and the `/events` SSE stream.
 *
 * This is the **contract** `demo/agora/ui/src/api/types.ts` is written
 * against ("The wire shape (civictech.agora AgoraApp.NodeDto)"), which is why
 * it lives in its own public file rather than nested privately inside
 * [AgoraApp]: `:demo:dialogue` serves the identical shape from a
 * dialogue-built graph so the same frontend renders it with no rewrite
 * (2aw.5-D3, epic computenet-2aw [AGO1-OBS-01]). One definition, never a
 * fork — a second copy is how the two backends drift apart silently.
 *
 * **The field order and the defaults are load-bearing.** kotlinx omits a
 * property whose value equals its declared default, so `polarity`, `source`
 * and `target` are absent on a CLAIM, `text` is absent on an EDGE, and `head`
 * is absent whenever it is false; `types.ts` models exactly those as
 * optional. Changing a default, or reordering the constructor, changes the
 * bytes on the wire.
 */
@Serializable
data class NodeDto(
    val ref: String,
    val kind: String,
    val text: String? = null,
    val polarity: Polarity? = null,
    val source: String? = null,
    val target: String? = null,
    val head: Boolean = false,
    val credence: Double,
)

/**
 * Encode a graph snapshot into the `/graph` response body.
 *
 * The single encoder for [NodeDto]: both [AgoraApp] and `:demo:dialogue`'s
 * HTTP surface call this rather than each building their own `Json`
 * configuration, which is what keeps the two byte-identical.
 */
fun graphJson(nodes: List<AgoraService.Node>): String = Json.encodeToString(
    ListSerializer(NodeDto.serializer()),
    nodes.map { node ->
        NodeDto(
            ref = node.ref.id.toString(),
            kind = node.info.kind.name,
            text = node.info.text,
            polarity = node.info.polarity,
            source = node.info.source?.id?.toString(),
            target = node.info.target?.id?.toString(),
            head = node.info.head,
            credence = node.credence,
        )
    },
)
