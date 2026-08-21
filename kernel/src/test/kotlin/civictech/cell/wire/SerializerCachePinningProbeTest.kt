package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.reflect.KClass

/**
 * A module's own `@Serializable` delta type, defined at top level so a
 * disposable child loader can define a **second, distinct** `Class` object for
 * it from the same bytecode. Public on purpose: the child copy lives in a
 * different runtime package (same name, different loader), so anything the
 * probe reaches reflectively — the constructor, the generated `Companion`,
 * `Companion.serializer()` — must be public to be reachable across the loader
 * boundary.
 */
@Serializable
@SerialName("SerializerCachePinningProbeDelta")
data class SerializerCachePinningProbeDelta(val payload: String, val revision: Long)

/**
 * JAR1 epic `computenet-051` risk **R1**, carried by feature
 * `computenet-051.6`: *does kotlinx-serialization's serializer cache pin a
 * module's classes after the wire round-trip?* The answer feeds feature
 * `computenet-051.4`'s B10 (loader reachability after unload), so it has to be
 * an **observation of this JVM**, not a reading of the library's source.
 *
 * ## Why a disposable loader is required for the question to mean anything
 *
 * A class on the test classpath is loaded by the application loader and can
 * *never* become unreachable, so a `WeakReference` to it would never clear no
 * matter what the serializer cache does — the probe would answer "pinned" for
 * a reason that has nothing to do with kotlinx-serialization. The probe
 * therefore defines a **second** `Class` object for
 * [SerializerCachePinningProbeDelta] through a child-first loader whose whole
 * reachability is under the test's control.
 *
 * ## Divergence from the bead's prescribed mechanism, and why
 *
 * The bead's `Implement` prose prescribes a *child-first `URLClassLoader` over
 * the test classpath*. This uses a `ClassLoader` subclass that reads the class
 * bytes from the parent's own resources and `defineClass`es them, delegating
 * every other name to the parent. It is child-first for exactly the probe
 * nest and nothing else, which is what the prose is after, and it does not
 * depend on `java.class.path` faithfully describing the Gradle test JVM's
 * classpath (Gradle may hand the worker a manifest-only jar, in which case a
 * `URLClassLoader` built from that property finds nothing and the probe fails
 * for an unrelated reason). The acceptance criterion asks for "a disposable
 * classloader", which this is.
 *
 * The nest — `…ProbeDelta`, its `Companion`, and the plugin-generated
 * `…ProbeDelta$$serializer` — is redefined **together**. Redefining only the
 * outer class, as a literal reading of "and only it" would have it, is not a
 * runnable configuration: the parent's `Companion.serializer()` returns the
 * parent's `$$serializer`, whose `serialize` casts its argument to the
 * *parent* class, so the round-trip would die with a `ClassCastException`
 * before reaching the question. Nothing outside that nest is redefined.
 *
 * ## What this test asserts, and what it only records
 *
 * The probe must not be written so that it can only pass on one answer. What
 * is **asserted** is that the *mechanism* worked:
 *
 *  - the child-loaded `Class` is reference-distinct from the test-classpath
 *    one, has the same binary name, and is owned by the disposable loader;
 *  - the wire round-trip through [WireCodec] really carried an instance of the
 *    **child-loaded** type (decoded value's `javaClass` is identical to the
 *    child `Class`), which is what makes a later cleared `WeakReference`
 *    meaningful rather than vacuous;
 *  - the contribution was withdrawn and every strong reference the test held
 *    was dropped before the GC loop began.
 *
 * What is only **recorded** — printed to the run output and copied verbatim
 * onto epic `computenet-051` — is the pinning outcome: whether the
 * `WeakReference`s to the child loader and the child `Class` cleared within a
 * bounded number of GC attempts. "Pinned" is a finding to report, not a test
 * failure; the epic's B10 honesty rule says to report the reduced claim rather
 * than loosen or delete the probe.
 *
 * ## What a "cleared" result does and does not license B10 to assume
 *
 * A cleared `WeakReference` shows that **nothing kotlinx-serialization touched
 * during contribute → round-trip → withdraw kept the module's class strongly
 * reachable from a process-wide root**. That is the claim, and it is the one
 * B10 needs.
 *
 * It deliberately does **not** distinguish "the serializer cache held the
 * class weakly (e.g. `ClassValue`-attached, which is unloaded with the class
 * itself)" from "the cache was never consulted for this class at all" —
 * both present identically as a cleared reference, and telling them apart
 * would need a heap walk this probe does not perform. The probe reduces the
 * second possibility by driving the reflective `serializer(Type)` lookup as
 * well as the generated companion accessor, but it cannot exclude it. B10
 * should read this as *"the serializer cache is not a reason unload fails"*,
 * not as *"the serializer cache is proven `ClassValue`-backed"*.
 *
 * The result is also **per-JVM and per-GC**: it is an observation of the JVM
 * and collector printed in the run output, not a guarantee about every JVM.
 */
class SerializerCachePinningProbeTest {

