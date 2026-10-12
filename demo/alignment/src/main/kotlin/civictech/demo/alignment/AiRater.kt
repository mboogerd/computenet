package civictech.demo.alignment

/**
 * A machine rater whose ratings form the AI score, aggregated apart from the human score.
 *
 * Each rater is asked about ONE idea at a time and sees only the topic title, that idea and the
 * dimensions' definitions — never another idea, never a human rating. That blindness is what makes
 * its score an independent second opinion rather than an echo of the room, and it also makes a
 * rating stable: adding an idea to the topic never changes the ratings of the others.
 *
 * Each answer names the model AND version that produced it ([AiAnswer.model]), and the ratings are
 * stored under participant `ai:<model>` ([RaterClass]). So two versions of one model are two raters,
 * concurrently present and aggregated together into the AI score, and an upgrade never overwrites
 * what the previous version said.
 *
 * Jev ([TypeSafeJevRater]) is the first; a Claude- or Codex-backed rater is another implementation
 * of this, with no change to the aggregation. The beads-triage heuristic ([BeadsHeuristic]) also
 * writes into the AI population but is not one of these: it rank-normalizes across a whole round,
 * so it seeds directly instead of answering per idea.
 */
internal interface AiRater {
    /** The rater as configured, e.g. `jev-latest` — what the facilitator sees; the version comes per answer. */
    val name: String

    /**
     * Ratings of [idea] on [dims] (keyed by dimension id) on the `[1, 9]` scale. A dimension absent
     * from the answer is an abstention and stays unrated — never a default. A thrown exception is a
     * failed call: nothing is written for the idea and a later run asks again.
     */
    fun rate(topicTitle: String, idea: Idea, dims: Map<String, Dimension>): AiAnswer

    companion object {
        /** Jev (unpinned, `jev-latest`) when `TYPESAFE_API_KEY` is set, otherwise none (the default build stays offline). */
        fun defaults(): List<AiRater> =
            System.getenv("TYPESAFE_API_KEY")?.takeIf { it.isNotBlank() }?.let { listOf(TypeSafeJevRater(it)) }.orEmpty()
    }
}

/**
 * One [AiRater] answer: [model] is the model and version that produced it, as the provider reports
 * it (`jev-1.13.0`, not the `jev-latest` alias it was called by); [ratings] as [AiRater.rate].
 */
internal data class AiAnswer(val model: String, val ratings: Map<String, Double>) {
    /** The participant name the ratings are stored under: `ai:` + [model]. */
    val participant: String get() = RaterClass.AI_PREFIX + model
}
