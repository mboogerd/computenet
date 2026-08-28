plugins {
    // Same real-KSP pipeline as `:loader:fixtures:valid-basic` — see that module's
    // build file for why (epic computenet-051 risk 051-R7).
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    implementation(project(":kernel"))
    // `compileOnly`, deliberately: `RemovedBase` must be resolvable at compile
    // time but MUST NOT end up in this fixture's own jar or on its runtime
    // classpath — that is what makes the host build "not declare it any more"
    // real rather than simulated. Verify with `unzip -l` on the built jar: no
    // `civictech/nature/removed/` entry.
    compileOnly(project(":loader:fixtures:removed-api"))
}

// Module manifest attributes (see :loader:fixtures:valid-basic's build file for the
// names).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.missing-shared-type",
            "ComputeNet-Module-Version" to "1.0.0",
        )
    }
}
