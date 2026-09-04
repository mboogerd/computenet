package civictech.testkit.dst

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.util.IdentityHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CHA1-31], [CHA1-32], [CHA1-34], [CHA1-54] and BS-1 — the replay artifact and its grading.
 *
 * ## Why these tests are shaped the way they are
 *
 * Replay is the claim in this epic that is easiest to fake: re-run the same objects in the
 * same process, observe the same answer, print `REPLAYED`. Such a test passes against a
 * `DstReplay` that ignores the file entirely.
 *
 * So the discriminating tests here are not the happy path. They are the three that **change a
 * byte in the artifact and require the verdict to change with it**: a mutated seed, a mutated
 * fault parameter, and a mutated rig version. Together they establish that the file's contents
 * — not the surviving in-memory objects — determine what the replay runs and how it is graded.
 * [ReplayTest.replayRebuildsThePlanFromTheFileNotFromMemory] is the sharpest of the three: it
 * edits a *fault's* parameter, which nothing but a genuine decode-from-JSON could honour.
 *
 * What none of this establishes is cross-process or cross-commit reproducibility. The rig does
 * not claim either: see [DstDriver] and [RigStamp].
 */
class ReplayTest {

    // ------------------------------------------------------------------ fixtures

    /**
     * A fault that drops every frame on one edge from a given step onwards.
     *
     * `Fault` is sealed to `:testkit` main, so a test source set cannot implement it and this
     * is a [ScriptedFault] — which holds lambdas and therefore cannot encode itself. The side
     * table below stands in for the configuration fields a real fault class ([CHA1-10]'s six)
     * carries. Only [encode] consults it: [decode] rebuilds purely from the JSON parameters,
     * which is what makes the mutated-parameter test above meaningful.
     */
    private val configs = IdentityHashMap<Fault, JsonObject>()

    private fun dropFrom(id: String, edge: String, fromStep: Int): Fault {
        val fault = ScriptedFault(
            id = id,
            targets = listOf(FaultTarget.Edge(edge)),
            description = "drop frames on $edge from step $fromStep",
            onInstall = { world ->
                world.edges.intercept(edge) { frame, step ->
                    if (step >= fromStep) {
                        world.trace.fault(id, port = edge)
                        emptyList()
                    } else {
                        listOf(frame)
                    }
                }
            },
        )
        configs[fault] = buildJsonObject {
            put("edge", edge)
            put("fromStep", fromStep)
        }
        return fault
    }

    private val codec = FaultCodecs.register(
        kind = DROP_KIND,
        owns = { it in configs.keys },
        encode = { configs.getValue(it) },
        decode = { id, params ->
            dropFrom(id, params.getValue("edge").jsonPrimitive.content, params.getValue("fromStep").jsonPrimitive.int)
        },
    )

