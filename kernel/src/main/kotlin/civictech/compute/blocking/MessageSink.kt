package civictech.compute.blocking

interface MessageSink<M> {
    fun enqueue(message: M)
}