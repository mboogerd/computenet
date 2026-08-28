plugins {
    // Deliberately plain `kotlin-jvm`, not `ksp-cell`, and with NO dependency on :kernel.
    //
    // The feature's breakdown flagged as unverified whether Kotlin would even compile a
    // local `package civictech.cell; class Cell` with :kernel on the compile classpath.
    // Measured 2026-08-28 (`:loader:fixtures:smuggler:compileKotlin` with
    // `implementation(project(":kernel"))` added): it compiles, with no error and no
    // warning — the local declaration simply shadows the kernel's interface of the same
    // name. So the "if Kotlin refuses" branch never fired.
    //
    // The shape below was chosen for a different reason, found while checking that one.
    // `ContractProcessor` locates cells by resolving `civictech.cell.Cell` and collecting
    // everything assignable from it (gen/.../ContractProcessor.kt, `KernelFqn.CELL_MARKER`).
    // Inside THIS module that name resolves to the smuggled class, so a `ksp-cell`
    // smuggler would emit a CellDescriptor for the impostor and the fixture would be
    // testing the generator's confusion rather than the loader's rejection.
    //
    // Nothing is lost: the rejection under test (JAR1-ISO-08, a sibling task of feature
    // computenet-051.1) happens at CLASSLOADER level, before anything looks for a
    // ContractModule. A smuggle jar needs no contract. Discovery and registration are
    // feature computenet-051.3.
    id("buildsrc.convention.kotlin-jvm")
}
