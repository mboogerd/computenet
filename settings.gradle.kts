dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":gen")
include(":nature")
include(":testkit")
// :bench is infrastructure, not an application — benchmark sources for the repository,
// depended on by nothing. That is why it sits next to :testkit rather than under demo/
// ([BEN1-01]).
include(":bench")
include(":oracle")

include(":kernel")
include(":concord")
include(":wire")

// JAR1 (epic computenet-051): the dynamic-jar loader, plus its fixture modules.
// The fixtures are ordinary subprojects of THIS build rather than an included build,
// because `buildsrc.convention.ksp-cell`'s `"ksp"(project(":gen"))` does not resolve
// across an included-build boundary without dependency substitution — and the epic
// (risk 051-R7) requires the fixture jars' META-INF/services entries to be real
// generator output, never hand-assembled.
include(":loader")
include(":loader:fixtures:valid-basic")
include(":loader:fixtures:util-a")
include(":loader:fixtures:util-b")
include(":loader:fixtures:smuggler")
// computenet-051.3.1: the load-path fixture set (manifest attributes, DISC-05,
// ERR-02/03/04, B2's doctored-table premise).
include(":loader:fixtures:no-attrs")
include(":loader:fixtures:empty-module")
include(":loader:fixtures:throwing-provider")
include(":loader:fixtures:removed-api")
include(":loader:fixtures:missing-shared-type")
include(":loader:fixtures:doctored-nature")
// computenet-9fqe: the ERR-05 registration-refusal arm — a fixture that collides
// with valid-basic's contractId.
include(":loader:fixtures:colliding-contract")
// computenet-051.6.4: fixture (h) — a module contributing WireSerializers for
// its own delta type, for the jar-loaded B13 end-to-end ([JAR1-REG-08]).
include(":loader:fixtures:wire-delta")
include(":identity")
include(":inspect")
include(":iroh")
include(":demo:shell")
include(":demo:shopping")
include(":demo:exchange")
include(":demo:agora")
include(":demo:allocator-observe")
include(":demo:beadsmirror")
include(":demo:dialogue")
include(":demo:slotfinder")
include(":demo:skillmatch")
include(":demo:tiering")
include(":demo:backlog-triage")

rootProject.name = "computenet"

