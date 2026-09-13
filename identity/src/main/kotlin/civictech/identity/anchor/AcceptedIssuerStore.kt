package civictech.identity.anchor

import civictech.cell.link.IssuerId
import civictech.identity.Ed25519
import civictech.identity.KeyStoreRefusal
import civictech.identity.KeyStoreRefusedException
import civictech.identity.PEER_ID_PREFIX
import civictech.identity.fingerprint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.path.name

/**
 * Where a relying process gets the anchor issuers it accepts (epic
 * `computenet-5y8t`, decision 5y8t-D2, feature decision 5y8t.2-D6): the
 * per-relying-peer POLICY CHOICE the DSC4 amendment asks for, expressed as an
 * ordinary loaded value. There is no discovery, no fetch over the wire, and
 * no default issuer compiled into this module — a process that accepts an
 * issuer says so by placing that issuer's public key where this store reads
 * it (or, in a test, by constructing the map directly and never touching this
 * interface at all).
 *
 * Anchor-key **rotation** (residual R2) and pinning/transparency (residual
 * R1) are explicitly not built here: this is a static map, read once by
 * whoever constructs an `AnchorVouchedBinding`, not a live subscription.
 */
fun interface AcceptedIssuerStore {
    /**
     * The issuers this load accepts, keyed by the [IssuerId] computed from
     * each key's own bytes ([fingerprint]) — never trusted from wherever the
     * key came from.
     */
    fun load(): Map<IssuerId, PublicKey>
}

/**
 * The default [AcceptedIssuerStore]: one X.509/SPKI file per accepted issuer
 * under [directory], reusing [civictech.identity.FilePeerKeyStore]'s
 * fail-closed discipline for the public-key half only.
 *
 * ## Layout
 *
 * Each file is named `<fp>.pub`, where `<fp>` is the issuer's [fingerprint]
 * **without** its [PEER_ID_PREFIX] scheme prefix — the 43 base64url
 * characters, filesystem-safe by construction (a `:` is not portable in a
 * file name). [issuerFileName] gives that name for a given [IssuerId], so an
 * operator tool and this class's own tests write exactly what this class
 * reads.
 *
 * The issuer id of a loaded key is always `IssuerId(fingerprint(key).name)`,
 * computed from the decoded bytes — the file name is *checked against* that
 * value ([KeyStoreRefusal.ISSUER_ID_MISMATCH]), never trusted in place of it.
 *
 * ## Refusal, never fallback
 *
 * [load] is total but fails closed: the **first** file that cannot be
 * trusted throws [KeyStoreRefusedException] and nothing partial is returned.
 * Nothing here ever writes.
 *
 * - The directory does not exist ->
 *   [KeyStoreRefusal.ISSUER_DIRECTORY_MISSING]. An *existing, empty*
 *   directory is different: it loads an empty map, and a binding constructed
 *   over it refuses every statement it is asked to verify with
 *   `ISSUER_NOT_ACCEPTED` — loud by construction, so this is not treated as
 *   an operator error.
 * - Subdirectories are ignored.
 * - Any regular file — dotfiles included, because `.DS_Store` is a real file
 *   an operator's directory can contain — that decodes as a PKCS#8 private
 *   key (`KeyFactory("EdDSA").generatePrivate` succeeds) ->
 *   [KeyStoreRefusal.ISSUER_PRIVATE_KEY_PRESENT]. This check runs before any
 *   name or extension check: a private key saved as `whatever.pub` is still
 *   refused as a private key, not parsed as one. The exception message names
 *   only the path, never key bytes ([civictech.identity.KeySecrecyTest]'s
 *   shape).
 * - A non-dotfile whose name does not end in `.pub`, or a `.pub` file that is
 *   not decodable X.509/SPKI -> [KeyStoreRefusal.MALFORMED]. A dotfile that
 *   is neither a private key nor named `*.pub` is silently ignored (the
 *   `.DS_Store` case).
 * - Decodable SPKI that is not an Ed25519 key -> [KeyStoreRefusal.UNSUPPORTED].
 *   Checked *before* the name comparison below, so a wrong-curve key named as
 *   if it were correct is reported as unsupported, not mismatched.
 * - A decodable Ed25519 key whose [fingerprint] does not match the file's
 *   stem -> [KeyStoreRefusal.ISSUER_ID_MISMATCH].
 */
class FileAcceptedIssuerStore(private val directory: Path) : AcceptedIssuerStore {

