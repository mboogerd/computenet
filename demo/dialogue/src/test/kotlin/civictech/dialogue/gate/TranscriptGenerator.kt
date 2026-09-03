package civictech.dialogue.gate

import civictech.agora.cell.Polarity
import civictech.dialogue.Segment
import civictech.dialogue.Utterance
import civictech.dialogue.extract.CassetteExtractor
import civictech.dialogue.extract.ExtractedClaim
import civictech.dialogue.extract.ExtractedItem
import civictech.dialogue.extract.ExtractedRelation
import civictech.dialogue.extract.ExtractedStance
import civictech.dialogue.extract.segmentContentHash
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.StringReader
import kotlin.random.Random

/**
 * The seeded transcript generator behind BS-10 (task computenet-2aw.6.1,
 * epic computenet-2aw §4 BS-10, §3.2 [AGO1-EXTR-03]).
 *
 * One call yields a whole *scenario*: the utterances, the in-memory cassette
 * that extracts them, and an admit/retract **program** — the order the
 * scenario is driven into a live pipeline in. Everything is a pure function
 * of [seed] and [cyclic]; two calls with the same arguments produce equal
 * transcripts, which [IncrementalEqualsBatchTest] asserts.
 *
 * ### What the content deliberately contains
 *
 * - `~10` distinct claim texts, each introduced by a claim+stance utterance.
 * - Relation utterances over already-introduced claims, ATTACK or SUPPORT at
 *   random. A relation that would close a cycle in the claim digraph is
 *   emitted **only** when [cyclic] is true (checked with [reaches] over the
 *   relations issued so far, the shape `AgoraExitTest` uses), so an even seed
 *   is a DAG and can be compared to the batch reference at 1e-9.
 * - Stance-change utterances: the same speaker on the same claim at a later
 *   turn with a different value, which is what exercises
 *   [civictech.dialogue.mint.StanceProject]'s last-writer-wins **by event
 *   order** rather than by admission order.
 * - Restatements: a second speaker asserting an existing claim text verbatim,
 *   so a canonical claim carries provenance from more than one utterance id.
 * - Retractions of roughly one in six earlier utterances, at least one of
 *   which is the **sole** contributor of a relation — the case the
 *   retraction-blind control in [IncrementalEqualsBatchTest] must diverge on.
 *
 * ### Two invariants that are load-bearing, not stylistic
 *
 * 1. **Every utterance's text is unique and is one sentence.** A cassette
 *    entry is keyed by [segmentContentHash] — over segment *text* only — and
 *    returns its recorded items verbatim, including their `utteranceId`. Two
 *    utterances with identical text would therefore share one recorded id and
 *    the transcript would silently stop being what this file describes. One
 *    sentence (no `[.!?]` followed by whitespace) means [
 *    civictech.dialogue.segment] splits each utterance 1:1, so every segment
 *    has a cassette entry and no segment ever fails extraction.
 * 2. **Exactly one claim-introducing utterance is retracted, and it is a
 *    reserved one.** Retracting the sole minter of a claim key while a stance
 *    on that key survives makes `GraphApplier` record a `claim not bound`
 *    failure, which would break BS-10's "the final reconcile has zero
 *    failures" precondition for a reason that has nothing to do with the
 *    property under test. So [PRISTINE_CLAIM] is excluded from stance changes
 *    and restatements — its introduction is the only utterance that ever
 *    mentions it apart from relations — and that introduction is retracted at
 *    the end of every program.
 *
 *    That retraction is not decoration: it un-mints a claim key that relations
 *    still point at, so the reference's **pending** rule (a relation enters
 *    only when both endpoint keys are minted, stages 5d/5e) is load-bearing on
 *    every seed. An [ANCHOR relation][Kind.RELATION] from claim 1 onto
 *    [PRISTINE_CLAIM] is emitted unconditionally and never retracted, so at
 *    least one relation is orphaned per seed. Without this the pending filter
 *    could be deleted from [DialogueBatchReference] with the whole sweep still
 *    green.
 */
object TranscriptGenerator {

    /** How many distinct claim texts a transcript may introduce. */
    const val CLAIM_COUNT = 10

