import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    // Same real-KSP pipeline as `:loader:fixtures:valid-basic` — see that module's
    // build file for why (epic computenet-051 risk 051-R7). Real generation is the
    // point here too: `DoctoredContractModule` below delegates to the generated
    // `ContractTable_<hash>` rather than reimplementing it, so everything except
    // the one doctored `PortDescriptor.natures` value is genuine generator output.
    id("buildsrc.convention.ksp-cell")
}

dependencies {
    implementation(project(":kernel"))
}

// Module manifest attributes (see :loader:fixtures:valid-basic's build file for the
// names). Plus the B2 doctoring itself: the `jar` task's normal content already
// carries KSP's generated `META-INF/services/civictech.nature.ContractModule` entry
// (naming `ContractTable_<hash>`, the real, undoctored table) — this `doLast`
// rewrites the FINISHED jar to replace that one zip entry's content with
// `DoctoredContractModule`'s FQN, generated here in the build script rather than
// read from any file under `src/` (epic risk 051-R7 / `FixtureJarsTest`'s
// "no fixture source tree contains a hand-written META-INF services file" check,
// which is scoped to `src/`, not `build/`, and stays green because of it).
//
// Rewriting the finished zip (rather than fighting Gradle's `CopySpec.exclude`,
// whose patterns are inherited by every nested `from(...)`, including one adding
// the replacement at the very same path) keeps the KSP-generated entry's removal
// and the replacement's addition atomic and easy to read.
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "ComputeNet-Module-Id" to "fixture.doctored-nature",
            "ComputeNet-Module-Version" to "1.0.0",
        )
    }
    doLast {
        val servicesEntry = "META-INF/services/civictech.nature.ContractModule"
        val doctoredModuleFqn = "civictech.loader.fixture.doctorednature.DoctoredContractModule\n"
        val jarFile = archiveFile.get().asFile
        val rewritten = File(jarFile.parentFile, jarFile.name + ".doctoring.tmp")

        val sourceZip = ZipFile(jarFile)
        sourceZip.use { source ->
            val destZip = ZipOutputStream(rewritten.outputStream().buffered())
            destZip.use { dest ->
                val entries: List<ZipEntry> = source.entries().toList()
                for (entry in entries) {
                    if (entry.name == servicesEntry) continue // dropped; re-added below
                    dest.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        source.getInputStream(entry).use { input -> input.copyTo(dest) }
                    }
                    dest.closeEntry()
                }
                dest.putNextEntry(ZipEntry(servicesEntry))
                dest.write(doctoredModuleFqn.toByteArray())
                dest.closeEntry()
            }
        }

        rewritten.copyTo(jarFile, overwrite = true)
        rewritten.delete()
    }
}
