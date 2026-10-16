package civictech.bench.cost

import java.nio.file.Files
import java.nio.file.Path

/**
 * Applies the wire cross-check's validation marker to a cost export ([BEN2-21]).
 *
 * A null marker means that a point was not cross-checked; `false` is reserved for a
 * measured disagreement, and `true` means agreement at every cross-checked host count.
 * The whole-file rewrite in `MacroSweepTest` and [SubjectMerge]'s subject replacement
 * drop marks from regenerated entries, so the wire cross-check must be rerun after
 * either export regeneration ([0cnmw-D13], [0cnmw-D17]).
 */
object TransportValidation {
    /**
     * Marks every multi-host structural point with [validated] and resets single-host
     * points to null. All other model sections and the structural entry order remain
     * unchanged.
     */
    fun mark(model: CostModel, validated: Boolean): CostModel = model.copy(
        structural = model.structural.map { entry ->
            entry.copy(
                transportValidated = validated.takeIf { entry.at.hostCount >= 2 },
            )
        },
    )

    /**
     * Parses, marks, validates the encoded result, and only then rewrites [export].
     * A failure while parsing or validating occurs before the file is changed.
     */
    fun mark(export: Path, validated: Boolean): CostModel {
        val existing = CostSchema.parse(Files.readString(export))
        val marked = mark(existing, validated)
        val encoded = CostSchema.encode(marked)
        val validatedModel = CostSchema.parse(encoded)
        Files.writeString(export, encoded)
        return validatedModel
    }

    /** Clears every cross-check marker without changing any other model content. */
    fun clear(model: CostModel): CostModel = model.copy(
        structural = model.structural.map { it.copy(transportValidated = null) },
    )
}
