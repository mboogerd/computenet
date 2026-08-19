package civictech.wire

import civictech.cell.DenialReason
import civictech.cell.link.PeerId
import civictech.cell.wire.DEFAULT_NONCE_RETENTION_MILLIS
import civictech.cell.wire.PeerCredentials
import civictech.identity.PEER_ID_LENGTH
import civictech.identity.PEER_ID_PREFIX
import civictech.identity.PeerIdentity
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * The authenticated hello's *grammar, challenge encoding, nonces and replay
 * window* — everything the DSC1 handshake needs that is a pure function or a
 * bounded piece of state, with no socket and no `Session` in sight
 * (epic `computenet-ssa` §9.1 and §9.8; `[DSC1-HELLO-01..02]`,
 * `[DSC1-HELLO-11]`).
 *
 * `WsTransport.Session` is deliberately **not** touched by this file: the
 * admission point that composes these pieces — parse, derive, verify, admit or
 * refuse — is its own item, so that the trust decision lands at one auditable
 * seam rather than being smeared across a grammar change.
 *
 * ## Why a new message form, and why a legacy peer cannot misparse it (§9.1)
 *
 * The legacy hello is one text line, `HELLO <mirrorRef>[ <peerName>]`, parsed
 * by `WsTransport.Session.onText` as
 * `require(message.startsWith("HELLO "))` followed by
 * `removePrefix("HELLO ").trim().split(" ", limit = 2)`. That `limit = 2`
 * is the hazard the epic names as its highest-probability breakage: **any**
 * extra whitespace-separated token is absorbed into the peer *name*, so a peer
 * that appended key material to the legacy line would be admitted under a
 * `PeerId` nobody minted. Corrupted identity, no error. Extending that line is
 * therefore forbidden, not merely inadvisable — its bytes never change.
 *
 * Instead there are two new forms, both with versioned prefixes:
 *
 * ```text
 * HELLO2 <mirrorRef> <claimedPeerId> <base64url(SPKI)> <base64url(nonce)>
 * PROOF <base64url(signature)>
 * ```
 *
 * **A legacy peer receiving `HELLO2 ...` fails loudly rather than misparsing
 * it.** The legacy prefix constant is `"HELLO "` *with a trailing space*, and
 * the sixth character of `"HELLO2 ..."` is `2`, not a space — so
 * `startsWith("HELLO ")` is false and the legacy `require` throws. The old side
 * sees a visible connection error; it can never reach `split` and so can never
 * mint a wrong `PeerId` from these bytes. [helloBytesCannotBeMisparsedByLegacy]
 * states that argument as executable code, and `HelloProtocolTest` pins it.
 *
 * In the other direction a new side reading a legacy line gets
 * [HelloMalformation.NOT_HELLO2] — a refusal, never a silent downgrade.
 *
 * **Strict parsing with an exact token count is unambiguous here** because
 * every `HELLO2` field is space-free by construction: a `UUID`'s canonical
 * form, the fixed-shape `ed25519:<43 base64url chars>` id, and unpadded
 * base64url. So a wrong token count, an undecodable field, or a claimed id
 * outside the derived form is a *malformed hello*
 * ([HelloParse.Malformed], `DenialReason.MALFORMED_HELLO`) — never a name.
 */

/** The versioned prefix of an authenticated hello line, trailing space included. */
const val HELLO2_PREFIX: String = "HELLO2 "

/** The versioned prefix of the challenge-response line, trailing space included. */
const val PROOF_PREFIX: String = "PROOF "

/**
 * The legacy hello prefix, **trailing space included** — mirrored here from
 * `WsTransport`'s own private constant so the collision argument above is
 * testable without reaching into that file.
 *
 * The duplication is deliberate and safe in one direction only: these bytes are
 * frozen (the legacy line is never extended, see the file KDoc), so this mirror
 * cannot go stale by `WsTransport` changing under it. What it does *not* do is
 * force `WsTransport` to adopt this constant — that unification belongs to the
 * item that touches `Session`.
 */
