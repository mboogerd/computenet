package civictech.cell.wire

import civictech.cell.link.IdentityStatement

/**
 * A [Peering.Side] whose credentials a binding's hello line cannot carry.
 * Thrown by [PeerTransport.listen] and [PeerTransport.dial] **before any
 * endpoint exists** (computenet-5y8t.6), so a configuration fault surfaces at
 * the call that configured it rather than once per connection inside a
 * reconnect loop.
 *
 * `open`, with a public constructor, so each transport's own
 * `UnsendableHelloCredentialsException` can extend it and existing catches of
 * the transport-specific name keep working (gyvli-D2).
 */
open class UnsendableHelloCredentialsException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * What a binding's hello line can carry of a side's credentials (gyvli-D2):
 * the three refusals `WsTransport` and `IrohTransport` each made in their own
 * `requireSendableHelloCredentials` — too many statements, a name that is not
 * a token, a statement the line cannot encode.
 *
 * Credentials with **no** statements are never refused: both transports send
 * their statement-free hello for them, which carries no name token.
 *
 * @param maxStatements the most statements one hello line carries.
 * @param tokenOk whether a credentials name can be the line's name token.
 * @param lineName the hello line's name, for messages (`"HELLO3"`, `"IROH-HELLO2"`).
 * @param encodeStatement the line's per-statement encoder, run for its
 *   refusals only: an [IllegalArgumentException] from it refuses the side.
 * @param encodeLine the rest of what the line encoder refuses, asked of the
 *   encoder itself over the whole credentials (`:wire` encodes a placeholder
 *   `HELLO3`); an [IllegalArgumentException] from it refuses the side.
 */
class HelloCredentialLimits(
    val maxStatements: Int,
    val tokenOk: (String) -> Boolean,
    val lineName: String,
    private val encodeStatement: (IdentityStatement) -> Unit = {},
    private val encodeLine: (PeerCredentials) -> Unit = {},
) {
    init {
        require(maxStatements >= 0) { "maxStatements must be >= 0 (was $maxStatements)" }
    }

    /** Refuses [side] with [UnsendableHelloCredentialsException] when its credentials cannot be sent. */
    fun requireSendable(side: Peering.Side) {
        val credentials = side.credentials ?: return
        val statements = credentials.statements
        if (statements.isEmpty()) return
        val name = credentials.peerId.name
        if (statements.size > maxStatements) {
            throw UnsendableHelloCredentialsException(
                "credentials for $name hold ${statements.size} statements; a $lineName line carries at most " +
                    "$maxStatements",
            )
        }
        if (!tokenOk(name)) {
            throw UnsendableHelloCredentialsException(
                "credentials name '$name' cannot be a $lineName name token",
            )
        }
        statements.forEachIndexed { index, statement ->
            try {
                encodeStatement(statement)
            } catch (e: IllegalArgumentException) {
                throw UnsendableHelloCredentialsException(
                    "credentials for $name: statement $index cannot be a $lineName token: ${e.message}",
                    e,
                )
            }
        }
        try {
            encodeLine(credentials)
        } catch (e: IllegalArgumentException) {
            throw UnsendableHelloCredentialsException(
                "credentials for $name cannot be sent in a $lineName: ${e.message}",
                e,
            )
        }
    }

    companion object {
        /** The statement ceiling both `HELLO3` and `IROH-HELLO2` declare (`MAX_HELLO_STATEMENTS`). */
        const val DEFAULT_MAX_STATEMENTS: Int = 8

        /** The name-token rule both transports apply: non-empty, no space. */
        val NAME_TOKEN: (String) -> Boolean = { it.isNotEmpty() && ' ' !in it }
    }
}
