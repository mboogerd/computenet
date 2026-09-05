package civictech.demo.allocatorobserve.declaration

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parsed allocation declaration used by the allocator-observe feature.
 * Equality is over parsed content, so it is also the declaration identity
 * used by the declaration history (fpml.2-D1).
 */
data class AllocationDeclaration(
    val weights: Map<String, Double>,
    val monthlyCapHours: Double,
    val window: String?,
)

/** The total result of parsing one allocation declaration. */
sealed interface DeclarationParse {
    data class Valid(val declaration: AllocationDeclaration) : DeclarationParse
    data object Malformed : DeclarationParse
}

/**
 * Parses the minimal R2 allocation layout. The layout is intentionally kept
 * in this file because the upstream allocation.yaml shape is unverified.
 * Unknown top-level keys are accepted for forward compatibility.
 *
 * Total: returns [DeclarationParse.Malformed] for every input that cannot be
 * decoded, rather than allowing a YAML or serialization exception to escape.
 */
fun parseAllocationDeclaration(text: String): DeclarationParse {
    return try {
        val decoded = allocationYaml.decodeFromString(AllocationDeclarationDto.serializer(), text)
        DeclarationParse.Valid(
            AllocationDeclaration(
                weights = decoded.projects,
                monthlyCapHours = decoded.monthlyCap.hours,
                window = decoded.window,
            ),
        )
    } catch (_: Exception) {
        DeclarationParse.Malformed
    }
}

private val allocationYaml = Yaml(
    configuration = YamlConfiguration(strictMode = false),
)

@Serializable
private data class AllocationDeclarationDto(
    val projects: Map<String, Double>,
    @SerialName("monthly_cap") val monthlyCap: MonthlyCapDto,
    val window: String? = null,
)

@Serializable
private data class MonthlyCapDto(val hours: Double)
