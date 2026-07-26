package civictech.concord.schema

import civictech.concord.value.Value
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The script step hierarchy — the driver-verb surface a scenario drives (§1.2,
 * §1.4). **Verb-complete** per the §4 seam rule: everything §3 needs is here, so
 * corpus waves add YAML, never steps.
 *
 * Canonical YAML form is a `type`-discriminated map, e.g.
 * `{type: apply, on: a, op: add, value: apple}` — see `concord/schema/scenario.md`
 * for the full grammar and the discriminator convention. Steps targeting the
 * same cell apply in file order; steps on different cells are concurrent unless a
 * [QuiesceStep] barrier separates them (§1.2 script semantics).
 */
@Serializable
sealed interface Step

/**
 * Apply operation [op] to cell [on] with optional [value], optionally [times]
 * repeated. Maps to `apply(cellId, op)` (§1.4). `op` is a neutral verb the cell
 * catalog defines (`add`, `remove`, `put`, `increment`, …).
 */
@Serializable
@SerialName("apply")
data class ApplyStep(
    val on: String,
    val op: String,
    @Contextual val value: Value? = null,
    val times: Int? = null,
) : Step

/**
 * A quiescence barrier: everything before it settles (driver `quiesce(budget)`)
 * before anything after it starts. [budget] overrides the harness default.
 */
@Serializable
@SerialName("quiesce")
data class QuiesceStep(val budget: Int? = null) : Step

/**
 * A topology add applied mid-script (`connect(from, to, inlet?, role?)`).
 * [expect] pins the construction-time result: `connected` (default) or `rejected`
 * (a negative / admission-policy scenario, §1.2 exemplar (d)).
 */
@Serializable
@SerialName("connect")
data class ConnectStep(
    val from: String,
    val to: String,
    val inlet: String? = null,
    val outlet: String? = null,
    val role: String? = null,
    val expect: Expect? = null,
) : Step

/**
 * A topology removal (`disconnect(linkRef)`), the link named by its endpoints.
 * [expect] pins the result where a removal can be refused.
 */
@Serializable
@SerialName("disconnect")
data class DisconnectStep(
    val from: String,
    val to: String,
    val inlet: String? = null,
    val outlet: String? = null,
    val expect: Expect? = null,
) : Step

/**
 * Capture cell [on]'s state (`snapshot(cellId) → blob`) under the handle [alias],
 * for a later [RestoreStep]. The blob is opaque scenario-local state.
 */
@Serializable
@SerialName("snapshot")
data class SnapshotStep(
    val on: String,
    @SerialName("as") val alias: String,
) : Step

/**
 * Re-materialize cell [on] from the snapshot handle [from] (`restore(hostId,
 * cellId, blob)`), optionally on a different [host] (migration/durability).
 */
@Serializable
@SerialName("restore")
data class RestoreStep(
    val on: String,
    val from: String,
    val host: String? = null,
) : Step

/** Gracefully retire cell [on] (`despawn(cellId)`), unlinking it. */
@Serializable
@SerialName("despawn")
data class DespawnStep(val on: String) : Step

/** Construction-time expectation for [ConnectStep]/[DisconnectStep] and inline `expect:` (§1.4). */
@Serializable
enum class Expect {
    @SerialName("connected") CONNECTED,
    @SerialName("rejected") REJECTED,
}
