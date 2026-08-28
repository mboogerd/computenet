package civictech.loader.fixture.doctorednature

import civictech.gen.wire.generated.ContractTable_538c72a577cafa07
import civictech.nature.CellDescriptor
import civictech.nature.ContractDescriptor
import civictech.nature.ContractModule
import civictech.nature.Monotonicity
import civictech.nature.NatureVector
import civictech.nature.ProtocolDescriptor

/**
 * B2 anti-reflection tripwire (epic computenet-051.3): hand-delegates every table
 * to the KSP-generated [ContractTable_538c72a577cafa07] — real generator output,
 * referenced directly rather than reimplemented — except for [DoctoredCell]'s one
 * `trigger` [civictech.nature.PortDescriptor], whose `natures` is replaced with a
 * non-default [NatureVector] no `@Contract`/`Cell` annotation in this module could
 * have produced (`ContractProcessor` never emits a non-[NatureVector.DEFAULT]
 * `natures` value — there is no source-level nature annotation to scan). A loader
 * that re-derives descriptors from bytecode/annotations instead of registering
 * this table unmodified will never reproduce this doctored value; one that trusts
 * the `ContractModule` verbatim will.
 *
 * IMPORTANT: `ContractTable_538c72a577cafa07`'s hash suffix is derived from this
 * fixture's own `@Contract`/`Cell` FQNs (`StableHash.of` over
 * `civictech.loader.fixture.doctorednature.TriggerApi` and `...DoctoredCell` —
 * gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt:286-291). Renaming
 * either type, or adding another `@Contract`/`Cell` to this module, changes the
 * hash and this reference stops compiling — the intended loud signal (see this
 * module's build file).
 */
class DoctoredContractModule : ContractModule {
    private val generated = ContractTable_538c72a577cafa07()

    override val contracts: List<ContractDescriptor> get() = generated.contracts

    override val cells: List<CellDescriptor>
        get() = generated.cells.map { cell ->
            cell.copy(
                ports = cell.ports.map { port ->
                    if (port.name == "trigger") {
                        port.copy(natures = NatureVector.of(Monotonicity.MONOTONE))
                    } else {
                        port
                    }
                },
            )
        }

    override val protocols: List<ProtocolDescriptor> get() = generated.protocols
}
