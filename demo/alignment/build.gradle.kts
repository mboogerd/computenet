plugins {
    id("buildsrc.convention.ksp-cell")
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(libs.kotlinx.serialization)
    implementation(project(":demo:shell"))

    testImplementation(project(":testkit"))
}

application {
    // AlignmentApp.kt (the `main` this names) lands with the sibling app task,
    // computenet-sigl0.2; the application plugin resolves it only at run time.
    mainClass = "civictech.demo.alignment.AlignmentAppKt"
}
