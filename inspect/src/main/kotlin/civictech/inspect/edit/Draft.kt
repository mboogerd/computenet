package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.Plan
import civictech.cell.graph.StepCheck
import civictech.cell.graph.Verdict
import civictech.inspect.InspectorServer
import kotlinx.serialization.Serializable

/**
 * One write-plane edit, as [StagedApplier] applies it (e1ojt-D5): a [spec] of
 * staged spawns and spec-internal links, the [boundary] links that attach the
 * staged cells to live ones at CUT-OVER, the live cells to [despawns] once
 * CUT-OVER has committed (RETIRE, e1ojt-D7), and [promotions].
 *
 * **One host per draft.** Every staged spawn lands on [host] — a key into the
 * applier's host map — and every boundary ref must be local to it (F2's
 * `LiveView` is single-host and refuses `MULTI_HOST`). A [host] the applier
 * does not know is a caller fault, not a planned refusal.
 */
data class Draft(
    val host: String,
    val spec: GraphSpec,
    val boundary: List<BoundaryLink> = emptyList(),
    val despawns: List<CellRef> = emptyList(),
    val promotions: List<PromotionRequest> = emptyList(),
)

/**
 * Placeholder for a promotion request (a staged cell superseding a live one).
 * Feature F9 fills it; until then a non-empty [Draft.promotions] is refused
 * with `NOT_YET_SUPPORTED` before PRECHECK (e1ojt-D5).
 */
class PromotionRequest

/**
 * Serialisable projection of F2's [Plan] — what [ApplyRecord.plan] carries so
 * a `refused-at-precheck` record holds the whole plan (`[WKB2-15]`,
 * e1ojt-D4). [appliable] is the verdict the applier acted on.
 */
@Serializable
data class PlanDto(val steps: List<PlannedStepDto>, val appliable: Boolean)

/**
 * One planned step. [action] is the `PlannedAction` name, [touches] the live
 * refs it would change in the inspector's `"<uuid>:<instanceId>"` encoding,
 * and [refusal] is set exactly when the step was refused.
 */
@Serializable
data class PlannedStepDto(
    val key: String,
    val handle: String?,
    val action: String,
    val touches: List<String>,
    val refusal: RefusalDto? = null,
)

/** A refused step's `RefusalCode` name and its reason string. */
@Serializable
data class RefusalDto(val code: String, val reason: String)

/** Projects this plan onto its wire shape; [PlanDto.appliable] mirrors the verdict. */
fun Plan.toDto(): PlanDto = PlanDto(
    steps = steps.map { step ->
        PlannedStepDto(
            key = step.key,
            handle = step.handle,
            action = step.action.name,
            touches = step.touches.map(InspectorServer::encodeRef).sorted(),
            refusal = (step.result as? StepCheck.Refused)?.let { RefusalDto(it.code.name, it.reason) },
        )
    },
    appliable = verdict is Verdict.Appliable,
)
