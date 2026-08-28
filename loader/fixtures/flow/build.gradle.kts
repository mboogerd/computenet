plugins {
    // The whole point of this module: the fixture jar is built by the SAME KSP
    // pipeline every cell-authoring module in the repository uses, so the
    // `META-INF/services/civictech.nature.ContractModule` entry inside the jar is
    // `ContractProcessor`'s output rather than a checked-in file. Epic
    // computenet-051 risk 051-R7 forbids the hand-assembled alternative, and
    // `civictech.loader.FixtureJarsTest` is what holds this honest.
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    // `civictech.cell.data.SetCellBase` (the `@CellBase`-generated base this
    // fixture's two cells extend, since `civictech.cell.data.SetCell` itself is
    // `final`), plus the shared `SetOps`/`SetDelta`/`Propagate` contract and
    // payload types both cells' ports carry — reused verbatim, not
    // re-declared, so cross-loader `Class` identity is guaranteed by
    // `ModuleClassLoader` parent delegation (computenet-051.5.2).
    implementation(project(":kernel"))
}

// Module manifest attributes, decided at feature level (computenet-051.3):
// `ComputeNet-Module-Id` / `ComputeNet-Module-Version`. Documented for real in
// :loader's own KDoc (the load-path task); recorded here, next to the fixture
// jar property map's other reference in loader/build.gradle.kts, so a reader
// of any one fixture's build file can find the names without cross-referencing.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.flow",
            "ComputeNet-Module-Version" to "1.0.0",
        )
    }
}
