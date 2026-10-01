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
        // computenet-o0m3.3: forward -Piroh.relay.url=<url>, when given, as the
        // same-named JVM system property SidecarProcess.spawn reads to steer
        // every spawned sidecar onto one relay. Absent the -P flag, no property
        // is set and spawn args are unchanged.
        if (project.hasProperty("iroh.relay.url")) {
            systemProperty("iroh.relay.url", project.property("iroh.relay.url") as String)
        }
        // computenet-vnscs F2-D5/F2-D8: same idiom, for the rendezvous flags
        // SidecarProcess.effectiveArgs steers on. -Piroh.pkarr.url and
        // -Piroh.dns.origin are required together by that steering; passing
        // only one through here reproduces the JVM's own refusal rather than
        // hiding it. -Piroh.dns.nameserver is optional.
        if (project.hasProperty("iroh.pkarr.url")) {
            systemProperty("iroh.pkarr.url", project.property("iroh.pkarr.url") as String)
        }
        if (project.hasProperty("iroh.dns.origin")) {
            systemProperty("iroh.dns.origin", project.property("iroh.dns.origin") as String)
        }
        if (project.hasProperty("iroh.dns.nameserver")) {
            systemProperty("iroh.dns.nameserver", project.property("iroh.dns.nameserver") as String)
        }
    }
}
