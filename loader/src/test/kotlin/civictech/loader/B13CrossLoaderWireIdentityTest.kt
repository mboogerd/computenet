package civictech.loader

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import civictech.nature.ModuleRegistration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import java.util.UUID

/**
 * Bug computenet-bb5b — **cross-loader wire identity**: the residual named, and
 * deliberately left uncovered, by [B13ModuleWireSerializersTest]'s KDoc and by
 * bug computenet-06cn.
 *
 * ## The question
 *
 * `WireCodec` is a Kotlin `object` holding a `@Volatile private var json`
 * (kernel `civictech/cell/wire/WireCodec.kt`), so its serializer registry is
 * **process-global**. Every existing B13 test bridges two endpoints inside one
 * JVM, so encode and decode share that one registry and both sides see a
 * module's contributed table *by construction*, however the bytes travelled.
 *
 * Across two processes that is no longer true. Each has its own `WireCodec`,
 * each opens the jar in its own [ModuleClassLoader], and each must
 * **independently** contribute the module's [WireSerializers] before the type
 * decodes. Nothing pinned that.
 *
 * ## Why this is the honest instrument, and not a two-JVM rig
 *
 * The bead's original shape was a jar-loaded two-JVM test. Two processes are
 * a *means* of obtaining the two things the question is actually about — two
 * independent codec registries and two independent [ModuleClassLoader]s — and
 * both are obtainable here, in one JVM, without inventing a `:loader` consumer:
 *
 * - **Two registries.** `WireCodec.decode` is a pure function of (bytes,
 *   currently-live contributions): `decode` reads the `@Volatile json` and
 *   nothing else. The receiving process's registry state is therefore fully
 *   reproduced by *withdrawing* the sending side's contribution and
 *   contributing the receiver's — the codec cannot tell that from a second
 *   process's freshly-built `Json`, because there is nothing else in it to
 *   tell with. (A second JVM's process-start `ServiceLoader` scan finds the
 *   same baseline: the module's jar is not on the application classpath in
 *   either process, which is the whole reason `contribute` exists.)
 * - **Two loaders.** [ModuleClassLoader.open] over the same jar twice yields
 *   two genuinely distinct `Class` objects for one FQN — asserted below, not
 *   assumed.
 * - **The socket.** Already settled: bug computenet-06cn's byte-boundary
 *   argument (quoted in [B13ModuleWireSerializersTest]'s KDoc) — `WsTransport`
 *   encodes nothing itself and carries an opaque `ByteArray`, so it cannot
 *   discriminate on the provenance of the type those bytes came from. This
 *   test hands the *same bytes* to the second registry that a socket would
 *   have delivered.
 *
 * ## Honest limits — what this does NOT prove
 *
 * 1. **No second `ModuleRegistration`.** The second endpoint below opens the
 *    jar and discovers its [WireSerializers] exactly as `ModuleLoader.load`
 *    does (`ServiceLoader` over the module loader, filtered to providers the
 *    jar itself defines — `ModuleLoader.providersDefinedBy`), but it does not
 *    re-run `ModuleRegistration.register`, which is process-global here and
 *    cannot be duplicated in one JVM. Nothing wire-relevant is lost: fixture
 *    (h) carries no `@Contract` and no `Cell`, so its registration contributes
 *    nothing that encode or decode consults.
 * 2. **No real socket hop, and no read-loop failure handling.** What
 *    `WsTransport`'s ingress loop *does* when a decode throws — log, drop,
 *    or drop the connection — is a separate, socket-side question this test
 *    does not reach. It is filed as its own item — bug computenet-mvu9 —
 *    rather than folded in here.
 *
 * ## The decision this test records
 *
 * **Per-process contribution is the host's responsibility, not the loader's.**
 * `ModuleLoader` hands its host the discovered [WireSerializers] through the
 * `onWireSerializers` seam and does nothing further; a host that runs a second
 * process must load the module and contribute there too. `:loader` cannot make
 * that true for a peer it has no knowledge of, and deliberately does not try —
 * it has no transport dependency at all
 * ([ModuleDependencyTest]). What the runtime owes is that the failure be
 * **loud** when a host has not done so, which is the first assertion below.
 */
class B13CrossLoaderWireIdentityTest {

    private companion object {
        const val DELTA_FQN = "civictech.loader.fixture.wiredelta.WireDeltaFixtureDelta"

        /** Fixed, so re-encoding the same logical value is byte-comparable. */
        val TAG_SOURCE: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        val CELL: CellRef = CellRef(UUID.fromString("00000000-0000-0000-0000-0000000005bb"))
        const val PAYLOAD = "cross-loader-payload"
        const val COUNTER = 41L
    }

    /** [Propagate] is itself `@Contract` (kernel-wide, any `T`). */
    private val propagateMethod = Propagate::class.java.getMethod("propagate", Any::class.java)

    private fun invocationOf(value: Any?): HostedPortInvocation = HostedPortInvocation(
        cellRef = CELL,
        portName = "inlet",
        type = HostedPortInvocation.Type.PORT_API,
        invocation = Invocation.of(propagateMethod, arrayOf(value), null),
    )

    private fun deltaInstance(loader: ClassLoader): Any =
        loader.loadClass(DELTA_FQN)
            .getDeclaredConstructor(String::class.java, Timestamp::class.java)
            .newInstance(PAYLOAD, Timestamp(TAG_SOURCE, COUNTER))

