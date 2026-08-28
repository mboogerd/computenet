plugins {
    // Deliberately NOT `ksp-cell`: DISC-05's fixture is a module that loads
    // successfully and contributes zero descriptors, so it must carry no
    // `@Contract`/`Cell` at all, and no generated `META-INF/services` entry.
    id("buildsrc.convention.kotlin-jvm")
}

// Module manifest attributes (see :loader:fixtures:valid-basic's build file for the
// names). The version string here is the DISC-04 fixture: it is not a version any
// scheme would produce, and the load path must record it verbatim, uninterpreted.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.empty-module",
            "ComputeNet-Module-Version" to "not a version, recorded verbatim",
        )
    }
}
