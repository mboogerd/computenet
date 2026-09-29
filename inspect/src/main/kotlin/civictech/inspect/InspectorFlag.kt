package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.shell.announcePort
import civictech.inspect.edit.Capability
import civictech.inspect.edit.Catalogue
import civictech.inspect.edit.KernelEntries
import civictech.inspect.edit.WritePlane

/**
 * The one shared `--inspect-port` opt-in every demo wires against
 * (`doc/spec/90-roadmap/97-inspector-plan`, computenet-3iv0w rule R1).
 *
 * It lives in `:inspect`, not `:demo:shell` (3iv0w-D1): `:demo:shell` declares
 * no `:kernel` dependency and so cannot even type [LocationRegistry] or
 * [ManagedHost], and an [InspectorServer] cannot be constructed from a module
 * it does not depend on without a cycle (`:inspect` already depends on
 * `:demo:shell`, the reverse would not compile). Every demo therefore keeps
 * its own registry and hosts private and hands this object exactly what only
 * it knows — parsed flags, its registry, its hosts by name, and optional cell
 * names — through [parse], [serve] and [announce], instead of re-implementing
 * flag parsing, argument stripping, write-plane wiring and the startup banner
 * once per demo the way `demo/shopping/.../Main.kt` and
 * `demo/skillmatch/.../SkillMatchApp.kt` did before this object existed.
 */
object InspectorFlag {

    /** `--inspect-port <p>` / `--inspect-port=<p>`, else env [PORT_ENV]. */
    const val PORT_FLAG: String = "--inspect-port"

    /** WKB2 F5 (`[WKB2-06]`): the bare opt-in into the write plane. Presence-tested, never given a value. */
    const val WRITE_FLAG: String = "--inspect-write"

    /** `--inspect-write-capability <v>` / `=<v>`, else env [WRITE_CAPABILITY_ENV]. Ignored without [WRITE_FLAG]. */
    const val WRITE_CAPABILITY_FLAG: String = "--inspect-write-capability"

    /** `--net-name <n>` — this JVM's network-host label (M5-NET), also used by peering identity (V4-PEERID). */
    const val NET_NAME_FLAG: String = "--net-name"

    /** [PORT_FLAG]'s environment fallback. */
    const val PORT_ENV: String = "INSPECT_PORT"

    /** [WRITE_CAPABILITY_FLAG]'s environment fallback. */
    const val WRITE_CAPABILITY_ENV: String = "INSPECT_WRITE_CAPABILITY"

    /**
     * What [parse] recovered: whether an inspector was asked for at all
     * ([port]), this JVM's network label ([netName], defaulted to
     * [Node.LOCAL_NET] for [serve]'s benefit), and the write plane it should
     * start with ([writePlane], [WritePlane.Disabled] unless [WRITE_FLAG] and
     * a port were both given — `[WKB2-06]`).
     */
    data class Options(
        val port: Int,
        val netName: String = Node.LOCAL_NET,
        val writePlane: WritePlane = WritePlane.Disabled,
    )

    /**
     * [parse]'s result. [options] is null when no port was given anywhere —
     * flag, `=`-form or environment — in which case an inspector is not
     * started at all, whatever [WRITE_FLAG] or a capability said. [rest] is
     * [Array] `args` with every inspector token (and, where it takes one, its
     * value) removed, so a caller's own positional-argument reader (`demoPort`,
     * deliberate's own `Options`) never mistakes an inspector value for its
     * own. [netName] is the raw `--net-name` value (or null if absent),
     * returned on its own because a caller — shopping, exchange — also reads
     * it for peering identity, not only for the inspector.
     */
    class Parsed(val options: Options?, val rest: Array<String>, val netName: String?)

