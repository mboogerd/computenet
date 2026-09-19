plugins {
    id("buildsrc.convention.kotlin-jvm")
    // computenet-5ab6f: Schema.kt's payload types and SocialWireSerializers'
    // registrations carry `@kotlinx.serialization.Serializable` and need the
    // plugin that generates their serializers — the host write-ahead journal
    // encodes every accepted invocation through `WireCodec`'s polymorphic
    // `Any` scope, so without them `--journal` mode cannot write a single
    // fact. Same pairing as `:demo:agora` and `:demo:tiering`.
    alias(libs.plugins.kotlin.plugin.serialization)
    application
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":demo:shell"))
    // computenet-5ab6f: `SocialWireSerializers` implements `civictech.cell.wire.WireSerializers`,
    // whose `module` is a kotlinx `SerializersModule`. `:kernel` declares kotlinx-serialization
    // as `implementation`, so the types reach this module's runtime classpath transitively but
    // not its compile classpath: needs stating here. Not a project dependency, so
    // [SOC1-MOD-01]'s exact-set check (ModuleDependencyTest) is untouched.
    implementation(libs.kotlinx.serialization)

    testImplementation(project(":testkit"))
}

application {
    mainClass = "civictech.demo.social.SocialAppKt"
}
