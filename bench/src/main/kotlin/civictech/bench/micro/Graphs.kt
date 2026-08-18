package civictech.bench.micro

import civictech.bench.Drive
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.Aggregators
import civictech.cell.data.op.CoalescingCombineCell
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.LookupJoinCell
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.view.SetView
import civictech.cell.graph.graph
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
import civictech.testkit.awaitDrained
import java.util.UUID

// ---------------------------------------------------------------------------------------
// Micro-benchmark graph fixtures [BEN1-17].
//
// One builder per subject operator, each constructing
//
//     source cell(s) ──> operator under test ──> collector
//
// on a real host, wired through the graph DSL's `link` — i.e. through
// `ManagedHost.connect` -> `LinkAdmission` -> the target-side link handshake, the same
// link/port path an application uses. Deltas enter through a source cell's *inlet*, as a
// hosted invocation on the host queue; the operator's own methods are never called
// directly. That is the whole point of the fixture: a micro-benchmark that called
// `filterCell.onInlet(delta)` would measure a method, not a dataflow graph, and would be
// silently insensitive to everything the port/link/dispatch layer costs.
//
// The per-operator kernel tests (UnionSetCellTest, QuorumSetCellTest, ...) are the
// construction reference and nothing more. Their DRIVE discipline is deliberately NOT
// copied: they exercise unhosted cells on the caller's thread, where "has it settled?"
// is answered by the call having returned. Here the graph is hosted, so quiescence is a
// real question with two real answers — see [Drive].
// ---------------------------------------------------------------------------------------

// -----------------------------------------------------------------------------------
// WHAT [Drive] MEANS HERE — and why it is F3's `civictech.bench.Drive` rather than a
// second enum of the same name and the same two constants.
//
// - Drive.SIM  — testkit's [SimWorld] over `SimulationController`: single-threaded and
//   deterministic, drained with `runToIdle` under a hard step budget. Quiescence is a
//   fact (no scheduler has work), not an estimate.
// - Drive.REAL — a [ManagedHost] on a [VirtualThreadScheduler], drained with testkit's
//   `HostScheduler.awaitDrained` fence. Read that helper's KDoc before reaching for a
//   poll instead: two equal samples of an observable value mean "nothing changed during
//   this window", which a *starved* host produces exactly as readily as a converged one.
//   The fence submits one task below every band the host uses and waits for it, so its
//   completion is positive evidence the queue emptied.
//
// The two exist side by side because they measure different things: SIM isolates the
// operator's own work from thread hand-off and queue contention; REAL is what a deployed
// host actually costs. A number from one is not a number from the other, and a result
// that does not say which drive produced it says nothing — which is exactly what
// `BenchResult.drive` is for [BEN1-26], and exactly why this fixture reuses that type
// instead of declaring its own. The regime a graph was DRIVEN under and the regime a
// recorded result CLAIMS are one fact, so `BenchResult(drive = graph.drive, …)` is a
// pass-through; a second enum would put a hand-written two-constant `when` on the only
// path from fixture to findings entry, where transposing the arms compiles cleanly and
// mislabels every row it ever writes.
// -----------------------------------------------------------------------------------

/**
 * Whether [Graphs.build] actually connects the graph it constructs.
 *
 * [UNLINKED] is the fixture's **negative control**, and it is load-bearing rather than
 * decorative. Every other guarantee here — arrival counts, membership folds — is read at
 * the collector, so a builder that returned a perfectly well-formed graph whose links
 * were never established would report `arrivals == 0`, `live == 0` and *still construct,
 * apply and quiesce without throwing*. A test suite that only asserts "it built" cannot
 * tell that apart from a working fixture. [UNLINKED] makes the difference observable and
 * asserted on every run, instead of resting on a one-off mutation somebody ran once.
 */
enum class Wiring { LINKED, UNLINKED }

/** The payload the subject's collector folds — one collector cell per shape. */
enum class Readout {
    /** `SetDelta<Int>`: folded through the kernel's own [SetView]. */
    SET,

    /** `CounterDelta`: summed. */
    COUNTER,

    /** `MapDelta<Int, Int>`: per-element live-source counts. */
    PRESENCE_MAP,

    /** `MapDelta<Int, Long>`: a keyed scalar — group-by count/sum/min/max, the lookup join's enriched map, the combine output. */
    LONG_MAP,

    /** `MapDelta<Int, List<Long>>`: `topK`'s per-group ranking, the one aggregate whose value is not a scalar. */
    TOP_K_MAP,
}

/**
 * Which operator family a subject belongs to.
 *
 * This exists because [Subject.Companion.setShaped] used to be `values()`, which was a
 * true name only while every declared subject *was* set-shaped. Now that it is not, the
 * predicate is split here rather than frozen into a hand-written list: `family` is a
 * required constructor parameter, so a newly declared subject cannot avoid classifying
 * itself, and [Subject.Companion.all] still returns everything — which is exactly what
 * `GraphsExtendedTest` asserts the per-family loops jointly cover, so no declared
 * subject can escape propagation testing by being added to neither.
 */
