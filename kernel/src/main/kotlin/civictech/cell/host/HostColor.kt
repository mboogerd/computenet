package civictech.cell.host

/**
 * The concurrency color of a host's execution context (spec 32): a
 * virtual-thread host is [BLOCKING] (🔵, hosting blocking/pure cells), a
 * coroutine host is [SUSPENDING] (🟣, hosting suspending/pure cells).
 * 🔵 and 🟣 never coexist in one host.
 */
enum class HostColor { BLOCKING, SUSPENDING }
