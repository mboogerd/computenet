package civictech.cell.partition

/**
 * PN-12 structural marker (`Manifest.PARTITIONED`): the cell holds its state
 * across an interest-partitioned instance set (a composite router over shards,
 * or one shard of one). KSP folds it into
 * [civictech.nature.CellDescriptor.manifest]. A pure marker — no methods, no
 * new annotation.
 */
interface Partitioned
