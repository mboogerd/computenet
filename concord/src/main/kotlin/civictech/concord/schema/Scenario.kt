package civictech.concord.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * L1 scenario schema (Concord §1.2), version 1. One [Scenario] per YAML document.
 * These are pure kotlinx-serialization data classes; the YAML front end (kaml)
 * and the [civictech.concord.value.Value] serializer live in the test source
 * set, so this package carries no YAML dependency and no kernel dependency.
 *
 * The load-bearing rule (Concord §4 seam): the script/step and check hierarchies
 * are **verb-complete** for everything §3 needs, and single-writer thereafter —
 * corpus waves add YAML, never code. Human documentation: `concord/schema/scenario.md`.
 */
@Serializable
data class Scenario(
    val id: String,
    val title: String,
    /** ≥1 EARS requirement id this scenario asserts (Concord P6); empty only for stubs. */
    val covers: List<String> = emptyList(),
    val profile: Profile,
    val kind: Kind,
    val narrative: Narrative? = null,
    /** The topology as data. Absent for `kind: generative` (a [generator] stands in). */
    val graph: Graph? = null,
    /** Ordered steps: ops, barriers, topology mutations, lifecycle. */
    val script: List<Step> = emptyList(),
    /** Assertions drawn from the closed check vocabulary (§1.4). */
    val checks: List<Check> = emptyList(),
    /** Present only for `kind: generative`. */
    val generator: Generator? = null,
    /**
     * Present only for `kind: control` (Concord P7), where it is **required**:
     * the failure this control exists to provoke. See [ExpectFailure].
     */
    @SerialName("expect-failure") val expectFailure: ExpectFailure? = null,
    /** Schedule-sweep run count; defaults to the harness default (20) when null. */
    val runs: Int? = null,
)

/**
 * The declared failure of a `kind: control` scenario: which declared check must
 * fail, and the reason it must fail for.
 *
 * A control is a negative scenario — it asserts that the harness *detects* one
 * specific violation. "At least one check failed" is not proof of that. A
 * control whose declared check starts failing for an unrelated reason (a vacuity
 * guard firing on an empty observation log, an evaluator reporting a missing
 * view, an oracle refusing the graph) keeps failing, keeps satisfying a
 * `!passed` assertion, and has silently stopped testing what it was written to
 * catch — from outside indistinguishable from the control still working. That is
 * how `CTL-GF-01`'s vacuous pass survived (computenet-qaz / computenet-dqy.18).
 *
 * So a control names its failure and the runner asserts *that* failure: the
 * declared check id, plus a substring of the evaluator's message pinning the
 * reason. Any other failure — a different check, or the same check for a
 * different reason — makes the suite RED rather than green.
 */
@Serializable
data class ExpectFailure(
    /**
     * The `type:` id of the declared check that must fail (e.g. `final-view`).
     * Must name a check this scenario's `checks:` list actually declares.
     */
    val check: String,
    /**
     * A substring the failing check's message must contain. It must discriminate
     * the provoked failure from every *other* way the same check can fail —
     * `fails the predicate` rather than `observations-all-satisfy`, which that
     * check's vacuity guard would also match.
     */
    @SerialName("message-contains") val messageContains: String,
)

/** Conformance profile gating optional capability (Concord P9). Claimed wholly or not at all. */
@Serializable
enum class Profile {
    @SerialName("core") CORE,
    @SerialName("dist") DIST,
    @SerialName("dur") DUR,
}

/** Scenario kind. `control` scenarios carry deliberately wrong expectations and MUST fail (Concord P7). */
@Serializable
enum class Kind {
    @SerialName("example") EXAMPLE,
    @SerialName("generative") GENERATIVE,
    @SerialName("control") CONTROL,
}

/** BDD prose, for humans and the concordance. */
@Serializable
data class Narrative(
    val given: String,
    @SerialName("when") val whenClause: String? = null,
    val then: String? = null,
)

/** Topology as data (§1.2). */
@Serializable
data class Graph(
    val cells: List<CellSpec>,
    val links: List<LinkSpec> = emptyList(),
    /** Named hosts for `profile: dist` scenarios; absent ⇒ single implicit host. */
    val hosts: List<String>? = null,
)

/**
 * One cell in the graph. [type] is a neutral cell-catalog id (see
 * `concord/schema/cell-catalog.md`); every other field is an optional descriptor
 * param a driver binds. The named params are the ones §1.2/§3 exercise; the
 * parser runs in lenient mode so a later param needs a schema-change ticket to
 * become a typed field but does not break older files.
 */
