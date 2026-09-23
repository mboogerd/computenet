package civictech.timetravel.cli

import kotlin.system.exitProcess

/**
 * Placeholder entry point (TTD1 D7, epic `computenet-ocv`) that only proves
 * `application`'s `mainClass` resolves so `:timetravel:build` succeeds. F7
 * (computenet-3qkx1) replaces this body with the real `inspect`/`reconstruct`/`diff`
 * subcommands.
 */
fun main(args: Array<String>) {
    System.err.println(
        "usage: timetravel <inspect|reconstruct|diff> <journal-path>... (not yet implemented)"
    )
    exitProcess(2)
}
