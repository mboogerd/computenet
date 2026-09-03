package civictech.identity

import civictech.cell.wire.ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    /** The incarnation file exists but cannot be read (permissions, IO). */
    INCARNATION_UNREADABLE,

    /** The incarnation file is not a decimal integer in range: truncated, empty, negative, or garbage. */
    INCARNATION_MALFORMED,

    /** The bumped incarnation could not be persisted, so returning it would be a promise this node cannot keep. */
    INCARNATION_UNWRITABLE,

    /** The next incarnation would not fit the bits a counter floor leaves for it. */
    INCARNATION_EXHAUSTED,
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
 * it is [KeyStoreRefusal.KEYPAIR_MISMATCH]: the node's
 * [KeyId][civictech.cell.link.KeyId] is the fingerprint of the public half and
 * its [PeerId] is what that key identifier resolves to, so a mismatched pair is
 * precisely "this key does not match its derived name", and continuing would
 * mean signing under a name we cannot prove.
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

/**
 * Where a node's **incarnation** comes from: a number that is strictly greater
 * on every run of one signing identity's process ([DSC1-ANN-04], `computenet-tdcx`).
 *
 * `civictech.cell.wire.AnnouncementSigningConfig.incarnation` reads its source
 * once, at signer construction, and seeds the announcement counter from
 * `civictech.cell.wire.announcementCounterFloor` of it — so a later incarnation's
 * first counter exceeds an earlier one's last, and a restarted process's
 * catch-up burst is accepted rather than classified as
 * `civictech.cell.DenialReason.REPLAY`.
 *
 * **This interface is additive to the wall-clock default, never a replacement
 * for it** (`computenet-ssa.6`, and the coverage limit `computenet-tdcx`
 * restates). A durable store needs somewhere to write next to the identity it
 * belongs to; a *derived* identity — [DeterministicKeySource], a seed phrase, an
 * HSM- or KMS-backed key — has no such place, so a file-backed incarnation
 * covers a strict subset of the identities the clock default covers. The clock
 * default stays the default for exactly that reason.
 */
fun interface IncarnationStore {
    /**
     * The incarnation for *this* run: strictly greater than every value this
     * store has previously returned, and persisted before it is returned.
     *
     * Called once per signer. Never falls back to a clock on failure — see
     * [FilePeerIncarnationStore].
     *
     * @throws KeyStoreRefusedException when the stored value cannot be read,
     *   parsed, bumped or persisted.
     */
    fun nextIncarnation(): Long
}

/**
 * The default [IncarnationStore]: one small file, `peer.incarnation`, beside
 * the key material [FilePeerKeyStore] writes into the same [directory].
 *
 * `nextIncarnation()` reads the stored value, adds one, **persists it, and only
 * then returns it**. Persist-before-return is the whole property: a crash after
 * the write and before the caller uses the value wastes an incarnation, which
 * costs nothing; a crash after the *return* and before the write would hand two
 * runs the same incarnation, which is the defect this type exists to prevent.
 * The write is a temp file plus a durable rename, so a torn write leaves the
 * previous value intact rather than a truncated one. After the rename,
 * [persist] also makes a best-effort attempt to flush [directory]'s own
 * metadata (see `fsyncDirectoryBestEffort`), so an interruption between the
 * rename and the filesystem persisting its directory entry — a power loss or
 * kernel panic, as opposed to the process crash the paragraph above covers —
 * does not resurrect the previous incarnation on the *platforms and
 * filesystems where directory `fsync` is meaningful*. That best-effort step
 * is an explicit non-goal beyond those platforms: it is a silent no-op on
 * ones without it (Windows, notably), and even where the JDK reports success,
 * this class cannot prove the write actually reached the storage device —
 * that reaches past what a JVM can observe, let alone verify in a test. Only
 * the process-crash property this section opens with is proven end to end.
 *
 * ## What it does and does not prove
 *
 * From the second call onward the wall clock is **not read at all**, so
 * monotonicity is *proven* rather than observed: a clock that steps backwards
 * across a restart (an NTP correction, a container with no battery-backed
 * clock) changes nothing about the sequence.
 *
 * The **first** call on a fresh directory has nothing to succeed, and seeds from
 * [initial], which defaults to the wall clock. That is deliberate and is the
 * only clock read in the type's life: an existing deployment running on the
 * clock default has already minted counters at `announcementCounterFloor(now)`,
 * and a durable store that began at `1` would seed every floor far *below* the
 * peer's high-water mark and dead-letter its whole burst as REPLAY —
 * permanently, because nothing lowers a high-water mark. Seeding from the clock
 * makes adopting this store safe on a live identity; every subsequent run is
 * clock-independent. A caller who knows the identity is new (or who is testing)
 * pins [initial] instead.
 *
 * ## Refusal, never fallback
 *
 * Every failure throws [KeyStoreRefusedException] with an
 * `INCARNATION_*` [KeyStoreRefusal], in the same discipline
 * [FilePeerKeyStore] uses for key material. **None of them falls back to the
 * clock.** A silent fallback would be the worst mode available: the operator
 * configured a durable source precisely because the clock is not trustworthy
 * here, and quietly reverting to it would reintroduce the defect while
 * reporting success.
 *
 * Not thread-safe against *other processes*: two live processes sharing one
 * directory is two nodes sharing one identity, which is outside what this
 * (or [FilePeerKeyStore]) defends. Within one process the file operations are
 * serialised on this object.
 */
