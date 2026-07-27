plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

// ponytail: the WebSocket dependency lives here so :kernel stays dependency-free;
// another transport = another small module behind the same bridge cells.
dependencies {
    implementation(project(":kernel"))
    implementation(libs.java.websocket)
}
