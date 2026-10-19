package civictech.bench.nexmark

/** Structural state accounting available without opening private operator ledgers. */
object StateAccounting {
    /** Return per-outlet and per-fold maxima over the captured series. */
    fun peak(series: List<RetainedState>): RetainedState {
        require(series.isNotEmpty()) { "state series must not be empty" }
        return RetainedState(
            liveRows = maxima(series.map { it.liveRows }),
            foldKeys = maxima(series.map { it.foldKeys }),
            ledgerProbe = series.last().ledgerProbe,
        )
    }

    /** Classify an outlet as growing only when it is monotone and ends above its baseline. */
    fun growth(series: List<RetainedState>): GrowthNote {
        require(series.isNotEmpty()) { "state series must not be empty" }
        val outlets = series.flatMapTo(sortedSetOf()) { it.liveRows.keys }
        val growing = outlets.firstOrNull { outlet ->
            val counts = series.map { it.liveRows[outlet] ?: 0L }
            counts.last() > counts.first() &&
                counts.zipWithNext().all { (previous, current) -> previous <= current }
        }
        return growing?.let(GrowthNote::Growing) ?: GrowthNote.Flat
    }

    private fun maxima(samples: List<Map<String, Long>>): Map<String, Long> =
        samples.flatMap { it.keys }.toSortedSet().associateWith { key ->
            samples.maxOf { it[key] ?: 0L }
        }
}

sealed interface GrowthNote {
    data object Flat : GrowthNote

    data class Growing(val outlet: String) : GrowthNote {
        init {
            require(outlet.isNotBlank()) { "growth outlet must not be blank" }
        }
    }
}
