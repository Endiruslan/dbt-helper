package com.dbthelper.codeintel

/** Checks if a file is a dbt template file that can contain Jinja ref/source calls. */
fun isDbtTemplateFile(fileName: String): Boolean {
    return fileName.endsWith(".sql") || fileName.endsWith(".jinja") || fileName.endsWith(".jinja2")
}

object DbtJinjaUtils {

    private val REF_PATTERN = Regex("""ref\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    private val SOURCE_PATTERN = Regex("""source\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*\)""")
    private val MACRO_CALL_PATTERN = Regex("""\{\{[-\s]*(\w+)\s*\(""")

    data class RefCall(val modelName: String, val fullRange: IntRange, val nameRange: IntRange)
    data class SourceCall(
        val sourceName: String,
        val tableName: String,
        val fullRange: IntRange,
        val sourceNameRange: IntRange,
        val tableNameRange: IntRange
    )
    data class MacroCall(val macroName: String, val fullRange: IntRange, val nameRange: IntRange)

    fun findRefCalls(text: String): List<RefCall> {
        return REF_PATTERN.findAll(text).map { match ->
            val nameGroup = match.groups[1]!!
            RefCall(match.groupValues[1], match.range, nameGroup.range)
        }.toList()
    }

    fun findSourceCalls(text: String): List<SourceCall> {
        return SOURCE_PATTERN.findAll(text).map { match ->
            val srcGroup = match.groups[1]!!
            val tblGroup = match.groups[2]!!
            SourceCall(
                match.groupValues[1], match.groupValues[2],
                match.range, srcGroup.range, tblGroup.range
            )
        }.toList()
    }

    fun findMacroCalls(text: String): List<MacroCall> {
        // Filter out built-in Jinja/dbt keywords that aren't macros
        val builtins = setOf("ref", "source", "config", "this", "if", "for", "set", "do", "block", "macro", "call")
        return MACRO_CALL_PATTERN.findAll(text).mapNotNull { match ->
            val name = match.groupValues[1]
            if (name in builtins) return@mapNotNull null
            val nameGroup = match.groups[1]!!
            MacroCall(name, match.range, nameGroup.range)
        }.toList()
    }

    /** Context detection for completion — checks what's being typed before the cursor. */
    sealed class CompletionContext {
        data class Ref(val prefix: String) : CompletionContext()
        data class SourceName(val prefix: String) : CompletionContext()
        data class SourceTable(val sourceName: String, val prefix: String) : CompletionContext()
        data class Macro(val prefix: String) : CompletionContext()
    }

    private val CTX_REF = Regex("""ref\s*\(\s*['"]([^'"]*)$""")
    private val CTX_SOURCE_SECOND = Regex("""source\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)$""")
    private val CTX_SOURCE_FIRST = Regex("""source\s*\(\s*['"]([^'"]*)$""")
    private val CTX_MACRO = Regex("""\{\{[-\s]*(\w*)$""")

    fun detectCompletionContext(textBeforeCursor: String): CompletionContext? {
        CTX_REF.find(textBeforeCursor)?.let {
            return CompletionContext.Ref(it.groupValues[1])
        }
        CTX_SOURCE_SECOND.find(textBeforeCursor)?.let {
            return CompletionContext.SourceTable(it.groupValues[1], it.groupValues[2])
        }
        CTX_SOURCE_FIRST.find(textBeforeCursor)?.let {
            return CompletionContext.SourceName(it.groupValues[1])
        }
        CTX_MACRO.find(textBeforeCursor)?.let {
            return CompletionContext.Macro(it.groupValues[1])
        }
        return null
    }
}
