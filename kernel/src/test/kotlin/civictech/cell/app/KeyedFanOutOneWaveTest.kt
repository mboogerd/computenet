package civictech.cell.app

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.MapCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.observe.observeAligned
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * KAGG-R-05 probe (epic `computenet-t6b.1`, feature `computenet-qi2tz`): one
 * ingress `SetDelta` fanned out to two keyed cells — a `MapCell`-shaped view
 * and a `KeyedSetCell`-shaped view, the SNB person/edge shape the epic names
 * — driven to quiescence on a [SimulationController], observed through
 * [civictech.cell.observe.AlignedObserve] (`[22-OBS-01]`/`[22-OBS-02]`) for
 * whether both cells' state changes land at ONE wave position.
 *
 * **Why a distributor cell, not two declared links.** `MapCell`'s and
 * `KeyedSetCell`'s inlets are `Use`-typed (`MapOps`/`KeyedSetOps`, F-3) — the
 * call-style shape every application driver in this repo writes through, not
 * a `Subscribe<Propagate<D>>` a graph link can target. So the fan-out has to
 * be a cell that receives the one ingress delta and forwards two writes
 * itself, exactly as an app-level ingress (`SocialGraph`, SOC1) would.
 *
 * **Why the forwarded writes are direct calls, not a second hop through
 * [ManagedHost.lookup].** The distributor forwards through the target cells'
 * own `inlet.call` directly — a plain synchronous Kotlin call on the same
 * thread as this distributor's own inbound delivery, the same "both arms
 * fused" shape `AlignedObserveTest` documents as safe (its invariant run only
 * reroutes the non-absorbing arm through the host queue; fusing both is what
 * it calls safe), rather than a second `host.lookup(...).inlet.call` hop
 * (`HostedCellProxy.apiInvocation`, re-enqueued on the host's scheduler).
 * Each direct call runs nested inside the [civictech.cell.port.FanOutlet]
 * delivery wrapper that installed this distributor's own
 * [civictech.cell.CurrentContext] (G-4: "outlets stamp... the incoming
 * timestamp... when reactive"), so both forwarded writes carry the SAME
 * `MessageContext.timestamp` as the triggering ingress delta, not a
 * freshly-minted one.
 *
 * **A harness pitfall this test does not repeat.** A first version of this
 * test read `sink.current()` synchronously the instant `controller.runToIdle()`
 * returned and was flaky under Gradle's parallel `:kernel:test` forks —
 * roughly 1 in 5 combined `--tests` runs (mixed with sibling classes; never
 * alone) read a stale composite missing the second of two writes, on BOTH the
 * queued-hop and the direct-call variant alike. Root cause: `AlignedCompositeCell`
 * dispatches composites off the deterministic simulation thread, on its own
 * private single-thread `ExecutorService` (`AlignedObserve.kt`'s
 * `dispatcher`/`draining` fields) — `runToIdle()` completing only guarantees
 * the simulation itself quiesced, not that the sink's own async delivery has
 * caught up. `AlignedObserveTest` already works around exactly this with
 * `awaitUntil`; this test does the same below, and is stable once it does
 * (10/10 runs of the same combined `--tests` invocation the synchronous read
 * flaked on). Not a `[KAGG-R-05]` divergence — a test-harness timing gap, not
 * an alignment failure: `awaitUntil` demonstrates the composite DOES arrive,
 * pairing both arms, for every wave.
 */
class KeyedFanOutOneWaveTest {

    /** One SNB-shaped ingress record (`SnbPipeline`'s person/edge grain, informally). */
    data class PersonEvent(val personId: Long, val city: Long, val friend: Long) : Serializable

    private interface PersonEventSetInlet {
        val inlet: Use<SetOps<PersonEvent>>
    }

    /**
     * Translates one ingress `SetDelta<PersonEvent>` into a `personCity`
     * (`MapCell`-shaped) write and a `personFriend` (`KeyedSetCell`-shaped)
     * write, both issued from inside this cell's own inbound delivery,
     * directly on the target cells' own `inlet.call` (see the class KDoc for
     * why this is a direct call rather than a second `host.lookup` hop).
     */
    private class FanOutDistributor(
        private val mapTarget: MapCell<Long, Long>,
        private val keyedTarget: KeyedSetCell<Long, Long>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<PersonEvent>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<PersonEvent>> {
                override fun propagate(value: SetDelta<PersonEvent>) {
                    val mapOps = mapTarget.inlet.call
                    val keyedOps = keyedTarget.inlet.call
                    for (event in value.adds.keys) {
                        mapOps.put(event.personId, event.city)
                        keyedOps.put(event.personId, event.friend)
                    }
                    for (event in value.dels.keys) {
                        mapOps.remove(event.personId)
                        keyedOps.remove(event.personId)
                    }
                }
            })
        }
    }

    @Test
    fun `one ingress SetDelta fanned to a MapCell view and a KeyedSetCell view lands at one wave position`() {
        val controller = SimulationController(seed = 1L)
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val source = SetCell<PersonEvent>()
        val personCity = MapCell<Long, Long>()
        val personFriend = KeyedSetCell<Long, Long>()
        mgmt.spawn(source)
        mgmt.spawn(personCity)
        mgmt.spawn(personFriend)

        val distributor = FanOutDistributor(personCity, personFriend)
        mgmt.spawn(distributor)
        mgmt.connect(source.ref, "outlet", distributor.ref, "inlet")

        val sink = host.observeAligned {
            map("personCity", personCity.ref)
            set("personFriend", personFriend.ref)
        }
        val composites = mutableListOf<Map<String, Any?>>()
        sink.onChange { composites += it }

        val ops = host.lookup<PersonEventSetInlet>(source.ref)!!.inlet.call
        ops.add(PersonEvent(personId = 1L, city = 10L, friend = 2L))
        controller.runToIdle()

        val settled = mapOf(
            "personCity" to mapOf(1L to 10L),
            "personFriend" to setOf(2L),
        )
        // The wave-aligned sink assembles from ONE per-source frontier of its
        // inputs ([22-OBS-01]): if the two forwarded writes had landed as two
        // separate wave positions, `current()` could only ever show one arm
        // updated while the other still read its pre-write value, and a
        // composite pairing them would never be published — exactly the F-5
        // flash `AlignedObserveTest` proves the point-consistent `CompositeSink`
        // control exhibits and this sink does not. See the class KDoc for why
        // this awaits delivery rather than reading `current()` synchronously.
        awaitUntil("first wave's composite delivered") { sink.current() == settled }
        composites.last() shouldBe settled

        // A second event at a different key: both arms accumulate, still at
        // one wave position each time, not just on the first write.
        ops.add(PersonEvent(personId = 2L, city = 11L, friend = 3L))
        controller.runToIdle()
        val settledAfterSecond = mapOf(
            "personCity" to mapOf(1L to 10L, 2L to 11L),
            "personFriend" to setOf(2L, 3L),
        )
        awaitUntil("second wave's composite delivered") { sink.current() == settledAfterSecond }
        composites.last() shouldBe settledAfterSecond
        // No torn republication ([22-OBS-01]): every recorded composite is
        // either the pre-write (possibly empty catch-up) state, the first
        // wave's settled state, or the second wave's — never a mix showing
        // one arm updated and the other still at its pre-write value.
        composites.forEach { it shouldBe (it["personCity"] as Map<*, *>).let { m ->
            when (m.size) {
                0 -> mapOf("personCity" to emptyMap<Long, Long>(), "personFriend" to emptySet<Long>())
                1 -> settled
                else -> settledAfterSecond
            }
        } }

        sink.close()
    }
}