enum class Family {
    /** The unary / fan-in tagged-set operators of the create task. */
    SET_SHAPED,

    /** Binary relational joins: `JoinSetCell`, `SemiJoinCell`, `LookupJoinCell`. */
    JOIN,

    /** Grouped aggregation: `GroupByCell` x `Aggregators` (see the named omission on `MergeableGroupByCell`). */
    GROUPED,

    /** Keyed / counter combines: `CombineLatestCell`, `CoalescingCombineCell`. */
    COMBINE,
}

/**
 * The set-shaped operator family — 8 of BEN1's 15 subjects.
 *
 * The sibling extension task adds the join/group-by/combine subjects
 * (`JoinSetCell`, `LookupJoinCell`, `SemiJoinCell`, `GroupByCell`,
 * `MergeableGroupByCell`, `CombineLatestCell`, `CoalescingCombineCell` and the
 * `Aggregator` variants) as further constants here. Two seams make that an
 * extension rather than a reshape:
 *
 *  - [Graphs.build]'s body is a `when` **expression** over this enum, so a new constant
 *    without a branch is a compile error, never a silently unbuildable subject;
 *  - [referenceLive] is stated per subject as the operator's *definition*, so a new
 *    subject brings its own oracle with it.
 *
 * @param sources how many source cells feed the operator — every source receives every
 *   delta of an applied batch (see [MicroGraph.applyBatch]).
 * @param readout the payload shape the collector folds.
 * @param family which operator family the subject belongs to; see [Family].
 */
enum class Subject(val sources: Int, val readout: Readout, val family: Family) {

    /**
     * The shared unary skeleton, `civictech.cell.data.op.TaggedSetOperator`.
     *
     * **Measured through `FilterCell { true }`, and the limitation is real.**
     * `TaggedSetOperator` is not a `Cell`: it is the composed ledger + emit-or-absorb
     * skeleton that `FilterCell`, `UnionSetCell`, `FlatMapSetCell` and `CountCell` each
     * hold as a field, and its `state` is `internal` to `:kernel`, so `:bench` cannot
     * drive one directly at all. An always-true filter is the thinnest kernel cell whose
     * entire body *is* the skeleton — `TagState.apply` plus `emitOrAbsorb`, with an
     * identity transform in front. So this subject measures the skeleton **plus one
     * always-true predicate call per element**, not the skeleton alone, and the honest
     * use of its number is as the identity baseline that [FILTER]'s own predicate cost is
     * the difference from. It is not a measurement of `TaggedSetOperator` in isolation,
     * and no result derived from it should be reported as one.
     */
    TAGGED_SET(sources = 1, readout = Readout.SET, family = Family.SET_SHAPED),

    /** `FilterCell` with an even-element predicate: half the stream is absorbed. */
    FILTER(sources = 1, readout = Readout.SET, family = Family.SET_SHAPED),

    /** `UnionSetCell` — the retaining (tombstone-keeping) tagged-set operator. */
    UNION(sources = 1, readout = Readout.SET, family = Family.SET_SHAPED),

    /** `IntersectSetCell`, both arms fed the same deltas, so the intersection is the whole set. */
    INTERSECT(sources = 2, readout = Readout.SET, family = Family.SET_SHAPED),

    /** `CountCell` — the one subject whose output is a `CounterDelta`, not a `SetDelta`. */
    COUNT(sources = 1, readout = Readout.COUNTER, family = Family.SET_SHAPED),

    /** `FlatMapSetCell` expanding each element to two distinct outputs. */
    FLAT_MAP(sources = 1, readout = Readout.SET, family = Family.SET_SHAPED),

    /** `PresenceCountCell` over a two-lane fan-in; output is `MapDelta<Int, Int>`. */
    PRESENCE_COUNT(sources = 2, readout = Readout.PRESENCE_MAP, family = Family.SET_SHAPED),

    /** `QuorumSetCell` at the intersection threshold (`n -> n`) over a two-lane fan-in. */
    QUORUM(sources = 2, readout = Readout.SET, family = Family.SET_SHAPED),

    /**
     * `JoinSetCell` equi-joining two tagged set streams on the element itself.
     *
     * Both arms receive the same elements (see [MicroGraph.applyBatch]), so each key
     * matches exactly one row per side and the join emits one pair per element, combined
     * back down to that element. That is deliberately the *one-to-one* shape: it keeps
     * the oracle a set identity while still driving `KeyedBinarySetJoin` plus the minted
     * pair ledger, and a retraction still has to kill a pair and remove its minted tag.
     */
    JOIN_SET(sources = 2, readout = Readout.SET, family = Family.JOIN),

    /** `SemiJoinCell` (non-negated): a left row survives iff its key is live on the right. */
    SEMI_JOIN(sources = 2, readout = Readout.SET, family = Family.JOIN),