    /** Bounded, never an unbounded spin — the pattern B10 prescribes. */
    private val gcAttemptBound = 60

    private val probeNestPrefix = SerializerCachePinningProbeDelta::class.java.name

    /**
     * Child-first for the probe nest only; everything else — `kotlin.*`,
     * `kotlinx.serialization.*`, `civictech.cell.*` — delegates to [parent],
     * so the child-defined class implements the *same* `KSerializer`
     * interfaces the codec knows about.
     */
    private class ProbeNestLoader(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith(PREFIX)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                val path = name.replace('.', '/') + ".class"
                val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                    ?: throw ClassNotFoundException("no bytecode for $name at $path")
                val defined = defineClass(name, bytes, 0, bytes.size)
                if (resolve) resolveClass(defined)
                return defined
            }
        }

        companion object {
            const val PREFIX = "civictech.cell.wire.SerializerCachePinningProbeDelta"
        }
    }

    /** What a dynamically loaded module contributes for its own delta type. */
    private class ProbeSerializers(child: KClass<*>, serializer: KSerializer<*>) : WireSerializers {
        @Suppress("UNCHECKED_CAST")
        override val module: SerializersModule = SerializersModule {
            polymorphic(Any::class) {
                subclass(child as KClass<Any>, serializer as KSerializer<Any>)
            }
        }
    }

    /**
     * Everything the probe observes about a round-trip that has already
     * finished and whose strong references are gone. Only weak references and
     * plain values survive the call that produces it — deliberately, so no
     * local variable of the probe body can keep the child loader alive.
     */
    private class ProbeOutcome(
        val loaderRef: WeakReference<ClassLoader>,
        val classRef: WeakReference<Class<*>>,
        val serializerClassName: String,
        val decodedTypeMatchedChild: Boolean,
        val decodedRendering: String,
        val childBinaryName: String,
        val childWasDistinct: Boolean,
        val childLoaderOwnedTheClass: Boolean,
    )

    private val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)

    private fun frame(payload: Any?) = HostedPortInvocation(
        cellRef = CellRef(UUID.randomUUID()),
        portName = "inlet",
        type = HostedPortInvocation.Type.PORT_API,
        invocation = Invocation.of(propagate, arrayOf(payload), null),
    )

    /**
     * Load the probe delta through a disposable loader, register its
     * serializer with [WireCodec], round-trip one frame carrying an instance
     * of it, then withdraw and drop everything.
     *
     * All strong references live in this frame and die when it returns — that
     * is the reason the round-trip is a separate method rather than inlined
     * into the test body, where the locals would remain live for the duration
     * of the GC loop.
     */
    private fun roundTripThroughDisposableLoader(): ProbeOutcome {
        val appLoadedClass: Class<*> = SerializerCachePinningProbeDelta::class.java
        val loader = ProbeNestLoader(appLoadedClass.classLoader)
        val childClass: Class<*> = loader.loadClass(probeNestPrefix)

        // Reflective construction: the child class is a *different* type from
        // the compiled-against one, so it can only be built this way.
        val instance = childClass
            .getDeclaredConstructor(String::class.java, java.lang.Long.TYPE)
            .newInstance("module-payload", 41L)

        // kotlinx generates `.serializer()` on the companion; reach it through
        // the CHILD-loaded companion so the serializer belongs to the child.
        val companion = childClass.getField("Companion").get(null)
        val childSerializer = companion.javaClass.getMethod("serializer").invoke(companion) as KSerializer<*>

        // Also drive the *reflective* lookup path, which is what populates
        // kotlinx-serialization's process-wide serializer cache
        // (`SerializersCache`); the generated companion accessor above need not
        // touch it, and a cache that was never populated could not pin anything
        // — that would answer a different question than the one R1 asks.
        val childJavaType: java.lang.reflect.Type = childClass
        val cachedSerializer = kotlinx.serialization.serializer(childJavaType)

        val contribution = ProbeSerializers(childClass.kotlin, childSerializer)
        WireCodec.contribute(contribution)
        val decodedTypeMatchedChild: Boolean
        val decodedRendering: String
        try {
            val decoded = WireCodec.decode(WireCodec.encode(frame(instance))).invocation.args.single()!!
            decodedTypeMatchedChild = decoded.javaClass === childClass
            decodedRendering = decoded.toString()
            check(decoded == instance) { "round-trip did not reproduce the value: $decoded vs $instance" }
        } finally {
            WireCodec.withdraw(contribution)
        }

        val outcome = ProbeOutcome(
            loaderRef = WeakReference<ClassLoader>(loader),
            classRef = WeakReference<Class<*>>(childClass),
            serializerClassName = cachedSerializer.javaClass.name,
            decodedTypeMatchedChild = decodedTypeMatchedChild,
            decodedRendering = decodedRendering,
            childBinaryName = childClass.name,
            childWasDistinct = childClass !== appLoadedClass,
            childLoaderOwnedTheClass = childClass.classLoader === loader,
        )
        println(
            """
            |[R1 probe] app-loaded  class: ${System.identityHashCode(appLoadedClass)} loader=${appLoadedClass.classLoader}
            |[R1 probe] child-loaded class: ${System.identityHashCode(childClass)} loader=${childClass.classLoader}
            |[R1 probe] companion serializer: ${childSerializer.javaClass.name}@${System.identityHashCode(childSerializer)}
            |[R1 probe] reflective serializer(Type) -> ${cachedSerializer.javaClass.name}@${System.identityHashCode(cachedSerializer)}
            |[R1 probe] decoded instance: $decodedRendering (javaClass === child class: $decodedTypeMatchedChild)
            """.trimMargin(),
        )
        return outcome
    }

    /**
     * Bounded GC solicitation. Returns the attempt on which both references
     * were observed clear, or `null` if they were still set after
     * [gcAttemptBound] attempts. `System.gc()` is a request, not a command —
     * hence the bound and the honest `null`.
     */
    private fun awaitCleared(outcome: ProbeOutcome): Int? {
        for (attempt in 1..gcAttemptBound) {
            if (outcome.loaderRef.get() == null && outcome.classRef.get() == null) return attempt
            System.gc()
            System.runFinalization()
            // A little allocation pressure: class unloading rides on a full
            // collection, and an idle heap can make the collector decline.
            var ballast: Array<ByteArray?>? = arrayOfNulls(32)
            for (i in ballast!!.indices) ballast[i] = ByteArray(1 shl 16)
            ballast = null
            Thread.sleep(20)
        }
        return if (outcome.loaderRef.get() == null && outcome.classRef.get() == null) gcAttemptBound else null
    }

    @Test
    fun `serializer cache pinning after a wire round-trip over a disposable classloader`() {
        val outcome = roundTripThroughDisposableLoader()

        // --- the MECHANISM: asserted, because a probe that did not actually
        // exercise a disposable loader would answer "cleared" vacuously.
        outcome.childWasDistinct shouldBe true
        outcome.childBinaryName shouldBe SerializerCachePinningProbeDelta::class.java.name
        outcome.childLoaderOwnedTheClass shouldBe true
        outcome.decodedTypeMatchedChild shouldBe true
        // the round-trip carried a real value, not an empty/absent payload
        outcome.decodedRendering shouldNotBe ""
        // the reflective lookup that populates the process-wide cache resolved
        // a plugin-generated serializer, not a fallback
        outcome.serializerClassName.startsWith(SerializerCachePinningProbeDelta::class.java.name) shouldBe true
        // and the contribution really is gone from the codec
        WireCodec.decode(WireCodec.encode(frame("kernel payload"))).invocation.args shouldBe listOf("kernel payload")

        // --- the ANSWER: recorded either way, never asserted.
        val clearedOnAttempt = awaitCleared(outcome)
        val gcs = ManagementFactory.getGarbageCollectorMXBeans().joinToString(", ") { it.name }
        val verdict = if (clearedOnAttempt != null) {
            "CLEARED on GC attempt $clearedOnAttempt of $gcAttemptBound — the serializer cache does NOT pin " +
                "the module's classes after contribute/round-trip/withdraw"
        } else {
            "PINNED — still strongly reachable after $gcAttemptBound GC attempts " +
                "(loaderRef=${outcome.loaderRef.get() != null}, classRef=${outcome.classRef.get() != null})"
        }
        println(
            """
            |[R1 probe] ===== observation (epic computenet-051 risk R1) =====
            |[R1 probe] outcome            : $verdict
            |[R1 probe] retry bound        : $gcAttemptBound attempts, System.gc() + runFinalization + 2MiB ballast + 20ms per attempt
            |[R1 probe] JVM                : ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")} (java ${System.getProperty("java.version")}, ${System.getProperty("java.vendor")})
            |[R1 probe] GC                 : $gcs
            |[R1 probe] kotlinx-serialization: 1.9.0 (see gradle/libs.versions.toml)
            |[R1 probe] what was cached    : the child-loaded delta's plugin-generated serializer, reached BOTH via the
            |[R1 probe]                      child companion's .serializer() and via the reflective serializer(Type)
            |[R1 probe]                      lookup that populates kotlinx-serialization's process-wide SerializersCache
            |[R1 probe] caveat            : a cleared reference shows no PROCESS-WIDE STRONG retention survived withdraw; it
            |[R1 probe]                      does NOT distinguish "cache held it weakly (ClassValue-attached)" from "cache was
            |[R1 probe]                      never consulted" — both read as cleared, and no heap walk was performed.
            |[R1 probe] ==========================================================
            """.trimMargin(),
        )

        // The verdict text must always name one of the two arms — this is the
        // only assertion about the answer, and it holds for either arm.
        (verdict.contains("CLEARED") || verdict.contains("PINNED")) shouldBe true

        // Reference-identity sanity that survives whichever arm was taken: the
        // app-loaded class is still here — it can never be unloaded, which is
        // exactly why the probe needed the disposable loader at all.
        SerializerCachePinningProbeDelta("x", 1L).javaClass.classLoader shouldBeSameInstanceAs
            javaClass.classLoader
    }
}
