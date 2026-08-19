package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.util.UUID

/**
 * Everything a signed location announcement commits to ([DSC1-ANN-02]).
 *
 * The identifying half comes from the invocation envelope
 * ([civictech.cell.wire.WireFrame]: [contractId], [methodId], [cellRef],
 * [portName], [args]); the authenticity half is minted by the announcing peer
 * ([mintingPeerId], [counter], [notAfter]). Signing over both together is what
 * stops an announcement being replayed onto a different port, contract or cell
 * than the one its author signed.
 *
 * [notAfter] is **epoch milliseconds**. What a receiver does with it — skew
 * tolerance, clock source, how far ahead an announcement may be minted — is the
 * ingress feature's policy, not this type's; here it is simply eight signed
 * bytes inside the signed region.
 *
 * [args] holds the [civictech.cell.wire.RegistryAnnounce] arguments, whose
 * domain is exactly `CellRef`, `TopologyLink` and `UUID` — see [canonicalBytes],
 * which rejects anything else rather than encoding it. [portName] and
 * [mintingPeerId]'s name must likewise be well-formed UTF-16: [canonicalBytes]
 * refuses an unpaired surrogate rather than signing bytes that also describe a
 * different announcement (`computenet-9qgg`). Nothing enforces either domain at
 * construction — this type is a plain record, and the encoder is the gate.
 *
 * `java.io.Serializable` so the whole input survives a round trip unchanged;
 * every component type ([PeerId], [CellRef], [TopologyLink], [PortRef], [UUID])
 * already is. BS-17 asserts that a round trip does not perturb the bytes.
 */