const val LEGACY_HELLO_PREFIX: String = "HELLO "

/**
 * Nonce size this side generates: 32 bytes / 256 bits
 * ([DSC1-HELLO-01] demands at least 128).
 *
 * Chosen at the SHA-256 output width rather than at the requirement's floor
 * because the cost is four extra base64url characters on one line per
 * connection, and nothing about a hello is rate-sensitive.
 */
const val HELLO_NONCE_BYTES: Int = 32

/**
 * Smallest nonce this side will *accept* from a peer: 16 bytes / 128 bits, the
 * floor `[DSC1-HELLO-01]` states.
 *
 * Deliberately below [HELLO_NONCE_BYTES] rather than equal to it. Pinning the
 * accepted size to the generated size would make a peer that legitimately
 * picks a different (still conformant) width indistinguishable from a peer
 * sending garbage, and the requirement is a floor, not an equality.
 */
const val MIN_HELLO_NONCE_BYTES: Int = 16

private val BASE64URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val BASE64URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()

/** One shared CSPRNG for hello nonces; `SecureRandom` is thread-safe. */
private val NONCE_SOURCE = SecureRandom()

/**
 * A fresh hello nonce: [HELLO_NONCE_BYTES] bytes from [SecureRandom]
 * ([DSC1-HELLO-01]).
 *
 * **Freshly drawn on every call, with no caching, pooling or reuse anywhere in
 * this file** — which is how `[DSC1-HELLO-02]` ("a distinct nonce for every
 * hello, including each re-hello after a reconnect") is met: the caller that
 * sends a hello calls this once per hello, and there is no path by which a
 * previous value could be returned again.
 */
fun generateHelloNonce(): ByteArray = ByteArray(HELLO_NONCE_BYTES).also { NONCE_SOURCE.nextBytes(it) }

/**
 * The authenticated hello a side sends: the mirror ref this connection instance
 * offers, the id it claims, the public key that claim must derive from, and its
 * fresh challenge nonce.
 *
 * [publicKeySpki] is the X.509/SubjectPublicKeyInfo encoding —
 * `java.security.PublicKey.getEncoded` for a JDK Ed25519 key, which is what
 * `civictech.identity.fingerprint` digests. Nothing here is secret: a public
 * key, a nonce and two public names.
 *
 * `equals`/`hashCode` are written out because the two [ByteArray] fields would
 * otherwise compare by reference, which would make the round-trip pin below
 * vacuous.
 */
class Hello2(
    val mirrorRef: UUID,
    val claimedPeerId: PeerId,
    val publicKeySpki: ByteArray,
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Hello2 &&
            mirrorRef == other.mirrorRef &&
            claimedPeerId == other.claimedPeerId &&
            publicKeySpki.contentEquals(other.publicKeySpki) &&
            nonce.contentEquals(other.nonce)

    override fun hashCode(): Int {
        var result = mirrorRef.hashCode()
        result = 31 * result + claimedPeerId.hashCode()
        result = 31 * result + publicKeySpki.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }

    /** No nonce bytes: a hello nonce is not a secret, but `[DSC1-OBS-05]` keeps it out of logs anyway. */
    override fun toString(): String =
        "Hello2(mirrorRef=$mirrorRef, claimedPeerId=${claimedPeerId.name}, " +
            "publicKeySpki=${publicKeySpki.size}B, nonce=${nonce.size}B)"
}

/**
 * The response to the peer's challenge: an Ed25519 signature over
 * [helloChallengeBytes].
 *
 * The signature's *length* is deliberately not constrained by [parseProof].
 * `civictech.identity.Ed25519.verify` is total over malformed signatures
 * (every `GeneralSecurityException` becomes `false`), so a wrong-length
 * signature is refused as `DenialReason.BAD_SIGNATURE` by the verifier that
 * owns that decision, rather than as a grammar error here. Verification is the
 * one step an attacker chooses the input for; keeping the length check off the
 * parse path means there is exactly one place that says "this proof is bad".
 */
