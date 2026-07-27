plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.MainKt"
}
