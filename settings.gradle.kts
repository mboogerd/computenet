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
//include(":runtime")

rootProject.name = "computenet"

