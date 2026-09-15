package civictech.query.diag

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test

/**
 * BS-13 ([QRY1-REJECT-05], cab.5-D1/D11): the closed [RejectionCode] enum and the
 * [RejectionCoverage] registry agree in both directions. Adding a variant with no registered
 * producer fails (a) naming the variant; a producer that stops producing its code fails (b)
 * or (c) naming the producer.
 */
class RejectionExhaustivenessTest {

    @Test
    fun `(a) every RejectionCode variant has a registered producer, and nothing else is registered`() {
        val variants = RejectionCode.entries.toSet()
        val registered = RejectionCoverage.producers.filterValues { it.isNotEmpty() }.keys
        val missing = (variants - registered).map { it.name }.sorted()
        val extra = (registered - variants).map { it.name }.sorted()
        withClue("RejectionCode variants with no registered producer in RejectionCoverage: $missing") {
            missing.shouldBeEmpty()
        }
        withClue("RejectionCoverage keys that are not RejectionCode variants: $extra") {
            extra.shouldBeEmpty()
        }
    }

    @Test
    fun `(b) every producer's result is Rejected`() {
        val notRejected = RejectionCoverage.producers.flatMap { (code, producers) ->
            producers.mapNotNull { producer ->
                val result = producer.compile()
                if (result is CompileResult.Rejected) null else "$code / '${producer.name}' returned $result"
            }
        }
        withClue("producers whose compile did not return Rejected: $notRejected") {
            notRejected.shouldBeEmpty()
        }
    }

    @Test
    fun `(c) no producer is registered under a code it does not produce`() {
        val misregistered = RejectionCoverage.producers.flatMap { (code, producers) ->
            producers.mapNotNull { producer ->
                val codes = (producer.compile() as? CompileResult.Rejected)?.rejections?.map { it.code }.orEmpty()
                if (code in codes) null else "$code / '${producer.name}' produced $codes"
            }
        }
        withClue("producers registered under a code their result does not contain: $misregistered") {
            misregistered.shouldBeEmpty()
        }
    }
}
