package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [GeneratorConfig]'s two validation layers: numeric sanity (checked at construction, no
 * catalog involved) and vocabulary membership (checked against [OperatorCatalog], which is
 * process-wide and therefore registered/reset per test like every other catalog-touching test
 * in this module).
 */
class GeneratorConfigTest {

    private fun sane(vararg vocabulary: String = arrayOf("set")) = GeneratorConfig(
        depthRange = 1..3,
        sourceCount = 2,
        vocabulary = vocabulary.toList(),
        elementDomainSize = 10,
        scriptLength = 20,
        addRemoveRatio = 0.5,
        unobservedRemoveRatio = 0.2,
        terminalCount = 1,
    )

    @Test
    fun `a sane config constructs without complaint`() {
        sane()
    }

    @Test
    fun `zero sourceCount is rejected naming sourceCount`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(sourceCount = 0) }
        failure.message!! shouldContain "sourceCount"
    }

    @Test
    fun `a ratio above 1 is rejected naming the ratio field`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(addRemoveRatio = 1.5) }
        failure.message!! shouldContain "addRemoveRatio"
    }

    @Test
    fun `an unobservedRemoveRatio above 1 is rejected naming the field`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(unobservedRemoveRatio = 1.5) }
        failure.message!! shouldContain "unobservedRemoveRatio"
    }

    @Test
    fun `an empty depthRange is rejected naming depthRange`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(depthRange = 3..1) }
        failure.message!! shouldContain "depthRange"
    }

    @Test
    fun `zero hostCount is rejected naming hostCount`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(hostCount = 0) }
        failure.message!! shouldContain "hostCount"
    }

    @Test
    fun `zero writerCount is rejected naming writerCount`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(writerCount = 0) }
        failure.message!! shouldContain "writerCount"
    }

    @Test
    fun `zero elementDomainSize is rejected naming elementDomainSize`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(elementDomainSize = 0) }
        failure.message!! shouldContain "elementDomainSize"
    }

    @Test
    fun `zero scriptLength is rejected naming scriptLength`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(scriptLength = 0) }
        failure.message!! shouldContain "scriptLength"
    }

    @Test
    fun `zero terminalCount is rejected naming terminalCount`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(terminalCount = 0) }
        failure.message!! shouldContain "terminalCount"
    }

    @Test
    fun `an empty vocabulary is rejected naming vocabulary`() {
        val failure = assertThrows<IllegalArgumentException> { sane().copy(vocabulary = emptyList()) }
        failure.message!! shouldContain "vocabulary"
    }

    // --- catalog-backed vocabulary validation -------------------------------

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    @Test
    fun `a config naming only registered ids validates cleanly`() {
        sane(CoreOperators.Ids.SET, CoreOperators.Ids.FILTER).validated()
    }

    @Test
    fun `a config naming a bogus id alongside a registered one fails naming the bogus id verbatim`() {
        val failure = assertThrows<IllegalArgumentException> {
            sane(CoreOperators.Ids.SET, "no-such-operator").validateAgainstCatalog()
        }

        failure.message!! shouldContain "no-such-operator"
    }
}
