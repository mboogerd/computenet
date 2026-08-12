// Gradle init script: prints a module's test runtime classpath, one entry per line,
// so an out-of-Gradle harness (scripts/flake-loop) can launch the JUnit Platform
// directly. Read-only — it adds a task and touches no build file.
//
//   ./gradlew --no-configuration-cache \
//     -I scripts/flake-loop/print-test-classpath.init.gradle.kts \
//     :wire:printTestClasspath -q
gradle.allprojects {
    tasks.register("printTestClasspath") {
        doLast {
            val test = project.tasks.findByName("test") as? Test
                ?: error("no `test` task on ${project.path}")
            test.classpath.files.forEach { println(it.absolutePath) }
        }
    }
}
