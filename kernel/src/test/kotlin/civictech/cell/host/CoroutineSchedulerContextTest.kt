package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.ReBaselineNotice
import civictech.cell.SuspendingCell
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T06 §C: the real [CoroutineScheduler] against T04 finding 7 (coroutine
 * context loss) and the `drainingThread` staleness case in T04 finding 7.2.
 *
 * §C1 constructs its `HostedPortInvocation`s directly rather than round-
 * tripping through a real `Journal`/`WireCodec` — the *mechanism* under test
 * is `ManagedHost.deliver`'s handling of a suspend-fun handler, which
 * `Invocation.invokeSuspending` dispatches by reflection (no `@Contract`/wire
 * involvement at all); wiring a full journal replay through a genuinely
 * suspend-fun `@Contract` method would additionally be exercising untested
 * KSP territory (T09's, not T04/T05's). §C1a still proves the real
 * end-to-end property: `HostDurability.recoverFrom`'s captured
 * `replayFrontier` (T04 finding 7, extended in this ticket once this suite
 * exposed the gap — see the T06 report) survives to a spontaneous emission
 * even after the handler suspends and resumes on a different OS thread.
 */
class CoroutineSchedulerContextTest {

    interface TriggerApi {
        suspend fun trigger()
    }

    /**
     * T06 §C1a — a spontaneous emission (no incoming wave) from a handler
     * that suspends and resumes on a genuinely different thread must still
     * carry the replay baseline the invocation arrived with.
     */
    @Test
    fun `C1a - a spontaneous emission after suspending mid-replay carries the replay baseline, not a live wave`() {
        val resumeOn = Executors.newSingleThreadExecutor { Thread(it, "t06-c1a-resume") }
        try {
            val host = ManagedHost(scheduler = CoroutineScheduler("t06-c1a"))

            val cell = object : Cell, SuspendingCell {
                override val ref = CellRef(UUID.randomUUID())
                val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

                @Suppress("UNCHECKED_CAST")
                val inlet = registerPort("inlet", FanInlet(TriggerApi::class.java))

                init {
                    inlet.serve(object : TriggerApi {
                        override suspend fun trigger() {
                            // genuine suspension resuming on a DIFFERENT, dedicated thread
                            withContext(resumeOn.asCoroutineDispatcher()) {}
                            // spontaneous: CurrentContext is already null here (the
                            // triggering invocation itself carries no context)
                            outlet.call.propagate("emitted")
                        }
                    })
                }
            }
            host.managementInlet.call.spawn(cell)

            val observedContext = CompletableFuture<MessageContext?>()
            cell.outlet.subscribe(
                Use.fixed(object : Propagate<String> {
                    override fun propagate(value: String) { observedContext.complete(CurrentContext.get()) }
                }, PortRef.generate()),
            )

            val frontier = TagFrontier(mapOf(UUID.randomUUID() to 3L))
            val invocation = HostedPortInvocation(
                cell.ref, "inlet", HostedPortInvocation.Type.PORT_API,
                Invocation("trigger", emptyList(), emptyList()),
                replayFrontier = frontier,
            )
            host.enqueueHostedInvocation(invocation)

            val ctx = observedContext.get(15, TimeUnit.SECONDS)
            requireNotNull(ctx) { "spontaneous emission never arrived" }
            ctx.baseline shouldBe frontier
        } finally {
            resumeOn.shutdown()
        }
    }

    interface RestartSink {
        suspend fun accept(value: String)
    }

