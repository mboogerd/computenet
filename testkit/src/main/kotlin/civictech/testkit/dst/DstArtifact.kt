package civictech.testkit.dst

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Path

/**
 * How a run was driven, and therefore what may honestly be claimed about reproducing it
 * ([CHA1-40]).
 *
 * This is the one place the rig admits that determinism is a property of the *driver*, not of
 * the artifact format: a single-JVM [SimulationController][civictech.cell.host.SimulationController]
 * drive is reproducible from a seed, and a run spread across `JvmPeer` processes is not —
 * the interleaving is the OS scheduler's, and no seed recovers it. An artifact written from a
 * multi-JVM run is still *useful* (it records what was tried), but [DstReplay] refuses to
 * grade it: the verdict is `INDETERMINATE`, never `REPLAYED` and never `DIVERGED`.
 */
enum class DstDriver(val deterministic: Boolean) {
    /** One JVM, the rig's own loop over `SimulationController.step()`. Reproducible from the seed. */
    IN_PROCESS(true),

    /**
     * Driven across processes (`civictech.testkit.JvmPeer`). Marked **non-deterministic**: the
     * report makes no replay-reproducibility claim, and replay of its artifact is
     * `INDETERMINATE` ([CHA1-40]).
     */
    MULTI_JVM(false),
    ;

    /** Whether a report from this driver may claim its failure is reproducible from the seed. */
    val claimsReplayReproducibility: Boolean get() = deterministic
}

/**
 * Which rig produced an artifact ([CHA1-31], epic §9 risk 6).
 *
 * A trace digest is a function of what the kernel scheduler actually did, so **a digest is
 * valid within a commit, not across them** — an unrelated scheduling change moves it, which is
 * the point of having one. That makes the recorded stamp load-bearing rather than decorative:
 * without it, a replay after any kernel change would report a spurious `DIVERGED` and read as
 * a caught regression.
 *
 * [version] is the rig's own format/behaviour generation, bumped by hand in [DstRig] whenever
 * a change would move digests. [commit] is the repository commit, which the rig cannot obtain
 * cheaply on its own (a `git rev-parse` per run is neither cheap nor available in every
 * sandbox), so it is supplied from outside — see [DstRig.commit] — and is `null` when nobody
 * supplied one. A `null` on either side means the comparison *cannot be made*, which is
 * reported as a caveat on a divergence rather than silently treated as agreement.
 */
@Serializable
data class RigStamp(val version: String, val commit: String? = null) {

    /**
     * Whether [other] is close enough to this stamp that a digest comparison means anything.
     * Different rig version, or two *known* different commits, is not.
     */
    fun comparableTo(other: RigStamp): Boolean =
        version == other.version && !(commit != null && other.commit != null && commit != other.commit)

    /** True when neither side pins a commit, so a cross-commit digest change cannot be excluded. */
    fun commitUnknownAgainst(other: RigStamp): Boolean = commit == null || other.commit == null

    override fun toString(): String = "rig $version @ ${commit ?: "unknown-commit"}"
}

/** The running rig's identity. See [RigStamp]. */
object DstRig {

    /**
     * The rig generation. **Bump this whenever a change to the rig would move a trace digest**
     * — a new trace event, a changed canonical rendering, a different drive loop. Not bumping
     * it turns every stored artifact into a source of false divergences.
     */
    const val VERSION: String = "1"

    /**
     * The repository commit, if something told us. Read from the `dst.rig.commit` system
     * property, else the `DST_RIG_COMMIT` environment variable, else `null`.
     *
     * Deliberately *not* shelling out to `git`: a subprocess per run is not "cheaply
     * obtainable", and a rig that silently reports the wrong commit is worse than one that
     * reports none. The honest default is `null`, and [DstReplay] says so out loud when a
     * divergence is graded without it.
     */
    val commit: String?
        get() = (System.getProperty("dst.rig.commit") ?: System.getenv("DST_RIG_COMMIT"))?.takeIf { it.isNotBlank() }

    fun stamp(): RigStamp = RigStamp(VERSION, commit)
}

