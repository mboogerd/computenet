plugins {
    // Same real-KSP pipeline as `:loader:fixtures:valid-basic` — see that module's
    // build file for why (epic computenet-051 risk 051-R7).
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    implementation(project(":kernel"))
}

// ERR-02 fixture: deliberately NO `jar { manifest { attributes(...) } }` block.
// A well-formed ksp-cell jar (real @Contract, real cell, real generated
// META-INF/services entry) that simply never declared the module manifest
// attributes — must be refused as not-a-module, not as malformed.
