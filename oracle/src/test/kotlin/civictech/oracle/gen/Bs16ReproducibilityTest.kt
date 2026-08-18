package civictech.oracle.gen

import civictech.oracle.bind.OperatorCatalog
import civictech.testkit.JvmPeer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * BS-16 (`[ORA1-GEN-01]`): the fixed `(seed = 42L, config)` case generated **in two separate
 * JVMs** serializes to byte-equal output.
 *
 * ## Why a child process and not two calls
 *
 * The feature's example says "two forked JVMs". Gradle's test forking gives *a* fork, but both
 * halves of a two-call comparison then run in the **same** one — sharing class initialization,
 * a warmed `OperatorCatalog`, one heap and one set of identity hash codes. Exactly the epic's
 * risk-5 nondeterminism sources are the ones that agree with themselves inside a process, so a
 * same-JVM comparison cannot see them. Here the second case is produced by
 * `Bs16FingerprintMain` in a JVM launched fresh by [JvmPeer.launch] (which inherits only the
 * test classpath), and the comparison is between processes.
 *
 * ## Why byte equality of the serialization, not a structural fingerprint
 *
 * `GeneratedCase` is `Serializable` all the way down by epic decision D3, so its serialized
 * form *is* the case's rendering — the thing a recorded case is written to disk as. Comparing
 * it needs no hand-written traversal, and therefore cannot silently omit a field the way a
 * `toString`-based digest can when a new one is added. The bead's fallback (a canonical
 * structural fingerprint) is not used: byte equality held, and no unstable `serialVersionUID`
 * in a kernel type forced a weaker comparison.
 *
 * One fixed case, one child JVM, once per run — `[ORA1-PERF-01]`'s module budget is a sweep
 * budget, and this test deliberately does not sweep.
 */
class Bs16ReproducibilityTest {

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        // Bs16Case.generate() registers into the process-wide singleton the sibling gen tests share.
        OperatorCatalog.reset()
    }

    @Test
    fun `the fixed case serializes byte-identically in this JVM and in a freshly launched one`(
        @TempDir tmp: File,
    ) {
        val childOutput = File(tmp, "bs16-child.ser")

        val here = Bs16Case.serialize(Bs16Case.generate())

        val peer = JvmPeer.launch(Bs16FingerprintMain::class.java.name, childOutput.absolutePath)
        try {
            val exited = peer.process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            withClue({ "child JVM did not exit within ${CHILD_TIMEOUT_SECONDS}s\n\n${peer.report()}" }) {
                exited shouldBe true
            }
            withClue({ "child JVM failed\n\n${peer.report()}" }) {
                peer.process.exitValue() shouldBe 0
            }
            withClue({ "child JVM wrote no case file\n\n${peer.report()}" }) {
                childOutput.isFile shouldBe true
            }

            val there = childOutput.readBytes()
            withClue({
                "the fixed BS-16 case is not reproducible across JVMs — " +
                    "this JVM: ${Bs16Case.describe(here)}; child JVM: ${Bs16Case.describe(there)}" +
                    "\n\n${peer.report()}"
            }) {
                there.contentEquals(here) shouldBe true
            }
        } finally {
            JvmPeer.destroy(peer)
        }
    }

    private companion object {
        /** A cold JVM start plus one case generation; the demo suites' launch budget is 45s. */
        const val CHILD_TIMEOUT_SECONDS: Long = 60
    }
}
