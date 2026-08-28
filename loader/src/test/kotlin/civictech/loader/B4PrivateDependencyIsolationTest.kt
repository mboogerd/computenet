package civictech.loader

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Scenario B4 of feature computenet-051.1 — **private dependency isolation**
 * `[JAR1-ISO-04][JAR1-ISO-07]`.
 *
 * `:loader:fixtures:util-a` and `:loader:fixtures:util-b` each bundle their own build of
 * `com.example.Util`, same fully-qualified name, different `tag()`. `com.example.` matches
 * no shared prefix, so each module must resolve **its own** copy: that is the guarantee
 * that lets two modules depend on incompatible versions of the same library, which is the
 * reason a per-module classloader exists at all.
 *
 * The assertions are deliberately in both directions — each loader sees its own build
 * **and** neither resolution changes the other's — because a delegation bug that let the
 * first loader win would still satisfy a one-sided check, and the failure it produces
 * (whichever module happens to load first silently wins) is order-dependent and therefore
 * exactly the kind that survives a weak test.
 */
class B4PrivateDependencyIsolationTest {

    private companion object {
        const val UTIL_FQN = "com.example.Util"

        fun fixtureJar(property: String): File {
            val path = System.getProperty(property)
                ?: error(
                    "System property '$property' is not set. It must be wired in " +
                        "loader/build.gradle.kts on the :loader `test` task."
                )
            return File(path).also {
                check(it.isFile) { "$property points at ${it.absolutePath}, which is not a file" }
            }
        }

        val utilAJar: File get() = fixtureJar("loader.fixture.utilA")
        val utilBJar: File get() = fixtureJar("loader.fixture.utilB")

        /** Invoke the no-arg `tag()` on a fresh instance of [util], reflectively. */
        fun tagOf(util: Class<*>): String =
            util.getDeclaredMethod("tag").invoke(util.getDeclaredConstructor().newInstance()) as String
    }

    @Test
    fun `each module resolves its own build of a non-shared class of the same FQN`() {
        ModuleClassLoader.open(utilAJar).use { a ->
            ModuleClassLoader.open(utilBJar).use { b ->
                val utilFromA = a.loadClass(UTIL_FQN)
                val utilFromB = b.loadClass(UTIL_FQN)

                withClue("$UTIL_FQN is not on any shared prefix, so the two must be different Classes") {
                    (utilFromA === utilFromB) shouldBe false
                }
                withClue("each must be defined by the loader whose jar carries it [JAR1-ISO-04]") {
                    utilFromA.classLoader shouldBe a
                    utilFromB.classLoader shouldBe b
                }
                withClue("and each must observe its OWN build's behaviour [JAR1-ISO-07]") {
                    tagOf(utilFromA) shouldBe "A"
                    tagOf(utilFromB) shouldBe "B"
                }
            }
        }
    }

    @Test
    fun `resolution order does not decide which build a module sees`() {
        // The reverse of the previous test's open/resolve order. A child-first bug that
        // let the first-loaded copy win would pass one of these two and fail the other.
        ModuleClassLoader.open(utilBJar).use { b ->
            tagOf(b.loadClass(UTIL_FQN)) shouldBe "B"

            ModuleClassLoader.open(utilAJar).use { a ->
                withClue("A's resolution must not be affected by B having already resolved the name") {
                    tagOf(a.loadClass(UTIL_FQN)) shouldBe "A"
                }
                withClue("and B's must not be affected by A's") {
                    tagOf(b.loadClass(UTIL_FQN)) shouldBe "B"
                }
            }
        }
    }

    @Test
    fun `the module jar is searched before the parent, not merely as a fallback`() {
        // THE discriminating assertion for [JAR1-ISO-04]. Every other test in this file
        // passes just as well under ordinary parent-first delegation, because no fixture
        // jar shares a non-shared FQN with the test's own classpath — parent-first would
        // simply miss and fall through to the jar.
        //
        // Chaining the loaders creates the case that tells them apart: util-a's loader is
        // parented to util-b's, so `com.example.Util` exists in BOTH the child's jar and
        // the parent's. Child-first answers "A"; parent-first answers "B". Mutation-checked
        // 2026-08-28 by reordering `loadClass`'s non-shared branch to consult the parent
        // first — this assertion is the one that fails.
        ModuleClassLoader.open(utilBJar).use { parent ->
            ModuleClassLoader.open(utilAJar, parent = parent).use { child ->
                withClue("the child's own jar must win over a parent that also defines the name") {
                    tagOf(child.loadClass(UTIL_FQN)) shouldBe "A"
                }
                withClue("and the class must be defined by the child, not inherited") {
                    child.loadClass(UTIL_FQN).classLoader shouldBe child
                }
                withClue("a shared-prefix type must STILL come from the host through the chain") {
                    (child.loadClass("civictech.cell.Cell") === civictech.cell.Cell::class.java) shouldBe true
                }
            }
        }
    }

    @Test
    fun `a module cell compiled against its own bundled build observes that build`() {
        // The end-to-end shape of ISO-07: not a bare `loadClass` of the utility, but a
        // module class whose own bytecode references it. UtilACell.bundledTag() calls
        // Util().tag(), and the reference is resolved through UtilACell's defining loader.
        ModuleClassLoader.open(utilAJar).use { a ->
            ModuleClassLoader.open(utilBJar).use { b ->
                val cellA = a.loadClass("civictech.loader.fixture.utila.UtilACell")
                val cellB = b.loadClass("civictech.loader.fixture.utilb.UtilBCell")

                val tagA = cellA.getDeclaredMethod("bundledTag")
                    .invoke(cellA.getDeclaredConstructor().newInstance()) as String
                val tagB = cellB.getDeclaredMethod("bundledTag")
                    .invoke(cellB.getDeclaredConstructor().newInstance()) as String

                tagA shouldBe "A"
                tagB shouldBe "B"
            }
        }
    }
}