class Proof(val signature: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Proof && signature.contentEquals(other.signature)

    override fun hashCode(): Int = signature.contentHashCode()

    /** No signature bytes — `[DSC1-OBS-05]` forbids raw signature bytes in records and logs. */
    override fun toString(): String = "Proof(signature=${signature.size}B)"
}

/**
 * Why a hello line did not parse — **machine-readable**, so a refusal can be
 * classified without matching on a message string.
 *
 * Every constant maps to `DenialReason.MALFORMED_HELLO`
 * ([HelloParse.Malformed.reason]); this enum is the finer grain underneath it,
 * for the refusal record and for tests. The distinction that matters most is
 * [NOT_HELLO2]: a legacy name-only hello arriving at a
 * `PeerAuthPolicy.RequireAuthenticated` side is a *downgrade attempt*, which
 * its admission point records as `DenialReason.AUTH_REQUIRED` rather than as a
 * malformation — the grammar layer only reports what it saw.
 */
enum class HelloMalformation {
    /** The line does not begin with [HELLO2_PREFIX] — a legacy hello, or something else entirely. */
    NOT_HELLO2,

    /** The line does not begin with [PROOF_PREFIX]. */
    NOT_PROOF,

    /**
     * The wrong number of space-separated tokens: fewer than the grammar needs,
     * or extra trailing tokens. This is the constant that closes the legacy
     * `split(" ", limit = 2)` hazard — extra tokens are an *error* here, never
     * absorbed into a name.
     */
    WRONG_TOKEN_COUNT,

    /** The mirror-ref token is not a canonical `UUID`. */
    UNDECODABLE_MIRROR_REF,

    /**
     * The claimed id is not in the fixed key-derived form
     * `ed25519:<43 unpadded-base64url chars>` that `civictech.identity.fingerprint`
     * mints. A claim that cannot possibly be a fingerprint is rejected as
     * malformed before any key is even looked at.
     */
    CLAIMED_ID_NOT_KEY_DERIVED,

    /** The public-key token is not decodable unpadded base64url, or decodes to nothing. */
    UNDECODABLE_PUBLIC_KEY,

    /** The nonce token is not decodable unpadded base64url. */
    UNDECODABLE_NONCE,

    /** The nonce decoded, but to fewer than [MIN_HELLO_NONCE_BYTES] bytes ([DSC1-HELLO-01]'s floor). */
    NONCE_TOO_SHORT,

    /** The signature token is not decodable unpadded base64url, or decodes to nothing. */
    UNDECODABLE_SIGNATURE,
}

/** The outcome of parsing one hello-protocol line: a message, or a classified malformation. */
sealed interface HelloParse<out T> {
    /** The line parsed. */
    data class Ok<out T>(val message: T) : HelloParse<T>

    /**
     * The line did not parse. Carries [kind] for machines and [detail] for
     * humans; [detail] names only the shape of what arrived, never a decoded
     * field, so it is safe in a log.
     */
    data class Malformed(val kind: HelloMalformation, val detail: String) : HelloParse<Nothing> {
        /** How an admission point accounts this refusal (`civictech.cell.BoundaryDenials`). */
        val reason: DenialReason get() = DenialReason.MALFORMED_HELLO
    }
}

/** `HELLO2 <mirrorRef> <claimedPeerId> <base64url(SPKI)> <base64url(nonce)>`. */
fun encodeHello2(hello: Hello2): String = buildString {
    append(HELLO2_PREFIX)
    append(hello.mirrorRef)
    append(' ')
    append(hello.claimedPeerId.name)
    append(' ')
    append(BASE64URL_ENCODER.encodeToString(hello.publicKeySpki))
    append(' ')
    append(BASE64URL_ENCODER.encodeToString(hello.nonce))
}

/** `PROOF <base64url(signature)>`. */
fun encodeProof(proof: Proof): String = PROOF_PREFIX + BASE64URL_ENCODER.encodeToString(proof.signature)

