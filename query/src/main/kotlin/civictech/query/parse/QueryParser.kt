package civictech.query.parse

import civictech.query.ast.Aggregate
import civictech.query.ast.AggregateKind
import civictech.query.ast.Atom
import civictech.query.ast.ComparisonOp
import civictech.query.ast.Definition
import civictech.query.ast.JoinKey
import civictech.query.ast.Literal
import civictech.query.ast.OuterJoinSide
import civictech.query.ast.Query
import civictech.query.ast.RelationalExpr
import civictech.query.ast.Rule
import civictech.query.ast.SetOpKind
import civictech.query.ast.Term
import civictech.query.diag.Locus
import civictech.query.diag.Rejection
import civictech.query.diag.RejectionCode
import civictech.query.schema.AttrType
import civictech.query.schema.Catalog

/**
 * The text half of `[QRY1-LANG-02]`'s two surfaces: a hand-rolled recursive-descent parser
 * from the non-recursive Datalog text of `[QRY1-LANG-01]`/`[QRY1-LANG-03]`/`[QRY1-LANG-04]`
 * onto the *same* `civictech.query.ast` vocabulary `query { }` produces. Equivalence is
 * asserted at the AST, by structural equality (cab.2-D1) — see `SurfaceEquivalenceTest`.
 *
 * ## Grammar
 *
 * ```
 * program     := statement* EOF
 * statement   := definition | rule
 * definition  := 'define' atom ':=' relExpr '.'
 * rule        := aggregate? atom (':-' body)? '.'
 * aggregate   := '@' IDENT ('(' INT ')')?
 * body        := literal (',' literal)*
 * literal     := 'not' atom | atom | term compareOp term
 * atom        := IDENT '(' (term (',' term)*)? ')'
 * term        := IDENT | INT | LONG | DOUBLE | STRING | 'true' | 'false'
 * relExpr     := joinExpr (setOp 'all'? joinExpr)*
 * setOp       := 'union' | 'intersect' | 'except'
 * joinExpr    := primary (('left'|'right'|'full') 'outer' 'join' primary 'on' joinKeys)*
 * joinKeys    := VAR '=' VAR (',' VAR '=' VAR)*
 * primary     := '(' relExpr ')' | atom
 * compareOp   := '=' | '!=' | '<>' | '<' | '<=' | '>' | '>='
 * ```
 *
 * Rule syntax is classic Datalog, as `[QRY1-LANG-01]` quotes it. The concrete spelling of
 * the aggregate annotation and of the `[QRY1-LANG-04]` statement forms is this task's
 * judgment (the bead grants it): nothing downstream binds to the text, because equivalence
 * is anchored at the AST. Set operations and outer joins are written as statements — the
 * `define` form — because they name a derived relation, which is exactly what `Definition`
 * is; a rule body stays the literal list `[QRY1-LANG-01]` describes and gains nothing.
 *
 * Keywords are **contextual**, recognised case-insensitively at the positions the grammar
 * allows them; a relation may still be called `join` or `on`.
 *
 * ## Variables, constants, and typing
 *
 * An identifier in term position is a variable when it starts with an uppercase letter or
 * `_`, following Datalog convention; `true`/`false` are boolean constants; any other
 * lowercase identifier is a `STRING` constant (the classic Datalog "symbol"). Numeric
 * literals are typed lexically — bare integer `INT`, `L`-suffixed `LONG`, fractional or
 * exponent-bearing `DOUBLE` — and then **retyped against the `Catalog`** when the constant
 * sits at a declared attribute position of a declared relation and the value converts
 * losslessly into the declared `AttrType`. That is what makes `dist("a", "b", 10)` produce
 * the `Term.Const(10L, LONG)` the builder's `const(10L)` produces, without the author
 * spelling the suffix.
 *
 * When no declaration covers the position, or the declared type cannot hold the literal,
 * the lexical type stands. The parser passes **no judgment** on the mismatch: a
 * constant whose type disagrees with its column is a semantic rejection owned by the
 * analysis feature, and refusing it here would mis-attribute it to a syntax error.
 *
 * ## Totality and per-statement recovery
 *
 * [parse] never throws ([QRY1-REJECT-03]'s front-door half). A failure inside one statement —
 * an unexpected token, a missing terminator, an unbalanced parenthesis, an unterminated
 * string, a nesting depth beyond [MAX_NESTING], an aggregate name outside the closed seven —
 * becomes one [Rejection] located at the offending [Locus.SourceSpan]. [Parser.program]
 * catches it there, records it, and recovers (cab.5-D7) before continuing with the next
 * statement.
 *
 * **Synchronization rule.** [Parser.recover] first checks the exact token the failure left it
 * on — never a token reached later by skipping — against [Parser.canStartStatement]: an
 * identifier immediately followed by `(` (a rule or definition head), `define` followed by an
 * identifier, or `@` followed by an identifier (an aggregate-annotated rule). If it matches,
 * recovery consumes nothing and [program] attempts that token as a fresh statement. Otherwise
 * it falls back to skipping token-by-token to the next `.` (consumed) or EOF, exactly as if no
 * statement-start check existed. The check fires once, at the failure point only, rather than
 * on every token skipped: a construct like unbounded nesting fails deep inside an *unclosed*
 * parenthesis run, where a coincidental `IDENT (` further along (still inside those
 * parentheses) is not a new statement at all, and continuously re-checking during the skip
 * would misread it as one and split a single failure into two — that regressed
 * `` `nesting beyond the bound is refused rather than overflowing the stack` `` under an
 * earlier version of this rule that scanned for the pattern throughout the skip, not only at
 * entry. Checking only the failure token is what keeps a *missing* terminator from cascading
 * without over-splitting a mid-expression failure: a statement whose next token IS a fresh
 * head (the reviewer's probe: `bad(X) :- link(X, Y)` with no `.`, directly followed by
 * `good(X, Y) :- ...`) resumes at `good` untouched, while a deeply nested failure's debris —
 * all closing parentheses and no real statement boundary until the eventual `.` — is skipped
 * in one run exactly as before. Whichever branch [program] takes on a resumed token
 * ([Parser.definition] or [Parser.rule]) consumes at least the two tokens that matched before
 * it could fail again, so the token position strictly advances and [program] always
 * terminates. [ParseResult.Rejected] therefore carries every statement's rejection together
 * with the *partial* query of the statements that did parse ([QRY1-REJECT-10]).
 *
 * ## Order-dependent aggregates
 *
 * An aggregate name outside `[QRY1-LANG-03]`'s closed seven is syntactically well-formed —
 * `@`, then an identifier — so refusing it is not a syntax error. `@first`, `@last` and
 * `@scan` are refused as [RejectionCode.ORDER_DEPENDENT_AGGREGATE] because the set-semantic
 * operator algebra excludes arrival-order aggregates by rule ([24-AGG-01]); any other unknown
 * name is refused with the same code, because it is likewise not an aggregate
 * `[QRY1-LANG-03]` admits ([QRY1-SEM-05]).
 */
