package civictech.identity.anchor

import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/*
 * The one-token hello encoding of an IdentityStatement (DSC4, epic
 * computenet-5y8t decision D1; feature computenet-5y8t.3 decision F3-D3; task
 * computenet-5y8t.3.1).
 *
 * A token is the unpadded base64url of statementSigningBytes(statement)
 * followed immediately by statement.signature. It is the DSC4
 * HELLO-PIGGYBACK delivery and nothing else: the presenting peer puts its own
 * statement(s) on its hello line and the relying side decodes and verifies
 * offline. There is no cache, no gossip and no lookup behind it.
 *
 * The token pins the v1 tag: the first field of the decoded bytes must equal
 * IDENTITY_BINDING_DOMAIN_TAG ("computenet/DSC4/identity-binding/v1"). A
 * statement under any other tag — a future /v2 layout — decodes to null here,
 * loudly, rather than being misread under the v1 grammar.
 *
 * Space-free by construction (the base64url alphabet is [A-Za-z0-9_-]), so a
 * strict split(" ") on a hello line stays unambiguous.
 *
 * The codec does not verify anything: a decoded statement is only a
 * well-formed one. Whether it vouches for anyone is AnchorVouchedBinding's
 * decision, and [DSC1-NV-01] stays EXPLICITLY UNVERIFIED.
 */

private val TOKEN_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val TOKEN_ALPHABET = Regex("^[A-Za-z0-9_-]*$")

/** Three 8-byte big-endian longs: issuance, notBefore, notAfter. */
private const val LONGS_BYTES = 24

/**
 * Encode [statement] as one space-free hello token: unpadded base64url of
 * [statementSigningBytes] followed by [IdentityStatement.signature].
 *
 * @throws IllegalArgumentException exactly when [statementSigningBytes] does
 *   (a name, keyId or issuer that is not well-formed UTF-16), and when
 *   [IdentityStatement.signature] is empty — an unsigned statement is not
 *   presentable.
 */
fun encodeIdentityStatementToken(statement: IdentityStatement): String {
    require(statement.signature.isNotEmpty()) {
        "an IdentityStatement with an empty signature is not presentable (name=${statement.name.name})"
    }
    val signed = statementSigningBytes(statement)
    return TOKEN_ENCODER.encodeToString(signed + statement.signature)
}

/**
 * Decode a hello token produced by [encodeIdentityStatementToken], or null.
 *
 * **Total over hostile input**: never throws; every malformation is null —
 * a token that is not canonical unpadded base64url (a padding `=`, a `+` or
 * `/`, a length no encoder produces, non-zero trailing bits); a first field
 * that is not [IDENTITY_BINDING_DOMAIN_TAG]; a length prefix that is negative
 * or overruns the buffer; a string field that is not valid UTF-8 (decoded
 * with [CodingErrorAction.REPORT], never replaced — a replaced byte would
 * yield a string the signature cannot cover and misclassify downstream as a
 * bad signature); fewer than 24 bytes left for the three longs; or nothing
 * left after them.
 *
 * Whatever remains after the three longs is the signature, whatever its
 * length: `Ed25519.verify` is total and owns that decision (the
 * `civictech.wire.Proof` precedent).
 *
 * `decodeIdentityStatementToken(encodeIdentityStatementToken(s))` equals `s`
 * field for field (the signature by content).
 */
fun decodeIdentityStatementToken(token: String): IdentityStatement? {
    if (!TOKEN_ALPHABET.matches(token)) return null
    val bytes = try {
        Base64.getUrlDecoder().decode(token)
    } catch (_: IllegalArgumentException) {
        return null
    }
    // Canonical form only: the JDK decoder tolerates non-zero trailing bits, so
    // two token strings could otherwise name one statement.
    if (TOKEN_ENCODER.encodeToString(bytes) != token) return null

    val buffer = ByteBuffer.wrap(bytes)
    val tag = buffer.readString() ?: return null
    if (tag != IDENTITY_BINDING_DOMAIN_TAG) return null
    val name = buffer.readString() ?: return null
    val keyId = buffer.readString() ?: return null
    val issuer = buffer.readString() ?: return null
    if (buffer.remaining() < LONGS_BYTES) return null
    val issuance = buffer.getLong()
    val notBefore = buffer.getLong()
    val notAfter = buffer.getLong()
    if (!buffer.hasRemaining()) return null
    val signature = ByteArray(buffer.remaining()).also { buffer.get(it) }

    return IdentityStatement(
        name = PeerId(name),
        keyId = KeyId(keyId),
        issuer = IssuerId(issuer),
        issuance = issuance,
        notBefore = notBefore,
        notAfter = notAfter,
        signature = signature,
    )
}

/** A 4-byte big-endian length then that many bytes of strict UTF-8, or null on any overrun or malformation. */
private fun ByteBuffer.readString(): String? {
    if (remaining() < Int.SIZE_BYTES) return null
    val length = getInt()
    if (length < 0 || length > remaining()) return null
    val slice: ByteBuffer = slice().limit(length)
    position(position() + length)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(slice)
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