    /** How many speakers take part. */
    const val SPEAKER_COUNT = 4

    /**
     * How many utterances a transcript holds. A **generator constant**: the
     * task's cost clause allows shrinking this if the sweep gets expensive,
     * and explicitly does not allow shrinking the seed set or widening the
     * tolerance.
     */
    const val UTTERANCE_COUNT = 34

    /**
     * The claim whose introduction is retracted at the end of every program,
     * so a minted claim key really does disappear and the pending rule is
     * exercised. Nothing but its introduction and relations ever mentions it.
     */
    const val PRISTINE_CLAIM = 2

    /**
     * The target of the control relation — the one relation that is always
     * emitted, always the sole contributor of its key, and always retracted.
     */
    const val CONTROL_TARGET_CLAIM = 3

    /** What an utterance was generated to carry. */
    enum class Kind { CLAIM_INTRO, RELATION, STANCE_CHANGE, RESTATEMENT }

    /** One step of the drive program. */
    sealed interface Step {
        val utterance: Utterance

        data class Admit(override val utterance: Utterance) : Step
        data class Retract(override val utterance: Utterance) : Step
    }

    /** One generated scenario. */
    data class Generated(
        val transcript: List<Utterance>,
        val cassette: CassetteExtractor,
        val program: List<Step>,
        /** What each utterance id was generated to carry. */
        val kinds: Map<String, Kind>,
        /** True when the generator emitted at least one cycle-closing relation. */
        val closedACycle: Boolean,
        /**
         * The unconditional `claim 1 -> PRISTINE_CLAIM` relation utterance. It
         * is never retracted, and [pristineIntroId] always is, so this
         * relation is orphaned (pending) in every final live set.
         */
        val anchorRelationId: String,
        /**
         * The `claim 1 -> CONTROL_TARGET_CLAIM` relation utterance, retracted
         * at the end of every program. Both its endpoints survive and no other
         * utterance asserts its triple, so it is the sole contributor of its
         * relation key — the divergence the retraction-blind control needs.
         */
        val controlRelationId: String,
        /** The retracted introduction of [PRISTINE_CLAIM]. */
        val pristineIntroId: String,
    ) {
        /** The utterances a [Step.Retract] names — the retracted set at the end of the program. */
        val retracted: Set<Utterance> get() = program.filterIsInstance<Step.Retract>().map { it.utterance }.toSet()

        /** The utterances live at the end of the program, in generation order. */
        val live: List<Utterance> get() = transcript.filter { it !in retracted }

        fun kindOf(utterance: Utterance): Kind = kinds.getValue(utterance.id)
    }

    /** The canonical text of claim [n] — what the extractor reports, not what the speaker said. */
    fun claimText(n: Int): String = "Proposition $n holds."

