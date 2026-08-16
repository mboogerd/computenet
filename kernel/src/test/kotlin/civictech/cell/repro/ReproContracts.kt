package civictech.cell.repro

import civictech.cell.Leased
import civictech.cell.Owned
import civictech.gen.wire.Contract
import civictech.gen.wire.Key

/**
 * Test-local `@Contract` fixtures for [ExclusiveDischargeReproTest] (C-11, BS-7/8/9/12).
 *
 * They live at file scope, not nested in the test class, because `:kernel`'s test source set
 * is KSP-processed exactly like its main source set (`gen.wire.ContractProcessor`) and the
 * processor scans top-level `@Contract` interfaces — the same arrangement
 * `kernel/src/test/kotlin/civictech/cell/evolve/ShadowOwnershipTest.kt` uses for
 * `ShadowOwnedPush`/`ShadowLeasedPush`. Deliberately *not* reused from that file: the
 * evidence lane keeps its own fixtures so a change to the landed exit test cannot silently
 * reshape a reproduction, and vice versa.
 */

/** BS-7: the direct-`Owned` half of the landed discharging path. */
@Contract
interface ReproOwnedPush {
    fun push(@Key value: Owned<String>)
}

/** BS-7: the direct-`Leased` half. */
@Contract
interface ReproLeasedPush {
    fun push(@Key value: Leased<String>)
}

/**
 * BS-8's payload: a **plain data class** carrying an `Owned` in a field.
 *
 * Not a `Map`, not an `Iterable`, not an `Array`, and — the part that matters for the KSP
 * half of the divergence — not a *type argument* either: `ContractProcessor.carriesExclusive`
 * (`gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt:70-73`) tests the parameter's
 * own declaration and then recurses through `type.arguments` only, so an exclusive reached
 * through a *field* is invisible to it.
 */
data class OwnedEnvelope(val label: String, val payload: Owned<String>)

/**
 * BS-8's contract, deliberately two-method.
 *
 * [pushDirect] carries a bare `Owned`, so KSP marks it exclusive and
 * `Shadow.suppressionProxy` (`Evolution.kt:88-93`) selects `Proxy.discharging` rather than
 * `Proxy.noop` for the whole contract. [pushNested] then crosses **that discharging proxy**,
 * which is the literal shape BS-8 (`[CHA2-21]`) asks for: the escape is observed through the
 * discharging sink, not through a proxy that was never discharging in the first place.
 */
@Contract
interface ReproNestedExclusivePush {
    fun pushDirect(@Key value: Owned<String>)
    fun pushNested(@Key envelope: OwnedEnvelope)
}

/**
 * BS-9's contract: the effect-carrying marker is the `@Contract(effect = true)` flag
 * (`nature/src/main/kotlin/civictech/gen/wire/Contract.kt:25`, "Marks a world-touching
 * boundary that shadow execution must suppress"), surfaced on the generated descriptor as
 * `ContractDescriptor.effect` (`nature/src/main/kotlin/civictech/nature/ContractDescriptor.kt:31`)
 * and emitted by `ContractProcessor.kt:369`. Verified by symbol, not inherited from the
 * ticket: there is no other effect marker in `nature/` or `gen/`.
 */
@Contract(effect = true)
interface ReproEffectApi {
    fun fire(@Key id: String)
}

/**
 * BS-12's contract: **no** `@Contract` annotation, therefore no generated descriptor and no
 * `ContractRegistry` row. `Proxy.discharging` must refuse it loudly by name.
 */
interface ReproUndescribedApi {
    fun push(value: Owned<String>)
}
