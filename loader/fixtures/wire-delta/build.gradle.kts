plugins {
    // Deliberately NOT `ksp-cell`: this fixture carries no `@Contract`/`Cell` —
    // its whole point is a module that contributes a `WireSerializers` table
    // for its own delta type, and `ContractProcessor` never emits a
    // `META-INF/services/civictech.cell.wire.WireSerializers` entry regardless
    // (see `:loader:fixtures:throwing-provider`'s KDoc — the same reason that
    // fixture's services entry is hand-written under src/main/resources rather
    // than generated). Same shape as `:loader:fixtures:empty-module`.
    id("buildsrc.convention.kotlin-jvm")
    // This fixture's whole point is an `@Serializable` delta type — the compiler
    // plugin that generates its `.serializer()` companion extension, applied the
    // same way :kernel applies it for its own main sources.
    alias(libs.plugins.kotlin.plugin.serialization)
}

dependencies {
    implementation(project(":kernel"))
    // civictech.cell.wire.WireSerializers.module's type — :kernel declares this
    // `implementation`, not `api`, so it does not reach this fixture transitively
    // (same note as :loader:fixtures:throwing-provider's build file).
    implementation(libs.kotlinx.serialization)
}

// Module manifest attributes (see :loader:fixtures:valid-basic's build file for the
// names).
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.wire-delta",
            "ComputeNet-Module-Version" to "1.0.0",
        )
    }
}
