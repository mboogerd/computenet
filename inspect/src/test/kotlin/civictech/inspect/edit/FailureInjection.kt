package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.graph.CellFactory
import civictech.cell.link.LinkPolicy
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.nature.ContractDescriptor
import civictech.nature.ContractModule
import civictech.nature.ContractRegistry
import civictech.nature.MethodDescriptor
import civictech.nature.ModuleId
import civictech.nature.StableHash
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * Failure-injection fixtures for [StagedApplier] (WKB2 F3, computenet-e1ojt).
 *
 * Verb ORDER is asserted from the registry, not from a wrapper around the
 * host's management API: `LocationRegistry.onLocalPublish` /
 * `onLocalUnpublish` / `onLocalTopology` see every spawn, despawn, link and
 * unlink the applier drives, in the order the host performs them — and they
 * are the observable the acceptance criteria name. The applier's own
 * recording management API is private, so no test-side wrapper could sit in
 * front of it anyway.
 */

/**
 * A [CellFactory] whose [n]-th `create` — counted per factory instance —
 * throws `IllegalStateException("injected")`; every other call builds with
 * [build].
 *
 * The count is per instance, and **PRECHECK constructs every spawn once, cold**
 * (F2's `GraphSpec.precheck` calls `factory.create(ref)` into a scratch map),
 * so the STAGE construction of a spawn is its factory's call 2. A spawn that
 * must fail in STAGE therefore uses `FailingFactory(2)`; `FailingFactory(1)`
 * throws out of PRECHECK instead.
 */
class FailingFactory(private val n: Int, private val build: (CellRef) -> Cell) : CellFactory {
    private val calls = AtomicInteger()

    override fun create(ref: CellRef): Cell {
        check(calls.incrementAndGet() != n) { "injected" }
        return build(ref)
    }
}

/** A spontaneous producer: [emit] originates a fresh wave on [outlet]. */
class EmitterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

    fun emit(value: String) = outlet.call.propagate(value)
}

/** A consumer recording every value delivered to [inlet]. */
class SinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val received = CopyOnWriteArrayList<String>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

    init {
        inlet.serve(Propagate { value -> received += value })
    }
}

/**
 * A cell whose [inlet]'s link policy refuses every `LinkRequest`, so a
 * management `connect` into it answers `LinkResult.Rejected`. Note that F2's
 * precheck runs the same policy walk, so a plan linking into it is refused
 * `POLICY_DENIAL` — reaching the CUT_OVER rejection takes `skipPrecheck`.
 */
class RejectingInlet(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>()).also {
        it.linking.policies += LinkPolicy { LinkResult.Rejected("injected: RejectingInlet refuses every link") }
    }
}

/** A live cell whose `onDeactivate` throws, so its management `despawn` throws after unpublishing. */
class ThrowingDeactivateCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    override fun onDeactivate(ctx: CellContext) {
        throw IllegalStateException("injected: onDeactivate")
    }
}

/**
 * A data contract whose one method takes an exclusive [Owned] payload — the
 * shape `[WKB2-26]`'s `OWNED_INTAKE` precheck refusal and e1ojt-D11's
 * `OwnedConsumed` residue are about.
 *
 * `:inspect` runs no KSP, and no exclusive contract under `kernel/src/main`
 * is reachable from here (the ones under `kernel/src/test` are not on this
 * module's classpath), so the generated descriptor is supplied by hand:
 * [OwnedIntakeDescriptor.ensureRegistered] registers the one
 * `ContractDescriptor` the processor would have emitted, with
 * `exclusive = true` on [accept]. Both witnesses read it —
 * `FanOutlet`'s SPSC bit (at port construction, so register first) and
 * `Precheck.carriesExclusive` / the applier's `exclusiveResidue`.
 */
interface OwnedIntake {
    fun accept(value: Owned<String>)
}

/** The hand-built descriptor for [OwnedIntake]; idempotent, JVM-global. */
object OwnedIntakeDescriptor {
    private val registered by lazy {
        val fqn = OwnedIntake::class.java.name.replace('$', '.')
        val jvm = "(L${Owned::class.java.name.replace('.', '/')};)V"
        val method = MethodDescriptor(
            methodId = StableHash.of("$fqn#accept$jvm"),
            name = "accept",
            jvmDescriptor = jvm,
            exclusive = true,
        )
        val module = object : ContractModule {
            override val contracts = listOf(
                ContractDescriptor(StableHash.of(fqn), fqn, management = false, methods = listOf(method)),
            )
        }
        ContractRegistry.register(module, ModuleId("inspect-test:failure-injection"))
        true
    }

    fun ensureRegistered() {
        check(registered)
    }
}

/** A live producer of [OwnedIntake] payloads; [emit] sends one fresh [Owned]. */
class OwnedProducerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = run {
        OwnedIntakeDescriptor.ensureRegistered()
        registerPort("outlet", FanOutlet.create<OwnedIntake>())
    }

    fun emit(value: String) = outlet.call.accept(Owned(value))
}

/** A consumer that takes every [Owned] delivered to [inlet] — the effect UNWIND cannot undo. */
class OwnedConsumerCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val consumed = CopyOnWriteArrayList<String>()
    val inlet = run {
        OwnedIntakeDescriptor.ensureRegistered()
        registerPort("inlet", FanInlet.create<OwnedIntake>())
    }

    init {
        inlet.serve(
            object : OwnedIntake {
                override fun accept(value: Owned<String>) {
                    consumed += value.take()
                }
            },
        )
    }
}
