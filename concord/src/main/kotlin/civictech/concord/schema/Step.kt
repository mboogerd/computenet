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

/**
 * Re-deliver an already-processed invocation **live** at cell [on]'s [inlet],
 * under the explicit wave position `([source], [counter])` (KFX followup —
 * `computenet-yh6.1.3.3` froze the shape, `computenet-yh6.1.8` bound it). Added
 * as the deliberate between-waves schema-change ticket the seam rule requires
 * (`concord/schema/scenario.md`, `#### retransmit`).
 *
 * A **duplicate delivery**, not a second op: the closed vocabulary's other
 * verbs reach a duplicate only through `recoverFrom` journal replay, so the
 * *live* half of `[24-DUR-05]` ("whether encountered during `recoverFrom`
 * replay **or post-recovery live delivery**") and the checkpoint-frontier half
 * of `[24-DUR-02]` had no corpus expression at all. This verb injects at a
 * named inlet under a position the scenario states, bypassing the graph's
 * routing — a re-arrival of the same message, not a new op driven through the
 * topology.
 *
 * [source] names a **scenario-local cell id**, not an opaque identifier: the
 * driver resolves it to that cell's own per-source wave identity (spec 20/22
 * §Structural changes — wave ids are per-source monotonic counters minted by
 * the emitting outlet), so the delivery is indistinguishable, at the receiving
 * inlet's processed-frontier, from a genuine second arrival from that source.
 * [counter] is the position within that source's monotonic sequence this
 * delivery claims; naming the one an earlier [ApplyStep] from [source] already
 * produced is what makes it a duplicate rather than a novel arrival.
 *
 * [op]/[value] are exactly [ApplyStep]'s fields — the payload the (re)delivery
 * carries. There is no `times:`: each duplicate names its own position, so
 * repeating one means another step.
 *
 * [baseline] is the **optional catch-up anchor** (`computenet-yh6.1.12`, the
 * second gated schema change to this verb): when present the delivery is
 * stamped as a catch-up **baseline** — `MessageContext.baseline` — rather than
 * as an ordinary live frame, which is what `[24-DUR-07]`/`[24-DUR-08]` are
 * written about. It maps **scenario-local cell ids to tag counters**, resolved
 * by the driver exactly as [source] is (the named cell's own per-source
 * identity), so the scenario never invents an implementation identifier. Omit
 * it — the default — and the step means precisely what it meant before this
 * field existed: a plain live duplicate carrying no baseline at all.
 *
 * **The anchor's *contents* are not asserted by anything.** A conforming
 * receiver keys on the *presence* of a baseline (act, but never advance the
 * processed-frontier from a baseline's timestamp) and on the frame's position,
 * never on which tag counters the anchor holds. Stating them is what makes the
 * step's anchor well-formed and its run reproducible, not an assertion about
 * merge-tag currency; a scenario must not be written as though a check read
 * them.
 *
 * This is not [RestartStep] or [RestoreStep]: neither the target's state nor
 * its checkpoint is touched and nothing is recovered — only whether the
 * receiving inlet's processed-frontier suppresses this one delivery is at
 * stake. Unlike a restart, a suppressed retransmit is **not** a failure event
 * (the guard discharges the invocation's payload and counts the suppression
 * rather than dead-lettering it), so a scenario using it may still assert
 * `no-dead-letters`.
 */
