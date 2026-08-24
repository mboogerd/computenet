package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Mutate one named journal's records for a window of controller steps ([CHA1-19], [CHA1-20]).
 *
 * ## The seam it reaches through, and nothing else
 *
 * `DstWorld.journals` (seam 3 of 6) hands the graph builder a stable *view* whose decoration
 * chain is resolved on every call, so installing a [MutatingJournal] mid-run takes effect on
 * the journal the host is already holding — no re-wiring, no host rebuild. That is the entire
 * mechanism here: this fault registers a decoration at [StepWindow.from] and removes it at
 * [StepWindow.until]. Nothing in `DstWorld` knows what a journal mutation is.
 *
 * ## What a window means for a journal, which is not what it means for an edge
 *
 * A frame-plane fault fires on traffic, so a window is a window over the frames crossing an
 * edge. A journal fault fires on **calls into the journal**, and a journal's two call sites are
 * very differently distributed in time: `append` happens continuously while the host accepts
 * work, while `replay` happens *once*, at a recovery, which is usually a single step.
 *
 * The practical consequence, and the reason [replayMutations] exists as a factory rather than
 * as advice: a replay-side mutation ([JournalMutation.TruncateTail], `TruncatePrefix`,
 * `CorruptAt`, `DuplicateAt`, `ReorderAt`) does nothing at all unless the window contains the
 * step a recovery happens on, and will be reported `inert` ([CHA1-24], BS-13) if it does not.
 * Pair one with a [RestartAtFrontierFault] whose restart step is inside the window, or open the
 * window at step 0 and never heal ([StepWindow.ALWAYS]).
 * [JournalMutation.FailAppendAfter] is the opposite case: it fires on ordinary traffic and a
 * narrow window is exactly right.
 *
 * ## Firing counts
 *
 * The fault fires when the mutation **actually changed something** — a replay whose record list
 * differs, or a refused append. An index past the end of a shorter-than-expected journal is a
 * no-op, and the report says `inert` rather than claiming a mutation that never happened. That
 * is deliberate: `CorruptAt(40)` against a 12-record journal is the shape of a plan whose author
 * mis-modelled the graph, and it must not read as a passing adversarial run.
 */
