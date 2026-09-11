package civictech.query.parse

import civictech.query.diag.Locus

/**
 * Lexical vocabulary of the `[QRY1-LANG-01]` text surface. Deliberately small and closed:
 * every keyword (`not`, `define`, `union`, `intersect`, `except`, `all`, `left`, `right`,
 * `full`, `outer`, `join`, `on`, `true`, `false`, and the aggregate names) is lexed as
 * [IDENT] and recognised *contextually* by [QueryParser], so a relation may legitimately be
 * called `join` without the lexer taking a position on it.
 *
 * [ERROR] is a token, not an exception: the lexer is total, exactly as the parser is
 * ([QRY1-REJECT-03]). A character it cannot start a token with, or an unterminated string
 * literal, becomes an [ERROR] token carrying its own span, and the parser turns the first
 * one it reaches into a `RejectionCode.SYNTAX_ERROR`.
 */
enum class TokenKind {
    IDENT,
    INT_LIT,
    LONG_LIT,
    DOUBLE_LIT,
    STRING_LIT,
    LPAREN,
    RPAREN,
    COMMA,
    DOT,
    AT,
    IMPLIES,
    DEFINE_AS,
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    ERROR,
    EOF,
}

/**
 * One lexed token: its [kind], the source [text] it spans, its decoded [value] for literal
 * kinds (`null` otherwise), and the [span] it occupies.
 *
 * [span] is inclusive of its last character: a single-character token at line 1, column 5
 * spans `(1, 5, 1, 5)`. The end-of-input [TokenKind.EOF] token spans the position just past
 * the last character, so a rejection pointing at "the input ended here" still carries a real
 * [Locus.SourceSpan] rather than a sentinel.
 */
data class Token(
    val kind: TokenKind,
    val text: String,
    val span: Locus.SourceSpan,
    val value: Any? = null,
)

/**
 * Turns query text into a [Token] list. Never throws — see [TokenKind.ERROR].
 *
 * Numeric literals are typed lexically: a bare integer is `INT`, an `L`-suffixed integer is
 * `LONG`, and anything with a `.` or an exponent is `DOUBLE`. [QueryParser] may retype a
 * constant against the `Catalog`'s declared attribute type for the position it appears in;
 * the lexical type is the default when no declaration covers it.
 *
 * Comments run from `%` or `//` to end of line, the classic Datalog and the familiar
 * spelling respectively.
 */
object Lexer {

