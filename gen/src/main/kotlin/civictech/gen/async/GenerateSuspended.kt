package civictech.gen.async

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateSuspended(
    val interfaceName: String = "",
    val packageName: String = "",
    val implementationClassName: String = "",
    val compositeOperationName: String = "operate",
    val compositeQueryName: String = "query",
)
