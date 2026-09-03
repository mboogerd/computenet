package civictech.demo.beadsmirror

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * The skip gate for every test in this module that spawns an iroh sidecar
 * (task `computenet-egl.4.1`; feature `computenet-egl.4` rule 5).
 *
 * `demo/beadsmirror/build.gradle.kts` sets the system property
 * `iroh.sidecar.binary` to the flag-built debug binary's path, and ONLY when
 * `-Piroh.enabled=true` is passed. That property is the single channel by
 * which a test locates the sidecar: deliberately no search of `target/debug`,
 * no `PATH` lookup, no environment variable — so "the flag was not set" and
 * "the crate was not built" are one observable state that no stale artifact
 * lying around can paper over.
 *
 * ## Why this duplicates `:iroh`'s `SidecarBinary` instead of importing it
 *
 * `civictech.iroh.SidecarBinary` is TEST-scoped in `:iroh` and therefore not
 * on this module's classpath. The two ways to share it are a `testFixtures`
 * source set on `:iroh` or a `testArtifacts` configuration — both add a
 * permanent build surface to a module whose whole design claim is that it is
 * small. egl.4.1 decided the ~20 duplicated lines are the cheaper of the two.
 * If a third module ever needs the gate, that is the moment to reconsider;
 * two is not.
 */
object IrohSidecarGate {

    const val PROPERTY: String = "iroh.sidecar.binary"

    /** The configured binary, or `null` when the property is unset or names no file. */
    fun locate(): Path? {
        val configured = System.getProperty(PROPERTY) ?: return null
        val path = Path.of(configured)
        return if (Files.isRegularFile(path)) path else null
    }

    /**
     * The sidecar binary, or a JUnit assumption failure — a SKIPPED test, never
     * a failed one — when it is not there.
     */
    fun orSkip(): Path {
        val configured = System.getProperty(PROPERTY)
        val path = locate()
        assumeTrue(
            path != null,
            {
                if (configured == null) {
                    "no $PROPERTY: the iroh sidecar was not built. Run with -Piroh.enabled=true (needs cargo)."
                } else {
                    "$PROPERTY=$configured names no existing file; run with -Piroh.enabled=true (needs cargo)."
                }
            },
        )
        return path!!
    }
}
