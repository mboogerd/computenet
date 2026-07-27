package civictech.cell.proxy

import civictech.cell.PendingReBaseline
import civictech.cell.ReBaselineNotice
import civictech.cell.ReplayScope
import civictech.cell.TagFrontier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T04 finding 7 (coroutine context loss): [PendingReBaseline] and
 * [ReplayScope] were bare `ThreadLocal`s with no coroutine context element,
 * unlike [civictech.cell.CurrentContext] which already had one. A
 * `SuspendingCell` resuming after a genuine suspension point on a *different*
 * worker thread — e.g. mid-handler during a RESTART re-baseline or a journal
 * replay — would see whatever that worker's own thread-local happened to
 * hold (usually nothing), losing the notice/scope and stamping a live wave
 * instead of a baseline.
 *
 * `Invocation.invokeSuspending` now composes [civictech.cell.CurrentContext],
 * [PendingReBaseline], and [ReplayScope] as coroutine context elements around
 * the suspend call. This pins that composition directly: a real suspension
 * that hops from one dedicated thread to a *different* dedicated thread must
 * still see both values, snapshotted from whatever was ambient the instant
 * this delivery began.
 */
class InvokeSuspendingContextCarryTest {

    interface SuspendingTarget {
        suspend fun run()
    }

    @Test
    fun `PendingReBaseline and ReplayScope survive a suspension that resumes on a different worker thread`() {
        val callerThread = Executors.newSingleThreadExecutor { Thread(it, "invoke-suspending-caller") }
        val resumeThread = Executors.newSingleThreadExecutor { Thread(it, "invoke-suspending-resume") }
        try {
            val notice = ReBaselineNotice(setOf(UUID.randomUUID()), supersede = true)
            val frontier = TagFrontier(mapOf(UUID.randomUUID() to 7L))

            val seenOnResumeThread = CompletableFuture<Triple<String, ReBaselineNotice?, TagFrontier?>>()
            val target = object : SuspendingTarget {
                override suspend fun run() {
                    // force a genuine suspension that resumes on a DIFFERENT,
                    // dedicated worker thread — not merely "might" (Dispatchers.Default
                    // could coincidentally resume on the same thread and mask a bug).
                    withContext(resumeThread.asCoroutineDispatcher()) {
                        seenOnResumeThread.complete(
                            Triple(Thread.currentThread().name, PendingReBaseline.get(), ReplayScope.get())
                        )
                    }
                }
            }
            val method = SuspendingTarget::class.java.methods.single { it.name == "run" }
            val invocation = Invocation.of(method, emptyArray())

            // .with() sets the ThreadLocal on the CALLER thread only — exactly
            // like the real call chains (FanOutlet.reBaseline / HostDurability's
            // ReplayScope.with) that stage this ambient state before a delivery
            // begins. runBlocking (no explicit dispatcher) starts undispatched on
            // this same thread, so invokeSuspending's initial synchronous portion
            // — where it snapshots PendingReBaseline.get()/ReplayScope.get() into
            // the coroutine context — observes the values .with() just set.
            callerThread.submit {
                PendingReBaseline.with(notice) {
                    ReplayScope.with(frontier) {
                        runBlocking { invocation.invokeSuspending(target) }
                    }
                }
            }.get(5, TimeUnit.SECONDS)

            val (threadName, seenNotice, seenFrontier) = seenOnResumeThread.get(5, TimeUnit.SECONDS)
            // sanity: genuinely a different thread (coroutines debug mode
            // suffixes the name with "@coroutine#N")
            threadName shouldStartWith "invoke-suspending-resume"
            seenNotice shouldBe notice
            seenFrontier shouldBe frontier
        } finally {
            callerThread.shutdown()
            resumeThread.shutdown()
        }
    }
}