    /**
     * The union of both private parsers `demo/shopping` and `demo/skillmatch`
     * carried before this object existed — [PORT_FLAG]'s bug (an inspector
     * value taken for the demo's own positional port) is the one every
     * caller must not reintroduce, which is why [rest] strips a token's value
     * along with the token, never leaving an orphaned value behind for a
     * later positional reader to pick up.
     */
    fun parse(args: Array<String>, env: (String) -> String? = System::getenv): Parsed {
        var portValue: String? = null
        var writeEnabled = false
        var capabilityValue: String? = null
        var netNameValue: String? = null
        val rest = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == PORT_FLAG -> {
                    portValue = args.getOrNull(i + 1)
                    i++
                }

                arg.startsWith("$PORT_FLAG=") -> portValue = arg.substringAfter('=')
                arg == WRITE_FLAG -> writeEnabled = true
                arg == WRITE_CAPABILITY_FLAG -> {
                    capabilityValue = args.getOrNull(i + 1)
                    i++
                }

                arg.startsWith("$WRITE_CAPABILITY_FLAG=") -> capabilityValue = arg.substringAfter('=')
                arg == NET_NAME_FLAG -> {
                    netNameValue = args.getOrNull(i + 1)
                    i++
                }

                else -> rest += arg
            }
            i++
        }

        val port = (portValue ?: env(PORT_ENV))?.trim()?.toIntOrNull()
        val netName = netNameValue?.trim()?.takeUnless { it.isEmpty() }
        val options = port?.let {
            val writePlane = if (writeEnabled) {
                val capability = (capabilityValue ?: env(WRITE_CAPABILITY_ENV))?.trim()?.takeUnless { v -> v.isEmpty() }
                WritePlane.Enabled(capability?.let(::Capability) ?: Capability.mint())
            } else {
                WritePlane.Disabled
            }
            Options(port = it, netName = netName ?: Node.LOCAL_NET, writePlane = writePlane)
        }
        return Parsed(options, rest.toTypedArray(), netName)
    }

    /**
     * Build and start the [InspectorServer] this [Options] describes.
     *
     * Registers the demo write-plane's kernel catalogue ([KernelEntries.register],
     * idempotent across repeated calls) before construction whenever
     * [Options.writePlane] is enabled — the catalogue population is part of the
     * opt-in, not of the read-only instrument (WKB2 F12, va0c4-D10) — and leaves
     * it untouched otherwise. [configure] runs after construction but before
     * [InspectorServer.start] (shopping's `nameGraph(...)` precedes `start()`
     * today); [InspectorServer.declareLink] stays a caller-side call on the
     * returned, already-started server.
     */
    fun Options.serve(
        registry: LocationRegistry,
        hosts: Map<String, ManagedHost>,
        cellNames: Map<CellRef, String> = emptyMap(),
        configure: InspectorServer.() -> Unit = {},
    ): InspectorServer {
        if (writePlane is WritePlane.Enabled) KernelEntries.register()
        val server = InspectorServer(
            registry = registry,
            hosts = hosts,
            port = port,
            cellNames = cellNames,
            netName = netName,
            writePlane = writePlane,
        )
        server.configure()
        return server.start()
    }

    /**
     * The four startup lines every demo's `main` printed privately —
     * verbatim in wording and order, since the second is read by
     * `civictech.testkit.JvmPeer.Peer.port("inspect")` in shopping's two-JVM
     * tests and the first two lines are documented recipes
     * (`doc/demo-shopping-inspector.md`, `inspect/ui/README.md`).
     */
    fun announce(server: InspectorServer, options: Options) {
        println("computenet inspector: http://localhost:${server.boundPort}/api/inspect/topology")
        announcePort("inspect", server.boundPort)
        println("  this JVM's network host: ${options.netName}")
        val writePlane = options.writePlane
        if (writePlane is WritePlane.Enabled) {
            println(
                "inspector write plane ENABLED on loopback; capability: ${writePlane.capability.value}; " +
                    "catalogue entries: ${Catalogue.entries().size}",
            )
        }
    }
}
