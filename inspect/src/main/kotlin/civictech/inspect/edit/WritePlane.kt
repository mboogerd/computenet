package civictech.inspect.edit

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Whether this inspector accepts graph edits at all (WKB2 F5, epic
 * computenet-7p8) — a **constructor choice** on `InspectorServer`, not a
 * runtime toggle, and [Disabled] unless the embedding process opts in
 * (`[WKB2-06]`).
 *
 * The plane is advertised at `GET /api/inspect/capabilities` (`[WKB2-51]`) so a
 * client can decide whether to offer editing at all, and every mutating route
 * it adds is guarded by [WriteGate], which refuses a disabled plane before it
 * looks at anything else.
 */
sealed interface WritePlane {

    /**
     * The management verbs a plan submitted to this plane may use — the
     * graph-building three of `HostManagementApi` (`spawn`, `connect`,
     * `despawn`), and nothing when [Disabled].
     *
     * `promote` is deliberately absent: it joins this list when WKB2 F9 (graph
     * evolution through the write plane) merges, not before, so a client never
     * sees a verb this server cannot yet apply.
     */
    val verbs: List<String>

    /** The default: no write route admits anything, and the capability read says so. */
    data object Disabled : WritePlane {
        override val verbs: List<String> = emptyList()
    }

    /**
     * Edits are accepted from a caller that presents [capability] in the
     * [WriteGate.WRITE_HEADER] header.
     *
     * [identityLabel] is the identity every admitted request is recorded under
     * (`[WKB2-49]`). It defaults to [DEFAULT_IDENTITY] because the only thing
     * this plane actually knows about a caller is that it held the capability —
     * "even where that identity is only the capability presented". A process
     * with a better name for its one operator passes it.
     */
    data class Enabled(
        val capability: Capability,
        val identityLabel: String = DEFAULT_IDENTITY,
    ) : WritePlane {
        override val verbs: List<String> get() = STEP_VERBS
    }

    companion object {
        /** [Enabled.identityLabel]'s default: the caller is known only as the holder of the capability. */
        const val DEFAULT_IDENTITY = "capability-holder"

        /** [Enabled.verbs] — see [verbs] for why `promote` is not here yet. */
        val STEP_VERBS: List<String> = listOf("spawn", "connect", "despawn")
    }
}

/**
 * The secret a write-plane caller presents in [WriteGate.WRITE_HEADER]. One per
 * process: either supplied by the operator or [minted][mint] at startup and
 * printed once by the embedding demo.
 *
 * [toString] deliberately renders `Capability(****)`: a capability that ends up
 * in a log line, an exception message or a data-class dump by accident is a
 * leaked credential. A caller that means to print it reads [value] explicitly.
 */
class Capability(val value: String) {

    init {
        require(value.isNotBlank()) { "a write-plane capability must not be blank" }
    }

    /**
     * Whether [presented] is this capability, compared with
     * [MessageDigest.isEqual] over the UTF-8 bytes rather than `==`.
     *
     * `String.equals` returns at the first differing character, so the time it
     * takes leaks how long a correct prefix a guesser has found; `isEqual` has
     * examined every byte of the presented value by the time it answers
     * (`[WKB2-43]`). No test measures that — a timing assertion would be a
     * flaky test of the JDK — so the reason is recorded here instead, and
     * swapping this for `==` is a change no test in this module catches.
     */
    fun matches(presented: String): Boolean =
        MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))

    override fun equals(other: Any?): Boolean = other is Capability && matches(other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Capability(****)"

    companion object {
        /** Bytes of entropy in a [mint]ed capability: 256 bits. */
        const val MINTED_BYTES = 32

        /**
         * A fresh capability: [MINTED_BYTES] random bytes, base64url without
         * padding (43 characters), so it survives a header, a URL and a shell
         * argument unquoted.
         */
        fun mint(random: SecureRandom = SecureRandom()): Capability {
            val bytes = ByteArray(MINTED_BYTES).also(random::nextBytes)
            return Capability(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}
