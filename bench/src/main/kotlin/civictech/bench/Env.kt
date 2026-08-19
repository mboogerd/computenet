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
 * [cpuModel], [coreCount] and [os] carry the same requirement one level down — see
 * [HostFacts] for the host-facts half of the same defect (computenet-yhbd).
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
         * ## Host facts: captured here for an IN-PROCESS caller, never for a JMH sweep
         *
         * [cpuModel], [coreCount] and [os] come from [HostFacts.captureCurrent] — a read
         * of THIS process's own host. That is sound only when the caller IS the
         * measuring process, exactly the same condition under which the four-scalar
         * knobs below are sound: an in-process probe (`BoundedReadFixtures
         * .probeRunEnvironment`, `Footprint.environment`) performs its measurement in
         * the very JVM that calls this function, so "the host asking" and "the host that
         * measured" coincide. A JMH sweep does NOT satisfy that condition — the renderer
         * runs long after the forks have exited, possibly on another machine entirely —
         * and the [RunKnobs] overload below therefore takes [HostFacts] as a parameter
         * instead of capturing it, for the identical reason it takes [RunKnobs] instead
         * of reading annotation constants. See [HostFacts] for the defect this closes
         * (computenet-yhbd) and why capturing harder here could not have closed it: no
         * JMH artifact records host facts at all, so the only honest fix is having the
         * measuring fork state its own.
         *
         * Every captured value either comes back non-blank/positive or this function
         * throws [IllegalStateException] naming which fact it could not determine.
         * There is no placeholder value: a capture that cannot answer a question
         * fails loudly instead of inventing `"unknown"`.
         *
         * The four JMH knobs stay parameters for the same reason the JVM triple does:
         * they are facts about the RUN. A JMH sweep's knobs are read off its log by
         * [RunKnobs.fromJmhLog] and reach this function through the [RunKnobs] overload;
         * the four-scalar form below remains for an IN-PROCESS probe, whose caller *is*
         * the measuring process and can therefore state the loop it just ran (see
         * `BoundedReadFixtures.probeRunEnvironment` and `Footprint.environment`). What
         * neither form permits is a renderer answering with a benchmark class's declared
         * annotation values — see [RunKnobs].
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
            val hostFacts = HostFacts.captureCurrent()
            return RunEnvironment(
                jvmVendor = measuringJvm.vendor,
                jvmVersion = measuringJvm.version,
                heapSettings = measuringJvm.heapSettings,
                cpuModel = hostFacts.cpuModel,
                coreCount = hostFacts.coreCount,
                os = hostFacts.os,
                jmhMode = jmhMode,
                forkCount = forkCount,
                warmupIterations = warmupIterations,
                measurementIterations = measurementIterations,
                harnessCommitSha = harnessCommitSha,
            )
        }

        /**
         * [forRun] over knobs AND host facts established from a JMH run's own log
         * rather than captured from the calling process — the honest form for a JMH
         * sweep (`[BEN1-23]`, computenet-x9e.8 for [knobs], computenet-yhbd for
         * [hostFacts]).
         *
         * @param knobs the mode, fork count and iteration counts the run actually used,
         *   from [RunKnobs.fromJmhLog].
         * @param hostFacts the CPU model, core count and OS of the host that ran the
         *   measuring fork, from [HostFacts.fromJmhLog]. NOT [HostFacts.captureCurrent]
         *   — that call answers for the process invoking it, which for a JMH sweep is
         *   the renderer, not the fork that measured.
         */
        fun forRun(
            measuringJvm: MeasuringJvm,
            knobs: RunKnobs,
            hostFacts: HostFacts,
            harnessCommitSha: String,
        ): RunEnvironment = RunEnvironment(
            jvmVendor = measuringJvm.vendor,
            jvmVersion = measuringJvm.version,
            heapSettings = measuringJvm.heapSettings,
            cpuModel = hostFacts.cpuModel,
            coreCount = hostFacts.coreCount,
            os = hostFacts.os,
            jmhMode = knobs.jmhMode,
            forkCount = knobs.forkCount,
            warmupIterations = knobs.warmupIterations,
            measurementIterations = knobs.measurementIterations,
            harnessCommitSha = harnessCommitSha,
        )
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
 * Thrown when the JMH knobs a run actually used cannot be established from the run's own
 * artifacts (`[BEN1-23]`, computenet-x9e.8).
 *
 * The sibling of [MeasuringJvmUnknownException], deliberately its own type for the same
 * reason and with the same posture: refusing to state a run parameter nobody verified
 * against the run, rather than answering it from something that is not the run. Where
 * that one refuses to substitute the RENDERING PROCESS's JVM, this one refuses to
 * substitute the BENCHMARK CLASS's declared annotation values — a different wrong source
 * for the same field of the same entry.
 */