object QueryParser {

    /** Maximum parenthesis/expression nesting depth, past which the input is refused. */
    const val MAX_NESTING: Int = 64

    /** The spec id every syntax rejection from this parser names ([QRY1-REJECT-02]). */
    const val SPEC_ID: String = "[QRY1-REJECT-03]"

    private val SET_OPS = mapOf(
        "union" to SetOpKind.UNION,
        "intersect" to SetOpKind.INTERSECTION,
        "except" to SetOpKind.DIFFERENCE,
    )

    private val JOIN_SIDES = mapOf(
        "left" to OuterJoinSide.LEFT,
        "right" to OuterJoinSide.RIGHT,
        "full" to OuterJoinSide.FULL,
    )

    /**
     * Aggregate names that are syntactically well-formed but semantically excluded as
     * arrival-order-dependent ([QRY1-SEM-05], citing [24-AGG-01]) rather than merely unknown.
     */
    private val ORDER_DEPENDENT_NAMES = setOf("first", "last", "scan")

    private val AGGREGATES = mapOf(
        "count" to AggregateKind.COUNT,
        "sum" to AggregateKind.SUM,
        "avg" to AggregateKind.AVG,
        "min" to AggregateKind.MIN,
        "max" to AggregateKind.MAX,
        "topk" to AggregateKind.TOP_K,
        "collecttoset" to AggregateKind.COLLECT_TO_SET,
    )