class FilePeerIncarnationStore(
    private val directory: Path,
    private val initial: () -> Long = System::currentTimeMillis,
) : IncarnationStore {

    /** The file this store reads and writes; nothing else under [directory] is touched. */
    val incarnationFile: Path = directory.resolve(INCARNATION_FILE)

    @Synchronized
    override fun nextIncarnation(): Long {
        val previous = readPrevious()
        val next = if (previous == null) seed() else previous + 1
        if (next > MAX_INCARNATION) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.INCARNATION_EXHAUSTED,
                incarnationFile,
                "the next incarnation $next does not fit the " +
                    "${Long.SIZE_BITS - ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT} bits an announcement counter " +
                    "floor leaves for it (maximum $MAX_INCARNATION); the floor it names would wrap negative " +
                    "and every announcement this signer minted would classify as REPLAY",
            )
        }
        persist(next)
        return next
    }

    /** The persisted value, or `null` when this identity has no incarnation yet. */
    private fun readPrevious(): Long? {
        if (!Files.exists(incarnationFile)) return null
        val text = try {
            Files.readString(incarnationFile, Charsets.UTF_8)
        } catch (e: IOException) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.INCARNATION_UNREADABLE,
                incarnationFile,
                "cannot be read; refusing rather than restarting the incarnation sequence, " +
                    "which would make this run's announcements replays at every peer",
                e,
            )
        }
        val value = text.trim().toLongOrNull()
        if (value == null || value < 0 || value > MAX_INCARNATION) {
            throw KeyStoreRefusedException(
                KeyStoreRefusal.INCARNATION_MALFORMED,
                incarnationFile,
                "is not a decimal incarnation in 0..$MAX_INCARNATION (${text.length} characters); " +
                    "refusing rather than guessing a value that may be below one already used",
            )
        }
        return value
    }

    private fun seed(): Long {
        val seed = initial()
        require(seed >= 0 && seed <= MAX_INCARNATION) {
            "initial incarnation out of range: $seed is not in 0..$MAX_INCARNATION"
        }
        return seed
    }

    private fun persist(value: Long) {
        val temporary = directory.resolve("$INCARNATION_FILE.$TEMP_SUFFIX")
        try {
            Files.createDirectories(directory)
            Files.newByteChannel(
                temporary,
                setOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC),
            ).use { it.write(ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8))) }
            Files.move(temporary, incarnationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw KeyStoreRefusedException(
                KeyStoreRefusal.INCARNATION_UNWRITABLE,
                incarnationFile,
                "incarnation $value could not be persisted, so returning it would promise a monotonicity " +
                    "the next run cannot honour",
                e,
            )
        }
        fsyncDirectoryBestEffort()
    }

    /**
     * Best-effort hardening for the rename [persist] just performed: flushes
     * [directory]'s own metadata so the new directory entry (not just the file
     * content, which [StandardOpenOption.SYNC] and `ATOMIC_MOVE` already made
     * durable) survives a power loss or kernel panic, not only a process crash.
     *
     * Never throws. Directory `fsync` is a no-op or unsupported on some
     * platforms and filesystems (notably Windows, where a directory cannot be
     * opened as a [FileChannel] at all), and a failure here does not undo the
     * content durability [persist] already achieved — so it is swallowed
     * rather than escalated to [KeyStoreRefusedException]. See the class KDoc
     * for exactly what this does and does not prove.
     */
    private fun fsyncDirectoryBestEffort() {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Unsupported here (or a transient failure); the rename's content
            // durability already stands regardless.
        } catch (_: UnsupportedOperationException) {
            // Same: the channel exists but force(metaData = true) is not
            // implemented on this platform.
        }
    }

    override fun toString(): String = "FilePeerIncarnationStore(directory=$directory)"

    companion object {
        /** The incarnation file, beside [FilePeerKeyStore.PRIVATE_KEY_FILE]. Decimal text, UTF-8. */
        const val INCARNATION_FILE: String = "peer.incarnation"

        private const val TEMP_SUFFIX: String = "next"

        /**
         * The largest incarnation an announcement counter floor can carry —
         * the same bound `civictech.cell.wire.announcementCounterFloor` enforces,
         * checked here so the refusal names the store and its file rather than
         * surfacing as an `IllegalArgumentException` out of signer construction.
         */
        const val MAX_INCARNATION: Long = Long.MAX_VALUE ushr ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT
    }
}
