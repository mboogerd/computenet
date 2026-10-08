package civictech.inspect.edit

import civictech.inspect.Edge
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The write plane's terminal outcome vocabulary: the one name an apply's
 * terminal event carries (`[WKB2-39]`).
 *
 * **The set is closed.** It is exactly committed, refused-at-precheck,
 * unwound-clean, unwound-with-residue, rolled-back-at-commit, conflicted.
 * Adding a seventh is a design decision, not a convenience; the whole
 * atomicity story is legible from that list, which is the point (WKB2 design,
 * "Naming discipline"). `ApplyVocabularyTest` fails when an arm is added,
 * removed or renamed.
 *
 * **No arm denotes a partially-applied graph left in place** (`[WKB2-20]`).
 * The kernel's `[15-APPLY-01]` leave-and-report semantics stay the kernel's
 * default for `GraphSpec.applyTo`/`applyRemote` (95 §R4 direction (1), kept
 * unchanged per `[WKB2-21]`), but they are never the operator-visible outcome
 * of a failed apply: a failure before cut-over compensates and reports
 * [UnwoundClean] or [UnwoundWithResidue]; it is never reported as a partial
 * success. The absence of such an arm in this closed enumeration is the check.
 *
 * Wire names are kebab-case exactly as `[WKB2-39]` spells them, carried in the
 * sealed-class discriminator (`"type"` under the inspector's JSON config).
 *
 * Arms start payload-free and grow into data classes when the feature that
 * produces them lands. Such a payload is additive because the discriminator
 * name stays fixed; [RolledBackAtCommit] is the first such extension.
 *
 * Cites `[WKB2-39]`, `[WKB2-25]`, `[WKB2-16]`, `[WKB2-20]`, 95 §R4.
 */
@Serializable
sealed interface ApplyOutcome {

    /** CUT-OVER completed successfully (`[WKB2-24]`); retirement of superseded cells follows this report. */
    @Serializable
    @SerialName("committed")
    data object Committed : ApplyOutcome

    /** The spec was refused at PRECHECK, before anything was staged. */
    @Serializable
    @SerialName("refused-at-precheck")
    data object RefusedAtPrecheck : ApplyOutcome

    /** A STAGE failure was compensated and the live graph is unchanged (`[WKB2-18]`, `[WKB2-19]`). */
    @Serializable
    @SerialName("unwound-clean")
    data object UnwoundClean : ApplyOutcome

    /**
     * A staging failure was compensated, but some effects could not be undone
     * (`[WKB2-25]`); each is named in [residue]. Never reported as rolled back.
     *
     * [residue] is non-empty: an unwind with nothing left over is [UnwoundClean].
     * The check runs on construction, and the kotlinx-generated deserialiser
     * runs this `init` block too, so a wire-decoded empty list is refused as
     * well (`ApplyVocabularyTest` pins both).
     */
    @Serializable
    @SerialName("unwound-with-residue")
    data class UnwoundWithResidue(val residue: List<Residue>) : ApplyOutcome {
        init {
            require(residue.isNotEmpty()) {
                "unwound-with-residue must name at least one un-compensated effect; " +
                    "an unwind with no residue is unwound-clean ([WKB2-25])"
            }
        }
    }

    /**
     * The gated kernel swap aborted mid-COMMIT. [reason] is the kernel's
     * message verbatim, [retained] is the encoded incumbent ref, and [residue]
     * is only the staged-prefix cleanup residue. The write plane performs no
     * second compensation of the kernel-owned swap (`[WKB2-23]`, 8joqm-D5).
     */
    @Serializable
    @SerialName("rolled-back-at-commit")
    data class RolledBackAtCommit(
        val reason: String,
        val retained: String,
        val residue: List<Residue> = emptyList(),
    ) : ApplyOutcome

    /** The swap set changed since the submitted base version; no step was executed (`[WKB2-33]`, `[WKB2-35]`). */
    @Serializable
    @SerialName("conflicted")
    data object Conflicted : ApplyOutcome
}

/**
 * One un-compensated effect of an unwind — the three residue classes
 * `[WKB2-25]` names, and nothing else. DTO-shaped from the start: this is
 * what the apply-status read route returns. `ref` uses the inspector's
 * `"<uuid>:<instanceId>"` encoding (as `Node.ref`), `port` the port name.
 */
@Serializable
sealed interface Residue {

    /** A staged cell already emitted downstream across this boundary link. */
    @Serializable
    @SerialName("emitted-across-boundary")
    data class EmittedAcrossBoundary(val edge: Edge) : Residue

    /** A staged cell consumed an `Owned` payload on this port. */
    @Serializable
    @SerialName("owned-consumed")
    data class OwnedConsumed(val ref: String, val port: String) : Residue

    /** A staged cell discharged a `Leased` payload on this port. */
    @Serializable
    @SerialName("leased-discharged")
    data class LeasedDischarged(val ref: String, val port: String) : Residue
}

/**
 * The ordered stages of an accepted apply (`[WKB2-16]`), in declaration order:
 * PRECHECK → STAGE → CUT_OVER → (UNWIND | RETIRE). The stages never interleave.
 *
 * [UNWIND] and [RETIRE] are alternatives after [CUT_OVER], not successive
 * stages: an apply either unwinds (compensation) or retires the replaced
 * members, never both. Declaration order is therefore not a claim that
 * RETIRE follows UNWIND.
 */
enum class ApplyPhase { PRECHECK, STAGE, CUT_OVER, UNWIND, RETIRE }
