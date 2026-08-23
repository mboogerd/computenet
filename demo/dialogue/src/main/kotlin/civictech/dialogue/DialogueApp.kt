package civictech.dialogue

import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort

/**
 * Stub entry point for `:demo:dialogue` (epic computenet-2aw, feature F1).
 * This boots the shared HTTP/SSE shell and nothing else: the transcript
 * ingress, replay drive, and dataflow graph are sibling tasks, and the real
 * HTTP surface is a later feature (F5). Its only job here is to prove the
 * module wires up and runs.
 */
fun main(args: Array<String>) {
    val port = demoPort(args)
    val shell = DemoShell(port).start()
    println("civictech.dialogue stub listening on port ${shell.boundPort} (no routes registered yet)")
}
