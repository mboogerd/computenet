package civictech.bench.tpch

/** Fresh-JVM entry point used by [GeneratorDeterminismTest]. */
object GeneratorDigestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "expected seed and scale factor" }
        val seed = args[0].toLong()
        val sf = args[1].toDouble()
        print(TpchGenerator.digest(TpchGenerator.generate(seed, sf)))
    }
}