data class AnnouncementSigningInput(
    val mintingPeerId: PeerId,
    val counter: Long,
    val notAfter: Long,
    val contractId: Long,
    val methodId: Long,
    val cellRef: CellRef,
    val portName: String,
    val args: List<Any?>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Type tags for the [AnnouncementSigningInput.args] elements. One byte each,
 * written before the element body so the reader never has to infer a shape from
 * the bytes that follow.
 *
 * The values are part of the wire-visible encoding pinned by the golden vector:
 * **never renumber them**, and give a new supported argument type a new value
 * rather than reusing a retired one.
 */
private const val TAG_CELL_REF: Byte = 0x01
private const val TAG_TOPOLOGY_LINK: Byte = 0x02
private const val TAG_UUID: Byte = 0x03

/** Presence markers for the nullable [PortRef.cell]. */
private const val ABSENT: Byte = 0x00
private const val PRESENT: Byte = 0x01

/**
 * The canonical bytes an announcement is signed over ([DSC1-ANN-02..03]).
 *
 * Pure, and **injective over its accepted domain**: distinct accepted inputs
 * never produce equal bytes. It is deliberately *not* total — an input outside
 * the domain is **rejected**, never encoded approximately. Two things are
 * outside it: an argument that is not `CellRef`/`TopologyLink`/`UUID` (see
 * `@throws`), and a string that is not well-formed UTF-16 (the surrogate rule
 * below). Injectivity is the security property, not a nicety — two announcements
 * sharing an encoding would share a signature, so a signature minted for one
 * would verify the other. It is obtained by construction rather than by testing:
 *
 * - **Fixed field order and fixed widths.** Fields appear in
 *   [AnnouncementSigningInput]'s constructor order; every `Long` is eight bytes
 *   big-endian, every `UUID` is sixteen (most-significant then
 *   least-significant bits). Nothing is omitted when it is zero, empty or
 *   default.
 * - **Length prefixes on everything variable.** Strings are UTF-8 behind a
 *   four-byte big-endian byte count; the argument list is behind a four-byte
 *   big-endian element count. So no two distinct field sequences can
 *   concatenate to the same byte string — the classic `"ab"+"c"` vs `"a"+"bc"`
 *   confusion cannot arise.
 * - **Strings must be well-formed UTF-16**, below: the one place where framing
 *   cannot buy injectivity, because the loss happens *inside* the string's own
 *   bytes rather than between fields.
 * - **A type tag per argument**, so a `CellRef` and a `UUID` sharing sixteen
 *   leading bytes are still distinguishable.
 * - **An explicit presence marker for the nullable [PortRef.cell]** rather than
 *   omitting an absent cell. Omission would let a cell-less `PortRef` followed
 *   by the next field collide with a `PortRef` whose cell is whatever those
 *   bytes happen to decode to.
 * - **A closed argument domain**, below.
 *
 * Because the grammar is prefix-free and self-delimiting at every position, the
 * byte string can be parsed back to exactly one input — which is injectivity.
 *
 * **Unpaired surrogates are rejected, not encoded** (`computenet-9qgg`).
 * `String.toByteArray(UTF_8)` substitutes `?` (`0x3f`) for an unpaired
 * surrogate, so before this rule `portName = "\uD800"`, `"\uDC00"` and `"?"` all
 * encoded to the identical byte string — measured against this function, not a
 * theoretical worry — and the same collision reproduced through
 * [PeerId.name]. Framing cannot repair it: the loss is inside `String` → UTF-8,
 * below the length prefix. So a string carrying an unpaired surrogate is outside
 * the domain and this function throws (see `@throws`) rather than signing bytes
 * that also describe a different announcement.
 *
 * Why *reject* rather than encode UTF-16 code units, which would be lossless for
 * every `String` (the decision recorded on `computenet-9qgg`):
 *
 * - **It leaves the bytes of every accepted input unchanged.** Rejection narrows
 *   the domain without redefining the grammar, so no signature ever minted
 *   under this encoding is invalidated and the golden vector stays pinned.
 *   Switching to UTF-16 code units would change the bytes of *every* string and
 *   so of every announcement.
 * - **UTF-8 is the interoperable canonical form.** UTF-16 code units are a JVM
 *   representation detail; baking them into a cross-implementation signed
 *   grammar would oblige a non-JVM verifier to model Java's `String`.
 * - **An ill-formed port name is not a thing to sign.** A `portName` names a
 *   declared `@Contract` port and a [PeerId] name is `ed25519:<base64url>`;
 *   neither can contain an unpaired surrogate. Encoding one losslessly would
 *   make a nonsense announcement *validly signed* and push the rejection out to
 *   every consumer, instead of refusing it once, here.
 *
 * "Accept and document as unreachable" was the third option and is **falsified**:
 * `WirePortNameSurrogateReachabilityTest` measures the kernel wire codec
 * (kotlinx JSON) decoding a JSON-escaped lone `\ud800` straight into
 * `WireFrame.portName`, so once feature `computenet-ssa.4` feeds this encoder
 * from a wire-supplied port name the string is remote input and the collision is
 * reachable from the network.
 *
 * The seeded property test `AnnouncementCanonicalBytesPropertyTest` (BS-17)
 * probes injectivity empirically over the accepted domain and rejection over
 * generated surrogate-bearing strings; `AnnouncementCanonicalBytesSurrogateTest`
 * pins the three formerly-colliding values; and
 * `AnnouncementCanonicalBytesGoldenVectorTest` pins the exact bytes so a later
 * refactor that changes them fails loudly instead of silently invalidating every
 * signature already in the field.
 *
 * @throws IllegalArgumentException if any element of
 *   [AnnouncementSigningInput.args] is outside the `RegistryAnnounce` argument
 *   domain — `CellRef`, `TopologyLink`, `UUID` — including `null`. **Fail
 *   closed**: a best-effort fallback (`toString`, Java serialization) is
 *   forbidden here, because neither is injective — `toString` collides for
 *   distinct values of different types, and Java serialization varies with
 *   class metadata that has nothing to do with the announcement's meaning.
 * @throws IllegalArgumentException if [AnnouncementSigningInput.portName] or
 *   [AnnouncementSigningInput.mintingPeerId]'s name is not well-formed UTF-16 —
 *   i.e. contains a surrogate code unit that is not part of a high/low pair. The
 *   message names the field and the offending index. Fail closed for the same
 *   reason as above: the alternative is signing bytes that describe more than
 *   one announcement.
 */
fun canonicalBytes(input: AnnouncementSigningInput): ByteArray {
    val out = ByteArrayOutputStream(256)
    out.writeString("mintingPeerId.name", input.mintingPeerId.name)
    out.writeLong(input.counter)
    out.writeLong(input.notAfter)
    out.writeLong(input.contractId)
    out.writeLong(input.methodId)
    out.writeCellRef(input.cellRef)
    out.writeString("portName", input.portName)
    out.writeInt(input.args.size)
    input.args.forEachIndexed { index, arg -> out.writeArg(index, arg) }
    return out.toByteArray()
}

private fun ByteArrayOutputStream.writeArg(index: Int, arg: Any?) {
    when (arg) {
        is CellRef -> {
            write(TAG_CELL_REF.toInt())
            writeCellRef(arg)
        }

        is TopologyLink -> {
            write(TAG_TOPOLOGY_LINK.toInt())
            writeUuid(arg.id)
            writePortRef(arg.from)
            writePortRef(arg.to)
        }

        is UUID -> {
            write(TAG_UUID.toInt())
            writeUuid(arg)
        }

        else -> throw IllegalArgumentException(
            "announcement argument $index is outside the signable domain " +
                "(CellRef, TopologyLink, UUID): ${arg?.javaClass?.name ?: "null"}",
        )
    }
}

private fun ByteArrayOutputStream.writeCellRef(ref: CellRef) {
    writeUuid(ref.id)
    writeLong(ref.instanceId)
}

/**
 * A [PortRef] is its id plus a one-byte presence marker for the owning cell —
 * `null` for free-standing endpoints such as `Use.fixed` is a real value here,
 * not an omission (see [canonicalBytes]).
 */
private fun ByteArrayOutputStream.writePortRef(port: PortRef) {
    writeUuid(port.id)
    val cell = port.cell
    if (cell == null) {
        write(ABSENT.toInt())
    } else {
        write(PRESENT.toInt())
        writeCellRef(cell)
    }
}

private fun ByteArrayOutputStream.writeUuid(value: UUID) {
    writeLong(value.mostSignificantBits)
    writeLong(value.leastSignificantBits)
}

/**
 * A string is its UTF-8 bytes behind a four-byte big-endian byte count — after
 * [requireWellFormedUtf16] has refused anything UTF-8 cannot represent
 * faithfully. [field] names the offending field in that refusal; it is not
 * encoded, so naming it costs nothing on the wire.
 */
private fun ByteArrayOutputStream.writeString(field: String, value: String) {
    requireWellFormedUtf16(field, value)
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes, 0, bytes.size)
}

