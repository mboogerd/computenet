package civictech.identity

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Where a node's own long-lived keypair comes from ([DSC1-KEY-04..05]).
 *
 * One entry point on purpose: a node either loads the identity it already has
 * or mints one, and nothing else. Two calls — "do I have a key?" then
 * "generate one" — is the shape that lets a transient read failure rename the
 * node.
 */
interface PeerKeyStore {
    /**
     * The node's identity: loaded when persisted material exists, otherwise
     * generated *and persisted before returning*.
     *
     * @throws KeyStoreRefusedException when material exists but cannot be
     *   trusted. Refusal never falls back to generating a replacement.
     */
    fun loadOrGenerate(): PeerIdentity
}

/**
 * Why a [PeerKeyStore] refused ([DSC1-KEY-06..07]). Machine-distinguishable by
 * construction — callers and tests branch on the enum, never on message text.
 */
enum class KeyStoreRefusal {
    /** The private key file is readable (or writable, or executable) by group or others. */
    WORLD_READABLE,

    /** The file is not a decodable key: truncated, empty, or garbage. */
    MALFORMED,

    /** A decodable key of the wrong kind — e.g. Ed448 where Ed25519 is required. */
    UNSUPPORTED,

    /** The persisted public key is not the public half of the persisted private key. */
    KEYPAIR_MISMATCH,

    /** One half of the pair is present and the other is missing. */
    INCOMPLETE_PAIR,

    /** The filesystem exposes no POSIX permission view, so owner-only storage cannot be established or checked. */
    NO_POSIX_PERMISSIONS,
}

/**
 * A [PeerKeyStore] refused to use key material, naming the offending [path]
 * and a machine-distinguishable [reason].
 *
 * The message carries the path, the reason and a human [detail] — never key
 * bytes. A cause is attached when one exists (a JDK decode failure), because
 * losing it costs the operator the diagnosis; JDK decode failures describe the
 * *shape* of the defect ("Unable to decode key", "Unsupported OID: ..."), not
 * its content.
 */
class KeyStoreRefusedException internal constructor(
    val reason: KeyStoreRefusal,
    val path: Path,
    val detail: String,
    cause: Throwable? = null,
) : IllegalStateException("refusing peer key material [$reason] at $path: $detail", cause)

/**
 * The default [PeerKeyStore]: two files under a configured [directory].
 *
 * - `peer.key` — the private key, PKCS#8 DER, created `0600` and refused if it
 *   is ever anything wider.
 * - `peer.pub` — the public key, X.509/SPKI DER.
 *
 * **Why the public half is persisted at all.** The JDK offers no API to derive
 * an Ed25519 public key from an [java.security.interfaces.EdECPrivateKey] (the
 * scalar multiplication is not exposed), so the public half cannot be
 * recomputed at load time. It is therefore stored — and, because a stored
 * public key can be *swapped*, every load re-establishes that the two halves
 * belong together with an in-memory sign/verify round trip. A pair that fails
 * it is [KeyStoreRefusal.KEYPAIR_MISMATCH]: the node's [PeerId] derives from
 * the public half, so a mismatched pair is precisely "this key does not match
 * its derived PeerId", and continuing would mean signing under a name we
 * cannot prove.
 *
 * **Nothing here regenerates.** Every failure path throws
 * [KeyStoreRefusedException]; none writes. Silent regeneration is the worst
 * available failure mode — it renames the node, and every peer that knew the
 * old [PeerId] now sees a stranger (BS-16).
 *
 * The store has exactly one constructor parameter, the directory. There is no
 * seam for injecting a keypair source, which is what keeps
 * [DeterministicKeySource] structurally unreachable from any default or
 * configuration path ([DSC1-KEY-08]); a test asserts that constructor shape.
 */
class FilePeerKeyStore(private val directory: Path) : PeerKeyStore {

