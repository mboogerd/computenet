package civictech.oracle.gen

import civictech.cell.CellRef
import civictech.cell.graph.GraphSpec
import civictech.oracle.model.DotOrder
import civictech.oracle.model.SourceId
import java.io.Serializable
import java.util.UUID

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
    /**
     * `[ORA2-GEN-03]`: for a replicated logical cell, the host ordinals its replicas sit on —
     * handle to ordinals, in replica rank order, always at least two distinct ordinals.
     *
     * Empty for every non-replicated case, and additive on purpose: [placement] keeps meaning
     * exactly what it meant (one ordinal per handle, the *primary*), so ORA1 consumers of this
     * type read the same thing they always did.
     */
    val replicaPlacement: Map<String, List<Int>> = emptyMap(),
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
    /**
     * `null` for a non-replicated case; otherwise what this case's replication actually
     * *achieved*, not what it was configured to attempt — see [ReplicationAudit].
     */
    val replication: ReplicationAudit? = null,
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

/**
 * Which replicas a replicated case placed, in **rank order** — the harness half of
 * `[ORA2-MODEL-12]`, supplied at case-construction time.
 *
 * `civictech.oracle.model.DotOrder` breaks a counter tie by instance rank and refuses to invent
 * one. This is where the ranks come from: the generator names the replica [SourceId]s and the
 * writer each one carries, and [DotOrders.of] turns the *live* refs those slices ran on into the
 * order the kernel itself will use. Generation cannot do that last step alone — a `CellRef`'s
 * `instanceId` exists only once the spec has been applied — so the plan carries the identities
 * and the ordering is completed by the harness at wiring time.
 *
 * @property handle the topology handle whose logical cell is replicated.
 * @property replicas one [SourceId] per replica, in the order the generator names them. This is
 *   **not** the dot order: the kernel's is the natural order of the derived dot-source `UUID`s
 *   and is known only after apply. [DotOrders.of] is what reconciles the two.
 * @property writers the `WriterId` each replica's events carry, positionally aligned with
 *   [replicas] — the writer-to-instance mapping `[ORA2-MODEL-12]` calls order-isomorphic. It is
 *   an isomorphism because it is a bijection built here, one writer per replica, and never
 *   re-derived anywhere else.
 * @property hosts the host ordinal each replica sits on, positionally aligned with [replicas];
 *   all distinct.
 */
data class ReplicaPlan(
    val handle: String,
    val replicas: List<SourceId>,
    val writers: List<String>,
    val hosts: List<Int>,
) : Serializable {
    init {
        require(replicas.size >= 2) { "A replica plan names at least two replicas; got ${replicas.size}" }
        require(writers.size == replicas.size) { "One writer per replica: ${writers.size} vs ${replicas.size}" }
        require(hosts.size == replicas.size) { "One host per replica: ${hosts.size} vs ${replicas.size}" }
        require(replicas.distinct().size == replicas.size) { "Duplicate replica source: ${replicas.map { it.id }}" }
        require(hosts.distinct().size == hosts.size) {
            "[ORA2-GEN-03] places replicas on DISTINCT ManagedHosts; got hosts $hosts"
        }
    }
}

/**
 * The dot order a replicated case's model must use, derived from the **kernel's own** identity
 * formula — `[ORA2-MODEL-12]`'s harness half, and the checked-in derivation expectation feature
 * risk 2 decided belongs here rather than in the model.
 *
 * `civictech.oracle.model.DotOrder`'s KDoc states why the model refuses to compute this: deriving
 * `UUID.nameUUIDFromBytes("or-map-tags:<id>:<instanceId>")` inside the model would re-implement
 * the identity a differential run exists to check, and would read a kernel identity the model is
 * forbidden. The harness is the only place that may, because it is the only place that holds the
 * live `CellRef`s.
 *
 * The expectation is pinned against a real `OrMapCell`'s minted dot in
 * `civictech.oracle.tagged.MultiWriterGenerationTest`, not asserted in prose: a KE1 change to the
 * derivation string fails that test loudly instead of silently mis-ordering every counter tie in
 * every replicated sweep.
 */
object DotOrders {

    /** The kernel's dot-source identity for an `OrMapCell` at [ref] (`OrMapCell.kt`, `dotSource`). */
    fun dotSourceOf(ref: CellRef): UUID =
        UUID.nameUUIDFromBytes("or-map-tags:${ref.id}:${ref.instanceId}".toByteArray())

    /**
     * The rank order over [refs]' script slices, sorted by [dotSourceOf] using `UUID`'s own
     * `compareTo` — which is what `TaggedMapDelta.DOT_ORDER` breaks a counter tie with, and which
     * is NOT the lexicographic order of a `UUID`'s `toString()` (it compares two `Long` halves
     * signed). Sorting the identities here, with the kernel's comparator, is exactly the step
     * `DotOrder` refuses to guess at.
     */
    fun of(refs: Map<SourceId, CellRef>): DotOrder {
        require(refs.isNotEmpty()) { "A dot order needs at least one replica ref" }
        return DotOrder.ranked(refs.entries.sortedBy { dotSourceOf(it.value) }.map { it.key })
    }
}

