package civictech.bench.micro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.MeasuringJvm
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import java.io.File
import java.io.Serializable
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Thrown when a footprint measurement cannot be taken honestly (`[BEN1-20]`,
 * `[BEN1-21]`, BS-10).
 *
 * Sits exactly where [ThroughputReportException] sits: strictly upstream of F3's
 * refusals. F3 refuses results it can see are dishonest; this refuses to produce a
 * number at all when the instrument's own preconditions do not hold — explicit GC
 * disabled, fewer than two samples to derive a dispersion from, a caller asking for a
 * non-positive scale. None of those can become a [BenchResult], so there is nothing for
 * F3 to refuse.
 */
class FootprintMeasurementException(message: String) : IllegalStateException(message)

// =====================================================================================
// THE METHOD, IN ONE SENTENCE (the findings task has to state it, so it is stated here
// first, and everything below is its implementation):
//
//   Retained size is measured by DIFFERENTIAL LIVE-HEAP ACCOUNTING — `System.gc()` to
//   quiescence, then the `MemoryMXBean` heap-used delta between a baseline holding
//   nothing and a state holding the structure — and payload/tag attribution is the same
//   measurement applied to two reachability sub-closures of the very graph just
//   measured: the payload objects it actually contains, and the `Timestamp`/`UUID`
//   objects it actually contains, with whatever those two do not account for reported as
//   UNATTRIBUTED.
//
// WHY THIS SHAPE, AND NOT THE TWO OBVIOUS ALTERNATIVES
//
//   - NOT allocation counting (`ThreadMXBean.getThreadAllocatedBytes`, JMH `-prof gc`).
//     Those measure bytes ALLOCATED, which for a populate-then-snapshot workload is
//     dominated by transient garbage — every intermediate `HashSet`, every re-hashed
//     table, every delta object on the way in. [BEN1-20] asks what a cell's state COSTS
//     while it is held, which is a retained-size question, and allocation is the wrong
//     quantity for it however precisely it is measured. `CellFootprintBenchmark` in
//     `bench/src/jmh/kotlin` measures the allocation quantity deliberately and says so;
//     it is a complement to this file, not the [BEN1-20] instrument.
//
//   - NOT a modelled `sizeof` walk (object header + field widths + alignment). That is
//     the technique a retained-size walk usually means, and it is ESTIMATION: the header
//     width, the reference width, HotSpot's field-layout and padding policy are all
//     assumptions about the JVM, not readings from it. [BEN1-21] forbids exactly that
//     move. The walk in this file therefore FINDS objects and never sizes them —
//     every byte reported here comes from a heap delta.
//
// WHAT THE NUMBERS ARE ABOUT, AND THE LIMIT THAT CARRIES
//
//   The subject is the graph `Stateful.snapshot()` returns, not the cell's private
//   fields. That is forced and not a preference: `TagState` and `MintedTags` are
//   `internal` to `civictech.cell.data.delta`, and every family's live state is a
//   private field, so `:bench` cannot reach any of it. `snapshot()` is the public
//   surface of a cell's retained state and the same seam durability, drain and
//   migration read.
//
//   So: these are the retained bytes of a cell's state AS SNAPSHOTTED. For the tagged
//   families that is a faithful structural copy (the same element -> tag-set maps, the
//   same `Timestamp` instances by value), but it is a COPY — it re-boxes the containers,
//   and a snapshot's `HashMap` need not have the same table capacity as the
//   `LinkedHashMap` it copied. Read a total here as "what this cell's state costs to
//   hold in the shape it hands out", and do not read it as the exact byte count of the
//   cell's own private fields. Nothing in `:bench` can measure the latter without a
//   kernel change, which `[BEN1-35]` forbids.
// =====================================================================================

/**
 * Differential live-heap accounting: the measurement primitive every byte in this file
 * comes from.
 *
 * The protocol per measurement is exactly:
 *
 * 1. [quiesce] — `System.gc()` until heap-used stops falling, so the baseline holds no
 *    collectable garbage.
 * 2. read [heapUsed] as `before`.
 * 3. run the caller's builder, which allocates the structure INSIDE the measured window.
 * 4. [quiesce] again with the structure still strongly reachable, so the transient
 *    garbage step 3 produced is collected and the structure is not.
 * 5. read [heapUsed] as `after`, then drop the structure.
 *
 * `after - before` is then the live bytes reachable from what the builder returned. The
 * builder allocating garbage is fine and expected — step 4 is what makes it fine.
 *
 * ## Why the structure must be built inside the window
 *
 * An object that already existed at step 2 is already counted in `before`, so holding it
 * across the window measures zero. That is why attribution here re-BUILDS the graph for
 * each sub-closure it wants to weigh (see [Footprint.measure]) instead of walking one
 * graph and weighing pieces of it: there is no way to un-count something already
 * allocated.
 *
 * ## What this primitive can and cannot see
 *
 * It measures the JVM's own accounting of live heap after a full collection, so it sees
 * real bytes including alignment padding and container slack — the things a modelled
 * `sizeof` gets wrong. It cannot see non-heap footprint (metaspace, code cache, direct
 * buffers), and it cannot resolve a signal below its own run-to-run noise;
 * [noiseFloorBytes] measures that noise rather than assuming it, and
 * [FootprintMeasurement.belowNoiseFloor] reports when a subject's whole state is under
 * it instead of publishing a number the instrument cannot actually resolve.
 *
 * ## MEASURED LIMIT: G1 accounts a humongous object's regions wholesale
 *
 * Found by measurement while calibrating this primitive (2026-08-19, macOS/arm64, JDK 21,
 * `-Xmx2g`), not anticipated: a single `LongArray(1 shl 20)` — 8 MiB of payload — reports
 * a delta of exactly 9 MiB, i.e. 9 whole 1 MiB G1 regions. G1 treats an object larger than
 * half a region as *humongous*, gives it whole contiguous regions, and counts all of them
 * as used; the same 8 MiB split into 256 arrays of 32 KiB reports its true size to within
 * a fraction of a percent. `FootprintTest.heapProbeMeasuresRealBytes` pins the
 * non-humongous case, and its history is why it uses 256 chunks rather than one array.
 *
 * **What that means for the figures in this file, stated here rather than left to be
 * rediscovered:** a snapshot at 1e5 elements has humongous backing arrays (a `HashMap`
 * table for 1e5 entries is a ~1 MiB `Node[]`), so a total at that scale includes the region
 * rounding of each of them. Three consequences, all of which a reader of a footprint number
 * has to carry:
 *
 * - It is a real occupancy, not an artifact: those regions hold nothing else, so the heap
 *   genuinely is that much fuller. It is simply not the sum of the objects' own sizes.
 * - It is deterministic, so it does not widen the dispersion and cannot be averaged away.
 * - It breaks strict proportionality across scales. A 1e5 total compared against 100x a
 *   1e3 total will differ partly by this, and reading that difference as a superlinearity
 *   in the cell would be wrong.
 *
 * ## MEASURED LIMIT: allocator fill waste counts as used
 *
 * Same calibration, same day: 256 non-humongous 32 KiB arrays report ~3.1% more than their
 * payload. Object headers and the holder array account for ~5 KiB of that; the remaining
 * ~256 KiB is HotSpot filling the unusable tail of each retiring TLAB with a filler object,
 * which is live heap and is counted as used.
 *
 * So every figure in this file is *occupancy*, and occupancy includes the allocator's own
 * slack. It is deterministic (again, no effect on dispersion) and it is roughly
 * proportional to the number of allocations, so it inflates a total by a few percent and
 * lands in whichever bucket the objects were allocated for. It is NOT a per-object size,
 * and a footprint figure here must not be quoted as one.
 */
