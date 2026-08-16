package civictech.demo.beadsmirror.baseline

import civictech.demo.beadsmirror.feed.DoltFeedPoller

/**
 * The background poll loop exited because a tick raised [failure] — anything
 * other than the truncation condition, which is handled and resumed from
 * rather than fatal. [checkpoint] is the commit the feed had persisted when it
 * stopped (`null` if nothing was ever checkpointed), i.e. the last position
 * the served fold reflects; it does not advance again, because
 * [DoltFeedPoller] does not restart its thread (computenet-dqj.12).
 *
 * This is a terminal event for the process's *feed* half, not for the process:
 * the HTTP surface keeps running and answers `503` with the stale fold
 * attached (see [civictech.demo.beadsmirror.http.MirrorRoutes]).
 *
 * **Why this type lives in this package and not in `feed`.** [MirrorEvent] is
 * the module's one operator-output vocabulary — everything the running mirror
 * wants an operator to know goes to `BeadsMirrorConfig.onEvent` — and a
 * `sealed interface`'s direct implementations must sit in its own package. A
 * second output channel for this one event would be strictly worse than the
 * package mismatch: it would be the channel an operator has not wired up.
 */
data class PollLoopDied(
    val failure: Throwable,
    val checkpoint: String?,
) : MirrorEvent
