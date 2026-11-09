package com.dbthelper.core

/**
 * Builds a runnable query out of part of a dbt model: the CTE under the caret (or the selected text)
 * plus every CTE it reads, directly or not, in their original order.
 *
 * This is a plain-text scanner on purpose: model files are Jinja templates, so SQL PSI is not
 * reliable on them. Jinja tags, comments and string literals are skipped as opaque spans, so
 * parentheses or keywords inside them don't count.
 */
object CteExtractor {

    data class Cte(
        /** Name without quotes, for matching. */
        val name: String,
        /** Name as written, quotes included, for the generated `select * from`. */
        val identifier: String,
        /** Offset of the CTE name. */
        val start: Int,
        /** Offset just after the closing parenthesis of the CTE body. */
        val end: Int,
        /** The `with` clause is `with recursive`. */
        val recursive: Boolean = false,
    )

    enum class Kind {
        /** The CTE under the caret or selection, with the CTEs it reads. */
        CTE,
        /** The whole file: no selection and the caret outside every CTE. */
        WHOLE,
        /** Selected text outside the CTEs, with the CTEs it reads. */
        SELECTION,
    }

    data class Extraction(
        /** SQL to run, still holding the model's Jinja. */
        val sql: String,
        val kind: Kind,
        /** Name of the CTE that was picked, for [Kind.CTE]. */
        val targetCte: String? = null,
    )

    /**
     * @param text the whole model file
     * @param selectionStart start of the selection, or the caret offset when nothing is selected
     * @param selectionEnd end of the selection, or the caret offset when nothing is selected
     */
    fun extract(text: String, selectionStart: Int, selectionEnd: Int): Extraction {
        val ctes = parseCtes(text)
        val hasSelection = selectionEnd > selectionStart

        // A selection inside one CTE (or just a caret in it) means "run that CTE".
        val target = ctes.firstOrNull { selectionStart >= it.start && selectionEnd <= it.end }
        if (target != null) return Extraction(cteQuery(text, ctes, target), Kind.CTE, target.name)

        if (!hasSelection) return Extraction(text.trim(), Kind.WHOLE)

        // Anything else: the selected text, with the CTEs it reads.
        val body = text.substring(selectionStart, selectionEnd)
        if (startsWithWith(body)) return Extraction(body.trim(), Kind.SELECTION)
        val deps = dependencies(text, ctes, body, before = Int.MAX_VALUE)
        if (deps.isEmpty()) return Extraction(body.trim(), Kind.SELECTION)
        return Extraction(
            "with ${deps.joinToString(",\n") { text.substring(it.start, it.end) }}\n${body.trim()}",
            Kind.SELECTION,
        )
    }

    /**
     * The query for CTE [name] in [text], with the CTEs it reads. Used on compiled SQL: CTE names
     * survive compilation, so the CTE picked in the model file can be found again there.
     */
    fun cteQuery(text: String, name: String): String? {
        val ctes = parseCtes(text)
        val target = ctes.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return cteQuery(text, ctes, target)
    }

    private fun cteQuery(text: String, ctes: List<Cte>, target: Cte): String {
        val deps = dependencies(text, ctes, text.substring(target.start, target.end), before = target.start)
        val parts = deps.map { text.substring(it.start, it.end) } + text.substring(target.start, target.end)
        val with = if (target.recursive) "with recursive" else "with"
        return "$with ${parts.joinToString(",\n")}\nselect * from ${target.identifier}"
    }

    /** CTEs read by [sql], directly or through other CTEs, defined before [before], in file order. */
    private fun dependencies(text: String, ctes: List<Cte>, sql: String, before: Int): List<Cte> {
        val candidates = ctes.filter { it.start < before }
        val patterns = candidates.associateWith { Regex("""(?i)(?<![\w.])${Regex.escape(it.name)}(?![\w])""") }
        val picked = mutableSetOf<Cte>()
        val queue = ArrayDeque(listOf(sql))
        while (queue.isNotEmpty()) {
            val code = stripOpaque(queue.removeFirst())
            for (cte in candidates) {
                if (cte !in picked && patterns.getValue(cte).containsMatchIn(code)) {
                    picked += cte
                    // Only the body: the CTE name itself would match itself.
                    val bodyStart = text.indexOf('(', cte.start + cte.identifier.length)
                    queue += text.substring(bodyStart, cte.end)
                }
            }
        }
        return candidates.filter { it in picked }
    }

