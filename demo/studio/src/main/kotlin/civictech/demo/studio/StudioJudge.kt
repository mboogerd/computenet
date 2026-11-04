package civictech.demo.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

internal const val JUDGE_DEFAULTS = """{"question":"Should {{in}} proceed?","output":"boolean","choices":[],"mode":"judge","threshold":0.5,"passPort":"","passChoices":[],"model":"","timeoutSec":60,"auto":true}"""
private val judgeKeys = json.parseToJsonElement(JUDGE_DEFAULTS).jsonObject.keys
internal fun judgePorts(config: JsonObject) = infer(config.string("question")).keys.toList()
internal fun judgeChoices(config: JsonObject, key: String): List<String> =
    (config[key] as? JsonArray)?.map {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: bad("$key must contain strings")
    } ?: bad("$key must be an array")
internal fun validateJudge(config: JsonObject) {
    // Credentials are environment-only. Reject unknown fields before factory/journal writes.
    if ((config.keys - judgeKeys).isNotEmpty()) bad("unknown judge config field; credentials must use TYPESAFE_API_KEY in the environment")
    for (key in listOf("question", "output", "mode", "passPort", "model"))
        if ((config[key] as? JsonPrimitive)?.isString != true) bad("$key must be a string")
    if (config.string("question").isBlank()) bad("question must not be blank")
    if (config.string("output") !in setOf("boolean", "probability", "choice")) bad("invalid judge output")
    if (config.string("mode") !in setOf("judge", "gate")) bad("invalid judge mode")
    val threshold = (config["threshold"] as? JsonPrimitive)?.doubleOrNull
    if (threshold == null || !threshold.isFinite() || threshold !in 0.0..1.0) bad("threshold must be between 0 and 1")
    if ((config["auto"] as? JsonPrimitive)?.booleanOrNull == null) bad("auto must be boolean")
    if (config.long("timeoutSec", 0) <= 0) bad("timeoutSec must be positive")
    val choices = judgeChoices(config, "choices")
    val pass = judgeChoices(config, "passChoices")
    if (choices.any { it.isBlank() } || choices.distinct().size != choices.size || choices.size > 255) bad("invalid or duplicate choices")
    if (config.string("output") == "choice" && choices.isEmpty()) bad("choice output requires choices")
    if (pass.any { it !in choices } || pass.distinct().size != pass.size) bad("passChoices must be distinct members of choices")
    if (config.string("mode") == "gate") {
        val ports = judgePorts(config)
        if (ports.isEmpty()) bad("a gate requires an input placeholder")
        if (config.string("passPort").isNotEmpty() && config.string("passPort") !in ports) bad("passPort must name an input")
    }
}

@Serializable
data class StudioJudgment(val answer: String, val probability: Double? = null, val reason: String? = null)
@Serializable
data class StudioJudgeState(val last: StudioJudgment, val passed: Int = 0, val gated: Int = 0)
internal data class StudioJudgeResponse(val judgment: StudioJudgment, val costUsd: Double? = null)
internal fun interface StudioJudge {
    fun judge(config: JsonObject, inputs: Map<String, JsonObject>): StudioJudgeResponse
}