    fun lex(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var line = 1
        var col = 1

        fun span(sl: Int, sc: Int, el: Int, ec: Int) = Locus.SourceSpan(sl, sc, el, ec)

        fun advance(count: Int) {
            repeat(count) {
                if (i < source.length && source[i] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
                i++
            }
        }

        fun simple(kind: TokenKind, length: Int) {
            val sl = line
            val sc = col
            val text = source.substring(i, i + length)
            advance(length)
            // `col - 1` is the column of the last consumed character (spans are inclusive).
            tokens += Token(kind, text, span(sl, sc, line, col - 1))
        }

        while (i < source.length) {
            val c = source[i]
            when {
                c == '\n' || c.isWhitespace() -> advance(1)

                c == '%' -> while (i < source.length && source[i] != '\n') advance(1)

                c == '/' && i + 1 < source.length && source[i + 1] == '/' ->
                    while (i < source.length && source[i] != '\n') advance(1)

                c == '(' -> simple(TokenKind.LPAREN, 1)
                c == ')' -> simple(TokenKind.RPAREN, 1)
                c == ',' -> simple(TokenKind.COMMA, 1)
                // A `.` inside a numeric literal is consumed by the digit branch below
                // before it can be seen here, so a `.` reaching this point is a terminator.
                c == '.' -> simple(TokenKind.DOT, 1)
                c == '@' -> simple(TokenKind.AT, 1)

                c == ':' && i + 1 < source.length && source[i + 1] == '-' ->
                    simple(TokenKind.IMPLIES, 2)
                c == ':' && i + 1 < source.length && source[i + 1] == '=' ->
                    simple(TokenKind.DEFINE_AS, 2)

                c == '!' && i + 1 < source.length && source[i + 1] == '=' ->
                    simple(TokenKind.NE, 2)
                c == '<' && i + 1 < source.length && source[i + 1] == '>' ->
                    simple(TokenKind.NE, 2)
                c == '<' && i + 1 < source.length && source[i + 1] == '=' ->
                    simple(TokenKind.LE, 2)
                c == '>' && i + 1 < source.length && source[i + 1] == '=' ->
                    simple(TokenKind.GE, 2)
                c == '<' -> simple(TokenKind.LT, 1)
                c == '>' -> simple(TokenKind.GT, 1)
                c == '=' -> simple(TokenKind.EQ, 1)

                c == '"' || c == '\'' -> {
                    val quote = c
                    val sl = line
                    val sc = col
                    advance(1)
                    val sb = StringBuilder()
                    var closed = false
                    while (i < source.length) {
                        val ch = source[i]
                        if (ch == '\\' && i + 1 < source.length) {
                            val esc = source[i + 1]
                            sb.append(
                                when (esc) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    'r' -> '\r'
                                    else -> esc
                                },
                            )
                            advance(2)
                        } else if (ch == quote) {
                            advance(1)
                            closed = true
                            break
                        } else if (ch == '\n') {
                            break
                        } else {
                            sb.append(ch)
                            advance(1)
                        }
                    }
                    val sp = span(sl, sc, line, maxOf(col - 1, sc))
                    tokens += if (closed) {
                        Token(TokenKind.STRING_LIT, sb.toString(), sp, sb.toString())
                    } else {
                        Token(TokenKind.ERROR, "unterminated string literal", sp)
                    }
                }

                c.isDigit() -> {
                    val sl = line
                    val sc = col
                    val start = i
                    while (i < source.length && source[i].isDigit()) advance(1)
                    var isDouble = false
                    if (i < source.length && source[i] == '.' &&
                        i + 1 < source.length && source[i + 1].isDigit()
                    ) {
                        isDouble = true
                        advance(1)
                        while (i < source.length && source[i].isDigit()) advance(1)
                    }
                    if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                        val save = i
                        var probe = i + 1
                        if (probe < source.length && (source[probe] == '+' || source[probe] == '-')) probe++
                        if (probe < source.length && source[probe].isDigit()) {
                            isDouble = true
                            advance(probe - save)
                            while (i < source.length && source[i].isDigit()) advance(1)
                        }
                    }
                    var isLong = false
                    if (!isDouble && i < source.length && (source[i] == 'L' || source[i] == 'l')) {
                        isLong = true
                        advance(1)
                    }
                    val text = source.substring(start, i)
                    val sp = span(sl, sc, line, col - 1)
                    val digits = if (isLong) text.dropLast(1) else text
                    tokens += when {
                        isDouble -> digits.toDoubleOrNull()
                            ?.let { Token(TokenKind.DOUBLE_LIT, text, sp, it) }
                            ?: Token(TokenKind.ERROR, "malformed numeric literal '$text'", sp)
                        isLong -> digits.toLongOrNull()
                            ?.let { Token(TokenKind.LONG_LIT, text, sp, it) }
                            ?: Token(TokenKind.ERROR, "malformed numeric literal '$text'", sp)
                        else -> digits.toIntOrNull()
                            ?.let { Token(TokenKind.INT_LIT, text, sp, it) }
                            ?: digits.toLongOrNull()
                                ?.let { Token(TokenKind.LONG_LIT, text, sp, it) }
                            ?: Token(TokenKind.ERROR, "malformed numeric literal '$text'", sp)
                    }
                }

                c.isLetter() || c == '_' -> {
                    val sl = line
                    val sc = col
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) {
                        advance(1)
                    }
                    val text = source.substring(start, i)
                    tokens += Token(TokenKind.IDENT, text, span(sl, sc, line, col - 1))
                }

                else -> {
                    val sl = line
                    val sc = col
                    advance(1)
                    tokens += Token(
                        TokenKind.ERROR,
                        "unexpected character '$c'",
                        span(sl, sc, line, col - 1),
                    )
                }
            }
        }
        tokens += Token(TokenKind.EOF, "", span(line, col, line, col))
        return tokens
    }
}