/**
 * One fault as stored in an artifact: its [id], the [kind] that names the codec able to
 * rebuild it, and that codec's own [params].
 *
 * The artifact schema deliberately knows *nothing* about any particular fault class. The six
 * fault classes of [CHA1-10] arrive in sibling files, and each brings a [FaultCodec] rather
 * than a new field here — so adding a fault class never changes the artifact format, exactly
 * as adding one never edits `DstWorld`.
 */
@Serializable
data class FaultRecord(
    val id: String,
    val kind: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

/**
 * A fault plan as stored: **its faults, and no seed**.
 *
 * The omission is the design. `FaultPlan.seed` is the single field a shrinker must hold
 * constant ([CHA1-35]), and [DstArtifact.seed] is where it lives in the file — once. A
 * shrunk plan is therefore *incapable* of carrying a different seed: there is no field for
 * one to disagree with. See [DstArtifact.withShrunkPlan].
 */
@Serializable
data class PlanRecord(val faults: List<FaultRecord> = emptyList())

/**
 * What the recorded run actually did, and the whole basis on which a replay is graded
 * ([CHA1-32]).
 *
 * **The full trace is not stored**, only its [traceDigest] and [traceEvents] count. A run may
 * emit tens of thousands of events, and an artifact that carried them would be a transcript
 * rather than a seed-plus-plan. The consequence is stated rather than papered over: a
 * divergence can be *detected* and localised to the failing step and the trace length, but the
 * first differing trace *event* cannot be recovered from an artifact alone — for that, re-run
 * both plans in one process and use [TraceDigests.divergence].
 */
@Serializable
data class ObservedRun(
    val outcome: DstOutcome,
    val steps: Int,
    val failingCheck: String? = null,
    val failingStep: Int? = null,
    val traceDigest: String,
    val traceEvents: Int,
)

/** Bounded-shrink bookkeeping (epic §9 risk 7). Filled by the shrinker task; unused here. */
@Serializable
data class ShrinkRecord(
    val attempts: Int,
    val reductionsAccepted: Int,
    val stoppedEarly: Boolean = false,
    val stopReason: String? = null,
)

/**
 * The self-contained replay artifact a failing run emits ([CHA1-31]).
 *
 * Everything needed to re-run the failure is here or reachable by *name* from here: the
 * [seed], the applied [plan], the step [budget], the [graphId] naming a [GraphSpec] in
 * [GraphRegistry], the [checkId] naming a [DstCheck] in [CheckRegistry], and the [rig] stamp
 * that says which commit's digests these are.
 *
 * ## What a shrinker gets from this
 *
 * The follow-on shrinker task ([CHA1-35]..[CHA1-37]) extends this file, and the shape is built
 * for it:
 *
 *  - **One seed field, at the top level.** [PlanRecord] has no seed, so `plan` and
 *    [shrunkPlan] cannot disagree about it — [CHA1-35] is structural, not a rule to remember.
 *  - **[plan] is never rewritten.** [withShrunkPlan] fills [shrunkPlan] and [shrink] and
 *    refuses anything else, which is [CHA1-37] as an API rather than a convention.
 *  - **Plans round-trip through [FaultCodecs].** `artifact.plan()` gives a live [FaultPlan];
 *    `FaultPlan.without(id)` is the reduction; the result encodes straight back.
 *  - **[observed] is the same-failure predicate.** [CHA1-36]'s "still fails with the same
 *    failing check" is `DstReplay.grade(...)` against this record, so the shrinker reuses the
 *    replay grader instead of inventing a second notion of "same failure".
 *
 * @property schema the artifact *format* version, independent of [rig] — the format can be
 *   stable across a rig generation that moves digests, and vice versa.
 * @property suite the sweep/suite name; also the artifact's directory under `build/dst/failures`.
 */
@Serializable
data class DstArtifact(
    val schema: Int = SCHEMA,
    val rig: RigStamp,
    val suite: String,
    val seed: Long,
    val graphId: String,
    val checkId: String? = null,
    val budget: Int,
    val driver: DstDriver = DstDriver.IN_PROCESS,
    val plan: PlanRecord,
    val observed: ObservedRun,
    val shrunkPlan: PlanRecord? = null,
    val shrink: ShrinkRecord? = null,
) {
    init {
        require(schema == SCHEMA) {
            "unsupported DST artifact schema $schema; this rig reads schema $SCHEMA"
        }
        DstArtifacts.requireArtifactName(suite, "suite")
    }

    /** Rebuild the original plan, decoding every fault through its registered [FaultCodec]. */
    fun plan(): FaultPlan = FaultPlan(seed, plan.faults.map(FaultCodecs::decode))

    /** Rebuild the shrunk plan, or null if nothing has been shrunk yet. */
    fun shrunkPlan(): FaultPlan? = shrunkPlan?.let { FaultPlan(seed, it.faults.map(FaultCodecs::decode)) }

    /** The graph this artifact names, or a naming error listing the registered ids ([CHA1-06]). */
    fun graph(): GraphSpec = GraphRegistry.require(graphId)

    /**
     * The check this artifact names. `null` [checkId] yields [DstCheck.none], which can only
     * ever reproduce a `PASSED` run — so an artifact of a `FAILED` run without a `checkId` is
     * refused at write time by [DstArtifact.of] rather than replaying as a false pass.
     */
    fun check(): DstCheck = checkId?.let(CheckRegistry::require) ?: DstCheck.none

    /** The runnable form of this artifact: graph, plan, budget and check, all resolved by name. */
    fun run(): DstRun = DstRun(graph(), plan(), budget, check())

    /**
     * Record a shrunk plan without touching the original ([CHA1-37]).
     *
     * Refuses a plan on a different seed ([CHA1-35]) and a plan containing a fault the original
     * did not: a shrink *reduces*, and a "shrunk" plan with a new fault is a different
     * experiment wearing the original's artifact.
     */
    fun withShrunkPlan(shrunk: FaultPlan, record: ShrinkRecord? = null): DstArtifact {
        require(shrunk.seed == seed) {
            "a shrunk plan must hold the run seed constant ([CHA1-35]): artifact seed=$seed, shrunk seed=${shrunk.seed}"
        }
        val originalIds = plan.faults.map { it.id }.toSet()
        val added = shrunk.faults.map { it.id }.filterNot { it in originalIds }
        require(added.isEmpty()) {
            "a shrunk plan may only drop faults, never add them; added: ${added.sorted()}"
        }
        return copy(shrunkPlan = PlanRecord(shrunk.faults.map(FaultCodecs::encode)), shrink = record)
    }

    /** Pretty JSON, exactly as [DstArtifacts.write] would put it on disk. */
    fun toJson(): String = DstArtifacts.json.encodeToString(serializer(), this)

    companion object {
        /** The artifact format version. Bump when a field's meaning changes, not when the rig does. */
        const val SCHEMA: Int = 1

        /**
         * Capture [report] — produced by [run] — as an artifact.
         *
         * @param checkId the id under which [DstRun.check] is registered in [CheckRegistry].
         *   **Required for a `FAILED` run**: without it a replay would run [DstCheck.none],
         *   observe nothing, and report the failure as reproduced-as-passing. Refusing here is
         *   the difference between an artifact and a fiction.
         */
        fun of(
            run: DstRun,
            report: DstReport,
            suite: String = report.graphId,
            checkId: String? = null,
            driver: DstDriver = DstDriver.IN_PROCESS,
            rig: RigStamp = DstRig.stamp(),
        ): DstArtifact {
            require(report.outcome != DstOutcome.FAILED || checkId != null) {
                "a FAILED run's artifact needs a checkId (register the check with CheckRegistry.register): " +
                    "replaying without the check would reproduce the run as PASSED, which is a false negative " +
                    "the rig will not write to disk"
            }
            return DstArtifact(
                rig = rig,
                suite = suite,
                seed = report.seed,
                graphId = report.graphId,
                checkId = checkId,
                budget = run.budget,
                driver = driver,
                plan = PlanRecord(report.plan.faults.map(FaultCodecs::encode)),
                observed = ObservedRun(
                    outcome = report.outcome,
                    steps = report.steps,
                    failingCheck = report.failingCheck?.message,
                    failingStep = report.failingCheck?.step,
                    traceDigest = report.traceDigest.hex,
                    traceEvents = report.trace.size,
                ),
            )
        }
    }
}

/**
 * Encodes and rebuilds one kind of [Fault] for an artifact.
 *
 * A codec is how a fault class stays a *value*: [encode] writes its configuration, [decode]
 * reconstructs an equivalent fault, and nothing about the artifact schema changes. Register
 * one alongside each fault class, in the same file as the class, so a fault and its codec
 * cannot drift apart.
 *
 * [owns] is asked of every candidate fault. Exactly one registered codec must claim a given
 * fault: zero means the plan cannot be written (and [FaultCodecs.encode] refuses rather than
 * writing an artifact that replays a *different* plan), and two means the artifact's `kind`
 * would be a coin toss.
 */
interface FaultCodec {
    val kind: String
    fun owns(fault: Fault): Boolean
    fun encode(fault: Fault): JsonObject
    fun decode(id: String, params: JsonObject): Fault
}

/**
 * The registry of [FaultCodec]s, in the same fail-loudly-with-alternatives shape as
 * [GraphRegistry] and [UnknownFaultTargetException]: an unencodable fault or an unknown kind
 * names itself and the registered set, never a bare "not found".
 */
object FaultCodecs {

    private val byKind = linkedMapOf<String, FaultCodec>()

    fun register(codec: FaultCodec): FaultCodec {
        val existing = byKind[codec.kind]
        require(existing == null || existing === codec) {
            "fault kind \"${codec.kind}\" is already registered to a different codec"
        }
        byKind[codec.kind] = codec
        return codec
    }

    /** Lambda form, for a codec that does not warrant a named class. */
    fun register(
        kind: String,
        owns: (Fault) -> Boolean,
        encode: (Fault) -> JsonObject,
        decode: (String, JsonObject) -> Fault,
    ): FaultCodec = register(
        object : FaultCodec {
            override val kind: String = kind
            override fun owns(fault: Fault): Boolean = owns(fault)
            override fun encode(fault: Fault): JsonObject = encode(fault)
            override fun decode(id: String, params: JsonObject): Fault = decode(id, params)
        },
    )

    fun kinds(): Set<String> = byKind.keys.toSet()

    /** Visible for suites that register per-test codecs and must not leak them into others. */
    fun unregister(kind: String) {
        byKind -= kind
    }

    fun encode(fault: Fault): FaultRecord {
        val claimants = byKind.values.filter { it.owns(fault) }
        if (claimants.isEmpty()) {
            throw IllegalArgumentException(
                "fault \"${fault.id}\" (${fault::class.simpleName}) has no registered codec, so its plan cannot be " +
                    "written to an artifact; registered kinds: ${byKind.keys.sorted()}. Register one with " +
                    "FaultCodecs.register(...) — the rig refuses to write an artifact whose plan it cannot rebuild.",
            )
        }
        require(claimants.size == 1) {
            "fault \"${fault.id}\" is claimed by ${claimants.size} codecs (${claimants.map { it.kind }.sorted()}); " +
                "a codec must claim only the faults it can encode"
        }
        val codec = claimants.single()
        return FaultRecord(fault.id, codec.kind, codec.encode(fault))
    }

    fun decode(record: FaultRecord): Fault {
        val codec = byKind[record.kind] ?: throw IllegalArgumentException(
            "unknown fault kind \"${record.kind}\" for fault \"${record.id}\"; registered kinds: " +
                "${byKind.keys.sorted()}. An artifact naming a kind this JVM has not registered cannot be replayed.",
        )
        return codec.decode(record.id, record.params)
    }
}

/**
 * Maps an id back to the [DstCheck] an artifact names, so replay does not need the original
 * test method — the same reason [GraphRegistry] exists for graphs ([CHA1-06]).
 *
 * A check is a *property*, so its id is a published name in exactly the way a graph id is:
 * renaming one orphans every artifact that recorded it.
 */
object CheckRegistry {

    private val checks = linkedMapOf<String, DstCheck>()

    fun register(id: String, check: DstCheck): DstCheck {
        require(id.isNotBlank()) { "a check needs a non-blank id — artifacts name checks by it" }
        val existing = checks[id]
        require(existing == null || existing === check) {
            "check id \"$id\" is already registered to a different check"
        }
        checks[id] = check
        return check
    }

    fun ids(): Set<String> = checks.keys.toSet()

    fun find(id: String): DstCheck? = checks[id]

    fun require(id: String): DstCheck = checks[id]
        ?: throw IllegalArgumentException("unknown check id \"$id\"; registered checks: ${checks.keys.sorted()}")

    /** Visible for suites that register per-test checks and must not leak them into others. */
    fun unregister(id: String) {
        checks -= id
    }
}

/**
 * Reading and writing [DstArtifact]s, and the one path rule the epic states as a requirement:
 * **artifacts live under the module's build directory and nowhere else** ([CHA1-54]).
 *
 * The rule is enforced, not documented: [requireUnderBuildDirectory] refuses a root with no
 * `build` element on its path, so a suite cannot quietly acquire a dependency on a writable
 * `$HOME`, a shared `/tmp`, or a checked-out source tree. A Gradle `Test` task's working
 * directory is its project directory, which is what makes the relative [DEFAULT_ROOT] resolve
 * to the *consuming* module's build directory rather than `:testkit`'s.
 */
object DstArtifacts {

    /** Relative to the module directory, i.e. a Gradle `Test` task's working directory. */
    const val DEFAULT_ROOT: String = "build/dst/failures"

    internal val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    /** `<module>/build/dst/failures`, resolved against the current working directory. */
    fun defaultRoot(): File = requireUnderBuildDirectory(File(DEFAULT_ROOT))

    /**
     * [CHA1-54]'s enforcement point: [root] must sit under a directory named `build`.
     *
     * The check is on the *path*, not on the filesystem, so it holds for a root that does not
     * exist yet — which is the usual case, since the first failing run is what creates it.
     */
    fun requireUnderBuildDirectory(root: File): File {
        val absolute: Path = root.absoluteFile.toPath().normalize()
        val underBuild = (0 until absolute.nameCount).any { absolute.getName(it).toString() == "build" }
        require(underBuild) {
            "[CHA1-54]: DST artifacts must be written under a module build directory, but \"$absolute\" has no " +
                "\"build\" path element. The rig must not require a writable path outside the build directory."
        }
        return root
    }

    /** `<root>/<suite>/<seed>.json` ([CHA1-31]). */
    fun pathFor(suite: String, seed: Long, root: File = defaultRoot()): File =
        requireUnderBuildDirectory(root).resolve(requireArtifactName(suite, "suite")).resolve("$seed.json")

    /** Write [artifact] to [pathFor], creating the directory. Returns the file written. */
    fun write(artifact: DstArtifact, root: File = defaultRoot()): File {
        val file = pathFor(artifact.suite, artifact.seed, root)
        file.parentFile.mkdirs()
        file.writeText(artifact.toJson())
        return file
    }

    /** Read an artifact, naming the file when the JSON is not one. */
    fun read(file: File): DstArtifact {
        require(file.isFile) { "no DST artifact at ${file.absolutePath}" }
        return parse(file.readText(), file.absolutePath)
    }

    fun parse(text: String, source: String = "<string>"): DstArtifact =
        try {
            json.decodeFromString(DstArtifact.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a readable DST artifact ($source): ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("not a readable DST artifact ($source): ${e.message}", e)
        }

    /**
     * A suite name is also a directory name, so it is constrained to something that cannot
     * escape [DEFAULT_ROOT] — `..`, an absolute path, or a separator would put an artifact
     * outside the build directory and break [CHA1-54] through the back door.
     */
    internal fun requireArtifactName(name: String, what: String): String {
        require(name.isNotBlank() && name.matches(SAFE_NAME)) {
            "$what \"$name\" is not usable as a directory name; use [A-Za-z0-9._-]+ (it names a directory under " +
                "$DEFAULT_ROOT, and anything else could place an artifact outside the build directory)"
        }
        require(name != "." && name != "..") { "$what \"$name\" is not usable as a directory name" }
        return name
    }

    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
}
