package civictech.bench

import java.io.File
import java.lang.management.ManagementFactory

/**
 * The captured circumstances a [BenchResult] was measured under (`[BEN1-23]`, BS-13).
 *
 * Every field is required and non-nullable by construction — there is no optional or
 * `"unknown"` placeholder anywhere in this type. A result's numbers are meaningless
 * without knowing what produced them (JVM, heap, CPU, OS, JMH's own knobs, and the
 * harness commit that ran it), so a [RunEnvironment] that is missing one of those
 * facts must not exist at all. [init] enforces that at construction time: a blank
 * string or a non-positive count fails with [IllegalArgumentException] rather than
 * being silently accepted.
 *
 * @param jvmVendor `java.vendor` (or an equivalent identification of the JVM build).
 * @param jvmVersion `java.version` (or an equivalent JVM version string).
 * @param heapSettings the heap configuration the run executed under (explicit
 *   `-Xms`/`-Xmx` flags, or a derived description of the effective heap).
 * @param cpuModel the host CPU's model/brand string.
 * @param coreCount the host's available processor/core count. Must be positive.
 * @param os the host operating system name and version.
 * @param jmhMode JMH's benchmark mode (e.g. `Throughput`, `AverageTime`), or an
 *   explicitly stated equivalent for a non-JMH measurement.
 * @param forkCount the number of forks the run used. Must be positive.
 * @param warmupIterations the number of warmup iterations the run used. Must be
 *   positive.
 * @param measurementIterations the number of measurement iterations the run used.
 *   Must be positive.
 * @param harnessCommitSha the commit SHA of the harness (this repository) that
 *   produced the result.
 */
data class RunEnvironment(
    val jvmVendor: String,
    val jvmVersion: String,
    val heapSettings: String,
    val cpuModel: String,
    val coreCount: Int,
    val os: String,
    val jmhMode: String,
    val forkCount: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
    val harnessCommitSha: String,
) {
    init {
        require(jvmVendor.isNotBlank()) { "jvmVendor must not be blank" }
        require(jvmVersion.isNotBlank()) { "jvmVersion must not be blank" }
        require(heapSettings.isNotBlank()) { "heapSettings must not be blank" }
        require(cpuModel.isNotBlank()) { "cpuModel must not be blank" }
        require(coreCount > 0) { "coreCount must be positive, was $coreCount" }
        require(os.isNotBlank()) { "os must not be blank" }
        require(jmhMode.isNotBlank()) { "jmhMode must not be blank" }
        require(forkCount > 0) { "forkCount must be positive, was $forkCount" }
        require(warmupIterations > 0) {
            "warmupIterations must be positive, was $warmupIterations"
        }
        require(measurementIterations > 0) {
            "measurementIterations must be positive, was $measurementIterations"
        }
        require(harnessCommitSha.isNotBlank()) { "harnessCommitSha must not be blank" }
    }

    companion object {

        /**
         * Captures the six facts this host/JVM can answer for itself — vendor,
         * version, heap, CPU model, core count, OS — and combines them with the five
         * run-specific facts the caller supplies (JMH's own knobs plus the harness
         * commit), which no system property or `Runtime` call can determine on this
         * type's behalf.
         *
         * Every captured value either comes back non-blank/positive or this function
         * throws [IllegalStateException] naming which fact it could not determine.
         * There is no placeholder value: a capture that cannot answer a question
         * fails loudly instead of inventing `"unknown"`.
         *
         * @throws IllegalStateException if a host fact cannot be determined (for
         *   example, an unsupported OS for the CPU-model probe, or an empty
         *   `sysctl`/`/proc/cpuinfo` read).
         */
        fun capture(
            jmhMode: String,
            forkCount: Int,
            warmupIterations: Int,
            measurementIterations: Int,
            harnessCommitSha: String,
        ): RunEnvironment {
            val jvmVendor = System.getProperty("java.vendor")
                ?: error("system property java.vendor is not set")
            val jvmVersion = System.getProperty("java.version")
                ?: error("system property java.version is not set")
            val osName = System.getProperty("os.name")
                ?: error("system property os.name is not set")
            val osVersion = System.getProperty("os.version")
                ?: error("system property os.version is not set")

            val runtime = Runtime.getRuntime()
            val coreCount = runtime.availableProcessors()
            check(coreCount > 0) { "Runtime.availableProcessors() returned $coreCount" }

            val heapSettings = captureHeapSettings(runtime)
            val cpuModel = captureCpuModel(osName)

            return RunEnvironment(
                jvmVendor = jvmVendor,
                jvmVersion = jvmVersion,
                heapSettings = heapSettings,
                cpuModel = cpuModel,
                coreCount = coreCount,
                os = "$osName $osVersion",
                jmhMode = jmhMode,
                forkCount = forkCount,
                warmupIterations = warmupIterations,
                measurementIterations = measurementIterations,
                harnessCommitSha = harnessCommitSha,
            )
        }

        /**
         * Prefers the explicit `-Xms`/`-Xmx` JVM arguments this process was launched
         * with (via [ManagementFactory]'s runtime MX bean), because those are the
         * setting someone actually chose; falls back to [Runtime.maxMemory] — always
         * available and always positive — when neither flag was passed.
         */
        private fun captureHeapSettings(runtime: Runtime): String {
            val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
            val heapArgs = jvmArgs.filter { it.startsWith("-Xms") || it.startsWith("-Xmx") }
            if (heapArgs.isNotEmpty()) {
                return heapArgs.joinToString(separator = " ")
            }
            val maxMemory = runtime.maxMemory()
            check(maxMemory > 0) { "Runtime.maxMemory() returned $maxMemory" }
            return "maxHeapBytes=$maxMemory"
        }

        /**
         * `sysctl -n machdep.cpu.brand_string` on darwin, `/proc/cpuinfo`'s `model
         * name` field on linux. Neither source is queried on any other OS, and either
         * source failing (missing command, non-zero exit, blank output, missing
         * file, missing field) throws rather than returning a placeholder.
         */
        private fun captureCpuModel(osName: String): String {
            val normalized = osName.lowercase()
            return when {
                normalized.contains("mac") || normalized.contains("darwin") ->
                    captureCpuModelDarwin()
                normalized.contains("linux") -> captureCpuModelLinux()
                else -> error(
                    "cannot determine CPU model on unsupported OS '$osName' " +
                        "(only darwin and linux are supported)"
                )
            }
        }

        private fun captureCpuModelDarwin(): String {
            val process = ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            check(exitCode == 0 && output.isNotBlank()) {
                "sysctl -n machdep.cpu.brand_string failed: exitCode=$exitCode output='$output'"
            }
            return output
        }

        private fun captureCpuModelLinux(): String {
            val cpuinfo = File("/proc/cpuinfo")
            check(cpuinfo.isFile) { "/proc/cpuinfo is not present" }
            val modelLine = cpuinfo.readLines()
                .firstOrNull { it.startsWith("model name") }
                ?: error("/proc/cpuinfo has no 'model name' field")
            val model = modelLine.substringAfter(":").trim()
            check(model.isNotBlank()) { "/proc/cpuinfo's 'model name' field is blank" }
            return model
        }
    }
}