internal fun judgePasses(config: JsonObject, answer: StudioJudgment): Boolean = when (config.string("output")) {
    "boolean" -> answer.answer == "true"
    "probability" -> checkNotNull(answer.probability) >= config.getValue("threshold").jsonPrimitive.double
    else -> answer.answer in judgeChoices(config, "passChoices")
}
internal fun judgedChange(config: JsonObject, inputs: Map<String, JsonObject>, previous: String?, answer: StudioJudgment): JsonObject? {
    if (config.string("mode") == "gate") {
        if (!judgePasses(config, answer)) return null
        val port = config.string("passPort").ifEmpty { judgePorts(config).first() }
        return JsonObject(inputs.getValue(port) + ("gate" to buildJsonObject {
            put("probability", answer.probability?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", answer.reason?.let(::JsonPrimitive) ?: JsonNull)
        }))
    }
    return JsonObject(change(previous, answer.answer) + buildJsonObject {
        put("probability", answer.probability?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", answer.reason?.let(::JsonPrimitive) ?: JsonNull)
    })
}

/** A mockable HTTP boundary; neither requests nor raw response/error bodies are persisted. */
internal data class JevHttpResponse(val status: Int, val body: String)
internal fun interface JevHttp {
    fun send(request: HttpRequest): JevHttpResponse
}
private val jdkJevHttp = JevHttp { request ->
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().use { http ->
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        JevHttpResponse(response.statusCode(), response.body())
    }
}

private class JevFailure(message: String) : IOException(message)

/** Same JDK HTTP/System One pattern as alignment, with one bounded deadline across retries. */
internal class TypeSafeStudioJudge(
    private val environment: () -> String? = { System.getenv("TYPESAFE_API_KEY") },
    private val http: JevHttp = jdkJevHttp,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val nanoTime: () -> Long = System::nanoTime,
) : StudioJudge {
    override fun judge(config: JsonObject, inputs: Map<String, JsonObject>): StudioJudgeResponse {
        val key = environment()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Jev requires TYPESAFE_API_KEY in the server environment")
        val timeout = Duration.ofSeconds(config.long("timeoutSec", 60))
        val deadline = nanoTime() + timeout.toNanos()
        try {
            val question = buildJsonObject {
                put("type", if (config.string("output") == "choice") "choice" else "noul")
                put("instructions", substitute(config.string("question"), inputs))
                if (config.string("output") == "choice") put("criteria", JsonObject(
                    judgeChoices(config, "choices").associateWith { JsonNull }))
            }
            val body = buildJsonObject {
                put("model", config.string("model").ifBlank { "jev-latest" })
                put("state", JsonObject(inputs))
                put("questions", obj("judgment" to question))
            }.toString()
            for (attempt in 0..3) {
                val remaining = deadline - nanoTime()
                if (remaining <= 0) throw HttpTimeoutException("deadline")
                val request = HttpRequest.newBuilder(URI.create("https://api.typesafe.ai/v1/systemone"))
                    .timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer $key").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val response = http.send(request)
                if (deadline - nanoTime() <= 0) throw HttpTimeoutException("deadline")
                if (response.status == 200) {
                    val root = json.parseToJsonElement(response.body).jsonObject
                    val answer = root["answers"]?.jsonObject?.get("judgment")?.jsonObject
                        ?: throw JevFailure("Jev response missing judgment")
                    fun probability(element: JsonElement?): Double {
                        val p = (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                        if (p == null || !p.isFinite() || p !in 0.0..1.0) throw JevFailure("Jev response has invalid probability")
                        return p
                    }
                    val p: Double?
                    val text: String
                    if (config.string("output") == "choice") {
                        if (answer.string("type", "choice") != "choice") throw JevFailure("Jev response has wrong answer type")
                        text = answer.string("choice")
                        if (text !in judgeChoices(config, "choices")) throw JevFailure("Jev response has unknown choice")
                        p = (answer["probabilities"] as? JsonObject)?.get(text)?.let(::probability)
                    } else {
                        if (answer.string("type", "noul") != "noul") throw JevFailure("Jev response has wrong answer type")
                        p = probability(answer["noul"])
                        text = if (config.string("output") == "boolean") (p >= 0.5).toString() else p.toString()
                    }
                    val reason = (answer["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.replace(key, "[redacted]")
                    // The current API documents token usage only. Preserve USD only when explicitly supplied.
                    val cost = ((root["costUsd"] ?: root["cost_usd"]) as? JsonPrimitive)?.doubleOrNull
                        ?.takeIf { it.isFinite() && it >= 0 }
                    return StudioJudgeResponse(StudioJudgment(text, p, reason), cost)
                }
                if (response.status !in setOf(429, 529) || attempt == 3)
                    throw JevFailure("Jev HTTP ${response.status}") // raw bodies can echo credentials
                val delay = 500L shl attempt
                if (deadline - nanoTime() <= Duration.ofMillis(delay).toNanos()) throw HttpTimeoutException("deadline")
                sleeper(delay)
            }
            error("unreachable")
        } catch (_: HttpTimeoutException) {
            throw IOException("Jev timed out after ${timeout.seconds} seconds")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Jev interrupted")
        } catch (ex: JevFailure) {
            throw IOException(ex.message)
        } catch (_: IOException) {
            // Transport exceptions can contain headers or bodies; never retain their cause.
            throw IOException("Jev request failed")
        } catch (_: Exception) {
            throw JevFailure("Jev response or request was invalid")
        }
    }
}