object HeapProbe {

    /**
     * Upper bound on `System.gc()` passes per [quiesce]. The loop exits as soon as
     * heap-used stops falling, so this is a guard against a pathological allocator, not
     * the usual cost — two or three passes is what a settled heap takes.
     */
    const val MAX_GC_PASSES: Int = 8

    /** Baseline re-measurements [noiseFloorBytes] takes. */
    const val NOISE_SAMPLES: Int = 5

    /**
     * Keeps a measured structure reachable across step 4's collection.
     *
     * `@Volatile` and written, not read: a plain local would be dead after the builder
     * returns, and the JIT is entitled to let the collector reclaim the very graph being
     * measured — which would report every retained size as zero. `identityHashCode` is
     * used rather than `hashCode` because it is O(1) and cannot walk (or mutate the hash
     * caches of) the structure under measurement.
     */
    @Volatile
    private var sink: Int = 0

    /** Live heap bytes, as the JVM's own accounting reports them. */
    fun heapUsed(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

    /**
     * `System.gc()` until heap-used stops falling, at most [MAX_GC_PASSES] times.
     *
     * Stopping on the first non-decrease rather than always running the full count is
     * what keeps a measurement cheap; the loop exists because one `System.gc()` leaves
     * behind objects that only became unreachable during that collection (a reference
     * chain freed in the same pass).
     */
    fun quiesce() {
        var previous = Long.MAX_VALUE
        repeat(MAX_GC_PASSES) {
            System.gc()
            val used = heapUsed()
            if (used >= previous) return
            previous = used
        }
    }

    /**
     * Refuses when this JVM cannot be made to collect on request.
     *
     * `-XX:+DisableExplicitGC` turns every `System.gc()` in [quiesce] into a no-op, and
     * the deltas this object reports would then be "bytes allocated since the last
     * automatic collection" — a plausible-looking number with no relation to retained
     * size. Read off `HotSpotDiagnosticMXBean` rather than assumed, and refused rather
     * than warned about, because a warning in a log is not a thing a findings entry
     * carries.
     *
     * @throws FootprintMeasurementException if explicit GC is disabled.
     */
    fun requireCollectableHeap() {
        val option = runCatching {
            ManagementFactory
                .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean::class.java)
                ?.getVMOption("DisableExplicitGC")
                ?.value
        }.getOrNull() ?: return // not a HotSpot VM, or the option is unknown here
        if (option.equals("true", ignoreCase = true)) {
            throw FootprintMeasurementException(
                "cannot measure retained size: this JVM runs with -XX:+DisableExplicitGC, " +
                    "so System.gc() is a no-op and every heap delta would be bytes " +
                    "allocated since the last automatic collection rather than bytes " +
                    "retained. Re-run without that flag"
            )
        }
    }

    /**
     * The live bytes reachable from whatever [build] returns — the primitive documented
     * on this object.
     *
     * A negative result is returned as measured, not clamped: it means the delta was
     * inside the instrument's noise (the baseline drifted down across the window), and a
     * clamp to zero would hide that from the dispersion the caller derives.
     */
    fun retainedBytes(build: () -> Any?): Long {
        quiesce()
        val before = heapUsed()
        var held: Any? = build()
        quiesce()
        val after = heapUsed()
        sink = System.identityHashCode(held)
        held = null
        return after - before
    }

    /**
     * The instrument's own noise, MEASURED: the largest absolute heap delta over
     * [samples] windows that hold nothing at all.
     *
     * This is the resolution limit of every other number in this file. A subject whose
     * whole retained state is under it is reported as [FootprintMeasurement.belowNoiseFloor]
     * rather than as a byte count, because the instrument genuinely cannot tell that
     * state apart from measurement drift — the honest answer for `CounterCell`, whose
     * snapshot is a single boxed `Long` however many increments it absorbed.
     */
    fun noiseFloorBytes(samples: Int = NOISE_SAMPLES): Long {
        require(samples >= 1) { "samples must be >= 1, was $samples" }
        var worst = 0L
        repeat(samples) {
            val delta = retainedBytes { null }
            worst = maxOf(worst, abs(delta))
        }
        return worst
    }
}

/** Which attribution bucket an object in a state graph falls into (`[BEN1-20]`). */
enum class Bucket {

    /** Caller data: the elements, keys and values the harness itself inserted. */
    PAYLOAD,

    /**
     * Tag and metadata overhead: [civictech.cell.Timestamp] instances and the [UUID]
     * source identities they and the PN-counter's per-source slots are keyed by.
     */
    TAG_METADATA,
}

