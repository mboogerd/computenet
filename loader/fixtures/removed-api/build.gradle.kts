plugins {
    // Plain kotlin-jvm: this module exists only to be REMOVED from
    // `:loader:fixtures:missing-shared-type`'s runtime classpath — see that
    // module's build file. It is never wired into `loader/build.gradle.kts`'s
    // `fixtureJarProperties`, so it never reaches the :loader test JVM as a
    // system property, and it carries no manifest attributes: it is not itself
    // a loadable module.
    id("buildsrc.convention.kotlin-jvm")
}
