package civictech.gen.async

interface SendOperation {
    suspend fun <Q, O> query(op: Q): O where Q : Op, Q : Return<O>
    suspend fun operate(op: Op)
}

