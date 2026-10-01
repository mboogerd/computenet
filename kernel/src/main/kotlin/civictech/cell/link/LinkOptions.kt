package civictech.cell.link

/**
 * Options for a host-admitted link.
 *
 * [role] selects whether the edge consumes data and participates in a
 * downstream completeness frontier or observes the stream without gating it.
 * [staged] keeps delivery on the target host's intake instead of fusing it
 * into the emitting thread. The default preserves the original fused Consume
 * link behavior.
 */
data class LinkOptions(
    val role: LinkRole = LinkRole.Consume,
    val staged: Boolean = false,
) : java.io.Serializable {
    companion object {
        val DEFAULT = LinkOptions()
    }
}
