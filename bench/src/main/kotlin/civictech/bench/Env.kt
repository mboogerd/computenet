package civictech.bench

import java.io.File

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
 * The first three fields describe the JVM that PRODUCED the measurements, and are
 * therefore not this process's to answer — see [MeasuringJvm] and [forRun], and the
 * defect ledger in [MeasuringJvm]'s own documentation for what happens when they are.
 *
 * @param jvmVendor the vendor/build identification of the JVM the measurements ran on.
 * @param jvmVersion the version of the JVM the measurements ran on.
 * @param heapSettings the heap configuration the measuring JVM executed under (its
 *   explicit `-Xms`/`-Xmx` flags, or an explicit statement that it was launched with
 *   none).
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
         * Builds the environment of a run whose measuring JVM is stated by
         * [measuringJvm], capturing only the facts that are the HOST's and combining
         * them with the five run-specific facts the caller supplies (JMH's own knobs
         * plus the harness commit).
         *
         * ## Why the JVM triple is a parameter and not a capture (computenet-hqid)
         *
         * This function used to read `java.vendor`, `java.version` and
         * `ManagementFactory.getRuntimeMXBean().inputArguments` itself. Those are facts
         * about the CALLING process — and the caller is the renderer, running in the
         * Gradle `:bench:test` JVM long after the JMH forks have exited. Two entries in
         * `doc/bench/findings.md` shipped with a `Harness:` line describing the render
         * step: the REAL-drive sweep ran on Homebrew JDK 26.0.1 with no VM options and
         * was reported as `Eclipse Adoptium/21.0.11 · heap -Xmx2g`, which is the
         * toolchain JDK the render task runs under. The wrong value was a real JDK on
         * that host, so it read as correct.
         *
         * The reads are therefore GONE from this file rather than merely bypassed:
         * there is no code path left that can answer "which JVM measured this?" with
         * "the one asking". [MeasuringJvm.fromJmhLog] is the answer's only source, and
         * it refuses when the run's artifacts do not carry one.
         *
         * ## What is still captured here, and the residual that leaves
         *
         * [cpuModel], [coreCount] and [os] are read from THIS host, because no JMH
         * artifact records them — the banner states the JVM and its options and nothing
         * about the machine. That is sound only because rendering is documented to
         * happen on the machine that ran the sweep (`ThroughputReport`'s command block
         * runs the sweep and the render back to back). It is a weaker guarantee than
         * the JVM triple now has, and it is stated rather than hidden: a results file
         * carried to another machine and rendered there would carry that machine's CPU
         * and OS. The JVM triple is the one that provably differed in practice, and it
         * is the one this change closes.
         *
         * Every captured value either comes back non-blank/positive or this function
         * throws [IllegalStateException] naming which fact it could not determine.
         * There is no placeholder value: a capture that cannot answer a question
         * fails loudly instead of inventing `"unknown"`.
         *
         * @param measuringJvm the vendor, version and heap of the JVM that produced the
         *   measurements, established from the run's own artifacts.
         * @throws IllegalStateException if a host fact cannot be determined (for
         *   example, an unsupported OS for the CPU-model probe, or an empty
         *   `sysctl`/`/proc/cpuinfo` read).
         */
        fun forRun(
            measuringJvm: MeasuringJvm,
            jmhMode: String,
            forkCount: Int,
            warmupIterations: Int,
            measurementIterations: Int,
            harnessCommitSha: String,
        ): RunEnvironment {
            val osName = System.getProperty("os.name")
                ?: error("system property os.name is not set")
            val osVersion = System.getProperty("os.version")
                ?: error("system property os.version is not set")

            val runtime = Runtime.getRuntime()
            val coreCount = runtime.availableProcessors()
            check(coreCount > 0) { "Runtime.availableProcessors() returned $coreCount" }

            val cpuModel = captureCpuModel(osName)

            return RunEnvironment(
                jvmVendor = measuringJvm.vendor,
                jvmVersion = measuringJvm.version,
                heapSettings = measuringJvm.heapSettings,
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

/**
 * Thrown when the JVM that PRODUCED a measurement cannot be established from the run's
 * own artifacts (`[BEN1-23]`, computenet-hqid).
 *
 * Deliberately its own type, and deliberately not a [FindingsRefusalException]: this is
 * upstream of every F3 refusal, in the same place `ThroughputReportException` sits. F3
 * refuses results it can see are dishonest; this refuses to manufacture the one fact
 * nobody in the render process is in a position to know.
 */
class MeasuringJvmUnknownException(message: String) : IllegalStateException(message)

/**
 * The vendor, version and heap of the JVM that ran the benchmark forks — read off the
 * run's own artifacts, never off the process doing the reading (`[BEN1-23]`).
 *
 * ## The defect this type exists to make impossible
 *
 * `RunEnvironment.capture` used to answer these three questions with
 * `System.getProperty("java.vendor")`, `System.getProperty("java.version")` and
 * `ManagementFactory.getRuntimeMXBean().inputArguments`. Every one of those describes
 * the calling process, and the caller is the renderer — the Gradle `:bench:test` JVM,
 * running after the JMH forks have exited. JMH's CSV output carries score, error, unit
 * and `Param:` columns and no JVM columns at all, so nothing about the measuring JVM
 * ever reached the entry. Both throughput entries in `doc/bench/findings.md` shipped a
 * `Harness:` line naming the render JVM; the REAL-drive sweep actually ran on Homebrew
 * JDK 26.0.1 with no VM options and was published as `Eclipse Adoptium/21.0.11 · heap
 * -Xmx2g`. It was not detectable by reading the entry, because that IS a JDK on that
 * host.
 *
 * ## The artifact this reads, and why the banner is a fact about the fork
 *
 * JMH writes a banner to its own stdout before each benchmark:
 *
 * ```
 * # JMH version: 1.37
 * # VM version: JDK 26.0.1, OpenJDK 64-Bit Server VM, 26.0.1
 * # VM invoker: /opt/homebrew/Cellar/openjdk/26.0.1/.../bin/java
 * # VM options: <none>
 * ```
 *
 * `# VM invoker` and `# VM options` are the binary and the arguments JMH then launches
 * the forked, measuring JVM with — they are the fork's launch command, printed. That is
 * why they can be trusted about the fork and the renderer's own properties cannot. The
 * banner is also what a human reads to check a sweep, and it is what the review of
 * `computenet-x9e.4` used to catch the shipped entries; parsing the same lines keeps
 * the tool and the human looking at one artifact instead of two.
 *
 * The one seam is that `# VM version` is the *launcher's* version, which equals the
 * fork's unless the sweep passed JMH's `-jvm` to fork a different binary. [fromJmhLog]
 * closes that seam where it can: when the invoker's JDK is readable it cross-checks the
 * banner's version against that JDK's own `release` file and REFUSES on disagreement,
 * rather than picking whichever it happens to read first.
 *
 * ## Capturing the log
 *
 * The banner is on stdout, so the sweep must tee it beside the results file — see
 * `ThroughputReport`'s command block, which names the exact invocation, and
 * `ThroughputReport.runLogFor`, which names where the renderer looks for it.
 *
 * @param vendor the measuring JDK's `IMPLEMENTOR` (`"Eclipse Adoptium"`, `"Homebrew"`)
 *   when its `release` file is readable; otherwise the banner's VM name qualified by
 *   the invoker path, which identifies the JVM build without guessing a distributor.
 * @param version the measuring JVM's version, as the banner's `# VM version` states it.
 * @param heapSettings the heap flags the fork was launched with, or an explicit
 *   statement that its `# VM options` carried none. Never the render process's heap,
 *   and never a heap "derived" from the render process's `Runtime.maxMemory()`.
 */
data class MeasuringJvm(
    val vendor: String,
    val version: String,
    val heapSettings: String,
) {
    init {
        require(vendor.isNotBlank()) { "vendor must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(heapSettings.isNotBlank()) { "heapSettings must not be blank" }
    }

    companion object {

        /** JMH's banner line stating the JVM version the run was launched from. */
        const val VM_VERSION_PREFIX: String = "# VM version:"

        /** JMH's banner line stating the `java` binary the forks are launched with. */
        const val VM_INVOKER_PREFIX: String = "# VM invoker:"

        /** JMH's banner line stating the arguments the forks are launched with. */
        const val VM_OPTIONS_PREFIX: String = "# VM options:"

        /** What JMH prints for `# VM options` when the forks got no arguments at all. */
        const val NO_OPTIONS: String = "<none>"

        /**
         * Flag prefixes that set the heap. `-Xmn` is included because a fixed young
         * generation is part of the heap configuration a reader would want to see, and
         * the `-XX:` forms because a sweep configured through them is configured no
         * less deliberately than one using `-Xmx`.
         */
        private val HEAP_FLAG_PREFIXES = listOf(
            "-Xms", "-Xmx", "-Xmn",
            "-XX:MinHeapSize", "-XX:InitialHeapSize", "-XX:MaxHeapSize",
            "-XX:MinRAMPercentage", "-XX:InitialRAMPercentage", "-XX:MaxRAMPercentage",
            "-XX:MaxRAM=",
        )

        /**
         * Reads the measuring JVM off a JMH run log, or refuses.
         *
         * @param log the full stdout of the JMH run that produced the results file.
         * @param source where [log] came from, named in every refusal message so a
         *   reader learns which file to go look at.
         * @throws MeasuringJvmUnknownException if the log carries no `# VM version` or
         *   no `# VM options` line, if it carries more than one distinct value for
         *   either (a log concatenating runs on different JVMs describes no single
         *   measuring JVM), or if the banner and the invoker's own `release` file
         *   disagree about the version.
         */
        fun fromJmhLog(log: String, source: String): MeasuringJvm {
            val versionValue = bannerValue(log, VM_VERSION_PREFIX, source, required = true)!!
            val optionsValue = bannerValue(log, VM_OPTIONS_PREFIX, source, required = true)!!
            val invoker = bannerValue(log, VM_INVOKER_PREFIX, source, required = false)

            // "JDK 26.0.1, OpenJDK 64-Bit Server VM, 26.0.1" — jdk version, VM name, VM
            // version. Only the first is required: it is the version a findings entry
            // reports, and the one `java.version` would have answered.
            val parts = versionValue.split(",").map { it.trim() }
            val bannerVersion = parts.firstOrNull().orEmpty().removePrefix("JDK").trim()
            if (bannerVersion.isBlank()) {
                throw MeasuringJvmUnknownException(
                    "cannot establish the measuring JVM's version: $source states " +
                        "'$VM_VERSION_PREFIX $versionValue', which carries no version " +
                        "before its first comma"
                )
            }
            val vmName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }

            val release = invoker?.let { readReleaseFile(it) }
            crossCheckVersion(bannerVersion, release, invoker, source)

            return MeasuringJvm(
                vendor = vendorOf(release, vmName, invoker, source),
                version = bannerVersion,
                heapSettings = heapSettingsOf(optionsValue),
            )
        }

        /**
         * The single distinct value of one banner line across the whole log.
         *
         * JMH repeats the banner per benchmark, so a well-formed sweep log holds the
         * same value many times; `distinct()` collapses that. More than one distinct
         * value means the log is not one run on one JVM, and there is then no measuring
         * JVM to name — refused rather than resolved to the first one seen, which is
         * the same shape as `FindingsTable`'s single-environment refusal.
         */
        private fun bannerValue(
            log: String,
            prefix: String,
            source: String,
            required: Boolean,
        ): String? {
            val values = log.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
            if (values.size > 1) {
                throw MeasuringJvmUnknownException(
                    "cannot establish the measuring JVM: $source states $prefix " +
                        "${values.size} different ways ($values), so it is not one run " +
                        "on one JVM"
                )
            }
            if (values.isEmpty() && required) {
                throw MeasuringJvmUnknownException(
                    "cannot establish the measuring JVM: $source carries no '$prefix' " +
                        "line. That line is JMH's own banner, written to stdout, and it " +
                        "is the only record of which JVM the forks ran on — the results " +
                        "file carries no JVM columns. Re-run the sweep teeing its " +
                        "output beside the results file, e.g. " +
                        "`java -jar bench/build/libs/bench-jmh.jar ... -rf csv -rff " +
                        "/abs/path/throughput.csv 2>&1 | tee /abs/path/throughput.log`"
                )
            }
            return values.firstOrNull()
        }

        /**
         * The `IMPLEMENTOR`/`IMPLEMENTOR_VERSION`/`JAVA_VERSION` entries of the JDK the
         * `# VM invoker` path points into, or `null` when that JDK is not readable from
         * here (a sweep rendered on another machine, a JDK since uninstalled).
         *
         * This reads the MEASURING JVM's own installation — the one the banner names —
         * so it is a fact about the run, not about the renderer. Being unable to read
         * it is not fatal: the banner alone still identifies the JVM build.
         */
        private fun readReleaseFile(invoker: String): Map<String, String>? {
            // <java home>/bin/java -> <java home>
            val javaHome = File(invoker).parentFile?.parentFile ?: return null
            val release = File(javaHome, "release")
            if (!release.isFile) return null
            return runCatching {
                release.readLines()
                    .mapNotNull { line ->
                        val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                        if (key.isEmpty() || !line.contains('=')) return@mapNotNull null
                        key to line.substringAfter('=').trim().removeSurrounding("\"")
                    }
                    .toMap()
            }.getOrNull()
        }

        /**
         * Refuses when the banner's version and the invoker JDK's own `release` file
         * disagree.
         *
         * This is the `-jvm` seam: JMH prints `# VM version` from the process running
         * the harness, and `# VM invoker` is what it forks. They are the same binary
         * unless the sweep passed `-jvm`, in which case exactly one of the two lines
         * describes the JVM that measured — and nothing in the log says which. Refusing
         * is the only honest move; picking one would reintroduce this ticket's defect
         * with a different wrong source.
         */
        private fun crossCheckVersion(
            bannerVersion: String,
            release: Map<String, String>?,
            invoker: String?,
            source: String,
        ) {
            val invokerVersion = release?.get("JAVA_VERSION")?.takeIf { it.isNotBlank() }
                ?: return
            if (invokerVersion != bannerVersion) {
                throw MeasuringJvmUnknownException(
                    "cannot establish the measuring JVM: $source states " +
                        "'$VM_VERSION_PREFIX ... $bannerVersion' but its " +
                        "'$VM_INVOKER_PREFIX $invoker' resolves to a JDK whose release " +
                        "file says JAVA_VERSION=$invokerVersion. The banner's version is " +
                        "the harness process's; the invoker is what the forks run. They " +
                        "disagree (a `-jvm` fork), so the artifacts do not say which JVM " +
                        "measured"
                )
            }
        }

        /**
         * `IMPLEMENTOR` when the measuring JDK's `release` file is readable — the
         * literal answer `java.vendor` would have given, for the right JVM. Otherwise
         * the banner's VM name qualified by the invoker path: not a distributor name,
         * but an identification of the JVM build taken from the run rather than
         * guessed.
         */
        private fun vendorOf(
            release: Map<String, String>?,
            vmName: String?,
            invoker: String?,
            source: String,
        ): String {
            val implementor = release?.get("IMPLEMENTOR")?.takeIf { it.isNotBlank() }
            if (implementor != null) {
                val implementorVersion = release["IMPLEMENTOR_VERSION"]
                    ?.takeIf { it.isNotBlank() && it != implementor }
                return if (implementorVersion == null) {
                    implementor
                } else {
                    "$implementor ($implementorVersion)"
                }
            }
            return when {
                vmName != null && invoker != null -> "$vmName (VM invoker $invoker)"
                vmName != null -> vmName
                invoker != null -> "VM invoker $invoker"
                else -> throw MeasuringJvmUnknownException(
                    "cannot establish the measuring JVM's vendor: $source's " +
                        "'$VM_VERSION_PREFIX' line names no VM and there is no " +
                        "'$VM_INVOKER_PREFIX' line to identify the JVM build from"
                )
            }
        }

        /**
         * The heap the forks were launched with, read off `# VM options`.
         *
         * A run with no heap flag is recorded as running on the JVM's defaults, quoting
         * the options it did get — a true statement about the measurement. What it is
         * NOT is the render process's `-Xmx`, nor its `Runtime.maxMemory()`: an
         * "effective heap" computed here would be this process's effective heap, which
         * is exactly the substitution that shipped `heap -Xmx2g` on forks that got
         * `<none>`.
         */
        private fun heapSettingsOf(optionsValue: String): String {
            val tokens = if (optionsValue == NO_OPTIONS) {
                emptyList()
            } else {
                optionsValue.split(Regex("\\s+")).filter { it.isNotBlank() }
            }
            val heapFlags = tokens.filter { token ->
                HEAP_FLAG_PREFIXES.any { token.startsWith(it) }
            }
            return when {
                heapFlags.isNotEmpty() -> heapFlags.joinToString(separator = " ")
                tokens.isEmpty() -> "JVM defaults (VM options: $NO_OPTIONS)"
                else -> "JVM defaults (no heap flag among VM options: " +
                    tokens.joinToString(separator = " ") + ")"
            }
        }
    }
}
