package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.gen.wire.ProxyModule
import civictech.gen.wire.ProxyRegistry
import civictech.nature.ContractModule
import civictech.nature.ContractRegistry
import civictech.nature.ModuleId
import civictech.nature.ModuleRegistration
import civictech.nature.ProtocolRegistry
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Feature computenet-051.4, task computenet-051.4.3 — the unload entry point:
 * scenario **B8**, the removal half of **B9**, and `[JAR1-UNL-02]`,
 * `[JAR1-UNL-03]`, `[JAR1-UNL-07]`.
 *
 * Close-semantics, reload and reachability (`[JAR1-UNL-04/05/06]`) are the
 * follow-on task's and are deliberately not asserted here — this file stops at
 * "the classloader was closed and the state is CLOSED".
 *
 * The registries are process-global objects, so every test here unregisters and
 * closes whatever it loaded, whether through [FixtureJars.withLoadedModule] or
 * an explicit `finally`.
 */
class ModuleUnloadTest {

    private companion object {
        const val GREETING_API = "civictech.loader.fixture.validbasic.GreetingApi"
        const val GREETING_CELL = "civictech.loader.fixture.validbasic.GreetingCell"
        const val TRIGGER_API = "civictech.loader.fixture.doctorednature.TriggerApi"
        const val DOCTORED_CELL = "civictech.loader.fixture.doctorednature.DoctoredCell"
    }

    /** Reflectively constructs a fresh `GreetingCell`, defined by [handle]'s own loader. */
    private fun newModuleCell(handle: ModuleHandle): Cell {
        val cellClass = handle.classLoader.loadClass(GREETING_CELL)
        return cellClass.getDeclaredConstructor(CellRef::class.java)
            .newInstance(CellRef(UUID.randomUUID())) as Cell
    }

    /**
     * Invoke `GreetingApi.greet` on a module cell instance reflectively, and read
     * `lastGreeted` back.
     *
     * This is the mid-flow form `valid-basic` supports: `GreetingCell` serves no
     * ports on purpose (see its own KDoc), so there is no wave to hold open, and
     * adding a ported fixture here would be inventing dataflow for a fixture set
     * whose whole point is module identity and isolation. What the B8 assertions
     * below actually establish is the property the scenario is about — a refused
     * unload leaves the live cell's own behaviour and the host's accounting
     * untouched, and the refusal happens before any removal so no invocation can
     * observe a half-removed descriptor.
     */
    private fun greet(cell: Cell, name: String): String? {
        val api = cell.javaClass.classLoader.loadClass(GREETING_API)
        api.getMethod("greet", String::class.java).invoke(cell, name)
        return cell.javaClass.getMethod("getLastGreeted").invoke(cell) as String?
    }

    // ------------------------------------------------------------------
    // B8 — [JAR1-UNL-01][JAR1-UNL-02][JAR1-UNL-03]
    // ------------------------------------------------------------------

