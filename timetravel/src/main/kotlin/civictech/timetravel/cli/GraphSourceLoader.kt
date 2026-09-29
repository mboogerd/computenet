package civictech.timetravel.cli

import civictech.cell.graph.GraphSpec
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.GraphSpecSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** The outcome of [GraphSourceLoader.load] (computenet-3qkx1 D7). */
sealed interface GraphSourceLoad {
    /** [source] is `null` when no graph flag was given — `Reconstructor.of` refuses that, not the loader. */
    data class Loaded(val source: GraphSource?) : GraphSourceLoad

    /** [subject] is the file path or class name; [reason] is why it yielded no [GraphSource]. */
    data class Refused(val subject: String, val reason: String) : GraphSourceLoad
}

/**
 * Turns [GraphFlags] into a [GraphSource] (computenet-3qkx1 D2/D7, `[TTD1-23]`: refuse, never
 * guess). `--graph <file>` is a Java-serialized [GraphSpec] wrapped in [GraphSpecSource];
 * `--graph-provider <fqcn>` is a public class implementing [GraphSource], built through its public
 * `(String)` constructor when `--graph-arg` is given and its public no-arg constructor otherwise.
 * Every expected failure is a [GraphSourceLoad.Refused], never an escaping exception.
 *
 * `--graph` deserializes the file with `ObjectInputStream`: only point it at a file you trust.
 */
object GraphSourceLoader {

    fun load(flags: GraphFlags): GraphSourceLoad = when {
        flags.graphFile != null -> fromSpecFile(File(flags.graphFile))
        flags.provider != null -> fromProvider(flags.provider, flags.providerArg)
        else -> GraphSourceLoad.Loaded(null)
    }

    private fun fromSpecFile(file: File): GraphSourceLoad {
        if (!file.isFile || !file.canRead()) return GraphSourceLoad.Refused(file.path, "not a readable regular file")
        val read = try {
            ObjectInputStream(FileInputStream(file)).use { it.readObject() }
        } catch (e: IOException) {
            return GraphSourceLoad.Refused(file.path, "not a Java-serialized GraphSpec: $e")
        } catch (e: ClassNotFoundException) {
            return GraphSourceLoad.Refused(file.path, "names a class not on the classpath: ${e.message}")
        }
        val spec = read as? GraphSpec
            ?: return GraphSourceLoad.Refused(file.path, "holds a ${read?.javaClass?.name}, not a ${GraphSpec::class.java.name}")
        return GraphSourceLoad.Loaded(GraphSpecSource(spec))
    }

    private fun fromProvider(className: String, arg: String?): GraphSourceLoad {
        val cls = try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            return GraphSourceLoad.Refused(className, "class not found")
        } catch (e: LinkageError) {
            return GraphSourceLoad.Refused(className, "class could not be loaded: $e")
        }
        if (!GraphSource::class.java.isAssignableFrom(cls)) {
            return GraphSourceLoad.Refused(className, "does not implement ${GraphSource::class.java.name}")
        }
        if (Modifier.isAbstract(cls.modifiers)) return GraphSourceLoad.Refused(className, "is abstract")
        val constructor = try {
            if (arg != null) cls.getConstructor(String::class.java) else cls.getConstructor()
        } catch (e: NoSuchMethodException) {
            val wanted = if (arg != null) "public (String) constructor (for --graph-arg)" else "public no-arg constructor"
            return GraphSourceLoad.Refused(className, "has no $wanted")
        }
        val instance = try {
            if (arg != null) constructor.newInstance(arg) else constructor.newInstance()
        } catch (e: InvocationTargetException) {
            return GraphSourceLoad.Refused(className, "constructor threw ${e.targetException}")
        } catch (e: ReflectiveOperationException) {
            return GraphSourceLoad.Refused(className, "could not be instantiated: $e")
        }
        return GraphSourceLoad.Loaded(instance as GraphSource)
    }
}
