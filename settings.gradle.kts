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
// :bench is infrastructure, not an application — benchmark sources for the repository,
// depended on by nothing. That is why it sits next to :testkit rather than under demo/
// ([BEN1-01]).
include(":bench")
include(":oracle")

include(":kernel")
include(":concord")
include(":wire")
include(":identity")
include(":inspect")
include(":demo:shell")
include(":demo:shopping")
include(":demo:exchange")
include(":demo:agora")
include(":demo:beadsmirror")
include(":demo:dialogue")
include(":demo:slotfinder")
include(":demo:skillmatch")
include(":demo:tiering")
include(":demo:backlog-triage")

rootProject.name = "computenet"

