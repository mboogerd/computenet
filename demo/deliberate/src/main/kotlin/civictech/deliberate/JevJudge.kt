package civictech.deliberate

import civictech.agora.cell.Polarity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A Jev call that failed; [status] is the HTTP status, or null for an IO failure. */
class JevException(val status: Int?, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * [Judge] backed by TypeSafe System One (`POST {baseUrl}/v1/systemone`).
 *
 * Every judgment is one request carrying structured JSON `state`; question ids
 * are not seen by the model, so each question is self-contained. Scores over n
 * levels are mapped linearly to [0,1] as `score / (n-1)` (CRED-01, CRED-02).
 * 429/529 responses are retried with exponential backoff, at most [maxAttempts]
 * attempts in total (EXP-07); any other failure fails at once.
 */
class JevJudge(
    private val apiKey: String = System.getenv("TYPESAFE_API_KEY") ?: error("TYPESAFE_API_KEY is not set"),
    baseUrl: String = "https://api.typesafe.ai",
    private val model: String = "jev-latest",
    private val maxAttempts: Int = 4,
    private val backoff: Duration = Duration.ofMillis(500),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : Judge {

    init {
        require(maxAttempts in 1..4) { "maxAttempts must be between 1 and 4" }
        require(!backoff.isNegative) { "backoff must not be negative" }
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "requestTimeout must be positive" }
    }

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/systemone")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /**
     * CRED-01: judged on `root_question` and `claim` alone — no path, parent or
     * direction, which pulled the judgment towards the claim's role in the
     * argument (measured live; SPEC CRED-01).
     */
    override fun plausibility(question: String, path: List<String>, claim: String): Double {
        val state = buildJsonObject {
            put("root_question", question)
            put("claim", claim)
        }
        return scoreOf(evaluate(state, mapOf("plausibility" to plausibilityQuestion())).getValue("plausibility"), PLAUSIBILITY_LEVELS.size)
    }

    override fun relationStrength(question: String, parent: String, child: String, side: Side): Double {
        val state = buildJsonObject {
            put("root_question", question)
            put("parent_claim", parent)
            put("child_claim", child)
            put("direction", side.verb)
        }
        return scoreOf(evaluate(state, mapOf("strength" to strengthQuestion("child_claim", side))).getValue("strength"), STRENGTH_LEVELS.size)
    }

    override fun quality(question: String, parent: String, child: String, side: Side): Double {
        val state = buildJsonObject {
            put("root_question", question)
            put("parent_claim", parent)
            put("child_claim", child)
            put("direction", side.verb)
        }
        return noulOf(evaluate(state, mapOf("quality" to qualityQuestion("child_claim", side))).getValue("quality"))
    }

    /**
     * CRED-01, CRED-02, EXP-05 in two parallel requests: plausibility on its
     * own minimal state ([plausibility]), and strength, quality (the
     * construction Noul alone) and relevance as independent questions over the
     * argument's full state.
     */
    override fun assess(question: String, path: List<String>, child: String, side: Side): Assessment {
        // SPEC §12: the parallel request's usage belongs to the same question.
        val sink = Usage.current
        val plausibility = java.util.concurrent.CompletableFuture.supplyAsync(
            { Usage.within(sink) { plausibility(question, path, child) } },
            PARALLEL,
        )
        val state = buildJsonObject {
            put("root_question", question)
            putStrings("path_from_root", path)
            put("parent_claim", path.last())
            put("claim", child)
            put("direction", side.verb)
        }
        val answers = evaluate(
            state,
            mapOf(
                "strength" to strengthQuestion("claim", side),
                "quality" to qualityQuestion("claim", side),
                "relevant" to relevanceQuestion(),
            ),
        )
        val p = try {
            plausibility.join()
        } catch (e: java.util.concurrent.CompletionException) {
            throw e.cause ?: e
        }
        return Assessment(
            plausibility = p,
            strength = scoreOf(answers.getValue("strength"), STRENGTH_LEVELS.size),
            quality = noulOf(answers.getValue("quality")),
            relevance = noulOf(answers.getValue("relevant")),
        )
    }

    /**
     * EXP-03 in one request: per candidate an `a<i>` action Choice and, when
     * there is anything to point at, an independent `t<i>` target Choice over
     * the existing arguments (both sides) and the candidates before it.
     */
    override fun triage(ctx: ClaimContext, candidates: List<Candidate>): List<Triage> {
        if (candidates.isEmpty()) return emptyList()
        val state = buildJsonObject {
            put("root_question", ctx.question)
            putStrings("path_from_root", ctx.path)
            put("claim", ctx.claim)
            putStrings("existing_arguments_for", ctx.pros)
            putStrings("existing_arguments_against", ctx.cons)
        }
        val labels = ctx.pros.map { "(existing argument for the claim) $it" } +
            ctx.cons.map { "(existing argument against the claim) $it" } +
            candidates.map { "(another new argument ${it.side.preposition} the claim) ${it.text}" }
        val existing = ctx.pros.size + ctx.cons.size
        val questions = LinkedHashMap<String, JsonObject>()
        candidates.forEachIndexed { i, cand ->
            val targets = existing + i
            val earlier = candidates.take(i)
            questions["a$i"] = buildJsonObject {
                put("type", "choice")
                putJsonObject("instructions") {
                    put("candidate_argument", cand.text)
                    put("proposed_as", "an argument ${cand.side.preposition} the claim")
                    putStrings("earlier_new_arguments", earlier.map { "(${it.side.preposition} the claim) ${it.text}" })
                    put(
                        "question",
                        "`candidate_argument` was just proposed as an argument ${cand.side.preposition} `claim` (in " +
                            "the state). Compare it with the existing arguments on both sides and with " +
                            "`earlier_new_arguments` (proposed in the same batch). What should be done with it?",
                    )
                }
                putJsonObject("criteria") {
                    val p = cand.side.preposition
                    put("ADD", "Add it: a substantively new reason $p the claim that no existing or earlier new argument already makes.")
                    if (targets > 0) {
                        put("DUPLICATE", "Drop it as a duplicate: it makes essentially the same point as one existing or earlier new argument (possibly reworded, narrower or broader), adds nothing that argument lacks, and is not clearly better.")
                        put("REPLACE", "Replace: it makes the same point as one existing or earlier new argument but is clearly stronger or clearer, so it should take that argument's place.")
                        put("MERGE", "Merge: it and one existing or earlier new argument make overlapping points, each with something the other lacks, which are best stated together as one argument.")
                        put("REFINE", "Refine: it is a specific instance, example or piece of evidence for one existing or earlier new argument, supporting that argument rather than giving a new reason of its own.")
                        put("UNDERCUT", "Undercut: it does not dispute the claim itself; it denies that one existing or earlier new argument actually bears on the claim — even if that argument is true, it does not show what it is offered to show.")
                    }
                    put("OTHER_SIDE", "Move it: it actually argues ${cand.side.opposite.preposition} the claim, not $p it.")
                    put("DROP", "Drop it: it is not a real argument about the claim — off-topic, incoherent, a question, or a mere restatement of the claim itself.")
                }
            }
            if (targets > 0) questions["t$i"] = buildJsonObject {
                put("type", "choice")
                putJsonObject("instructions") {
                    put("candidate_argument", cand.text)
                    put(
                        "question",
                        "`candidate_argument` is a new argument about `claim` (in the state). Each option other " +
                            "than `$NONE` is another argument about the same claim. Which option shares the most " +
                            "with `candidate_argument`: the one it restates, overlaps with, improves on, is a " +
                            "specific instance of or evidence for, or denies the relevance of? Answer `$NONE` only " +
                            "if it shares no point with any option.",
                    )
                }
                putJsonObject("criteria") {
                    put(NONE, "None: `candidate_argument` shares no point with any option.")
                    labels.take(targets).forEachIndexed { j, text -> put(j.toString(), text) }
                }
            }
        }
        val answers = evaluate(state, questions)
        return candidates.indices.map { i ->
            val action = choiceOf(answers.getValue("a$i")).let { a ->
                TriageAction.entries.firstOrNull { it.name == a } ?: throw JevException(null, "unknown triage action '$a'")
            }
            val needsTarget = action in TARGETED
            val target = if (!needsTarget || "t$i" !in questions) null else choiceOf(answers.getValue("t$i")).let { t ->
                if (t == NONE) null else t.toIntOrNull()?.takeIf { it in 0 until existing + i }
                    ?: throw JevException(null, "unknown triage target '$t'")
            }
            Triage(action, target)
        }
    }

    override fun saturation(ctx: ClaimContext, side: Side): Double {
        val state = buildJsonObject {
            put("root_question", ctx.question)
            putStrings("path_from_root", ctx.path)
            put("claim", ctx.claim)
            putStrings("existing_arguments", if (side == Polarity.SUPPORT) ctx.pros else ctx.cons)
        }
        // EXP-04 asks the question in the "missing" direction: first live runs showed Jev
        // rarely affirms "covered", so the Noul is p(an important consideration is
        // still missing) and saturation is its complement.
        val q = noul(
            "`existing_arguments` are the arguments ${side.preposition} `claim` collected so far. Is an important " +
                "consideration ${side.preposition} `claim` still missing from `existing_arguments`?",
            yes = "Missing: at least one important consideration ${side.preposition} the claim is not yet " +
                "represented by `existing_arguments`, not even in other words.",
            no = "Nothing important missing: the important considerations ${side.preposition} the claim are already " +
                "represented (or there is genuinely nothing substantive to add); a further argument would mostly " +
                "restate or marginally refine them.",
        )
        return 1.0 - noulOf(evaluate(state, mapOf("missing" to q)).getValue("missing"))
    }

    override fun relevance(ctx: ClaimContext): Double {
        val state = buildJsonObject {
            put("root_question", ctx.question)
            putStrings("path_from_root", ctx.path)
            put("claim", ctx.claim)
        }
        return noulOf(evaluate(state, mapOf("relevant" to relevanceQuestion())).getValue("relevant"))
    }

    /** POSTs one request and returns its `answers` map, retrying transient failures. */
    internal fun evaluate(state: JsonElement, questions: Map<String, JsonObject>): Map<String, JsonElement> {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", JsonObject(questions))
        }.toString()
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        for (attempt in 1..maxAttempts) {
            try {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                val status = response.statusCode()
                if (status == 200) {
                    // SPEC §12: every successful request reports its usage; a retried 429/529 is not billed.
                    Usage.raw("jev") { Regex("\"usage\"\\s*:\\s*\\{[^}]*}").find(response.body())?.value ?: "none" }
                    Usage.capture("jev") { jevUsageOf(response.body()) }
                    return answersOf(response.body(), questions.keys)
                }
                val error = JevException(status, "Jev HTTP $status: ${response.body().take(500)}")
                if (status != 429 && status != 529 || attempt == maxAttempts) throw error
                sleeper(backoff.multipliedBy(1L shl (attempt - 1)))
            } catch (e: IOException) {
                throw JevException(null, "Jev IO failure: $e", e)
            }
        }
        error("retry loop exhausted")
    }

    private fun answersOf(body: String, expected: Set<String>): Map<String, JsonElement> {
        val answers = Json.parseToJsonElement(body).jsonObject["answers"]?.jsonObject
            ?: throw JevException(200, "Jev response without `answers`: ${body.take(500)}")
        val missing = expected - answers.keys
        if (missing.isNotEmpty()) throw JevException(200, "Jev response missing answers $missing")
        return answers
    }

    private companion object {
        const val NONE = "none"
        val TARGETED = setOf(TriageAction.DUPLICATE, TriageAction.REPLACE, TriageAction.MERGE, TriageAction.REFINE, TriageAction.UNDERCUT)

        /** [assess] runs its plausibility request beside the argument request. */
        val PARALLEL: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

        val PLAUSIBILITY_LEVELS = listOf(
            "Almost certainly false: available facts or well-established knowledge directly contradict the claim; " +
                "it would require exceptional contrary evidence to be true.",
            "Probably false: available evidence or well-established knowledge weighs against the claim, although " +
                "the claim remains reasonably possible.",
            "Uncertain: evidence is absent, balanced, or conflicting, so neither truth nor falsity is more likely.",
            "Probably true: available evidence or well-established knowledge supports the claim, although meaningful " +
                "uncertainty remains.",
            "Almost certainly true: available facts or well-established knowledge directly support the claim; it " +
                "would require exceptional contrary evidence to be false.",
        )

        val STRENGTH_LEVELS = listOf(
            "Irrelevant: assuming the child claim is true, it would not change the likelihood of the parent claim in " +
                "the stated direction, or would bear only in the opposite direction.",
            "Weak: assuming the child claim is true, it would move the parent claim only slightly in the stated " +
                "direction because it is peripheral or readily outweighed.",
            "Moderate: assuming the child claim is true, it would make a meaningful difference to the parent claim, " +
                "but several ordinary considerations could still outweigh it.",
            "Strong: assuming the child claim is true, it would substantially move the parent claim as one of the " +
                "main considerations, though it would not settle the parent claim by itself.",
            "Decisive: assuming the child claim is true, the parent claim would be settled in the stated direction " +
                "except under exceptional conditions.",
        )

        val Side.preposition get() = if (this == Polarity.SUPPORT) "for" else "against"
        val Side.verb get() = if (this == Polarity.SUPPORT) "supports" else "attacks"
        val Side.opposite get() = if (this == Polarity.SUPPORT) Polarity.ATTACK else Polarity.SUPPORT

        fun plausibilityQuestion() = score(
            "How likely is `claim` to be true? Judge only what `claim` itself asserts, using your general " +
                "knowledge. `root_question` only tells you what topic the claim is about: do not reward or " +
                "penalise `claim` for which answer to `root_question` it favours, and do not assume any other " +
                "claim is true or false. If `claim` is phrased as a question, judge how likely its answer is yes.",
            PLAUSIBILITY_LEVELS,
        )

        fun strengthQuestion(child: String, side: Side) = score(
            "`$child` is offered as an argument that ${side.verb} `parent_claim`. Assume `$child` is " +
                "true. How strongly would it then bear on `parent_claim` in that direction (`direction`)? Rate only " +
                "the strength of the connection, not whether `$child` is actually true. If it would bear in " +
                "the opposite direction, or not at all, rate it irrelevant.",
            STRENGTH_LEVELS,
        )

        fun qualityQuestion(child: String, side: Side) = noul(
            "`$child` is offered as an argument that ${side.verb} `parent_claim`. Is it a well-constructed " +
                "argument: a self-contained, coherent claim that actually bears on `parent_claim` in that direction " +
                "(`direction`)? Judge the construction, not whether it is true.",
            yes = "Well-constructed: a self-contained, coherent declarative claim that bears on `parent_claim` in the " +
                "stated direction.",
            no = "Poorly constructed: a restatement of `parent_claim`, off-topic, incoherent, not self-contained, a " +
                "rhetorical question, or bearing in the opposite direction.",
        )

        fun relevanceQuestion() = noul(
            "`claim` arose while deliberating `root_question`, via the chain of claims in `path_from_root`. Would " +
                "analysing `claim` further — examining the arguments for and against it — materially change how " +
                "`root_question` should be answered?",
            yes = "Material: if examining arguments changed whether `claim` is believed, that change would alter at " +
                "least one important reason for answering `root_question`.",
            no = "Immaterial: even if examining arguments changed whether `claim` is believed, the answer to " +
                "`root_question` would remain effectively the same because the claim is peripheral, redundant, or " +
                "too remote.",
        )

        fun choiceOf(answer: JsonElement): String = answer.jsonObject["choice"]?.jsonPrimitive?.content
            ?: throw JevException(null, "choice answer without `choice`")

        fun score(instructions: String, levels: List<String>) = buildJsonObject {
            put("type", "score")
            put("instructions", instructions)
            putStrings("criteria", levels)
        }

        fun noul(instructions: String, yes: String, no: String) = buildJsonObject {
            put("type", "noul")
            put("instructions", instructions)
            putJsonObject("criteria") {
                put("true", yes)
                put("false", no)
            }
        }

        fun scoreOf(answer: JsonElement, levels: Int): Double {
            val score = answer.jsonObject["score"]?.jsonPrimitive?.double
                ?: throw JevException(200, "score answer without `score`")
            return (score / (levels - 1)).coerceIn(0.0, 1.0)
        }

        fun noulOf(answer: JsonElement): Double =
            (answer.jsonObject["noul"]?.jsonPrimitive?.double ?: throw JevException(200, "noul answer without `noul`"))
                .coerceIn(0.0, 1.0)

        fun JsonObjectBuilder.putStrings(key: String, values: List<String>) =
            putJsonArray(key) { values.forEach { add(it) } }
    }
}

/** SPEC §12: the `usage` of a System One response ({input_tokens, output_tokens}); null when absent. */
internal fun jevUsageOf(body: String): CallUsage? {
    val root = Json.parseToJsonElement(body).jsonObject
    val u = root["usage"] as? JsonObject ?: return null
    fun long(k: String) = (u[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
    val model = (root["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return CallUsage(
        backend = Pricing.JEV,
        models = listOfNotNull(model),
        inputTokens = long("input_tokens"),
        outputTokens = long("output_tokens"),
    )
}
