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

    override fun plausibility(question: String, path: List<String>, claim: String): Double {
        val state = buildJsonObject {
            put("root_question", question)
            putStrings("path_from_root", path)
            put("claim", claim)
        }
        val q = score(
            "How likely is `claim` to be true? Judge the claim on its own merits and your general knowledge. " +
                "`root_question` and `path_from_root` (the chain of claims leading to it) are context for what " +
                "the claim means only. If `claim` is phrased as a question, judge how likely its answer is yes.",
            PLAUSIBILITY_LEVELS,
        )
        return scoreOf(evaluate(state, mapOf("plausibility" to q)).getValue("plausibility"), PLAUSIBILITY_LEVELS.size)
    }

    override fun relationStrength(question: String, parent: String, child: String, side: Side): Double {
        val state = buildJsonObject {
            put("root_question", question)
            put("parent_claim", parent)
            put("child_claim", child)
            put("direction", if (side == Polarity.SUPPORT) "supports" else "attacks")
        }
        val q = score(
            "`child_claim` is offered as an argument that ${side.verb} `parent_claim`. Assume `child_claim` is " +
                "true. How strongly would it then bear on `parent_claim` in that direction (`direction`)? Rate only " +
                "the strength of the connection, not whether `child_claim` is actually true. If it would bear in " +
                "the opposite direction, or not at all, rate it irrelevant.",
            STRENGTH_LEVELS,
        )
        return scoreOf(evaluate(state, mapOf("strength" to q)).getValue("strength"), STRENGTH_LEVELS.size)
    }

    override fun duplicates(claim: String, side: Side, existing: List<String>, candidates: List<String>): List<Int?> {
        if (candidates.isEmpty()) return emptyList()
        if (existing.isEmpty()) return candidates.map { null }
        val state = buildJsonObject {
            put("claim", claim)
            put("side", "arguments ${side.preposition} the claim")
        }
        val criteria = buildJsonObject {
            put(NONE, "None of the existing arguments: the candidate makes a substantively new point.")
            existing.forEachIndexed { i, text -> put(i.toString(), text) }
        }
        val questions = candidates.withIndex().associate { (i, candidate) ->
            "c$i" to buildJsonObject {
                put("type", "choice")
                putJsonObject("instructions") {
                    put("candidate_argument", candidate)
                    put(
                        "question",
                        "`candidate_argument` is a new argument ${side.preposition} `claim` (in the state). Each " +
                            "option other than `$NONE` is an existing argument on the same side. Which existing " +
                            "argument makes essentially the same point as `candidate_argument` — the same reason, " +
                            "possibly reworded, narrower or broader? Answer `$NONE` if `candidate_argument` adds a " +
                            "substantively different reason.",
                    )
                }
                put("criteria", criteria)
            }
        }
        val answers = evaluate(state, questions)
        return candidates.indices.map { i ->
            val choice = answers.getValue("c$i").jsonObject["choice"]?.jsonPrimitive?.content
                ?: throw JevException(null, "choice answer without `choice`")
            if (choice == NONE) null else choice.toIntOrNull()?.takeIf { it in existing.indices }
                ?: throw JevException(null, "unknown duplicate option '$choice'")
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
        val q = noul(
            "`claim` arose while deliberating `root_question`, via the chain of claims in `path_from_root`. Would " +
                "analysing `claim` further — examining the arguments for and against it — materially change how " +
                "`root_question` should be answered?",
            yes = "Material: if examining arguments changed whether `claim` is believed, that change would alter at " +
                "least one important reason for answering `root_question`.",
            no = "Immaterial: even if examining arguments changed whether `claim` is believed, the answer to " +
                "`root_question` would remain effectively the same because the claim is peripheral, redundant, or " +
                "too remote.",
        )
        return noulOf(evaluate(state, mapOf("relevant" to q)).getValue("relevant"))
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
                if (status == 200) return answersOf(response.body(), questions.keys)
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
