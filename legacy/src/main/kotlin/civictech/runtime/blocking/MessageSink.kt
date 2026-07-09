package civictech.runtime.blocking

interface MessageSink<M> {
    fun enqueue(message: M)
}