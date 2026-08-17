package civictech.oracle.model

/**
 * One operator's batch reference: the independent answer a differential run compares the
 * kernel's incremental answer against.
 *
 * **This is deliberately a bare marker, and adding a member to it is out of scope here.**
 * The evaluation signature — what a reference op is handed and what it returns — is decided
 * by the reference-model feature (computenet-4ru.5), which is the item that has to make it
 * fit all of `[ORA1-MODEL-01..09]`: membership-only derivation, observed-remove semantics
 * defined on the script, the `Aggregators` retraction family, minted-tag-free join results,
 * and the single-writer-FIFO restriction on the order-dependent operators. Guessing that
 * signature from the catalog's side would either constrain 4ru.5 to a shape it has not
 * evaluated or be rewritten by it — the seam only needs the *type* to exist so that
 * [civictech.oracle.bind.OperatorCatalog] can require a model beside every kernel binding
 * ([ORA1-API-02]).
 *
 * `[ORA1-MODEL-10]` binds this file too: an implementation may reference value, key and
 * delta types, never a `civictech.cell.data.op` type or a concrete data-cell class. A
 * reference op that reached for `FilterCell` would be checking the implementation against
 * itself.
 */
interface ReferenceOp