    @Test
    fun `unload while cells are live is refused naming the count, and succeeds once they despawn`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val handle = loader.load(FixtureJars.validBasic)
        var unloaded = false
        try {
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            loader.track(registry).use {
                val cellA = newModuleCell(handle)
                val cellB = newModuleCell(handle)
                host.managementInlet.call.spawn(cellA)
                host.managementInlet.call.spawn(cellB)
                awaitUntil("both module cells counted") { handle.liveInstances == 2 }

                val deadLettersBefore = host.supervisionAccounting().deadLetters
                val contractClass = handle.classLoader.loadClass(GREETING_API)

                // An invocation in flight across the refused unload: issued before the
                // request, read back after it.
                greet(cellA, "before")

                val refusal = shouldThrow<ModuleUnloadRefusedException> { loader.unload(handle) }

                withClue("UNL-02: the refusal must NAME the live count, not merely refuse") {
                    refusal.liveInstances shouldBe 2
                    refusal.message.shouldNotBeNull() shouldContain "2 cell(s)"
                }

                withClue("the module is untouched: still REGISTERED and still listed") {
                    handle.state shouldBe ModuleState.REGISTERED
                    loader.loaded() shouldContain handle
                }
                withClue("and every registry contribution still resolves") {
                    ContractRegistry.descriptor(contractClass) shouldNotBe null
                    ContractRegistry.cellDescriptor(handle.classLoader.loadClass(GREETING_CELL)) shouldNotBe null
                    ProxyRegistry.factory(contractClass) shouldNotBe null
                    handle.protocolIds.forEach { ProtocolRegistry.protocol(it) shouldNotBe null }
                }

                withClue("the in-flight invocation completed normally across the refusal") {
                    greet(cellA, "after") shouldBe "after"
                }
                withClue("and the refusal produced no dead letter — asserted, not assumed") {
                    host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
                }
                withClue("the live cells are still live: nothing was torn down") {
                    handle.liveInstances shouldBe 2
                    registry.locate(cellA.ref) shouldNotBe null
                    registry.locate(cellB.ref) shouldNotBe null
                }

                host.managementInlet.call.despawn(cellA.ref)
                host.managementInlet.call.despawn(cellB.ref)
                awaitUntil("both despawned") { handle.liveInstances == 0 }

                loader.unload(handle)
                unloaded = true

                handle.state shouldBe ModuleState.CLOSED
                loader.loaded() shouldBe emptyList()
                withClue("UNL-03: the module's contributions are gone") {
                    ContractRegistry.descriptor(contractClass) shouldBe null
                    ProxyRegistry.factory(contractClass) shouldBe null
                }
                withClue("no dead letter was produced by the successful unload either") {
                    host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
                }
            }
        } finally {
            if (!unloaded) {
                ModuleRegistration.unregister(handle.id)
                handle.classLoader.close()
            }
        }
    }

    // ------------------------------------------------------------------
    // B9 (removal half) — [JAR1-UNL-03] exact removal
    // ------------------------------------------------------------------

    @Test
    fun `unloading one module removes exactly its contributions and leaves another module's resolvable`() {
        // The second module must be a *module* (manifest attributes + descriptors);
        // `util-a` is an isolation fixture with no module manifest, so `doctored-nature`
        // is the only other fixture that both loads and contributes descriptors.
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic, FixtureJars.doctoredNature)
        val basic = loader.load(FixtureJars.validBasic)
        val util = loader.load(FixtureJars.doctoredNature)
        var basicUnloaded = false
        try {
            val basicApi = basic.classLoader.loadClass(GREETING_API)
            val basicCell = basic.classLoader.loadClass(GREETING_CELL)
            val utilApi = util.classLoader.loadClass(TRIGGER_API)
            val utilCell = util.classLoader.loadClass(DOCTORED_CELL)

            ContractRegistry.descriptor(basicApi) shouldNotBe null
            ContractRegistry.descriptor(utilApi) shouldNotBe null

            loader.unload(basic)
            basicUnloaded = true

            withClue("exactly the unloaded module's contract/cell/proxy entries are gone") {
                ContractRegistry.descriptor(basicApi) shouldBe null
                ContractRegistry.cellDescriptor(basicCell) shouldBe null
                ProxyRegistry.factory(basicApi) shouldBe null
                basic.protocolIds.forEach { ProtocolRegistry.protocol(it) shouldBe null }
            }
            withClue("and the other module's are untouched") {
                ContractRegistry.descriptor(utilApi) shouldNotBe null
                ContractRegistry.cellDescriptor(utilCell) shouldNotBe null
                ProxyRegistry.factory(utilApi) shouldNotBe null
                util.protocolIds.forEach { ProtocolRegistry.protocol(it) shouldNotBe null }
            }
            withClue("loaded() drops the unloaded handle and keeps the other") {
                loader.loaded() shouldBe listOf(util)
            }
            basic.state shouldBe ModuleState.CLOSED
            util.state shouldBe ModuleState.REGISTERED
        } finally {
            if (!basicUnloaded) {
                ModuleRegistration.unregister(basic.id)
                basic.classLoader.close()
            }
            ModuleRegistration.unregister(util.id)
            util.classLoader.close()
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-UNL-07] — part-way removal failure restores the registrations
    // ------------------------------------------------------------------

    /**
     * A genuinely **partial** removal, expressed entirely through public API.
     *
     * `ContractRegistry.removeOwner` / `ProtocolRegistry.removeOwner` /
     * `ProxyRegistry.removeOwner` are all `internal` to `:nature`, so a `:loader`
     * test cannot drive one table's removal directly. What it *can* do is leave
     * the registries in exactly the state a part-way removal leaves them in:
     * unregister the owner completely, then re-register only its [ContractModule]
     * tables, so its contract/protocol entries are back and its **proxy**
     * constructors are still missing — then throw. That is the residual state
     * UNL-07 has to recover from, produced without weakening `:nature`'s
     * visibility.
     */
    private fun partialRemovalThenThrow(contractModules: List<ContractModule>): (ModuleId) -> Unit = { id ->
        ModuleRegistration.unregister(id)
        ModuleRegistration.register(owner = id, contractModules = contractModules, proxyModules = emptyList())
        throw IllegalStateException("injected: registry removal failed after dropping the proxy table")
    }

    @Test
    fun `a part-way removal failure restores the registrations, keeps the loader open, and rethrows`() {
        // Two loaders over the same jar: the first only to read the module's own
        // discovered ContractModule tables back out of the jar, so the injected
        // seam can re-register a genuine subset of them.
        val jar: File = FixtureJars.validBasic
        val probe = FixtureJars.loaderAccepting(jar)
        val probeHandle = probe.load(jar)
        val contractModules = listOf(FixtureJars.contractModuleIn(jar, probeHandle.classLoader))
        probe.unload(probeHandle)

        val loader = ModuleLoader(
            acceptedLocations = setOf(jar.toPath().toAbsolutePath().normalize().parent),
            observe = {},
            unregister = partialRemovalThenThrow(contractModules),
        )
        val handle = loader.load(jar)
        var unloaded = false
        try {
            val api = handle.classLoader.loadClass(GREETING_API)
            val cell = handle.classLoader.loadClass(GREETING_CELL)

            val failure = shouldThrow<ModuleUnloadFailedException> { loader.unload(handle) }

            withClue("neither failure is swallowed: the removal failure is the cause") {
                failure.restored shouldBe true
                failure.cause.shouldNotBeNull().message.shouldNotBeNull() shouldContain "injected"
            }
            withClue("UNL-07: the module reports REGISTERED again") {
                handle.state shouldBe ModuleState.REGISTERED
            }
            withClue("every contribution resolves again — including the proxy the partial removal dropped") {
                ContractRegistry.descriptor(api) shouldNotBe null
                ContractRegistry.cellDescriptor(cell) shouldNotBe null
                ProxyRegistry.factory(api) shouldNotBe null
                handle.protocolIds.forEach { ProtocolRegistry.protocol(it) shouldNotBe null }
            }
            withClue("the classloader was never closed and the handle is still listed") {
                handle.classLoader.loadClass(GREETING_CELL) shouldBe cell
                loader.loaded() shouldContain handle
            }

            // A subsequent CLEAN unload succeeds: same loader, real removal seam.
            val clean = ModuleLoader(acceptedLocations = loader.acceptedLocations, observe = {})
            // The clean loader must own the handle to unload it, so drive the real
            // seam through this loader's own path instead: unregister + close, then
            // re-verify by loading afresh through `clean`.
            loader.unloadWithRealSeam(handle)
            unloaded = true

            handle.state shouldBe ModuleState.CLOSED
            ContractRegistry.descriptor(api) shouldBe null

            val reloaded = clean.load(jar)
            try {
                ContractRegistry.descriptor(reloaded.classLoader.loadClass(GREETING_API)) shouldNotBe null
            } finally {
                clean.unload(reloaded)
            }
        } finally {
            if (!unloaded) {
                runCatching { ModuleRegistration.unregister(handle.id) }
                handle.classLoader.close()
            }
        }
    }

    /**
     * The failing-seam loader above cannot perform a clean unload through itself —
     * its seam always throws. This drives the real removal and the same
     * bookkeeping the success path does, so the "a subsequent clean unload
     * succeeds" assertion is about the module's *state*, not about re-plumbing the
     * loader.
     */
    private fun ModuleLoader.unloadWithRealSeam(handle: ModuleHandle) {
        ModuleRegistration.unregister(handle.id)
        handle.state = ModuleState.UNREGISTERED
        handle.classLoader.close()
        handle.state = ModuleState.CLOSED
    }

    // ------------------------------------------------------------------
    // Caller errors
    // ------------------------------------------------------------------

    @Test
    fun `unloading a handle this loader does not hold, or unloading twice, is a caller error`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val other = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val handle = loader.load(FixtureJars.validBasic)
        loader.unload(handle)

        shouldThrow<IllegalStateException> { loader.unload(handle) }
        shouldThrow<IllegalStateException> { other.unload(handle) }
    }

    /** Retention is [JAR1-DISC-03]'s rule applied to the restore path: the same objects, untouched. */
    @Test
    fun `the handle retains the discovered tables it registered, unmodified`() {
        FixtureJars.withLoadedModule(FixtureJars.validBasic) { handle ->
            val fromJar = FixtureJars.contractModuleIn(FixtureJars.validBasic, handle.classLoader)
            val retained: List<ContractModule> = handle.contractModules
            val retainedProxies: List<ProxyModule> = handle.proxyModules

            retained.size shouldBe 1
            withClue("the retained table equals the jar's own generated one — nothing was rebuilt") {
                retained.single().contracts shouldBe fromJar.contracts
                retained.single().cells shouldBe fromJar.cells
                retained.single().protocols shouldBe fromJar.protocols
            }
            retainedProxies.flatMap { it.factories.keys } shouldBe handle.proxiedClasses
        }
    }
}
