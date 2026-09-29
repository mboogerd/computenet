package civictech.inspect.edit

import civictech.inspect.inspectorJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass

/**
 * Pins the write plane's closed terminal vocabulary (`[WKB2-39]`), its residue
 * classes (`[WKB2-25]`) and its phase order (`[WKB2-16]`). The closure is the
 * `[WKB2-20]` check: there is no arm for a partially-applied graph left in
 * place, and adding one — or any seventh outcome — fails here.
 *
 * The arm lists are read by reflection (`sealedSubclasses`) and named through
 * each arm's generated serializer, never hand-typed a second time, so a new
 * arm changes what is compared rather than slipping past a static list.
 */
class ApplyVocabularyTest {

    @OptIn(InternalSerializationApi::class)
    private fun serialNamesOf(sealed: KClass<*>): List<String> =
        sealed.sealedSubclasses.map { it.serializer().descriptor.serialName }

    @Test
    fun `ApplyOutcome is exactly the six WKB2-39 outcomes`() {
        val names = serialNamesOf(ApplyOutcome::class)
        // The set catches a rename; the size catches a duplicate or extra arm the set would fold.
        names.toSet() shouldBe setOf(
            "committed",
            "refused-at-precheck",
            "unwound-clean",
            "unwound-with-residue",
            "rolled-back-at-commit",
            "conflicted",
        )
        names.size shouldBe 6
    }

    @Test
    fun `Residue is exactly the three WKB2-25 classes`() {
        val names = serialNamesOf(Residue::class)
        names.toSet() shouldBe setOf("emitted-across-boundary", "owned-consumed", "leased-discharged")
        names.size shouldBe 3
    }

    @Test
    fun `ApplyPhase runs in the WKB2-16 order`() {
        ApplyPhase.entries.map { it.name } shouldContainExactly
            listOf("PRECHECK", "STAGE", "CUT_OVER", "UNWIND", "RETIRE")
    }

    @Test
    fun `UnwoundWithResidue refuses an empty residue list at construction`() {
        assertThrows<IllegalArgumentException> { ApplyOutcome.UnwoundWithResidue(emptyList()) }
        ApplyOutcome.UnwoundWithResidue(listOf(Residue.LeasedDischarged("u:1", "inlet")))
            .residue.size shouldBe 1
    }

    @Test
    fun `UnwoundWithResidue round-trips on the wire under its kebab-case names`() {
        val outcome: ApplyOutcome =
            ApplyOutcome.UnwoundWithResidue(listOf(Residue.OwnedConsumed("u:1", "inlet")))
        val json = inspectorJson.encodeToString(ApplyOutcome.serializer(), outcome)
        json shouldContain "\"type\":\"unwound-with-residue\""
        json shouldContain "\"type\":\"owned-consumed\""
        inspectorJson.decodeFromString(ApplyOutcome.serializer(), json) shouldBe outcome
    }

    @Test
    fun `UnwoundWithResidue refuses an empty residue list when decoded`() {
        // A wire-decoded instance is a construction too: the generated
        // deserialiser runs the class's init block, so the require() fires.
        val thrown = runCatching {
            inspectorJson.decodeFromString(
                ApplyOutcome.serializer(),
                """{"type":"unwound-with-residue","residue":[]}""",
            )
        }.exceptionOrNull()
        // Match our require()'s message, not just any decode failure, so a
        // malformed-JSON error cannot pass for the refusal.
        generateSequence(thrown) { it.cause }.any {
            it is IllegalArgumentException && it.message.orEmpty().contains("WKB2-25")
        } shouldBe true
    }

    @Test
    fun `payload-free outcomes encode as a bare discriminator`() {
        inspectorJson.encodeToString(ApplyOutcome.serializer(), ApplyOutcome.Committed) shouldBe
            """{"type":"committed"}"""
    }
}
