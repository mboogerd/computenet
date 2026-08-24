package civictech.testkit.dst

import civictech.cell.durability.Journal
import civictech.cell.host.ManagedHost
import civictech.cell.host.RecoveryIncomplete

/**
 * Index-level surgery on a journal's opaque records — the substrate every journal-plane fault
 * is assembled from ([CHA1-19]).
 *
 * ## Why every mutation is positional
 *
 * `civictech.cell.durability.Journal` is an append-only log of **opaque `ByteArray`s**: the
 * durable host writes a one-byte record tag followed by either a `WireCodec`-encoded
 * invocation frame or a Java-serialised record whose class is `private` to
 * `civictech.cell.host.HostDurability`. So a testkit decorator can *reorder, drop, duplicate,
 * corrupt and refuse* records without decoding a single one — and cannot decode one at all.
 * That is not a limitation this file works around; it is the reason all six mutations of
 * [CHA1-19] are expressible as pure `List<ByteArray>` transforms, and the reason
 * [JournalRecords] can go no further than the tag byte.
 *
 * ## The decorator contract, including the part that is easy to miss
 *
 * A journal decorator must forward **[Journal.formatVersion]** from its delegate. The member
 * arrived with the format-version refusal (computenet-437w) and carries an interface default,
 * so a decorator that omits it *compiles* and silently reports the build's
 * `JOURNAL_FORMAT_VERSION` for a delegate pinned to another one — turning the refusal
 * `Journal.replay` exists to raise into a version mismatch nobody sees. Every decorator here
 * forwards it, and [MutatingJournal] is where a new mutation should be added rather than in a
 * fresh `object : Journal`.
 */
object JournalRecords {

    /**
     * The record type tag, byte 0 of every journal record.
     *
     * These five constants **duplicate** `private const val RECORD_*` in
     * `civictech.cell.host.HostDurability`. They are duplicated rather than shared because the
     * bead this file implements forbids widening kernel visibility, and duplication is the
     * only other option: `HostDurability.recoverFrom` dispatches on `record[0]`, so the tag is
     * genuinely identifiable from here — it is the *values* that are private, not the layout.
     *
     * The duplication is pinned, not trusted: `JournalFaultTest`'s
     * `journal record tags match what the kernel actually writes` drives a real durable graph
     * and asserts the tags it observes are exactly members of [KNOWN], with [FRAME] and
     * [FRONTIER] both present. A kernel renumbering fails that test rather than silently
     * turning [indicesOfTag] into a filter that matches nothing.
     */
    const val FRAME: Byte = 1
    const val CHECKPOINT: Byte = 2
    const val FRONTIER: Byte = 3
    const val OUTLET_WAVE: Byte = 4
    const val BASELINE: Byte = 5

    /** Every tag the kernel writes today. See the note on [FRAME] for how this stays true. */
    val KNOWN: Set<Byte> = setOf(FRAME, CHECKPOINT, FRONTIER, OUTLET_WAVE, BASELINE)

    /**
     * A tag no kernel record type uses, so a record replaced by it reaches
     * `HostDurability.recoverFrom`'s `else -> error("unknown journal record type ...")` branch
     * and raises [RecoveryIncomplete] at exactly that index. [JournalMutation.CorruptAt]'s
     * default corruption, chosen so the *index* of the failure is the thing under test rather
     * than whichever deserialisation error a random byte string happens to produce.
     */
    const val UNKNOWN: Byte = 99

    /** Byte 0 of [record], or null for an empty record (which the kernel never writes). */
    fun tagOf(record: ByteArray): Byte? = record.firstOrNull()

    /** The positions in [records] carrying [tag], in order. */
    fun indicesOfTag(records: List<ByteArray>, tag: Byte): List<Int> =
        records.indices.filter { tagOf(records[it]) == tag }

    /** How many records of each tag [records] holds — for a report line, never for control flow. */
    fun tagHistogram(records: List<ByteArray>): Map<Byte, Int> =
        records.mapNotNull { tagOf(it) }.groupingBy { it }.eachCount()
}

/** A journal refused a write ([JournalMutation.FailAppendAfter]) — the disk-full simulation. */
class JournalAppendFailed(val journal: String, val appendsAccepted: Int) :
    RuntimeException(
        "journal \"$journal\" refused an append after accepting $appendsAccepted record(s) " +
            "(JournalMutation.FailAppendAfter). The write-ahead guarantee is broken from here on: " +
            "anything the host acknowledged after this point is not on disk.",
    )

