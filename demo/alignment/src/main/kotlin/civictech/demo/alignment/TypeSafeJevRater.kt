package civictech.demo.alignment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

/**
 * [AiRater] backed by TypeSafe System One (`POST {baseUrl}/v1/systemone`): one request per idea,
 * one five-level `score` question per dimension, plus one `knowledge` choice.
 *
 * The request carries only `{topic, idea_title, idea_description}`. No date (deliberate measured a
 * date moving judgements), no other idea, no human rating. Each dimension's question names the
 * dimension, gives its description, and anchors the scale's ends on its low/high labels; a score
 * `s` over the five levels maps to `1 + 8·s/4`, so Jev's fractional score keeps its resolution.
 * A COST or FACTOR dimension is asked literally ("how high is it on X") — [Direction] does the
 * inversion downstream, so the prompt never needs to know it.
 *
 * The answer's model is the one System One reports having run (`jev-1.13.0`), falling back to the
 * requested [model] if a response omits it — so `jev-latest` resolves to a versioned rater.
 *
 * Answering OUTSIDE on the `knowledge` choice is an abstention on every dimension of the idea, the
 * honest unrated state, as deliberate's `JevJudge` maps it to `OUTSIDE_KNOWLEDGE`.
 * ponytail: the gate is per idea, not per dimension; split it into a per-dimension choice if
 * calibration shows Jev guessing on org-internal dimensions ("cost to us").
 *
 * The HTTP and retry code is a port of `demo/deliberate`'s `JevJudge.evaluate`, not an import:
 * `:demo:alignment` does not depend on `:demo:deliberate`. 429/529 are retried with exponential
 * backoff, at most [maxAttempts] attempts in total; any other failure throws at once.
 */
internal class TypeSafeJevRater(
    private val apiKey: String,
    baseUrl: String = "https://api.typesafe.ai",
    private val model: String = "jev-latest",
    private val maxAttempts: Int = 4,
    private val backoff: Duration = Duration.ofMillis(500),
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : AiRater {
    override val name = model

    init {
        require(maxAttempts in 1..4) { "maxAttempts must be between 1 and 4" }
    }

    private val endpoint = URI.create(baseUrl.trimEnd('/') + "/v1/systemone")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun rate(topicTitle: String, idea: Idea, dims: Map<String, Dimension>): AiAnswer {
        if (dims.isEmpty()) return AiAnswer(model, emptyMap())
        val state = buildJsonObject {
            put("topic", topicTitle)
            put("idea_title", idea.title)
            put("idea_description", idea.description)
        }
        val ids = dims.keys.toList()
        val questions = LinkedHashMap<String, JsonObject>()
        ids.forEachIndexed { i, id -> questions["d$i"] = dimensionQuestion(dims.getValue(id)) }
        questions[KNOWLEDGE] = knowledgeQuestion()
        val (ran, answers) = evaluate(state, questions)
        val ratings = when (val known = choiceOf(answers.getValue(KNOWLEDGE))) {
            KNOWLEDGE_OUTSIDE -> emptyMap()
            KNOWLEDGE_WITHIN -> ids.withIndex().associate { (i, id) ->
                id to 1.0 + 8.0 * scoreOf(answers.getValue("d$i"))
            }
            else -> throw IOException("Jev: unknown knowledge answer '$known'")
        }
        return AiAnswer(ran, ratings)
    }

    /** POSTs one request and returns the model that ran and its `answers` map, retrying 429/529. */
    private fun evaluate(state: JsonElement, questions: Map<String, JsonObject>): Pair<String, Map<String, JsonElement>> {
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
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            if (status == 200) {
                val root = Json.parseToJsonElement(response.body()).jsonObject
                val answers = root["answers"]?.jsonObject
                    ?: throw IOException("Jev response without `answers`: ${response.body().take(500)}")
                val missing = questions.keys - answers.keys
                if (missing.isNotEmpty()) throw IOException("Jev response missing answers $missing")
                val ran = (root["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: model
                return ran to answers
            }
            if (status != 429 && status != 529 || attempt == maxAttempts) {
                throw IOException("Jev HTTP $status: ${response.body().take(500)}")
            }
            sleeper(backoff.multipliedBy(1L shl (attempt - 1)))
        }
        error("retry loop exhausted")
    }

    private companion object {
        const val KNOWLEDGE = "knowledge"
        const val KNOWLEDGE_WITHIN = "WITHIN_MY_KNOWLEDGE"
        const val KNOWLEDGE_OUTSIDE = "OUTSIDE_MY_KNOWLEDGE"
        const val LEVELS = 5

        fun dimensionQuestion(d: Dimension) = buildJsonObject {
            put("type", "score")
            put(
                "instructions",
                "Rate the idea in the state (`idea_title`, `idea_description`, proposed under `topic`) on one " +
                    "dimension: \"${d.name}\"." +
                    (if (d.description.isNotEmpty()) " What this dimension measures: ${d.description}" else "") +
                    " Rate how high the idea is on this dimension, judging only this dimension and only from " +
                    "what the idea says and your general knowledge.",
            )
            putJsonArray("criteria") {
                add("Lowest: " + d.lowLabel.ifEmpty { "as low on \"${d.name}\" as an idea plausibly gets" } + ".")
                add("Low: clearly below the middle on \"${d.name}\", though not at the extreme.")
                add("Middle: neither low nor high on \"${d.name}\".")
                add("High: clearly above the middle on \"${d.name}\", though not at the extreme.")
                add("Highest: " + d.highLabel.ifEmpty { "as high on \"${d.name}\" as an idea plausibly gets" } + ".")
            }
        }

        fun knowledgeQuestion() = buildJsonObject {
            put("type", "choice")
            putJsonObject("instructions") {
                put(
                    "question",
                    "Can you judge the idea in the state from what it says and your own general knowledge? Answer " +
                        "$KNOWLEDGE_OUTSIDE only when judging it needs facts you do not have (for example, private " +
                        "details of the team or organisation, or something too recent or too obscure), not merely " +
                        "when the answer is uncertain or contested.",
                )
            }
            putJsonObject("criteria") {
                put(KNOWLEDGE_WITHIN, "Within my knowledge: I can judge the idea, even if the answer is uncertain.")
                put(KNOWLEDGE_OUTSIDE, "Outside my knowledge: any rating of the idea would be a guess.")
            }
        }

        fun choiceOf(answer: JsonElement): String = answer.jsonObject["choice"]?.jsonPrimitive?.content
            ?: throw IOException("Jev: choice answer without `choice`")

        /** The five-level score as a fraction of the scale, [0, 1]. */
        fun scoreOf(answer: JsonElement): Double {
            val score = answer.jsonObject["score"]?.jsonPrimitive?.double
                ?: throw IOException("Jev: score answer without `score`")
            return (score / (LEVELS - 1)).coerceIn(0.0, 1.0)
        }
    }
}
