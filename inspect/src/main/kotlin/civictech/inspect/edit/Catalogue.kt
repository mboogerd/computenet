package civictech.inspect.edit

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.graph.CellFactory
import civictech.cell.graph.InstanceFactory
import civictech.cell.graph.InstanceSpec
import civictech.nature.CellDescriptor
import civictech.nature.ContractRegistry
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * WKB2 F12 (va0c4-D1) — the process-wide named-cell-constructor registry a
 * browser draft resolves against. Shaped like [ContractRegistry] on purpose:
 * `register`/`entry`/`entries` mirror that registry's read/write split, and
 * for the same reason — [resolve] hands back a [CatalogueFactory] that is a
 * *data class* over `(id, params)` ([WKB2-02]; see [CatalogueFactory]'s KDoc
 * for why that shape, not a closure, is load-bearing), which resolves through
 * *this* registry again at `create` time. A factory minted against one
 * `Catalogue` instance could never `create` against another, so there can
 * only sensibly be one — the same argument that makes [ContractRegistry] a
 * singleton `object` rather than an injectable instance.
 *
 * A [CatalogueEntry]'s palette metadata (ports, color, manifest) is never
 * copied onto the entry: [CatalogueEntry.descriptor] reads
 * [ContractRegistry.cells] fresh on every call, so a registry update (e.g. a
 * dynamically loaded module, JAR1) is visible to the palette without a
 * catalogue re-registration. This is the same closed-catalogue discipline
 * `concord/schema/cell-catalog.md` documents for the contract side: the
 * catalogue is a curated, explicit list of what a draft may name, not
 * everything the registry happens to know about.
 *
 * Guarded by a single [ReentrantLock] rather than relying solely on the
 * backing [ConcurrentHashMap]'s own atomicity: [register] performs several
 * checks (duplicate id, duplicate param name, enum shape, descriptor
 * presence) that must all see the same snapshot of the map, which a bare
 * `computeIfAbsent` cannot express without leaking the validation into the
 * remapping function.
 */
object Catalogue {

    private val lock = ReentrantLock()
    private val byId = ConcurrentHashMap<String, CatalogueEntry>()

    /**
     * Registers [entry], failing loudly (feature rule 3) rather than silently
     * accepting a draft-facing entry the host cannot actually honor:
     *
     * - a duplicate [CatalogueEntry.id] is refused — re-registration is not
     *   supported; call [unregister] first;
     * - a [CatalogueEntry.descriptorFqn] absent from [ContractRegistry.cells]
     *   is refused, and nothing is registered;
     * - [ParamSchema] shape faults (a duplicate parameter name, an `ENUM`
     *   parameter with empty `values`, or a non-`ENUM` parameter with
     *   non-empty `values`) are refused.
     *
     * Every refusal is an [IllegalArgumentException] naming [entry]'s id (and,
     * where relevant, the offending fqn or parameter name).
     */
    fun register(entry: CatalogueEntry) {
        lock.withLock {
            require(!byId.containsKey(entry.id)) {
                "catalogue entry '${entry.id}': already registered"
            }
            require(ContractRegistry.cells.any { it.fqn == entry.descriptorFqn }) {
                "catalogue entry '${entry.id}': descriptor fqn '${entry.descriptorFqn}' " +
                    "is not present in ContractRegistry"
            }
            val names = mutableSetOf<String>()
            for (param in entry.schema.params) {
                require(names.add(param.name)) {
                    "catalogue entry '${entry.id}': duplicate parameter name '${param.name}'"
                }
                if (param.kind == ParamKind.ENUM) {
                    require(param.values.isNotEmpty()) {
                        "catalogue entry '${entry.id}': ENUM parameter '${param.name}' declares no values"
                    }
                } else {
                    require(param.values.isEmpty()) {
                        "catalogue entry '${entry.id}': non-ENUM parameter '${param.name}' " +
                            "(kind ${param.kind}) declares values ${param.values}"
                    }
                }
            }
            byId[entry.id] = entry
        }
    }

    /** Removes the entry [id], if present. A no-op for an unknown id. */
    fun unregister(id: String) {
        byId.remove(id)
    }

    /** The entry registered under [id], or `null` if none is. */
    fun entry(id: String): CatalogueEntry? = byId[id]

    /** A snapshot of every registered entry, sorted by id. */
    fun entries(): List<CatalogueEntry> = byId.values.sortedBy { it.id }

    /**
     * Validates [params] against the entry [id]'s [ParamSchema] and, on
     * success, returns a [CatalogueFactory] over `(id, params)` — it never
     * invokes [CatalogueEntry.build]. Refuses (all as [IllegalArgumentException]
     * naming [id] and, where applicable, the offending parameter name):
     *
     * - `id` is not registered;
     * - a required parameter is missing from `params`;
     * - `params` names a parameter the schema does not declare;
     * - a value's [ParamValue] variant does not match its [ParamSpec.kind];
     * - a `ParamValue.Enum` value is outside its [ParamSpec.values].
     */
    fun resolve(id: String, params: Map<String, ParamValue>): CatalogueFactory {
        val entry = entry(id) ?: throw IllegalArgumentException("catalogue entry '$id': not registered")
        val declared = entry.schema.params.associateBy { it.name }
        val unknown = params.keys - declared.keys
        require(unknown.isEmpty()) {
            "catalogue entry '$id': unknown parameter(s) $unknown"
        }
        for (spec in declared.values) {
            val value = params[spec.name]
            if (value == null) {
                require(!spec.required) {
                    "catalogue entry '$id': missing required parameter '${spec.name}'"
                }
                continue
            }
            val kindOk = when (value) {
                is ParamValue.Str -> spec.kind == ParamKind.STRING
                is ParamValue.I32 -> spec.kind == ParamKind.INT
                is ParamValue.I64 -> spec.kind == ParamKind.LONG
                is ParamValue.Bool -> spec.kind == ParamKind.BOOLEAN
                is ParamValue.Enum -> spec.kind == ParamKind.ENUM
                is ParamValue.Ref -> spec.kind == ParamKind.REF
            }
            require(kindOk) {
                "catalogue entry '$id': parameter '${spec.name}' expects ${spec.kind}, got $value"
            }
            if (value is ParamValue.Enum) {
                require(value.value in spec.values) {
                    "catalogue entry '$id': parameter '${spec.name}' value '${value.value}' " +
                        "is not one of ${spec.values}"
                }
            }
        }
        return CatalogueFactory(id, params)
    }
}

