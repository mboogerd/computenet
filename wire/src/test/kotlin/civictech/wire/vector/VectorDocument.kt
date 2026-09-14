package civictech.wire.vector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * A document in `wire/corpus/` refused by [VectorDocument.parse] or
 * [VectorLoader]: the message always names the file and the violated rule of
 * `wire/corpus/SCHEMA.md`.
 */
class VectorSchemaException(message: String) : IllegalArgumentException(message)

/** `SCHEMA.md` §Kinds — the closed set of vector kinds. */
enum class VectorKind(val word: String) {
    FRAME("frame"),
    HANDSHAKE_TEXT("handshake-text"),
    NEGATIVE("negative"),
    ;

    val positive: Boolean get() = this != NEGATIVE

    companion object {
        fun of(word: String): VectorKind? = entries.firstOrNull { it.word == word }
    }
}

/** `SCHEMA.md` §Kinds — which conversion a vector asserts. */
enum class VectorDirection(val word: String) {
    BOTH("both"),
    DECODE("decode"),
    ENCODE("encode"),
    ;

    companion object {
        fun of(word: String): VectorDirection? = entries.firstOrNull { it.word == word }
    }
}

/**
 * `SCHEMA.md` §The `encoded` block. [bytes] is the base64-decoded content;
 * [utf8] is absent only on an `invalid-utf8` negative.
 */
class EncodedBlock(val utf8: String?, val base64: String, val bytes: ByteArray)

/**
 * One wire test vector, every field of `SCHEMA.md` §Document fields, parsed
 * by [parse] which enforces the schema. [decoded] stays a [JsonElement]: the
 * neutral grammar is generic by design, and [NeutralValues] is what turns it
 * into a codec value. [source] is the document exactly as parsed (key order
 * and numeric spelling intact), for tools that rewrite a document.
 */
