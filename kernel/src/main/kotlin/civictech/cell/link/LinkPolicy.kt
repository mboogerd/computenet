package civictech.cell.link

/**
 * Deny-by-default building block (M8.3): remote peers whose identity is not
 * the identity of one of [keys] are rejected; local requests (null identity)
 * pass — boundary control, not ambient suspicion (spec 43 posture).
 *
 * **Configured in key identifiers, judged against the stamped identity**
 * (feature `computenet-376c`). Seam-2 link authority is boundary admission,
 * so its allowlist is expressed in [KeyId]s — "which keys may link here" —
 * which is what "boundary admission is expressed in terms of the key
 * identifier" means for this function. But a [LinkRequest] carries no key:
 * it carries the [PeerId] the admitting side already stamped
 * ([LinkRequest.identity], built from [CurrentPeer.get]). Widening the stamp
 * to carry a key alongside was considered and rejected as a carrier change
 * this feature does not need. So each configured key is resolved to its
 * identity through [binding] and compared against the stamped one.
 *
 * The resolution happens **per evaluation**, not once at construction: a
 * binding is a live mapping (DSC4's anchor-vouched names can change what a
 * key resolves to over time), and a policy that froze it at construction
 * would keep admitting a peer under a name it no longer holds.
 *
 * Under the default [PeerIdentityBinding.Interim] a key identifier and the
 * identity it resolves to hold the same string, so every pre-`376c` allowlist
 * admits and refuses exactly what it did before.
 */
fun allowPeers(
    vararg keys: KeyId,
    binding: PeerIdentityBinding = PeerIdentityBinding.Interim,
): LinkPolicy = LinkPolicy { request ->
    val identity = request.identity
    when {
        identity == null -> null
        keys.any { binding.identityOf(it) == identity } -> null
        else -> LinkResult.Rejected("peer $identity is not on the allowlist (spec 43)")
    }
}

/** Link-time policy; composable, first rejection wins. Null = no objection. */
fun interface LinkPolicy {
    fun evaluate(request: LinkRequest): LinkResult.Rejected?
}
