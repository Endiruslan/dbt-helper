package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class DbtCompletionContributor : CompletionContributor() {

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
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, node) in index.nodes) {
            if (node.resourceType == "test") continue
            matcher.addElement(
                LookupElementBuilder.create(node.name)
                    .withTypeText(node.resourceType)
                    .withTailText(" (${node.packageName})", true)
                    .withIcon(AllIcons.Nodes.DataTables)
            )
        }
    }

    private fun completeSourceName(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        val names = index.sources.values.map { it.sourceName }.distinct()
        for (name in names) {
            matcher.addElement(
                LookupElementBuilder.create(name)
                    .withTypeText("source")
                    .withIcon(AllIcons.Nodes.DataTables)
            )
        }
    }

    private fun completeSourceTable(sourceName: String, prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, source) in index.sources) {
            if (source.sourceName == sourceName) {
                matcher.addElement(
                    LookupElementBuilder.create(source.name)
                        .withTypeText(source.schema ?: "")
                        .withIcon(AllIcons.Nodes.DataTables)
                )
            }
        }
    }

    private fun completeMacro(prefix: String, index: ManifestIndex, result: CompletionResultSet) {
        val matcher = result.withPrefixMatcher(prefix)
        for ((_, macro) in index.macros) {
            if (macro.name.startsWith("__")) continue
            val argsText = if (macro.arguments.isNotEmpty()) {
                macro.arguments.joinToString(", ") { it.name }
            } else ""
            matcher.addElement(
                LookupElementBuilder.create(macro.name)
                    .withTailText("($argsText)", true)
                    .withTypeText(macro.packageName)
                    .withIcon(AllIcons.Nodes.Function)
            )
        }
    }
}
