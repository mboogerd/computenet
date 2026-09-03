package civictech.agora

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `/graph` wire shape (`civictech.agora.NodeDto`), lifted out of
 * `AgoraApp` by computenet-2aw.5.1 so `:demo:dialogue` can serve the
 * identical bytes (2aw.5-D3, [AGO1-OBS-01]).
 *
 * The lift is a **visibility refactor with byte-identical output**, and that
 * is what this file pins — not merely that the new encoder produces *some*
 * plausible JSON. Two independent instruments:
 *
 * 1. [`the lifted encoder is byte-identical to AgoraApp's former private one`]
 *    encodes the same nodes through [ReplicaNodeDto] — a verbatim copy of the
 *    `private data class NodeDto` that lived in `AgoraApp.kt` before the lift
 *    (same eight-field order, same defaults, same `Json` defaults) — and
 *    asserts string equality. A changed field order, a changed default, or a
 *    changed `Json` configuration fails here.
 * 2. The golden-string tests below pin the omissions `demo/agora/ui/src/api/types.ts`
 *    models as optional: `polarity`/`source`/`target` absent on a CLAIM,
 *    `text` absent on an EDGE, `head` absent when false.
 *
 * No existing agora test is modified by the lift; this file is additive.
 */
class NodeDtoTest {

    /**
     * The pre-lift definition, verbatim from `AgoraApp.kt` at
     * e0dc04ac2 — the baseline instrument 1 compares against. Do not
     * "tidy" it to match [NodeDto]: its whole value is being an independent
     * copy of what the wire looked like before.
     */
    @Serializable
    private data class ReplicaNodeDto(
        val ref: String,
        val kind: String,
        val text: String? = null,
        val polarity: Polarity? = null,
        val source: String? = null,
        val target: String? = null,
        val head: Boolean = false,
        val credence: Double,
    )

    private fun replicaGraphJson(nodes: List<AgoraService.Node>): String = Json.encodeToString(
        ListSerializer(ReplicaNodeDto.serializer()),
        nodes.map { node ->
            ReplicaNodeDto(
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

    private fun ref(name: String) = CellRef(UUID.nameUUIDFromBytes(name.toByteArray()))

    private val claimRef = ref("claim-a")
    private val otherClaimRef = ref("claim-b")
    private val edgeRef = ref("edge-a")

    private fun claim(
        ref: CellRef = claimRef,
        text: String = "Proposition 1 holds.",
        head: Boolean = false,
        credence: Double = 0.625,
    ) = AgoraService.Node(
        ref = ref,
        info = AgoraService.NodeInfo(kind = AgoraService.Kind.CLAIM, text = text, head = head),
        credence = credence,
    )

    private fun edge(head: Boolean = false, credence: Double = 0.5) = AgoraService.Node(
        ref = edgeRef,
        info = AgoraService.NodeInfo(
            kind = AgoraService.Kind.EDGE,
            polarity = Polarity.ATTACK,
            source = claimRef,
            target = otherClaimRef,
            head = head,
        ),
        credence = credence,
    )

    /** Every shape the encoder has to cover, in one snapshot. */
    private val nodes = listOf(
        claim(),
        claim(ref = otherClaimRef, text = "Proposition 2 holds.", head = true, credence = 1.0),
        edge(),
        edge(head = true, credence = 0.0),
    )

    @Test
    fun `the lifted encoder is byte-identical to AgoraApp's former private one`() {
        assertEquals(replicaGraphJson(nodes), graphJson(nodes))
    }

    @Test
    fun `a CLAIM omits polarity, source and target, and omits head when false`() {
        assertEquals(
            """[{"ref":"${claimRef.id}","kind":"CLAIM","text":"Proposition 1 holds.","credence":0.625}]""",
            graphJson(listOf(claim())),
        )
    }

    @Test
    fun `a CLAIM carries head only when it is true`() {
        assertEquals(
            """[{"ref":"${claimRef.id}","kind":"CLAIM","text":"Proposition 1 holds.","head":true,"credence":0.625}]""",
            graphJson(listOf(claim(head = true))),
        )
    }

    @Test
    fun `an EDGE omits text and carries polarity, source and target`() {
        assertEquals(
            """[{"ref":"${edgeRef.id}","kind":"EDGE","polarity":"ATTACK",""" +
                """"source":"${claimRef.id}","target":"${otherClaimRef.id}","credence":0.5}]""",
            graphJson(listOf(edge())),
        )
    }

    @Test
    fun `an empty graph encodes as an empty array`() {
        assertEquals("[]", graphJson(emptyList()))
    }
}
