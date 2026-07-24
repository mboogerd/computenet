package civictech.gen.wire

/**
 * Marks a cell Api interface for base-class generation: the processor emits an
 * abstract `<Name>CellBase` (the `Api` suffix stripped) that declares and
 * registers every port the interface exposes and statically binds each inlet
 * to a handler:
 *
 * - `Serve<Propagate<T>>` / `Use<Propagate<T>>` → a registered [FanInlet] and
 *   `protected abstract fun on<Name>(value: T)`, bound at construction —
 *   the handler is a plain overridden method, no `serve`/`onEach` ceremony.
 * - `Serve<C>` / `Use<C>` for a non-Propagate contract `C` → a registered
 *   [FanInlet] and `protected abstract fun <name>Handler(): C`, served at
 *   construction. NOTE: the handler factory runs during base-class init,
 *   before the subclass's own initializers — return an object that *captures*
 *   `this`, don't *read* subclass state inside the factory itself (the same
 *   discipline today's hand-written `init { inlet.serve(inletApi) }` needs).
 * - `Subscribe<P>` → a registered [FanOutlet]; emission stays the cell's own
 *   logic (call `outlet.call…` from handlers, `catchUpOnLinked` at will).
 *
 * Port property names mirror the interface's, so the registry-name ==
 * property-name invariant (G-17) holds by construction — the mis-registration
 * the spawn-time check exists to catch cannot be written in this style.
 *
 * Generation is two-round: bases emit first, tables/proxies/Ports second, so
 * cells extending a generated base resolve like any other cell and get their
 * descriptor rows, `<Cell>Ports` ids, and the spawn-time name check as usual.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class CellBase
