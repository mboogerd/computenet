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
    implementation(project(":kernel"))
}
