package com.example

/**
 * Fixture (d), half A: a NON-shared class — `com.example.` matches no shared prefix — that
 * `:loader:fixtures:util-b` also declares, under the same fully-qualified name, with a
 * different body.
 *
 * [tag] is the observable difference. It is a plain instance method on a class with a
 * no-argument constructor rather than a Kotlin `object` or a top-level function, so a test
 * can resolve the class through a module classloader and invoke it reflectively without
 * depending on Kotlin's `INSTANCE`-field or `UtilKt`-facade naming.
 */
class Util {
    fun tag(): String = "A"
}
