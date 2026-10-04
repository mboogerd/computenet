package civictech.demograph.ranking

import civictech.demograph.Preference

/**
 * An actor's comparative [Preference] between two items: the actor holds the
 * winner above the loser. Actor and item identifiers are opaque strings here;
 * binding an actor to a Principal belongs to AGO2/the flagship's integration.
 */
data class PairwisePreference(
    val actor: String,
    val winner: String,
    val loser: String,
) : Preference, java.io.Serializable

/**
 * One [PairwisePreference] projected onto one item with a signed contribution.
 * The actor and opponent keep this contribution distinct from other actors'
 * comparative stances. Actor and item identifiers remain opaque strings here.
 */
data class Contribution(
    val item: String,
    val actor: String,
    val opponent: String,
    val sign: Long,
) : java.io.Serializable

/** Projects this actor's comparative stance into the two signed item contributions. */
fun PairwisePreference.contributions(): List<Contribution> = listOf(
    Contribution(winner, actor, loser, +1),
    Contribution(loser, actor, winner, -1),
)
