plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "civictech.demo.exchange.MainKt"
}
