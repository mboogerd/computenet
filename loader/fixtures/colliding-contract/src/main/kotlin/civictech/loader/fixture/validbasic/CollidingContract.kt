package civictech.loader.fixture.validbasic

import civictech.gen.wire.Contract

/**
 * Fixture (i) of epic computenet-051's fixture set — computenet-9fqe, the
 * [JAR1-ERR-05] registration-refusal arm.
 *
 * `contractId` is `StableHash.of(fqn)` (`ContractProcessor`, and see
 * `nature/src/main/kotlin/civictech/nature/ContractDescriptor.kt`'s `StableHash`
 * object) — there is no annotation attribute that sets it directly. The **only**
 * way to force a `CONTRACT_ID` collision at [civictech.nature.ContractRegistry]
 * without hand-constructing a descriptor is therefore to reuse another
 * loadable fixture's contract FQN exactly, which is what this file does:
 * `civictech.loader.fixture.validbasic.GreetingApi` is also the FQN of
 * `:loader:fixtures:valid-basic`'s baseline contract.
 *
 * The method shape is deliberately **different** from valid-basic's
 * (`greet(String, Boolean)` vs `greet(String)`): [JAR1-REG-06] treats a
 * byte-for-byte identical re-registration as idempotent, not a conflict, so an
 * identical method set here would make `ModuleRegistration.register` succeed
 * instead of refuse. The differing signature makes the two generated
 * `ContractDescriptor`s structurally unequal under the same `contractId`,
 * which is exactly the shape `ContractRegistry.stage` flags as `CONTRACT_ID`.
 *
 * This module carries no [civictech.cell.Cell] — the collision this fixture
 * exists to exercise is on the contract table alone, and `ModuleRegistration`
 * stages contracts and methods before it ever looks at a cell.
 */
@Contract
fun interface GreetingApi {
    fun greet(name: String, loudly: Boolean)
}