/**
 * Refuse a [String] holding a surrogate code unit that is not part of a
 * high-then-low pair (see [canonicalBytes], `computenet-9qgg`).
 *
 * This is a scan over code *units*, deliberately — not `String.codePoints()` or
 * a re-encode/compare, both of which silently substitute `U+FFFD`/`?` for the
 * very code unit being looked for and so would report every string well-formed.
 */
private fun requireWellFormedUtf16(field: String, value: String) {
    var index = 0
    while (index < value.length) {
        val unit = value[index]
        if (unit.isHighSurrogate()) {
            require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                unpairedSurrogate(field, value, index, "high")
            }
            index += 2
        } else {
            require(!unit.isLowSurrogate()) { unpairedSurrogate(field, value, index, "low") }
            index++
        }
    }
}

private fun unpairedSurrogate(field: String, value: String, index: Int, half: String): String =
    "$field is not well-formed UTF-16: unpaired $half surrogate " +
        "U+%04X at index %d (length %d)".format(value[index].code, index, value.length) +
        " — the canonical announcement encoding refuses it rather than substituting '?' " +
        "(that substitution collides distinct announcements; computenet-9qgg)"

private fun ByteArrayOutputStream.writeLong(value: Long) {
    for (shift in 56 downTo 0 step 8) write(((value ushr shift) and 0xFF).toInt())
}

private fun ByteArrayOutputStream.writeInt(value: Int) {
    for (shift in 24 downTo 0 step 8) write((value ushr shift) and 0xFF)
}
