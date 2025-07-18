package civictech.gen.async

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@GenerateSuspended
interface StringRegister {
    fun get(): String
    fun set(value: String)
}

@GenerateSuspended
interface IntRegister {
    fun get(): Int
    fun set(value: Int)
}

data class StringRegisterImpl(var value: String) : StringRegister {
    override fun get(): String {
        return value
    }

    override fun set(value: String) {
        this.value = value
    }
}

data class IntRegisterImpl(var value: Int) : IntRegister {
    override fun get(): Int {
        return value
    }

    override fun set(value: Int) {
        this.value = value
    }
}


class StateOwner(
    val asyncRecipeRegistry: AsyncRecipeRegistry = defaultAsyncRecipeRegistry,
    val variables: MutableMap<String, DerivedSuspends<*>> = mutableMapOf(),
) {
    suspend inline fun <reified S, reified I: Any> put(key: String, i: I): S where S : DerivedSuspends<I> {
        val recipe = asyncRecipeRegistry.recipe<I>()
            ?: throw RuntimeException("Cannot put $i as its type is not in recipes: $asyncRecipeRegistry")

        val sendOperation = ChanneledDataOwner(i, Channel())

        val supervisedScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        supervisedScope.launch {
            sendOperation.run()
        }

        val meal = recipe.construct(sendOperation)
        variables[key] = meal
        return meal as S
    }

    operator fun <S, I> get(key: String): S? where S : Any, S : DerivedSuspends<I> {
        @Suppress("UNCHECKED_CAST")
        return variables[key] as S
    }


}

fun main() {
    val string: StringRegister = StringRegisterImpl("my string")
    val int: IntRegister = IntRegisterImpl(1)

    val stateOwner = StateOwner()


    runBlocking {
        val asyncString: AsyncStringRegister = stateOwner.put("string", string)
        val asyncInt: AsyncIntRegister = stateOwner.put("int", int)

        asyncString.set("a new string")
        println(asyncString.get())

        asyncInt.set(42)
        println(asyncInt.get())

        val newString = asyncString.query {
            set("booh")
            set("BOOH")
            get()
        }
        println(newString)

        val asyncInt2: AsyncIntRegister? = stateOwner["int"]
        println(asyncInt2?.get())
        val newInt = asyncInt2?.query {
            set(1337)
            get()
        }
        println(newInt)
    }
}

