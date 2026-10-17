package civictech.bench.nexmark

/** Fresh-JVM entry point used by [GeneratorDeterminismTest]. */
object GeneratorDigestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "expected seed, events and eventsPerSecond" }
        val scale = NexmarkScale(args[1].toInt(), args[2].toInt())
        print(NexmarkGenerator.digest(NexmarkGenerator.generate(args[0].toLong(), scale)))
    }
}