/**
 * What one walk of a state graph found — object identities only, never sizes.
 *
 * @param payload every distinct object whose class is one of the subject's declared
 *   payload classes, in first-visit order.
 * @param tagMetadata every distinct [civictech.cell.Timestamp] and [UUID].
 * @param visited how many distinct objects the walk reached in total.
 * @param opaque how many of those it could not look inside — a JDK-internal object that
 *   is neither a `Map`, a `Collection` nor an array. Reported rather than swallowed:
 *   an opaque object may hide payload or tag objects the walk therefore did not collect,
 *   which moves their bytes into UNATTRIBUTED. That direction is safe (attribution can
 *   only under-claim, never over-claim), but a reader has to be able to see it happened.
 */
data class WalkResult(
    val payload: List<Any>,
    val tagMetadata: List<Any>,
    val visited: Int,
    val opaque: Int,
)

/**
 * Finds the payload and tag objects inside a state graph. It never sizes anything —
 * see this file's header for why a modelled `sizeof` would be estimation.
 *
 * ## Traversal
 *
 * `Map` (keys and values), `Collection`, and object arrays are traversed through their
 * public interfaces; [civictech.cell.Timestamp] is traversed by reading its two declared
 * properties. Primitive arrays hold no references and are not traversed. Anything else
 * whose class lives under `java.`/`javax.`/`sun.`/`jdk.` is counted [WalkResult.opaque]
 * and NOT reflected into: JDK 21 does not open `java.base`'s packages to the unnamed
 * module, so `setAccessible` on a `java.util` field throws — writing that call and
 * catching the exception would only be a more elaborate way of reaching the same
 * conclusion. A non-JDK class is reflected over (declared reference fields), and counted
 * opaque if that reflection is refused.
 *
 * Identity, not equality: the visited set is an [IdentityHashMap], so a `Timestamp`
 * shared between two tag sets is one object here, exactly as it is one object on the
 * heap. Weighing it twice is precisely how an attribution starts exceeding its total.
 */
object StateWalk {

    private val OPAQUE_PACKAGE_PREFIXES = listOf("java.", "javax.", "sun.", "jdk.", "com.sun.")

    fun walk(root: Any?, payloadClasses: Set<Class<*>>): WalkResult {
        val seen = IdentityHashMap<Any, Boolean>()
        val payload = ArrayList<Any>()
        val tags = ArrayList<Any>()
        var opaque = 0
        val pending = ArrayDeque<Any>()
        if (root != null) pending.addLast(root)

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (seen.put(current, true) != null) continue

            when {
                payloadClasses.contains(current.javaClass) -> payload += current
                current is civictech.cell.Timestamp || current is UUID -> tags += current
            }

            when (current) {
                is civictech.cell.Timestamp -> pending.addLast(current.sourceId)
                is Map<*, *> -> current.forEach { (key, value) ->
                    key?.let { pending.addLast(it) }
                    value?.let { pending.addLast(it) }
                }
                is Collection<*> -> current.forEach { element ->
                    element?.let { pending.addLast(it) }
                }
                is Array<*> -> current.forEach { element ->
                    element?.let { pending.addLast(it) }
                }
                else -> if (!isLeaf(current)) {
                    val fields = referenceFields(current)
                    if (fields == null) {
                        opaque++
                    } else {
                        fields.forEach { value -> pending.addLast(value) }
                    }
                }
            }
        }
        return WalkResult(payload, tags, visited = seen.size, opaque = opaque)
    }

    /**
     * Holds no references worth following: a boxed primitive, a `String`, a primitive
     * array, an `enum`. Distinguished from [WalkResult.opaque] deliberately — a leaf hides
     * nothing, so counting it opaque would report a completeness doubt that does not
     * exist. (A `String`'s internal `byte[]` is unreachable to reflection here and holds
     * no payload or tag object, so nothing is missed by stopping.)
     */
    private fun isLeaf(value: Any): Boolean =
        value is Number || value is CharSequence || value is Boolean || value is Char ||
            value is UUID || value is Enum<*> || value.javaClass.isArray

    /**
     * The values of [value]'s declared reference fields, or `null` when this JVM refuses
     * to open them.
     */
    private fun referenceFields(value: Any): List<Any>? {
        val type = value.javaClass
        val name = type.name
        if (OPAQUE_PACKAGE_PREFIXES.any { name.startsWith(it) }) return null
        return runCatching {
            val out = ArrayList<Any>()
            var cursor: Class<*>? = type
            while (cursor != null && cursor != Any::class.java) {
                cursor.declaredFields.forEach { field ->
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                    if (field.type.isPrimitive) return@forEach
                    field.isAccessible = true
                    field.get(value)?.let { out += it }
                }
                cursor = cursor.superclass
            }
            out
        }.getOrNull()
    }
}

/**
 * The seven data-cell families `[BEN1-20]` names, as an enum.
 *
 * An enum rather than a list of strings for one concrete reason: JMH fills an enum
 * `@Param` from the enum's own constants, so `CellFootprintBenchmark`'s coverage cannot
 * drift from this catalog without a compile error. (That is the same property
 * `OperatorThroughputBenchmark` gets from `Subject` being an enum, and the same reason a
 * hand-written `@Param("SetCell", "MapCell", ...)` list was rejected here.)
 *
 * @param cellName the kernel class's simple name — the label every rendered row carries.
 */
enum class CellFamily(val cellName: String) {
    SET_CELL("SetCell"),
    MAP_CELL("MapCell"),
    OR_MAP_CELL("OrMapCell"),
    KEYED_SET_CELL("KeyedSetCell"),
    LIST_CELL("ListCell"),
    COUNTER_CELL("CounterCell"),
    PN_COUNTER_CELL("PnCounterCell"),
}

/**
 * The three scales `[BEN1-20]` names, as an enum — for the same JMH `@Param` reason
 * [CellFamily] is one. An `Int` `@Param` would need its three values written out as
 * annotation literals, which is a second definition of [Footprint.SCALES] that nothing
 * would keep in step.
 */
enum class Scale(val elements: Int) {
    N1E3(1_000),
    N1E4(10_000),
    N1E5(100_000),
}

