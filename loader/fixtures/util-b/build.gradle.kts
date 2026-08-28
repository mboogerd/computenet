plugins {
    // Same real-KSP pipeline as `:loader:fixtures:valid-basic` — see that module's
    // build file for why (epic computenet-051 risk 051-R7).
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    implementation(project(":kernel"))
}