    /**
     * Parses [source] against [catalog]. Total: returns [ParseResult.Rejected] rather than
     * throwing, for every input. [Parser.program] recovers per statement internally, so this
     * only ever sees a [ParseError] the recovery loop itself cannot come from — none does
     * today, but the catch stays as the totality backstop the class KDoc promises.
     */
    fun parse(source: String, catalog: Catalog = Catalog(emptyMap())): ParseResult =
        try {
            Parser(Lexer.lex(source), catalog).program()
        } catch (e: ParseError) {
            val empty = Query(rules = emptyList(), catalog = catalog, definitions = emptyList())
            ParseResult.Rejected(
                rejections = listOf(e.rejection),
                partial = empty,
                spans = SpanTable(rules = emptyList(), definitions = emptyList()),
            )
        }

    /**
     * Internal control flow only — caught by [parse], which is why it carries no stack
     * trace. Nothing outside this file can observe it: totality means the *caller* never
     * sees a throw, not that the implementation avoids one internally.
     */
    private class ParseError(val rejection: Rejection, detail: String) :
        Exception(detail, null, false, false)

    private class Parser(private val tokens: List<Token>, private val catalog: Catalog) {

        private var pos = 0
        private var depth = 0

        // ------------------------------------------------------------- token access

        private fun peek(offset: Int = 0): Token =
            tokens[minOf(pos + offset, tokens.size - 1)]

        private fun at(kind: TokenKind): Boolean = peek().kind == kind

        private fun atKeyword(word: String): Boolean =
            peek().kind == TokenKind.IDENT && peek().text.lowercase() == word

        /**
         * The value [keywords] maps the current token to, when that token is an identifier
         * spelling one of them — the contextual-keyword lookup. `null` otherwise, which is
         * how a relation named `union` or `left` stays usable as a relation.
         */
        private fun <T> keywordOf(keywords: Map<String, T>): T? =
            if (peek().kind == TokenKind.IDENT) keywords[peek().text.lowercase()] else null

        private fun advance(): Token = peek().also { if (pos < tokens.size - 1) pos++ }

        private fun expect(kind: TokenKind, what: String): Token {
            if (peek().kind == TokenKind.ERROR) fail(peek(), peek().text)
            if (peek().kind != kind) fail(peek(), "expected $what")
            return advance()
        }

        /**
         * [message] is developer-facing only — it rides on the internal [ParseError] for a
         * stack trace a maintainer might print, and is deliberately NOT part of the
         * [Rejection], whose shape [QRY1-REJECT-02] fixes to code + locus + spec id.
         */
        private fun fail(token: Token, message: String): Nothing {
            val seen = if (token.kind == TokenKind.EOF) "end of input" else "'${token.text}'"
            val where = "line ${token.span.startLine}, column ${token.span.startColumn}"
            throw ParseError(
                rejection = Rejection(
                    code = RejectionCode.SYNTAX_ERROR,
                    locus = token.span,
                    specId = SPEC_ID,
                ),
                detail = "$message, but found $seen at $where",
            )
        }

        /**
         * [name] is an identifier outside `[QRY1-LANG-03]`'s closed seven: syntactically a
         * fine aggregate name, semantically excluded ([QRY1-SEM-05]). `first`/`last`/`scan`
         * cite [24-AGG-01]'s arrival-order exclusion by name; anything else is simply not an
         * aggregate the language admits.
         */
        private fun failOrderDependent(name: Token): Nothing {
            val specId = if (name.text.lowercase() in ORDER_DEPENDENT_NAMES) {
                "[QRY1-SEM-05] '@${name.text}' is an arrival-order aggregate; " +
                    "[24-AGG-01] excludes it by rule"
            } else {
                "[QRY1-SEM-05] '@${name.text}' is not an aggregate [QRY1-LANG-03] admits"
            }
            throw ParseError(
                rejection = Rejection(
                    code = RejectionCode.ORDER_DEPENDENT_AGGREGATE,
                    locus = name.span,
                    specId = specId,
                ),
                detail = "unknown aggregate '${name.text}', but found it at line " +
                    "${name.span.startLine}, column ${name.span.startColumn}",
            )
        }