/**
 * What a replicated case's generation actually **achieved** — `[ORA2-GEN-02]`'s reporting half
 * and `[ORA2-GEN-04]`/BS-2's evidence.
 *
 * Every field here is measured off the emitted script, never off the config. That asymmetry is
 * the requirement: a knob says what was asked for, and a sweep that asked for 100% concurrency
 * and realised ~0% is red (D4) — which is only visible if the achieved number exists at all.
 *
 * @property plan which replicas this case placed.
 * @property concurrency the measured concurrency of this case's writes.
 * @property counterTieKeys keys whose CONVERGED state holds two or more live dots sharing a
 *   counter, so the winner is decided only by instance rank (`[24-TMAP-03]`'s `DOT_ORDER`
 *   tie-break). Measured by folding this case's own script through
 *   `civictech.oracle.model.DotModel`, so it reports what the reference will actually see rather
 *   than what the generator hoped to arrange. Empty is a legitimate per-case answer; empty across
 *   a whole default sweep range is BS-2's configuration failure.
 * @property deliveryCount how many gossip deliveries the script states.
 */
data class ReplicationAudit(
    val plan: ReplicaPlan,
    val concurrency: ConcurrencyAudit,
    val counterTieKeys: List<Any?>,
    val deliveryCount: Int,
) : Serializable

/**
 * Configured versus achieved concurrency for one case (`[ORA2-GEN-02]`, D4).
 *
 * A write is **concurrent** when, at the moment it is issued, the issuing replica has not
 * absorbed some other replica's earlier write to the same key — the causal-unorderedness the
 * dot algebra's tie-break exists for. A write is **comparable** when concurrency was *possible*
 * at all: some other replica had already written that key. Measuring the ratio against the
 * comparable writes rather than against every write is what keeps the number honest — a script
 * whose replicas never touch a shared key has no concurrency to achieve, and reporting a low
 * fraction of *all* writes would blame the gossip schedule for the key domain.
 *
 * @property configured `GeneratorConfig.concurrencyRatio`, carried so a report never has to go
 *   looking for the config to interpret [achieved].
 * @property concurrentWrites how many writes were issued genuinely concurrently.
 * @property comparableWrites how many writes could have been.
 * @property totalWrites every keyed write the case emitted, comparable or not.
 */
data class ConcurrencyAudit(
    val configured: Double,
    val concurrentWrites: Int,
    val comparableWrites: Int,
    val totalWrites: Int,
) : Serializable {

    /**
     * The ACHIEVED ratio: [concurrentWrites] over [comparableWrites], or `0.0` when nothing was
     * comparable.
     *
     * Zero-with-no-comparable-writes and zero-with-many are different findings and the
     * denominator is carried so a reader can tell them apart; [shortfall] is the reading D4 acts
     * on.
     */
    val achieved: Double get() = if (comparableWrites == 0) 0.0 else concurrentWrites.toDouble() / comparableWrites

    /**
     * D4's red flag: `true` when concurrency was asked for, was genuinely *possible*, and did not
     * happen — a configured-100%/achieved-~0% sweep, reported rather than passed silently.
     *
     * The `0.1 * configured` floor is a deliberate order-of-magnitude test, not a tolerance: the
     * question D4 poses is "did the knob do anything at all", not "did it hit its target". A
     * sweep achieving half of what it configured is a distribution to look at; one achieving a
     * tenth is a broken knob. Nothing calibrated that constant against a measured distribution —
     * it is a threshold chosen to separate those two readings, and a case whose
     * [comparableWrites] is small is excluded from it entirely for that reason.
     */
    val shortfall: Boolean
        get() = configured > 0.0 && comparableWrites >= MIN_COMPARABLE && achieved < 0.1 * configured

    /** Sum of two audits, for aggregating a sweep. [configured] must match. */
    operator fun plus(other: ConcurrencyAudit): ConcurrencyAudit {
        require(configured == other.configured) {
            "Cannot aggregate concurrency audits configured differently: $configured vs ${other.configured}"
        }
        return ConcurrencyAudit(
            configured = configured,
            concurrentWrites = concurrentWrites + other.concurrentWrites,
            comparableWrites = comparableWrites + other.comparableWrites,
            totalWrites = totalWrites + other.totalWrites,
        )
    }

    override fun toString(): String =
        "ConcurrencyAudit(configured=%.3f achieved=%.3f, %d/%d comparable of %d writes)"
            .format(configured, achieved, concurrentWrites, comparableWrites, totalWrites)

    companion object {
        /** Below this many comparable writes, an achieved ratio is noise and [shortfall] abstains. */
        const val MIN_COMPARABLE: Int = 20

        /** The zero audit, for a case that emitted no keyed write at all. */
        fun none(configured: Double): ConcurrencyAudit = ConcurrencyAudit(configured, 0, 0, 0)
    }
}
