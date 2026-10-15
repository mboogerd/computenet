package civictech.demo.alignment

import civictech.demo.shell.esc
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/*
 * LLM ideation for a topic: Claude and Codex take turns proposing ideas, Jev
 * judges each proposal before it reaches the board and judges after every
 * round whether another one is worth running.
 *
 * Deliberately a COPY of `:demo:deliberate`'s round workflow (`CliProposer`,
 * `JevJudge`, `RoundProtocol`), cut down to a flat idea list: no sides, no
 * tree, no merge/replace/refine, no cost ledger. A shared abstraction is a
 * later decision (user, 2026-10-08); until then the two copies may drift.
 */

/** What a proposer and the judge see of a topic: its title, how ideas will be rated, and the ideas so far. */
internal data class IdeationContext(
    val topic: String,
    val dimensions: List<Dimension>,
    val ideas: List<ProposedIdea>,
)

internal data class ProposedIdea(val title: String, val description: String = "")

internal interface IdeaProposer {
    /** The participant name the proposer's ideas are attributed to (`claude`, `codex`). */
    val id: String

    fun propose(ctx: IdeationContext, max: Int): List<ProposedIdea>
}

internal enum class IdeaAction { ADD, DUPLICATE, DROP }

internal data class IdeaVerdict(val action: IdeaAction, val reason: String)

internal interface IdeaJudge {
    /** One verdict per candidate, in order; each candidate is compared with the existing ideas and the candidates before it. */
    fun triage(ctx: IdeationContext, candidates: List<ProposedIdea>): List<IdeaVerdict>

    /** Probability in [0,1] that the existing ideas already cover what matters on the topic. */
    fun saturation(ctx: IdeationContext): Double
}

// ── proposer: the CLI machinery, copied from deliberate's CliProposer ──────────

/**
 * [IdeaProposer] that shells out to an LLM CLI. Each call runs [command]'s argv
 * inside [gate], in a fresh empty temp directory (deleted afterwards), with
 * stdin closed and a [timeout] after which the process tree is destroyed.
 * [reader] turns (stdout, out file) into the answer text, which must contain a
 * JSON array of idea objects ([parseIdeas]).
 */
