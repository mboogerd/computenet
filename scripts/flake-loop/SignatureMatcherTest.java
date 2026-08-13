// Fixture test for computenet-dqy.62: SuiteLoop.matchesCollectorAnnouncedSignature
// must score computenet-dqy.34's signature 1 and computenet-dqy.40's catch-up-loss
// shape 0, even though the latter's diagnostic body quotes the word "announced".
//
// Not wired into any Gradle source set (scripts/flake-loop/ is deliberately outside
// all of them — see SuiteLoop.java's header), so this is run directly:
//
//   CP=$(./gradlew -q --no-configuration-cache \
//          -I scripts/flake-loop/print-test-classpath.init.gradle.kts \
//          :wire:printTestClasspath | grep -v '^WARNING' | grep -v '^$' | tr '\n' ':')
//   javac -cp "$CP" -d /tmp/sigtest \
//       scripts/flake-loop/SuiteLoop.java scripts/flake-loop/SignatureMatcherTest.java
//   java -cp "/tmp/sigtest:$CP" SignatureMatcherTest
//
// Exits 0 and prints "ALL PASSED" when every case matches; exits 1 and prints the
// failing case(s) otherwise.
public final class SignatureMatcherTest {

    // The exact retained artifact quoted verbatim in a 2026-08-13 comment on
    // computenet-dqy.40 ("RETAINED OCCURRENCE"), captured from
    // build/flake-loop-rv-checks/failures/flag-5-iter-1.txt during a real container
    // run. This is a genuine WsAnnouncementStressTest catch-up-loss failure message —
    // the shape the counter must NOT match.
    private static final String DQY40_SHAPE_MESSAGE = """
            announcement path: 1 failure(s) in 50 awaits over 25 iterations
            arrival latency ms: p50=26 p99=44 max=103
            --- iteration 17 (catch-up): never arrived within 15000ms
            awaited ref: CellRef(id=602abe3e-a073-44a3-afb1-baf8e3e5047c, instanceId=0)
            client location: null
            awaited ref on the server:
            Local(host=civictech.cell.host.ManagedHost@74518890)
            announced: server localRefs=3 -> client remoteRefs=2
            server localRefs: [CellRef(id=c40288cf-5523-4452-9144-1b2ca121026c, instanceId=0), CellRef(id=0e389b56-3d8b-4ade-b045-39c32e2ca592, instanceId=0), CellRef(id=602abe3e-a073-44a3-afb1-baf8e3e5047c, instanceId=0)]
            client remoteRefs: [CellRef(id=c40288cf-5523-4452-9144-1b2ca121026c, instanceId=0), CellRef(id=0e389b56-3d8b-4ade-b045-39c32e2ca592, instanceId=0)]
            client localRefs: [CellRef(id=2b82e1e6-6f21-4035-84bf-6ee523f06393, instanceId=0), CellRef(id=360d0845-34c9-4b82-8fb1-7bd54e6ccde6, instanceId=0)]
            parked for awaited ref: 0
            silent drops: client preHello=0 gate=0 / listener preHello=0 gate=0""";

    public static void main(String[] args) {
        int failures = 0;

        // computenet-dqy.34's own signature (testkit's AwaitUntil.await("collector
        // announced")): must score 1.
        failures += expect(true,
                "timed out awaiting: collector announced",
                "dqy.34 exact signature");

        // Same family, different await site names (WsTransportSmokeTest /
        // WsThreadEntryConformanceTest use "collector announced to client";
        // WsPeerIdentityTest also uses "collector announced to the dialer") — dqy.34's
        // own bead groups these under the same ~1% flake, so they must also score 1.
        failures += expect(true,
                "timed out awaiting: collector announced to client",
                "dqy.34 family: \"to client\" variant");
        failures += expect(true,
                "timed out awaiting: collector announced to the dialer",
                "dqy.34 family: \"to the dialer\" variant");

        // THE REGRESSION THIS BEAD IS ABOUT: computenet-dqy.40's catch-up-loss shape,
        // verbatim from the retained artifact. It contains "announced" (in the
        // "announced: server localRefs=..." diagnostic line) but is not dqy.34's
        // signature, and must score 0.
        failures += expect(false,
                DQY40_SHAPE_MESSAGE,
                "dqy.40 catch-up-loss shape (retained artifact)");

        // A message that merely mentions the word "announced" elsewhere must not
        // match either — this is the exact shape of bug computenet-dqy.62 reported
        // for the old `msg.contains("announced")` check.
        failures += expect(false,
                "collector announced to client, eventually, but too late",
                "word present, not as the dqy.34 prefix");
        failures += expect(false,
                null,
                "null message");

        if (failures == 0) {
            System.out.println("ALL PASSED");
        } else {
            System.out.println(failures + " CASE(S) FAILED");
            System.exit(1);
        }
    }

    private static int expect(boolean want, String msg, String label) {
        boolean got = SuiteLoop.matchesCollectorAnnouncedSignature(msg);
        boolean ok = got == want;
        System.out.printf("[%s] %s: expected=%s actual=%s%n", ok ? "PASS" : "FAIL", label, want, got);
        return ok ? 0 : 1;
    }
}