    /**
     * `LookupJoinCell` enriching a fact map with a dimension map on an identity foreign key.
     *
     * Both map sources carry `key -> key.toLong()`, and `combine` returns `null` when the
     * dimension row is absent — inner-join semantics, so a fact that arrives before its
     * dimension emits nothing until the dimension lands, and a dimension retraction
     * removes the enriched key again.
     */
    LOOKUP_JOIN(sources = 2, readout = Readout.LONG_MAP, family = Family.JOIN),

    /** `GroupByCell` + `Aggregators.count()` — the invertible-accumulator baseline. */
    GROUP_BY_COUNT(sources = 1, readout = Readout.LONG_MAP, family = Family.GROUPED),

    /** `GroupByCell` + `Aggregators.sumOf` — invertible, one selector call per membership flip. */
    GROUP_BY_SUM(sources = 1, readout = Readout.LONG_MAP, family = Family.GROUPED),

    /**
     * `GroupByCell` + `Aggregators.minOf` — the **non-invertible** accumulator, whose
     * retraction path is the point of this subject.
     *
     * `Aggregators.Support` keeps the full value multiset in a `TreeMap`, so a retraction
     * is a `TreeMap` lookup-and-decrement (and a key removal at multiplicity 1) rather
     * than an arithmetic undo, and the extremum reshuffles without a re-scan. A benchmark
     * that only ever inserted would never touch that path at all, which is why the
     * fixture's per-subject test always applies the covering retract batch.
     */
    GROUP_BY_MIN(sources = 1, readout = Readout.LONG_MAP, family = Family.GROUPED),

    /** `GroupByCell` + `Aggregators.maxOf` — the same `TreeMap` support as [GROUP_BY_MIN], read from the other end. */
    GROUP_BY_MAX(sources = 1, readout = Readout.LONG_MAP, family = Family.GROUPED),

    /**
     * `GroupByCell` + `Aggregators.topK` — `TreeMap` support again, but with a
     * `value()` that walks the descending map on every emission rather than reading one
     * key, so its per-delta cost grows with [TOP_K] where the extremum aggregators' does not.
     */
    GROUP_BY_TOP_K(sources = 1, readout = Readout.TOP_K_MAP, family = Family.GROUPED),

    // ------------------------------------------------------------------------------
    // NAMED OMISSION: `MergeableGroupByCell` has no subject here, and cannot.
    //
    // It declares `Replicable`, which makes CP-F2's marker scan stamp its ports
    // `MergeClass.IDEMPOTENT` on `MERGE_IDEMPOTENCE`. A source cell offering the default
    // `NON_IDEMPOTENT` is then refused at link time by CP-F3, verbatim:
    //
    //   link source-0.outlet -> op.inlet rejected: nature mismatch on MERGE_IDEMPOTENCE:
    //   producer offers NON_IDEMPOTENT, consumer requires IDEMPOTENT (no adapter --
    //   typed refusal, CP-F3)
    //
    // That refusal is the kernel working, not a fixture bug: the cell's legitimate
    // producers are replication peers gossiping aggregate deltas into `deltaInlet`, and a
    // declared nature comes from a generated contract descriptor, which `:bench` has no
    // KSP to produce. The two ways to get a subject anyway would both be dishonest — a
    // hand-registered runtime nature would fake authoritative descriptor metadata
    // (AGENTS.md), and substituting `GroupByCell` would file a number about one cell under
    // another's name. Its retraction story is separately empty in this fixture's terms:
    // `onLocal` folds `adds` and ignores `dels`, because element-level retraction belongs
    // to GROUP_BY_COUNT's non-replicated `GroupByCell`. So the merge-only group-by is
    // *unmeasured* by BEN1's micro fixtures, and no result may claim otherwise.
    // ------------------------------------------------------------------------------


    /** `CombineLatestCell` (ungated default) over two map streams; `combine` drops keys held by only one side. */
    COMBINE_LATEST(sources = 2, readout = Readout.LONG_MAP, family = Family.COMBINE),

    /**
     * `CoalescingCombineCell` over a single counter arm.
     *
     * One source, not two, and deliberately: this cell coalesces the arms *of one wave*
     * and gates on the expected-sibling set of that wave. Two independent source cells
     * are two independent roots minting two unrelated waves, which is the phantom-
     * expected-edge topology its own KDoc warns against rather than the fork it is built
     * for. A single arm keeps the subject honest — it measures the fold and the flush,
     * not a fan-in this fixture cannot construct legitimately.
     */
    COALESCING_COMBINE(sources = 1, readout = Readout.COUNTER, family = Family.COMBINE),
    ;