/** The closed set of parameter kinds a browser draft can render and encode (va0c4-D2). */
@Serializable
enum class ParamKind { STRING, INT, LONG, BOOLEAN, ENUM, REF }

/**
 * One declared parameter of a [CatalogueEntry]. [values] is populated only
 * for [ParamKind.ENUM] — [Catalogue.register] refuses any other combination.
 */
@Serializable
data class ParamSpec(
    val name: String,
    val kind: ParamKind,
    val values: List<String> = emptyList(),
    val required: Boolean = true,
)

/** The full parameter schema of a [CatalogueEntry], as served over `GET /api/inspect/catalogue`. */
@Serializable
data class ParamSchema(val params: List<ParamSpec> = emptyList())

/**
 * A resolved parameter value (va0c4-D3). Plain [java.io.Serializable], not
 * kotlinx: a [ParamValue] rides a [CatalogueFactory] inside a `SpawnStep` —
 * the same wire-crossing construction form [CellFactory] documents — and
 * never JSON directly; decoding a draft's JSON parameters into this shape is
 * task 2's [DraftCompiler]'s job.
 */
sealed interface ParamValue : java.io.Serializable {
    data class Str(val value: String) : ParamValue
    data class I32(val value: Int) : ParamValue
    data class I64(val value: Long) : ParamValue
    data class Bool(val value: Boolean) : ParamValue
    data class Enum(val value: String) : ParamValue
    data class Ref(val value: CellRef) : ParamValue
}

/**
 * Builds the [Cell] for one catalogue-resolved factory. Takes [ref] so the
 * built cell can be constructed carrying the ref its [civictech.cell.graph.IdentityBinding]
 * chose ([civictech.cell.graph.requireBoundRef], GraphDsl.kt).
 */
fun interface EntryBuilder {
    fun build(params: Map<String, ParamValue>, ref: CellRef): Cell
}

/**
 * One registered named constructor (va0c4-D1/D2). [descriptorFqn] must name a
 * fqn present in [ContractRegistry] at [Catalogue.register] time; [descriptor]
 * reads the live registry rather than caching the [CellDescriptor], so the
 * entry never drifts from what the registry currently says.
 */
data class CatalogueEntry(
    val id: String,
    val descriptorFqn: String,
    val schema: ParamSchema,
    val build: EntryBuilder,
) {
    /** The [CellDescriptor] this entry's [descriptorFqn] currently resolves to in [ContractRegistry]. */
    fun descriptor(): CellDescriptor =
        ContractRegistry.cells.first { it.fqn == descriptorFqn }
}

/**
 * PN-13/WKB2 F12 (va0c4-D3) — the [CellFactory] a [Catalogue]-resolved draft
 * node lowers to: a **data class** over `(id, params)`, exactly the reasoning
 * `InstanceCellFactory`'s KDoc gives (GraphDsl.kt) for that factory shape —
 * `equals`/`hashCode` over the recorded parameters (not over a closure
 * identity) is what makes a `SpawnStep` built from a draft structurally
 * comparable to a hand-built one ("parameters, not verbs", 51 §Graph
 * construction DSL), and a plain data class is `Serializable` for free so the
 * recorded `GraphSpec` stays graphs-as-data (G-30).
 *
 * [create] resolves [id] against the live [Catalogue] at *construction* time,
 * not at [resolve] time: an entry unregistered between compile and apply
 * surfaces here as an ordinary factory exception (propagated by the host
 * exactly as any other factory failure — see F2's precheck KDoc), never as a
 * structural precheck verdict.
 */
data class CatalogueFactory(val id: String, val params: Map<String, ParamValue>) : CellFactory {
    override fun create(ref: CellRef): Cell {
        val entry = Catalogue.entry(id)
            ?: throw IllegalStateException("catalogue entry '$id' is not registered")
        return entry.build.build(params, ref)
    }
}

/**
 * PN-13/WKB2 F12 (va0c4-D4) — the per-instance counterpart of [CatalogueFactory]
 * for a [civictech.cell.graph.InstanceSetStep]'s replicas. The [InstanceSpec]
 * is not baked into [params]: only [civictech.cell.link.Interest.Total]
 * replicas are expressible from a draft (a partitioned set needs placement
 * parameters, G-61, out of scope here), so [build] simply ignores [spec]
 * beyond delegating construction through [CatalogueFactory].
 */
data class CatalogueInstanceFactory(val id: String, val params: Map<String, ParamValue>) : InstanceFactory {
    override fun build(ref: CellRef, spec: InstanceSpec): Cell = CatalogueFactory(id, params).create(ref)
}
