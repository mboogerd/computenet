// :iroh is the JVM half of the iroh adoption (DSC0, epic `computenet-egl`): it
// wraps the Rust crate at iroh/sidecar/ (egl.1-D1) and speaks that crate's
// local-socket protocol (iroh/sidecar/PROTOCOL.md) from Kotlin.
//
// egl.1-D4: the cargo flag is the project property `iroh.enabled`
// (-Piroh.enabled=true). Cargo tasks are registered ONLY when the property is
// set — a registered-but-disabled task would still let Gradle resolve and
// configure a cargo invocation on the default path, which is the wrong shape.
// On the unset path this module has no cargo task at all: `./gradlew :iroh:build`
// and `./gradlew build` never touch a Rust toolchain. The JVM half compiles and
// its codec tests run on that default path; only the tests that spawn a sidecar
// are skipped, by the assumption in `SidecarBinary` (egl.2's rule 5).
plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

// egl.2 rule 2: the dependency direction is `:iroh -> :kernel`, never the
// reverse, exactly as wire/build.gradle.kts documents for `:wire`. No `:wire`
// and no `:identity` here — the NodeId-derived PeerId is feature egl.3's, and
// this module has no business on another transport's classpath.
dependencies {
    implementation(project(":kernel"))

    testImplementation(project(":testkit"))
}

if (project.hasProperty("iroh.enabled")) {
    val sidecarDir = layout.projectDirectory.dir("sidecar")

    val cargoBuild = tasks.register<Exec>("cargoBuild") {
        group = "iroh"
        description = "Runs `cargo build` for the iroh sidecar crate (iroh/sidecar)."
        workingDir = sidecarDir.asFile
        commandLine("cargo", "build")
    }

    // `cargo test` also builds; running it after cargoBuild is redundant work
    // it happily re-checks, not a correctness requirement — kept as two tasks
    // so `cargoBuild`'s failure is distinguishable from a test failure.
    val cargoTest = tasks.register<Exec>("cargoTest") {
        group = "iroh"
        description = "Runs `cargo test` for the iroh sidecar crate (iroh/sidecar)."
        dependsOn(cargoBuild)
        workingDir = sidecarDir.asFile
        commandLine("cargo", "test")
    }

    // egl.2-D2: the ONLY channel by which a test locates the sidecar. Set only
    // on the flag-set path, so an unset build leaves the property absent and
    // every sidecar-backed test takes its JUnit assumption. The path is cargo's
    // default-bin convention for the `computenet-iroh-sidecar` package
    // (iroh/sidecar/Cargo.toml), confirmed by a flag-set build.
    val sidecarBinary = sidecarDir.file("target/debug/computenet-iroh-sidecar")
    tasks.withType<Test>().configureEach {
        dependsOn(cargoBuild)
        systemProperty("iroh.sidecar.binary", sidecarBinary.asFile.absolutePath)
    }

    // Exec's default behavior already fails the Gradle build on a nonzero
    // cargo exit code — no explicit isIgnoreExitValue handling needed.
    tasks.named("check") {
        dependsOn(cargoTest)
    }
    tasks.named("build") {
        dependsOn(cargoTest)
    }
}
