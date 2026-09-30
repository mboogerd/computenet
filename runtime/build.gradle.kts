plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
}

// :runtime is the composition root: it is deliberately the one module that
// knows every concrete transport and the optional inspector/economy bindings.
// The kernel and each binding stay unaware of this module.
dependencies {
    api(project(":kernel"))
    implementation(project(":wire"))
    implementation(project(":iroh"))
    implementation(project(":inspect"))
    implementation(project(":economy"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

// The sidecar task exists only on the same opt-in path in :iroh. On the
// default path no cargo task is referenced, no system property is set, and
// TwoNodesOverIrohTest reports SKIPPED.
if (project.hasProperty("iroh.enabled")) {
    val sidecarBinary = File(rootDir, "iroh/sidecar/target/debug/computenet-iroh-sidecar")
    tasks.withType<Test>().configureEach {
        dependsOn(":iroh:cargoBuild")
        systemProperty("iroh.sidecar.binary", sidecarBinary.absolutePath)
    }
}
