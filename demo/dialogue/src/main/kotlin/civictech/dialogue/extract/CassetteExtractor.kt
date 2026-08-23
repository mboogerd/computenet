package civictech.dialogue.extract

import civictech.dialogue.Segment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.Reader

/**
 * Thrown by [CassetteExtractor] when [segment]'s content hash has no
 * recorded entry — `[AGO1-EXTR-08]`/BS-15's "fail loudly" at the extractor
 * level. A cassette miss is never an empty extraction: the sibling
 * `ExtractionCell` task turns this throw into a recorded per-segment
 * failure.
 */
class CassetteMissException(
    val segmentId: String,
    val contentHash: String,
) : RuntimeException(
    "No cassette entry for segment '$segmentId' (content hash $contentHash)",
)

/**
 * The [Json], and the wire shape of a cassette file, kept visible outside
 * this file (2aw.F2-D1's "the only extractor gating tests may use") so the
 * sibling `LlmExtractor`/recorder task can reuse the exact same
 * serialization without editing this file.
 *
 * A cassette file is a JSON object mapping [segmentContentHash] (hex
 * string) to a JSON array of [ExtractedItem]. The array is serialized with
 * kotlinx's default class-discriminator polymorphism so the three
 * `ExtractedItem` subtypes round-trip.
 */
internal val cassetteJson = Json { prettyPrint = true }

internal val cassetteItemListSerializer = ListSerializer(ExtractedItem.serializer())

internal val cassetteEntriesSerializer = MapSerializer(String.serializer(), cassetteItemListSerializer)

/**
 * The determinism firewall's recorded-results [Extractor] (epic
 * computenet-2aw DESIGN 2aw-D4, 2aw.F2-D1): the ONLY extractor gating tests
 * may use. A lookup hit returns the recorded item list verbatim — extraction
 * is a pure function of segment content ([segmentContentHash]), so cassette
 * items are returned exactly as recorded, including their own
 * `utteranceId`. A lookup miss throws [CassetteMissException] rather than
 * returning an empty list.
 *
 * Loading is pure: no clock, no network — just parsing the checked-in
 * fixture.
 */
class CassetteExtractor internal constructor(
    private val entries: Map<String, List<ExtractedItem>>,
) : Extractor {

    override fun extract(segment: Segment): List<ExtractedItem> {
        val contentHash = segmentContentHash(segment)
        return entries[contentHash] ?: throw CassetteMissException(segment.id, contentHash)
    }

    companion object {
        /** Loads a cassette from an already-open [Reader]. Does not close it. */
        fun load(reader: Reader): CassetteExtractor {
            val text = reader.readText()
            val entries = cassetteJson.decodeFromString(cassetteEntriesSerializer, text)
            return CassetteExtractor(entries)
        }

        /** Loads a cassette from a [File], opening and closing its own reader. */
        fun load(file: File): CassetteExtractor = file.reader(Charsets.UTF_8).use { load(it) }
    }
}
