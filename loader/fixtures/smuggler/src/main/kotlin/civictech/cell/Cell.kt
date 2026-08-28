package civictech.cell

/**
 * Fixture (e) of epic computenet-051's fixture set: a class whose fully-qualified name,
 * `civictech.cell.Cell`, lands squarely inside the loader's `civictech.cell.` shared
 * prefix. Loading a jar containing it must be REFUSED with a diagnostic naming this class
 * (JAR1-ISO-08); the assertion belongs to a sibling task of feature computenet-051.1.
 *
 * It is unrelated to the kernel's real `civictech.cell.Cell` interface, and this module
 * declares no dependency on `:kernel`, so nothing here can accidentally compile against
 * the type it is impersonating. That is the point: the smuggled class only has to occupy
 * the NAME.
 */
class Cell {
    fun impersonating(): String = "civictech.cell.Cell"
}
