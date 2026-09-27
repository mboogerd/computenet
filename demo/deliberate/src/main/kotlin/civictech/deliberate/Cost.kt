package civictech.deliberate

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * SPEC §12 Cost: what one external call used. Token counts follow the
 * backend's own report: [cachedInputTokens] and [cacheWriteTokens] are part
 * of [inputTokens] (OpenAI's accounting; for Claude, which reports them
 * separately, [CliProposer.claudeUsage] adds them in), and [reasoningTokens] are
 * part of [outputTokens] (billed as output, never added twice).
 */
data class CallUsage(
    /** "claude" | "codex" | "jev". */
    val backend: String,
    val models: List<String> = emptyList(),
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    /** The largest single prompt of the call (a multi-turn call's longest turn), for long-prompt surcharges. */
    val longestPromptTokens: Long = inputTokens,
    /** A cost the backend reported itself (Claude Code's `total_cost_usd`); null when it reports none. */
    val reportedUsd: Double? = null,
)

/** Receives the usage of calls made on behalf of one question (SPEC §12). */
fun interface UsageSink {
    fun record(usage: CallUsage)
}

/**
 * The attribution seam (SPEC §12): the engine binds the sink of the question
 * a call serves around that call ([within]); an adapter deep inside the call
 * ([CliProposer], [JevJudge]) [report]s what the call used, and the report
 * reaches that question — the [Proposer]/[Judge]/[Merger] interfaces stay
 * free of cost plumbing. An adapter that hands work to another thread passes
 * [current] along. Reporting never throws: a failed capture never fails a call.
 */
object Usage {
    private val bound = ThreadLocal<UsageSink?>()

    val current: UsageSink? get() = bound.get()

    fun <T> within(sink: UsageSink?, block: () -> T): T {
        val previous = bound.get()
        bound.set(sink)
        try {
            return block()
        } finally {
            bound.set(previous)
        }
    }

    fun report(usage: CallUsage?) {
        if (usage == null) return
        val sink = bound.get() ?: return
        try {
            sink.record(usage)
        } catch (e: Exception) {
            System.err.println("deliberate: recording usage failed: $e")
        }
    }

    /** `DELIBERATE_LOG_USAGE=1`: adapters log each call's raw usage to stderr (for checking the totals). */
    val logRaw: Boolean = System.getenv("DELIBERATE_LOG_USAGE") == "1"

    fun raw(backend: String, text: () -> String) {
        if (logRaw) System.err.println("deliberate: usage $backend ${text()}")
    }

    /** Runs [capture] and reports its result; any failure in it is logged and swallowed. */
    inline fun capture(what: String, capture: () -> CallUsage?) {
        val usage = try {
            capture()
        } catch (e: Exception) {
            System.err.println("deliberate: could not read $what usage: $e")
            null
        }
        report(usage)
    }
}

/**
 * A token price in USD per million tokens (SPEC §12). [cacheWriteMultiplier]
 * prices cache-write tokens relative to [inputPerM]; a call whose longest
 * prompt exceeds [longPromptTokens] pays [longInputMultiplier] on every input
 * rate and [longOutputMultiplier] on output.
 */
