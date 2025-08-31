package civictech.kernel.germ

interface Consumer<T> {
    fun provide(input: T)
}