        private fun <T> nested(block: () -> T): T {
            if (++depth > MAX_NESTING) fail(peek(), "nesting deeper than $MAX_NESTING")
            return try {
                block()
            } finally {
                depth--
            }
        }

        // ------------------------------------------------------------- statements

        fun program(): ParseResult {
            val rules = mutableListOf<Rule>()
            val definitions = mutableListOf<Definition>()
            val ruleSpans = mutableListOf<Locus.SourceSpan>()
            val definitionSpans = mutableListOf<Locus.SourceSpan>()
            val rejections = mutableListOf<Rejection>()

            while (!at(TokenKind.EOF)) {
                val start = peek()
                try {
                    if (at(TokenKind.ERROR)) fail(peek(), peek().text)
                    if (atKeyword("define") && peek(1).kind == TokenKind.IDENT) {
                        val (definition, end) = definition()
                        definitions += definition
                        definitionSpans += spanning(start, end)
                    } else {
                        val (rule, end) = rule()
                        rules += rule
                        ruleSpans += spanning(start, end)
                    }
                } catch (e: ParseError) {
                    rejections += e.rejection
                    recover()
                }
            }

            val query = Query(rules = rules, catalog = catalog, definitions = definitions)
            val spans = SpanTable(rules = ruleSpans, definitions = definitionSpans)
            return if (rejections.isEmpty()) {
                ParseResult.Parsed(query = query, spans = spans)
            } else {
                ParseResult.Rejected(rejections = rejections, partial = query, spans = spans)
            }
        }

        /**
         * Recovery after one statement's [ParseError] (cab.5-D7). If the token the failure
         * left [pos] on already [canStartStatement], recovery consumes nothing — [program]
         * retries right there rather than treating a perfectly good next statement as more of
         * the failed one's debris (the fix for the missing-terminator cascade the task review
         * caught, computenet-cab.5.3, 2026-09-14). Otherwise it falls back to skipping
         * token-by-token to the next `.` (consumed here, so [program] starts the next
         * statement clean) or EOF. The statement-start check runs only once, at the failure
         * token, never again while skipping — see the class KDoc's "Synchronization rule" for
         * why continuous re-checking misfires on nested constructs.
         */
        private fun recover() {
            if (canStartStatement()) return
            while (!at(TokenKind.EOF) && !at(TokenKind.DOT)) advance()
            if (at(TokenKind.DOT)) advance()
        }

        /**
         * Whether the current token could begin a fresh [definition] or [rule], the same
         * shapes [program] itself dispatches on: `define` followed by an identifier, `@`
         * followed by an identifier (an aggregate-annotated rule), or an identifier
         * immediately followed by `(` (a bare rule/definition head). Used only by [recover],
         * and only at the moment a statement's [ParseError] is caught.
         */
        private fun canStartStatement(): Boolean =
            (atKeyword("define") && peek(1).kind == TokenKind.IDENT) ||
                (at(TokenKind.AT) && peek(1).kind == TokenKind.IDENT) ||
                (at(TokenKind.IDENT) && peek(1).kind == TokenKind.LPAREN)

        private fun spanning(start: Token, end: Token) = Locus.SourceSpan(
            startLine = start.span.startLine,
            startColumn = start.span.startColumn,
            endLine = end.span.endLine,
            endColumn = end.span.endColumn,
        )

        /** `'define' atom ':=' relExpr '.'` — returns the statement and its final token. */
        private fun definition(): Pair<Definition, Token> {
            advance() // 'define'
            val head = atom()
            expect(TokenKind.DEFINE_AS, "':=' after a definition head")
            val expr = relExpr()
            val dot = expect(TokenKind.DOT, "'.' to end the definition")
            return Definition(head, expr) to dot
        }