/**
 * One data-cell family, populated to a scale and snapshotted — the unit [Footprint]
 * measures.
 *
 * @param family which family this is; [name] is its kernel class's simple name, the
 *   prefix of every rendered row label.
 * @param payloadClasses the classes of the objects the harness itself inserts. Declared
 *   per family rather than inferred, because "which objects are the caller's data" is a
 *   fact about the fixture below, not something a walk can discover: `KeyedSetCell`'s
 *   snapshot holds `Integer` keys, `Integer` elements AND a boxed `Long` tag counter,
 *   and only the first two are payload.
 * @param scalesWithElements whether populating to `n` actually produces `n` elements of
 *   state. `false` for the two counters, whose state is O(1) in the number of increments
 *   — stated as a property of the subject so a reader of a near-zero bytes-per-element
 *   figure sees that it is the design, not a measurement failure.
 * @param populate drives `n` writes into a fresh, UNHOSTED cell and returns the cell.
 *   Unhosted is what makes this [Drive.REAL] with no scheduler at all — the same shape
 *   V1C-BENCH's E1 used for its direct `snapshot()` timings, and the opposite of a
 *   `SimWorld` measurement. Returning the CELL rather than its snapshot is what lets
 *   `CellFootprintBenchmark` populate once per iteration and snapshot repeatedly.
 */
class FootprintSubject(
    val family: CellFamily,
    val payloadClasses: Set<Class<*>>,
    val scalesWithElements: Boolean,
    val populate: (Int) -> civictech.cell.Stateful,
) {

    /** The kernel class's simple name. */
    val name: String get() = family.cellName

    /** [populate] then `snapshot()` — the graph whose retained size [Footprint] measures. */
    fun snapshotOf(elements: Int): Serializable = populate(elements).snapshot()

    override fun toString(): String = name
}

/**
 * Mean and dispersion over a replicate set, in the same statistic
 * [BenchResult.dispersion] is defined as.
 *
 * [dispersion] is the half-width of the two-sided 99.9% confidence interval of the mean,
 * `t(0.9995, n-1) * s / sqrt(n)` — which is what JMH reports as `Score Error (99.9%)`
 * and computes the same way (`AbstractStatistics.getMeanErrorAt`, via its own
 * `TDistribution`). Deriving it identically is what makes a footprint row comparable to
 * a throughput row at all, and what lets `civictech.bench.classify` weigh both against
 * one `NOISE_FLOOR`.
 */
data class Stat(val mean: Double, val dispersion: Double, val samples: Int) {

    init {
        require(samples >= MIN_SAMPLES) {
            "a dispersion needs at least $MIN_SAMPLES samples, got $samples"
        }
        require(dispersion.isFinite() && dispersion >= 0.0) {
            "dispersion must be finite and non-negative, was $dispersion"
        }
    }

    /** [mean] and [dispersion] scaled by [factor] — an exact linear rescale, not a re-derivation. */
    fun scaled(factor: Double): Stat =
        Stat(mean * factor, abs(dispersion * factor), samples)

    companion object {

        /** Two samples is the fewest a sample standard deviation exists for. */
        const val MIN_SAMPLES: Int = 2

        /**
         * `t(0.9995, df)` for `df` = 1..30, indexed by `df - 1`; [T_LARGE_DF] beyond.
         *
         * A table rather than a computed inverse-t, for the same reason `:bench` parses
         * JMH's CSV by hand: this module depends on `:kernel` and `:testkit` and nothing
         * else (`[BEN1-03]`), and a statistics library bought to avoid thirty literals
         * would be a dependency bought for a formatting choice. The values are the
         * standard two-sided 99.9% Student-t quantiles.
         */
        private val T_99_9 = doubleArrayOf(
            636.619, 31.599, 12.924, 8.610, 6.869, 5.959, 5.408, 5.041, 4.781, 4.587,
            4.437, 4.318, 4.221, 4.140, 4.073, 4.015, 3.965, 3.922, 3.883, 3.850,
            3.819, 3.792, 3.768, 3.745, 3.725, 3.707, 3.690, 3.674, 3.659, 3.646,
        )

        /** The normal limit `t` converges to; used for `df > 30`. */
        const val T_LARGE_DF: Double = 3.291

        fun tQuantile(df: Int): Double {
            require(df >= 1) { "df must be >= 1, was $df" }
            return if (df <= T_99_9.size) T_99_9[df - 1] else T_LARGE_DF
        }

        /**
         * Mean and 99.9% error over [samples].
         *
         * @throws FootprintMeasurementException with fewer than [MIN_SAMPLES] samples —
         *   the same refusal `ThroughputReport` makes for a JMH row whose error is `NaN`,
         *   and for the same reason: the RUN was too small, and a row with no dispersion
         *   cannot be classified against `NOISE_FLOOR` at all.
         */
        fun of(samples: List<Long>): Stat {
            if (samples.size < MIN_SAMPLES) {
                throw FootprintMeasurementException(
                    "a footprint measurement needs at least $MIN_SAMPLES replicates to " +
                        "carry a dispersion, got ${samples.size}"
                )
            }
            val n = samples.size
            val mean = samples.sumOf { it.toDouble() } / n
            val variance = samples.sumOf { (it.toDouble() - mean) * (it.toDouble() - mean) } /
                (n - 1)
            val error = tQuantile(n - 1) * sqrt(variance) / sqrt(n.toDouble())
            return Stat(mean = mean, dispersion = error, samples = n)
        }

        /**
         * The stat of `left - right` for two independently measured quantities:
         * difference of means, dispersions combined in quadrature.
         *
         * Standard propagation for independent measurements, and the ONLY arithmetic in
         * this file that produces a number no single window measured. It is used for the
         * UNATTRIBUTED residual, which is the point: `[BEN1-21]` forbids estimating the
         * split, and a residual carrying the combined error of its three measured terms
         * is the opposite of an estimate — it is the measured remainder with its measured
         * uncertainty attached, so a reader can see when the residual is smaller than the
         * error bars on it.
         */
        fun difference(left: Stat, vararg right: Stat): Stat {
            val mean = left.mean - right.sumOf { it.mean }
            val variance = left.dispersion * left.dispersion +
                right.sumOf { it.dispersion * it.dispersion }
            return Stat(
                mean = mean,
                dispersion = sqrt(variance),
                samples = minOf(left.samples, right.minOfOrNull { it.samples } ?: left.samples),
            )
        }
    }
}