    fun generate(seed: Long, cyclic: Boolean): Generated {
        val rnd = Random(seed)
        val utterances = mutableListOf<Utterance>()
        val entries = mutableMapOf<String, List<ExtractedItem>>()
        val kinds = mutableMapOf<String, Kind>()

        // Claim number -> speakers who currently hold a stance on it, so a
        // stance change can pick a speaker who actually has one to change.
        val stanceHolders = mutableMapOf<Int, MutableSet<String>>()
        val introduced = mutableListOf<Int>()
        // The claim digraph issued so far, as (source claim, target claim).
        val edges = mutableListOf<Pair<Int, Int>>()
        // Claim number -> the id of the utterance that introduced it.
        val claimIntroIds = mutableMapOf<Int, String>()
        var closedACycle = false

        fun speaker(i: Int) = "speaker$i"

        fun emit(kind: Kind, text: String, items: (String) -> List<ExtractedItem>): Utterance {
            val turn = utterances.size + 1
            val id = "u$turn"
            val utterance = Utterance(id = id, turn = turn, speaker = "", tsMillis = 1000L * turn, text = text)
            entries[segmentContentHash(hashSegment(text))] = items(id)
            kinds[id] = kind
            return utterance
        }

        /**
         * The speaker attribute of the *utterance* is the one the transcript
         * carries; the speaker attribute of an extracted claim/stance is the
         * one the extractor reports. They agree here, which is why [emit]
         * takes the utterance's speaker separately.
         */
        fun add(kind: Kind, speakerName: String, text: String, items: (String) -> List<ExtractedItem>) {
            val u = emit(kind, text, items).copy(speaker = speakerName)
            utterances += u
        }

        fun introduceClaim() {
            val n = introduced.size + 1
            val who = speaker(rnd.nextInt(SPEAKER_COUNT))
            val value = 0.1 + rnd.nextInt(9) / 10.0
            add(Kind.CLAIM_INTRO, who, "Turn ${utterances.size + 1} asserts proposition $n.") { id ->
                listOf(
                    ExtractedClaim(text = claimText(n), speaker = who, utteranceId = id),
                    ExtractedStance(claimText = claimText(n), speaker = who, value = value, utteranceId = id),
                )
            }
            introduced += n
            claimIntroIds[n] = utterances.last().id
            stanceHolders.getOrPut(n) { mutableSetOf() } += who
        }

        /** Claims a stance change or a restatement may name — never [PRISTINE_CLAIM]. */
        fun mentionable(): List<Int> = introduced.filter { it != PRISTINE_CLAIM }

        fun addStanceChange() {
            val choices = mentionable()
            val n = choices[rnd.nextInt(choices.size)]
            val holders = stanceHolders.getValue(n).toList().sorted()
            val who = holders[rnd.nextInt(holders.size)]
            val value = 0.05 + rnd.nextInt(19) / 20.0
            add(Kind.STANCE_CHANGE, who, "Turn ${utterances.size + 1} revises the view on proposition $n.") { id ->
                listOf(ExtractedStance(claimText = claimText(n), speaker = who, value = value, utteranceId = id))
            }
            stanceHolders.getValue(n) += who
        }

        fun addRestatement() {
            val choices = mentionable()
            val n = choices[rnd.nextInt(choices.size)]
            val who = speaker(rnd.nextInt(SPEAKER_COUNT))
            add(Kind.RESTATEMENT, who, "Turn ${utterances.size + 1} repeats proposition $n verbatim.") { id ->
                listOf(ExtractedClaim(text = claimText(n), speaker = who, utteranceId = id))
            }
        }

        fun emitRelation(source: Int, target: Int, polarity: Polarity, closes: Boolean) {
            val who = speaker(rnd.nextInt(SPEAKER_COUNT))
            add(
                Kind.RELATION,
                who,
                "Turn ${utterances.size + 1} draws a ${polarity.name.lowercase()} from $source onto $target.",
            ) { id ->
                listOf(
                    ExtractedRelation(
                        sourceText = claimText(source),
                        targetText = claimText(target),
                        polarity = polarity.name,
                        utteranceId = id,
                    ),
                )
            }
            edges += source to target
            if (closes) closedACycle = true
        }

        /** @return whether a relation was actually emitted. */
        fun addRelation(): Boolean {
            if (introduced.size < 2) return false
            val source = introduced[rnd.nextInt(introduced.size)]
            val target = introduced[rnd.nextInt(introduced.size)]
            if (source == target) return false
            // Reserved for the control relation above, so it stays the sole
            // contributor of its key.
            if (source == 1 && target == CONTROL_TARGET_CLAIM) return false
            val polarity = if (rnd.nextBoolean()) Polarity.ATTACK else Polarity.SUPPORT
            val closes = reaches(edges, from = target, to = source)
            if (closes && !cyclic) return false
            emitRelation(source, target, polarity, closes)
            return true
        }

        // Three claims first, so relations have somewhere to land.
        repeat(3) { introduceClaim() }
        // The anchor: claim 1 -> PRISTINE_CLAIM, emitted unconditionally
        // before anything else and never retracted. The claim graph is empty
        // here, so it can close no cycle on any seed.
        emitRelation(1, PRISTINE_CLAIM, if (rnd.nextBoolean()) Polarity.ATTACK else Polarity.SUPPORT, closes = false)
        val anchorRelationId = utterances.last().id
        // The control: claim 1 -> claim 3, ATTACK, emitted unconditionally and
        // retracted at the end of every program. Both endpoints survive, and
        // `addRelation` refuses to re-emit the (1, 3) pair, so this utterance
        // is the SOLE contributor of its relation key on every seed — which is
        // what makes the retraction-blind control in IncrementalEqualsBatchTest
        // have something to disagree about.
        emitRelation(1, CONTROL_TARGET_CLAIM, Polarity.ATTACK, closes = false)
        val controlRelationId = utterances.last().id
        while (utterances.size < UTTERANCE_COUNT) {
            val roll = rnd.nextInt(10)
            when {
                introduced.size < CLAIM_COUNT && roll < 3 -> introduceClaim()
                roll < 8 -> if (!addRelation()) addStanceChange()
                roll < 9 -> addStanceChange()
                else -> addRestatement()
            }
        }

        // ------------------------------------------------------------------
        // The drive program: admit in generation order, interleaving
        // retractions of earlier utterances (never a CLAIM_INTRO — see the
        // class doc's invariant 2).
        // ------------------------------------------------------------------
        val program = mutableListOf<Step>()
        val retracted = mutableSetOf<String>()
        utterances.forEachIndexed { index, utterance ->
            program += Step.Admit(utterance)
            if (rnd.nextInt(6) == 0) {
                val eligible = utterances.take(index + 1)
                    .filter { kinds.getValue(it.id) != Kind.CLAIM_INTRO }
                    .filter { it.id != anchorRelationId && it.id != controlRelationId && it.id !in retracted }
                if (eligible.isNotEmpty()) {
                    val victim = eligible[rnd.nextInt(eligible.size)]
                    program += Step.Retract(victim)
                    retracted += victim.id
                }
            }
        }

        // The control's teeth, and the reserved claim's un-minting, both go
        // last and unconditionally. The control retraction is what the
        // retraction-blind reference re-admits; the reserved claim's
        // retraction is what makes the pending rule load-bearing.
        val controlRelation = utterances.first { it.id == controlRelationId }
        program += Step.Retract(controlRelation)
        retracted += controlRelationId

        // The reserved claim's introduction goes last, un-minting a claim key
        // that the anchor relation (and possibly others) still points at — so
        // the pending rule is load-bearing on every seed. Nothing else names
        // this claim, so no stance survives it and no `claim not bound`
        // failure can be recorded against it.
        val pristineIntro = utterances.first { it.id == claimIntroIds.getValue(PRISTINE_CLAIM) }
        program += Step.Retract(pristineIntro)
        retracted += pristineIntro.id

        return Generated(
            transcript = utterances.toList(),
            cassette = cassetteOf(entries),
            program = program.toList(),
            kinds = kinds.toMap(),
            closedACycle = closedACycle,
            anchorRelationId = anchorRelationId,
            controlRelationId = controlRelationId,
            pristineIntroId = pristineIntro.id,
        )
    }

    /**
     * Whether [to] is reachable from [from] in the claim digraph [edges] —
     * so adding `source -> target` closes a cycle exactly when
     * `reaches(edges, from = target, to = source)`.
     *
     * Claims only: relation endpoints are `ClaimKey`s and edge-on-edge is
     * unreachable in this pipeline (computenet-2aw.4.5), so the cycle
     * structure of the argumentation graph is exactly the cycle structure of
     * this digraph.
     */
    fun reaches(edges: List<Pair<Int, Int>>, from: Int, to: Int): Boolean {
        if (from == to) return true
        val seen = mutableSetOf(from)
        val stack = ArrayDeque<Int>().apply { add(from) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            edges.filter { it.first == n }.forEach { (_, next) ->
                if (next == to) return true
                if (seen.add(next)) stack.add(next)
            }
        }
        return false
    }

    private fun hashSegment(text: String) =
        Segment(id = "hash", utteranceId = "hash", ordinal = 0, speaker = "hash", text = text)

    private fun cassetteOf(entries: Map<String, List<ExtractedItem>>): CassetteExtractor {
        val json = Json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(ExtractedItem.serializer())),
            entries,
        )
        return CassetteExtractor.load(StringReader(json))
    }
}