data class JournalFault(
    override val id: String,
    val journal: String,
    val mutation: JournalMutation,
    val window: StepWindow = StepWindow.ALWAYS,
) : Fault {

    /** Removes the decoration at the healing step. Per-run state, created in [onStep]. */
    private var installed: AutoCloseable? = null

    /**
     * The append count, created **once per installation** and shared by every decorator instance
     * the seam resolves. See [JournalLedger]: `DstWorld.journals` re-runs the decoration lambda
     * on every journal call, so a counter kept inside the decorator resets on each one and
     * [JournalMutation.FailAppendAfter] silently accepts everything.
     */
    private var ledger: JournalLedger? = null

    override val targets: List<FaultTarget> get() = listOf(FaultTarget.Journal(journal))

    override fun describe(): String = "journal($journal, $window): ${mutation.describe()}"

    /**
     * Nothing to do: a journal mutation is registered by [onStep] at [StepWindow.from], which
     * fires for step 0 as well, so there is no window this method would have to special-case.
     *
     * The target's existence is already validated before any `install` runs ([CHA1-23]), so a
     * plan naming an undeclared journal has failed the run before reaching here.
     */
    override fun install(world: DstWorld) = Unit

    override fun onStep(world: DstWorld, step: Int) {
        if (step == window.from && installed == null) {
            val shared = JournalLedger().also { ledger = it }
            installed = world.journals.decorate(journal) { inner ->
                MutatingJournal(inner, mutation, journal, shared) { what ->
                    world.trace.fault(id, port = journal)
                    world.trace.emit(port = "$journal:$what")
                }
            }
        } else if (window.healedAt(step)) {
            installed?.close()
            installed = null
        }
    }

    companion object {

        /** The `kind` a [JournalFault] is written under in a [FaultRecord]. A published name. */
        const val KIND: String = "dst-journal"

        /**
         * This class's [FaultCodec], registered when the class is loaded — see
         * [CrashFault.CODEC] for why the companion object is the registration point.
         *
         * ## The mutation is a discriminator plus flat parameters
         *
         * [JournalMutation] is a sealed hierarchy, and it is written as a `mutation` tag naming
         * which one plus that mutation's own parameters at the **top level** of `params` —
         * `n`, `index`, `i`/`j`, `corruption`. Flat for [PartitionFault.CODEC]'s reason: a
         * nested object is unreachable to [ReductionStrategies.numericParamToward], and `n` and
         * `index` are exactly the knobs a shrinker wants to walk toward zero. The tags are
         * written out as literals rather than derived from class names, so renaming a mutation
         * class does not silently orphan every artifact that recorded it.
         *
         * [JournalMutation.CorruptAt.corruption] is a `ByteArray`, encoded as a lowercase hex
         * **string** rather than a JSON array. A string primitive is what keeps
         * `numericParamToward` safe if a caller ever names `"corruption"`: it reads the
         * parameter's `jsonPrimitive`, which throws on an array, and yields a null `double` on
         * a string — a skip instead of a crash.
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = KIND,
            owns = { it is JournalFault },
            encode = { fault ->
                val journalFault = fault as JournalFault
                buildJsonObject {
                    put("journal", journalFault.journal)
                    put("from", journalFault.window.from)
                    put("until", journalFault.window.until)
                    encodeMutation(journalFault.mutation)
                }
            },
            decode = { id, params -> decodeFrom(id, params) },
        )

        private fun decodeFrom(id: String, params: JsonObject): JournalFault = JournalFault(
            id = id,
            journal = params.getValue("journal").jsonPrimitive.content,
            mutation = decodeMutation(params),
            window = StepWindow(
                from = params.getValue("from").jsonPrimitive.int,
                until = params.getValue("until").jsonPrimitive.int,
            ),
        )

        private const val TRUNCATE_TAIL = "truncate-tail"
        private const val TRUNCATE_PREFIX = "truncate-prefix"
        private const val CORRUPT_AT = "corrupt-at"
        private const val DUPLICATE_AT = "duplicate-at"
        private const val REORDER_AT = "reorder-at"
        private const val FAIL_APPEND_AFTER = "fail-append-after"

        private fun JsonObjectBuilder.encodeMutation(mutation: JournalMutation) {
            when (mutation) {
                is JournalMutation.TruncateTail -> {
                    put("mutation", TRUNCATE_TAIL)
                    put("n", mutation.n)
                }

                is JournalMutation.TruncatePrefix -> {
                    put("mutation", TRUNCATE_PREFIX)
                    put("n", mutation.n)
                }

                is JournalMutation.CorruptAt -> {
                    put("mutation", CORRUPT_AT)
                    put("index", mutation.index)
                    put("corruption", mutation.corruption.toHex())
                }

                is JournalMutation.DuplicateAt -> {
                    put("mutation", DUPLICATE_AT)
                    put("index", mutation.index)
                }

                is JournalMutation.ReorderAt -> {
                    put("mutation", REORDER_AT)
                    put("i", mutation.i)
                    put("j", mutation.j)
                }

                is JournalMutation.FailAppendAfter -> {
                    put("mutation", FAIL_APPEND_AFTER)
                    put("n", mutation.n)
                }
            }
        }

        private fun decodeMutation(params: JsonObject): JournalMutation {
            val tag = params.getValue("mutation").jsonPrimitive.content
            fun int(name: String): Int = params.getValue(name).jsonPrimitive.int
            return when (tag) {
                TRUNCATE_TAIL -> JournalMutation.TruncateTail(int("n"))
                TRUNCATE_PREFIX -> JournalMutation.TruncatePrefix(int("n"))
                CORRUPT_AT -> JournalMutation.CorruptAt(
                    int("index"),
                    params.getValue("corruption").jsonPrimitive.content.fromHex(),
                )

                DUPLICATE_AT -> JournalMutation.DuplicateAt(int("index"))
                REORDER_AT -> JournalMutation.ReorderAt(int("i"), int("j"))
                FAIL_APPEND_AFTER -> JournalMutation.FailAppendAfter(int("n"))
                else -> throw IllegalArgumentException(
                    "unknown journal mutation \"$tag\"; known: " +
                        "${listOf(
                            TRUNCATE_TAIL, TRUNCATE_PREFIX, CORRUPT_AT,
                            DUPLICATE_AT, REORDER_AT, FAIL_APPEND_AFTER,
                        ).sorted()}. An artifact naming a mutation this rig does not have " +
                        "cannot be replayed.",
                )
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.fromHex(): ByteArray {
            require(length % 2 == 0) { "a hex-encoded byte string has an even length; got \"$this\"" }
            return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        /** Drop the last [n] records ([JournalMutation.TruncateTail]). */
        fun truncateTail(id: String, journal: String, n: Int, window: StepWindow = StepWindow.ALWAYS) =
            JournalFault(id, journal, JournalMutation.TruncateTail(n), window)

        /** Drop the first [n] records ([JournalMutation.TruncatePrefix]). */
        fun truncatePrefix(id: String, journal: String, n: Int, window: StepWindow = StepWindow.ALWAYS) =
            JournalFault(id, journal, JournalMutation.TruncatePrefix(n), window)

        /** Corrupt record [index] ([JournalMutation.CorruptAt]). */
        fun corruptAt(id: String, journal: String, index: Int, window: StepWindow = StepWindow.ALWAYS) =
            JournalFault(id, journal, JournalMutation.CorruptAt(index), window)

        /** Replay record [index] twice ([JournalMutation.DuplicateAt]). */
        fun duplicateAt(id: String, journal: String, index: Int, window: StepWindow = StepWindow.ALWAYS) =
            JournalFault(id, journal, JournalMutation.DuplicateAt(index), window)

        /** Swap records [i] and [j] ([JournalMutation.ReorderAt]). */
        fun reorderAt(id: String, journal: String, i: Int, j: Int, window: StepWindow = StepWindow.ALWAYS) =
            JournalFault(id, journal, JournalMutation.ReorderAt(i, j), window)

        /**
         * Accept [n] appends then refuse ([JournalMutation.FailAppendAfter]). The one mutation
         * whose window is a window over ordinary traffic rather than over a recovery step.
         */
        fun failAppendAfter(id: String, journal: String, n: Int, window: StepWindow = StepWindow.ALWAYS) =
            JournalFault(id, journal, JournalMutation.FailAppendAfter(n), window)

        /**
         * The five mutations that rewrite what recovery **reads**, in the order they appear in
         * [CHA1-19]. `FailAppendAfter` is deliberately absent: it is the only one that damages
         * the write path, so a suite sweeping "every replay-side mutation" over a fixed recovery
         * step would report it inert every time and learn nothing.
         *
         * @param records the journal's record count, so the indices produced are in range for
         *   the log under test. A mutation whose index is out of range is a no-op that reports
         *   inert, which is correct but wastes the run.
         */
        fun replayMutations(records: Int): List<JournalMutation> {
            val mid = records / 2
            return listOf(
                JournalMutation.TruncateTail(1),
                JournalMutation.TruncatePrefix(1),
                JournalMutation.CorruptAt(mid),
                JournalMutation.DuplicateAt(mid),
                JournalMutation.ReorderAt(0, (records - 1).coerceAtLeast(0)),
            )
        }
    }
}