/**
 * One family at one scale, measured (`[BEN1-20]`, `[BEN1-21]`, BS-10).
 *
 * Every byte figure is per ONE structure — the per-window deltas are divided by
 * [multiplicity] before any statistic is taken.
 *
 * @param total retained bytes of the whole snapshot graph.
 * @param payload retained bytes of the payload objects that graph contains.
 * @param tagMetadata retained bytes of the `Timestamp`/`UUID` objects it contains.
 * @param multiplicity how many independent structures each measured window held, chosen
 *   by [Footprint.multiplicityFor] from a calibration measurement so the signal sits
 *   above [noiseFloorBytes].
 * @param noiseFloorBytes the instrument's measured resolution limit for this run.
 * @param payloadCount / [tagCount] / [visitedCount] / [opaqueCount] what the walk found
 *   in one structure — carried so a reader can check the attribution against the graph's
 *   own shape (e.g. that a `SetCell` at 1e4 really did hold 1e4 payload objects and 1e4
 *   tags) instead of taking the byte figures on trust.
 */
data class FootprintMeasurement(
    val subject: FootprintSubject,
    val elements: Int,
    val multiplicity: Int,
    val total: Stat,
    val payload: Stat,
    val tagMetadata: Stat,
    val noiseFloorBytes: Long,
    val payloadCount: Int,
    val tagCount: Int,
    val visitedCount: Int,
    val opaqueCount: Int,
) {

    /**
     * What [payload] and [tagMetadata] do not account for (`[BEN1-21]`).
     *
     * This is the containers and scaffolding — hash tables, entry nodes, set wrappers,
     * the snapshot's own `HashMap`, the boxed tag counter — plus anything the walk could
     * not see (see [opaqueCount]). It is reported as UNATTRIBUTED and never split,
     * apportioned or ascribed: the instrument measured three quantities and this is the
     * remainder, with the three measurements' errors propagated onto it.
     */
    val unattributed: Stat get() = Stat.difference(total, payload, tagMetadata)

    /** [total] divided by [elements] — retained bytes per element. */
    val bytesPerElement: Stat get() = total.scaled(1.0 / elements)

    /**
     * True when the subject's whole retained state is at or under [noiseFloorBytes]
     * times [multiplicity] — the instrument cannot resolve it, and the honest report is
     * that fact rather than a byte figure. Expected for `CounterCell` and
     * `PnCounterCell`, whose state is O(1) in the number of increments.
     */
    val belowNoiseFloor: Boolean
        get() = total.mean * multiplicity <= noiseFloorBytes.toDouble()

    /** The row label prefix every rendered result of this measurement carries. */
    val label: String get() = "${subject.name} n=$elements"
}

/**
 * The footprint instrument: the seven data-cell families, the three scales, and the
 * measurement that turns one of each into [FootprintMeasurement] (`[BEN1-20]`,
 * `[BEN1-21]`, BS-10).
 *
 * Sweeping all 21 combinations and writing the findings entry belongs to the sibling
 * measurement task; [sweep] is the entry point it calls, and
 * `CellFootprintProbeTest` in `bench/src/test/kotlin/civictech/bench/micro` is the
 * `@Tag("bench")` probe that proves it runs at full scale.
 */
object Footprint {

    /** The three scales `[BEN1-20]` names, in ascending order. */
    val SCALES: List<Int> = Scale.entries.map { it.elements }

    /** Replicates per measured quantity — see [Stat] for what they buy. */
    const val DEFAULT_REPLICATES: Int = 10

    /**
     * Discarded build-and-snapshot passes before the first measured one, per subject and
     * scale. One is enough for what it is for: loading the family's classes and letting
     * C2 compile the fold path, so that class-loading allocation does not land inside a
     * measured window.
     */
    const val WARMUP_PASSES: Int = 1

    /**
     * Signal the [multiplicityFor] heuristic aims for, in bytes. Two orders of magnitude
     * above the noise this instrument measures in practice on a settled heap, and small
     * enough to sit comfortably inside the module's 2 GiB test fork alongside the
     * transient garbage a build pass produces.
     */
    const val TARGET_SIGNAL_BYTES: Long = 8L * 1024 * 1024

    /**
     * Hard ceiling on writes per measured window, over all copies. This, not the target
     * signal, is what keeps a 21-combination sweep to minutes: a window costs
     * `multiplicity * elements` inlet calls plus that many snapshot copies, and it is
     * paid `3 * replicates` times per combination.
     */
    const val MAX_WRITES_PER_WINDOW: Int = 200_000

    /**
     * Element values start here, not at zero.
     *
     * `Integer.valueOf` caches -128..127, so an element in that band is a JDK-interned
     * instance that was already live before any measurement began: holding it adds no
     * bytes, and payload attribution would silently under-report by those objects.
     * Starting above the cache makes every boxed element a fresh allocation, which is
     * what the measurement assumes.
     */
    const val ELEMENT_BASE: Int = 1_000

    private val INTEGER_PAYLOAD = setOf<Class<*>>(java.lang.Integer::class.java)
    private val LONG_PAYLOAD = setOf<Class<*>>(java.lang.Long::class.java)

