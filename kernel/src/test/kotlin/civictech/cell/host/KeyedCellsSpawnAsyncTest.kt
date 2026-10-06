package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.SuspendingCell
import civictech.cell.data.SetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.TopoEvent
import civictech.cell.port.input
import civictech.cell.proxy.Invocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class KeyedCellsSpawnAsyncTest {

    private class TriggerCell(
        private val action: () -> Unit,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet by input<Consumer<Unit>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Unit> {
                override fun provide(input: Unit) = action()
            })
        }
    }

    private val provide = Consumer::class.java.methods.first { it.name == "provide" }

    private fun familyKeys(journal: Journal): List<TopoEvent.FamilyKey> =
        journal.replay()
            .map(JournalRecords::decode)
            .filterIsInstance<DecodedJournalRecord.Topology>()
            .flatMap { it.events }
            .filterIsInstance<TopoEvent.FamilyKey>()

    private fun failed(future: CompletableFuture<Cell>): Throwable =
        shouldThrow<ExecutionException> { future.get(5, TimeUnit.SECONDS) }.cause!!

    private class WrongColorCell(override val ref: CellRef) : SuspendingCell

    @Test
    fun `spawnAsync from the host thread completes without blocking or dead-lettering`() {
        val scheduler = VirtualThreadScheduler("keyed-spawn-async")
        try {
            val registry = LocationRegistry()
            val host = ManagedHost(scheduler = scheduler, registry = registry)
            val family = KeyedCells<String>(
                host,
                null,
                "writer",
                factory = { _, ref -> SetCell<String>(ref) },
            )
            val spawned = CompletableFuture<Cell>()
            val trigger = TriggerCell(action = {
                family.spawnAsync("alice").whenComplete { cell, failure ->
                    if (failure == null) {
                        spawned.complete(cell)
                    } else {
                        spawned.completeExceptionally(failure)
                    }
                }
            })
            host.managementInlet.call.spawn(trigger)

            host.routerInlet.call.route(
                trigger.ref,
                "inlet",
                Invocation.of(provide, arrayOf(Unit)),
            )

            val cell = spawned.get(5, TimeUnit.SECONDS)
            family.contains("alice").shouldBeTrue()
            registry.location(cell.ref).shouldNotBeNull()
            host.supervisionAccounting().deadLetters shouldBe 0L
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `getOrSpawn returns an already-live cell from the host thread without awaiting`() {
        val scheduler = VirtualThreadScheduler("keyed-live-fast-path")
        try {
            val host = ManagedHost(scheduler = scheduler)
            val family = KeyedCells<String>(
                host,
                null,
                "writer",
                factory = { _, ref -> SetCell<String>(ref) },
            )
            val live = family.getOrSpawn("alice")
            val returned = CompletableFuture<Cell>()
            val trigger = TriggerCell(action = { returned.complete(family.getOrSpawn("alice")) })
            host.managementInlet.call.spawn(trigger)

            host.routerInlet.call.route(
                trigger.ref,
                "inlet",
                Invocation.of(provide, arrayOf(Unit)),
            )

            returned.get(5, TimeUnit.SECONDS) shouldBeSameInstanceAs live
            host.supervisionAccounting().deadLetters shouldBe 0L
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `concurrent host and test thread calls join one pending spawn`() {
        val scheduler = VirtualThreadScheduler("keyed-pending-join")
        val releaseHandler = CountDownLatch(1)
        try {
            val registry = LocationRegistry()
            val journal = InMemoryJournal()
            val host = ManagedHost(scheduler = scheduler, registry = registry, journal = journal)
            val factoryCalls = AtomicInteger()
            val family = KeyedCells<String>(
                host,
                null,
                "writer",
                factory = { _, ref ->
                    factoryCalls.incrementAndGet()
                    SetCell<String>(ref)
                },
            )
            val fromHandler = CompletableFuture<CompletableFuture<Cell>>()
            val trigger = TriggerCell(action = {
                fromHandler.complete(family.spawnAsync("alice"))
                check(releaseHandler.await(5, TimeUnit.SECONDS)) { "test did not release handler" }
            })
            host.managementInlet.call.spawn(trigger)
            host.routerInlet.call.route(
                trigger.ref,
                "inlet",
                Invocation.of(provide, arrayOf(Unit)),
            )

            val first = fromHandler.get(5, TimeUnit.SECONDS)
            val second = family.spawnAsync("alice")
            second shouldBeSameInstanceAs first
            releaseHandler.countDown()

            val firstCell = first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS) shouldBeSameInstanceAs firstCell
            factoryCalls.get() shouldBe 1
            familyKeys(journal) shouldBe listOf(TopoEvent.FamilyKey("writer", "alice"))
            host.supervisionAccounting().deadLetters shouldBe 0L
        } finally {
            releaseHandler.countDown()
            scheduler.shutdown()
        }
    }

    @Test
    fun `pending is reserved before a factory re-enters for its own key`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        lateinit var family: KeyedCells<String>
        lateinit var reentered: CompletableFuture<Cell>
        family = KeyedCells(
            host,
            null,
            "writer",
            factory = { key, ref ->
                reentered = family.spawnAsync(key)
                SetCell<String>(ref)
            },
        )

        val outer = family.spawnAsync("alice")
        reentered shouldBeSameInstanceAs outer
        controller.runToIdle()

        outer.get(5, TimeUnit.SECONDS) shouldBeSameInstanceAs reentered.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `spawn refusal completes exceptionally without dead-letter and keeps only recorded keys`() {
        val volatileController = SimulationController()
        val volatileHost = ManagedHost(
            scheduler = volatileController.scheduler(HostColor.BLOCKING),
        )
        val volatileFamily = KeyedCells<String>(
            volatileHost,
            null,
            "volatile",
            factory = { _, ref -> WrongColorCell(ref) },
        )
        val volatileSpawn = volatileFamily.spawnAsync("alice")
        volatileController.runToIdle()

        failed(volatileSpawn).message shouldContain "cannot spawn on a BLOCKING host"
        volatileFamily.contains("alice").shouldBeFalse()
        volatileHost.supervisionAccounting().deadLetters shouldBe 0L

        val durableController = SimulationController()
        val journal = InMemoryJournal()
        val durableHost = ManagedHost(
            scheduler = durableController.scheduler(HostColor.BLOCKING),
            journal = journal,
        )
        val durableFamily = KeyedCells<String>(
            durableHost,
            null,
            "durable",
            factory = { _, ref -> WrongColorCell(ref) },
        )
        val durableSpawn = durableFamily.spawnAsync("bob")
        durableController.runToIdle()

        failed(durableSpawn).message shouldContain "cannot spawn on a BLOCKING host"
        durableFamily.contains("bob").shouldBeTrue()
        familyKeys(journal) shouldBe listOf(TopoEvent.FamilyKey("durable", "bob"))
        durableHost.supervisionAccounting().deadLetters shouldBe 0L
    }

    @Test
    fun `getOrSpawn still steps a SimulationController synchronously`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val family = KeyedCells<String>(
            host,
            null,
            "writer",
            factory = { _, ref -> SetCell<String>(ref) },
        )

        val cell = family.getOrSpawn("alice")

        family.contains("alice").shouldBeTrue()
        registry.location(cell.ref).shouldNotBeNull()
        host.supervisionAccounting().deadLetters shouldBe 0L
    }
}