    private val root = File("build/dst-selftest/replay")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        GraphRegistry.register(GRAPH)
        CheckRegistry.register(CHECK_ID, CHECK)
    }

    @AfterTest
    fun tearDown() {
        FaultCodecs.unregister(codec.kind)
        GraphRegistry.unregister(GRAPH.id)
        CheckRegistry.unregister(CHECK_ID)
    }

    private fun failingRun(seed: Long = 41L): DstRun =
        DstRun(GRAPH, FaultPlan.of(seed, dropFrom("drop-ab", "a->b", 2)), BUDGET, CHECK)

    private fun writeFailingArtifact(seed: Long = 41L): Pair<DstReport, File> {
        val run = failingRun(seed)
        val report = run.execute()
        assertEquals(DstOutcome.FAILED, report.outcome, "fixture must fail for the replay tests to mean anything")
        val file = DstArtifacts.write(
            DstArtifact.of(run, report, suite = SUITE, checkId = CHECK_ID),
            root,
        )
        return report to file
    }

    /** Read the artifact as raw JSON, apply [edit], write it back. The file is the only channel. */
    private fun mutateArtifact(file: File, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
        val obj = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
        edit(obj)
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(obj)))
    }

    // ------------------------------------------------------------------ BS-1

    /**
     * BS-1 ([CHA1-31], [CHA1-32]): a failing run's artifact re-runs to the *same* failing
     * check, at the same step, with an equal trace digest, and the verdict is `REPLAYED`.
     *
     * The recorded values are re-read out of the file's JSON text rather than taken from the
     * in-memory [DstReport], so what the replay is graded against is demonstrably what is on
     * disk.
     */
    @Test
    fun replayReproducesTheRecordedFailureExactly_BS1() {
        val (original, file) = writeFailingArtifact()

        val onDisk = Json.parseToJsonElement(file.readText()).jsonObject.getValue("observed").jsonObject
        val recordedDigest = onDisk.getValue("traceDigest").jsonPrimitive.content
        val recordedCheck = onDisk.getValue("failingCheck").jsonPrimitive.content
        val recordedStep = onDisk.getValue("failingStep").jsonPrimitive.int

        val result = DstReplay.from(file)

        assertEquals(ReplayVerdict.REPLAYED, result.verdict, result.message)
        val replayed = result.report!!
        assertEquals(DstOutcome.FAILED, replayed.outcome)
        assertEquals(recordedCheck, replayed.failingCheck!!.message, "same failing check")
        assertEquals(recordedStep, replayed.failingCheck!!.step, "same step")
        assertEquals(recordedDigest, replayed.traceDigest.hex, "equal trace digest")
        assertEquals(original.traceDigest, replayed.traceDigest)

        // The fault was rebuilt from the record, not carried over: it fired in the replayed run.
        assertTrue(replayed.appliedFaults.single { it.id == "drop-ab" }.fired > 0, "replayed plan is live")
        assertEquals(original.appliedFaults, replayed.appliedFaults)

        // The assertion form used by consumer suites.
        assertEquals(replayed.traceDigest, DstReplay.assertReplays(file).traceDigest)
    }

    /**
     * The anti-fake test: change a **fault parameter** in the artifact on disk and the replay
     * must run a different experiment and diverge. Nothing but a genuine decode-from-JSON of
     * the plan can produce that behaviour — the in-memory fault is untouched.
     */
    @Test
    fun replayRebuildsThePlanFromTheFileNotFromMemory() {
        val (original, file) = writeFailingArtifact()
        assertEquals(ReplayVerdict.REPLAYED, DstReplay.from(file).verdict, "control: unmutated artifact replays")

        // Push the drop window past the end of the run: the same fault, one parameter later.
        mutateArtifact(file) { obj ->
            val plan = obj.getValue("plan").jsonObject.toMutableMap()
            val faults = plan.getValue("faults").jsonArray
            val fault = faults.single().jsonObject.toMutableMap()
            val params = fault.getValue("params").jsonObject.toMutableMap()
            assertEquals(2, params.getValue("fromStep").jsonPrimitive.int, "fixture parameter moved")
            params["fromStep"] = JsonPrimitive(9_999)
            fault["params"] = JsonObject(params)
            plan["faults"] = JsonArray(listOf(JsonObject(fault)))
            obj["plan"] = JsonObject(plan)
        }

        val result = DstReplay.from(file)

        assertEquals(ReplayVerdict.DIVERGED, result.verdict, result.message)
        assertEquals(
            DstOutcome.PASSED,
            result.report!!.outcome,
            "the parameter on disk is what the replay ran: with the drop window out of range nothing is dropped",
        )
        assertNotEquals(original.traceDigest.hex, result.report!!.traceDigest.hex)
        assertTrue("outcome: recorded FAILED, replayed PASSED" in result.message, result.message)
    }

    // ------------------------------------------------------------------ CHA1-34

    /**
     * [CHA1-34]: a replay that does not reproduce the recorded failure fails loudly, naming the
     * divergence step and both digests, and is never reported as a pass.
     *
     * The mutation is the artifact's **seed**, which the rig treats as the one input every
     * other source of randomness derives from ([CHA1-30]) — so a different seed is a different
     * run, and grading it against the recorded observation must contradict.
     */
    @Test
    fun divergingReplayNamesTheDivergenceStepAndBothDigests_CHA1_34() {
        val (original, file) = writeFailingArtifact(seed = 41L)
        mutateArtifact(file) { it["seed"] = JsonPrimitive(42L) }

        val result = DstReplay.from(file)

        assertEquals(ReplayVerdict.DIVERGED, result.verdict, result.message)
        assertNotEquals(
            original.traceDigest.hex,
            result.report!!.traceDigest.hex,
            "the mutated seed must actually change the run, or this test proves nothing",
        )
        assertTrue(original.traceDigest.hex in result.message, "message names the recorded digest")
        assertTrue(result.report!!.traceDigest.hex in result.message, "message names the replayed digest")
        assertTrue("divergence at step" in result.message, "message names the divergence step: ${result.message}")
        assertTrue("NOT a pass" in result.message, "a diverging replay is never reported as passed")

        val thrown = assertFailsWith<AssertionError> { DstReplay.assertReplays(file) }
        assertTrue(original.traceDigest.hex in thrown.message!!)
    }

    /**
     * The digest is graded on its own account, not merely as a side effect of a run that also
     * differed in outcome or step count.
     *
     * This test exists because of a mutation that survived without it: deleting the trace-digest
     * comparison from [DstReplay.grade] left every other replay test green, since a seed or
     * parameter mutation changes the outcome too. Grading a report that differs from the
     * recorded observation in *nothing but* the digest — and, separately, in nothing but the
     * trace length — is the only shape that pins [CHA1-32]'s "with the same trace digest".
     */
    @Test
    fun gradingDetectsADigestOnlyDivergence_CHA1_32() {
        val (original, file) = writeFailingArtifact()
        val artifact = DstArtifacts.read(file)
        assertEquals(ReplayVerdict.REPLAYED, DstReplay.grade(artifact, original).verdict, "control")

        val forged = original.copy(traceDigest = TraceDigest("00".repeat(32)))
        val digestOnly = DstReplay.grade(artifact, forged)
        assertEquals(ReplayVerdict.DIVERGED, digestOnly.verdict, digestOnly.message)
        assertTrue("trace digest: recorded ${original.traceDigest.hex}" in digestOnly.message, digestOnly.message)
        assertTrue("00".repeat(32) in digestOnly.message, digestOnly.message)

        val lengthOnly = DstReplay.grade(artifact, original.copy(trace = original.trace.drop(1)))
        assertEquals(ReplayVerdict.DIVERGED, lengthOnly.verdict, lengthOnly.message)
        assertTrue("trace length: recorded" in lengthOnly.message, lengthOnly.message)
    }

    // ------------------------------------------------------------------ risk 6 / CHA1-40

    /**
     * Epic §9 risk 6: a digest is valid within a commit, not across them, so a replay recorded
     * by a different rig version is `INDETERMINATE` — not `DIVERGED` and not a pass. Graded
     * *before* re-running, so the run that cannot be compared is not even performed.
     */
    @Test
    fun replayAgainstADifferentRigVersionIsIndeterminate_risk6() {
        val (_, file) = writeFailingArtifact()
        mutateArtifact(file) { obj ->
            obj["rig"] = JsonObject(
                obj.getValue("rig").jsonObject.toMutableMap()
                    .also { it["version"] = JsonPrimitive(DstRig.VERSION + "-other") },
            )
        }

        val result = DstReplay.from(file)

        assertEquals(ReplayVerdict.INDETERMINATE, result.verdict, result.message)
        assertNull(result.report, "an ungradable artifact is not re-run")
        assertTrue("INDETERMINATE, not FAILED" in result.message, result.message)
        assertFailsWith<AssertionError> { DstReplay.assertReplays(file) }
    }

    /**
     * [CHA1-40]: a run driven across JVMs is marked non-deterministic and claims no replay
     * reproducibility — the artifact is kept (it records what was tried) but grading it is
     * refused, in-process re-run or not.
     */
    @Test
    fun multiJvmRunClaimsNoReplayReproducibility_CHA1_40() {
        val run = failingRun()
        val report = run.execute()
        val file = DstArtifacts.write(
            DstArtifact.of(run, report, suite = SUITE, checkId = CHECK_ID, driver = DstDriver.MULTI_JVM),
            root,
        )

        val result = DstReplay.from(file)

        assertEquals(ReplayVerdict.INDETERMINATE, result.verdict, result.message)
        assertNull(result.report, "a run the rig makes no claim about is not re-run and graded")
        assertTrue("CHA1-40" in result.message, result.message)
        assertTrue(!DstDriver.MULTI_JVM.claimsReplayReproducibility)
        assertTrue(DstDriver.IN_PROCESS.claimsReplayReproducibility)
    }

    // ------------------------------------------------------------------ CHA1-54

    /** [CHA1-54]: artifacts go under a module build directory, and any other root is refused. */
    @Test
    fun artifactsAreWrittenUnderTheModuleBuildDirectoryOnly_CHA1_54() {
        val (_, file) = writeFailingArtifact(seed = 7L)
        assertEquals(File(root, "$SUITE/7.json").absolutePath, file.absolutePath)
        assertTrue(DstArtifacts.DEFAULT_ROOT.startsWith("build/"))
        assertEquals(
            File("build/dst/failures/$SUITE/7.json").absolutePath,
            DstArtifacts.pathFor(SUITE, 7L).absolutePath,
        )

        val outside = assertFailsWith<IllegalArgumentException> {
            DstArtifacts.pathFor(SUITE, 7L, File(System.getProperty("java.io.tmpdir"), "dst-outside"))
        }
        assertTrue("CHA1-54" in outside.message!!, outside.message!!)

        // A suite name cannot escape the root either.
        assertFailsWith<IllegalArgumentException> { DstArtifacts.pathFor("../../escape", 7L, root) }
    }

    // ------------------------------------------------------------------ refusals

    /** An unencodable plan is refused rather than written as an artifact that replays wrongly. */
    @Test
    fun aFaultWithNoCodecRefusesToWriteAnArtifact() {
        val run = DstRun(GRAPH, FaultPlan.of(41L, ScriptedFault("uncodeable")), BUDGET, CHECK)
        val report = run.execute()
        val e = assertFailsWith<IllegalArgumentException> {
            DstArtifact.of(run, report, suite = SUITE, checkId = CHECK_ID)
        }
        assertTrue("uncodeable" in e.message!! && DROP_KIND in e.message!!, e.message!!)
    }

    /** A `FAILED` run whose artifact cannot name its check would replay as a false pass. */
    @Test
    fun aFailedRunsArtifactMustNameItsCheck() {
        val run = failingRun()
        val report = run.execute()
        val e = assertFailsWith<IllegalArgumentException> { DstArtifact.of(run, report, suite = SUITE) }
        assertTrue("checkId" in e.message!!, e.message!!)
    }

    /** An artifact naming a fault kind this JVM never registered cannot be replayed silently. */
    @Test
    fun anUnknownFaultKindIsNamedAlongsideTheRegisteredSet() {
        val (_, file) = writeFailingArtifact()
        FaultCodecs.unregister(DROP_KIND)
        val e = assertFailsWith<IllegalArgumentException> { DstReplay.from(file) }
        assertTrue(DROP_KIND in e.message!!, e.message!!)
    }

    // ------------------------------------------------------------------ shrinker slot

    /**
     * The shape the shrinker task ([CHA1-35], [CHA1-37]) inherits: one seed field at the top
     * level, an original plan that cannot be overwritten, and a shrunk-plan slot that can only
     * ever drop faults.
     */
    @Test
    fun theArtifactHoldsOneSeedAndAShrunkPlanCannotChangeIt_CHA1_35_37() {
        val (_, file) = writeFailingArtifact()
        val artifact = DstArtifacts.read(file)

        // [CHA1-35] structurally: PlanRecord has no seed field at all, so no plan can disagree.
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(41L, obj.getValue("seed").jsonPrimitive.long)
        assertTrue("seed" !in obj.getValue("plan").jsonObject.keys, "a stored plan carries no seed of its own")

        val shrunk = artifact.withShrunkPlan(artifact.plan().without("drop-ab"), ShrinkRecord(3, 1))
        assertEquals(artifact.plan, shrunk.plan, "[CHA1-37]: the original plan is never overwritten")
        assertEquals(emptyList(), shrunk.shrunkPlan!!.faults)
        assertEquals(41L, shrunk.seed)

        assertFailsWith<IllegalArgumentException> {
            artifact.withShrunkPlan(FaultPlan.of(42L, dropFrom("drop-ab", "a->b", 2)))
        }
        assertFailsWith<IllegalArgumentException> {
            artifact.withShrunkPlan(
                FaultPlan.of(41L, dropFrom("drop-ab", "a->b", 2), dropFrom("drop-ba", "b->a", 2)),
            )
        }
    }

    // ------------------------------------------------------------------ decode-only JVM

    /**
     * A JVM whose only contact with a fault class is *reading an artifact that names its kind*
     * can still decode it (computenet-trpc).
     *
     * ## Why the isolated class loader is the test, not scaffolding around it
     *
     * `FaultCodec`s register from their fault class's companion object, so the registry is a
     * function of *what this JVM has loaded*. Every landed consumer builds its plans in the
     * same process that replays them, which loads every class it will later need — so a test
     * written in the ordinary way passes against a registry that would be empty for a real
     * replay tool, and proves nothing at all. Worse, it would pass **for a reason it cannot
     * see**: a sibling test method in the same forked JVM having constructed the fault first
     * (the ordering effect [FaultCodecRoundTripTest.LANDED_KINDS] documents).
     *
     * So the decode happens in a child [URLClassLoader] whose parent is the *platform* loader:
     * nothing of `civictech.testkit` is shared with this JVM's loader, and the only class the
     * test touches inside it is `DstArtifacts`. [assertSame] on the loader is the pin on that —
     * a decoded fault loaded by this test's own loader would mean the isolation leaked and the
     * assertion below is vacuous.
     *
     * ## What makes the kind list complete rather than "the ones I thought of"
     *
     * [SHIPPED_FAULT_FIXTURES] is a hand-written map — it has to be, because only a fault
     * class's own constructor knows what a *valid* instance of it is — but it is never trusted
     * as the enumeration. It is checked against `FaultCodecs.kinds()` **as the isolated loader
     * reports it**, i.e. against whatever the eager registration list actually registered. A
     * fault class added without a fixture here fails on that comparison, and one added without
     * an eager-list entry fails
     * [theEagerRegistrationListNamesEveryShippedFaultCodec_computenet_trpc], which reads the
     * compiled class files rather than any list a human wrote.
     */
    @Test
    fun everyShippedFaultKindDecodesInAJvmThatNeverConstructedTheFault_CHA1_31() {
        val json = decodeOnlyArtifactJson()

        isolatedRigLoader().use { loader ->
            val artifacts = loader.loadClass("civictech.testkit.dst.DstArtifacts")
            val artifact = artifacts.callObjectMethod(
                "parse",
                listOf(String::class.java, String::class.java),
                listOf(json, "<decode-only-fixture>"),
            )
            val plan = artifact.javaClass.getMethod("plan").invokeUnwrapped(artifact)
            val faults = plan.javaClass.getMethod("getFaults").invokeUnwrapped(plan) as List<*>

            assertEquals(SHIPPED_FAULT_FIXTURES.size, faults.size)
            faults.forEach { fault ->
                assertSame(
                    loader,
                    fault!!.javaClass.classLoader,
                    "the decoded fault must come from the isolated loader, or this test proves nothing",
                )
            }
            assertEquals(
                SHIPPED_FAULT_FIXTURES.keys.sorted(),
                faults.map { it!!.javaClass.getMethod("getId").invokeUnwrapped(it) as String }.sorted(),
                "every shipped kind's fixture must decode, keyed by its own kind as the fault id",
            )

            val faultCodecs = loader.loadClass("civictech.testkit.dst.FaultCodecs")
            @Suppress("UNCHECKED_CAST")
            val kinds = faultCodecs.callObjectMethod("kinds", emptyList(), emptyList()) as Set<String>
            assertEquals(
                SHIPPED_FAULT_FIXTURES.keys.sorted(),
                kinds.sorted(),
                "the fixtures above must cover exactly the kinds a fresh JVM registers eagerly — " +
                    "a new fault class means a new fixture here, not a quietly narrower test",
            )
        }
    }

    /**
     * [ShippedFaults.CLASSES] names **every** class in this module that registers a
     * [FaultCodec] — checked against the compiled class files, not against a list.
     *
     * ## Why this test is the actual deliverable of computenet-trpc
     *
     * The eager list re-centralises what per-class registration decentralised, and a central
     * list that can fall behind silently would just move the bug: a fault class added in six
     * months would register on load exactly as today, pass every round-trip suite in this
     * repository, and be undecodable in the one JVM that matters — with nothing red anywhere.
     * "Nobody will forget" is not a mechanism, so this is the mechanism.
     *
     * The enumeration is authoritative because it is the shipped artefact itself: every
     * `.class` file under the code source of [FaultCodec] is opened and asked, via reflection,
     * whether it *declares a field of type [FaultCodec]* — which is precisely what a Kotlin
     * `companion object { val CODEC: FaultCodec = FaultCodecs.register(...) }` compiles to (a
     * static field on the enclosing class). Adding a fault class in the prescribed shape
     * therefore fails this test until [ShippedFaults.CLASSES] names it, and deleting one fails
     * it until the entry goes.
     *
     * `Class.forName(name, false, ...)` and `declaredFields` are both non-initialising, so the
     * scan does not register anything and cannot mask what it is looking for.
     */
    @Test
    fun theEagerRegistrationListNamesEveryShippedFaultCodec_computenet_trpc() {
        val declaring = classesDeclaringAFaultCodecField()
        assertTrue(
            declaring.size >= 6,
            "the scan found only ${declaring.size} codec-declaring classes, which cannot be right — " +
                "it has stopped seeing the class files and would pass vacuously: $declaring",
        )
        assertEquals(
            declaring.sorted(),
            ShippedFaults.CLASSES.map { it.name }.sorted(),
            "ShippedFaults.CLASSES must name exactly the classes that register a FaultCodec. A fault class " +
                "missing from it registers only when something loads it, which is the decode-only hole " +
                "computenet-trpc closed; a stale entry names a class that no longer exists.",
        )
    }

    companion object {
        private const val DROP_KIND = "dst-selftest-drop-from-step"
        private const val SUITE = "dst-selftest-replay"
        private const val CHECK_ID = "dst-selftest-all-chains-complete"
        private const val BUDGET = 5_000

        /** Distinct from [SweepTest]'s graph: a `GraphSpec` id is a globally registered name. */
        private val GRAPH: GraphSpec = SelfTestGraphs.crossTalk(chains = 4, rounds = 6)

        /**
         * The property: every chain ran to completion, counted from the trace so the check is a
         * pure function of the run and therefore reproducible on replay.
         */
        private val CHECK = DstCheck { world ->
            val delivered = world.traceEvents().count { it.port == "recv" }
            if (delivered < EXPECTED_DELIVERIES) {
                throw AssertionError("only $delivered of $EXPECTED_DELIVERIES chain deliveries arrived")
            }
        }

        /** `chains * (rounds + 1)` hops in each direction, with no frame lost. */
        private const val EXPECTED_DELIVERIES = 4 * 7 * 2

        // -------------------------------------------------------------- decode-only fixtures

        /**
         * One valid instance of every fault class `:testkit` ships, keyed by its published kind
         * and carrying that kind as its fault id (so a decoded fault names the fixture it came
         * from without a second lookup table).
         *
         * Hand-written on purpose, and never the enumeration: only a fault class's own
         * constructor knows what a valid instance is, so the *values* cannot be derived — but
         * the *key set* is asserted against the registry of a fresh JVM, and the class list
         * behind that registry is asserted against the compiled class files. Every fault
         * activates far past any run here; nothing in this file executes them.
         */
        private val SHIPPED_FAULT_FIXTURES: Map<String, Fault> = listOf(
            CrashFault.atQuiescence(CrashFault.KIND, host = "h", atStep = 9_000, journal = "j"),
            PartitionFault.drop(PartitionFault.KIND, edge = "e", from = 9_000),
            JournalFault(
                JournalFault.KIND,
                journal = "j",
                mutation = JournalMutation.TruncateTail(1),
                window = StepWindow(9_000),
            ),
            RestartAtFrontierFault(
                RestartAtFrontierFault.KIND,
                host = "h",
                journal = "j",
                atStep = 9_000,
                prefix = 2,
            ),
            ReorderFault.crossLink("reorder", edge = "e", window = 4, from = 9_000, until = 9_100),
            DuplicateFault.frames("duplicate", edge = "e", copies = 2, from = 9_000, until = 9_100),
            JoinEvent(JoinEvent.KIND, "peer1", 9_000),
            RejoinEvent(RejoinEvent.KIND, "peer2", 9_000),
            DepartEvent(DepartEvent.KIND, "peer3", 9_000, DepartureMode.EVICT_NO_CLOSE),
            ReassignEvent(ReassignEvent.KIND, "peer0", 9_000, "interest-3", 5L),
        ).associateBy { FaultCodecs.encode(it).kind }

        /** The artifact a decode-only JVM is handed: one record of every shipped kind, nothing else. */
        private fun decodeOnlyArtifactJson(): String = DstArtifact(
            rig = DstRig.stamp(),
            suite = "dst-selftest-decode-only",
            seed = 7L,
            graphId = "dst-selftest-decode-only-graph",
            checkId = "dst-selftest-decode-only-check",
            budget = 16,
            plan = PlanRecord(SHIPPED_FAULT_FIXTURES.values.map(FaultCodecs::encode)),
            observed = ObservedRun(
                outcome = DstOutcome.FAILED,
                steps = 1,
                failingCheck = "decode-only fixture",
                failingStep = 1,
                traceDigest = "0",
                traceEvents = 1,
            ),
        ).toJson()

        /**
         * A loader that shares **no** application class with this JVM: its parent is the
         * platform loader, so `civictech.testkit.dst` — and the Kotlin runtime under it — is
         * loaded afresh and its `FaultCodecs` registry starts empty.
         *
         * The URLs are gathered from three places because no single one is dependable across
         * runners: a Gradle test worker loads test classes through a `URLClassLoader` whose
         * `urls` hold the real runtime classpath while `java.class.path` may name only the
         * worker jar, and a plain `java -cp` run is the other way round. The code-source
         * locations are the backstop for both.
         */
        private fun isolatedRigLoader(): URLClassLoader {
            val urls = linkedSetOf<URL>()
            generateSequence(ReplayTest::class.java.classLoader as ClassLoader?) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .forEach { urls += it.urLs }
            System.getProperty("java.class.path").orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .forEach { urls += File(it).absoluteFile.toURI().toURL() }
            listOf(FaultCodec::class.java, DstArtifact::class.java, Json::class.java, Unit::class.java)
                .mapNotNull { it.protectionDomain?.codeSource?.location }
                .forEach { urls += it }
            return URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
        }

        /**
         * Every class under [FaultCodec]'s own code source that declares a [FaultCodec]-typed
         * field — i.e. every fault class carrying the prescribed `companion object` `CODEC`.
         *
         * The code source is `:testkit`'s compiled main output (a directory under Gradle, a jar
         * for a consumer), which is why this is a statement about what the module *ships*
         * rather than about what some test happened to import. Test classes are outside it, so
         * a per-suite codec ([DROP_KIND] here) is correctly invisible.
         */
        private fun classesDeclaringAFaultCodecField(): List<String> {
            val loader = FaultCodec::class.java.classLoader
            val source = FaultCodec::class.java.protectionDomain.codeSource.location
            val root = File(source.toURI())
            val names: List<String> = if (root.isDirectory) {
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
                    .toList()
            } else {
                java.util.zip.ZipFile(root).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".class") }
                        .map { it.removeSuffix(".class").replace('/', '.') }
                        .toList()
                }
            }
            return names
                .filter { it.startsWith("civictech.testkit.dst.") }
                .filter { name ->
                    // `initialize = false`: the scan must not register the very thing it measures.
                    val type = Class.forName(name, false, loader)
                    type.declaredFields.any { it.type == FaultCodec::class.java }
                }
                .sorted()
        }

        /** Invoke a method on a Kotlin `object` living in another loader, unwrapping reflection. */
        private fun Class<*>.callObjectMethod(
            method: String,
            paramTypes: List<Class<*>>,
            args: List<Any?>,
        ): Any = getMethod(method, *paramTypes.toTypedArray())
            .invokeUnwrapped(getField("INSTANCE").get(null), *args.toTypedArray())

        /**
         * Rethrow what the called code threw, not `InvocationTargetException`. The failure this
         * test exists for is `FaultCodecs.decode`'s "unknown fault kind" message, and a wrapper
         * would hide it behind a reflection stack.
         */
        private fun Method.invokeUnwrapped(receiver: Any?, vararg args: Any?): Any =
            try {
                invoke(receiver, *args)!!
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
    }
}