/**
 * One index-level mutation of a journal ([CHA1-19]).
 *
 * A mutation is a **value**: immutable configuration, no per-run state, so a [FaultPlan]
 * holding one is serialisable and shrinkable. All per-run state (how many appends have been
 * accepted) lives in [MutatingJournal].
 *
 * Two hooks, because a journal has two failure surfaces:
 *  - [onReplay] rewrites what recovery *reads* — five of the six mutations,
 *  - [acceptAppend] refuses what the host *writes* — [FailAppendAfter], the only one whose
 *    damage is done before the crash rather than after it.
 *
 * An out-of-range index is **not** clamped and not an error: it makes the mutation a no-op,
 * which the rig reports as an inert fault ([CHA1-24]) rather than hiding as a silent clamp to
 * some other record. A journal shorter than the index the plan named is a real finding about
 * the run, and `fired == 0` is how the report says so.
 */
sealed interface JournalMutation {

    fun describe(): String

    /** Rewrite what `replay()` returns. Default: unchanged. */
    fun onReplay(records: List<ByteArray>): List<ByteArray> = records

    /** False refuses this append ([JournalAppendFailed]). [accepted] counts prior acceptances. */
    fun acceptAppend(accepted: Int): Boolean = true

    /**
     * Drop the last [n] records — the crash-mid-write shape, and the mutation whose
     * *converging* control is [CHA1-62]: a torn tail is a suffix the host never acknowledged,
     * so replay must produce a clean prefix and no dead letter (BS-9).
     */
    data class TruncateTail(val n: Int) : JournalMutation {
        init {
            require(n >= 0) { "TruncateTail(n) drops the last n records; got n=$n" }
        }

        override fun describe(): String = "truncate the last $n record(s) — a crash mid-append"

        override fun onReplay(records: List<ByteArray>): List<ByteArray> =
            records.take((records.size - n).coerceAtLeast(0))
    }

    /**
     * Drop the first [n] records — a log whose head was compacted away without the checkpoint
     * that was supposed to summarise it. Diverging where [TruncateTail] converges: a lost
     * prefix is state nothing else holds.
     */
    data class TruncatePrefix(val n: Int) : JournalMutation {
        init {
            require(n >= 0) { "TruncatePrefix(n) drops the first n records; got n=$n" }
        }

        override fun describe(): String = "drop the first $n record(s) — a compacted-away log head"

        override fun onReplay(records: List<ByteArray>): List<ByteArray> = records.drop(n)
    }

    /**
     * Replace record [index] with [corruption] — bit rot, a torn *interior* record, a partial
     * write that landed. With the default corruption the record's tag is
     * [JournalRecords.UNKNOWN], so recovery aborts at exactly [index] with
     * `RecoveryIncomplete(recordIndex = index, total = R)` ([CHA1-20], BS-9's diverging half).
     */
    data class CorruptAt(
        val index: Int,
        val corruption: ByteArray = byteArrayOf(JournalRecords.UNKNOWN),
    ) : JournalMutation {
        init {
            require(index >= 0) { "CorruptAt(index) corrupts one record; got index=$index" }
        }

        override fun describe(): String =
            "corrupt record $index (to ${corruption.size} byte(s), tag ${corruption.firstOrNull()}) — " +
                "replay must abort AT $index, not silently truncate there"

        override fun onReplay(records: List<ByteArray>): List<ByteArray> =
            if (index !in records.indices) {
                records
            } else {
                records.mapIndexed { i, r -> if (i == index) corruption else r }
            }

        // ByteArray in a data class: equals/hashCode must be structural or two identical plans
        // compare unequal, which a shrinker's plan bookkeeping would read as progress.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is CorruptAt && index == other.index && corruption.contentEquals(other.corruption))

        override fun hashCode(): Int = 31 * index + corruption.contentHashCode()
    }

    /**
     * Emit record [index] twice — an fsync that returned twice, or a retried append. Journal
     * replay is meant to be idempotent per record; this is what proves it, and where it is not,
     * a duplicate is a double-fire.
     */
    data class DuplicateAt(val index: Int) : JournalMutation {
        init {
            require(index >= 0) { "DuplicateAt(index) repeats one record; got index=$index" }
        }

        override fun describe(): String = "replay record $index twice — a retried or double-fsynced append"

