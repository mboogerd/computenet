package civictech.cell.app

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.CollectorCell
import civictech.cell.data.CountCell
import civictech.cell.data.CounterDelta
import civictech.cell.data.FilterCell
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.data.tagFold
import civictech.cell.graph.graph
import civictech.cell.host.CellError
import civictech.cell.host.DeadLetter
import civictech.cell.host.ErrorReporting
import civictech.cell.host.HostColor
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.verify.InvariantCell
import civictech.cell.verify.Violation
import civictech.cell.verify.checkInvariants
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * The M4 exit criterion: a collaborative shopping-list-with-votes app built
 * purely from cells — per-user writers on their own hosts (mixed 🔵/🟣),
 * per-user derived views (union → filter → count) constructed via the graph
 * DSL, running under seeded randomized scheduling with:
 *
 * - concurrent adds/removes/votes from all users,
 * - a user joining mid-session and catching up (M4.2),
 * - a user's host migrating mid-session (M3),
 * - an injected cell failure consumed through an error outlet under RESTART (M4.4),
 * - invariants running through the kotest adapter (M4.4/M4.6),
 *
 * with a control run proving the harness detects the failure class
 * (non-convergent views). 100 seeds.
 */
class CollaborativeAppTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    class CounterCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals = mutableListOf<CounterDelta>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {
                    arrivals += value
                }
            })
        }
    }

    /** A side-path consumer that chokes on one poisoned item — the injected failure. */
    class NotifierCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, ErrorReporting {
        var notified = 0
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        override val errorOutlet = registerPort("errorOutlet", FanOutlet.create<Propagate<CellError>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    value.adds.keys.forEach { require(it != "poison") { "cannot notify about: $it" } }
                    notified += value.adds.size
                }
            })
        }
    }

    /** One user's presence: writer cells plus the DSL-built derived views on their host. */
    private class User(
        val host: ManagedHost,
        registry: LocationRegistry,
    ) {
        val items = SetCell<String>()
        val votes = SetCell<String>()
        val itemsUnion = UnionSetCell<String>()
        val votesUnion = UnionSetCell<String>()
        val itemsView = CollectorCell()
        val votesView = CollectorCell()
        val produceView = CollectorCell()
        val voteCounts = CounterCollectorCell()
        val countViolations = mutableListOf<Violation>()
        val nonNegativeCount = InvariantCell<CounterDelta, Long>(
            "non-negative vote count", 0L,
            fold = { total, delta -> total + delta.amount },
            check = { total, _ -> if (total < 0) "vote count went negative: $total" else null },
        )

        val itemsApi: SetOps<String>
        val votesApi: SetOps<String>

        init {
            val manage = host.managementInlet.call
            listOf(items, votes, itemsUnion, votesUnion, itemsView, votesView, produceView, voteCounts, nonNegativeCount)
                .forEach { manage.spawn(it) }

            // the derived view chain is DSL-built: produce filter + vote counter
            val refs = mutableMapOf<String, CellRef>()
            graph(host.managementInlet) {
                val produce = spawn("produce") { FilterCell<String> { s -> s <= "item-j" } }
                val count = spawn("count") { CountCell<String>() }
                refs["produce"] = produce.ref
                refs["count"] = count.ref
            }
            manage.connect(itemsUnion.ref, "outlet", refs.getValue("produce"), "inlet")
            manage.connect(votesUnion.ref, "outlet", refs.getValue("count"), "inlet")
            manage.connect(itemsUnion.ref, "outlet", itemsView.ref, "inlet")
            manage.connect(votesUnion.ref, "outlet", votesView.ref, "inlet")
            manage.connect(refs.getValue("produce"), "outlet", produceView.ref, "inlet")
            manage.connect(refs.getValue("count"), "outlet", voteCounts.ref, "inlet")
            manage.connect(refs.getValue("count"), "outlet", nonNegativeCount.ref, "inlet")
            nonNegativeCount.violations.subscribe(Use.fixed(object : Propagate<Violation> {
                override fun propagate(value: Violation) {
                    countViolations += value
                }
            }, PortRef.generate()))

            itemsApi = (HostedCellProxy.create(items.ref, registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            votesApi = (HostedCellProxy.create(votes.ref, registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
        }

        fun itemsMembership() = tagFold(itemsView.arrivals)
    }

    /** Stream [writer]'s deltas into [union] on [unionHost]: handshake (catch-up) then queue-routed delivery. */
    private fun streamInto(
        writer: SetCell<String>,
        union: UnionSetCell<String>,
        unionHost: ManagedHost,
        registry: LocationRegistry,
    ) {
        @Suppress("UNCHECKED_CAST")
        writer.outlet.linkTo(union.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        val routed = (HostedCellProxy.create(union.ref, registry, DeltaInletProxy::class.java)
                as DeltaInletProxy).inlet.call
        writer.outlet.unsubscribe(union.inlet.ref)
        writer.outlet.subscribe(Use.fixed(routed, union.inlet.ref))
    }

    private data class Run(
        val users: List<User>,
        val notifier: NotifierCell,
        val errors: List<CellError>,
        val letters: List<DeadLetter>,
        val expectedItems: Set<String>,
        val expectedVotes: Set<String>,
        val itemsEver: Set<String>,
    )

    private fun runSession(seed: Long, ops: Int): Run {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hostU1 = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostU2 = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostU3 = ManagedHost(scheduler = controller.scheduler(HostColor.SUSPENDING), registry = registry)
        val hostU2b = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val letters = mutableListOf<DeadLetter>()
        listOf(hostU1, hostU2, hostU3, hostU2b).forEach { host ->
            host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) {
                    letters += value
                }
            }, PortRef.generate()))
        }

        val user1 = User(hostU1, registry)
        val user2 = User(hostU2, registry)
        val users = mutableListOf(user1, user2)

        // full mesh: every writer streams into every user's unions
        users.forEach { owner ->
            users.forEach { viewer ->
                streamInto(owner.items, viewer.itemsUnion, viewer.host, registry)
                streamInto(owner.votes, viewer.votesUnion, viewer.host, registry)
            }
        }

        // the injected-failure path: a notifier on user1's items view, RESTART-supervised,
        // delivered through the host queue so failures attribute to the notifier itself
        val notifier = NotifierCell()
        hostU1.managementInlet.call.spawn(notifier)
        hostU1.managementInlet.call.supervise(notifier.ref, SupervisionPolicy.RESTART)
        @Suppress("UNCHECKED_CAST")
        user1.itemsUnion.outlet.linkTo(notifier.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        val routedNotifier = (HostedCellProxy.create(notifier.ref, registry, DeltaInletProxy::class.java)
                as DeltaInletProxy).inlet.call
        user1.itemsUnion.outlet.unsubscribe(notifier.inlet.ref)
        user1.itemsUnion.outlet.subscribe(Use.fixed(routedNotifier, notifier.inlet.ref))
        val errors = mutableListOf<CellError>()
        notifier.errorOutlet.subscribe(Use.fixed(object : Propagate<CellError> {
            override fun propagate(value: CellError) {
                errors += value
            }
        }, PortRef.generate()))

        // ---- session script
        val rnd = Random(seed)
        val domain = ('a'..'t').map { "item-$it" }
        val heldItems = mutableMapOf<User, MutableSet<String>>()
        val heldVotes = mutableMapOf<User, MutableSet<String>>()
        users.forEach { heldItems[it] = mutableSetOf(); heldVotes[it] = mutableSetOf() }
        val itemsEver = mutableSetOf<String>()
        val joinAt = ops / 4 + rnd.nextInt(ops / 4)
        val poisonAt = ops / 2
        val moveAt = ops / 2 + 1 + rnd.nextInt(ops / 4)

        val invariants = listOf(user1.nonNegativeCount, user2.nonNegativeCount)
        checkInvariants(controller, invariants, clue = "seed $seed:") {
            for (n in 1..ops) {
                if (n == joinAt) {
                    // a third user joins mid-session on a suspending host and catches up
                    val user3 = User(hostU3, registry)
                    heldItems[user3] = mutableSetOf(); heldVotes[user3] = mutableSetOf()
                    users.forEach { existing ->
                        streamInto(existing.items, user3.itemsUnion, hostU3, registry)
                        streamInto(existing.votes, user3.votesUnion, hostU3, registry)
                        streamInto(user3.items, existing.itemsUnion, existing.host, registry)
                        streamInto(user3.votes, existing.votesUnion, existing.host, registry)
                    }
                    streamInto(user3.items, user3.itemsUnion, hostU3, registry)
                    streamInto(user3.votes, user3.votesUnion, hostU3, registry)
                    users += user3
                }
                if (n == poisonAt) {
                    users[0].itemsApi.add("poison")
                    heldItems[users[0]]!! += "poison"; itemsEver += "poison"
                }
                if (n == moveAt) {
                    hostU2.managementInlet.call.migrate(hostU2b.managementInlet)
                }

                val user = users[rnd.nextInt(users.size)]
                when {
                    rnd.nextInt(10) < 6 -> {
                        val item = domain[rnd.nextInt(domain.size)]
                        user.itemsApi.add(item)
                        heldItems[user]!! += item; itemsEver += item
                    }
                    rnd.nextInt(2) == 0 && heldItems[user]!!.isNotEmpty() -> {
                        val mine = heldItems[user]!!.toList()
                        val item = mine[rnd.nextInt(mine.size)]
                        user.itemsApi.remove(item)
                        heldItems[user]!! -= item
                    }
                    else -> {
                        val visible = user.itemsMembership()
                        if (visible.isNotEmpty()) {
                            val item = visible.toList()[rnd.nextInt(visible.size)]
                            user.votesApi.add(item)
                            heldVotes[user]!! += item
                        }
                    }
                }
                repeat(rnd.nextInt(4)) { controller.step() }
            }
        }

        return Run(
            users = users,
            notifier = notifier,
            errors = errors,
            letters = letters,
            expectedItems = users.flatMap { heldItems[it]!! }.toSet(),
            expectedVotes = users.flatMap { heldVotes[it]!! }.toSet(),
            itemsEver = itemsEver,
        )
    }

    @Test
    fun `the collaborative session converges for every user on every seed`() {
        for (seed in 0L until 100L) {
            val run = runSession(seed, ops = 60)

            run.users.size shouldBe 3
            run.users.forEach { user ->
                // every user — including the late joiner — sees the same list, votes, and views
                tagFold(user.itemsView.arrivals) shouldBe run.expectedItems
                tagFold(user.votesView.arrivals) shouldBe run.expectedVotes
                tagFold(user.produceView.arrivals) shouldBe run.expectedItems.filter { it <= "item-j" }.toSet()
                user.voteCounts.arrivals.sumOf { it.amount } shouldBe run.expectedVotes.size.toLong()
                user.countViolations.shouldBeEmpty()
            }

            // votes only ever reference items that existed
            (run.expectedVotes - run.itemsEver).shouldBeEmpty()

            // the injected failure surfaced on the error outlet, was consumed,
            // and the RESTART-ed notifier kept processing afterwards
            run.errors.size shouldBe 1
            run.errors[0].cellRef shouldBe run.notifier.ref
            (run.notifier.notified > 0).shouldBeTrue()

            // the only dead letter in the whole session is the injected failure
            run.letters.forEach { letter ->
                (letter.cause?.message ?: "") shouldBe "cannot notify about: poison"
            }
        }
    }

    @Test
    fun `control - arrival-order views would diverge across users on at least one seed`() {
        fun naiveFold(deltas: List<SetDelta<String>>): Set<String> {
            val present = mutableSetOf<String>()
            deltas.forEach { present += it.adds.keys; present -= it.dels.keys }
            return present
        }

        var diverged = 0
        for (seed in 0L until 30L) {
            val run = runSession(seed, ops = 60)
            val views = run.users.map { naiveFold(it.itemsView.arrivals) }
            if (views.toSet().size > 1 || views.any { it != run.expectedItems }) diverged++
        }
        // if this fails the harness cannot detect the failure class the invariants guard
        (diverged > 0).shouldBeTrue()
    }
}
