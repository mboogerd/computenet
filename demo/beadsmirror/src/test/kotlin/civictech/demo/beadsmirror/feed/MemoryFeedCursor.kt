package civictech.demo.beadsmirror.feed

/** Synchronous test cursor: a failed drive leaves the previously committed head untouched. */
class MemoryFeedCursor(var committed: String? = null) : FeedCursor {
    override fun committed(): String? = committed

    override fun commit(head: String, drive: () -> Unit) {
        drive()
        committed = head
    }
}
