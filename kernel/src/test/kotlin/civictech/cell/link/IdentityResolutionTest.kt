package civictech.cell.link

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * The binding seam takes presented evidence and names who vouched (feature
 * `computenet-5y8t.1`, task `computenet-5y8t.1.1`):
 * [PeerIdentityBinding.resolve] receives the [IdentityStatement]s a key's
 * holder presents, and [IdentityResolution.Bound] carries the [IssuerId] and
 * the statement a resolution rests on — null for both under
 * [PeerIdentityBinding.Interim], whose identity is key-derived.
 *
 * What this file does NOT show: no statement is verified, no validity window
 * is compared, and nothing here is about stolen-key resistance or revocation
 * (`[DSC1-NV-01]` stays EXPLICITLY UNVERIFIED). The test binding below takes a
 * statement's name on its word; it demonstrates only that the seam *carries*
 * evidence to a binding that reads it.
 *
 * **No-serialization audit note** (the `AuthLevel` KDoc precedent, recorded
 * for this task at base `192539789`): [IssuerId], [IdentityStatement] and
 * [IdentityResolution] are not `@Serializable`, and no wire frame, journal
 * record or serializer carries any of them. The check is
 * `git grep -n '@Serializable' -- kernel/src/main/kotlin/civictech/cell/link/Identity.kt`,
 * which lists only the pre-existing [PeerId] annotation. Their shapes, and
 * [UnboundReason]'s ordinals, are therefore local conventions with no
 * cross-version compatibility constraint — keep it that way.
 */
class IdentityResolutionTest {

    @Test
    fun `the interim binding ignores a presented statement naming a different peer and issuer`() {
        val key = KeyId("ed25519:abc")
        val anyStatement = IdentityStatement(
            name = PeerId("someone-else"),
            keyId = key,
            issuer = IssuerId("anchor-Z"),
            issuance = 1,
            notBefore = 0,
            notAfter = Long.MAX_VALUE,
            signature = ByteArray(0),
        )
        // Precondition: the statement really does name someone else, so a
        // binding that read it could not produce the expected value by accident.
        anyStatement.name shouldNotBe PeerId(key.name)

        PeerIdentityBinding.Interim.resolve(key, listOf(anyStatement)) shouldBe
            IdentityResolution.Bound(PeerId("ed25519:abc"), issuer = null, statement = null)
    }

    @Test
    fun `the unbound reasons are exactly the appended list, in order`() {
        // Pins insertion and reordering alike: new reasons are appended at the
        // end, never slotted in (decisions 5y8t.1-D4, D11).
        UnboundReason.entries shouldContainExactly listOf(
            UnboundReason.NO_BINDING,
            UnboundReason.NO_STATEMENT,
            UnboundReason.ISSUER_NOT_ACCEPTED,
            UnboundReason.BAD_SIGNATURE,
            UnboundReason.KEY_MISMATCH,
            UnboundReason.EXPIRED,
            UnboundReason.NOT_YET_VALID,
        )
    }

    @Test
    fun `a binding that reads presented statements resolves to the statement's name and issuer`() {
        val readsPresented = PeerIdentityBinding { key, presented ->
            presented.firstOrNull { it.keyId == key }
                ?.let { IdentityResolution.Bound(it.name, it.issuer, it) }
                ?: IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
        }
        val key = KeyId("ed25519:alice-key")
        val statement = IdentityStatement(
            name = PeerId("alice"),
            keyId = key,
            issuer = IssuerId("anchor-A"),
            issuance = 1,
            notBefore = 0,
            notAfter = Long.MAX_VALUE,
            signature = ByteArray(0),
        )

        // Compared against the instance held, not a separately built copy:
        // equality over the ByteArray signature is identity (decision D13).
        readsPresented.resolve(key, listOf(statement)) shouldBe
            IdentityResolution.Bound(PeerId("alice"), IssuerId("anchor-A"), statement)

        readsPresented.resolve(key, emptyList()) shouldBe
            IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
    }
}
