package civictech.kernel.germ

interface Consumer<T> {
    fun provide(input: T)

    companion object {
        internal fun <T> buffering(): Pair<Consumer<T>, List<T>> {
            val buffer = mutableListOf<T>()
            val consumer = object : Consumer<T> {
                override fun provide(input: T) {
                    buffer += input
                }
            }
            return consumer to buffer
        }
    }
}