    /**
     * The seven data-cell families `[BEN1-20]` names, each populated through its own
     * public inlet contract on an unhosted cell.
     *
     * `Int` elements and `Int` map values throughout, so that a per-element figure is
     * comparable across families rather than reflecting a different payload type in each.
     * The two counters take the same `n` as increments even though their state does not
     * grow with it — see [FootprintSubject.scalesWithElements].
     */
    val FAMILIES: List<FootprintSubject> = listOf(
        FootprintSubject(CellFamily.SET_CELL, INTEGER_PAYLOAD, scalesWithElements = true) { n ->
            val cell = civictech.cell.data.SetCell<Int>()
            for (i in 0 until n) cell.inlet.call.add(ELEMENT_BASE + i)
            cell
        },
        FootprintSubject(CellFamily.MAP_CELL, INTEGER_PAYLOAD, scalesWithElements = true) { n ->
            val cell = civictech.cell.data.MapCell<Int, Int>()
            for (i in 0 until n) cell.inlet.call.put(ELEMENT_BASE + i, ELEMENT_BASE + i)
            cell
        },
        FootprintSubject(CellFamily.OR_MAP_CELL, INTEGER_PAYLOAD, scalesWithElements = true) { n ->
            val cell = civictech.cell.data.OrMapCell<Int, Int>()
            for (i in 0 until n) cell.inlet.call.put(ELEMENT_BASE + i, ELEMENT_BASE + i)
            cell
        },
        FootprintSubject(
            CellFamily.KEYED_SET_CELL,
            INTEGER_PAYLOAD,
            scalesWithElements = true,
        ) { n ->
            val cell = civictech.cell.data.KeyedSetCell<Int, Int>()
            for (i in 0 until n) cell.inlet.call.put(ELEMENT_BASE + i, ELEMENT_BASE + i)
            cell
        },
        FootprintSubject(CellFamily.LIST_CELL, INTEGER_PAYLOAD, scalesWithElements = true) { n ->
            val cell = civictech.cell.data.ListCell<Int>()
            for (i in 0 until n) cell.inlet.call.add(ELEMENT_BASE + i)
            cell
        },
        FootprintSubject(CellFamily.COUNTER_CELL, LONG_PAYLOAD, scalesWithElements = false) { n ->
            val cell = civictech.cell.data.CounterCell()
            for (i in 0 until n) cell.inlet.call.increment(1L)
            cell
        },
        FootprintSubject(
            CellFamily.PN_COUNTER_CELL,
            LONG_PAYLOAD,
            scalesWithElements = false,
        ) { n ->
            val cell = civictech.cell.data.PnCounterCell()
            for (i in 0 until n) cell.inlet.call.increment(1L)
            cell
        },
    )

    /**
     * The subject for [family].
     *
     * Refuses rather than returning `null`: a [CellFamily] constant with no fixture is a
     * catalog that claims a coverage it does not have, and `[BEN1-20]` names all seven.
     */
    fun of(family: CellFamily): FootprintSubject =
        FAMILIES.firstOrNull { it.family == family }
            ?: throw FootprintMeasurementException(
                "no footprint subject for $family; the catalog covers " +
                    "${FAMILIES.map { it.family }}"
            )

    /** The family whose [FootprintSubject.name] is [name], for a text-driven caller. */
    fun byName(name: String): FootprintSubject =
        FAMILIES.firstOrNull { it.name == name }
            ?: throw FootprintMeasurementException(
                "no footprint subject named '$name'; known: ${FAMILIES.map { it.name }}"
            )

    /**
     * How many independent structures one measured window should hold.
     *
     * Derived from a calibration measurement rather than assumed: a family whose state
     * is large needs one copy, and a family whose state is a single boxed `Long` needs as
     * many as the write budget allows before its signal has any chance of clearing the
     * noise. Both bounds are explicit — never fewer than one, never more writes than
     * [MAX_WRITES_PER_WINDOW].
     *
     * A calibration at or below zero (a signal lost in the noise) asks for the largest
     * affordable multiplicity; that is the [FootprintMeasurement.belowNoiseFloor] case,
     * and asking for the most the budget allows is what gives it its best chance of not
     * being one.
     */
    fun multiplicityFor(calibrationBytes: Long, elements: Int): Int {
        require(elements > 0) { "elements must be positive, was $elements" }
        val affordable = maxOf(1, MAX_WRITES_PER_WINDOW / elements)
        if (calibrationBytes <= 0L) return affordable
        val wanted = (TARGET_SIGNAL_BYTES / calibrationBytes).toInt()
        return wanted.coerceIn(1, affordable)
    }

    /**
     * Measures one family at one scale.
     *
     * Three quantities, each measured in its own windows because
     * [HeapProbe.retainedBytes] can only weigh what was allocated inside the window (see
     * its documentation): the whole graph, the payload objects it contains, and the tag
     * objects it contains. The payload and tag windows build the same graph, walk it,
     * keep an array of the selected objects and let the rest of the graph become garbage
     * — so what they weigh is the ACTUAL objects of the ACTUAL graph, not a
     * reconstruction of them, and the array holding them is subtracted using its own
     * measured cost.
     *
     * The two array controls are measured ONCE rather than per replicate: an
     * `arrayOfNulls` of a fixed length is a deterministic allocation, so replicating it
     * would buy a dispersion on a constant.
     *
     * @throws FootprintMeasurementException if [elements] or [replicates] is out of
     *   range, or explicit GC is disabled on this JVM.
     */
    fun measure(
        subject: FootprintSubject,
        elements: Int,
        replicates: Int = DEFAULT_REPLICATES,
    ): FootprintMeasurement {
        if (elements <= 0) {
            throw FootprintMeasurementException(
                "cannot measure ${subject.name} at $elements elements — a scale must be " +
                    "positive"
            )
        }
        if (replicates < Stat.MIN_SAMPLES) {
            throw FootprintMeasurementException(
                "cannot measure ${subject.name} with $replicates replicates — a " +
                    "dispersion needs at least ${Stat.MIN_SAMPLES}"
            )
        }
        HeapProbe.requireCollectableHeap()

        repeat(WARMUP_PASSES) { subject.snapshotOf(elements) }
        val noise = HeapProbe.noiseFloorBytes()

        val calibration = HeapProbe.retainedBytes { subject.snapshotOf(elements) }
        val multiplicity = multiplicityFor(calibration, elements)

        // Counts come from one unmeasured walk: the graph is deterministic, so the
        // number of payload and tag objects is a fact about the fixture, not a sample.
        val shape = StateWalk.walk(subject.snapshotOf(elements), subject.payloadClasses)
        val payloadPerStructure = shape.payload.size
        val tagPerStructure = shape.tagMetadata.size

        val holderControl = HeapProbe.retainedBytes { arrayOfNulls<Any>(multiplicity) }
        val payloadHolderControl =
            HeapProbe.retainedBytes { arrayOfNulls<Any>(payloadPerStructure * multiplicity) }
        val tagHolderControl =
            HeapProbe.retainedBytes { arrayOfNulls<Any>(tagPerStructure * multiplicity) }

        val totals = ArrayList<Long>(replicates)
        val payloads = ArrayList<Long>(replicates)
        val tags = ArrayList<Long>(replicates)
        repeat(replicates) {
            totals += HeapProbe.retainedBytes { structures(subject, elements, multiplicity) } -
                holderControl
            payloads += HeapProbe.retainedBytes {
                selection(subject, elements, multiplicity) { it.payload }
            } - payloadHolderControl
            tags += HeapProbe.retainedBytes {
                selection(subject, elements, multiplicity) { it.tagMetadata }
            } - tagHolderControl
        }

        val perStructure = 1.0 / multiplicity
        return FootprintMeasurement(
            subject = subject,
            elements = elements,
            multiplicity = multiplicity,
            total = Stat.of(totals).scaled(perStructure),
            payload = Stat.of(payloads).scaled(perStructure),
            tagMetadata = Stat.of(tags).scaled(perStructure),
            noiseFloorBytes = noise,
            payloadCount = payloadPerStructure,
            tagCount = tagPerStructure,
            visitedCount = shape.visited,
            opaqueCount = shape.opaque,
        )
    }

