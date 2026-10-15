package civictech.bench.cost

import java.nio.file.Files
import java.nio.file.Path

/**
 * Replaces one subject's evidence in an existing cost export.
 *
 * The replacement model contributes only its structural entries, exclusions, and
 * growth laws for [subject]. Its calibration section is intentionally ignored: the
 * existing calibration remains authoritative, and calibration replacement belongs to
 * [civictech.bench.macro.CalibrationExport.merge]. The existing model's schema version
 * and top-level harness SHA are retained; per-entry structural SHAs remain those of
 * the model that supplied each entry.
 *
 * The path overload validates the encoded result before changing the file. This means
 * a replacement with an entry for another subject is refused without rewriting the
 * export, and the schema's derived-envelope check protects the duplicated envelope
 * derivation below from drifting from [CostWriter].
 *
 * A later [civictech.bench.macro.MacroSweepTest] run rewrites the export whole with
 * its own structural snapshot. After such a run, callers must re-run or re-merge
 * both [civictech.bench.macro.MacroCalibrationRenderTest] and the re-baseline suite
 * so neither section is accidentally lost.
 */
object SubjectMerge {
    /**
     * Returns [existing] with every structural entry, exclusion, and law of [subject]
     * replaced by [replacement]'s.
     *
     * [replacement.calibration] is ignored; [existing]'s calibration is kept. Use the
     * calibration route in [civictech.bench.macro.CalibrationExport.merge] when that
     * section needs to change.
     */
    fun replaceSubject(
        existing: CostModel,
        subject: CostSubject,
        replacement: CostModel,
    ): CostModel {
        replacement.structural.forEach { entry ->
            require(entry.subject == subject) {
                "replacement structural entry at ${entry.at} carries ${entry.subject}; " +
                    "expected $subject"
            }
        }
        replacement.exclusions.forEach { exclusion ->
            require(exclusion.subject == subject) {
                "replacement exclusion at ${exclusion.at} carries ${exclusion.subject}; " +
                    "expected $subject"
            }
        }
        replacement.laws.forEach { law ->
            require(law.subject == subject) {
                "replacement growth law '${law.dimension}/${law.metric}' carries " +
                    "${law.subject}; expected $subject"
            }
        }

        val structural = existing.structural.filterNot { it.subject == subject } +
            replacement.structural
        return existing.copy(
            envelope = deriveEnvelope(structural),
            structural = structural,
            exclusions = existing.exclusions.filterNot { it.subject == subject } +
                replacement.exclusions,
            laws = existing.laws.filterNot { it.subject == subject } + replacement.laws,
        )
    }

    /**
     * Parses [export], applies [replaceSubject], validates the encoded result, writes
     * it, and returns the validated model.
     *
     * The replacement's calibration is ignored just like in the model overload; the
     * export's calibration is preserved. A failed subject check or schema validation
     * occurs before [export] is written.
     */
    fun replaceSubject(
        export: Path,
        subject: CostSubject,
        replacement: CostModel,
    ): CostModel {
        val existing = CostSchema.parse(Files.readString(export))
        val merged = replaceSubject(existing, subject, replacement)
        val encoded = CostSchema.encode(merged)
        val validated = CostSchema.parse(encoded)
        Files.writeString(export, encoded)
        return validated
    }

    private fun deriveEnvelope(entries: List<StructuralCost>): Envelope = Envelope(
        numeric = linkedMapOf(
            Dimensions.CARDINALITY to entries.range { it.at.cardinality.toDouble() },
            Dimensions.BATCH_SIZE to entries.range { it.at.batchSize.toDouble() },
            Dimensions.HOST_COUNT to entries.range { it.at.hostCount.toDouble() },
            Dimensions.FAN_OUT to entries.range { it.at.fanOut.toDouble() },
        ),
        categorical = linkedMapOf(
            Dimensions.SHAPE to entries.mapTo(linkedSetOf()) { it.at.shape },
            Dimensions.REPLICATION to entries.mapTo(linkedSetOf()) { it.at.replication.toString() },
            Dimensions.DURABILITY to entries.mapTo(linkedSetOf()) { it.at.durability.toString() },
        ),
    )

    private fun List<StructuralCost>.range(
        selector: (StructuralCost) -> Double,
    ): Envelope.Range {
        val values = map(selector)
        return Envelope.Range(values.min(), values.max())
    }
}
