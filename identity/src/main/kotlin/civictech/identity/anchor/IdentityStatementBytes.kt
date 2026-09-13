package civictech.identity.anchor

import civictech.cell.link.IdentityStatement
import civictech.identity.announce.writeLong
import civictech.identity.announce.writeString
import java.io.ByteArrayOutputStream

/**
 * The domain tag for [statementSigningBytes] (epic `computenet-5y8t`, decision
 * D3/D4; feature `computenet-5y8t.2`, task `computenet-5y8t.2.1`).
 *
 * **Version by tag.** A change to the layout below — field order, width, or
 * which fields are signed — is a **new tag**, `computenet/DSC4/identity-binding/v2`;
 * it is never a repurposing of `v1` for a different grammar, because a
 * verifier that has pinned this string as "the DSC4 identity-binding bytes"
 * must be able to trust that a statement carrying it was signed under
 * exactly this grammar, forever. The precedent is
 * `civictech.wire.HelloProtocol.CHALLENGE_DOMAIN_TAG`.
 */
const val IDENTITY_BINDING_DOMAIN_TAG: String = "computenet/DSC4/identity-binding/v1"

/**
 * The **one definition**, in `:identity`, of the bytes an [IdentityStatement]
 * is signed over (feature `computenet-5y8t.2`, task `computenet-5y8t.2.1`,
 * epic `computenet-5y8t` decision D1/D4).
 *
 * The kernel's `IdentityStatement` KDoc says the signed bytes "have one
 * definition, in `:identity`" and carries no cryptography itself — this
 * function is that definition. It exists so an [AnchorIssuer] mints a
 * signature over exactly these bytes and an `AnchorVouchedBinding` verifies
 * against exactly these bytes; neither invents its own encoding.
 *
 * **Grammar, in this fixed order — nothing omitted:**
 *
 * 1. [IDENTITY_BINDING_DOMAIN_TAG], length-prefixed UTF-8 (a 4-byte
 *    big-endian byte count, then the bytes) — a signature over these bytes
 *    can never be replayed as a signature over some other tagged grammar,
 *    because the tag is inside the signed region, not appended out of band.
 * 2. [IdentityStatement.name]`.name`, length-prefixed UTF-8.
 * 3. [IdentityStatement.keyId]`.name`, length-prefixed UTF-8.
 * 4. [IdentityStatement.issuer]`.name`, length-prefixed UTF-8.
 * 5. [IdentityStatement.issuance], 8 bytes big-endian two's complement.
 * 6. [IdentityStatement.notBefore], 8 bytes big-endian two's complement.
 * 7. [IdentityStatement.notAfter], 8 bytes big-endian two's complement.
 *
 * [IdentityStatement.signature] is **never written** — it is what is
 * computed *over* these bytes, not part of them. Two [IdentityStatement]s
 * differing only in `signature` therefore encode to the SAME bytes. That is
 * not a defect: injectivity here is a claim over the tuple `(name, keyId,
 * issuer, issuance, notBefore, notAfter)`, and `signature` is deliberately
 * outside it.
 *
 * **Injective by construction** over that six-field tuple, by the same
 * discipline as `civictech.identity.announce.canonicalBytes`
 * (`[DSC1-ANN-02..03]`, `computenet-9qgg`): fixed field order, fixed-width
 * 8-byte big-endian longs, a 4-byte big-endian length prefix on every
 * variable-length field. Because every field is either fixed-width or
 * self-delimiting via its own length prefix, no two distinct tuples can
 * concatenate to the same byte string, and the grammar can be parsed back to
 * exactly one tuple.
 *
 * **Unpaired UTF-16 surrogates are rejected, not encoded**, exactly the
 * announcement rule (`computenet-9qgg`): each of [IdentityStatement.name],
 * [IdentityStatement.keyId] and [IdentityStatement.issuer] goes through the
 * same well-formedness check the announcement encoder uses before any bytes
 * are written for it, so an ill-formed string is outside the domain and this
 * function throws rather than silently substituting `?` and letting two
 * distinct statements collide on one signature.
 *
 * This function is **pure**: no I/O, no clock, no randomness. It reuses
 * `civictech.identity.announce`'s `writeString`/`writeLong`/`writeInt` and
 * surrogate check rather than duplicating them — one length-prefixing and
 * one surrogate-refusal implementation in `:identity`, not two.
 *
 * @throws IllegalArgumentException if [IdentityStatement.name]`.name`,
 *   [IdentityStatement.keyId]`.name` or [IdentityStatement.issuer]`.name` is
 *   not well-formed UTF-16 — i.e. contains a surrogate code unit that is not
 *   part of a high/low pair. The message names the field (`"name"`,
 *   `"keyId"` or `"issuer"`) and the offending index, and fails closed for
 *   the same reason `canonicalBytes` does: the alternative is signing bytes
 *   that also describe a different statement.
 */
fun statementSigningBytes(statement: IdentityStatement): ByteArray {
    val out = ByteArrayOutputStream(160)
    out.writeString("tag", IDENTITY_BINDING_DOMAIN_TAG)
    out.writeString("name", statement.name.name)
    out.writeString("keyId", statement.keyId.name)
    out.writeString("issuer", statement.issuer.name)
    out.writeLong(statement.issuance)
    out.writeLong(statement.notBefore)
    out.writeLong(statement.notAfter)
    return out.toByteArray()
}
