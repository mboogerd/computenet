package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.deliberate.CostLedger.Companion.withCost

/**
 * The UI's view (SPEC §6): the graph's nodes — every credence, the consensus
 * and the spread from the hub fold, where the cells put them (CRED-06) —
 * joined with the engine's exploration metadata. It only reads.
 */
internal class GraphProjection(private val policy: ExplorationPolicy, private val ledger: CostLedger) {

    private companion object {
        /** A node's credence before its first emission reached the hub. */
        const val NEUTRAL = 0.5
        /** Model C: how many cruxes a question lists. */
        const val CRUXES = 3
    }

    /** Caller holds the engine's lock (guarding [state] and the [ledger]). */
    fun project(graph: List<CredenceGraph.Node>, layers: LayerSet, state: EngineState): GraphDto {
        val neutral = List(layers.ids.size) { NEUTRAL }
        val neutralValues = neutral
        val nodes = graph.mapNotNull { n ->
            val values = n.credence?.values ?: neutral
            val named = layers.named(values)
            val consensus = n.credence?.consensus ?: NEUTRAL
            val credence = layers.headlineOf(values, consensus)
            val low = n.credence?.spreadLow ?: NEUTRAL
            val high = n.credence?.spreadHigh ?: NEUTRAL
            state.edges[n.ref]?.let { e ->
                // SPEC §3 "Links as claims": an edge carries its link's exploration state.
                val l = state.claims[n.ref]
                NodeDto(
                    ref = e.ref.id.toString(), kind = "EDGE", credence = credence, root = e.root.id.toString(),
                    credences = named, consensus = consensus, spreadLow = low, spreadHigh = high,
                    polarity = e.side.name, source = e.source.id.toString(), target = e.target.id.toString(),
                    strength = e.strength,
                    text = l?.text, depth = l?.depth, status = l?.status, override = l?.override,
                    reach = l?.reach, contribution = l?.contribution, sensitivity = n.sensitivity,
                    proSaturation = l?.proSaturation, conSaturation = l?.conSaturation, rounds = l?.rounds,
                    duplicatesDropped = l?.duplicatesDropped, error = l?.error,
                    triage = l?.triage?.mapKeys { it.key.name }?.ifEmpty { null },
                    activity = l?.let(::activityOf),
                )
            } ?: state.claims[n.ref]?.let { c ->
                val onLink = c.parent?.takeIf { it.isLink }
                NodeDto(
                    ref = c.ref.id.toString(), kind = "CLAIM", credence = credence, root = c.root.id.toString(),
                    credences = named, consensus = consensus, spreadLow = low, spreadHigh = high,
                    text = c.text, depth = c.depth, status = c.status, override = c.override,
                    proposer = c.proposer, plausibility = c.plausibility, relevance = c.relevance, reach = c.reach,
                    quality = c.quality, contribution = c.contribution, sensitivity = n.sensitivity,
                    proSaturation = c.proSaturation, conSaturation = c.conSaturation, rounds = c.rounds,
                    duplicatesDropped = c.duplicatesDropped, error = c.error,
                    alsoProposedBy = c.alsoProposedBy.toList().ifEmpty { null },
                    merged = c.merged.takeIf { it },
                    evidence = c.evidence.toList().ifEmpty { null },
                    triage = c.triage.mapKeys { it.key.name }.ifEmpty { null },
                    activity = activityOf(c),
                    undercuts = onLink?.takeIf { c.side == Polarity.ATTACK }?.ref?.id?.toString(),
                    onLink = onLink?.ref?.id?.toString(),
                    positionOf = c.root.id.toString().takeIf { c.parent == null && c.ref != c.root },
                )
            }
        }
        val window = DeliberationEngine.Config.YIELD_WINDOW
        val sensitivity = graph.associate { it.ref to it.sensitivity }
        val credences = graph.associate { it.ref to it.credence }
        val qs = state.questions.map { (root, text) ->
            // Links are part of the question's work (activity, rounds, cost), not of its claim count.
            val tree = state.claims.values.filter { it.root == root }
            val ys = state.yields[root].orEmpty()
            val queued = tree.count { it.status in ExplorationPolicy.ACTIVE }
            // Model C: "what would change the answer" — best crux score first, ties in creation order.
            val cruxes = tree.filter { it.parent != null }
                .mapNotNull { n ->
                    val p = if (n.isLink) n.argument!!.edge?.strength else n.plausibility
                    policy.cruxScore(sensitivity[n.ref], p)?.takeIf { it > 0.0 }?.let { n to it }
                }
                .sortedByDescending { it.second }.take(CRUXES).map { it.first.ref.id.toString() }
            // Model D: the verdict from Jev's first impression against what the arguments say from a neutral prior.
            fun verdicts(ref: civictech.cell.CellRef): Pair<Double?, Double?> {
                val cr = credences[ref]
                return cr?.let { layers.headlineOf(it.values, it.consensus) } to
                    cr?.neutral?.let { layers.headlineOf(it, layers.consensus(it)) }
            }
            val framing = state.claims[root]?.framing?.takeIf { it.mode != FramingMode.NONE }?.let { f ->
                val shares = graph.firstOrNull { it.ref == root }?.shares
                val positions = tree.filter { it.parent == null && it.ref != root }
                FramingDto(
                    mode = f.mode.name, term = f.term,
                    positions = positions.mapIndexed { i, p ->
                        val (v, n) = verdicts(p.ref)
                        val cr = credences[p.ref]
                        PositionDto(
                            ref = p.ref.id.toString(), text = p.text,
                            credence = layers.headlineOf(cr?.values ?: neutralValues, cr?.consensus ?: NEUTRAL),
                            firstImpression = p.plausibility, neutralCredence = n,
                            verdictsDisagree = v != null && n != null && LayerSet.oppositeSides(v, n),
                            share = if (f.mode == FramingMode.POSITIONS) {
                                shares?.let { s -> s.positions.indexOf(p.ref).takeIf { it >= 0 }?.let { s.consensus[it] } }
                            } else null,
                        )
                    },
                )
            }
            val (verdict, neutral) = if (framing != null) null to null else verdicts(root)
            QuestionDto(
                root.id.toString(), text, tree.count { !it.isLink }, queued > 0,
                yieldRounds = ys.size,
                yieldRecent = ys.takeLast(window).takeIf { it.isNotEmpty() }?.average(),
                yieldEarlier = ys.dropLast(window).takeIf { it.isNotEmpty() }?.average(),
                stoppedBy = policy.stoppedBy(
                    state.projectionQuestionView(root), active = queued > 0,
                    anyDiminishing = tree.any { it.status == Status.DIMINISHING },
                ),
                paused = root in state.paused,
                cruxes = cruxes,
                firstImpression = state.claims[root]?.plausibility.takeIf { framing == null },
                neutralCredence = neutral,
                verdictsDisagree = verdict != null && neutral != null && LayerSet.oppositeSides(verdict, neutral),
                framing = framing,
            ).withCost(ledger.costOf(root, rounds = tree.sumOf { it.rounds }, queued = queued))
        }
        return GraphDto(qs, nodes, layers.members)
    }

    /** What [c] is doing right now, for the UI's "now exploring" line; null when idle. */
    private fun activityOf(c: Claim): String? = when {
        c.roundInFlight -> "exploring"
        c.status == Status.JUDGING -> "judging"
        c.assessing -> "assessing"
        else -> null
    }
}
