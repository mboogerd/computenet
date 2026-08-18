package civictech.oracle.gen

import civictech.cell.graph.GraphSpec
import civictech.oracle.model.SourceId
import java.io.Serializable

/**
 * One catalog-id-level node of a [CaseTopology]: what operator sits here and where its inputs
 * come from, at the abstraction the generator reasons about before it lowers to a concrete
 * [GraphSpec].
 *
 * @property handle This node's spec-local handle — the same string a lowered
 *   `civictech.cell.graph.SpawnStep.handle` and `ConnectStep.from`/`to` use
 *   (`civictech.cell.graph.GraphDsl`).
 * @property catalogId The `civictech.oracle.bind.OperatorCatalog` id this node instantiates.
 * @property inputs The upstream node handles feeding this node, in the registered
 *   `ShapeRule.inputs` port order — index *i* here is the source connected to
 *   `ShapeRule.inputPorts[i]`.
 * @property source Non-null iff this node has arity 0 (a source node) — the [SourceId] a
 *   [CaseScript] drives events into for this node.
 */
data class TopologyNode(
    val handle: String,
    val catalogId: String,
    val inputs: List<String>,
    val source: SourceId?,
) : Serializable

/**
 * One terminal a generated case observes.
 *
 * @property name A human-readable label for this terminal — not necessarily unique across a
 *   sweep, only within one case.
 * @property handle The [TopologyNode.handle] this terminal reads.
 * @property late `true` for a terminal linked only after a mid-script quiesce [CaseStep.Barrier]
 *   — the late-joiner extension ([ORA1-GEN-09]).
 */
data class TerminalSpec(
    val name: String,
    val handle: String,
    val late: Boolean = false,
) : Serializable

/**
 * A generated case's topology at catalog-id level, one abstraction above the lowered
 * [GraphSpec] it renders to.
 *
 * @property nodes Every operator and source node, keyed by [TopologyNode.handle] within the
 *   list (handles are unique within one topology, enforced by the generator, not by this type).
 * @property terminals Every terminal the case observes.
 * @property placement Handle to host ordinal, for a multi-host case ([ORA1-GEN-10]). Every
 *   handle maps to `0` for a single-host case.
 */
data class CaseTopology(
    val nodes: List<TopologyNode>,
    val terminals: List<TerminalSpec>,
    val placement: Map<String, Int>,
) : Serializable

/**
 * The carrier for one generated remove event's provenance: whether it targeted an element the
 * removing writer had actually added/observed ("observed") or was a deliberately unobserved
 * remove (`GeneratorConfig.unobservedRemoveRatio` — the ScriptGenerator task, computenet-4ru.6,
 * populates this; the type is defined here because [GeneratedCase] carries it as an audit
 * alongside the script it was generated from).
 *
 * @property stepIndex Index into the generating [CaseScript.steps] this record describes —
 *   always the index of a [CaseStep.Op] whose event is a `ScriptEvent.Remove`.
 * @property observed `true` if the removing writer had actually added/observed the removed
 *   element at generation time, `false` if the remove was deliberately generated unobserved.
 */
data class RemoveRecord(
    val stepIndex: Int,
    val observed: Boolean,
) : Serializable

/**
 * One fully generated, replayable case: a deterministic (seed, config) pair rendered down to
 * everything a runner (computenet-4ru.8) needs — the lowered [spec], the drive [script], the
 * catalog-level [topology] it was rendered from, and [removeAudit] recording which removes were
 * generated observed vs. deliberately unobserved.
 *
 * `Serializable` throughout (epic D3): a recorded case crosses a JVM boundary — written to
 * disk, replayed on a second host, or shrunk — without the generator being involved again.
 *
 * @property seed The case's own seed. `(seed, GeneratorConfig)` deterministically produces this
 *   whole case ([ORA1-GEN-01]) — generation itself is this task's sibling's job
 *   (computenet-4ru.6, GraphGenerator/ScriptGenerator), not this type's.
 * @property topology The catalog-id-level shape this case was rendered from.
 * @property spec The lowered, kernel-ready graph (`civictech.cell.graph.GraphSpec`) —
 *   replayable verbatim onto a second host.
 * @property script The total drive order, including any quiesce barriers.
 * @property removeAudit Which generated removes were observed vs. deliberately unobserved.
 */
data class GeneratedCase(
    val seed: Long,
    val topology: CaseTopology,
    val spec: GraphSpec,
    val script: CaseScript,
    val removeAudit: List<RemoveRecord>,
) : Serializable {

    /**
     * The seed a `testkit.SimWorld`/`SimulationController` (or equivalent) replays this case
     * with — a **pure function of [seed] alone** (`[ORA1-GEN-07]`), so identical (seed, config)
     * across two JVMs derives an identical controller seed without either JVM sharing state.
     *
     * The mix is a single splitmix64 step (Steele, Lea & Flood 2014) over [seed]: fast, has no
     * external dependency, and is a well-studied one-round mix — good enough to decorrelate the
     * controller's random stream from any pattern in raw case seeds a sweep might enumerate
     * (e.g. consecutive integers), without needing to be cryptographically strong.
     */
    val controllerSeed: Long
        get() {
            var z = seed + SPLITMIX64_GAMMA
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
            return z xor (z ushr 31)
        }

    private companion object {
        /** splitmix64's golden-ratio increment constant. */
        const val SPLITMIX64_GAMMA: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    }
}
