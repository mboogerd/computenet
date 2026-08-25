package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.security.MessageDigest

/**
 * The **one** definition of the BS-16 case, shared verbatim by both sides of the cross-JVM
 * comparison: `Bs16ReproducibilityTest` generates it in the test JVM, [Bs16FingerprintMain]
 * generates it in a freshly launched child JVM, and neither restates the seed or the config.
 *
 * The config deliberately exercises more than the minimum — several sources, a fan-in
 * vocabulary, more than one writer, a late-joiner barrier and two hosts — so the byte
 * comparison covers topology, placement, terminals (including the late one) and the whole
 * script, not just a two-node chain.
 */
object Bs16Case {

    /** The fixed case seed. Hardcoded here and nowhere else. */
    const val SEED: Long = 42L

    /** The fixed config. Hardcoded here and nowhere else. */
    val CONFIG: GeneratorConfig = GeneratorConfig(
        depthRange = 2..4,
        sourceCount = 3,
        vocabulary = listOf(
            CoreOperators.Ids.SET,
            CoreOperators.Ids.FILTER,
            CoreOperators.Ids.FLAT_MAP_SET,
            CoreOperators.Ids.UNION,
            CoreOperators.Ids.COUNT,
            CoreOperators.Ids.PRESENCE_COUNT,
        ),
        elementDomainSize = 8,
        scriptLength = 60,
        addRemoveRatio = 0.6,
        unobservedRemoveRatio = 0.3,
        terminalCount = 1,
        writerCount = 3,
        lateJoiner = true,
        hostCount = 2,
    )

    /**
     * Registers `CoreOperators` into the process-wide [OperatorCatalog] and generates the fixed
     * case. Registration is part of the fixture because the child JVM starts with an empty
     * catalog and the test JVM's registration must not be the thing that makes the two agree.
     */
    fun generate(): GeneratedCase {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        return CaseGenerator(CONFIG).generate(SEED)
    }

    /**
     * The BS-16 rendering: the Java-serialized [GeneratedCase]. `Serializable` throughout by
     * epic decision D3, so this is the case's own on-the-wire form rather than a fingerprint
     * invented for the test — it covers the lowered `GraphSpec`'s spawn factories and connect
     * steps, the topology's placement and terminals, every script step in order, and the remove
     * audit, with no chance of a field being silently left out of the comparison the way a
     * hand-written `toString` digest could.
     */
    fun serialize(case: GeneratedCase): ByteArray {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(case) }
        return bytes.toByteArray()
    }

    /** `sha256:<hex> len=<n>` — what a mismatch reports, since the bytes themselves are unreadable. */
    fun describe(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:${digest.joinToString("") { "%02x".format(it) }} len=${bytes.size}"
    }
}

/**
 * BS-16's child half (`ORA1 §GEN-01`): a `main` the test launches as a **separate JVM** via
 * `civictech.testkit.JvmPeer.launch`, which generates [Bs16Case] from scratch in a process that
 * shares nothing with the test JVM but the classpath, and writes the serialized case to the
 * file named by `args[0]`.
 *
 * A fresh process is the point. Two calls inside one JVM share class initialization, a warmed
 * `OperatorCatalog`, one heap layout and one set of identity hashes, so they cannot detect a
 * generator that depends on any of those. This one can — which is why the mutation check for
 * this task's determinism claim was run against a nondeterministic choice that two in-process
 * calls would also have caught, and this test independently.
 */
object Bs16FingerprintMain {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "usage: Bs16FingerprintMain <output-file>" }
        val bytes = Bs16Case.serialize(Bs16Case.generate())
        File(args[0]).writeBytes(bytes)
        // Read back by the parent only on failure; harmless otherwise.
        println("bs16 ${Bs16Case.describe(bytes)}")
    }
}