@Serializable
data class CellSpec(
    val id: String,
    val type: String,
    /** Element/scalar type hint, e.g. `string`, `int`. */
    val of: String? = null,
    /** Pure-function id from `concord/schema/function-catalog.md` (for filter/map/combine cells). */
    val fn: String? = null,
    /**
     * Aggregator id (`count` | `sum` | `min` | `max`) a `group-by`/`partition`
     * cell folds each group with (function-catalog.md aggregators). Optional and
     * additive (W3-0): absent ⇒ **`count`**, the pre-W3-0 hard default, so every
     * existing scenario deserializes and folds unchanged. The catalog lists
     * `group-by` as `fn (key-of), agg`; `fn` is the key extractor, this is the agg.
     */
    val agg: String? = null,
    /**
     * The `k` of a `quorum-set`'s k-of-n admission (function-catalog.md / spec 24
     * quorum): an element is emitted once `k` distinct live source links assert
     * it. Optional and additive (W3-0): absent ⇒ **all live sources** (`n`, an
     * intersection), so a quorum-set with no `k` is a well-defined intersection.
     */
    val k: Int? = null,
    /** Request wave-aligned (glitch-free) semantics on a fan-in cell. */
    @SerialName("glitch-free") val glitchFree: Boolean? = null,
    /** Inlet admission policy, e.g. `single-writer`, `fan-in`. */
    @SerialName("inlet-mode") val inletMode: String? = null,
    /** Host placement (dist profile). */
    val host: String? = null,
    /** Logical replica group this cell is a member of (dist profile). */
    @SerialName("replica-of") val replicaOf: String? = null,
    /**
     * Interest-scoped instance-set assignment (dist profile, spec 40/42
     * §Interest-scoped instance sets, `42-INTEREST-01`). Optional and additive
     * (W4-A followup): absent ⇒ the kernel's own default (total interest —
     * plain replication, byte-identical to a `replica-of` cell with no
     * `interest:`). The driver translates this into a real
     * `civictech.cell.link.Interest` and calls `LocationRegistry.setInterest`
     * before the replica joins the mesh (`KernelDriverDist.spawnReplica`).
     */
    val interest: InterestSpec? = null,
    /**
     * Window descriptor (M11.6 "windowing = key derivation",
     * `24-data-cells.md` §Grouped aggregation, `24-OP-WINDOW-01`/`-02`):
     * present only on a `window` cell. Optional and additive — existing
     * scenarios carry no `window:` block and still parse. `size`/`slide` count
     * the raw event-time/sequence field's units (there is no wall clock, spec
     * 24), not durations.
     */
    val window: WindowSpec? = null,
)

/**
 * A `window` cell's key-derivation descriptor (M11.6): tumbling assigns each
 * element's event time to one composite bucket key (`size`); sliding expands
 * each element into every window of `size` it falls in, `slide` apart, then
 * groups (mirrors the kernel `Windows.tumbling`/`Windows.sliding` assigners —
 * see `KernelCatalog`/`BatchOracle`). Windows never close (`24-OP-WINDOW-02`):
 * a late element is an ordinary add and retractions flow like any other view.
 */
@Serializable
data class WindowSpec(
    val kind: WindowKind,
    /** The window length, in event-time/sequence units. */
    val size: Long,
    /** The hop between successive window starts; required for `sliding`, ignored for `tumbling`. */
    val slide: Long? = null,
)

/** A `window` cell's assignment strategy (`24-OP-WINDOW-01`). */
@Serializable
enum class WindowKind {
    @SerialName("tumbling") TUMBLING,
    @SerialName("sliding") SLIDING,
}

/**
 * The neutral interest sub-grammar (`42-INTEREST-01`): a small, closed vocabulary
 * mirroring the kernel's `civictech.cell.link.Interest` algebra (spec 40/42) —
 * `total` (every key, the replication setting), `empty` (no key), `slots` (a
 * hash-slot subset out of `total-slots` slots — the partitioning setting when two
 * instances' slot sets are disjoint), or `ranges` (half-open `[lo, hi)` integer
 * ranges over a numeric key). Exactly one of these is expected to be set per
 * instance; the driver resolves precedence `total` > `empty` > `slots` > `ranges`
 * when more than one is present. All fields optional (additive, lenient parse).
 */
@Serializable
data class InterestSpec(
    /** Total interest — every key, every delta (the replication default). */
    val total: Boolean? = null,
    /** Empty interest — no key, no delta. */
    val empty: Boolean? = null,
    /** A hash-slot subset this instance admits, out of [totalSlots] slots. */
    val slots: List<Int>? = null,
    /** The hash-slot space size `slots` is drawn from (required alongside [slots]). */
    @SerialName("total-slots") val totalSlots: Int? = null,
    /** Half-open `[lo, hi)` integer ranges this instance admits, over a numeric key. */
    val ranges: List<List<Long>>? = null,
)

/**
 * One link. [from]/[to] are cell ids; [outlet]/[inlet] name the ports (default
 * `outlet`/`inlet`), [role] selects consume vs observe (23-ownership).
 */
@Serializable
data class LinkSpec(
    val from: String,
    val to: String,
    val inlet: String? = null,
    val outlet: String? = null,
    val role: String? = null,
)

/**
 * The `generator:` block (§1.2 exemplar (f)) — a generative scenario stands up
 * random pipelines from a vocabulary instead of a fixed [Graph]. Harness support
 * lands in W4-C; W0 only freezes the shape so it round-trips.
 */
@Serializable
data class Generator(
    @SerialName("pipeline-depth") val pipelineDepth: List<Int>? = null,
    val vocabulary: List<String> = emptyList(),
    val ops: Int? = null,
    @SerialName("late-joiner") val lateJoiner: Boolean? = null,
    val instances: Int? = null,
)
