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
    /** Schedule-sweep run count; defaults to the harness default (20) when null. */
    val runs: Int? = null,
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
    /** Request wave-aligned (glitch-free) semantics on a fan-in cell. */
    @SerialName("glitch-free") val glitchFree: Boolean? = null,
    /** Inlet admission policy, e.g. `single-writer`, `fan-in`. */
    @SerialName("inlet-mode") val inletMode: String? = null,
    /** Host placement (dist profile). */
    val host: String? = null,
    /** Logical replica group this cell is a member of (dist profile). */
    @SerialName("replica-of") val replicaOf: String? = null,
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
