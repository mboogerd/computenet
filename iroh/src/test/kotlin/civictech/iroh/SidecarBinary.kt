package civictech.iroh

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * The skip gate for every test that spawns a sidecar (feature `computenet-egl.2`
 * rule 5; this module owns the mechanism).
 *
 * `iroh/build.gradle.kts` sets the system property `iroh.sidecar.binary` to the
 * flag-built debug binary's path, and ONLY when `-Piroh.enabled=true` is passed.
 * That property is the single channel by which a test locates the sidecar: there
 * is deliberately no search of `target/debug`, no `PATH` lookup and no
 * environment variable, so "the flag was not set" and "the crate was not built"
 * are the same observable state and neither can be papered over by a stale
 * artifact lying around.
 *
 * Codec tests do not call this and run unconditionally on the default lanes.
 */
object SidecarBinary {

    const val PROPERTY: String = "iroh.sidecar.binary"

    /** The configured binary, or `null` when the property is unset or names no file. */
    fun locate(): Path? {
        val configured = System.getProperty(PROPERTY) ?: return null
        val path = Path.of(configured)
        return if (Files.isRegularFile(path)) path else null
    }

    /**
     * The sidecar binary, or a JUnit assumption failure — a SKIPPED test, never a
     * failed one — when it is not there.
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