/**
 * Parse one [HELLO2_PREFIX] line. Total: every rejection is a
 * [HelloParse.Malformed], never a throw and never a [PeerId].
 *
 * The token split is deliberately `split(" ")` with **no** `limit` and **no**
 * `trim()`, requiring exactly [HELLO2_TOKEN_COUNT] tokens. That is what makes a
 * trailing space, a doubled space, or an appended token an error rather than
 * something folded into the last field — the precise failure the legacy
 * grammar has.
 */
fun parseHello2(line: String): HelloParse<Hello2> {
    if (!line.startsWith(HELLO2_PREFIX)) {
        return HelloParse.Malformed(
            HelloMalformation.NOT_HELLO2,
            "line does not start with the \"$HELLO2_PREFIX\" prefix",
        )
    }
    val tokens = line.substring(HELLO2_PREFIX.length).split(" ")
    if (tokens.size != HELLO2_TOKEN_COUNT) {
        return HelloParse.Malformed(
            HelloMalformation.WRONG_TOKEN_COUNT,
            "expected $HELLO2_TOKEN_COUNT space-separated tokens after the prefix, got ${tokens.size}",
        )
    }
    val (mirrorRefToken, claimedIdToken, keyToken, nonceToken) = tokens
    val mirrorRef = try {
        UUID.fromString(mirrorRefToken)
    } catch (_: IllegalArgumentException) {
        return HelloParse.Malformed(HelloMalformation.UNDECODABLE_MIRROR_REF, "mirror ref is not a canonical UUID")
    }
    if (!isKeyDerivedPeerIdForm(claimedIdToken)) {
        return HelloParse.Malformed(
            HelloMalformation.CLAIMED_ID_NOT_KEY_DERIVED,
            "claimed id is not in the form $PEER_ID_PREFIX<43 base64url chars>",
        )
    }
    val publicKeySpki = decodeBase64Url(keyToken)
        ?: return HelloParse.Malformed(HelloMalformation.UNDECODABLE_PUBLIC_KEY, "public key is not base64url")
    if (publicKeySpki.isEmpty()) {
        return HelloParse.Malformed(HelloMalformation.UNDECODABLE_PUBLIC_KEY, "public key decoded to zero bytes")
    }
    val nonce = decodeBase64Url(nonceToken)
        ?: return HelloParse.Malformed(HelloMalformation.UNDECODABLE_NONCE, "nonce is not base64url")
    if (nonce.size < MIN_HELLO_NONCE_BYTES) {
        return HelloParse.Malformed(
            HelloMalformation.NONCE_TOO_SHORT,
            "nonce is ${nonce.size} bytes, below the $MIN_HELLO_NONCE_BYTES-byte floor",
        )
    }
    return HelloParse.Ok(Hello2(mirrorRef, PeerId(claimedIdToken), publicKeySpki, nonce))
}

/**
 * Parse one [PROOF_PREFIX] line. Total, same discipline as [parseHello2].
 *
 * A `PROOF` arriving *before* any `HELLO2` is also a malformed hello — an
 * out-of-order protocol message — but detecting that needs connection state,
 * so it is the admission point's check, recorded under the same
 * `DenialReason.MALFORMED_HELLO`. This function sees one line and says only
 * whether that line is a well-formed `PROOF`.
 */
fun parseProof(line: String): HelloParse<Proof> {
    if (!line.startsWith(PROOF_PREFIX)) {
        return HelloParse.Malformed(
            HelloMalformation.NOT_PROOF,
            "line does not start with the \"$PROOF_PREFIX\" prefix",
        )
    }
    val tokens = line.substring(PROOF_PREFIX.length).split(" ")
    if (tokens.size != PROOF_TOKEN_COUNT) {
        return HelloParse.Malformed(
            HelloMalformation.WRONG_TOKEN_COUNT,
            "expected $PROOF_TOKEN_COUNT space-separated token after the prefix, got ${tokens.size}",
        )
    }
    val signature = decodeBase64Url(tokens[0])
        ?: return HelloParse.Malformed(HelloMalformation.UNDECODABLE_SIGNATURE, "signature is not base64url")
    if (signature.isEmpty()) {
        return HelloParse.Malformed(HelloMalformation.UNDECODABLE_SIGNATURE, "signature decoded to zero bytes")
    }
    return HelloParse.Ok(Proof(signature))
}