    /**
     * What the collector *should* observe as live membership once [elements] have been
     * inserted and nothing retracted — the operator's definition, written independently
     * of the kernel implementation and of the collector fold it is compared against.
     *
     * This is the fixture's oracle. Deriving it from anything the graph computes would
     * make every per-subject test tautological.
     */
    fun referenceLive(elements: Set<Int>, groups: Int = DEFAULT_GROUPS): Int = when (this) {
        TAGGED_SET, UNION, INTERSECT, COUNT, PRESENCE_COUNT, QUORUM -> elements.size
        FILTER -> elements.count { PASSES_FILTER(it) }
        FLAT_MAP -> elements.size * FLAT_MAP_FANOUT

        // Joins and combines: both arms carry the same elements, so every key matches and
        // the output has one live entry per input element.
        JOIN_SET, SEMI_JOIN, LOOKUP_JOIN, COMBINE_LATEST -> elements.size

        // Grouped: the observable is the *group* map, so live membership is the number of
        // distinct keys the elements fall into — not how many elements there are.
        GROUP_BY_COUNT, GROUP_BY_SUM, GROUP_BY_MIN, GROUP_BY_MAX, GROUP_BY_TOP_K,
        -> elements.mapTo(HashSet()) { groupKey(it, groups) }.size

        // One counter arm, one +1 per inserted element.
        COALESCING_COMBINE -> elements.size
    }

    /**
     * The per-group aggregate the collector *should* hold for [GROUP_BY_COUNT],
     * [GROUP_BY_SUM], [GROUP_BY_MIN] and [GROUP_BY_MAX], stated from the aggregator
     * definitions rather than read back off the graph.
     *
     * [referenceLive] only counts live groups, which a graph that grouped correctly and
     * aggregated wrongly would still satisfy. This is the assertion that separates them.
     */
    fun referenceAggregate(elements: Set<Int>, groups: Int = DEFAULT_GROUPS): Map<Int, Long> {
        val byGroup = elements.groupBy { groupKey(it, groups) }
        return when (this) {
            GROUP_BY_COUNT -> byGroup.mapValues { (_, es) -> es.size.toLong() }
            GROUP_BY_SUM -> byGroup.mapValues { (_, es) -> es.sumOf { it.toLong() } }
            GROUP_BY_MIN -> byGroup.mapValues { (_, es) -> es.minOf { it.toLong() } }
            GROUP_BY_MAX -> byGroup.mapValues { (_, es) -> es.maxOf { it.toLong() } }
            else -> error("$this has no scalar per-group aggregate")
        }
    }

    /** [GROUP_BY_TOP_K]'s oracle: the [TOP_K] largest selected values per group, descending. */
    fun referenceTopK(elements: Set<Int>, groups: Int = DEFAULT_GROUPS): Map<Int, List<Long>> {
        require(this == GROUP_BY_TOP_K) { "$this is not the topK subject" }
        return elements.groupBy { groupKey(it, groups) }
            .mapValues { (_, es) -> es.map { it.toLong() }.sortedDescending().take(TOP_K) }
    }

    companion object {
        /**
         * The tagged-set operator family — the create task's eight.
         *
         * It used to be `values()`, with a note telling the extension task to split it
         * rather than freeze an enumeration once a non-set-shaped subject existed. This
         * is that split, and it is still not a hand-written list: it filters on
         * [Family], a *required* constructor parameter, so a newly declared subject
         * cannot dodge classification. [all] remains everything, and
         * `GraphsExtendedTest` asserts `setShaped() + extended() == all()`, so a subject
         * that landed in neither family loop fails the build instead of going untested.
         */
        fun setShaped(): List<Subject> = values().filter { it.family == Family.SET_SHAPED }

        /** The join / grouped / combine subjects this task adds — the complement of [setShaped]. */
        fun extended(): List<Subject> = values().filter { it.family != Family.SET_SHAPED }

        /** Every declared subject. */
        fun all(): List<Subject> = values().toList()

        /** [FILTER]'s predicate, shared with [referenceLive] so the two cannot drift. */
        val PASSES_FILTER: (Int) -> Boolean = { it % 2 == 0 }

        /** [FLAT_MAP]'s expansion: each element becomes this many distinct outputs. */
        const val FLAT_MAP_FANOUT: Int = 2

        /**
         * Default number of groups the grouped subjects fold into.
         *
         * A *parameter* of [Graphs.build] rather than a constant baked into the graph,
         * because it is the fixture's one control over per-group accumulator size: with
         * N elements and G groups each `TreeMap` support holds N/G values, so `groups`
         * is the dial that separates "the aggregator's own retraction cost" from "the
         * tag map's growth", which the feature names as the suspect for dominating the
         * set-shaped numbers. Hiding it would make that question unaskable.
         */
        const val DEFAULT_GROUPS: Int = 8

        /** [GROUP_BY_TOP_K]'s k, shared with [referenceTopK]. */
        const val TOP_K: Int = 2

        /**
         * The grouped subjects' key function, shared by the builders and the oracles so
         * the two cannot drift. Elements come from a strictly increasing counter, so
         * `element % groups` spreads them evenly and deterministically.
         */
        fun groupKey(element: Int, groups: Int): Int = element % groups
    }
}