        override fun onReplay(records: List<ByteArray>): List<ByteArray> =
            if (index !in records.indices) {
                records
            } else {
                records.flatMapIndexed { i, r -> if (i == index) listOf(r, r) else listOf(r) }
            }
    }

    /**
     * Swap records [i] and [j] — an out-of-order log, which is the one thing an append-only
     * journal is *entitled* to assume it never sees. Reordering a frame past the frontier
     * record that dedupes it is how a replay double-fires without any record being lost.
     */
    data class ReorderAt(val i: Int, val j: Int) : JournalMutation {
        init {
            require(i >= 0 && j >= 0) { "ReorderAt(i, j) swaps two records; got i=$i, j=$j" }
        }

        override fun describe(): String = "swap records $i and $j — an out-of-order log"

        override fun onReplay(records: List<ByteArray>): List<ByteArray> {
            if (i == j || i !in records.indices || j !in records.indices) return records
            val out = records.toMutableList()
            out[i] = records[j]
            out[j] = records[i]
            return out
        }
    }

    /**
     * Accept [n] appends and refuse every one after that with [JournalAppendFailed] — the disk
     * filling up mid-run.
     *
     * The only mutation that damages the *write* path, and therefore the only one whose effect
     * is visible before any recovery: the host's write-ahead guarantee is broken from the
     * refusal onward, so whatever it acknowledges after that point exists in memory only.
     */
    data class FailAppendAfter(val n: Int) : JournalMutation {
        init {
            require(n >= 0) { "FailAppendAfter(n) accepts n appends then refuses; got n=$n" }
        }

        override fun describe(): String = "refuse every append after the first $n — a full disk"

        override fun acceptAppend(accepted: Int): Boolean = accepted < n
    }
}

/**
 * The per-installation state a [MutatingJournal] keeps, held **outside** the decorator instance.
 *
 * ## Why this type exists — a property of the journal seam that is easy to get wrong
 *
 * `DstWorld.journals` resolves its decoration chain **on every call**:
 * `decorations.fold(base) { inner, decorate -> decorate(inner) }` runs inside `resolve(name)`,
 * which every `append`/`replay`/`reset` goes through. So a decoration lambda is re-invoked per
 * call and a decorator that keeps state in its own fields gets a **fresh instance, with fresh
 * state, for every single journal operation**.
 *
 * Measured, not deduced: `JournalMutation.FailAppendAfter(2)` installed through the seam accepted
 * *every* append and was reported inert, because its "appends accepted so far" counter was
 * reconstructed at zero on each call. A stateful journal decorator must therefore be handed its
 * state, created once per installation — which is what this is.
 *
 * (Per-call resolution is the right behaviour for the seam: it is what lets a decoration
 * installed at step 400 take effect on the journal a host is already holding, with no re-wiring.
 * The consequence just has to be paid by the decorator rather than assumed away.)
 */
class JournalLedger {
    /** Appends the delegate has actually accepted, across every resolved decorator instance. */
    var accepted: Int = 0
        internal set
}

/**
 * A [Journal] decorator applying one [JournalMutation] ([CHA1-19]).
 *
 * Composed over the rig's journal seam — `DstWorld.journals.decorate(name) { MutatingJournal(it, ...) }`
 * — so it wraps whatever the graph declared and whatever else already decorates it, and is
 * removed again when the fault's window heals.
 *
 * [reset] is **not** mutated. A reset is checkpoint compaction: the host is rewriting the whole
 * log from state it holds in memory, and mutating that would corrupt a record the host is about
 * to rely on for reasons unrelated to any fault window. The mutation applies to what recovery
 * *reads* and to what the host *appends*, which is where a real disk fails.
 *
 * @param ledger the append count, which **must** be created once per installation and shared
 *   across resolved instances — see [JournalLedger] for the seam behaviour that makes this
 *   mandatory rather than stylistic.
 * @param onFire called whenever the mutation actually changed something — the firing count that
 *   makes an inert fault visible ([CHA1-24]). Not called when the mutation was a no-op (an index
 *   past the end of a shorter-than-expected journal).
 */