    /** The private key file this store reads and writes; nothing outside [directory] is ever touched. */
    val privateKeyFile: Path = directory.resolve(PRIVATE_KEY_FILE)

    /** The public key file this store reads and writes. */
    val publicKeyFile: Path = directory.resolve(PUBLIC_KEY_FILE)

    override fun loadOrGenerate(): PeerIdentity {
        val hasPrivate = Files.exists(privateKeyFile)
        val hasPublic = Files.exists(publicKeyFile)
        return when {
            hasPrivate && hasPublic -> load()
            !hasPrivate && !hasPublic -> generate()
            hasPrivate -> throw KeyStoreRefusedException(
                KeyStoreRefusal.INCOMPLETE_PAIR,
                publicKeyFile,
                "private key $PRIVATE_KEY_FILE exists but $PUBLIC_KEY_FILE is missing; " +
                    "generating would mint a second identity for this node",
            )

            else -> throw KeyStoreRefusedException(
                KeyStoreRefusal.INCOMPLETE_PAIR,
                privateKeyFile,
                "public key $PUBLIC_KEY_FILE exists but $PRIVATE_KEY_FILE is missing; " +
                    "generating would mint a second identity for this node",
            )
        }
    }

    private fun load(): PeerIdentity {
        // Permissions first: a world-readable key is refused before its bytes
        // are read, so the refusal cannot be confused with a decode failure and
        // no key is loaded (BS-15).
        requireOwnerOnly(privateKeyFile)

        val privateKey = decodePrivate(readAll(privateKeyFile))
        val publicKey = decodePublic(readAll(publicKeyFile))
        val identity = PeerIdentity(publicKey, privateKey)

        val consistent = runCatching { identity.verify(CONSISTENCY_PROBE, identity.sign(CONSISTENCY_PROBE)) }
            .getOrDefault(false)
        if (!consistent) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.KEYPAIR_MISMATCH,
                publicKeyFile,
                "$PUBLIC_KEY_FILE is not the public half of $PRIVATE_KEY_FILE " +
                    "(sign/verify round trip failed), so the derived PeerId ${identity.peerId.name} " +
                    "cannot be proven by this node",
            )
        }
        return identity
    }

    private fun generate(): PeerIdentity {
        Files.createDirectories(directory)
        val keyPair = Ed25519.generateKeyPair()
        val privateEncoding = keyPair.private.encoded
        try {
            writeOwnerOnly(privateKeyFile, privateEncoding)
        } finally {
            // Our copy of the private encoding; `getEncoded()` hands out a fresh
            // array each call, so clearing it does not disturb the key itself.
            privateEncoding.fill(0)
        }
        write(publicKeyFile, keyPair.public.encoded)
        return PeerIdentity(keyPair)
    }

    private fun requireOwnerOnly(path: Path) {
        val permissions = try {
            Files.getPosixFilePermissions(path)
        } catch (e: UnsupportedOperationException) {
            // Fail closed: without a POSIX view we cannot establish that the
            // private key is owner-only, and "probably fine" is not a security
            // property. CI and dev machines here are POSIX.
            throw KeyStoreRefusedException(
                KeyStoreRefusal.NO_POSIX_PERMISSIONS,
                path,
                "filesystem exposes no POSIX permission view, so owner-only storage cannot be verified",
                e,
            )
        } catch (e: IOException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                path,
                "cannot read the permissions of the private key file",
                e,
            )
        }
        val leaked = permissions.filter { it in NON_OWNER_PERMISSIONS }.sorted()
        if (leaked.isNotEmpty()) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.WORLD_READABLE,
                path,
                "private key is reachable by principals other than the owning user " +
                    "(${PosixFilePermissions.toString(permissions)}; offending bits $leaked); " +
                    "expected ${PosixFilePermissions.toString(PRIVATE_KEY_PERMISSIONS)}",
            )
        }
    }

    private fun readAll(path: Path): ByteArray =
        try {
            Files.readAllBytes(path)
        } catch (e: IOException) {
            throw KeyStoreRefusedException(KeyStoreRefusal.MALFORMED, path, "cannot be read", e)
        }

    private fun decodePrivate(bytes: ByteArray): PrivateKey {
        val key = try {
            KeyFactory.getInstance(Ed25519.KEY_FACTORY).generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (e: GeneralSecurityException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                privateKeyFile,
                "not a decodable PKCS#8 private key (${bytes.size} bytes)",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                privateKeyFile,
                "not a decodable PKCS#8 private key (${bytes.size} bytes)",
                e,
            )
        }
        if (!Ed25519.isEd25519(key)) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.UNSUPPORTED,
                privateKeyFile,
                "decodes to a ${curveOf(key)} private key; this node signs with ${Ed25519.ALGORITHM}",
            )
        }
        return key
    }

    private fun decodePublic(bytes: ByteArray): PublicKey {
        val key = try {
            KeyFactory.getInstance(Ed25519.KEY_FACTORY).generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: GeneralSecurityException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                publicKeyFile,
                "not a decodable X.509/SPKI public key (${bytes.size} bytes)",
                e,
            )
        } catch (e: IllegalArgumentException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.MALFORMED,
                publicKeyFile,
                "not a decodable X.509/SPKI public key (${bytes.size} bytes)",
                e,
            )
        }
        if (!Ed25519.isEd25519(key)) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.UNSUPPORTED,
                publicKeyFile,
                "decodes to a ${curveOf(key)} public key; this node is identified by an ${Ed25519.ALGORITHM} key",
            )
        }
        return key
    }

    private fun writeOwnerOnly(path: Path, bytes: ByteArray) {
        val ownerOnly = PosixFilePermissions.asFileAttribute(PRIVATE_KEY_PERMISSIONS)
        try {
            // CREATE_NEW: never truncate an existing key. The permissions are
            // applied at creation (so the bytes are never briefly world-readable)
            // and re-asserted afterwards, because a restrictive umask can only
            // narrow the creation mode, never widen it — but a permissive one
            // must not be able to leave it wide.
            Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly)
                .use { it.write(ByteBuffer.wrap(bytes)) }
            Files.setPosixFilePermissions(path, PRIVATE_KEY_PERMISSIONS)
        } catch (e: UnsupportedOperationException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.NO_POSIX_PERMISSIONS,
                path,
                "filesystem does not support owner-only file permissions",
                e,
            )
        } catch (e: IOException) {
            throw KeyStoreRefusedException(KeyStoreRefusal.MALFORMED, path, "cannot be written", e)
        }
    }

    private fun write(path: Path, bytes: ByteArray) {
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            throw KeyStoreRefusedException(KeyStoreRefusal.MALFORMED, path, "cannot be written", e)
        }
    }

    private fun curveOf(key: java.security.Key): String =
        (key as? java.security.interfaces.EdECKey)?.params?.name ?: key.algorithm

    override fun toString(): String = "FilePeerKeyStore(directory=$directory)"

    companion object {
        /** Private key, PKCS#8 DER, `0600`. */
        const val PRIVATE_KEY_FILE: String = "peer.key"

        /** Public key, X.509/SPKI DER. */
        const val PUBLIC_KEY_FILE: String = "peer.pub"

        /** The only permissions a private key file may carry. */
        val PRIVATE_KEY_PERMISSIONS: Set<PosixFilePermission> =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        private val NON_OWNER_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermission.values().filterTo(mutableSetOf()) { !it.name.startsWith("OWNER_") }

        /**
         * Fixed public plaintext for the load-time keypair consistency round
         * trip. Not a secret and not a nonce: it is signed with a key we
         * already hold and verified in memory, never persisted or transmitted.
         */
        private val CONSISTENCY_PROBE: ByteArray =
            "computenet:identity:keypair-consistency-probe".toByteArray(Charsets.UTF_8)
    }
}
