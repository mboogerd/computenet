plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.ksp)
}

group = "civictech"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(project(":gen"))
    ksp(project(":gen"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}