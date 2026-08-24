package civictech.testkit.dst

import civictech.cell.Borrowed
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.host.DeadLetter

/**
 * The identity of one exclusive payload, stable across a whole run and across a replay of the
 * same seed.
 *
 * Deliberately a caller-chosen string rather than a counter: the tokens in a failure detail
 * are only useful if the same seed produces the same names, and a global counter would be
 * perturbed by anything else the graph minted first.
 */
data class ExclusiveToken(val id: String) {
    override fun toString(): String = id
}

/**
 * The value an [Owned]/[Leased] wraps in a rig-driven graph, carrying its [token] so the
 * ledger can recognise it wherever it surfaces — in a sink, in a dead letter's sanitized
 * arguments, or in a `Borrowed` view.
 */
class TrackedExclusive(val token: ExclusiveToken) {
    override fun toString(): String = "TrackedExclusive($token)"
}

/**
 * How an exclusive payload's obligation was discharged ([CHA1-53]).
 *
 * The four are the spec's own vocabulary (spec 23 R8, AGENTS.md "Core invariants"): an
 * exclusive is consumed, released, discharged, or explicitly dead-lettered. Anything else —
 * dropped, suppressed, parked, shadowed — leaves the token outstanding, which is what fails
 * the run.
 */
enum class ExclusiveDisposition {
    /** `Owned.take()` / `freeze()` by the sole consumer. */
    CONSUMED,

    /** `Leased.release()` — observed structurally, through the lease's own `returnToPool`. */
    RELEASED,

    /** Explicitly discharged by the graph on a failure path it handled (a rejected duplicate). */
    DISCHARGED,

    /** Reported on a host's dead-letter outlet, and therefore not lost silently. */
    DEAD_LETTERED,
}

/** One payload's life: where it was minted and every disposition recorded against it. */
data class ExclusiveRecord(
    val token: ExclusiveToken,
    val origin: String,
    val dispositions: List<Pair<ExclusiveDisposition, String>>,
) {
    val outstanding: Boolean get() = dispositions.isEmpty()

    /** Two dispositions on one exclusive is a double-consume, which is equally a violation. */
    val doubleAccounted: Boolean get() = dispositions.size > 1

    fun render(): String = when {
        outstanding -> "$token (minted at $origin) — NO disposition"
        else -> "$token (minted at $origin) — " + dispositions.joinToString(", ") { "${it.first} ${it.second}" }
    }
}

/**
 * Accounts every exclusive payload a rig-driven graph mints ([CHA1-53]).
 *
 * ## The property
 *
 * `Owned` and `Leased` carry an obligation: consumed exactly once, released exactly once. The
 * repo's standing invariant is that **no failure, suppression, shadow, park or dead-letter
 * path may silently drop one** (AGENTS.md, spec 23 R8). This ledger mechanises that: a graph
 * mints through [mintOwned]/[mintLeased], every disposition is recorded, and [verify] fails
 * the run if any token reaches the end of the run with no disposition — or with two.
 *
 * ## What it can see, and what it cannot — read this before trusting a green run
 *
 * The accounting is a **balance**, not an observation of the kernel's own bookkeeping, because
 * the kernel exposes none. Specifically:
 *
 *  - **`Owned`'s consume is invisible.** `Owned.consumed` is a private field with no accessor
 *    and no event (`civictech.cell.Ownership`), so nothing in `:testkit` can see a `take()`.
 *    A graph therefore consumes *through* [consume], which takes the value and records it. A
 *    graph that calls `owned.take()` directly is not accounted, and the ledger reports the
 *    token as outstanding — i.e. it fails **loud** rather than passing quietly. That direction
 *    is chosen deliberately; the opposite default would make an un-instrumented graph look
 *    conforming.
 *  - **`Leased`'s release IS visible**, and needs no cooperation: [mintLeased] supplies a
 *    `returnToPool` that records [ExclusiveDisposition.RELEASED]. This is the one disposition
 *    the ledger observes structurally, and it covers the kernel's own release — the
 *    dead-letter sanitizer releases a `Leased` before redacting it
 *    (`DeadLetters.sanitizeForDeadLetter`), and that release lands here without the graph
 *    doing anything.
 *  - **Dead letters are read back through [accountFrom]**, which finds tokens in the letter's
 *    sanitized arguments: a `Frozen<TrackedExclusive>` (what an `Owned` degenerates to at
 *    capture), a live `Owned`/`Leased` (read via `borrow()`, which does not consume), a
 *    `Borrowed`, or a bare [TrackedExclusive]. A `Redacted` marker carries only a reason
 *    string and **loses token identity** — a `Leased` that reaches a dead letter is therefore
 *    accounted by its release callback, not by the letter, and an `Owned` already consumed
 *    before capture is redacted the same way and cannot be recovered from the letter either.
 *  - **The ledger is per-process and per-run.** It knows nothing about a payload that left
 *    this JVM. A graph modelling a wire crossing must keep the sending side's obligation open
 *    until it observes the far side (that is what makes a destroyed frame a detectable loss);
 *    a graph that treats hand-off to the wire as a discharge has defined the loss away, and no
 *    ledger can tell the difference.
 *  - **It sees only what the graph mints through it.** A payload constructed as a bare
 *    `Owned(x)` is not tracked at all. The count in a report is "tracked exclusives", never
 *    "exclusives in this JVM".
 */
