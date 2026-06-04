package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class DbtCompletionContributor : CompletionContributor() {

    companion object {
        /** Avoid building thousands of lookup elements on large projects. */
        private const val MAX_LOOKUP_ITEMS = 100
    }

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: CompletionResultSet
            ) {
                val file = parameters.originalFile
                val vFile = file.virtualFile ?: return
                if (!isDbtTemplateFile(vFile.name)) return

                val project = file.project
                val index = ManifestService.getInstance(project).getIndex()
                if (index === ManifestIndex.EMPTY) return

                val offset = parameters.offset
                val textBefore = file.text.substring(0, offset)

                when (val ctx = DbtJinjaUtils.detectCompletionContext(textBefore)) {
                    is DbtJinjaUtils.CompletionContext.Ref -> completeRef(ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.SourceName -> completeSourceName(ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.SourceTable -> completeSourceTable(ctx.sourceName, ctx.prefix, index, result)
                    is DbtJinjaUtils.CompletionContext.Macro -> completeMacro(ctx.prefix, index, result)
                    null -> return
                }
            }
        })
    }

    private fun completeRef(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val prefixMatcher = CamelHumpMatcher(prefix, true)
        val resultSet = result.withPrefixMatcher(prefix)
        val matching = index.nodes.values.asSequence()
            .filter { it.resourceType != "test" }
            .filter { node ->
                prefixMatcher.prefixMatches(node.name) ||
                    (node.alias != null && prefixMatcher.prefixMatches(node.alias!!))
            }
            .sortedBy { it.name }
            .take(MAX_LOOKUP_ITEMS)
        for (node in matching) {
            resultSet.addElement(
                LookupElementBuilder.create(node.name)
                    .withTypeText(node.resourceType)
                    .withTailText(" (${node.packageName})", true)
                    .withIcon(AllIcons.Nodes.DataTables)
            )
        }
    }

    private fun completeSourceName(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        addPrefixMatched(
            result,
            prefix,
            index.sources.values.map { it.sourceName }.distinct().asSequence(),
            lookupString = { it },
        ) { name ->
            LookupElementBuilder.create(name)
                .withTypeText("source")
                .withIcon(AllIcons.Nodes.DataTables)
        }
    }

    private fun completeSourceTable(sourceName: String, prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        addPrefixMatched(
            result,
            prefix,
            index.sources.values.asSequence().filter { it.sourceName == sourceName },
            lookupString = { it.name },
        ) { source ->
            LookupElementBuilder.create(source.name)
                .withTypeText(source.schema ?: "")
                .withIcon(AllIcons.Nodes.DataTables)
        }
    }

    private fun completeMacro(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        addPrefixMatched(
            result,
            prefix,
            index.macros.values.asSequence().filter { !it.name.startsWith("__") },
            lookupString = { it.name },
        ) { macro ->
            val argsText = if (macro.arguments.isNotEmpty()) {
                macro.arguments.joinToString(", ") { it.name }
            } else ""
            LookupElementBuilder.create(macro.name)
                .withTailText("($argsText)", true)
                .withTypeText(macro.packageName)
                .withIcon(AllIcons.Nodes.Function)
        }
    }

    /** Only materialize lookup elements that match [prefix], capped for large manifests. */
    private fun <T> addPrefixMatched(
        result: CompletionResultSet,
        prefix: String,
        items: Sequence<T>,
        lookupString: (T) -> String,
        toElement: (T) -> LookupElementBuilder
    ) {
        val prefixMatcher = CamelHumpMatcher(prefix, true)
        val resultSet = result.withPrefixMatcher(prefix)
        for (item in items
            .filter { prefixMatcher.prefixMatches(lookupString(it)) }
            .sortedBy(lookupString)
            .take(MAX_LOOKUP_ITEMS)
        ) {
            resultSet.addElement(toElement(item))
        }
    }
}