@Serializable
@SerialName("retransmit")
data class RetransmitStep(
    val on: String,
    val inlet: String? = null,
    val source: String,
    val counter: Long,
    val op: String,
    @Contextual val value: Value? = null,
    val baseline: Map<String, Long>? = null,
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

/**
 * Deliver [op] (with optional [value]) to cell [on]'s [inlet] **with no message
 * context at all** — the shape `HostedCellProxy` produces off the data path,
 * and the only shape spec 24 §Effectful `[24-DUR-06]` is written about. Added
 * as the deliberate between-waves, single-writer schema change
 * `computenet-em9i` (`concord/schema/scenario.md`, `#### drive-contextless`).
 *
 * **The absence of the context is the whole verb.** `[24-DUR-06]` says a
 * `PORT_API` invocation arriving at an `Effectful` inlet with no
 * `MessageContext` SHALL be refused as undeliverable, its exclusive payloads
 * discharged and the refusal accounted. A frame with no context has no position
 * on that inlet's processed-frontier, so the case is *defined* by what the
 * delivery does not carry.
 *
 * No existing verb reaches it, and the reason is structural rather than
 * incidental:
 *
 * - [ApplyStep] drives an op through the cell's own outlet along the graph's
 *   links, and the driver mints the next wave position for that outlet — an
 *   `apply` that arrived unstamped would be a *defect* of the implementation,
 *   not the case under test. That a particular binding's `apply` happens to
 *   enter a source's inlet unstamped is an accident of that binding: another
 *   conforming driver may stamp, so a scenario built on it would assert nothing
 *   (`concord/corpus/DISPUTES.md`, "the second boundary", residual 1).
 * - [RetransmitStep] **states** an explicit `(source, counter)` position. A verb
 *   that names a position cannot drive the path whose defining property is the
 *   absence of one.
 *
 * [on] names the cell under test, exactly as [RestartStep]/[DespawnStep]/
 * [SnapshotStep] do; [inlet] selects the receiving inlet (default `"inlet"`,
 * the same default `connect`/`disconnect` use); [op]/[value] are [ApplyStep]'s
 * own fields — the payload the delivery carries.
 *
 * There is no `source:`, no `counter:` and no `baseline:` **by construction**: a
 * scenario that could name any of them would be describing a different frame.
 * There is no `times:` either — a repeated contextless drive is another step,
 * and each is judged on its own.
 *
 * A drive that is refused **is** a failure event: the refusal is reported, so a
 * scenario using this verb against an effect boundary cannot also assert
 * `no-dead-letters` (see `concord/schema/scenario.md`). It asserts
 * `refusal-count` instead, which is the observable [24-DUR-06] actually
 * requires.
 */
@Serializable
@SerialName("drive-contextless")
data class DriveContextlessStep(
    val on: String,
    val inlet: String? = null,
    val op: String,
    @Contextual val value: Value? = null,
) : Step

/**
 * Deliver [op] (with optional [value]) to cell [on]'s [inlet] through the
 * scenario-local **actor lane** [actor] — a delivery that carries a wave
 * position the *driver's own external-ingress seam* stamps, on a lane that is
 * stable across the run and across a crash. Added as the deliberate
 * between-waves, single-writer schema change `computenet-8ohq`
 * (`concord/schema/scenario.md`, `#### drive-stamped`).
 *
 * **The lane is the whole verb, and it is the admitted twin of
 * [DriveContextlessStep].** `[24-DUR-06]` refuses a `PORT_API` frame at an
 * `Effectful` inlet that carries no `MessageContext`; the corollary the
 * refusal exists to protect is that the *same* external drive, once it carries
 * a position on a lane the driver owns, is **admitted** and then falls under
 * `[24-DUR-05]` like any other frame — it fires exactly once across a
 * crash/replay, and once more for each further arrival the frontier has not
 * seen. Until this verb, no scenario could reach that arm at all: the corpus
 * could be passed in full by an implementation that admitted an externally
 * driven frame and then re-fired its effect on replay.
 *
 * No existing verb reaches it, and the reason is structural:
 *
 * - [ApplyStep] drives an op through the cell's own outlet along the graph's
 *   links; the position it carries is the *graph's*, minted for that outlet.
 *   It cannot express a frame that entered from outside the graph.
 * - [RetransmitStep] states an explicit `(source, counter)` position, where
 *   `source` must name a **cell in the scenario** whose outlet owns that wave
 *   identity. An external actor is not a cell and owns no outlet, so a
 *   retransmit cannot name its lane — and a retransmit's purpose is a
 *   *duplicate* of a delivery the graph already made, which is the opposite of
 *   a first arrival from outside.
 * - [DriveContextlessStep] is this verb with the lane removed, which is the
 *   refused case rather than the admitted one.
 *
 * [on] names the cell under test, exactly as [DriveContextlessStep] does;
 * [inlet] selects the receiving inlet (default `"inlet"`); [op]/[value] are
 * [ApplyStep]'s own fields.
 *
 * [actor] is a **scenario-local handle**, never an implementation identifier —
 * the same rule [RetransmitStep]'s `source:` follows. The scenario says *which*
 * lane, and repeating the handle means "the same external actor again"; what
 * that lane's identity actually is, and how a position is minted on it, is the
 * driver's business and is never written into a corpus file. There is no
 * `counter:` for the same reason there is no `source:`: a scenario that could
 * state the position would be describing the graph's frame, not an external
 * actor's.
 *
 * There is no `times:` — each arrival on a lane is its own step and its own
 * position, and a repeated drive is a different assertion from a repeated
 * delivery of one frame (that is [RetransmitStep]'s job).
 */
@Serializable
@SerialName("drive-stamped")
data class DriveStampedStep(
    val on: String,
    val actor: String,
    val inlet: String? = null,
    val op: String,
    @Contextual val value: Value? = null,
) : Step
