plugins {
    // Same real-KSP pipeline as `:loader:fixtures:valid-basic` — see that module's
    // build file for why (epic computenet-051 risk 051-R7). This fixture must be
    // KSP-built too: the whole point is that its `ContractTable_<hash>` is real
    // generator output that collides with valid-basic's at the registry, not a
    // hand-assembled one (computenet-9fqe).
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    implementation(project(":kernel"))
}

// Module manifest attributes, decided at feature level (computenet-051.3). See
// `:loader:fixtures:valid-basic`'s build file for the fuller comment.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.colliding-contract",
            "ComputeNet-Module-Version" to "1.0.0",
        )
    }
}
