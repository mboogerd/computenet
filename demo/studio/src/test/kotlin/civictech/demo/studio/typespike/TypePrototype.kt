package civictech.demo.studio.typespike

import kotlinx.serialization.json.JsonObject

/** Throwaway design probe: these declarations are NOT a production port contract. */
internal enum class Kind { text, gitRange, issueSet, judgment, json, any }
internal data class Output(val kind: Kind, val extras: Set<String> = emptySet(), val newSchema: JsonObject? = null) {
    val fields: Set<String> get() = setOf("old", "new") + extras + when (kind) {
        Kind.gitRange -> setOf("repo")
        Kind.issueSet -> setOf("workspace", "added", "removed", "changed")
        Kind.judgment -> setOf("probability", "reason") // answer is serialized as new
        else -> emptySet()
    }
}
internal data class Requirement(val fields: Set<String> = emptySet(), val kind: Kind? = null)
internal enum class Admission { ACCEPT, CHECK_ON_ARRIVAL, REJECT }
internal data class Compatibility(val admission: Admission, val message: String? = null)

// Preserve current flat field names (including author-name and literal dots).
private val token = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)(?::([A-Za-z_][A-Za-z0-9_]*))?(?:\\.([^{}]+))?}}")
internal fun infer(template: String): Map<String, Requirement> {
    val result = linkedMapOf<String, Requirement>()
    for (match in token.findAll(template)) {
        val (port, annotation, field) = match.destructured
        val kind = annotation.takeIf { it.isNotEmpty() }?.let(Kind::valueOf)
        val prior = result[port] ?: Requirement()
        require(kind == null || prior.kind == null || kind == prior.kind) { "Conflicting types for $port" }
        result[port] = Requirement(prior.fields + field.ifEmpty { "new" }, kind ?: prior.kind)
    }
    require(!token.replace(template, "").contains("{{")) { "Invalid placeholder" }
    return result
}

internal fun satisfies(output: Output, requirement: Requirement, port: String = "c"): Compatibility {
    val nominal = requirement.kind?.takeUnless { it == Kind.any }
    if (output.kind == Kind.any) return Compatibility(Admission.CHECK_ON_ARRIVAL,
        "$port: producer is any; validate on arrival")
    if (nominal != null && output.kind != nominal)
        return Compatibility(Admission.REJECT, "$port: requires $nominal; producer offers ${output.kind}")
    val missing = requirement.fields - output.fields
    if (missing.isNotEmpty()) return if (output.kind == Kind.json)
        Compatibility(Admission.CHECK_ON_ARRIVAL, "$port: JSON does not guarantee ${missing.sorted().joinToString()}; validate on arrival")
    else Compatibility(Admission.REJECT, "$port: requires field '${missing.sorted().first()}'; ${output.kind} offers ${output.fields.sorted().joinToString()}")
    return Compatibility(Admission.ACCEPT)
}

/** A Jev gate preserves the selected whole envelope and adds gate metadata. */
internal fun gateOutput(passPort: String, inputs: Map<String, Output>): Output =
    (inputs[passPort] ?: Output(Kind.any)).let { it.copy(extras = it.extras + "gate") }
