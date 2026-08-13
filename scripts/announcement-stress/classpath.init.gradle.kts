// Prints :wire's test runtime classpath so the standalone stress entry point can
// be launched with a plain `java -cp`. Used as
// `./gradlew -I scripts/announcement-stress/classpath.init.gradle.kts -q :wire:printTestRuntimeClasspath`;
// kept as an init script so :wire's own build file stays free of harness-only tasks.
//
// The null-check matters: an init script is applied to the `buildSrc` build too,
// and that build has no `:wire`, so a `first { }` here fails the whole build with
// "Collection contains no element matching the predicate" (measured).
gradle.projectsEvaluated {
    val wire = gradle.rootProject.subprojects.find { it.path == ":wire" } ?: return@projectsEvaluated
    val cp = wire.extensions
        .getByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
        .sourceSets.getByName("test").runtimeClasspath
    wire.tasks.register("printTestRuntimeClasspath") {
        dependsOn(wire.tasks.named("testClasses"))
        val path = cp.asPath
        doLast { println(path) }
    }
}
