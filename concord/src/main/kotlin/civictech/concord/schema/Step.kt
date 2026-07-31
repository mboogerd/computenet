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

/**
 * Walk cell [on]'s **bounded state read** to completion, at most [limit] whole
 * entries per page (`readState(cellId, cursor, limit)` — spec 21 §Pull, spec 24
 * §Required next steps). Added by V1C-CONCORD, the deliberate between-waves
 * schema-change ticket the seam rule requires (`concord/schema/scenario.md`).
 *
 * **A step is a whole walk, not a page.** Cursor threading is the driver's
 * concern, not the scenario's: the harness calls the driver until the page it
 * returns has no resume token, and records the walk (its pages, and the cell's
 * wave plane immediately before and after it) for the `wave-plane-unchanged`
 * and `pages-equal-view` checks. A scenario therefore cannot express a
 * *partial* walk, an abandoned one, or one interleaved with an operation —
 * which is deliberate: the script model has no way to order a mutation against
 * a page boundary, so pretending otherwise would be a scenario asserting an
 * interleaving it did not produce.
 *
 * [limit] sweeps are how a scenario probes page-boundary behaviour: several
 * `read-state` steps at different limits over one source, each walk checked
 * (`24-BOUND-02`).
 */
@Serializable
@SerialName("read-state")
data class ReadStateStep(
    val on: String,
    val limit: Int = 200,
) : Step

/**
 * Restart cell [on] (`restart(cellId)` — spec 21 §RESTART re-baselines,
 * `[21-REBASE-01]`; spec 30/31 rule 5). Added by D-C12, the deliberate
 * between-waves schema-change ticket the seam rule requires
 * (`concord/schema/scenario.md`).
 *
 * A restart is *restore + re-baseline*, not a bare local rollback: the cell
 * reverts to its freshest available checkpoint, its outlets succeed their
 * emission epochs, and the reverted state is re-announced downstream over the
 * ordinary catch-up path so convergent consumers retract what the restart
 * un-asserted. That reconciliation is the only thing a scenario observes — how
 * an implementation *induces* the restart (a failing invocation, an operator
 * command, a supervisor signal) is the driver's business, not the scenario's.
 *
 * A restart is a **failure event**, so an implementation that also reports
 * failures observably (as this specification requires — 30/31 rule 5:
 * "observability is not a policy") will record one. A scenario driving a
 * restart therefore cannot also assert `no-dead-letters` — see
 * `concord/schema/scenario.md`.
 */
@Serializable
@SerialName("restart")
data class RestartStep(val on: String) : Step

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