class VectorDocument(
    val file: Path,
    val source: JsonObject,
    val id: String,
    val title: String,
    val category: String,
    val kind: VectorKind,
    val covers: List<String>,
    val codecVersion: Int,
    val notes: String,
    val decoded: JsonElement?,
    val encoded: EncodedBlock?,
    val reject: String?,
    val direction: VectorDirection,
    val messageKind: String?,
    val deprecated: String?,
) {
    override fun toString(): String = "VectorDocument($id, $kind, $file)"

    companion object {
        /** `SCHEMA.md` §Document fields: "No other top-level key is allowed". */
        val TOP_LEVEL_KEYS: Set<String> = setOf(
            "id", "title", "category", "kind", "covers", "codecVersion", "notes",
            "decoded", "encoded", "expect", "direction", "messageKind", "deprecated",
        )

        /** `SCHEMA.md` §Ids and categories, the id pattern for a lint. */
        val ID_PATTERN: Regex =
            Regex("^WV-(PORT-API|PORT-MGMT|PORT-PROTOCOL|ADDITIVE|PAYLOAD|INTEREST|HELLO|NEG)(-[A-Z0-9]+)+-[0-9]{2,}$")

        /** `SCHEMA.md` §Rejection vocabulary — the closed set of ten. */
        val REJECTIONS: Set<String> = setOf(
            "malformed", "truncated", "invalid-utf8", "unknown-frame-type", "unknown-discriminator",
            "missing-required-field", "unknown-ids", "unsupported-version", "unknown-envelope-field",
            "leased-at-encode",
        )

        /** Parses [file] (which must lie under [corpusRoot]) and enforces SCHEMA.md. */
        fun parse(file: Path, corpusRoot: Path): VectorDocument {
            val text = try {
                Files.readString(file)
            } catch (e: java.io.IOException) {
                throw VectorSchemaException("$file: cannot read vector document: $e")
            }
            val element = try {
                Json.parseToJsonElement(text)
            } catch (e: IllegalArgumentException) {
                throw VectorSchemaException("$file: not well-formed JSON: ${e.message}")
            }
            val obj = element as? JsonObject
                ?: throw VectorSchemaException("$file: a vector document must be a JSON object")
            return parse(obj, file, corpusRoot)
        }

        /**
         * `[a-numeric literal RFC 8259 §6 permits]`: an optional `-`, then `0` or a
         * non-zero digit followed by more digits (no leading zero), an optional
         * `.` fraction of one-or-more digits, and an optional `e`/`E` exponent
         * with an optional sign and one-or-more digits. Anything else that
         * [Json.parseToJsonElement] accepted as an unquoted, non-boolean,
         * non-null literal (`01`, `.5`, `1.`, `NaN`, a bare word) is not valid
         * JSON and this parser must not be more permissive than SCHEMA.md's
         * "implementation-neutral" corpus requires.
         */
        private val RFC8259_NUMBER: Regex = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

        /**
         * Walks every primitive under [element], refusing a non-string
         * [JsonPrimitive] whose content is not `true`, `false`, `null`, or an
         * [RFC8259_NUMBER]. `Json.parseToJsonElement`'s default parser is
         * lenient and accepts unquoted literals RFC 8259 does not (`01`, `.5`,
         * `1.`, `NaN`, `abc`) — see the class doc and this task's bead
         * (computenet-v7s6t). [path] is a `$`-rooted JSON-path-like locator
         * reported in the refusal.
         */
        private fun requireRfc8259Literals(element: JsonElement, path: String, file: Path) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> requireRfc8259Literals(value, "$path.$key", file) }
                is JsonArray -> element.forEachIndexed { i, value -> requireRfc8259Literals(value, "$path[$i]", file) }
                is JsonPrimitive -> {
                    if (element.isString) return
                    val content = element.content
                    if (content != "true" && content != "false" && content != "null" && !RFC8259_NUMBER.matches(content)) {
                        throw VectorSchemaException(
                            "$file: $path: literal \"$content\" is not RFC 8259 JSON (must be true, false, null, or a " +
                                "JSON number matching ${RFC8259_NUMBER.pattern}) — kotlinx's lenient parser accepted it " +
                                "but a non-JVM driver reading this corpus would refuse it (SCHEMA.md's implementation " +
                                "neutrality, computenet-v7s6t)",
                        )
                    }
                }
            }
        }

        /**
         * Enforces SCHEMA.md on an already-parsed [obj] as though it were the
         * content of [file]. Parse numbers only through [Json.parseToJsonElement]
         * so 64-bit literals keep their spelling.
         */
        fun parse(obj: JsonObject, file: Path, corpusRoot: Path): VectorDocument {
            fun refuse(rule: String): Nothing = throw VectorSchemaException("$file: $rule")

            requireRfc8259Literals(obj, "$", file)

            val unknown = obj.keys - TOP_LEVEL_KEYS
            if (unknown.isNotEmpty()) {
                refuse(
                    "top-level key(s) $unknown not allowed (SCHEMA.md §Document fields: " +
                        "\"No other top-level key is allowed\")",
                )
            }

            fun string(key: String): String? {
                val v = obj[key] ?: return null
                val p = v as? JsonPrimitive
                if (p == null || !p.isString) refuse("field `$key` must be a JSON string")
                return p.content
            }
            fun requiredString(key: String): String =
                string(key) ?: refuse("required field `$key` is missing (SCHEMA.md §Document fields)")

            // --- identity ------------------------------------------------------
            val id = requiredString("id")
            if (!ID_PATTERN.matches(id)) refuse("`id` \"$id\" does not match ${ID_PATTERN.pattern} (SCHEMA.md §Ids)")
            val basename = file.fileName.toString()
            if (basename != "$id.json") {
                refuse("`id` \"$id\" must equal the file's basename without .json (basename is \"$basename\")")
            }
            val category = requiredString("category")
            val parent = file.toAbsolutePath().normalize().parent
            val root = corpusRoot.toAbsolutePath().normalize()
            val actualCategory = if (parent != null && parent.startsWith(root)) {
                root.relativize(parent).joinToString("/")
            } else {
                refuse("file is not under the corpus root $root")
            }
            if (category != actualCategory) {
                refuse("`category` \"$category\" must equal the file's directory relative to the corpus root (\"$actualCategory\")")
            }

            val title = requiredString("title")
            val notes = requiredString("notes")
            val deprecated = string("deprecated")

            val kindWord = requiredString("kind")
            val kind = VectorKind.of(kindWord)
                ?: refuse("`kind` \"$kindWord\" is not one of ${VectorKind.entries.map { it.word }} (SCHEMA.md §Kinds)")

            // --- covers / codecVersion ----------------------------------------
            val coversEl = obj["covers"] ?: refuse("required field `covers` is missing (SCHEMA.md §Document fields)")
            val covers = (coversEl as? JsonArray)?.map { el ->
                val p = el as? JsonPrimitive
                if (p == null || !p.isString) refuse("`covers` must be an array of strings")
                p.content
            } ?: refuse("`covers` must be an array of strings")
            if (covers.isEmpty()) refuse("`covers` must be non-empty")
            if (covers.none { it.startsWith("WIR1-") }) refuse("`covers` must carry at least one WIR1-* id")

            val cvEl = obj["codecVersion"] ?: refuse("required field `codecVersion` is missing (SCHEMA.md §Document fields)")
            val cvPrim = cvEl as? JsonPrimitive
            val codecVersion = if (cvPrim != null && !cvPrim.isString && Regex("^-?[0-9]+$").matches(cvPrim.content)) {
                cvPrim.content.toIntOrNull() ?: refuse("`codecVersion` ${cvPrim.content} is out of range")
            } else {
                refuse("`codecVersion` must be a JSON integer")
            }

            // --- per-kind presence table (SCHEMA.md §Kinds) -----------------------
            val decoded = obj["decoded"]
            val encodedEl = obj["encoded"]
            val expectEl = obj["expect"]
            val directionWord = string("direction")

            val direction: VectorDirection
            val reject: String?
            if (kind.positive) {
                if (decoded == null) refuse("kind `${kind.word}` requires `decoded` (SCHEMA.md §Kinds)")
                if (encodedEl == null) refuse("kind `${kind.word}` requires `encoded` (SCHEMA.md §Kinds)")
                if (expectEl != null) refuse("`expect` is allowed on kind `negative` only (SCHEMA.md §Document fields)")
                direction = when (directionWord) {
                    null -> VectorDirection.BOTH
                    "both" -> VectorDirection.BOTH
                    "decode" -> VectorDirection.DECODE
                    else -> refuse(
                        "`direction` \"$directionWord\" is not allowed on positive kind `${kind.word}`: " +
                            "only both (default) | decode (SCHEMA.md §Kinds, \"encode is not a value for a positive kind\")",
                    )
                }
                reject = null
            } else {
                if (directionWord == null) {
                    refuse("kind `negative` requires `direction` (decode | encode) (SCHEMA.md §Kinds)")
                }
                direction = when (directionWord) {
                    "decode" -> VectorDirection.DECODE
                    "encode" -> VectorDirection.ENCODE
                    else -> refuse("`direction` \"$directionWord\" is not decode | encode on kind `negative` (SCHEMA.md §Kinds)")
                }
                when (direction) {
                    VectorDirection.DECODE -> {
                        if (encodedEl == null || decoded != null) {
                            refuse("a negative `direction: decode` vector carries `encoded` and no `decoded` (SCHEMA.md §Kinds)")
                        }
                    }
                    else -> {
                        if (decoded == null || encodedEl != null) {
                            refuse("a negative `direction: encode` vector carries `decoded` and no `encoded` (SCHEMA.md §Kinds)")
                        }
                    }
                }
                val expect = expectEl as? JsonObject
                    ?: refuse("kind `negative` requires `expect` as {\"reject\": <classification>} (SCHEMA.md §Document fields)")
                if (expect.keys != setOf("reject")) refuse("`expect` must carry exactly the key `reject`, found ${expect.keys}")
                val r = (expect["reject"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: refuse("`expect.reject` must be a string")
                if (r !in REJECTIONS) refuse("`expect.reject` \"$r\" is not in the rejection vocabulary $REJECTIONS")
                reject = r
            }

            // --- encoded block / messageKind ----------------------------------
            val messageKind = string("messageKind")
            val encoded = encodedEl?.let { el ->
                val block = el as? JsonObject ?: refuse("`encoded` must be an object {utf8, base64}")
                val extra = block.keys - setOf("utf8", "base64")
                if (extra.isNotEmpty()) refuse("`encoded` carries key(s) $extra outside {utf8, base64}")
                fun blockString(key: String): String? = block[key]?.let {
                    val p = it as? JsonPrimitive
                    if (p == null || !p.isString) refuse("`encoded.$key` must be a JSON string")
                    p.content
                }
                val base64 = blockString("base64") ?: refuse("`encoded.base64` is required (SCHEMA.md §The encoded block)")
                val utf8 = blockString("utf8")
                if (utf8 == null && kind.positive) {
                    refuse("`encoded.utf8` is required on positive kind `${kind.word}` — it is absent only for invalid-UTF-8 bytes")
                }
                val bytes = try {
                    Base64.getDecoder().decode(base64)
                } catch (e: IllegalArgumentException) {
                    refuse("`encoded.base64` is not standard base64: ${e.message}")
                }
                if (Base64.getEncoder().encodeToString(bytes) != base64) {
                    refuse("`encoded.base64` is not canonical standard base64 with = padding (SCHEMA.md §The encoded block)")
                }
                if (utf8 != null && !bytes.contentEquals(utf8.toByteArray(Charsets.UTF_8))) {
                    refuse(
                        "`encoded.base64` does not decode to exactly the UTF-8 bytes of `encoded.utf8` " +
                            "(SCHEMA.md §The encoded block: \"a document where the two disagree is invalid\")",
                    )
                }
                EncodedBlock(utf8, base64, bytes)
            }
            if (encoded != null) {
                if (messageKind == null) refuse("`messageKind` is required whenever `encoded` is present (SCHEMA.md §Document fields)")
                val expected = if (kind == VectorKind.HANDSHAKE_TEXT) "text" else "binary"
                if (messageKind != expected) {
                    refuse("`messageKind` \"$messageKind\" must be \"$expected\" for kind `${kind.word}` (SCHEMA.md §Kinds)")
                }
            } else if (messageKind != null && messageKind != "binary" && messageKind != "text") {
                refuse("`messageKind` \"$messageKind\" is not binary | text")
            }

            return VectorDocument(
                file = file,
                source = obj,
                id = id,
                title = title,
                category = category,
                kind = kind,
                covers = covers,
                codecVersion = codecVersion,
                notes = notes,
                decoded = decoded,
                encoded = encoded,
                reject = reject,
                direction = direction,
                messageKind = messageKind,
                deprecated = deprecated,
            )
        }
    }
}
