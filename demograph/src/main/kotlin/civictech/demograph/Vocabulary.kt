package civictech.demograph

/**
 * `:demograph` vocabulary charter (epic `computenet-drz8`).
 *
 * `:demograph` sits between `:kernel` and applications, the same dependency
 * shape as `:identity`: it depends on `:kernel` (and, transitively through
 * `:kernel`'s own `api(:nature)`, on `:nature`); `:kernel` never depends on
 * it. It provides data structures for capturing subjective stances and
 * personal preferences per actor, and for consolidating them into aggregate
 * views — insights over the participating actors. Collective ranking and
 * deliberation graphs are the first two families this vocabulary anchors,
 * not the extent of it.
 *
 * The six terms below are the whole of the vocabulary this charter fixes.
 * DGR.2/DGR.3 own the concrete families (stance/preference structures,
 * consolidation operators); this file anchors only the KDoc, with the
 * minimum declarations needed to attach it — no behaviour, no data structure
 * beyond the skeleton.
 */

/**
 * An **actor** is a participant whose stances and preferences `:demograph`
 * can hold. Per-actor identity is out of this module's scope — a concrete
 * family binds an actor to DSC1's `Principal` (`computenet-gb1` / AGO2)
 * rather than `:demograph` inventing its own identity type; here the term
 * names only the *role* a participant plays in a stance, a preference or a
 * weight below.
 */
public interface Actor

/**
 * A **stance** is one actor's subjective position toward some subject —
 * a claim, an option, a candidate ranking. "Subjective" is load-bearing:
 * a stance is that actor's own position, recorded as theirs, never
 * normalized into a shared or objective fact by the act of recording it.
 * Per-actor keyed stance maps are OR-map-shaped multi-writer state, a named
 * consumer of KE1 (`computenet-j2x`).
 */
public interface Stance

/**
 * A **preference** is an actor's comparative stance between two or more
 * subjects — which of a set they favor, and how strongly. Where a stance
 * can stand alone, a preference is inherently relational: it orders or
 * weighs alternatives against each other for the actor who holds it.
 */
public interface Preference

/**
 * A **weight** is the magnitude `:demograph` attaches to a stance or a
 * preference — how strongly an actor holds it, or how much it counts
 * toward a consolidated view. A weight is data the module carries and
 * consolidates; it is never itself the vote-as-civic-act the boundary line
 * below excludes.
 */
public interface Weight

/**
 * **Contestation** is the state of a subject carrying more than one actor's
 * stance or preference at once, without those stances being merged away or
 * one privileged over another. `:demograph` keeps contestation visible
 * through consolidation rather than resolving it silently — a
 * contestable-graph primitive in the socaity vision.md sense the epic
 * charter names.
 */
public interface Contestation

/**
 * **Aggregation** is a consolidated view over the stances, preferences and
 * weights of the participating actors — an insight derived from many
 * actors' positions, kept distinct from any one actor's own stance. Cyclic
 * deliberation graphs with fixpoint evaluation (AGO4, `computenet-6sz`) and
 * collective-ranking operators registered in the oracle catalog (ORA2,
 * `computenet-4ru.1`) are aggregation's first named consumers.
 */
public interface Aggregation

// Boundary (epic computenet-drz8, "what keeps this a legitimate ComputeNet
// child under socaity's substrate doctrine"): `:demograph` knows actor,
// stance, preference, weight, contestation, aggregation. It never models
// need, credit, subsidy, or vote-as-civic-act — those are the societal
// layer, out of scope for this module.