    /**
     * T06 §C1b — `CurrentContext.withSuspending` (pre-existing; T04's
     * `PendingReBaseline`/`ReplayScope` fix mirrors this same pattern) must
     * carry a `MessageContext.reBaseline` notice across a genuine
     * suspension/cross-thread resume — the shape a RESTART re-baseline
     * delivery to a downstream `SuspendingCell` takes.
     */
    @Test
    fun `C1b - a suspending handler processing a RESTART re-baseline still sees the notice after resuming on a different thread`() {
        val resumeOn = Executors.newSingleThreadExecutor { Thread(it, "t06-c1b-resume") }
        try {
            val host = ManagedHost(scheduler = CoroutineScheduler("t06-c1b"))
            val observedNotice = CompletableFuture<ReBaselineNotice?>()
            val observedThread = CompletableFuture<String>()

            val cell = object : Cell, SuspendingCell {
                override val ref = CellRef(UUID.randomUUID())

                @Suppress("UNCHECKED_CAST")
                val inlet = registerPort("inlet", FanInlet(RestartSink::class.java))

                init {
                    inlet.serve(object : RestartSink {
                        override suspend fun accept(value: String) {
                            withContext(resumeOn.asCoroutineDispatcher()) {
                                observedThread.complete(Thread.currentThread().name)
                                observedNotice.complete(CurrentContext.get()?.reBaseline)
                            }
                        }
                    })
                }
            }
            host.managementInlet.call.spawn(cell)

            val notice = ReBaselineNotice(setOf(UUID.randomUUID()), supersede = true)
            val ctx = MessageContext(Timestamp(UUID.randomUUID(), 1L), PortRef.generate(), reBaseline = notice)
            val invocation = HostedPortInvocation(
                cell.ref, "inlet", HostedPortInvocation.Type.PORT_API,
                Invocation("accept", listOf("java.lang.String"), listOf("restarted"), context = ctx),
            )
            host.enqueueHostedInvocation(invocation)

            observedThread.get(15, TimeUnit.SECONDS).let { it.startsWith("t06-c1b-resume") shouldBe true }
            observedNotice.get(15, TimeUnit.SECONDS) shouldBe notice
        } finally {
            resumeOn.shutdown()
        }
    }

    /**
     * T06 §C2 — the self-await deadlock guard (`HostScheduler.await`) must
     * fire from the ACTUAL resume thread, not a stale pre-suspension one
     * (T04 finding 7.2, `CoroutineScheduler.DrainingThreadElement`): a
     * suspending handler that suspends, resumes on a different thread, and
     * THEN calls back into its own host must still fail fast with
     * `IllegalStateException`, never a 5s timeout.
     */
    @Test
    fun `C2 - self-await after a suspension point fails fast instead of timing out`() {
        val resumeOn = Executors.newSingleThreadExecutor { Thread(it, "t06-c2-resume") }
        try {
            lateinit var host: ManagedHost
            val selfAwaitResult = CompletableFuture<Throwable?>()

            val cell = object : Cell, SuspendingCell {
                override val ref = CellRef(UUID.randomUUID())

                @Suppress("UNCHECKED_CAST")
                val inlet = registerPort("inlet", FanInlet(TriggerApi::class.java))

                init {
                    inlet.serve(object : TriggerApi {
                        override suspend fun trigger() {
                            // the self-await call happens INSIDE the withContext
                            // block — genuinely executing on resumeOn's thread,
                            // not back on whichever thread started draining this
                            // task. enqueueAwaiting's await() must recognize
                            // THIS as the host's own execution context
                            // (drainingThread tracks the actual resume thread,
                            // not just task entry) and fail fast, not block 5s.
                            withContext(resumeOn.asCoroutineDispatcher()) {
                                val failure = runCatching {
                                    host.managementInlet.call.lookup(ref, Any::class.java)
                                }.exceptionOrNull()
                                selfAwaitResult.complete(failure)
                            }
                        }
                    })
                }
            }
            host = ManagedHost(scheduler = CoroutineScheduler("t06-c2"))
            host.managementInlet.call.spawn(cell)

            val invocation = HostedPortInvocation(
                cell.ref, "inlet", HostedPortInvocation.Type.PORT_API,
                Invocation("trigger", emptyList(), emptyList()),
            )
            val started = System.currentTimeMillis()
            host.enqueueHostedInvocation(invocation)

            val failure = selfAwaitResult.get(4, TimeUnit.SECONDS) // well under the 5s await timeout
            val elapsed = System.currentTimeMillis() - started
            (elapsed < 4000).let { it shouldBe true } // fast failure, not a 5s block

            requireNotNull(failure) { "expected the self-await guard to throw" }
            failure.shouldBeInstanceOf<IllegalStateException>()
        } finally {
            resumeOn.shutdown()
        }
    }
}
