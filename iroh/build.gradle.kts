// :iroh has no JVM sources in this feature — it wraps the Rust crate at
// iroh/sidecar/ (egl.1-D1) behind a Gradle flag, so it applies only the
// `base` plugin (lifecycle tasks: build, check, clean) and NOT
// buildsrc.convention.kotlin-jvm, which this module has no use for.
//
// egl.1-D4: the flag is the project property `iroh.enabled`
// (-Piroh.enabled=true). Cargo tasks are registered ONLY when the property
// is set — a registered-but-disabled task would still let Gradle resolve and
// configure a cargo invocation on the default path, which is the wrong
// shape. On the unset path this module has no cargo task at all: `./gradlew
// :iroh:build` and `./gradlew build` never touch a Rust toolchain.
plugins {
    base
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

    // Exec's default behavior already fails the Gradle build on a nonzero
    // cargo exit code — no explicit isIgnoreExitValue handling needed.
    tasks.named("check") {
        dependsOn(cargoTest)
    }
    tasks.named("build") {
        dependsOn(cargoTest)
    }
}