internal class CliIdeaProposer(
    override val id: String,
    private val command: (prompt: String, outFile: File) -> List<String>,
    private val gate: Semaphore,
    private val timeout: Duration = Duration.ofSeconds(180),
    private val reader: (stdout: String, outFile: String?) -> String = { stdout, out -> out?.takeIf { it.isNotEmpty() } ?: stdout },
) : IdeaProposer {

    override fun propose(ctx: IdeationContext, max: Int): List<ProposedIdea> = parseIdeas(run(prompt(ctx, max)), max)

    private fun run(prompt: String): String {
        val scratch = Files.createTempDirectory("alignment-$id-").toFile()
        try {
            val work = File(scratch, "work").apply { mkdir() }
            val out = File(scratch, "answer.txt")
            val stdout = File(scratch, "stdout.txt")
            val stderr = File(scratch, "stderr.txt")
            gate.acquire()
            val process = try {
                val p = ProcessBuilder(command(prompt, out)).directory(work)
                    .redirectOutput(stdout).redirectError(stderr).start()
                try {
                    p.outputStream.close()
                    if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        destroyTree(p)
                        throw IllegalStateException("$id timed out after ${timeout.toSeconds()} s")
                    }
                    p
                } catch (e: Exception) {
                    if (p.isAlive) destroyTree(p)
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    throw e
                }
            } finally {
                gate.release()
            }
            check(process.exitValue() == 0) { "$id exited ${process.exitValue()}: ${stderr.readText().takeLast(500)}" }
            return reader(stdout.readText(), out.takeIf { it.isFile }?.readText())
        } finally {
            scratch.deleteRecursively()
        }
    }

    // ponytail: one descendant snapshot; deliberate's CliProposer.destroyTree re-snapshots after a grace
    // wait to catch late-spawned children (computenet-lmpn3) — port that if an orphan is ever seen here.
    private fun destroyTree(p: Process) {
        val descendants = runCatching { p.descendants().toList() }.getOrDefault(emptyList())
        descendants.asReversed().forEach { it.destroyForcibly() }
        p.destroyForcibly()
        p.waitFor(5, TimeUnit.SECONDS)
    }

    companion object {
        const val CODEX_MODEL = "gpt-5.6-sol"

        /** Claude Code in print mode with every tool disabled; the answer is the JSON envelope's `result`. */
        fun claude(gate: Semaphore, model: String? = null) = CliIdeaProposer("claude", { prompt, _ ->
            // `--tools` is variadic: `--` stops it from swallowing the prompt as a tool name.
            listOf(
                "claude", "-p", "--output-format", "json", "--safe-mode", "--restricted",
                "--tools", "", "--disable-slash-commands", "--strict-mcp-config",
                "--permission-prompts", "none", "--no-session-persistence",
            ) + (model?.let { listOf("--model", it) } ?: emptyList()) + listOf("--", prompt)
        }, gate, reader = { stdout, _ -> claudeResult(stdout) })

        /** Codex with local/hosted tools disabled; the last message goes to the out file. */
        fun codex(gate: Semaphore, model: String = CODEX_MODEL) = CliIdeaProposer("codex", { prompt, out ->
            listOf(
                "codex", "exec", "--skip-git-repo-check", "--ephemeral", "--ignore-user-config",
                "--ignore-rules", "--strict-config", "--disable", "shell_tool", "--disable", "unified_exec",
                "--disable", "multi_agent", "--disable", "apps",
                "--disable", "view_image", "--disable", "image_generation", "--disable", "browser_use",
                "--disable", "computer_use", "--disable", "in_app_browser", "-s", "read-only",
                "-c", "web_search=\"disabled\"",
                "-c", "model_reasoning_effort=\"none\"",
                // Always pin the model: under --ignore-user-config codex otherwise falls back to its built-in default.
                "-m", model, "-o", out.absolutePath, "--", prompt,
            )
        }, gate)

        /** The `result` of Claude Code's `--output-format json` envelope; output that is not an envelope is the answer itself. */
        fun claudeResult(stdout: String): String {
            fun parse(text: String) = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull()
            fun JsonObject.isEnvelope() = (this["type"] as? JsonPrimitive)?.contentOrNull == "result" || "result" in this || "is_error" in this
            val envelope = parse(stdout)?.takeIf { it.isEnvelope() }
                ?: stdout.lineSequence().mapNotNull(::parse).firstOrNull { it.isEnvelope() }
                ?: return stdout
            val result = (envelope["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val isError = (envelope["is_error"] as? JsonPrimitive)?.booleanOrNull == true
            check(!isError && result != null) { "claude reported an error: ${(result ?: "no result").take(300)}" }
            return result
        }

        private fun bullets(items: List<String>) = if (items.isEmpty()) "  (none yet)" else items.joinToString("\n") { "  - $it" }

        fun prompt(ctx: IdeationContext, max: Int): String {
            val dims = ctx.dimensions.map { d ->
                val anchors = listOf(d.lowLabel.takeIf { it.isNotBlank() }?.let { "1 = $it" }, d.highLabel.takeIf { it.isNotBlank() }?.let { "9 = $it" })
                    .filterNotNull()
                if (anchors.isEmpty()) d.name else "${d.name} (${anchors.joinToString(", ")})"
            }
            val existing = ctx.ideas.map { if (it.description.isBlank()) it.title else "${it.title}: ${it.description}" }
            return """
                |You are helping a team brainstorm. Propose new ideas on the topic below.
                |
                |Topic:
                |  ${ctx.topic}
                |
                |The team will rate every idea from 1 to 9 on these dimensions:
                |${bullets(dims)}
                |
                |Ideas already on the board (do not repeat or reword them):
                |${bullets(existing)}
                |
                |Give at most $max new ideas, each a substantively different option from the ones above, not a variant
                |or refinement of one of them. Each idea is one concrete, actionable option the team could choose:
                |- title: at most 80 characters, naming the option itself (not a category or a question)
                |- description: one or two sentences saying what it is and why it could be worth doing
                |
                |Output ONLY a JSON array of at most $max objects, e.g. [{"title":"...","description":"..."}]. No other text.
            """.trimMargin()
        }
    }
}

/**
 * The first JSON array in [text] whose elements are objects (surrounding prose
 * and code fences are tolerated); elements without a non-blank string `title`
 * are skipped, fields trimmed, capped to [max]. Throws
 * [IllegalArgumentException] when there is no such array.
 */
internal fun parseIdeas(text: String, max: Int): List<ProposedIdea> {
    for (start in text.indices) {
        if (text[start] != '[') continue
        val end = matchingBracket(text, start) ?: continue
        val array = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
        if (array !is JsonArray || array.isEmpty() || !array.all { it is JsonObject }) continue
        return array.mapNotNull { e ->
            val o = e as JsonObject
            val title = (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(200)
            val description = (o["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(4000) ?: ""
            title?.takeIf { it.isNotEmpty() }?.let { ProposedIdea(it, description) }
        }.take(max)
    }
    throw IllegalArgumentException("no JSON array of idea objects in output: ${text.take(300)}")
}

/** Index of the `]` closing the `[` at [start], skipping brackets inside JSON strings. */
private fun matchingBracket(text: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        when {
            inString && c == '\\' -> i++
            c == '"' -> inString = !inString
            inString -> {}
            c == '[' -> depth++
            c == ']' -> if (--depth == 0) return i
        }
        i++
    }
    return null
}

// ── judge: Jev over TypeSafe System One, copied from deliberate's JevJudge ─────

internal class JevException(val status: Int?, message: String) : RuntimeException(message)

/**
 * The HTTP client [JevIdeaJudge] talks through. Retrying is the client's own
 * configuration (Ktor's `HttpRequestRetry`), not code in the judge: a status in
 * [retryOn] or an IO failure/timeout is retried up to [maxRetries] times with
 * exponential backoff — `baseDelay · 2^(n-1)` capped at [maxDelay], plus up to
 * [jitter] random, honouring a `Retry-After` header — after which the last
 * response (or exception) is the caller's.
 *
 * The defaults span ~30 s of retrying (1, 2, 4, 8, 8 s + jitter): measured
 * 2026-10-08, Jev answers `529 system_overloaded` up to 4 times in a row and
 * `503 model_unavailable` intermittently, each clearing within seconds.
 */
internal fun jevHttpClient(
    maxRetries: Int = 5,
    baseDelay: Duration = Duration.ofSeconds(1),
    maxDelay: Duration = Duration.ofSeconds(8),
    jitter: Duration = Duration.ofMillis(500),
    requestTimeout: Duration = Duration.ofSeconds(60),
    retryOn: Set<Int> = setOf(429, 502, 503, 504, 529),
): HttpClient = HttpClient(Java) {
    install(HttpTimeout) { requestTimeoutMillis = requestTimeout.toMillis() }
    install(HttpRequestRetry) {
        retryIf(maxRetries) { _, response -> response.status.value in retryOn }
        retryOnException(maxRetries, retryOnTimeout = true)
        exponentialDelay(baseDelayMs = baseDelay.toMillis(), maxDelayMs = maxDelay.toMillis(), randomizationMs = jitter.toMillis())
    }
}

/**
 * [IdeaJudge] backed by TypeSafe System One (`POST {baseUrl}/v1/systemone`).
 * One request per call carrying structured `state`; retries are [http]'s
 * configuration ([jevHttpClient]), so a failure seen here is final.
 */
internal class JevIdeaJudge(
    private val apiKey: String,
    baseUrl: String = "https://api.typesafe.ai",
    private val model: String = "jev-latest",
    private val http: HttpClient = jevHttpClient(),
) : IdeaJudge {

    private val endpoint = baseUrl.trimEnd('/') + "/v1/systemone"

    override fun triage(ctx: IdeationContext, candidates: List<ProposedIdea>): List<IdeaVerdict> {
        if (candidates.isEmpty()) return emptyList()
        val questions = LinkedHashMap<String, JsonObject>()
        candidates.forEachIndexed { i, cand ->
            questions["a$i"] = buildJsonObject {
                put("type", "choice")
                putJsonObject("instructions") {
                    put("candidate_idea", cand.render())
                    putStrings("earlier_new_ideas", candidates.take(i).map { it.render() })
                    put(
                        "question",
                        "`candidate_idea` was just proposed for `topic` (in the state). Compare it with `existing_ideas` " +
                            "and with `earlier_new_ideas` (proposed in the same batch). Is it worth adding to the board " +
                            "for the team to rate on `rating_dimensions`?",
                    )
                }
                putJsonObject("criteria") {
                    for (a in IdeaAction.entries) put(a.name, CRITERIA.getValue(a))
                }
            }
        }
        val answers = evaluate(ctx.state(), questions)
        return candidates.indices.map { i ->
            val choice = choiceOf(answers.getValue("a$i"))
            val action = IdeaAction.entries.firstOrNull { it.name == choice } ?: throw JevException(null, "unknown triage action '$choice'")
            IdeaVerdict(action, REASONS.getValue(action))
        }
    }

    override fun saturation(ctx: IdeationContext): Double {
        // Asked in the "missing" direction, as deliberate's EXP-04 does: Jev rarely affirms "covered".
        val q = buildJsonObject {
            put("type", "noul")
            put(
                "instructions",
                "`existing_ideas` are the options collected so far for `topic`. Is an important, substantively " +
                    "different option for `topic` still missing from `existing_ideas`?",
            )
            putJsonObject("criteria") {
                put("true", "Missing: at least one important option for the topic is not yet represented by `existing_ideas`, not even in other words.")
                put("false", "Nothing important missing: the important options are already represented; a further idea would mostly restate or marginally vary them.")
            }
        }
        val missing = evaluate(ctx.state(), mapOf("missing" to q)).getValue("missing")
        val p = missing.jsonObject["noul"]?.jsonPrimitive?.double ?: throw JevException(200, "noul answer without `noul`")
        return 1.0 - p.coerceIn(0.0, 1.0)
    }

    private fun IdeationContext.state() = buildJsonObject {
        put("topic", topic)
        putStrings("rating_dimensions", dimensions.map { it.name })
        putStrings("existing_ideas", ideas.map { it.render() })
    }

    private fun evaluate(state: JsonElement, questions: Map<String, JsonObject>): Map<String, JsonElement> {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", JsonObject(questions))
        }.toString()
        // ponytail: runBlocking bridges Ktor's suspend API onto the run's (virtual) thread; the loop is blocking by design
        val (status, text) = try {
            runBlocking {
                val response = http.post(endpoint) {
                    bearerAuth(apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                response.status.value to response.bodyAsText()
            }
        } catch (e: IOException) {
            throw JevException(null, "Jev IO failure: $e")
        }
        if (status != 200) throw JevException(status, "Jev HTTP $status: ${errorOf(text)}")
        val answers = Json.parseToJsonElement(text).jsonObject["answers"]?.jsonObject
            ?: throw JevException(200, "Jev response without `answers`: ${text.take(500)}")
        val missing = questions.keys - answers.keys
        if (missing.isNotEmpty()) throw JevException(200, "Jev response missing answers $missing")
        return answers
    }

    /** `detail.error_type` of a TypeSafe error body (e.g. `model_unavailable`), else the body's start. */
    private fun errorOf(text: String): String = runCatching {
        Json.parseToJsonElement(text).jsonObject["detail"]!!.jsonObject["error_type"]!!.jsonPrimitive.content
    }.getOrElse { text.take(200) }

    private companion object {
        val CRITERIA = mapOf(
            IdeaAction.ADD to "Add it: a substantively new, on-topic, concrete option that no existing or earlier new idea already " +
                "covers, and worth the team's time to rate.",
            IdeaAction.DUPLICATE to "Drop it as a duplicate: it is essentially the same option as one existing or earlier new idea " +
                "(possibly reworded, narrower or broader) and adds nothing that idea lacks.",
            IdeaAction.DROP to "Drop it: off-topic, too vague to act on, not an option the team could choose, or not worth the " +
                "team's time to rate.",
        )
        val REASONS = mapOf(
            IdeaAction.ADD to "new option",
            IdeaAction.DUPLICATE to "same option as an idea already on the board",
            IdeaAction.DROP to "off-topic, too vague, or not worth rating",
        )

        fun ProposedIdea.render() = if (description.isBlank()) title else "$title — $description"

        fun choiceOf(answer: JsonElement): String = answer.jsonObject["choice"]?.jsonPrimitive?.content
            ?: throw JevException(null, "choice answer without `choice`")

        fun JsonObjectBuilder.putStrings(key: String, values: List<String>) = putJsonArray(key) { values.forEach { add(it) } }
    }
}

// ── the loop: deliberate's round protocol, flattened ──────────────────────────

internal data class HeldIdea(val title: String, val description: String, val proposer: String, val action: IdeaAction, val reason: String)

/** One ideation run's progress, read by the page over `/state`. Every member is guarded by the instance. */
internal class IdeationStatus(val budget: Int) {
    var running = true
    var round = 0
    var added = 0
    var stopped: String? = null
    var error: String? = null
    val held = ArrayList<HeldIdea>()

    @Volatile
    var cancelled = false

    @Synchronized
    fun json(): String {
        val heldJson = held.joinToString(",", "[", "]") { h ->
            """{"title":${esc(h.title)},"description":${esc(h.description)},"proposer":${esc(h.proposer)},""" +
                """"verdict":${esc(h.action.name.lowercase())},"reason":${esc(h.reason)}}"""
        }
        return """{"running":$running,"round":$round,"added":$added,"budget":$budget,""" +
            """"stopped":${stopped?.let(::esc) ?: "null"},"error":${error?.let(::esc) ?: "null"},"held":$heldJson}"""
    }
}

/**
 * One ideation run over a topic, deliberate's round workflow flattened. Each
 * round, every proposer in [proposers] order takes a turn: it sees the topic's
 * ideas as they are NOW (so the second proposer sees what the first just
 * added), proposes at most [Config.ideasPerTurn], exact-title duplicates are
 * held back without asking Jev, and one Jev triage call decides the rest. ADD
 * goes to the board via [add]; every other verdict is held back.
 *
 * Jev's gate is strict: a failed triage call holds the whole batch back rather
 * than adding unjudged ideas (deliberate falls back to ADD). A failed proposer
 * call is recorded and the next proposer still takes its turn.
 *
 * After each round the run stops on: cancel, budget spent, [Config.maxRounds],
 * a round that added nothing, or Jev judging the topic saturated.
 */
internal class IdeationRun(
    private val proposers: List<IdeaProposer>,
    private val judge: IdeaJudge,
    private val context: () -> IdeationContext,
    private val add: (ProposedIdea, String) -> Boolean,
    private val onChange: () -> Unit,
    val status: IdeationStatus,
    private val config: Config = Config(),
) {
    data class Config(val ideasPerTurn: Int = 3, val maxRounds: Int = 4, val saturation: Double = 0.7)

    fun run() {
        try {
            status.stop(loop())
        } catch (e: Exception) {
            synchronized(status) { status.error = e.toString() }
            status.stop("failed")
        } finally {
            onChange()
        }
    }

    private fun loop(): String {
        while (true) {
            if (status.cancelled) return "stopped by you"
            if (status.round >= config.maxRounds) return "round limit reached"
            synchronized(status) { status.round++ }
            onChange()
            var addedThisRound = 0
            for (p in proposers) {
                if (status.cancelled) return "stopped by you"
                val room = status.budget - synchronized(status) { status.added }
                if (room <= 0) return "idea budget spent"
                addedThisRound += turn(p, minOf(config.ideasPerTurn, room))
                onChange()
            }
            if (synchronized(status) { status.added } >= status.budget) return "idea budget spent"
            if (addedThisRound == 0) return "the last round added nothing"
            if (status.cancelled) return "stopped by you"
            val saturation = runCatching { judge.saturation(context()) }
                .onFailure { e -> synchronized(status) { status.error = "jev saturation: $e" } }
                .getOrNull()
            if (saturation != null && saturation >= config.saturation) return "Jev judged the topic covered"
        }
    }

    /** One proposer's turn; answers how many ideas reached the board. */
    private fun turn(p: IdeaProposer, max: Int): Int {
        val ctx = context()
        val proposed = try {
            p.propose(ctx, max).take(max)
        } catch (e: Exception) {
            synchronized(status) { status.error = "${p.id}: $e" }
            return 0
        }
        // exact-title dedupe first, as deliberate's RoundProtocol.dedupe does: no Jev call for a verbatim repeat
        val seen = ctx.ideas.mapTo(HashSet()) { normalize(it.title) }
        val fresh = ArrayList<ProposedIdea>()
        for (idea in proposed) {
            if (seen.add(normalize(idea.title))) fresh += idea else hold(idea, p.id, IdeaVerdict(IdeaAction.DUPLICATE, "same title as an existing idea"))
        }
        if (fresh.isEmpty()) return 0
        val verdicts = try {
            judge.triage(ctx, fresh).takeIf { it.size == fresh.size } ?: error("expected ${fresh.size} verdicts")
        } catch (e: Exception) {
            synchronized(status) { status.error = "jev triage: $e" }
            fresh.forEach { hold(it, p.id, IdeaVerdict(IdeaAction.DROP, "not judged: Jev was unavailable")) }
            return 0
        }
        var added = 0
        for ((idea, v) in fresh.zip(verdicts)) {
            when {
                v.action != IdeaAction.ADD -> hold(idea, p.id, v)
                add(idea, p.id) -> { added++; synchronized(status) { status.added++ } }
                else -> hold(idea, p.id, IdeaVerdict(IdeaAction.DUPLICATE, "same id as an existing idea"))
            }
        }
        return added
    }

    private fun hold(idea: ProposedIdea, proposer: String, v: IdeaVerdict) = synchronized(status) {
        status.held += HeldIdea(idea.title, idea.description, proposer, v.action, v.reason)
    }

    private fun IdeationStatus.stop(reason: String) = synchronized(this) {
        running = false
        if (stopped == null) stopped = reason
    }

    private companion object {
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';')
    }
}