/** Token count after [HELLO2_PREFIX]: mirror ref, claimed id, public key, nonce. */
const val HELLO2_TOKEN_COUNT: Int = 4

/** Token count after [PROOF_PREFIX]: the signature alone. */
const val PROOF_TOKEN_COUNT: Int = 1

/**
 * The §9.1 legacy-collision argument, as code rather than prose: no line this
 * file encodes can be read by the legacy parser as a hello at all.
 *
 * True by construction — `"HELLO2 "` and `"PROOF "` both fail
 * `startsWith("HELLO ")` — and pinned by `HelloProtocolTest` so a later
 * prefix change that reintroduced the collision would redden a build instead
 * of silently corrupting identity on a mixed-version peering.
 */
fun helloBytesCannotBeMisparsedByLegacy(line: String): Boolean = !line.startsWith(LEGACY_HELLO_PREFIX)

/**
 * Whether [name] has the fixed shape `civictech.identity.fingerprint` mints:
 * [PEER_ID_PREFIX] plus 43 unpadded-base64url characters, total
 * [PEER_ID_LENGTH].
 *
 * A *shape* check, not a verification — it says the claim could be a
 * fingerprint, never that it is *this* peer's. The binding between key and name
 * is checked by deriving the fingerprint from the presented key and comparing
 * (`DenialReason.ID_MISMATCH`, `[DSC1-HELLO-06]`), which is the admission
 * point's job. Rejecting the wrong shape here means a claimed id that could not
 * possibly be a fingerprint never reaches that comparison, and never becomes a
 * `PeerId`.
 */
fun isKeyDerivedPeerIdForm(name: String): Boolean {
    if (name.length != PEER_ID_LENGTH || !name.startsWith(PEER_ID_PREFIX)) return false
    val digest = decodeBase64Url(name.substring(PEER_ID_PREFIX.length)) ?: return false
    return digest.size == 32
}

