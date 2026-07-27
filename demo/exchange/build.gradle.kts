plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":wire"))
    implementation(project(":nature")) // PN-15: the Manifest nature vocabulary for the composed-manifest assertion
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.exchange.MainKt"
}