    override fun load(): Map<IssuerId, PublicKey> {
        if (!Files.isDirectory(directory)) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.ISSUER_DIRECTORY_MISSING,
                directory,
                "accepted-issuers directory does not exist; a mistyped path must not silently accept nobody",
            )
        }

        val entries = try {
            Files.list(directory).use { it.sorted().toList() }
        } catch (e: IOException) {
            throw KeyStoreRefusedException(KeyStoreRefusal.ISSUER_DIRECTORY_MISSING, directory, "cannot be listed", e)
        }

        val accepted = mutableMapOf<IssuerId, PublicKey>()
        for (path in entries) {
            if (!Files.isRegularFile(path)) continue // subdirectories (and anything else non-regular) are ignored

            val fileName = path.name
            val isDotfile = fileName.startsWith(".")

            // Runs before any name/extension check: a private key is refused
            // as exactly that, regardless of what it happens to be named.
            if (isDecodablePrivateKey(path)) {
                throw KeyStoreRefusedException(
                    KeyStoreRefusal.ISSUER_PRIVATE_KEY_PRESENT,
                    path,
                    "decodes as a PKCS#8 private key; a private key in the accepted-issuers directory is " +
                        "the configuration error this store exists to refuse",
                )
            }

            if (!fileName.endsWith(PUBLIC_KEY_SUFFIX)) {
                if (isDotfile) continue // e.g. `.DS_Store`: neither a private key nor a `.pub` file
                throw KeyStoreRefusedException(
                    KeyStoreRefusal.MALFORMED,
                    path,
                    "file name does not end in \"$PUBLIC_KEY_SUFFIX\"; expected \"<fingerprint>$PUBLIC_KEY_SUFFIX\"",
                )
            }

            val key = decodePublic(path)
            if (!Ed25519.isEd25519(key)) {
                throw KeyStoreRefusedException(
                    KeyStoreRefusal.UNSUPPORTED,
                    path,
                    "decodes to a ${curveOf(key)} public key; accepted issuers are identified by an " +
                        "${Ed25519.ALGORITHM} key",
                )
            }

            val fingerprint = fingerprint(key)
            val stem = fileName.removeSuffix(PUBLIC_KEY_SUFFIX)
            val expectedStem = fingerprint.name.removePrefix(PEER_ID_PREFIX)
            if (stem != expectedStem) {
                throw KeyStoreRefusedException(
                    KeyStoreRefusal.ISSUER_ID_MISMATCH,
                    path,
                    "fingerprint ${fingerprint.name} does not match the file name it was loaded from; " +
                        "expected \"$expectedStem$PUBLIC_KEY_SUFFIX\"",
                )
            }

            accepted[IssuerId(fingerprint.name)] = key
        }
        return accepted
    }

    private fun isDecodablePrivateKey(path: Path): Boolean {
        val bytes = readAll(path)
        return try {
            KeyFactory.getInstance(Ed25519.KEY_FACTORY).generatePrivate(PKCS8EncodedKeySpec(bytes))
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun decodePublic(path: Path): PublicKey {
        val bytes = readAll(path)
        return try {
            KeyFactory.getInstance(Ed25519.KEY_FACTORY).generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: GeneralSecurityException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                path,
                "not a decodable X.509/SPKI public key (${bytes.size} bytes)",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                path,
                "not a decodable X.509/SPKI public key (${bytes.size} bytes)",
                e,
            )
        }
    }

    private fun readAll(path: Path): ByteArray =
        try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw KeyStoreRefusedException(KeyStoreRefusal.MALFORMED, path, "cannot be read", e)
        }

    private fun curveOf(key: java.security.Key): String =
        (key as? java.security.interfaces.EdECKey)?.params?.name ?: key.algorithm

    override fun toString(): String = "FileAcceptedIssuerStore(directory=$directory)"

    private companion object {
        const val PUBLIC_KEY_SUFFIX: String = ".pub"
    }
}

/**
 * The file name [FileAcceptedIssuerStore] reads (and an operator tool would
 * write) for [issuer]: its fingerprint without the [PEER_ID_PREFIX] scheme,
 * plus `.pub`.
 *
 * @throws IllegalArgumentException if [issuer] is not a key-derived id in the
 *   `ed25519:` scheme [fingerprint] mints.
 */
fun issuerFileName(issuer: IssuerId): String {
    require(issuer.name.startsWith(PEER_ID_PREFIX)) {
        "not a key-derived IssuerId: expected the \"$PEER_ID_PREFIX\" scheme, got \"${issuer.name}\""
    }
    return issuer.name.removePrefix(PEER_ID_PREFIX) + ".pub"
}
