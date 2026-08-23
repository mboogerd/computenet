package civictech.dialogue.extract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An item derived from a single [civictech.dialogue.Segment] by an
 * [Extractor] (epic computenet-2aw §2.2 stage 3, §3.2, DESIGN 2aw-D4).
 *
 * `@Serializable` on the hierarchy and every case is load-bearing: this is
 * the cassette's value type (see [CassetteExtractor]), so `ExtractedItem`
 * instances round-trip through kotlinx JSON with a class discriminator
 * (kotlinx's default `"type"` key) that distinguishes the three subtypes.
 * Each subtype carries an explicit `@SerialName` so that discriminator value
 * is a short, stable string (`"claim"`/`"relation"`/`"stance"`) rather than
 * this class's fully-qualified name — load-bearing for the hand-written
 * cassette fixture in `src/test/resources/cassette/basic.json`.
 *
 * Validating a well-formed item (blank claim text, unparseable polarity, a
 * stance outside `[0,1]`) is explicitly not this hierarchy's job —
 * `[AGO1-EXTR-05]` is a pipeline-level (`ExtractionCell`) obligation,
 * satisfied by the sibling cell task. An `ExtractedItem` may therefore be
 * malformed data in transit; it is still representable and still round-trips.
 */
@Serializable
sealed class ExtractedItem

/**
 * A claim distilled from one segment, before minting (F3) merges it with
 * others that share a canonical key.
 */
@Serializable
@SerialName("claim")
data class ExtractedClaim(
    val text: String,
    val speaker: String,
    val utteranceId: String,
) : ExtractedItem()

/**
 * A relation (attack/support) between two claims named by their raw
 * (pre-minting) text, as extracted from one segment.
 *
 * `polarity` is deliberately a raw [String], not
 * `civictech.agora.cell.Polarity`: `[AGO1-EXTR-05]` requires "a relation
 * naming an unparseable polarity" to be representable as *data* so the
 * pipeline can reject it with a reason — a typed field would turn that case
 * into a deserialization failure instead, which is a different, earlier
 * failure mode than the one the requirement describes. Parsing/validating
 * `polarity` is the sibling cell task's job, not this one's.
 */
@Serializable
@SerialName("relation")
data class ExtractedRelation(
    val sourceText: String,
    val targetText: String,
    val polarity: String,
    val utteranceId: String,
) : ExtractedItem()

/**
 * A speaker's stance on a claim (named by raw, pre-minting text), as
 * extracted from one segment. `value` is not clamped to `[0,1]` here for the
 * same reason `polarity` is a raw string above — an out-of-range value must
 * be representable so `[AGO1-EXTR-05]` can reject it with a reason rather
 * than fail earlier at deserialization.
 */
@Serializable
@SerialName("stance")
data class ExtractedStance(
    val claimText: String,
    val speaker: String,
    val value: Double,
    val utteranceId: String,
) : ExtractedItem()
