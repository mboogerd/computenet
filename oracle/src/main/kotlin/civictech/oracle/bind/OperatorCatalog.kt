package civictech.oracle.bind

import civictech.cell.graph.CellFactory
import civictech.oracle.model.ReferenceOp

/**
 * The one place a catalog id binds **both** a kernel cell factory and an independent
 * reference model (epic computenet-4ru §2.3). This is the extension seam ORA2 and QRY1
 * widen: registering an operator here is what puts it in the generator's vocabulary
 * ([ORA1-API-03]) and in the differential runner's reach.
 *
 * ## Why registration is paired, and why it fails here
 *
 * `[ORA1-API-02]`: a kernel factory without a reference model, or a model without a factory,
 * is rejected **at registration time**, naming the id, and the id is *not* in the catalog
 * afterwards. This is also `[ORA1-GEN-08]`'s enforcement point — that requirement says a
 * vocabulary naming a half-bound id must fail loudly rather than silently drop the operator,
 * and the cheapest way to make it true is for a half-bound id never to exist. A sweep cannot
 * quietly skip an operator it was configured to exercise; a skipped operator is a green run
 * that checked less than it claims, which is the single failure mode this whole epic is
 * built to avoid.
 *
 * ## Why the parameters are nullable
 *
 * Epic §2.3 sketches `register(id, shape, kernel: CellFactory, model: ReferenceOp)` with
 * non-null parameters, which would make the one-sided call unrepresentable in Kotlin — and
 * therefore make `[ORA1-API-02]`'s failure untestable from Kotlin, leaving the requirement
 * defended only by the compiler of one of the two languages that can call this. The feature's
 * own example mapping asks for the failing case in as many words ("Ex/BS-17: … `model` =
 * null-equivalent absence"), so absence is expressible on purpose: a Java caller, a
 * reflective registration, or a `Map` lookup that missed all produce exactly this call, and
 * the useful behaviour is a loud named failure rather than a compile error the caller never
 * sees. The nullability is the *input* contract only — [Entry] holds both non-null.
 *
 * ## Mutability and test isolation
 *
 * A singleton with a mutable registry, per the feature's breakdown decision. Registration is
 * a process-wide side effect, so tests that register must clean up: [reset] exists for that
 * and nothing else. Access is synchronized because a sweep may register from a fixture while
 * generating on another thread; the guarantee is registry integrity, not a happens-before
 * for anything the entries themselves hold.
 */
object OperatorCatalog {

    /**
     * One fully-bound operator: an id, its machine-readable [shape], the [kernel] cell it
     * builds, and the [model] a differential run checks that cell against. Both bindings are
     * non-null by construction — an [Entry] cannot exist half-bound.
     */
    data class Entry(
        val id: String,
        val shape: ShapeRule,
        val kernel: CellFactory,
        val model: ReferenceOp,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * Binds [id] to a [kernel] cell factory and a [model] reference implementation with the
     * given [shape], and returns the resulting [Entry].
     *
     * **[kernel] and [model] are nullable on purpose, and that is a deliberate divergence from
     * epic computenet-4ru §2.3**, which sketches them non-null. Non-null would make the
     * one-sided call unrepresentable in Kotlin and therefore make `[ORA1-API-02]`'s failure
     * untestable, while a Java caller, a reflective registration or a `Map` lookup that missed
     * would still reach this method with a null and get an unnamed `NullPointerException`.
     * Expressible-and-loud beats unrepresentable-and-silent; see the type's own KDoc for the
     * full reasoning. [Entry] holds both non-null, so the nullability is the input contract only.
     *
     * @throws IllegalArgumentException if [id] is blank, or if exactly one of [kernel] and
     *   [model] is absent — the message names [id], and [id] is not in the catalog afterwards
     *   ([ORA1-API-02], BS-17).
     * @throws IllegalStateException if [id] is already registered. Silent replacement is the
     *   other way a differential run can end up exercising something other than what it was
     *   configured to exercise, so a second registration is as loud as a half one.
     */
    @Synchronized
    fun register(id: String, shape: ShapeRule, kernel: CellFactory?, model: ReferenceOp?): Entry {
        require(id.isNotBlank()) { "Catalog id must not be blank" }

        val missing = buildList {
            if (kernel == null) add("kernel cell factory")
            if (model == null) add("reference model")
        }
        require(missing.isEmpty()) {
            "Catalog id '$id' cannot be registered: no ${missing.joinToString(" and no ")}. " +
                "A kernel binding and a reference model are registered together or not at all " +
                "[ORA1-API-02]; '$id' has not been added to the catalog."
        }

        check(id !in entries) {
            "Catalog id '$id' is already registered; unregister or reset the catalog before " +
                "rebinding it. Silently replacing a binding would let a sweep exercise an " +
                "operator other than the one it was configured with."
        }

        val entry = Entry(id = id, shape = shape, kernel = kernel!!, model = model!!)
        entries[id] = entry
        return entry
    }

    /** The entry bound to [id], or `null` if nothing is. */
    @Synchronized
    fun entry(id: String): Entry? = entries[id]

    /**
     * The [ShapeRule] bound to [id], or `null` if nothing is — the read-back
     * `[ORA1-API-03]` needs: a generator picks a newly registered operator up by reading its
     * shape, never by knowing its name.
     */
    @Synchronized
    fun shapeOf(id: String): ShapeRule? = entries[id]?.shape

    /** Every registered id, in registration order. */
    @Synchronized
    fun ids(): Set<String> = LinkedHashSet(entries.keys)

    /** Every registered entry, in registration order. */
    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    /** Whether [id] is registered — `"filter" in OperatorCatalog`. */
    @Synchronized
    operator fun contains(id: String): Boolean = id in entries

    /** Drops [id]'s binding if it has one; returns whether it did. */
    @Synchronized
    fun unregister(id: String): Boolean = entries.remove(id) != null

    /**
     * Empties the registry. Test support: the catalog is process-wide, so a test that
     * registers has to undo it or the next test inherits its vocabulary.
     */
    @Synchronized
    fun reset() {
        entries.clear()
    }
}
