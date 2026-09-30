plugins {
    // Generated typed port ids for the routed wiring (computenet-jnkvu).
    id("buildsrc.convention.ksp-cell")
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    implementation(project(":demo:agora"))
    implementation(libs.kotlinx.serialization)
    // opt-in `--inspect-port`, see `InspectorFlag` (computenet-3iv0w)
    implementation(project(":inspect"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.deliberate.DeliberateAppKt"
}

// LiveSmokeTest runs only with DELIBERATE_LIVE=1 and CalibrationTest only with
// DELIBERATE_CALIBRATE=1; forward those and the Jev key explicitly rather than
// relying on the daemon's inherited environment.
tasks.test {
    for (name in listOf("DELIBERATE_LIVE", "DELIBERATE_CALIBRATE", "DELIBERATE_CALIBRATE_REGEN", "DELIBERATE_GOLDEN_REGEN", "TYPESAFE_API_KEY")) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
