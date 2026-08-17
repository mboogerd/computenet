plugins {
    // Shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
}

// :identity is JDK-only crypto (JEP 339 Ed25519, `java.security`) — no BouncyCastle
// and no third-party cryptographic dependency belongs here ([DSC1-KEY-01],
// [DSC1-WIRE-04]). A demonstrated gap in what the JDK provides is a parked
// question for a human, not a unilateral dependency add.
//
// The direction is :identity -> :kernel only; :kernel MUST NOT depend on
// :identity. `api` because kernel types appear in this module's public
// signatures: `fingerprint()` returns `civictech.cell.link.PeerId`, and
// `Ed25519SignatureVerifier` implements `civictech.cell.membrane.SignatureVerifier`
// at the seam the kernel already owns (this module implements that seam; it does
// not modify it).
dependencies {
    api(project(":kernel"))

    testImplementation(kotlin("test"))
}
