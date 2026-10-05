package civictech.cell.link

/**
 * Deny-by-default building block (M8.3): remote peers whose stamped identity
 * is not one of [peers] are rejected; local requests (null identity) pass —
 * boundary control, not ambient suspicion (spec 43 posture).
 *
 * **Allowlists name identities** (epic `computenet-5y8t`). The key is what a
 * hello is **proven** on; the admitting side resolves it to an identity
 * through its `civictech.cell.link.PeerIdentityBinding` and stamps that
 * identity on every delivery. A [LinkRequest] carries exactly that stamp
 * ([LinkRequest.identity], built from [CurrentPeer.get]), so this policy
 * compares the stamp with the configured names directly and **resolves
 * nothing at link time**. The inversion feature `computenet-376c` needed —
 * an allowlist configured in key identifiers, each resolved per evaluation
 * to compare against the stamp — is gone with the key-configured allowlist,
 * and so is the resolver parameter it needed: there is one way to say who may
 * link here, not two.
 *
 * Under the default `Interim` resolution a key identifier and the identity it
 * resolves to hold the same string, so every allowlist configured before
 * epic `computenet-5y8t` admits and refuses exactly what it did.
 */
fun allowPeers(vararg peers: PeerId): LinkPolicy = LinkPolicy { request ->
    val identity = request.identity
    when {
        identity == null -> null
        identity in peers -> null
        else -> LinkResult.Rejected("peer $identity is not on the allowlist (spec 43)")
    }
}

/** Link-time policy; composable, first rejection wins. Null = no objection. */
fun interface LinkPolicy {
    fun evaluate(request: LinkRequest): LinkResult.Rejected?
}

/**
 * Thread-scoped marker that the `LinkPolicy` walk running on this thread is a
 * cold dry run (`Precheck.policyCheck` under `GraphSpec.precheck`), not a handshake.
 * Policies with side effects — the Link budget arm (`CompositeCell.linkBudgetPolicy`,
 * `computenet-fh9cc`) — must not charge or account a denial while it is set.
 *
 * Decision (computenet-fh9cc, acceptance clause 3): this marks
 * `Precheck.policyCheck` ONLY. Promotion re-authorization
 * (`LinkSupport.reauthorize` / `Evolution.reauthorizeRebinds`) is a CHARGE, not
 * a dry run (66m-D11; `computenet-8aboz` lets it be budget-charged once the
 * establishing stamp is retained), so those callers never set this flag even
 * though they share the `reject` walk.
 */
internal object LinkDryRun {
    private val active = ThreadLocal.withInitial { false }

    /** True while the current thread is inside [during]. */
    fun isActive(): Boolean = active.get()

    fun <R> during(block: () -> R): R {
        val previous = active.get()
        active.set(true)
        try {
            return block()
        } finally {
            active.set(previous)
        }
    }
}