    /**
     * What the *second process* would discover: `ServiceLoader` over the module's
     * own loader, keeping only providers that loader itself defined — the same
     * filter `ModuleLoader.providersDefinedBy` applies, so a provider inherited
     * from the application classpath cannot stand in for the jar's own.
     */
    private fun wireSerializersDefinedBy(loader: ModuleClassLoader): List<WireSerializers> =
        ServiceLoader.load(WireSerializers::class.java, loader)
            .stream()
            .filter { it.type().classLoader === loader }
            .map { it.get() }
            .toList()

    @Test
    fun `bytes from one loader's contribution decode only under an independent second contribution, into that loader's own class`() {
        val jar = FixtureJars.wireDelta

        // --- Endpoint A: the real load path. Its onWireSerializers seam folds the
        // jar's table into this process's WireCodec, exactly as B13 does.
        var contributedByA: List<WireSerializers> = emptyList()
        val loaderA = ModuleLoader(
            acceptedLocations = setOf(jar.toPath().toAbsolutePath().normalize().parent),
            onWireSerializers = { _, serializers ->
                contributedByA = serializers
                serializers.forEach(WireCodec::contribute)
            },
        )
        val handleA = loaderA.load(jar)

        var contributedByB: List<WireSerializers> = emptyList()
        var classLoaderB: ModuleClassLoader? = null
        try {
            withClue("endpoint A discovered no WireSerializers, so everything below would be vacuous") {
                contributedByA shouldHaveSize 1
            }

            val classA = handleA.classLoader.loadClass(DELTA_FQN)
            val valueA = deltaInstance(handleA.classLoader)

            // The bytes the socket would carry. Encoded while, and only while, A's
            // contribution is live.
            val bytes = WireCodec.encode(invocationOf(valueA))

            // ================================================================
            // 1. THE LOUD-FAILURE ARM — "one side has not contributed".
            //    Withdrawing A's contribution leaves the codec in exactly the
            //    state a second process's codec is in before that process has
            //    loaded the module: baseline only. The bytes must be REFUSED,
            //    loudly, not silently dropped or half-decoded.
            // ================================================================
            contributedByA.forEach(WireCodec::withdraw)
            withClue("ARM-1 (loud failure): a codec with no contribution live accepted a module type's bytes") {
                shouldThrow<SerializationException> { WireCodec.decode(bytes) }
            }

            // ================================================================
            // 2. The second endpoint contributes independently, from its OWN
            //    classloader over the same jar.
            // ================================================================
            val loaderB = ModuleClassLoader.open(jar)
            classLoaderB = loaderB
            val serializersB = wireSerializersDefinedBy(loaderB)
            withClue("endpoint B's own ServiceLoader scan found no provider defined by its loader") {
                serializersB shouldHaveSize 1
            }
            // Genuinely a *second* loader: distinct provider instance, and — the
            // premise of this whole item — a distinct Class for one FQN.
            serializersB.single() shouldNotBeSameInstanceAs contributedByA.single()
            val classB = loaderB.loadClass(DELTA_FQN)
            withClue("the two loaders collapsed onto one Class; the cross-loader premise is gone") {
                classB shouldNotBeSameInstanceAs classA
            }

            contributedByB = serializersB
            serializersB.forEach(WireCodec::contribute)

            // ================================================================
            // 3. Now the same bytes decode — into endpoint B's OWN class, not
            //    A's. Wire identity travels as the @SerialName in the bytes;
            //    Class identity does not travel at all.
            // ================================================================
            val decoded = WireCodec.decode(bytes).invocation.args.single()
            checkNotNull(decoded) { "decoded arg was null" }
            decoded.javaClass shouldBeSameInstanceAs classB
            decoded.javaClass.classLoader shouldBeSameInstanceAs loaderB

            // Not the same value object, and not `equals` to A's either: a data
            // class's generated equals is class-scoped, so this is the assertion
            // that the identity really did cross a loader boundary rather than
            // the same Class being reused.
            withClue("decoded value compared equal to endpoint A's instance, so no loader boundary was crossed") {
                (decoded == valueA) shouldBe false
            }
            // ...while the CONTENT is intact, field for field, tag included.
            classB.getMethod("getPayload").invoke(decoded) shouldBe PAYLOAD
            classB.getMethod("getTag").invoke(decoded) shouldBe Timestamp(TAG_SOURCE, COUNTER)

            // And the round trip closes: B re-encodes its own instance to the
            // very bytes A produced. The wire form is loader-independent.
            WireCodec.encode(invocationOf(decoded)) shouldBe bytes

            // ================================================================
            // 4. The converse, which is what makes 3 a cross-loader claim and
            //    not a tautology: under B's contribution alone, A's instance is
            //    UNENCODABLE. The codec's polymorphic registry is keyed by the
            //    runtime Class, and classA is not in it.
            // ================================================================
            withClue("ARM-4 (converse): endpoint A's Class was encodable under endpoint B's contribution alone") {
                shouldThrow<SerializationException> { WireCodec.encode(invocationOf(valueA)) }
            }

            // Sanity: A's and B's values are not merely unequal by accident —
            // they differ only in the loader that defined their class.
            valueA.toString() shouldBe decoded.toString()
            valueA shouldNotBe decoded
        } finally {
            contributedByB.forEach(WireCodec::withdraw)
            contributedByA.forEach(WireCodec::withdraw)
            classLoaderB?.close()
            ModuleRegistration.unregister(handleA.id)
            handleA.classLoader.close()
        }
    }
}