class RunKnobsUnknownException(message: String) : IllegalStateException(message)

/**
 * The single distinct value of one JMH banner line across a whole log, or the empty list
 * when the log carries none.
 *
 * JMH repeats its banner per benchmark, so a well-formed sweep log holds the same value
 * many times and `distinct()` collapses that. More than one distinct value is left for
 * the caller to refuse — it means the log is not one run under one configuration, and
 * the refusal message differs by which fact was being established.
 */
private fun bannerValues(log: String, prefix: String): List<String> = log.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith(prefix) }
    .map { it.removePrefix(prefix).trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

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
         * More than one distinct value means the log is not one run on one JVM, and
         * there is then no measuring JVM to name — refused rather than resolved to the
         * first one seen, which is the same shape as `FindingsTable`'s
         * single-environment refusal.
         */
        private fun bannerValue(
            log: String,
            prefix: String,
            source: String,
            required: Boolean,
        ): String? {
            val values = bannerValues(log, prefix)
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

/**
 * The JMH configuration a run ACTUALLY used — mode, forks, warmup and measurement
 * iteration counts — read off the run's own log, never off the benchmark class's
 * annotations (`[BEN1-23]`, computenet-x9e.8).
 *
 * ## The defect this type exists to make impossible
 *
 * `computenet-hqid` closed this exact hole for the JVM triple: a findings entry's
 * `Harness:` line used to describe the process doing the RENDERING. The same shape
 * survived one field over. `ThroughputReport.renderRun` filled `RunEnvironment`'s four
 * knob fields from `ThroughputReport.JMH_MODE`/`FORKS`/`WARMUP_ITERATIONS`/
 * `MEASUREMENT_ITERATIONS` — `const val`s that mirror `OperatorThroughputBenchmark`'s
 * `@BenchmarkMode`/`@Fork`/`@Warmup`/`@Measurement` annotations — so every rendered entry
 * stated `mode=Throughput forks=2 warmup=5 iters=10` no matter what the sweep did.
 *
 * That is reachable and not theoretical, because **JMH's command-line flags override the
 * annotations**. `-f 1`, `-wi 1`, `-i 1` (the smoke invocation
 * `OperatorThroughputBenchmark`'s own KDoc documents) genuinely measure a different
 * configuration, and the entry would still have claimed the annotated one. No shipped
 * entry is known wrong this way — the three sweeps run before this change each used the
 * annotation config with no overriding flags, so their stated knobs happen to be true —
 * which is precisely why it was worth closing before a smoke sweep at `-f 1` got
 * published.
 *
 * ## The artifact this reads
 *
 * JMH writes its configuration to stdout, per benchmark, before running it:
 *
 * ```
 * # Warmup: 5 iterations, 1 s each
 * # Measurement: 10 iterations, 1 s each
 * # Threads: 1 thread, will synchronize iterations
 * # Benchmark mode: Throughput, ops/time
 * # Benchmark: civictech.bench.micro.OperatorThroughputBenchmark.simApplyDelta
 * # Fork: 1 of 2
 * ```
 *
 * Those lines are printed from JMH's own resolved `BenchmarkParams` — after flag
 * overrides are applied — which is what makes them a fact about the run rather than about
 * the source. They sit in the same log, captured by the same `| tee`, that
 * [MeasuringJvm.fromJmhLog] already reads; this adds no new artifact and no new
 * convention. `ThroughputReport.runLogFor` names where the renderer looks.
 *
 * ## Refusal, not defaulting
 *
 * Every one of the four fields comes from the log or [fromJmhLog] throws
 * [RunKnobsUnknownException]. There is no path back to the annotation constants: a log
 * with no `# Fork:` line, a `-f 0` run measuring inside the harness JVM, a `-wi 0` run
 * whose warmup JMH prints as `<none>`, or a log stating one knob two different ways are
 * all refusals. The same posture as the JVM triple, one field over, and for the identical
 * reason — an entry nobody can stand behind is not published.
 *
 * The limit is the one `ThroughputReport.runLogFor` already states: the log is paired to
 * the results file by NAME, and nothing cross-checks that they came from the same
 * invocation. This closes the accidental failure — a renderer answering from the source
 * because no artifact recorded the run — not a determined substitution.
 *
 * @param jmhMode JMH's benchmark mode as its `# Benchmark mode:` line states it, up to
 *   the metric clause that line appends (`Throughput, ops/time` -> `Throughput`): the
 *   metric is the unit, and a findings table's rows already carry that.
 * @param forkCount the total from the run's `# Fork: <n> of <total>` lines. Must be
 *   positive.
 * @param warmupIterations the count from the run's `# Warmup:` line. Must be positive.
 * @param measurementIterations the count from the run's `# Measurement:` line. Must be
 *   positive.
 */
data class RunKnobs(
    val jmhMode: String,
    val forkCount: Int,
    val warmupIterations: Int,
    val measurementIterations: Int,
) {
    init {
        require(jmhMode.isNotBlank()) { "jmhMode must not be blank" }
        require(forkCount > 0) { "forkCount must be positive, was $forkCount" }
        require(warmupIterations > 0) {
            "warmupIterations must be positive, was $warmupIterations"
        }
        require(measurementIterations > 0) {
            "measurementIterations must be positive, was $measurementIterations"
        }
    }

    companion object {

        /** JMH's banner line stating the resolved benchmark mode. */
        const val BENCHMARK_MODE_PREFIX: String = "# Benchmark mode:"

        /** JMH's banner line stating the resolved warmup iteration count and time. */
        const val WARMUP_PREFIX: String = "# Warmup:"

        /** JMH's banner line stating the resolved measurement iteration count and time. */
        const val MEASUREMENT_PREFIX: String = "# Measurement:"

        /** JMH's per-fork progress line, `<n> of <total>`. */
        const val FORK_PREFIX: String = "# Fork:"

        /** What JMH prints for an iteration phase configured with zero iterations. */
        const val NONE: String = "<none>"

        /** `<n> iterations, <time> each` — the shape of a warmup/measurement line. */
        private val ITERATION_COUNT = Regex("""^(\d+)\s+iterations?\b""")

        /** `<n> of <total>` — the shape of a fork progress line. */
        private val FORK_OF_TOTAL = Regex("""^(\d+)\s+of\s+(\d+)$""")

        /**
         * How to capture the log, quoted in every refusal so a reader learns the fix
         * rather than only the fault.
         */
        private const val TEE_HINT: String =
            "Re-run the sweep teeing its output beside the results file, e.g. `java -jar " +
                "bench/build/libs/bench-jmh.jar ... -rf csv -rff /abs/path/throughput.csv " +
                "2>&1 | tee /abs/path/throughput.log`"

        /**
         * Reads the knobs a run used off its JMH log, or refuses.
         *
         * @param log the full stdout of the JMH run that produced the results file.
         * @param source where [log] came from, named in every refusal message so a reader
         *   learns which file to go look at.
         * @throws RunKnobsUnknownException if any of the four knobs cannot be established
         *   from [log] — a missing banner line, a line in a shape that states no count
         *   (`<none>`, `N/A`), or one knob stated more than one way.
         */
        fun fromJmhLog(log: String, source: String): RunKnobs = RunKnobs(
            jmhMode = modeOf(log, source),
            forkCount = forkCountOf(log, source),
            warmupIterations = iterationsOf(log, WARMUP_PREFIX, source),
            measurementIterations = iterationsOf(log, MEASUREMENT_PREFIX, source),
        )

        /**
         * The one distinct value of a banner line, refusing on absence and on
         * disagreement.
         *
         * Disagreement is refused rather than resolved for the same reason
         * [MeasuringJvm.fromJmhLog] refuses two JVMs: a log concatenating benchmarks run
         * at different fork or iteration counts (`:bench`'s own benchmark classes declare
         * three different configurations) describes no single configuration, so there is
         * nothing for one entry's `JMH:` line to state.
         */
        private fun singleValue(log: String, prefix: String, source: String): String {
            val values = bannerValues(log, prefix)
            if (values.size > 1) {
                throw RunKnobsUnknownException(
                    "cannot establish the run's JMH configuration: $source states " +
                        "'$prefix' ${values.size} different ways ($values), so it is not " +
                        "one run under one configuration"
                )
            }
            return values.firstOrNull() ?: throw RunKnobsUnknownException(
                "cannot establish the run's JMH configuration: $source carries no " +
                    "'$prefix' line. That line is JMH's own banner, written to stdout " +
                    "from the parameters it resolved AFTER applying any command-line " +
                    "override, and it is the only record of what the run actually used — " +
                    "the results file carries no such columns, and the benchmark class's " +
                    "annotations state what was DECLARED, which `-f`/`-wi`/`-i` override. " +
                    TEE_HINT
            )
        }

        /** `Throughput, ops/time` -> `Throughput`; `Single shot invocation time` as-is. */
        private fun modeOf(log: String, source: String): String {
            val value = singleValue(log, BENCHMARK_MODE_PREFIX, source)
            val mode = value.substringBefore(',').trim()
            if (mode.isBlank()) {
                throw RunKnobsUnknownException(
                    "cannot establish the run's benchmark mode: $source states " +
                        "'$BENCHMARK_MODE_PREFIX $value', which names no mode before its " +
                        "first comma"
                )
            }
            return mode
        }

        /** The leading count of a `# Warmup:`/`# Measurement:` line. */
        private fun iterationsOf(log: String, prefix: String, source: String): Int {
            val value = singleValue(log, prefix, source)
            val count = ITERATION_COUNT.find(value)?.groupValues?.get(1)?.toIntOrNull()
            if (count == null || count <= 0) {
                throw RunKnobsUnknownException(
                    "cannot establish the run's iteration count: $source states " +
                        "'$prefix $value', which states no positive iteration count. JMH " +
                        "writes '$prefix $NONE' for a phase configured with none (`-wi 0` " +
                        "/ `-i 0`); such a run has no count to report, and a measurement " +
                        "with no measurement iterations is not one this entry can state"
                )
            }
            return count
        }

        /**
         * The total from the run's fork progress lines.
         *
         * JMH prints one line per fork (`1 of 2`, then `2 of 2`), so the values differ by
         * design and it is the TOTAL that must agree across them — that total is
         * `BenchmarkParams.getForks()`, i.e. the resolved `-f`, and it is the number a
         * findings entry means by `forks=`.
         */
        private fun forkCountOf(log: String, source: String): Int {
            val values = bannerValues(log, FORK_PREFIX)
            if (values.isEmpty()) {
                throw RunKnobsUnknownException(
                    "cannot establish the run's fork count: $source carries no " +
                        "'$FORK_PREFIX' line. JMH prints one per fork from the count it " +
                        "resolved, which `-f` overrides independently of the benchmark's " +
                        "`@Fork` annotation. " + TEE_HINT
                )
            }
            val totals = values.map { value ->
                FORK_OF_TOTAL.find(value)?.groupValues?.get(2)?.toIntOrNull()
                    ?: throw RunKnobsUnknownException(
                        "cannot establish the run's fork count: $source states " +
                            "'$FORK_PREFIX $value', which is not JMH's '<n> of <total>' " +
                            "form. JMH writes '$FORK_PREFIX N/A, test runs in the host " +
                            "VM' for an unforked run (`-f 0`), which measures inside the " +
                            "harness JVM and so has no fork count to state"
                    )
            }.distinct()
            if (totals.size > 1) {
                throw RunKnobsUnknownException(
                    "cannot establish the run's fork count: $source states fork totals " +
                        "$totals across its '$FORK_PREFIX' lines, so it is not one run " +
                        "under one configuration"
                )
            }
            return totals.single()
        }
    }
}

/**
 * Thrown when the host that PRODUCED a measurement cannot be established from the run's
 * own artifacts (`[BEN1-23]`, computenet-yhbd).
 *
 * The sibling of [MeasuringJvmUnknownException] and [RunKnobsUnknownException], with the
 * same posture: refusing to state a fact about the run from something that is not the
 * run, rather than falling back to whatever answers fastest. Where those two refuse to
 * substitute the RENDERING PROCESS's JVM and the BENCHMARK CLASS's declared knobs, this
 * one refuses to substitute the RENDERING PROCESS's CPU, core count and OS — the last of
 * the three fact-groups `[BEN1-23]` names, and the one no JMH artifact records at all.
 */
class HostFactsUnknownException(message: String) : IllegalStateException(message)

/**
 * The host CPU model, core count and OS the run's own artifacts recorded — read from
 * INSIDE the measuring fork, never from the process doing the reading (`[BEN1-23]`,
 * computenet-yhbd).
 *
 * ## The defect this closes, and why it could not be closed by parsing harder
 *
 * [MeasuringJvm] and [RunKnobs] both close their half of `[BEN1-23]` by reading a line
 * JMH already prints about the fork — `# VM version`/`# VM invoker`/`# VM options` for
 * the JVM, `# Benchmark mode`/`# Warmup`/`# Measurement`/`# Fork` for the knobs. No such
 * line exists for the host: JMH's banner states the JVM and the arguments it was
 * launched with, and nothing about the CPU, the core count or the OS. `RunEnvironment
 * .forRun`'s JMH-sweep overload used to fill exactly those three fields by calling
 * `System.getProperty`/`Runtime.getRuntime()`/`sysctl`/`/proc/cpuinfo` on ITS OWN
 * process — sound only under the undocumented-until-now assumption that rendering
 * happens on the machine that ran the sweep. A results file carried to another machine
 * and rendered there would carry that machine's CPU, core count and OS, silently and
 * plausibly, because the wrong value is a real machine's real facts.
 *
 * The fix is not a sharper parse of an existing artifact — none exists — but recording
 * the fact where it is true: [OperatorThroughputBenchmark]'s `GraphState` carries a
 * `@Setup(Level.Trial)` hook that calls [captureCurrent] and prints [bannerLines] to
 * stdout **from inside the measuring fork**, onto the exact same stream that is already
 * teed beside the results file for [MeasuringJvm.fromJmhLog] and [RunKnobs.fromJmhLog]
 * to read (`ThroughputReport.runLogFor`). No new artifact, no new file convention, and no
 * new place a caller has to remember to look — the run log already carries the JVM and
 * the knobs, and now carries the host too.
 *
 * ## Refusal is the only fallback
 *
 * [fromJmhLog] is the sole source `RunEnvironment.forRun`'s JMH-sweep overload accepts.
 * A log with no host-facts banner — a run captured before this change, a benchmark class
 * without the trial-level hook, or a results file whose log was lost — throws
 * [HostFactsUnknownException] rather than falling back to [captureCurrent]. That fallback
 * is the exact substitution this type exists to make unreachable: there is no code path
 * left in the JMH-sweep overload that can answer "which host measured this?" with "the
 * one asking".
 *
 * @param cpuModel the measuring host's CPU model/brand string.
 * @param coreCount the measuring host's available processor/core count. Must be
 *   positive.
 * @param os the measuring host's operating system name and version.
 */
data class HostFacts(
    val cpuModel: String,
    val coreCount: Int,
    val os: String,
) {
    init {
        require(cpuModel.isNotBlank()) { "cpuModel must not be blank" }
        require(coreCount > 0) { "coreCount must be positive, was $coreCount" }
        require(os.isNotBlank()) { "os must not be blank" }
    }

    companion object {

        /** The banner line this prints/reads for [cpuModel]. */
        const val CPU_MODEL_PREFIX: String = "# Host CPU model:"

        /** The banner line this prints/reads for [coreCount]. */
        const val CORE_COUNT_PREFIX: String = "# Host core count:"

        /** The banner line this prints/reads for [os]. */
        const val OS_PREFIX: String = "# Host OS:"

        /**
         * Captures THIS process's own host facts.
         *
         * Legal only when the caller IS the measuring host: [OperatorThroughputBenchmark]
         * calling it from inside a `@Setup(Level.Trial)` hook (where this process is the
         * measuring fork), or an in-process `@Tag("bench")` probe answering for itself
         * through `RunEnvironment.forRun`'s four-scalar overload. NOT legal for a JMH
         * sweep's renderer, which is why that path goes through [fromJmhLog] instead —
         * see [HostFacts]'s own KDoc for the defect this distinction exists to prevent.
         *
         * `sysctl -n machdep.cpu.brand_string` on darwin, `/proc/cpuinfo`'s `model name`
         * field on linux, for [cpuModel]. Neither source is queried on any other OS, and
         * either source failing (missing command, non-zero exit, blank output, missing
         * file, missing field) throws rather than returning a placeholder.
         *
         * @throws IllegalStateException if a host fact cannot be determined.
         */
        fun captureCurrent(): HostFacts {
            val osName = System.getProperty("os.name")
                ?: error("system property os.name is not set")
            val osVersion = System.getProperty("os.version")
                ?: error("system property os.version is not set")

            val coreCount = Runtime.getRuntime().availableProcessors()
            check(coreCount > 0) { "Runtime.availableProcessors() returned $coreCount" }

            return HostFacts(
                cpuModel = captureCpuModel(osName),
                coreCount = coreCount,
                os = "$osName $osVersion",
            )
        }

        /**
         * [bannerLines] parsed back — reads the host facts a run's own artifacts
         * recorded, or refuses.
         *
         * @param log the full stdout of the JMH run that produced the results file.
         * @param source where [log] came from, named in every refusal message so a
         *   reader learns which file to go look at.
         * @throws HostFactsUnknownException if the log carries no host-facts banner
         *   line, more than one distinct value for one, or a non-integer core count.
         */
        fun fromJmhLog(log: String, source: String): HostFacts {
            val cpuModel = singleValue(log, CPU_MODEL_PREFIX, source)
            val coreCountText = singleValue(log, CORE_COUNT_PREFIX, source)
            val os = singleValue(log, OS_PREFIX, source)
            val coreCount = coreCountText.toIntOrNull()
                ?: throw HostFactsUnknownException(
                    "cannot establish the run's host: $source states " +
                        "'$CORE_COUNT_PREFIX $coreCountText', which is not an integer " +
                        "core count"
                )
            return HostFacts(cpuModel = cpuModel, coreCount = coreCount, os = os)
        }

        private fun singleValue(log: String, prefix: String, source: String): String {
            val values = hostBannerValues(log, prefix, source)
            if (values.size > 1) {
                throw HostFactsUnknownException(
                    "cannot establish the run's host: $source states '$prefix' " +
                        "${values.size} different ways ($values), so it is not one run " +
                        "on one host"
                )
            }
            return values.firstOrNull() ?: throw HostFactsUnknownException(
                "cannot establish the run's host: $source carries no '$prefix' line. No " +
                    "JMH artifact records host facts on its own — this line is printed " +
                    "by the measuring benchmark's own `@Setup(Level.Trial)` hook, from " +
                    "inside the fork, onto the same stdout that is already teed beside " +
                    "the results file for the JVM triple and the JMH knobs. Re-run the " +
                    "sweep on a benchmark class that prints " +
                    "'${HostFacts::class.simpleName}' banner lines, teeing its output " +
                    "beside the results file, e.g. `java -jar " +
                    "bench/build/libs/bench-jmh.jar ... -rf csv -rff " +
                    "/abs/path/throughput.csv 2>&1 | tee /abs/path/throughput.log`"
            )
        }

        /** Every marker [hostBannerValues] recognizes, in the order they're printed. */
        private val HOST_BANNER_PREFIXES: List<String> =
            listOf(CPU_MODEL_PREFIX, CORE_COUNT_PREFIX, OS_PREFIX)

        /**
         * [bannerValues] for the host banner, matching the marker ANYWHERE in a line
         * rather than only at its start — which the shared helper cannot do, and which
         * this banner needs because it is the only one printed from inside the fork.
         *
         * JMH writes its progress prefix (`# Warmup Iteration   1: `) WITHOUT a trailing
         * newline and then relays the fork's stdout onto that same line, so the FIRST
         * line [bannerLines] prints does not begin at column 0. Measured on a 2-fork
         * sweep of `OperatorThroughputBenchmark` (2026-08-19, review of computenet-yhbd),
         * the run log read:
         *
         * ```
         * # Warmup Iteration   1: # Host CPU model: Apple M2 Pro
         * # Host core count: 10
         * # Host OS: Mac OS X 26.6.2
         * ```
         *
         * A `startsWith` match therefore lost exactly one fact — the CPU model — and
         * [fromJmhLog] refused every real sweep, telling the operator to re-run a sweep
         * that had in fact printed the banner correctly. `GraphState.announceHost`
         * additionally terminates JMH's pending line before printing, so a freshly
         * captured log is clean for a human reader; this parser is what keeps a log
         * captured before that (or by any other host-relaying harness) readable.
         *
         * [MeasuringJvm] and [RunKnobs] keep the strict `startsWith` form on purpose:
         * their lines are JMH's own, written by the HOST process, and never relayed.
         *
         * ANYWHERE-matching alone would also accept two markers FUSED onto one line with
         * no separator between them — e.g. `# Host CPU model: Apple M2 Pro# Host core
         * count: 10` — which is a different shape from a single relayed fact: here the
         * text taken as [prefix]'s value runs past it into the NEXT marker and its value,
         * corrupting the first fact rather than merely starting it mid-line
         * (computenet-x9e.11). [bannerLines] is the sole emitter of these markers and
         * always terminates each with `println`, so this shape cannot arise from two
         * already-printed lines fusing — only from a value swallowing a marker that
         * follows it on the same line — and is therefore always corruption, never a
         * second legitimate relay. So the extracted value is checked for any OTHER
         * known marker before being accepted, and the line is refused, not truncated,
         * when one is found.
         */
        private fun hostBannerValues(log: String, prefix: String, source: String): List<String> =
            log.lineSequence()
                .mapNotNull { line ->
                    val marker = line.indexOf(prefix)
                    if (marker < 0) return@mapNotNull null
                    val value = line.substring(marker + prefix.length).trim()
                    val fused = HOST_BANNER_PREFIXES.firstOrNull { other ->
                        other != prefix && value.contains(other)
                    }
                    if (fused != null) {
                        throw HostFactsUnknownException(
                            "cannot establish the run's host: $source fuses two host-fact " +
                                "markers onto one line with no separator ('$prefix' " +
                                "immediately followed by '$fused', with no line break " +
                                "between them): '$line'. This is not a fact relayed " +
                                "mid-line by JMH's progress prefix — it is two facts " +
                                "run together, and reading it would corrupt the value " +
                                "for '$prefix'."
                        )
                    }
                    value
                }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

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

    /**
     * The lines [captureCurrent]'s caller should print to stdout — one per fact, in the
     * shape [fromJmhLog] reads back.
     */
    fun bannerLines(): List<String> = listOf(
        "$CPU_MODEL_PREFIX $cpuModel",
        "$CORE_COUNT_PREFIX $coreCount",
        "$OS_PREFIX $os",
    )
}