        /** `aggregate? atom (':-' body)? '.'` — returns the rule and its final token. */
        private fun rule(): Pair<Rule, Token> {
            val aggregate = if (at(TokenKind.AT)) aggregate() else null
            val head = atom()
            val body = if (at(TokenKind.IMPLIES)) {
                advance()
                body()
            } else {
                emptyList()
            }
            val dot = expect(TokenKind.DOT, "'.' to end the rule")
            return Rule(head, body, aggregate) to dot
        }

        /** `'@' IDENT ('(' INT ')')?` over `[QRY1-LANG-03]`'s closed seven. */
        private fun aggregate(): Aggregate {
            advance() // '@'
            val name = expect(TokenKind.IDENT, "an aggregate name after '@'")
            val kind = AGGREGATES[name.text.lowercase()] ?: failOrderDependent(name)
            var k: Int? = null
            if (at(TokenKind.LPAREN)) {
                advance()
                val kTok = expect(TokenKind.INT_LIT, "an integer k")
                k = kTok.value as Int
                if (k <= 0) fail(kTok, "topK's k must be positive")
                expect(TokenKind.RPAREN, "')' after the aggregate parameter")
            }
            if (kind == AggregateKind.TOP_K && k == null) {
                fail(name, "topK requires a positive k, as '@topK(n)'")
            }
            if (kind != AggregateKind.TOP_K && k != null) {
                fail(name, "'${name.text}' takes no parameter")
            }
            return Aggregate(kind, k)
        }

        // ------------------------------------------------------------- rule bodies

        private fun body(): List<Literal> {
            val literals = mutableListOf(literal())
            while (at(TokenKind.COMMA)) {
                advance()
                literals += literal()
            }
            return literals
        }

        private fun literal(): Literal {
            if (atKeyword("not") && peek(1).kind == TokenKind.IDENT) {
                advance()
                return Literal.Negated(atom())
            }
            if (at(TokenKind.IDENT) && peek(1).kind == TokenKind.LPAREN) {
                return Literal.Positive(atom())
            }
            val left = term(declaredType = null)
            val op = comparisonOp()
            val right = term(declaredType = null)
            return Literal.Comparison(left, op, right)
        }

        private fun comparisonOp(): ComparisonOp = when (peek().kind) {
            TokenKind.EQ -> ComparisonOp.EQ
            TokenKind.NE -> ComparisonOp.NE
            TokenKind.LT -> ComparisonOp.LT
            TokenKind.LE -> ComparisonOp.LE
            TokenKind.GT -> ComparisonOp.GT
            TokenKind.GE -> ComparisonOp.GE
            else -> fail(peek(), "expected a comparison operator or an atom")
        }.also { advance() }

        // ------------------------------------------------------------- atoms, terms

        private fun atom(): Atom {
            val name = expect(TokenKind.IDENT, "a predicate name")
            expect(TokenKind.LPAREN, "'(' after the predicate name '${name.text}'")
            val declared = catalog.relations[name.text]?.attributes
            val terms = mutableListOf<Term>()
            if (!at(TokenKind.RPAREN)) {
                while (true) {
                    terms += term(declaredType = declared?.getOrNull(terms.size)?.type)
                    if (at(TokenKind.COMMA)) advance() else break
                }
            }
            expect(TokenKind.RPAREN, "')' to close the argument list of '${name.text}'")
            return Atom(name.text, terms)
        }

