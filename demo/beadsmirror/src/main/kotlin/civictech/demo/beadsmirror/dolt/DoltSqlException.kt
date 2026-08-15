package civictech.demo.beadsmirror.dolt

/**
 * Raised whenever a [DoltSql] query cannot be turned into rows: the `dolt`
 * process exited non-zero, or it exited zero but printed something the
 * `-r json` envelope parser does not recognise. Carries the query text and
 * whatever diagnostic the process produced, so a caller three layers up
 * (the feed reader, its tests) can report a failure that names both the
 * question asked and why the answer could not be read — see
 * computenet-dqj.1.1's acceptance criterion on the query-failure path.
 */
class DoltSqlException(
    val query: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("dolt sql failed for query [$query]: $message", cause)