private fun decodeBase64Url(token: String): ByteArray? =
    try {
        BASE64URL_DECODER.decode(token)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Everything one side signs to prove it holds the private half — in the
 * **role-asymmetric** order that makes reflecting a proof useless
 * ([DSC1-HELLO-03]).
 *
 * The field order is `signerPeerId, verifierPeerId, verifierNonce, signerNonce,
 * signerMirrorRef, verifierMirrorRef`, and the asymmetry is the security
 * property: the bytes a side *signs* are not the bytes it *expects* from its
 * peer, because signer and verifier swap places in every pair. So echoing a
 * side's own `PROOF` straight back at it cannot verify — the reflected
 * signature commits to the roles the other way round. [helloChallengeBytes]'s
 * KDoc spells out the two things that would each break this on their own.
 *
 * The **two mirror refs are the session-binding value** `[DSC1-HELLO-03]`
 * asks for. Both peering paths mint a fresh mirror per connection instance
 * (`WsTransport.Session.hello` per socket open,
 * `civictech.cell.wire.Peering.Loopback.heal` per heal), and after the `HELLO2`
 * exchange each side knows both refs — so a proof captured on one connection
 * is bound to that connection's refs and is worthless on the next one.
 */
class HelloChallenge(
    val signerPeerId: PeerId,
    val verifierPeerId: PeerId,
    val verifierNonce: ByteArray,
    val signerNonce: ByteArray,
    val signerMirrorRef: UUID,
    val verifierMirrorRef: UUID,
) {
    /** The same challenge as the peer computes for its own proof: every role swapped. */
    fun mirrored(): HelloChallenge = HelloChallenge(
        signerPeerId = verifierPeerId,
        verifierPeerId = signerPeerId,
        verifierNonce = signerNonce,
        signerNonce = verifierNonce,
        signerMirrorRef = verifierMirrorRef,
        verifierMirrorRef = signerMirrorRef,
    )

    /** No nonce bytes (`[DSC1-OBS-05]`). */
    override fun toString(): String =
        "HelloChallenge(signer=${signerPeerId.name}, verifier=${verifierPeerId.name}, " +
            "signerMirrorRef=$signerMirrorRef, verifierMirrorRef=$verifierMirrorRef)"
}

/**
 * Domain-separation tag, inside the signed region and first.
 *
 * A **deliberate addition** to the field list [HelloChallenge] names, and the
 * reason is cross-protocol confusion: the same keypair also signs location
 * announcements (`civictech.identity.announce.canonicalBytes`) in a sibling
 * feature. Without a tag, nothing structurally prevents some future byte
 * string from being valid under both grammars, and a signature minted for one
 * purpose would then be a valid signature for the other. Tagging costs 38
 * bytes inside a per-connection hash and removes the whole question.
 *
 * Version it, do not repurpose it: a change to the layout below gets `/v2`.
 */
private const val CHALLENGE_DOMAIN_TAG: String = "computenet/DSC1/hello-challenge/v1"

/**
 * The canonical bytes a side signs for its hello proof.
 *
 * Pure, total over well-formed UTF-16 inputs, and **injective** — the security
 * property, since two challenges sharing an encoding would share a signature.
 * Injectivity is obtained by construction, in the style of
 * `civictech.identity.announce.canonicalBytes` (that function is deliberately
 * *not* reused: it is announcement-specific and its exact bytes are pinned by a
 * golden vector):
 *
 * - **A domain tag first** — see [CHALLENGE_DOMAIN_TAG].
 * - **Fixed field order and fixed widths.** Fields appear in
 *   [HelloChallenge]'s constructor order; each `UUID` is sixteen bytes
 *   (most-significant then least-significant bits). Nothing is omitted when it
 *   is empty or default.
 * - **A four-byte big-endian length prefix on every variable-width field** —
 *   the two ids as UTF-8, the two nonces as raw bytes. So no two distinct field
 *   sequences can concatenate to the same byte string: the classic
 *   `"ab"+"c"` vs `"a"+"bc"` confusion cannot arise, and neither can a nonce
 *   borrowing bytes from the id in front of it.
 *
 * Because the grammar is self-delimiting at every position, the byte string
 * parses back to exactly one challenge — which is injectivity.
 *
 * **Role asymmetry, and the two ways it could be lost.** Swapping signer and
 * verifier must change the output, so a reflected `PROOF` cannot verify. That
 * holds only because (a) the two ids are in a fixed order rather than sorted or
 * combined commutatively, and (b) the two nonces likewise — and it is why
 * `verifierNonce` precedes `signerNonce` while `signerMirrorRef` precedes
 * `verifierMirrorRef`: even a hypothetical peering where both sides drew equal
 * nonces would still produce different bytes per role, via the ids and the
 * refs. `HelloProtocolTest` pins the asymmetry directly, on
 * [HelloChallenge.mirrored].
 *
 * @throws IllegalArgumentException if either peer id name contains an unpaired
 *   UTF-16 surrogate. **Fail closed** rather than encode it: `String.toByteArray`
 *   substitutes `?` for an unpaired surrogate, which would collide
 *   `"\uD800"`, `"\uDC00"` and `"?"` into one encoding and so into one
 *   signature — the exact defect `computenet-9qgg` closed the same way on the
 *   announcement path. Unreachable from [parseHello2] (a key-derived id is
 *   base64url only), and rejected anyway, because this function is public and
 *   an unreachable hole is still a hole.
 */
fun helloChallengeBytes(challenge: HelloChallenge): ByteArray {
    val out = ByteArrayOutputStream(160)
    out.writeLengthPrefixed(CHALLENGE_DOMAIN_TAG.toByteArray(Charsets.UTF_8))
    out.writeName(challenge.signerPeerId, "signerPeerId")
    out.writeName(challenge.verifierPeerId, "verifierPeerId")
    out.writeLengthPrefixed(challenge.verifierNonce)
    out.writeLengthPrefixed(challenge.signerNonce)
    out.writeUuid(challenge.signerMirrorRef)
    out.writeUuid(challenge.verifierMirrorRef)
    return out.toByteArray()
}

private fun ByteArrayOutputStream.writeName(peer: PeerId, field: String) {
    val name = peer.name
    var index = 0
    while (index < name.length) {
        val c = name[index]
        when {
            c.isHighSurrogate() -> {
                if (index + 1 >= name.length || !name[index + 1].isLowSurrogate()) {
                    throw IllegalArgumentException("$field contains an unpaired high surrogate at index $index")
                }
                index += 2
            }

            c.isLowSurrogate() ->
                throw IllegalArgumentException("$field contains an unpaired low surrogate at index $index")

            else -> index++
        }
    }
    writeLengthPrefixed(name.toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeLengthPrefixed(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes, 0, bytes.size)
}

private fun ByteArrayOutputStream.writeUuid(value: UUID) {
    writeLong(value.mostSignificantBits)
    writeLong(value.leastSignificantBits)
}

private fun ByteArrayOutputStream.writeLong(value: Long) {
    for (shift in 56 downTo 0 step 8) write(((value ushr shift) and 0xFF).toInt())
}

private fun ByteArrayOutputStream.writeInt(value: Int) {
    for (shift in 24 downTo 0 step 8) write((value ushr shift) and 0xFF)
}

/**
 * The bounded memory of hellos this side already accepted — the §9.8 retention
 * window that makes `[DSC1-HELLO-11]` implementable without unbounded state.
 *
 * ## What is retained, and what is deliberately not
 *
 * Per accepted hello: the acceptance timestamp plus **SHA-256 digests** of the
 * nonce and of the signature — never the raw bytes. Digests are enough to
 * answer "have I seen this before?" and keep `[DSC1-OBS-05]` ("no nonces or
 * raw signature bytes in any record") true of this structure by construction
 * rather than by discipline at every call site.
 *
 * ## Why the state is proportional to admitted peers, not to hellos
 *
 * This is `[DSC1-ANN-13]`'s bounded-state rule applied to the hello path, and
 * two independent bounds enforce it:
 *
 * - **Time.** An entry is evicted once it is *older than*
 *   [retentionMillis] — at exactly the window it is still remembered. A peer
 *   whose entries have all expired is dropped from the map entirely, so an
 *   idle side converges to empty.
 * - **Count.** Each peer keeps at most [MAX_ENTRIES_PER_PEER] entries, oldest
 *   evicted first. So a peer flooding accepted hellos cannot grow this beyond
 *   64 entries — the flood costs it detection of its own oldest replays, not
 *   this side's memory.
 *
 * The honest limit of the count bound: **it trades replay detection for
 * bounded memory, and that trade is the decision, not an oversight.** A peer
 * that gets 65 hellos accepted inside the window can replay its 1st. Reaching
 * that state requires 65 *successful* handshakes — each with a distinct fresh
 * nonce and a valid signature, i.e. 65 uses of the private key — so the
 * attacker who could exploit it already holds the key and does not need a
 * replay. `[DSC1-NV-01]` states stolen-key resistance as explicitly out of
 * scope.
 *
 * ## Clock
 *
 * [clock] is injected so the window is testable deterministically rather than
 * with sleeps; it defaults to `System.currentTimeMillis`. Only *differences*
 * are used, so a wall-clock step affects at worst one window's worth of
 * detection.
 *
 * Thread-safe: every method synchronizes on this instance. A hello is a
 * per-connection event, so contention is not a consideration.
 */
class HelloReplayGuard(
    private val retentionMillis: Long = DEFAULT_NONCE_RETENTION_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(retentionMillis > 0) { "retentionMillis must be positive, got $retentionMillis" }
    }

    companion object {
        /** Per-peer entry cap; see the class KDoc for the trade it makes and why. */
        const val MAX_ENTRIES_PER_PEER: Int = 64
    }

    private class Entry(
        val acceptedAtMillis: Long,
        val nonceDigest: ByteArray,
        val signatureDigest: ByteArray,
    )

    /** Insertion-ordered per peer, so the oldest entry is always the head. */
    private val entriesByPeer = HashMap<PeerId, ArrayDeque<Entry>>()

    /** True when [nonce] was accepted from [peer] and has not yet been evicted. */
    fun hasSeenNonce(peer: PeerId, nonce: ByteArray): Boolean = synchronized(this) {
        val digest = digest(nonce)
        live(peer).any { MessageDigest.isEqual(it.nonceDigest, digest) }
    }

    /** True when [signature] was accepted from [peer] and has not yet been evicted. */
    fun hasSeenSignature(peer: PeerId, signature: ByteArray): Boolean = synchronized(this) {
        val digest = digest(signature)
        live(peer).any { MessageDigest.isEqual(it.signatureDigest, digest) }
    }

    /**
     * Remember an accepted hello's [nonce] and [signature] under the peer's
     * **derived** id — the key-backed one, never the id the hello merely
     * claimed, so replay state is keyed by something the peer had to prove.
     */
    fun recordAccepted(peer: PeerId, nonce: ByteArray, signature: ByteArray) = synchronized(this) {
        val entries = entriesByPeer.getOrPut(peer) { ArrayDeque() }
        pruneExpired(entries)
        entries.addLast(Entry(clock(), digest(nonce), digest(signature)))
        while (entries.size > MAX_ENTRIES_PER_PEER) entries.removeFirst()
    }

    /** How many entries this peer currently holds — the count bound, observable. */
    fun retainedEntries(peer: PeerId): Int = synchronized(this) { live(peer).size }

    /** How many peers this guard currently holds state for — the "proportional to admitted peers" bound. */
    fun retainedPeers(): Int = synchronized(this) {
        entriesByPeer.keys.toList().forEach { live(it) }
        entriesByPeer.size
    }

    /**
     * The peer's unexpired entries, pruning as a side effect and dropping the
     * peer's whole map slot once nothing is left — which is what keeps the map
     * proportional to *currently active* admitted peers.
     */
    private fun live(peer: PeerId): List<Entry> {
        val entries = entriesByPeer[peer] ?: return emptyList()
        pruneExpired(entries)
        if (entries.isEmpty()) {
            entriesByPeer.remove(peer)
            return emptyList()
        }
        return entries.toList()
    }

    private fun pruneExpired(entries: ArrayDeque<Entry>) {
        val now = clock()
        while (entries.isNotEmpty() && now - entries.first().acceptedAtMillis > retentionMillis) {
            entries.removeFirst()
        }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * `civictech.identity.PeerIdentity` seen through the kernel's
 * [PeerCredentials] seam — the adapter that lets a `Peering.Side` be
 * configured with a keypair without `:kernel` knowing what a keypair is
 * (`[DSC1-WIRE-03]`; the direction is `:wire -> :identity -> :kernel`).
 *
 * The private key stays inside [PeerIdentity]: this class holds no key
 * material of its own, forwards [sign], and redacts [toString] — the same
 * discipline `[DSC1-KEY-09]` puts on the type it wraps.
 */
class PeerIdentityCredentials(private val identity: PeerIdentity) : PeerCredentials {
    override val peerId: PeerId = identity.peerId

    private val spki: ByteArray = requireNotNull(identity.publicKey.encoded) {
        "public key has no encoded form (format=${identity.publicKey.format})"
    }

    /** A fresh copy per read: nothing a caller does to the array can perturb the identity. */
    override val publicKey: ByteArray get() = spki.copyOf()

    override fun sign(message: ByteArray): ByteArray = identity.sign(message)

    /** The [peerId] is public by construction; no key material appears. */
    override fun toString(): String = "PeerIdentityCredentials(peerId=${peerId.name})"
}

/** This identity as the credentials a `Peering.Side` takes — see [PeerIdentityCredentials]. */
fun PeerIdentity.asPeerCredentials(): PeerCredentials = PeerIdentityCredentials(this)
