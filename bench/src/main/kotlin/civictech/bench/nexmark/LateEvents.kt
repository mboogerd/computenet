package civictech.bench.nexmark

/** Deterministic late-event fixtures applied to already-built batch streams. */
object LateEvents {
    fun inject(batches: List<Batch>, at: Int, event: Event): List<Batch> {
        require(at in batches.indices) {
            "late-event batch index $at is outside 0..${batches.lastIndex}"
        }
        return batches.mapIndexed { index, batch ->
            if (index == at) batch.copy(ops = batch.ops + StreamOp.Add(event)) else batch
        }
    }

    fun lateBid(template: List<Event>, inWindowStart: Long, id: Long): Bid {
        requireFreshId(template, id)
        val source = template.filterIsInstance<Bid>().firstOrNull()
            ?: error("lateBid needs a generated Bid template")
        return source.copy(
            id = id,
            dateTime = insideWindow(inWindowStart),
            eventNumber = id,
        )
    }

    fun lateAuction(template: List<Event>, inWindowStart: Long, id: Long): Auction {
        requireFreshId(template, id)
        val source = template.filterIsInstance<Auction>().firstOrNull()
            ?: error("lateAuction needs a generated Auction template")
        return source.copy(
            id = id,
            dateTime = insideWindow(inWindowStart),
            eventNumber = id,
        )
    }

    private fun requireFreshId(template: List<Event>, id: Long) {
        require(template.none { event ->
            event.eventNumber == id ||
                (event is Bid && event.id == id) ||
                (event is Auction && event.id == id)
        }) { "synthetic event id $id is not fresh" }
    }

    private fun insideWindow(windowStart: Long): Long =
        Math.addExact(windowStart, NexmarkSpec.WINDOW_MS / 2L)
}
