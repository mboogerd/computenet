package civictech.dialogue

/**
 * Stage 2 of the AGO1 pipeline (epic computenet-2aw §2.2): an [Utterance]
 * expands into the discourse [Segment]s extraction is applied to.
 *
 * The rule lives alone in this file on purpose (epic §8/R6): segmentation is
 * a **hidden, replaceable design decision**, not incidental code. Everything
 * downstream — content hashing, extraction, minting, provenance — is keyed
 * off what this function decides a segment is, so when the demo's results
 * turn out to be dominated by the splitting rule rather than by extraction
 * quality, the fix is to replace this one function, and the observation is a
 * `doc/demo-findings.md` note.
 *
 * The rule, deliberately tiny and deliberately pure:
 *
 * - Split on a sentence-ending punctuation mark (`.`, `!`, `?`) followed by
 *   whitespace. Punctuation at the very end of the text simply terminates
 *   the last segment, so "followed by whitespace **or end**" needs no second
 *   case.
 * - Trim each piece; drop the ones that are blank.
 * - [Segment.ordinal] is the 0-based index of the surviving pieces, and
 *   [Segment.id] is `"${utterance.id}#${ordinal}"`.
 * - [Segment.speaker] and [Segment.utteranceId] are carried from the
 *   utterance unchanged.
 *
 * **Purity is load-bearing, not stylistic.** Stage 2 is spawned as a
 * `civictech.cell.data.op.FlatMapSetCell<Utterance, Segment>` over
 * `::segment`, and that cell's contract states "[f] must be pure — dels
 * re-apply it to translate removals" and re-derives its output from input
 * state on late-join catch-up. A segmentation that consulted a clock, a
 * counter or any other outside state would translate a retraction into
 * segments that never existed, stranding their extracted items live forever.
 * No clock, no randomness, no I/O ([AGO1-EXTR-01]).
 */
fun segment(utterance: Utterance): List<Segment> =
    SENTENCE_BOUNDARY.split(utterance.text)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { ordinal, text ->
            Segment(
                id = "${utterance.id}#$ordinal",
                utteranceId = utterance.id,
                ordinal = ordinal,
                speaker = utterance.speaker,
                text = text,
            )
        }

/**
 * A sentence-ending mark followed by whitespace. The lookbehind keeps the
 * punctuation attached to the segment it ends, so a segment reads as the
 * sentence a human would quote.
 */
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
