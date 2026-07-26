package civictech.cell.link

/**
 * Deny-by-default building block (M8.3): remote identities not in [peers]
 * are rejected; local requests (null identity) pass — boundary control, not
 * ambient suspicion (spec 43 posture).
 */
fun allowPeers(vararg peers: PeerId): LinkPolicy = LinkPolicy { request ->
    when (request.identity) {
        null -> null
        in peers -> null
        else -> LinkResult.Rejected("peer ${request.identity} is not on the allowlist (spec 43)")
    }
}

/** Link-time policy; composable, first rejection wins. Null = no objection. */
fun interface LinkPolicy {
    fun evaluate(request: LinkRequest): LinkResult.Rejected?
}