class ExclusiveLedger(val name: String = "exclusives") {

    private val origins = linkedMapOf<ExclusiveToken, String>()
    private val dispositions = linkedMapOf<ExclusiveToken, MutableList<Pair<ExclusiveDisposition, String>>>()

    /** Every token minted, in mint order. */
    fun tokens(): List<ExclusiveToken> = origins.keys.toList()

    /** Mint a tracked [Owned]. [origin] is free text naming where in the graph it came from. */
    fun mintOwned(id: String, origin: String = "unspecified"): Owned<TrackedExclusive> {
        val token = declare(id, origin)
        return Owned(TrackedExclusive(token))
    }

    /**
     * Mint a tracked [Leased] whose release is recorded automatically.
     *
     * The `returnToPool` lambda is the whole trick: `Leased.release()` calls it, so a release
     * anywhere — the graph's, or the kernel's inside dead-letter sanitization — is observed
     * without the releasing code knowing this ledger exists.
     */
    fun mintLeased(id: String, origin: String = "unspecified"): Leased<TrackedExclusive> {
        val token = declare(id, origin)
        return Leased(TrackedExclusive(token)) { record(token, ExclusiveDisposition.RELEASED, "returnToPool") }
    }

    private fun declare(id: String, origin: String): ExclusiveToken {
        val token = ExclusiveToken(id)
        require(token !in origins) { "exclusive \"$id\" was already minted on ledger \"$name\" (at ${origins[token]})" }
        origins[token] = origin
        dispositions[token] = mutableListOf()
        return token
    }

    /** Consume an owned payload and record it. The graph's replacement for a bare `take()`. */
    fun consume(owned: Owned<TrackedExclusive>, where: String = "sink"): TrackedExclusive =
        owned.take().also { record(it.token, ExclusiveDisposition.CONSUMED, where) }

    /**
     * Record an explicit discharge on a failure path the graph handled itself — a duplicate it
     * rejected, a payload it abandoned deliberately. The [why] is printed in the failure
     * detail, because "discharged" with no reason is the shape a silent drop wears.
     */
    fun discharge(value: TrackedExclusive, why: String) =
        record(value.token, ExclusiveDisposition.DISCHARGED, why)

    /** The same, by token, for a graph that has already taken the value apart. */
    fun discharge(token: ExclusiveToken, why: String) = record(token, ExclusiveDisposition.DISCHARGED, why)

    /**
     * Account every tracked exclusive appearing in [letters] as [ExclusiveDisposition.DEAD_LETTERED].
     *
     * Idempotent per token: calling it once at the end of a run and again from a check does not
     * manufacture a double-accounting. See the class KDoc for the identities it cannot recover
     * (`Redacted`).
     */
    fun accountFrom(letters: List<DeadLetter>) {
        letters.forEach { letter ->
            letter.invocation?.invocation?.args.orEmpty().forEach { arg ->
                tokenOf(arg)?.let { token ->
                    if (dispositions[token]?.none { it.first == ExclusiveDisposition.DEAD_LETTERED } == true) {
                        record(token, ExclusiveDisposition.DEAD_LETTERED, "dead letter: ${letter.description}")
                    }
                }
            }
        }
    }

    /** The token inside a payload wrapper, without consuming or releasing anything. */
    private fun tokenOf(arg: Any?): ExclusiveToken? = when (arg) {
        is TrackedExclusive -> arg.token
        is Frozen<*> -> (arg.value as? TrackedExclusive)?.token
        is Borrowed<*> -> (arg.value as? TrackedExclusive)?.token
        // borrow() is the non-consuming snapshot view (spec 23 §Taps): reading a live handle
        // here must not compete with the sole consumer's take()/release().
        is Owned<*> -> (arg.borrow().value as? TrackedExclusive)?.token
        is Leased<*> -> (arg.borrow().value as? TrackedExclusive)?.token
        else -> null
    }

    private fun record(token: ExclusiveToken, disposition: ExclusiveDisposition, where: String) {
        val recorded = dispositions[token]
            ?: throw IllegalArgumentException(
                "exclusive \"$token\" was never minted on ledger \"$name\"; minted: ${origins.keys.map { it.id }}",
            )
        recorded += disposition to where
    }

    fun records(): List<ExclusiveRecord> =
        origins.map { (token, origin) -> ExclusiveRecord(token, origin, dispositions.getValue(token).toList()) }

    /** Minted and never consumed, released, discharged or dead-lettered — the silent losses. */
    fun outstanding(): List<ExclusiveRecord> = records().filter { it.outstanding }