    /** [measure] over every combination, in catalog order then ascending scale. */
    fun sweep(
        subjects: List<FootprintSubject> = FAMILIES,
        scales: List<Int> = SCALES,
        replicates: Int = DEFAULT_REPLICATES,
    ): List<FootprintMeasurement> =
        subjects.flatMap { subject -> scales.map { scale -> measure(subject, scale, replicates) } }

    /** [multiplicity] independent populated snapshots, held in one array. */
    private fun structures(
        subject: FootprintSubject,
        elements: Int,
        multiplicity: Int,
    ): Array<Any?> {
        val out = arrayOfNulls<Any>(multiplicity)
        for (i in 0 until multiplicity) out[i] = subject.snapshotOf(elements)
        return out
    }

    /**
     * The objects [select] picks out of [multiplicity] freshly built graphs, held in one
     * array while the graphs themselves become garbage.
     *
     * This is the attribution step, and its shape is the whole reason attribution here is
     * measurement: the returned array's retained closure is exactly those objects (plus
     * whatever they reference — a `Timestamp`'s `UUID`, a `String`'s bytes), because
     * nothing else in the graph survives the window's collection.
     */
    private fun selection(
        subject: FootprintSubject,
        elements: Int,
        multiplicity: Int,
        select: (WalkResult) -> List<Any>,
    ): Array<Any?> {
        val picked = ArrayList<Any>()
        repeat(multiplicity) {
            picked.addAll(
                select(StateWalk.walk(subject.snapshotOf(elements), subject.payloadClasses)),
            )
        }
        return picked.toArray(arrayOfNulls<Any>(picked.size))
    }
}

/**
 * Turns [FootprintMeasurement]s into F3 results and findings entry text.
 *
 * Every number leaves this object through `civictech.bench.BenchResult` under a
 * [RunEnvironment] and [Drive.REAL], and is rendered by [ThroughputReport.renderResults]
 * — the same writer, the same dispersion gate, the same omission list. Nothing here
 * re-implements a refusal: a row too dispersed to report is excluded and named by that
 * renderer, and a table mixing drives or environments is refused by `FindingsTable`'s
 * constructor.
 */
object FootprintReport {

    /**
     * What `RunEnvironment.jmhMode` records for these measurements — the "explicitly
     * stated equivalent for a non-JMH measurement" that field's own documentation allows.
     *
     * Named so that a reader of a findings entry cannot mistake a footprint row for a JMH
     * row: the mode line will say retained-heap-delta, in-process, not JMH.
     */
    const val MODE: String = "retained-heap-delta (in-process JUnit probe; not JMH)"

    /** System property carrying the harness commit, forwarded by `bench/build.gradle.kts`. */
    const val HARNESS_SHA_PROPERTY: String = "civictech.bench.harnessSha"

    /** System property carrying the checkout root, set by `bench/build.gradle.kts`. */
    const val REPO_ROOT_PROPERTY: String = "computenet.repo.root"

    /**
     * The JVM these measurements ran on.
     *
     * ## Why reading this process's own properties is honest HERE
     *
     * `MeasuringJvm.fromJmhLog` exists because a JMH renderer asking `java.vendor`
     * answers with the RENDERER's JVM, not the forked JVM that measured — the defect
     * `computenet-hqid` fixed after two findings entries shipped with the wrong
     * environment line. That reasoning turns entirely on the measuring JVM being a
     * different process from the reporting one.
     *
     * A footprint measurement has no fork. `HeapProbe` collects THIS heap and reads THIS
     * process's `MemoryMXBean`; the JVM that measured is the JVM asking, so
     * `java.vendor`, `java.version` and `RuntimeMXBean.inputArguments` are facts about
     * the run rather than about a bystander. Constructing [MeasuringJvm] directly is
     * therefore the honest path and not a bypass of the refusal — there is no log to read
     * because there was no fork to log.
     *
     * **Do not lift this function into a path that renders a JMH results file.** The
     * moment the numbers come from somewhere other than this process's own heap, these
     * three reads describe the wrong JVM again, which is exactly `computenet-hqid`.
     */
    fun inProcessMeasuringJvm(): MeasuringJvm {
        val vendor = System.getProperty("java.vendor")
            ?: throw FootprintMeasurementException("system property java.vendor is not set")
        val vmName = System.getProperty("java.vm.name")
        val version = System.getProperty("java.version")
            ?: throw FootprintMeasurementException("system property java.version is not set")
        val arguments = ManagementFactory.getRuntimeMXBean().inputArguments
        return MeasuringJvm(
            vendor = if (vmName.isNullOrBlank()) vendor else "$vendor ($vmName)",
            version = version,
            heapSettings = heapSettingsOf(arguments),
        )
    }

    /**
     * The heap flags this process was launched with, or an explicit statement that it got
     * none — quoting the arguments it did get, the same shape `MeasuringJvm.heapSettings`
     * uses for a JMH fork, and for the same reason: never a heap "derived" from
     * `Runtime.maxMemory()`, which is an effective value and not a configured one.
     */
    private fun heapSettingsOf(arguments: List<String>): String {
        val prefixes = listOf(
            "-Xms", "-Xmx", "-Xmn",
            "-XX:MinHeapSize", "-XX:InitialHeapSize", "-XX:MaxHeapSize",
            "-XX:MinRAMPercentage", "-XX:InitialRAMPercentage", "-XX:MaxRAMPercentage",
            "-XX:MaxRAM=",
        )
        val heap = arguments.filter { argument -> prefixes.any { argument.startsWith(it) } }
        return when {
            heap.isNotEmpty() -> heap.joinToString(separator = " ")
            arguments.isEmpty() -> "JVM defaults (no VM options)"
            else -> "JVM defaults (no heap flag among VM options: " +
                arguments.joinToString(separator = " ") + ")"
        }
    }

