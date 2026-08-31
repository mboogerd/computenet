package civictech.query.schema

import java.io.Serializable

/**
 * One relation attribute: its [name] and declared [type] (`[QRY1-LANG-05]`). Pure data,
 * `Serializable` per `[QRY1-LANG-06]`.
 */
data class Attribute(val name: String, val type: AttrType) : Serializable