    /** Accounted twice: a double consume or a release after a discharge. Equally a violation. */
    fun doubleAccounted(): List<ExclusiveRecord> = records().filter { it.doubleAccounted }

    /** One line for the failure report ([CHA1-50]). */
    fun renderSummary(): String {
        val records = records()
        if (records.isEmpty()) return "0 tracked"
        val accounted = records.count { !it.outstanding }
        return "${records.size} tracked, $accounted accounted" +
            (outstanding().size.takeIf { it > 0 }?.let { ", $it LOST" } ?: "") +
            (doubleAccounted().size.takeIf { it > 0 }?.let { ", $it double-accounted" } ?: "")
    }

    /**
     * [CHA1-53]: fail the run if any tracked exclusive was lost or accounted twice.
     *
     * **The message carries no run-varying numbers or token names.** The shrinker accepts a
     * reduction only when the failing check's message still matches
     * (`FailurePredicate.sameFailingCheck`), and a message naming *which* payloads were lost
     * would change under every legitimate reduction. The tokens, their origins and their
     * dispositions are in [ExclusivePayloadLost.detail], which the failure report renders and
     * the shrinker never reads.
     */
    fun verify() {
        val lost = outstanding()
        val doubled = doubleAccounted()
        if (lost.isEmpty() && doubled.isEmpty()) return
        throw ExclusivePayloadLost(this, lost, doubled)
    }

    /**
     * The [CHA1-53] check, ready to hand to a [DstRun]: accounts the run's dead letters first
     * (a dead-lettered payload is *not* a silent loss), then verifies the balance.
     */
    fun check(): DstCheck = DstCheck { world ->
        accountFrom(world.deadLetters)
        verify()
    }
}

/**
 * Per-world ledgers, so a graph builder and a [DstCheck] can find *the same* ledger without the
 * ledger being a global.
 *
 * A sweep builds one [DstWorld] per seed and a [GraphSpec] is a reusable value, so a check
 * closing over one ledger instance would grade seed 3 against seed 1's payloads. The same
 * arrangement, and the same reason, as `LinkControls` and `CrashWitnesses`: keyed weakly by
 * world, declared by the graph builder, resolved by the check at verify time.
 */
object ExclusiveLedgers {

    private val byWorld = java.util.WeakHashMap<DstWorld, MutableMap<String, ExclusiveLedger>>()

    /** Declare a ledger for this world. Called by the graph builder, once per run. */
    fun declare(world: DstWorld, name: String = "exclusives"): ExclusiveLedger {
        val ledgers = byWorld.getOrPut(world) { linkedMapOf() }
        require(name !in ledgers) { "exclusive ledger \"$name\" is already declared for this world" }
        return ExclusiveLedger(name).also { ledgers[name] = it }
    }

    fun find(world: DstWorld, name: String = "exclusives"): ExclusiveLedger? = byWorld[world]?.get(name)

    fun require(world: DstWorld, name: String = "exclusives"): ExclusiveLedger =
        find(world, name) ?: throw IllegalArgumentException(
            "no exclusive ledger \"$name\" in this world; the graph builder declares one with " +
                "ExclusiveLedgers.declare(world, \"$name\"). Declared: ${byWorld[world]?.keys?.sorted() ?: emptyList<String>()}",
        )

    /**
     * The [CHA1-53] check, resolved per world: accounts the run's dead letters, then verifies
     * that every tracked exclusive was consumed, released, discharged or dead-lettered.
     *
     * Register it in [CheckRegistry] to make a failing run replayable.
     */
    fun check(name: String = "exclusives"): DstCheck = DstCheck { world ->
        val ledger = require(world, name)
        ledger.accountFrom(world.deadLetters)
        ledger.verify()
    }
}

/**
 * [CHA1-53]'s failure: an exclusive payload reached the end of the run without an explicit
 * consume, release, discharge or dead letter — or was accounted twice.
 *
 * [message] is stable across runs of the same failure mode; everything run-varying is in
 * [detail]. See [ExclusiveLedger.verify].
 */
class ExclusivePayloadLost(
    val ledger: ExclusiveLedger,
    val lost: List<ExclusiveRecord>,
    val doubleAccounted: List<ExclusiveRecord>,
) : AssertionError(
    if (lost.isNotEmpty()) {
        "exclusive payload lost ([CHA1-53]): an Owned/Leased payload ended the run with no consume, " +
            "release, discharge or dead letter"
    } else {
        "exclusive payload accounted twice ([CHA1-53]): an Owned/Leased payload was discharged more than once"
    },
),
    DstFailureDetail {

    override fun detail(): String = buildString {
        append("exclusives on ledger \"${ledger.name}\": ${ledger.renderSummary()}")
        lost.forEach { append("\n  LOST ").append(it.render()) }
        doubleAccounted.forEach { append("\n  DOUBLE ").append(it.render()) }
    }
}
