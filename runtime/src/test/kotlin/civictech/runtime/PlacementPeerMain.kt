package civictech.runtime

import civictech.testkit.JvmPeer
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/** Main class launched by [ThreeJvmPlacementTest] in a fresh JVM. */
object PlacementPeerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        var manifestFile: File? = null
        var node: String? = null
        val overrides = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            when (val option = args[index]) {
                "--manifest" -> manifestFile = File(args.requireValue(++index, option))
                "--node" -> node = args.requireValue(++index, option)
                "--peer" -> {
                    val assignment = args.requireValue(++index, option)
                    val separator = assignment.indexOf('=')
                    require(separator > 0 && separator < assignment.lastIndex) {
                        "$option expects <name>=<address>, got '$assignment'"
                    }
                    overrides[assignment.substring(0, separator)] = assignment.substring(separator + 1)
                }

                else -> error("unknown argument '$option'")
            }
            index++
        }

        val manifest = requireNotNull(manifestFile) { "--manifest is required" }
        val nodeName = requireNotNull(node) { "--node is required" }
        PlacementFixture.resetCaptures()
        val runtime = Runtime.boot(
            Manifest.load(manifest),
            nodeName,
            PlacementFixture.spec(),
            overrides = overrides,
        )
        try {
            println("computenet-recovered ${runtime.recovered}")
            runtime.open()
            runtime.boundAddress?.let { address ->
                println(ADDRESS_LINE_PREFIX + address.text)
            }
            val port = runtime.boundAddress?.let { URI(it.text).port.takeIf { port -> port >= 0 } } ?: 0
            println(JvmPeer.PORT_LINE_PREFIX + "ws " + port)
            println(READY_LINE_PREFIX + runtime.name)
            System.out.flush()
            while (System.`in`.read() >= 0) {
                // Keep the peer alive until JvmPeer destroys it after the test.
            }
        } finally {
            runtime.close()
        }
    }

    private fun Array<String>.requireValue(index: Int, option: String): String =
        getOrNull(index) ?: error("$option requires a value")

    const val ADDRESS_LINE_PREFIX: String = "computenet-address "
    const val READY_LINE_PREFIX: String = "computenet-ready "
}
