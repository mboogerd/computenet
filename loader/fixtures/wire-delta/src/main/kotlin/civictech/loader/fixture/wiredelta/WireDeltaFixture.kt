package civictech.loader.fixture.wiredelta

import civictech.cell.Timestamp
import civictech.cell.wire.WireSerializers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Fixture (h) of epic computenet-051's fixture set (task computenet-051.6.4):
 * a module contributing a [WireSerializers] table for its own delta type — the
 * jar-loaded end-to-end for scenario B13, `[JAR1-REG-08]`.
 *
 * Deliberately carries no `@Contract`/`Cell` (see this fixture's build file):
 * the whole point is that ONLY the `WireSerializers` service, discovered by
 * `ModuleLoader.load` alongside `ContractModule`/`ProxyModule`, is what makes
 * this delta type wire-capable — there is nothing here for the contract/proxy
 * registries to do.
 */
@Serializable
@SerialName("WireDeltaFixtureDelta")
data class WireDeltaFixtureDelta(val payload: String, val tag: Timestamp)

/**
 * Named by this module's hand-written
 * `META-INF/services/civictech.cell.wire.WireSerializers` entry (src/main/resources
 * — real Gradle-built jar content, not a post-hoc assembled file; the same
 * technique `:loader:fixtures:throwing-provider` uses and documents, since
 * `ContractProcessor` never emits this particular services entry for ANY
 * module, well-formed or not).
 */
class WireDeltaFixtureWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(WireDeltaFixtureDelta::class, WireDeltaFixtureDelta.serializer())
        }
    }
}
