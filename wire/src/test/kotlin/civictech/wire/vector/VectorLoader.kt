package civictech.wire.vector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** `SCHEMA.md` §Manifest, one `vectors[]` entry. */
data class ManifestEntry(val id: String, val category: String, val kind: String, val file: String)

/** `SCHEMA.md` §Manifest, one `pending[]` entry. */
data class PendingEntry(val discriminator: String, val owedBy: String)

/**
 * Reads `wire/corpus/manifest.json` and every vector it lists (`[WIR1-C12]`,
 * read half). A harness discovers vectors through the manifest, never by
 * walking directories (`SCHEMA.md` §Manifest); a file under the corpus the
 * manifest does not list is the generator's concern, not this loader's.
 *
 * Refuses, with a [VectorSchemaException] naming the file: a malformed
 * manifest, a listed file that is missing, a document that violates SCHEMA.md
 * ([VectorDocument.parse]), and a manifest entry whose `id`/`category`/`kind`
 * disagrees with the document it names.
 */
class VectorLoader(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()

    private val manifestFile: Path = this.root.resolve("manifest.json")

    private val manifest: Pair<List<ManifestEntry>, List<PendingEntry>> by lazy { readManifest() }

    private val loaded: List<VectorDocument> by lazy { manifest.first.map(::load) }

    fun entries(): List<ManifestEntry> = manifest.first

    fun pending(): List<PendingEntry> = manifest.second

    fun documents(): List<VectorDocument> = loaded

    private fun refuse(rule: String): Nothing = throw VectorSchemaException("$manifestFile: $rule")

    private fun readManifest(): Pair<List<ManifestEntry>, List<PendingEntry>> {
        if (!Files.isRegularFile(manifestFile)) refuse("manifest not found")
        val obj = try {
            Json.parseToJsonElement(Files.readString(manifestFile)) as? JsonObject
        } catch (e: IllegalArgumentException) {
            refuse("not well-formed JSON: ${e.message}")
        } ?: refuse("the manifest must be a JSON object")

        fun JsonObject.str(key: String, where: String): String {
            val p = this[key] as? JsonPrimitive
            if (p == null || !p.isString) refuse("$where: `$key` must be a string")
            return p.content
        }

        val vectors = (obj["vectors"] as? JsonArray ?: refuse("`vectors` must be an array")).mapIndexed { i, el ->
            val e = el as? JsonObject ?: refuse("vectors[$i] must be an object")
            ManifestEntry(e.str("id", "vectors[$i]"), e.str("category", "vectors[$i]"), e.str("kind", "vectors[$i]"), e.str("file", "vectors[$i]"))
        }
        val duplicates = vectors.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) refuse("duplicate vector id(s) $duplicates")

        val pending = (obj["pending"] as? JsonArray ?: refuse("`pending` must be an array")).mapIndexed { i, el ->
            val e = el as? JsonObject ?: refuse("pending[$i] must be an object")
            PendingEntry(e.str("discriminator", "pending[$i]"), e.str("owedBy", "pending[$i]"))
        }
        return vectors to pending
    }

    private fun load(entry: ManifestEntry): VectorDocument {
        val file = root.resolve(entry.file).normalize()
        if (!file.startsWith(root)) refuse("entry ${entry.id}: file \"${entry.file}\" escapes the corpus root")
        if (!Files.isRegularFile(file)) refuse("entry ${entry.id}: listed file \"${entry.file}\" is missing")
        val doc = VectorDocument.parse(file, root)
        val disagreements = buildList {
            if (doc.id != entry.id) add("id (manifest \"${entry.id}\", document \"${doc.id}\")")
            if (doc.category != entry.category) add("category (manifest \"${entry.category}\", document \"${doc.category}\")")
            if (doc.kind.word != entry.kind) add("kind (manifest \"${entry.kind}\", document \"${doc.kind.word}\")")
        }
        if (disagreements.isNotEmpty()) {
            refuse("entry ${entry.id} disagrees with $file on ${disagreements.joinToString("; ")}")
        }
        return doc
    }

    companion object {
        /**
         * The corpus under the working directory: walks up from it looking for
         * `corpus/manifest.json`, then `wire/corpus/manifest.json`, so the same
         * code runs under Gradle (cwd `wire/`) and from the repository root —
         * mirroring `WireSystemPropertyForwardingTest.testSourceRoot()`.
         */
        fun locate(start: Path = Paths.get("").toAbsolutePath()): VectorLoader = VectorLoader(locateRoot(start))

        fun locateRoot(start: Path = Paths.get("").toAbsolutePath()): Path {
            var dir: Path? = start.toAbsolutePath().normalize()
            while (dir != null) {
                for (candidate in listOf(dir.resolve("corpus"), dir.resolve("wire/corpus"))) {
                    if (Files.isRegularFile(candidate.resolve("manifest.json"))) return candidate
                }
                dir = dir.parent
            }
            throw VectorSchemaException("no corpus/manifest.json or wire/corpus/manifest.json at or above $start")
        }
    }
}