data class Rate(
    val inputPerM: Double,
    val cachedInputPerM: Double = inputPerM,
    val outputPerM: Double,
    val cacheWriteMultiplier: Double = 1.0,
    val longPromptTokens: Long? = null,
    val longInputMultiplier: Double = 1.0,
    val longOutputMultiplier: Double = 1.0,
) {
    fun cost(u: CallUsage): Double {
        val uncached = (u.inputTokens - u.cachedInputTokens - u.cacheWriteTokens).coerceAtLeast(0)
        val long = longPromptTokens != null && u.longestPromptTokens > longPromptTokens
        val inM = if (long) longInputMultiplier else 1.0
        val outM = if (long) longOutputMultiplier else 1.0
        val input = uncached * inputPerM + u.cachedInputTokens * cachedInputPerM + u.cacheWriteTokens * inputPerM * cacheWriteMultiplier
        return (input * inM + u.outputTokens * outputPerM * outM) / 1_000_000.0
    }

    fun describe(): String = buildString {
        append("${usd(inputPerM)}/1M input")
        if (cachedInputPerM != inputPerM) append(" · ${usd(cachedInputPerM)}/1M cached input")
        append(" · ${if (outputPerM == 0.0) "output free" else "${usd(outputPerM)}/1M output"}")
        if (cacheWriteMultiplier != 1.0) append(" · cache writes ${fmt(cacheWriteMultiplier)}× input")
        if (longPromptTokens != null) {
            append(" · prompts over ${longPromptTokens / 1000}K tokens ${fmt(longInputMultiplier)}× input, ${fmt(longOutputMultiplier)}× output")
        }
    }

    private companion object {
        fun usd(x: Double) = "$" + String.format(Locale.ROOT, if (x < 0.1) "%.3f" else "%.2f", x)
        fun fmt(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else x.toString()
    }
}

/** How one backend's cost is priced, and where that price comes from (shown in the UI, SPEC §12). */
data class PriceInfo(
    val rate: String,
    val source: String,
    val date: String?,
    /** Not a published price of the provider: an assumption the user should know about. */
    val assumed: Boolean,
    val note: String? = null,
)

/**
 * SPEC §12: the prices applied to each backend. Claude is priced by what the
 * Claude Code CLI reports itself; Codex and Jev by token rates. A null
 * [codex] rate means the rate is unknown (a `--codex-model` other than
 * [DEFAULT_CODEX_MODEL] without rate flags): its tokens are shown, its cost
 * is left out of every total.
 */
data class Pricing(
    val codexModel: String = DEFAULT_CODEX_MODEL,
    val codex: Rate? = CODEX_DEFAULT,
    val codexSource: PriceInfo? = null,
    val jev: Rate = JEV_DEFAULT,
    val jevSource: PriceInfo? = null,
) {
    /** The USD cost of [u], or null when it cannot be priced. */
    fun price(u: CallUsage): Double? = when (u.backend) {
        CLAUDE -> u.reportedUsd
        CODEX -> codex?.cost(u)
        JEV -> jev.cost(u)
        else -> null
    }

    fun info(backend: String): PriceInfo = when (backend) {
        CLAUDE -> PriceInfo(
            rate = "as reported by Claude Code (total_cost_usd per call)",
            source = "Claude Code CLI",
            date = null,
            assumed = false,
            note = CLAUDE_CAVEAT,
        )
        CODEX -> when {
            codex == null -> PriceInfo(
                rate = "rate unknown for $codexModel — tokens only, left out of the total",
                source = "set --codex-input-rate, --codex-cached-rate and --codex-output-rate",
                date = null,
                assumed = true,
            )
            codexSource != null -> codexSource.copy(rate = codex.describe())
            else -> PriceInfo(
                rate = codex.describe(),
                source = CODEX_SOURCE,
                date = PRICES_DATE,
                assumed = false,
                note = "Reasoning tokens are billed as output. The input price is promotional through at least 2026-11-21.",
            )
        }
        JEV -> (jevSource ?: PriceInfo(
            rate = jev.describe(),
            source = JEV_SOURCE,
            date = PRICES_DATE,
            assumed = true,
            note = "TypeSafe publishes no pricing; this is a third-party listing.",
        )).copy(rate = jev.describe())
        else -> PriceInfo("unknown", "unknown", null, true)
    }

    companion object {
        const val CLAUDE = "claude"
        const val CODEX = "codex"
        const val JEV = "jev"
        const val DEFAULT_CODEX_MODEL = "gpt-5.6-sol"
        const val PRICES_DATE = "2026-09-27"
        const val CODEX_SOURCE = "developers.openai.com/api/docs/models/gpt-5.6-sol"
        const val JEV_SOURCE = "third-party: OpenRouter typesafe/jev-1.13, MindStudio"
        const val CLAUDE_CAVEAT =
            "API-equivalent as reported by Claude Code; not your bill if you use a subscription."

        val CODEX_DEFAULT = Rate(
            inputPerM = 4.00,
            cachedInputPerM = 0.40,
            outputPerM = 20.00,
            cacheWriteMultiplier = 1.25,
            longPromptTokens = 272_000,
            longInputMultiplier = 2.0,
            longOutputMultiplier = 1.5,
        )
        val JEV_DEFAULT = Rate(inputPerM = 0.042, outputPerM = 0.0)

        /**
         * The pricing the command line asks for: the Codex default applies to
         * [DEFAULT_CODEX_MODEL] (or no `--codex-model`); another model needs
         * all three Codex rates, else its rate is unknown.
         */
        fun of(
            codexModel: String?,
            codexInput: Double?,
            codexCached: Double?,
            codexOutput: Double?,
            jevInput: Double?,
            jevOutput: Double?,
        ): Pricing {
            val model = codexModel ?: DEFAULT_CODEX_MODEL
            val anyCodexFlag = codexInput != null || codexCached != null || codexOutput != null
            val codex: Rate?
            val codexSource: PriceInfo?
            if (anyCodexFlag) {
                val base = CODEX_DEFAULT.takeIf { model == DEFAULT_CODEX_MODEL }
                val input = codexInput ?: base?.inputPerM
                val cached = codexCached ?: base?.cachedInputPerM
                val output = codexOutput ?: base?.outputPerM
                codex = if (input == null || cached == null || output == null) null else Rate(
                    inputPerM = input,
                    cachedInputPerM = cached,
                    outputPerM = output,
                    cacheWriteMultiplier = 1.25,
                    longPromptTokens = base?.longPromptTokens,
                    longInputMultiplier = base?.longInputMultiplier ?: 1.0,
                    longOutputMultiplier = base?.longOutputMultiplier ?: 1.0,
                )
                codexSource = PriceInfo("", "command-line rate flags", null, assumed = false)
            } else {
                codex = CODEX_DEFAULT.takeIf { model == DEFAULT_CODEX_MODEL }
                codexSource = null
            }
            val jevFlags = jevInput != null || jevOutput != null
            return Pricing(
                codexModel = model,
                codex = codex,
                codexSource = codexSource,
                jev = if (jevFlags) Rate(jevInput ?: JEV_DEFAULT.inputPerM, outputPerM = jevOutput ?: JEV_DEFAULT.outputPerM) else JEV_DEFAULT,
                jevSource = if (jevFlags) PriceInfo("", "command-line rate flags", null, assumed = false) else null,
            )
        }
    }
}

/**
 * One backend's usage within one question, as aggregate counters (SPEC §12:
 * durable, small — never a per-call log). [usd] sums the priced calls only;
 * [unpricedCalls] counts the rest.
 */
@Serializable
data class BackendTally(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val usd: Double = 0.0,
    val unpricedCalls: Int = 0,
    val models: List<String> = emptyList(),
) {
    fun plus(u: CallUsage, usd: Double?): BackendTally = copy(
        calls = calls + 1,
        inputTokens = inputTokens + u.inputTokens,
        cachedInputTokens = cachedInputTokens + u.cachedInputTokens,
        cacheWriteTokens = cacheWriteTokens + u.cacheWriteTokens,
        outputTokens = outputTokens + u.outputTokens,
        reasoningTokens = reasoningTokens + u.reasoningTokens,
        usd = if (usd != null && usd.isFinite()) this.usd + usd else this.usd,
        unpricedCalls = unpricedCalls + if (usd == null || !usd.isFinite()) 1 else 0,
        models = (models + u.models).distinct(),
    )
}