class MutatingJournal(
    private val delegate: Journal,
    val mutation: JournalMutation,
    private val name: String = "journal",
    private val ledger: JournalLedger = JournalLedger(),
    private val onFire: (String) -> Unit = {},
) : Journal {

    /**
     * Forwarded, never defaulted. See [JournalRecords]' decorator note: the interface default
     * makes an omission compile, and a decorated journal that misreports its format version
     * suppresses exactly the refusal `civictech.cell.durability.JournalFormatMismatch` exists
     * to raise.
     */
    override val formatVersion: Int get() = delegate.formatVersion

    @Synchronized
    override fun append(record: ByteArray) {
        val accepted = ledger.accepted
        if (!mutation.acceptAppend(accepted)) {
            onFire("append refused after $accepted")
            throw JournalAppendFailed(name, accepted)
        }
        delegate.append(record)
        ledger.accepted = accepted + 1
    }

    override fun replay(): List<ByteArray> {
        val original = delegate.replay()
        val mutated = mutation.onReplay(original)
        if (!sameRecords(original, mutated)) onFire("replay ${original.size} -> ${mutated.size}")
        return mutated
    }

    override fun reset(records: List<ByteArray>) = delegate.reset(records)

    override fun toString(): String = "MutatingJournal($name, ${mutation.describe()})"

    private fun sameRecords(a: List<ByteArray>, b: List<ByteArray>): Boolean =
        a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }
}

/**
 * A **read-only** view of [delegate] holding only its first [k] records ([CHA1-21]).
 *
 * The restart-from-an-arbitrary-prefix primitive. Distinct from
 * [JournalMutation.TruncateTail] on purpose: `TruncateTail` is relative (drop the last `n`) and
 * is a fault applied for a window, while a prefix restart is absolute (`k` of `R`) and is the
 * *axis a sweep walks*. Expressing "restart at k" as `TruncateTail(size - k)` would make the
 * sweep's own index depend on a size read at a different moment than the replay.
 *
 * `append` and `reset` throw: a prefix view exists for exactly one `recoverFrom` call, and a
 * host that wrote through it would be extending a log it is only meant to read a slice of.
 */
class PrefixJournal(private val delegate: Journal, val k: Int) : Journal {

    init {
        require(k >= 0) { "a journal prefix is a record count; got k=$k" }
    }

    override val formatVersion: Int get() = delegate.formatVersion

    /** How many records the underlying log actually holds — `k` may exceed it, harmlessly. */
    fun total(): Int = delegate.replay().size

    override fun append(record: ByteArray): Unit =
        throw UnsupportedOperationException(
            "PrefixJournal is a read-only restart view of the first $k record(s); it is handed to " +
                "ManagedHost.recoverFrom and to nothing else. Give the host the undecorated journal " +
                "(DstWorld.journals.view(name)) for its live writes.",
        )

    override fun replay(): List<ByteArray> = delegate.replay().take(k)

    override fun reset(records: List<ByteArray>): Unit =
        throw UnsupportedOperationException("PrefixJournal is read-only; see append()")

    override fun toString(): String = "PrefixJournal(k=$k)"
}

/**
 * A read-only view of [delegate] that keeps only the **first [keepFrontierAdvances]**
 * `RECORD_FRONTIER` records and drops the rest, leaving every other record untouched
 * ([CHA1-22], BS-11).
 *
 * ## What this rolls back, and what it provably cannot
 *
 * `HostDurability` restores the processed-frontier by replaying `RECORD_FRONTIER` records in
 * order, each advancing one `(cellRef, portName)`'s high-water for one source. Dropping the
 * later ones therefore leaves the frontier where it stood after the ones that remain — a
 * rollback **positional in the log**, independent of the record prefix a
 * [PrefixJournal] restart uses, which is what [CHA1-22] asks for.
 *
 * It is not a rollback to a *named* `(sourceId, counter)`, and it cannot be made into one from
 * `:testkit`. `FrontierRecord` is a `private data class` in `HostDurability.kt` and its payload
 * is Java serialisation of that class, so the `(cellRef, portName, timestamp)` a given record
 * carries is undecodable here — only its *tag byte* is readable (see [JournalRecords]). A
 * caller therefore selects a rollback point by counting frontier advances, not by naming a
 * position, and reads the resulting position off the system under test. **This is the reported
 * blocker, not a workaround:** naming the target position would require either widening
 * `FrontierRecord`'s visibility or a kernel-side decode seam, and the item that specified this
 * work authorised neither.
 *
 * A second, sharper limit, also structural: once a **checkpoint** has run, the frontier lives
 * inside the `RECORD_CHECKPOINT` blob (`CheckpointRecord.frontier`) and no longer in separate
 * frontier records at all. Dropping frontier records after a checkpoint rolls back only the
 * advances made *since* it. [frontierAdvancesAfterLastCheckpoint] is what a caller checks
 * before believing a rollback reached where it aimed.
 */