        /**
         * One term. [declaredType] is the `Catalog`'s type for this argument position when
         * there is one — the constant is retyped to it when the literal converts losslessly,
         * and keeps its lexical type otherwise (no judgment here; see the class KDoc).
         */
        private fun term(declaredType: AttrType?): Term {
            val token = peek()
            return when (token.kind) {
                TokenKind.IDENT -> {
                    advance()
                    when {
                        isVariableName(token.text) -> Term.Var(token.text)
                        token.text.lowercase() == "true" -> constant(true, AttrType.BOOL, declaredType)
                        token.text.lowercase() == "false" -> constant(false, AttrType.BOOL, declaredType)
                        else -> constant(token.text, AttrType.STRING, declaredType)
                    }
                }
                TokenKind.INT_LIT -> {
                    advance(); constant(token.value!!, AttrType.INT, declaredType)
                }
                TokenKind.LONG_LIT -> {
                    advance(); constant(token.value!!, AttrType.LONG, declaredType)
                }
                TokenKind.DOUBLE_LIT -> {
                    advance(); constant(token.value!!, AttrType.DOUBLE, declaredType)
                }
                TokenKind.STRING_LIT -> {
                    advance(); constant(token.value!!, AttrType.STRING, declaredType)
                }
                TokenKind.ERROR -> fail(token, token.text)
                else -> fail(token, "expected a variable or a constant")
            }
        }

        private fun isVariableName(text: String): Boolean =
            text.first() == '_' || text.first().isUpperCase()

        private fun constant(value: Any, lexical: AttrType, declared: AttrType?): Term.Const {
            val coerced = declared?.let { coerce(value, it) }
            return if (coerced != null) Term.Const(coerced, declared) else Term.Const(value, lexical)
        }

        /** The value as [target]'s runtime type, or `null` when it does not convert losslessly. */
        private fun coerce(value: Any, target: AttrType): Any? = when (target) {
            AttrType.INT -> when (value) {
                is Int -> value
                is Long -> value.toInt().takeIf { it.toLong() == value }
                else -> null
            }
            AttrType.LONG -> when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }
            AttrType.DOUBLE -> when (value) {
                is Int -> value.toDouble()
                is Long -> value.toDouble().takeIf { it.toLong() == value }
                is Double -> value
                else -> null
            }
            AttrType.STRING -> value as? String
            AttrType.BOOL -> value as? Boolean
        }

        // ------------------------------------------------------------- relational algebra

        /** `joinExpr (setOp 'all'? joinExpr)*`, left-associative. */
        private fun relExpr(): RelationalExpr = nested {
            var left = joinExpr()
            while (true) {
                val kind = keywordOf(SET_OPS) ?: break
                advance()
                val all = atKeyword("all").also { if (it) advance() }
                left = RelationalExpr.SetOp(kind, left, joinExpr(), all)
            }
            left
        }

        /** `primary (side 'outer' 'join' primary 'on' joinKeys)*`, binding tighter than a set op. */
        private fun joinExpr(): RelationalExpr = nested {
            var left = primary()
            while (true) {
                val side = keywordOf(JOIN_SIDES)?.takeIf { isOuterJoinAhead() } ?: break
                advance() // side
                advance() // 'outer'
                expectKeyword("join")
                val right = primary()
                expectKeyword("on")
                left = RelationalExpr.OuterJoin(side, left, right, joinKeys())
            }
            left
        }

        private fun isOuterJoinAhead(): Boolean =
            peek(1).kind == TokenKind.IDENT && peek(1).text.lowercase() == "outer"

        private fun expectKeyword(word: String) {
            if (!atKeyword(word)) fail(peek(), "expected '$word'")
            advance()
        }

        private fun joinKeys(): List<JoinKey> {
            val keys = mutableListOf(joinKey())
            while (at(TokenKind.COMMA)) {
                advance()
                keys += joinKey()
            }
            return keys
        }

        private fun joinKey(): JoinKey {
            val left = joinKeyVar()
            expect(TokenKind.EQ, "'=' between the two variables of a join key")
            return JoinKey(left, joinKeyVar())
        }

        private fun joinKeyVar(): Term.Var {
            val token = peek()
            if (token.kind != TokenKind.IDENT || !isVariableName(token.text)) {
                fail(token, "a join key relates two variables")
            }
            advance()
            return Term.Var(token.text)
        }

        private fun primary(): RelationalExpr = nested {
            if (at(TokenKind.LPAREN)) {
                advance()
                val inner = relExpr()
                expect(TokenKind.RPAREN, "')' to close a parenthesised expression")
                inner
            } else {
                RelationalExpr.Relation(atom())
            }
        }
    }
}
