package civictech.cell.proxy

/**
 * Where a proxy delivers captured invocations: a fixed host's intake
 * (fail-fast on closure, spec 33) or a re-resolving registry
 * ([civictech.cell.host.LocationRegistry], which parks on closure).
 */
fun interface InvocationSink {
    fun deliver(invocation: HostedPortInvocation)
}