class FrontierRollbackJournal(
    private val delegate: Journal,
    val keepFrontierAdvances: Int,
) : Journal {

    init {
        require(keepFrontierAdvances >= 0) {
            "keepFrontierAdvances counts the frontier advances to retain; got $keepFrontierAdvances"
        }
    }

    override val formatVersion: Int get() = delegate.formatVersion

    /**
     * How many `RECORD_FRONTIER` records follow the last `RECORD_CHECKPOINT` — the number of
     * advances this view can actually roll back. Zero with a checkpoint present means the
     * frontier is entirely inside the checkpoint blob and positional rollback reaches none of it.
     */
    fun frontierAdvancesAfterLastCheckpoint(): Int {
        val records = delegate.replay()
        val lastCheckpoint = records.indexOfLast { JournalRecords.tagOf(it) == JournalRecords.CHECKPOINT }
        return JournalRecords.indicesOfTag(records, JournalRecords.FRONTIER).count { it > lastCheckpoint }
    }

    /** Every frontier advance in the underlying log, checkpointed or not. */
    fun frontierAdvances(): Int =
        JournalRecords.indicesOfTag(delegate.replay(), JournalRecords.FRONTIER).size

    override fun append(record: ByteArray): Unit =
        throw UnsupportedOperationException("FrontierRollbackJournal is a read-only recovery view")

    override fun replay(): List<ByteArray> {
        var seen = 0
        return delegate.replay().filter { record ->
            if (JournalRecords.tagOf(record) != JournalRecords.FRONTIER) {
                true
            } else {
                seen++
                seen <= keepFrontierAdvances
            }
        }
    }

    override fun reset(records: List<ByteArray>): Unit =
        throw UnsupportedOperationException("FrontierRollbackJournal is a read-only recovery view")

    override fun toString(): String = "FrontierRollbackJournal(keep=$keepFrontierAdvances)"
}

/**
 * What one `ManagedHost.recoverFrom` call did ([CHA1-20]).
 *
 * `recoverFrom` either returns (every record applied) or throws [RecoveryIncomplete] carrying
 * `recordIndex` and `total`. Both are findings a run must report, so [JournalRecovery.attempt]
 * turns the pair into one value rather than leaving the second as an exception a fault would
 * have to swallow or propagate.
 *
 * @property offered how many records the journal handed to recovery — `total` when incomplete,
 *   and the whole point of the comparison: `RecoveryIncomplete(recordIndex = i, total = R)` says
 *   records `[0, i)` applied and `[i, R)` did not.
 */
data class RecoveryAttempt(
    val offered: Int,
    val incomplete: RecoveryIncomplete? = null,
) {
    val complete: Boolean get() = incomplete == null

    /** The 0-based record recovery stopped at, or null if it did not stop. */
    val abortedAt: Int? get() = incomplete?.recordIndex

    /** Records that never applied: `total - recordIndex`, or 0 for a complete recovery. */
    val unapplied: Int get() = incomplete?.let { it.total - it.recordIndex } ?: 0

    /**
     * The one-line form that goes into the trace, and therefore into `DstReport.trace` —
     * which is how a [RecoveryIncomplete]'s index and total reach the run report without any
     * new report field ([CHA1-20]).
     */
    fun traceTag(): String =
        incomplete?.let { "recovery-incomplete@${it.recordIndex}/${it.total}" } ?: "recovery-complete@$offered"

    override fun toString(): String = traceTag()
}

/** Runs a recovery and reports what it did, instead of letting a partial replay throw past. */
object JournalRecovery {

    /**
     * `host.recoverFrom(journal)`, with [RecoveryIncomplete] captured rather than propagated
     * ([CHA1-20]).
     *
     * Only `RecoveryIncomplete` is caught. Anything else — a `JournalFormatMismatch`, a broken
     * graph, an exception from a cell's `restore` that recovery did not wrap — propagates: a
     * fault that cannot even attempt its recovery is a broken experiment, not a finding about
     * the property under test, and the rig reports the two differently.
     */
    fun attempt(host: ManagedHost, journal: Journal): RecoveryAttempt {
        val offered = journal.replay().size
        return try {
            host.recoverFrom(journal)
            RecoveryAttempt(offered)
        } catch (e: RecoveryIncomplete) {
            RecoveryAttempt(offered, e)
        }
    }
}
