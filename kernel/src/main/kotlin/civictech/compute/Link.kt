package civictech.compute

data class Link(
    val from: Port,
    val to: Port,
) {
    fun Port.other(): Port? {
        return when(this) {
            from -> to
            to -> from
            else -> null
        }
    }

    fun Port.send(message: Message) {
        other()?.send(message)
    }
}
