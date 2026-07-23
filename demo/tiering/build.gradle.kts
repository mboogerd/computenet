plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.ksp)
    application
}

kotlin {
    sourceSets["main"].kotlin.srcDir("build/generated/ksp/main/kotlin")
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":gen"))
    ksp(project(":gen"))

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "civictech.demo.tiering.TieringAppKt"
}
