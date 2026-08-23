package civictech.testkit.dst

import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost

/**
 * Graphs the rig tests itself with.
 *
 * Deliberately built from schedulers and named edges rather than from `@Contract` cells:
 * `:testkit` has no KSP processor, so a real cell graph is not constructible here, and the
 * properties under test — step indexing, seeded cross-host interleaving, digest stability,
 * budget exhaustion — are properties of the *controller* and the seams, not of any cell. Real
 * cell graphs arrive with the consumer suites.
 */
object SelfTestGraphs {

    /**
     * Two hosts passing frames over two named unidirectional edges, with several chains in
     * flight at once so both hosts are genuinely busy and the controller's seeded cross-host
     * pick actually decides the interleaving.
     */
    fun crossTalk(chains: Int = 4, rounds: Int = 6): GraphSpec =
        GraphSpec("dst-selftest-crosstalk-$chains-$rounds") { world ->
            val schedulers = mutableMapOf<String, HostScheduler>()
            val journal = world.journals.declare("peerA-journal")

            val a = world.hosts.declare("peerA") { ctx ->
                schedulers["peerA"] = ctx.scheduler
                ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry, journalFor = { journal })
            }
            val b = world.hosts.declare("peerB") { ctx ->
                schedulers["peerB"] = ctx.scheduler
                ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
            }
            world.cells.declare("a", a.host.ref)
            world.cells.declare("b", b.host.ref)

            world.edges.declare("a->b", from = "peerA", to = "peerB")
            world.edges.declare("b->a", from = "peerB", to = "peerA")

            lateinit var send: (String, String, ByteArray) -> Unit
            send = { edge, target, frame ->
                world.edges.deliver(edge, frame).forEach { delivered ->
                    schedulers.getValue(target).submit(10) {
                        world.trace.emit(host = target, cell = if (target == "peerA") "a" else "b", port = "recv")
                        journal.append(delivered)
                        val round = delivered[1].toInt()
                        if (round < rounds) {
                            val next = byteArrayOf(delivered[0], (round + 1).toByte())
                            if (target == "peerA") send("a->b", "peerB", next) else send("b->a", "peerA", next)
                        }
                    }
                }
            }

            repeat(chains) { chain ->
                send("a->b", "peerB", byteArrayOf(chain.toByte(), 0.toByte()))
                send("b->a", "peerA", byteArrayOf((100 + chain).toByte(), 0.toByte()))
            }
        }

    /** A graph that never quiesces: one task that resubmits itself forever ([CHA1-03]). */
    fun livelock(): GraphSpec = GraphSpec("dst-selftest-livelock") { world ->
        lateinit var spin: () -> Unit
        var scheduler: HostScheduler? = null
        world.hosts.declare("spinner") { ctx ->
            scheduler = ctx.scheduler
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
        }
        spin = {
            scheduler!!.submit(10) {
                world.trace.emit(host = "spinner", port = "spin")
                spin()
            }
        }
        spin()
    }

    /** The smallest graph that declares one of every target kind and then does nothing. */
    fun inert(): GraphSpec = GraphSpec("dst-selftest-inert") { world ->
        world.journals.declare("j")
        val h = world.hosts.declare("h") { ctx ->
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
        }
        world.cells.declare("c", h.host.ref)
        world.edges.declare("e", from = "h", to = "h")
    }
}