/**
 * What a collector cell exposes, uniformly, whatever payload it folds.
 *
 * **Reading these is safe only after quiescence**, and the reason is worth stating: the
 * fields are written by the host's single drain thread and read by the caller's. Under
 * [Drive.SIM] there is one thread and the question does not arise. Under [Drive.REAL],
 * `awaitDrained`'s latch supplies the happens-before edge — every task the host ran
 * precedes the fence task, whose `countDown()` precedes the caller's `await()` return —
 * so a read *after* [MicroGraph.quiesce] sees every write. A read taken mid-flight is
 * racy and meaningless; the API offers no way to want one.
 */
interface Collected {
    /** Number of deltas that arrived at the collector's inlet. */
    val arrivals: Long

    /** Live membership the collector observes, folded from the arrivals it actually got. */
    val live: Int
}

/**
 * The source cell: a delta arrives on its inlet as a hosted invocation and is
 * re-originated verbatim on its outlet.
 *
 * Deliberately not `civictech.cell.data.SetCell`, though that cell has a `deltaInlet`
 * that would accept these batches. `SetCell` is a full OR-set — it holds tag maps under
 * a lock, mints its own tags and folds a delivered frontier — and every bit of that
 * would land inside the measured region while measuring nothing about the operator under
 * test. This forwards and does nothing else, so what a benchmark sees is the host queue,
 * the link, and the operator.
 */
class DeltaSourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Int>>>())

    init {
        // originate, not a plain reactive emit: each applied delta is its own wave, so a
        // batch is N waves rather than one long-lived context that would let downstream
        // wave-completeness machinery coalesce work the benchmark meant to count.
        inlet.onEach { delta -> outlet.originate { propagate(delta) } }
    }
}

/**
 * Source cell for the map-shaped operators: takes the same `SetDelta<Int>` batch every
 * other source takes and re-originates it as the `MapDelta<Int, Long>` those operators
 * serve, through [Deltas.asMap].
 *
 * The alternative — a second batch type and a second generator family — was rejected on
 * purpose. One batch shape means [MicroGraph.applyBatch] stays one method, the JMH task
 * drives every subject through one call, and a keyed subject's input is *provably* the
 * same seeded stream as a set-shaped subject's, so the two are comparable. The price is
 * one map construction per delta inside the measured region, which is stated here rather
 * than hidden: it is small beside a hosted invocation, but it is not nothing, and a
 * result comparing a map-shaped subject against a set-shaped one carries it.
 */
class MapSourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<Int, Long>>>())

    init {
        inlet.onEach { delta -> outlet.originate { propagate(Deltas.asMap(delta)) } }
    }
}

/**
 * Source cell for [Subject.COALESCING_COMBINE]: the same batch, re-originated as the
 * `CounterDelta` that cell's arms carry, through [Deltas.asCounter]. See [MapSourceCell]
 * for why the adaptation lives in a source cell rather than in a second batch type.
 */
class CounterSourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

    init {
        inlet.onEach { delta -> outlet.originate { propagate(Deltas.asCounter(delta)) } }
    }
}

/**
 * The interface a hosted-proxy lookup reflects over to reach [DeltaSourceCell.inlet].
 * `ManagedHost.lookup` builds a dynamic proxy from a port-typed getter, so this
 * one-property interface is the whole handle a caller needs.
 */
interface DeltaSourceApi {
    val inlet: Use<Propagate<SetDelta<Int>>>
}

/** Collector for `SetDelta<Int>` outputs, folding membership through the kernel's own [SetView]. */
class SetCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
    private val view = SetView<Int>()
    private var count = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = view.size

    init {
        inlet.onEach { delta ->
            view.apply(delta)
            count++
        }
    }
}

/** Collector for `CounterDelta` outputs ([Subject.COUNT]); live membership is the running sum. */
class CounterCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())
    private var count = 0L
    private var sum = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = sum.toInt()

    init {
        inlet.onEach { delta ->
            sum += delta.amount
            count++
        }
    }
}

/**
 * Collector for `MapDelta<Int, Int>` outputs ([Subject.PRESENCE_COUNT]); live membership
 * is the number of elements currently carrying a non-zero live-source count, which is
 * exactly the map's size once removals are applied (`PresenceCountCell` emits a removal
 * when a count drops to 0).
 */
class PresenceCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, Int>>>())
    private val counts = LinkedHashMap<Int, Int>()
    private var count = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = counts.size

    init {
        inlet.onEach { delta ->
            counts.putAll(delta.puts)
            delta.removals.forEach { counts.remove(it) }
            count++
        }
    }
}

/**
 * Collector for `MapDelta<Int, Long>` outputs — every keyed scalar subject
 * ([Readout.LONG_MAP]): the scalar group-by aggregates, the lookup join's enriched map
 * and the combine output.
 *
 * Separate from [PresenceCollectorCell] rather than a generic map collector because
 * `FanInlet.create` takes a **reified** type argument, so a `MapCollectorCell<A>` could
 * not register its own port; and separate from the `MapDelta<Int, Int>` presence fold
 * because `link` matches payload types exactly. Two concrete classes cost a duplicated
 * six-line fold and buy no unchecked casts on the link path.
 */
class LongMapCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, Long>>>())
    private val values = LinkedHashMap<Int, Long>()
    private var count = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = values.size

    /** The folded map itself — the aggregate oracle's comparand. Read after quiescence, like [live]. */
    fun values(): Map<Int, Long> = LinkedHashMap(values)

    init {
        inlet.onEach { delta ->
            values.putAll(delta.puts)
            delta.removals.forEach { values.remove(it) }
            count++
        }
    }
}

/** Collector for [Subject.GROUP_BY_TOP_K]'s `MapDelta<Int, List<Long>>` — see [LongMapCollectorCell]. */
class TopKCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Collected {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, List<Long>>>>())
    private val values = LinkedHashMap<Int, List<Long>>()
    private var count = 0L

    override val arrivals: Long get() = count
    override val live: Int get() = values.size

    /** The folded rankings — the topK oracle's comparand. Read after quiescence. */
    fun values(): Map<Int, List<Long>> = LinkedHashMap(values)

    init {
        inlet.onEach { delta ->
            values.putAll(delta.puts)
            delta.removals.forEach { values.remove(it) }
            count++
        }
    }
}

/**
 * One built, hosted, drivable operator graph — the single API every BEN1 micro-benchmark
 * and every fixture test goes through.
 *
 * Lifecycle: [Graphs.build] → repeated ([applyBatch] → [quiesce]) → read [arrivals] /
 * [live] → [close]. A graph is single-threaded from the caller's side: apply and drain
 * from one thread (under [Drive.SIM] this is mandatory — the simulation controller's
 * determinism *is* its single-threadedness).
 */
class MicroGraph internal constructor(
    val subject: Subject,
    val drive: Drive,
    val wiring: Wiring,
    /** Group cardinality this graph was built with; meaningful only for [Family.GROUPED] subjects. */
    val groups: Int,
    private val sourceInlets: List<Use<Propagate<SetDelta<Int>>>>,
    private val collected: Collected,
    private val drain: () -> Unit,
    private val stop: () -> Unit,
) : AutoCloseable {

    /** How many source cells feed the operator; equals `subject.sources`. */
    val sourceCount: Int get() = sourceInlets.size

    /**
     * The collector cell itself, for the subjects whose observable is richer than an
     * element count — [LongMapCollectorCell] and [TopKCollectorCell] expose the folded
     * map their aggregate oracle is compared against. Same rule as [live]: read after
     * [quiesce].
     */
    val collector: Collected get() = collected

    /** Deltas observed at the collector. Read after [quiesce] — see [Collected]. */
    val arrivals: Long get() = collected.arrivals

    /** Live membership observed at the collector. Read after [quiesce] — see [Collected]. */
    val live: Int get() = collected.live

    /**
     * Apply a pre-generated batch: every delta is pushed into **every** source inlet, in
     * batch order, as a hosted invocation. So a batch of N deltas on a two-source subject
     * costs 2N host invocations — the multi-source subjects genuinely do more work per
     * batch, and a result comparing them across arities has to say so.
     *
     * Returns without waiting: the deltas are queued, not necessarily processed. Call
     * [quiesce] before reading anything.
     */
    fun applyBatch(batch: DeltaBatch) {
        batch.deltas.forEach { delta ->
            sourceInlets.forEach { it.call.propagate(delta) }
        }
    }

    /** Drive the host to quiescence: `runToIdle` under [Drive.SIM], the drain fence under [Drive.REAL]. */
    fun quiesce() = drain()

    /** [applyBatch] then [quiesce] — the shape a measured benchmark body uses. */
    fun applyAndQuiesce(batch: DeltaBatch) {
        applyBatch(batch)
        quiesce()
    }

    /** Release the drive's resources (stops the [Drive.REAL] scheduler thread; a no-op under [Drive.SIM]). */
    override fun close() = stop()
}

/**
 * Which payload a subject's source cells originate.
 *
 * Every source *inlet* takes the same `SetDelta<Int>` batch — that is what keeps
 * [MicroGraph.applyBatch] one method across every subject — but the map- and
 * counter-shaped operators serve a different payload, so their sources re-originate the
 * batch through [Deltas.asMap] / [Deltas.asCounter]. [sourceShape] is an exhaustive
 * `when`, so a newly declared subject has to state its shape or the file does not
 * compile.
 */
internal enum class SourceShape { SET, MAP, COUNTER }

internal fun sourceShape(subject: Subject): SourceShape = when (subject) {
    Subject.TAGGED_SET, Subject.FILTER, Subject.UNION, Subject.INTERSECT, Subject.COUNT,
    Subject.FLAT_MAP, Subject.PRESENCE_COUNT, Subject.QUORUM,
    Subject.JOIN_SET, Subject.SEMI_JOIN,
    Subject.GROUP_BY_COUNT, Subject.GROUP_BY_SUM, Subject.GROUP_BY_MIN, Subject.GROUP_BY_MAX,
    Subject.GROUP_BY_TOP_K,
    -> SourceShape.SET

    Subject.LOOKUP_JOIN, Subject.COMBINE_LATEST -> SourceShape.MAP

    Subject.COALESCING_COMBINE -> SourceShape.COUNTER
}

