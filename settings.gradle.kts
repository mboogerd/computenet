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
include(":gen-test")

include(":kernel")
include(":wire")
include(":demo:shopping")
include(":demo:agora")
include(":demo:slotfinder")
include(":demo:skillmatch")
include(":demo:tiering")

rootProject.name = "computenet"