    /**
     * The harness commit: the forwarded [HARNESS_SHA_PROPERTY] when set, otherwise read
     * from the checkout at [REPO_ROOT_PROPERTY] with `git rev-parse --short HEAD`,
     * suffixed `-dirty` when the working tree is not clean.
     *
     * The `-dirty` suffix is the point of doing it this way rather than defaulting to a
     * placeholder: a footprint measured from an edited tree is not reproducible from the
     * commit it names, and `RunEnvironment` has no field in which to say so. Refuses
     * rather than inventing one when neither source answers.
     */
    fun harnessCommitSha(): String {
        System.getProperty(HARNESS_SHA_PROPERTY)?.takeIf { it.isNotBlank() }?.let { return it }
        val root = System.getProperty(REPO_ROOT_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: throw FootprintMeasurementException(
                "cannot establish the harness commit: neither -D$HARNESS_SHA_PROPERTY nor " +
                    "-D$REPO_ROOT_PROPERTY is set, so there is no commit to name and no " +
                    "checkout to read one from"
            )
        val head = git(root, "rev-parse", "--short", "HEAD")
            ?: throw FootprintMeasurementException(
                "cannot establish the harness commit: `git rev-parse --short HEAD` failed " +
                    "in $root. Pass -D$HARNESS_SHA_PROPERTY=<sha> instead"
            )
        val status = git(root, "status", "--porcelain")
        return if (status.isNullOrBlank()) head else "$head-dirty"
    }

    private fun git(root: String, vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", *arguments))
            .directory(File(root))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    /**
     * The environment [toResults] stamps every result with.
     *
     * `forkCount = 1` is the literal truth for an in-process probe: the Gradle test
     * worker is the one and only JVM involved. `warmupIterations`/`measurementIterations`
     * carry [Footprint.WARMUP_PASSES] and the replicate count, which are what those words
     * mean here.
     */
    fun environment(
        replicates: Int = Footprint.DEFAULT_REPLICATES,
        harnessCommitSha: String = harnessCommitSha(),
    ): RunEnvironment = RunEnvironment.forRun(
        measuringJvm = inProcessMeasuringJvm(),
        jmhMode = MODE,
        forkCount = 1,
        warmupIterations = Footprint.WARMUP_PASSES,
        measurementIterations = replicates,
        harnessCommitSha = harnessCommitSha,
    )

    /**
     * The four rows one measurement contributes: total, payload, tag/metadata,
     * unattributed — plus the per-element figure `[BEN1-20]` asks for by name.
     *
     * The unattributed row is emitted ALWAYS, including when it is the whole total (the
     * counters, whose snapshot holds no tag objects at all) and including when it is
     * negative within its own error bars. `[BEN1-21]` is a requirement about what the
     * harness reports, not a fallback for when attribution fails: there is no code path
     * here that omits the residual, reallocates it to payload or tag, or scales the two
     * measured components up to meet the total.
     */
    fun toResults(
        measurement: FootprintMeasurement,
        env: RunEnvironment,
    ): List<LabelledResult> {
        fun row(suffix: String, stat: Stat, unit: String) = LabelledResult(
            label = "${measurement.label} $suffix",
            result = BenchResult(
                value = stat.mean,
                unit = unit,
                dispersion = stat.dispersion,
                drive = Drive.REAL,
                env = env,
            ),
        )
        return listOf(
            row("total retained", measurement.total, "bytes"),
            row("payload", measurement.payload, "bytes"),
            row("tag/metadata", measurement.tagMetadata, "bytes"),
            row("UNATTRIBUTED", measurement.unattributed, "bytes"),
            row("total per element", measurement.bytesPerElement, "bytes/element"),
        )
    }

    /** [toResults] over a whole sweep, in measurement order. */
    fun toResults(
        measurements: List<FootprintMeasurement>,
        env: RunEnvironment,
    ): List<LabelledResult> = measurements.flatMap { toResults(it, env) }

    /**
     * The sweep rendered through F3's writer, plus the provenance lines a byte figure is
     * meaningless without.
     *
     * [Report.text] carries the entry and its omissions; [provenance] carries what the
     * findings template has no column for — the multiplicity each window held, the
     * measured noise floor, the object counts the walk found, and every subject whose
     * state came in under the noise floor. Both are returned because publishing the table
     * without the second would state byte figures whose resolution the reader cannot see.
     */
    fun render(
        measurements: List<FootprintMeasurement>,
        date: String,
        subject: String,
        env: RunEnvironment,
        trigger: TriggerClaim = TriggerClaim.None,
    ): Report = ThroughputReport.renderResults(toResults(measurements, env), date, subject, trigger)

    /**
     * The per-measurement facts the findings table has no column for, one line each.
     *
     * Every claim in a footprint entry rests on these: a total measured at multiplicity
     * 40 is forty structures' bytes divided by forty, and a total under the noise floor
     * is not a measurement of that state at all. Rendered as text beside the table rather
     * than folded into the notes column, so that a reader who copies the table still has
     * to copy this.
     */
    fun provenance(measurements: List<FootprintMeasurement>): String =
        measurements.joinToString(separator = "\n") { m ->
            val resolution = if (m.belowNoiseFloor) {
                "BELOW the measured noise floor of ${m.noiseFloorBytes} bytes — this " +
                    "subject's snapshot state is smaller than the instrument can resolve, " +
                    "which is the finding, not a byte figure"
            } else {
                "above the measured noise floor of ${m.noiseFloorBytes} bytes"
            }
            "- ${m.label}: multiplicity=${m.multiplicity}, replicates=${m.total.samples}, " +
                "walk found ${m.payloadCount} payload / ${m.tagCount} tag objects in " +
                "${m.visitedCount} visited (${m.opaqueCount} opaque), " +
                "scalesWithElements=${m.subject.scalesWithElements}, $resolution"
        }
}
