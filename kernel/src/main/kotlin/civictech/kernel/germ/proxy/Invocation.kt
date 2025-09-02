package civictech.kernel.germ.proxy

import java.lang.reflect.Method

data class Invocation(val method: Method?, val args: List<Any>) {
    operator fun invoke(target: Any?) = method?.invoke(
        target,
        *(args.toTypedArray())
    )

    companion object {
        fun of(method: Method?, args: Array<out Any>?): Invocation {
            return Invocation(method, args?.toList() ?: listOf())
        }
    }
}