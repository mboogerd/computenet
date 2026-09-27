plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":demo:agora"))
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.deliberate.DeliberateAppKt"
}

// LiveSmokeTest runs only with DELIBERATE_LIVE=1; forward it and the Jev key
// explicitly rather than relying on the daemon's inherited environment.
tasks.test {
    for (name in listOf("DELIBERATE_LIVE", "TYPESAFE_API_KEY")) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
