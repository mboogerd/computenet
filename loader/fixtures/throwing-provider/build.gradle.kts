plugins {
    // Same real-KSP pipeline as `:loader:fixtures:valid-basic` — see that module's
    // build file for why (epic computenet-051 risk 051-R7). The VALID
    // ContractModule this pipeline generates is the atomicity probe for
    // ERR-03: a failed load (because the hand-written WireSerializers provider
    // below throws) must register nothing, including this contract.
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    implementation(project(":kernel"))
    // civictech.cell.wire.WireSerializers.module's type — :kernel declares this
    // `implementation`, not `api`, so it does not reach this fixture transitively.
    implementation(libs.kotlinx.serialization)
}

// Module manifest attributes (see :loader:fixtures:valid-basic's build file for the
// names).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.throwing-provider",
            "ComputeNet-Module-Version" to "1.0.0",
        )
    }
}