    /** CTEs of the first top-level `with` clause. Empty when the model has none. */
    fun parseCtes(text: String): List<Cte> {
        val s = Scanner(text)
        // Find the first top-level `with` keyword.
        var found = false
        while (!s.eof()) {
            if (s.skipOpaque()) continue
            val c = text[s.pos]
            when {
                c == '(' -> { s.depth++; s.pos++ }
                c == ')' -> { s.depth--; s.pos++ }
                s.depth == 0 && s.atWord("with") -> { s.pos += 4; found = true; break }
                c.isLetterOrDigit() || c == '_' -> s.skipWord()
                else -> s.pos++
            }
        }
        if (!found) return emptyList()

        val ctes = mutableListOf<Cte>()
        s.skipBlank()
        val recursive = s.atWord("recursive")
        if (recursive) { s.pos += "recursive".length; s.skipBlank() }
        while (!s.eof()) {
            val nameStart = s.pos
            val name = s.readIdentifier() ?: break
            s.skipBlank()
            if (s.peek() == '(') { // column list: name (a, b) as (...)
                s.skipParens() ?: break
                s.skipBlank()
            }
            if (!s.atWord("as")) break
            s.pos += 2
            s.skipBlank()
            if (s.atWord("not")) { s.pos += 3; s.skipBlank() }
            if (s.atWord("materialized")) { s.pos += "materialized".length; s.skipBlank() }
            if (s.peek() != '(') break
            val end = s.skipParens() ?: break
            ctes += Cte(name.trim('"', '`'), name, nameStart, end, recursive)
            s.skipBlank()
            if (s.peek() != ',') break
            s.pos++
            s.skipBlank()
        }
        return ctes
    }

    private fun startsWithWith(sql: String): Boolean =
        Regex("""^\s*with\b""", RegexOption.IGNORE_CASE).containsMatchIn(stripOpaque(sql))

    /** Blanks comments, string literals and Jinja tags so names inside them (e.g. ref('x')) don't match. */
    private fun stripOpaque(sql: String): String = OPAQUE.replace(sql) { " ".repeat(it.value.length) }

    private val OPAQUE = Regex("""--[^\n]*|/\*.*?\*/|\{#.*?#}|\{\{.*?}}|\{%.*?%}|'(?:[^'\\]|\\.|'')*'""", RegexOption.DOT_MATCHES_ALL)

    private class Scanner(val text: String) {
        var pos = 0
        var depth = 0

        fun eof() = pos >= text.length
        fun peek(): Char? = text.getOrNull(pos)

        fun atWord(word: String): Boolean =
            text.regionMatches(pos, word, 0, word.length, ignoreCase = true) &&
                (pos == 0 || !isWordChar(text[pos - 1])) &&
                text.getOrNull(pos + word.length)?.let { !isWordChar(it) } != false

        fun skipWord() { while (!eof() && isWordChar(text[pos])) pos++ }

        /** Skips a comment, string, quoted identifier or Jinja tag at [pos]. Returns true if it did. */
        fun skipOpaque(): Boolean {
            val rest = text.startsWith(pos)
            val end = when {
                rest("--") -> text.indexOf('\n', pos).let { if (it < 0) text.length else it + 1 }
                rest("/*") -> closeAfter("*/")
                rest("{#") -> closeAfter("#}")
                rest("{{") -> closeAfter("}}")
                rest("{%") -> closeAfter("%}")
                peek() == '\'' -> closeQuote('\'')
                peek() == '"' -> closeQuote('"')
                peek() == '`' -> closeQuote('`')
                else -> return false
            }
            pos = end
            return true
        }

        fun skipBlank() {
            while (!eof()) {
                if (text[pos].isWhitespace()) { pos++; continue }
                val rest = text.startsWith(pos)
                // Jinja tags and comments between CTEs ({% if %} around one, {# notes #}) are blanks here.
                if (rest("--") || rest("/*") || rest("{#") || rest("{%")) { skipOpaque(); continue }
                break
            }
        }

        fun readIdentifier(): String? {
            val start = pos
            if (peek() == '"' || peek() == '`') { pos = closeQuote(text[pos]); return text.substring(start, pos) }
            skipWord()
            return if (pos > start) text.substring(start, pos) else null
        }

        /** At an opening parenthesis: moves past its matching one and returns that offset. */
        fun skipParens(): Int? {
            var d = 0
            while (!eof()) {
                if (skipOpaque()) continue
                when (text[pos]) {
                    '(' -> d++
                    ')' -> { d--; if (d == 0) { pos++; return pos } }
                }
                pos++
            }
            return null
        }

        private fun closeAfter(marker: String): Int =
            text.indexOf(marker, pos + 2).let { if (it < 0) text.length else it + marker.length }

        private fun closeQuote(q: Char): Int {
            var i = pos + 1
            while (i < text.length) {
                // Snowflake, BigQuery, MySQL and Databricks also take backslash escapes: 'doesn\'t'.
                if (text[i] == '\\' && q == '\'') { i += 2; continue }
                if (text[i] == q) {
                    if (text.getOrNull(i + 1) == q) { i += 2; continue } // '' escape
                    return i + 1
                }
                i++
            }
            return text.length
        }

        private fun String.startsWith(at: Int): (String) -> Boolean = { this.startsWith(it, at) }
        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
    }
}
