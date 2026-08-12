// SuiteLoop — an in-process JUnit Platform repetition harness for measuring rare
// test flakes, built for computenet-dqy.38 (Linux sample of the
// "timed out awaiting: collector announced" rate that computenet-dqy.34 measured
// only on macOS).
//
// Why not Gradle: `./gradlew :wire:test --rerun` costs ~40s per sample, almost all
// of it daemon and configuration overhead. Driving the Launcher directly in one JVM
// runs the same suite in a few seconds, which is what makes a few hundred samples
// affordable.
//
// Why it writes a file per failure: a rare `:wire` failure otherwise destroys its
// own evidence, because the next run overwrites `wire/build/test-results/test`
// (computenet-58m, and computenet-dqy.34 lost a run to exactly that). Every failing
// iteration here gets its own append-only record with the full stack trace.
//
// Usage:
//   java -cp <test-runtime-classpath> SuiteLoop.java \
//        --package civictech.wire --runs 400 --out /path/to/evidence \
//        [--label linux] [--expect-tests 14]
//
// Output: one progress line per iteration on stdout, a `failures/` file per failing
// iteration, and a final SUMMARY line with the sample size and failure count.
//
// Reading the SUMMARY, because two of its fields are easy to misread:
//   failingTests                  every failure, whatever it was. This is the field
//                                 that answers "did the suite stay green".
//   collectorAnnouncedSignature   ONLY computenet-dqy.34's message ("... collector
//                                 announced"). computenet-dqy.40's lost announcement
//                                 does not match it and scores 0 — so a run can have
//                                 failingTests=1 collectorAnnouncedSignature=0 and
//                                 still be an announcement loss.
//   unexpectedTestCountIterations with --expect-tests, iterations whose executed test
//                                 count differed from the expected one. Anything but
//                                 0 means the sample is not what it claims to be
//                                 (a class that failed to initialise, a selector that
//                                 stopped matching), so a zero-failure result from a
//                                 run with a nonzero count here proves nothing.
//                                 :wire is 14 on Linux, 15 on macOS (WsListenerAcceptRstTest
//                                 is @EnabledOnOs(MAC)).
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class SuiteLoop {

    record Failure(String testId, String displayPath, Throwable cause) {}

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[i];
    }

    public static void main(String[] args) throws Exception {
        String pkg = "civictech.wire";
        int runs = 100;
        Path out = Path.of("suite-loop-evidence");
        String label = "run";
        int expectTests = -1;

        // Unknown or value-less flags are fatal on purpose: a silently ignored
        // `--run 2000` would produce a 100-iteration sample that still looks like a
        // measurement, and a wrong n is worse than no n.
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--package" -> pkg = value(args, ++i, "--package");
                case "--runs" -> runs = Integer.parseInt(value(args, ++i, "--runs"));
                case "--out" -> out = Path.of(value(args, ++i, "--out"));
                case "--label" -> label = value(args, ++i, "--label");
                case "--expect-tests" -> expectTests = Integer.parseInt(value(args, ++i, "--expect-tests"));
                default -> throw new IllegalArgumentException(
                        "unknown argument: " + args[i]
                                + " (expected --package/--runs/--out/--label/--expect-tests)");
            }
        }

        Path failureDir = out.resolve("failures");
        Files.createDirectories(failureDir);
        Path log = out.resolve(label + ".log");

        System.out.printf("SuiteLoop label=%s package=%s runs=%d out=%s java=%s os=%s/%s%n",
                label, pkg, runs, out.toAbsolutePath(),
                System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));

        int totalFailingIterations = 0;
        int totalFailingTests = 0;
        int signatureMatches = 0;
        int shortIterations = 0;
        Instant started = Instant.now();
        StringBuilder logBuf = new StringBuilder();

        for (int iteration = 1; iteration <= runs; iteration++) {
            List<Failure> failures = new ArrayList<>();
            AtomicInteger executed = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectPackage(pkg))
                    .build();

            Launcher launcher = LauncherFactory.create();
            launcher.registerTestExecutionListeners(new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                    if (id.isTest()) executed.incrementAndGet();
                    // Container failures (a @BeforeAll blowing up, an engine-level
                    // error) are recorded too — otherwise an iteration that never ran
                    // its tests reports failures=0 and the zero means nothing.
                    if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                        failures.add(new Failure(id.getUniqueId(),
                                id.isTest() ? id.getDisplayName() : id.getDisplayName() + " [container]",
                                result.getThrowable().orElse(null)));
                    }
                }

                @Override
                public void executionSkipped(TestIdentifier id, String reason) {
                    if (id.isTest()) skipped.incrementAndGet();
                }
            });

            Instant t0 = Instant.now();
            launcher.execute(request);
            long ms = Duration.between(t0, Instant.now()).toMillis();

            boolean short_ = expectTests >= 0 && executed.get() != expectTests;
            if (short_) shortIterations++;
            String line = String.format("%s iter=%d tests=%d skipped=%d failures=%d %dms%s",
                    label, iteration, executed.get(), skipped.get(), failures.size(), ms,
                    short_ ? " UNEXPECTED-TEST-COUNT expected=" + expectTests : "");
            System.out.println(line);
            System.out.flush();
            logBuf.append(line).append('\n');

            if (!failures.isEmpty()) {
                totalFailingIterations++;
                totalFailingTests += failures.size();
                StringBuilder sb = new StringBuilder();
                sb.append(line).append("\n\n");
                for (Failure f : failures) {
                    sb.append("TEST: ").append(f.displayPath()).append('\n');
                    sb.append("ID:   ").append(f.testId()).append('\n');
                    Throwable c = f.cause();
                    if (c != null) {
                        String msg = String.valueOf(c.getMessage());
                        // Narrow, deliberate: computenet-dqy.34's signature only.
                        // Other announcement losses do NOT match it — computenet-dqy.40's
                        // WsAnnouncementStressTest failure says "announcement path: N
                        // failure(s)" and scores 0 here. Read failingTests for "did
                        // anything fail", this counter for "was it dqy.34's signature".
                        if (msg.contains("announced")) signatureMatches++;
                        StringWriter sw = new StringWriter();
                        c.printStackTrace(new PrintWriter(sw));
                        sb.append(sw).append('\n');
                    }
                    sb.append("----\n");
                }
                Path evidence = failureDir.resolve(label + "-iter-" + iteration + ".txt");
                Files.writeString(evidence, sb.toString(), StandardCharsets.UTF_8);
                System.out.println("FAILURE EVIDENCE -> " + evidence.toAbsolutePath());
                System.out.print(sb);
                System.out.flush();
            }

            if (iteration % 25 == 0) {
                Files.writeString(log, logBuf.toString(), StandardCharsets.UTF_8);
            }
        }

        long elapsed = Duration.between(started, Instant.now()).toSeconds();
        String summary = String.format(
                "SUMMARY label=%s runs=%d failingIterations=%d failingTests=%d collectorAnnouncedSignature=%d"
                        + " unexpectedTestCountIterations=%d elapsedSeconds=%d",
                label, runs, totalFailingIterations, totalFailingTests, signatureMatches, shortIterations, elapsed);
        System.out.println(summary);
        logBuf.append(summary).append('\n');
        Files.writeString(log, logBuf.toString(), StandardCharsets.UTF_8);
    }
}