/** Builders for the subject graphs [BEN1-17]. */
object Graphs {

    /** A host plus the two things only its drive knows: how to settle it, and how to stop it. */
    private class Rig(val host: ManagedHost, val drain: () -> Unit, val stop: () -> Unit)

    private fun rig(subject: Subject, drive: Drive): Rig = when (drive) {
        Drive.SIM -> {
            // No seed: one host, so there is nothing for the controller's cross-host RNG
            // to choose between — the drain order is fixed either way.
            val world = SimWorld()
            Rig(world.host, { world.runToIdle() }, { })
        }

        Drive.REAL -> {
            val scheduler = VirtualThreadScheduler("bench-micro-${subject.name.lowercase()}")
            val host = ManagedHost(scheduler = scheduler)
            Rig(host, { scheduler.awaitDrained("micro graph ${subject.name}") }, { scheduler.shutdown() })
        }
    }

    /**
     * Build [subject]'s graph on a host driven by [drive].
     *
     * The returned graph is already quiescent: spawning and linking have been driven to
     * completion, so the first [MicroGraph.applyBatch] measures delta flow and not
     * construction.
     */
    fun build(
        subject: Subject,
        drive: Drive,
        wiring: Wiring = Wiring.LINKED,
        groups: Int = Subject.DEFAULT_GROUPS,
    ): MicroGraph {
        require(groups >= 1) { "groups must be >= 1, was $groups" }
        val rig = rig(subject, drive)
        val host = rig.host

        lateinit var sourceRefs: List<CellRef>
        lateinit var collected: Collected

        graph(host.managementInlet) {
            // Link only under LINKED. Everything else about the graph — the cells, their
            // refs, the host, the drive — is identical, which is what makes UNLINKED a
            // control for the links specifically rather than for construction in general.
            fun <A> wire(out: Subscribe<A>, inn: Serve<A>) {
                if (wiring == Wiring.LINKED) link(out, inn)
            }

            // Exactly one of the three lists is non-empty — `sourceShape` is an
            // exhaustive `when` over the enum, so a new subject must say which payload
            // its sources originate or the file does not compile. Whichever list is
            // built assigns `sourceRefs`, and those are the inlets `applyBatch` drives;
            // every shape's inlet takes the same `SetDelta<Int>` batch, which is what
            // keeps one batch type and one apply call across all 19 subjects.
            val shape = sourceShape(subject)
            fun <C : Cell> sources(make: (CellRef) -> C) =
                (0 until subject.sources)
                    .map { i -> spawn("source-$i") { ref -> make(ref) } }
                    .also { spawned -> sourceRefs = spawned.map { it.ref } }

            val srcs = if (shape == SourceShape.SET) sources { DeltaSourceCell(it) } else emptyList()
            val mapSrcs = if (shape == SourceShape.MAP) sources { MapSourceCell(it) } else emptyList()
            val counterSrcs = if (shape == SourceShape.COUNTER) sources { CounterSourceCell(it) } else emptyList()

            // A `when` EXPRESSION over the enum: the extension task cannot add a subject
            // without adding its branch here, because the compiler will not let it.
            collected = when (subject) {
                Subject.TAGGED_SET -> {
                    val op = spawn("op") { ref -> FilterCell<Int>(ref = ref, predicate = { true }) }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.FILTER -> {
                    val op = spawn("op") { ref ->
                        FilterCell(ref = ref, predicate = Subject.PASSES_FILTER)
                    }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.UNION -> {
                    val op = spawn("op") { ref -> UnionSetCell<Int>(ref) }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.INTERSECT -> {
                    val op = spawn("op") { ref -> IntersectSetCell<Int>(ref) }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.left)
                    wire(srcs[1].cell.outlet, op.cell.right)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.COUNT -> {
                    val op = spawn("op") { ref -> CountCell<Int>(ref) }
                    val out = spawn("collector") { ref -> CounterCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.FLAT_MAP -> {
                    val op = spawn("op") { ref ->
                        // Two distinct outputs per input: `2a` and `2a+1` never collide
                        // across distinct `a`, so the expansion is exactly FLAT_MAP_FANOUT
                        // and the oracle stays a multiplication.
                        FlatMapSetCell(ref = ref, f = { a: Int -> listOf(2 * a, 2 * a + 1) })
                    }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.PRESENCE_COUNT -> {
                    val op = spawn("op") { ref -> PresenceCountCell<Int>(ref) }
                    val out = spawn("collector") { ref -> PresenceCollectorCell(ref) }
                    // Both sources land on the one fan-in inlet: two links, two lanes.
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(srcs[1].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.QUORUM -> {
                    // Intersection threshold: every live source must assert the element.
                    val op = spawn("op") { ref -> QuorumSetCell<Int>(ref) { n -> n } }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(srcs[1].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.JOIN_SET -> {
                    // Identity keys on both arms, and `combine` keeps the left row: with
                    // both arms carrying the same elements this is one pair per key, so
                    // the oracle is a set identity while the pair ledger still mints and
                    // retires a tag per pair.
                    val op = spawn("op") { ref ->
                        JoinSetCell<Int, Int, Int, Int>(
                            ref = ref,
                            leftKey = { it },
                            rightKey = { it },
                            combine = { a, _ -> a },
                        )
                    }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.left)
                    wire(srcs[1].cell.outlet, op.cell.right)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.SEMI_JOIN -> {
                    val op = spawn("op") { ref ->
                        SemiJoinCell<Int, Int, Int>(ref = ref, leftKey = { it }, rightKey = { it })
                    }
                    val out = spawn("collector") { ref -> SetCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.left)
                    wire(srcs[1].cell.outlet, op.cell.right)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.LOOKUP_JOIN -> {
                    val op = spawn("op") { ref ->
                        LookupJoinCell<Int, Long, Int, Long, Long>(
                            ref = ref,
                            fk = { k -> k },
                            // Inner join: no dimension row, no output row. So a fact that
                            // lands before its dimension emits nothing, and retracting
                            // either side removes the enriched key again.
                            combine = { _, v, d -> d?.let { v + it } },
                        )
                    }
                    val out = spawn("collector") { ref -> LongMapCollectorCell(ref) }
                    wire(mapSrcs[0].cell.outlet, op.cell.fact)
                    wire(mapSrcs[1].cell.outlet, op.cell.dimension)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                // The four scalar aggregators differ only in the `aggregator` argument —
                // count and sum are invertible, min and max are the TreeMap support
                // multiset whose retraction path this task exists to reach. The branches
                // are spelled out rather than folded into a helper because the helper
                // would have to be generic in ACC and take `spawn`/`link` as function
                // references out of the graph DSL's receiver, which is more machinery
                // than the four lines it would save.
                Subject.GROUP_BY_COUNT -> {
                    val op = spawn("op") { ref ->
                        GroupByCell(
                            ref = ref,
                            keyFn = { e: Int -> Subject.groupKey(e, groups) },
                            aggregator = Aggregators.count(),
                        )
                    }
                    val out = spawn("collector") { ref -> LongMapCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.GROUP_BY_SUM -> {
                    val op = spawn("op") { ref ->
                        GroupByCell(
                            ref = ref,
                            keyFn = { e: Int -> Subject.groupKey(e, groups) },
                            aggregator = Aggregators.sumOf { e: Int -> e.toLong() },
                        )
                    }
                    val out = spawn("collector") { ref -> LongMapCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.GROUP_BY_MIN -> {
                    val op = spawn("op") { ref ->
                        GroupByCell(
                            ref = ref,
                            keyFn = { e: Int -> Subject.groupKey(e, groups) },
                            aggregator = Aggregators.minOf { e: Int -> e.toLong() },
                        )
                    }
                    val out = spawn("collector") { ref -> LongMapCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.GROUP_BY_MAX -> {
                    val op = spawn("op") { ref ->
                        GroupByCell(
                            ref = ref,
                            keyFn = { e: Int -> Subject.groupKey(e, groups) },
                            aggregator = Aggregators.maxOf { e: Int -> e.toLong() },
                        )
                    }
                    val out = spawn("collector") { ref -> LongMapCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.GROUP_BY_TOP_K -> {
                    val op = spawn("op") { ref ->
                        GroupByCell(
                            ref = ref,
                            keyFn = { e: Int -> Subject.groupKey(e, groups) },
                            aggregator = Aggregators.topK(Subject.TOP_K) { e: Int -> e.toLong() },
                        )
                    }
                    val out = spawn("collector") { ref -> TopKCollectorCell(ref) }
                    wire(srcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.COMBINE_LATEST -> {
                    val op = spawn("op") { ref ->
                        CombineLatestCell<Int, Long, Long, Long>(ref = ref) { _, v, w ->
                            // Null-drop: a key held by only one side produces no output,
                            // so the ungated default cannot show a null-extended row.
                            if (v != null && w != null) v + w else null
                        }
                    }
                    val out = spawn("collector") { ref -> LongMapCollectorCell(ref) }
                    wire(mapSrcs[0].cell.outlet, op.cell.left)
                    wire(mapSrcs[1].cell.outlet, op.cell.right)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }

                Subject.COALESCING_COMBINE -> {
                    val op = spawn("op") { ref -> CoalescingCombineCell(ref) }
                    val out = spawn("collector") { ref -> CounterCollectorCell(ref) }
                    wire(counterSrcs[0].cell.outlet, op.cell.inlet)
                    wire(op.cell.outlet, out.cell.inlet)
                    out.cell
                }
            }
        }

        rig.drain()

        val inlets = sourceRefs.map { ref ->
            val api = host.lookup<DeltaSourceApi>(ref)
                ?: error("source cell $ref not hosted after spawn — the build never completed")
            api.inlet
        }
        return MicroGraph(subject, drive, wiring, groups, inlets, collected, rig.drain, rig.stop)
    }
}
