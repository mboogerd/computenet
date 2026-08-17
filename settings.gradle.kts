dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":gen")
include(":nature")
include(":testkit")
include(":oracle")

include(":kernel")
include(":concord")
include(":wire")
include(":inspect")
include(":demo:shell")
include(":demo:shopping")
include(":demo:exchange")
include(":demo:agora")
include(":demo:beadsmirror")
include(":demo:slotfinder")
include(":demo:skillmatch")
include(":demo:tiering")
include(":demo:backlog-triage")

rootProject.name = "computenet"

