package com.dbthelper.codeintel

import com.dbthelper.core.DbtUtils
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.*
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class DbtDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        return generateDoc(element, originalElement)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        // element = resolved target file (from PsiReference.resolve())
        // When hovering over ref('model'), element is the model's .sql file
        if (element is PsiFile) {
            val vFile = element.virtualFile ?: return null
            val project = element.project
            val service = ManifestService.getInstance(project)
            val index = service.getIndex()
            if (index === ManifestIndex.EMPTY) return null

            val relativePath = service.getLocator().getRelativePath(vFile) ?: return null
            val normalized = relativePath.replace('\\', '/')

            // Check nodes (models, seeds, snapshots)
            val nodeId = index.findByFilePath(normalized)
            if (nodeId != null) {
                val node = index.nodes[nodeId]
                if (node != null) return buildNodeDoc(node, index)
            }

            // Check sources (yaml files — multiple sources can share one file)
            // Try to match by the originalElement context
            val sourceMatch = findSourceFromContext(originalElement, index)
            if (sourceMatch != null) return buildSourceDoc(sourceMatch, index)

            // Check macros
            for ((_, macro) in index.macros) {
                if (macro.originalFilePath.replace('\\', '/') == normalized) {
                    return buildMacroDoc(macro)
                }
            }
        }

        return null
    }

    private fun findSourceFromContext(originalElement: PsiElement?, index: ManifestIndex): DbtSource? {
        if (originalElement == null) return null
        val file = originalElement.containingFile ?: return null
        val text = file.text
        val offset = originalElement.textRange.startOffset

        for (src in DbtJinjaUtils.findSourceCalls(text)) {
            if (offset in src.sourceNameRange || offset in src.tableNameRange) {
                return index.sources.values.firstOrNull {
                    it.sourceName == src.sourceName && it.name == src.tableName
                }
            }
        }

        // If originalElement is within a large text block, try checking the reference range
        // For TEXT language, offset might be 0 (whole file), so also try element text matching
        if (offset == 0) {
            // Try all source calls in the file (can't determine which one)
            val firstSource = DbtJinjaUtils.findSourceCalls(text).firstOrNull()
            if (firstSource != null) {
                return index.sources.values.firstOrNull {
                    it.sourceName == firstSource.sourceName && it.name == firstSource.tableName
                }
            }
        }

        return null
    }

    private fun buildNodeDoc(node: DbtNode, index: ManifestIndex): String = buildString {
        append("<html><body style='margin:4px'>")

        // Header
        append("<h3 style='margin:0 0 6px 0'>${esc(node.name)}</h3>")

        // Type + materialization + package
        append("<p>")
        append("<code>${node.resourceType}</code>")
        val mat = node.config["materialized"] as? String
        if (mat != null) append(" &middot; <code>$mat</code>")
        append(" &middot; <i>${esc(node.packageName)}</i>")
        append("</p>")

        // Database location
        if (node.database != null || node.schema != null) {
            val parts = listOfNotNull(node.database, node.schema, node.alias ?: node.name)
            append("<p><code>${parts.joinToString(".") { esc(it) }}</code></p>")
        }

        // Description
        if (node.description.isNotEmpty()) {
            append("<p style='margin:6px 0'>${esc(node.description)}</p>")
        }

        // Dependencies
        val upstream = index.getUpstream(node.uniqueId)
        val downstream = index.getDownstream(node.uniqueId)
        if (upstream.isNotEmpty() || downstream.isNotEmpty()) {
            append("<hr>")
            if (upstream.isNotEmpty()) {
                val names = upstream.mapNotNull { id -> friendlyName(id, index) }.take(8)
                append("<p><b>Depends on (${upstream.size}):</b> ${names.joinToString(", ") { "<code>${esc(it)}</code>" }}")
                if (upstream.size > 8) append(" +${upstream.size - 8} more")
                append("</p>")
            }
            if (downstream.isNotEmpty()) {
                val names = downstream.mapNotNull { id -> friendlyName(id, index) }.take(8)
                append("<p><b>Used by (${downstream.size}):</b> ${names.joinToString(", ") { "<code>${esc(it)}</code>" }}")
                if (downstream.size > 8) append(" +${downstream.size - 8} more")
                append("</p>")
            }
        }

        // Columns
        if (node.columns.isNotEmpty()) {
            append("<hr><p><b>Columns (${node.columns.size}):</b></p>")
            append("<table style='margin:2px 0'>")
            for ((_, col) in node.columns.entries.take(15)) {
                append("<tr>")
                append("<td><code>${esc(col.name)}</code></td>")
                append("<td style='padding-left:8px;color:gray'>${esc(col.dataType ?: "")}</td>")
                if (col.description.isNotEmpty()) {
                    append("<td style='padding-left:8px'>${esc(col.description)}</td>")
                }
                append("</tr>")
            }
            append("</table>")
            if (node.columns.size > 15) append("<p><i>... and ${node.columns.size - 15} more columns</i></p>")
        }

        // Tags
        if (node.tags.isNotEmpty()) {
            append("<p style='margin-top:6px'><b>Tags:</b> ${node.tags.joinToString(", ") { "<code>${esc(it)}</code>" }}</p>")
        }

        // File path
        append("<p style='color:gray;margin-top:6px'>${esc(node.originalFilePath)}</p>")
        append("</body></html>")
    }

    private fun buildSourceDoc(source: DbtSource, index: ManifestIndex): String = buildString {
        append("<html><body style='margin:4px'>")

        append("<h3 style='margin:0 0 6px 0'>${esc(source.sourceName)}.${esc(source.name)}</h3>")
        append("<p><code>source</code> &middot; <i>${esc(source.packageName)}</i></p>")

        // Database location
        val parts = listOfNotNull(source.database, source.schema, source.identifier ?: source.name)
        if (parts.isNotEmpty()) {
            append("<p><code>${parts.joinToString(".") { esc(it) }}</code></p>")
        }

        if (source.description.isNotEmpty()) {
            append("<p style='margin:6px 0'>${esc(source.description)}</p>")
        }

        // Used by
        val downstream = index.getDownstream(source.uniqueId)
        if (downstream.isNotEmpty()) {
            append("<hr>")
            val names = downstream.mapNotNull { id -> friendlyName(id, index) }.take(8)
            append("<p><b>Used by (${downstream.size}):</b> ${names.joinToString(", ") { "<code>${esc(it)}</code>" }}")
            if (downstream.size > 8) append(" +${downstream.size - 8} more")
            append("</p>")
        }

        // Columns
        if (source.columns.isNotEmpty()) {
            append("<hr><p><b>Columns (${source.columns.size}):</b></p>")
            append("<table style='margin:2px 0'>")
            for ((_, col) in source.columns.entries.take(15)) {
                append("<tr>")
                append("<td><code>${esc(col.name)}</code></td>")
                append("<td style='padding-left:8px;color:gray'>${esc(col.dataType ?: "")}</td>")
                if (col.description.isNotEmpty()) {
                    append("<td style='padding-left:8px'>${esc(col.description)}</td>")
                }
                append("</tr>")
            }
            append("</table>")
            if (source.columns.size > 15) append("<p><i>... and ${source.columns.size - 15} more columns</i></p>")
        }

        if (source.tags.isNotEmpty()) {
            append("<p style='margin-top:6px'><b>Tags:</b> ${source.tags.joinToString(", ") { "<code>${esc(it)}</code>" }}</p>")
        }

        append("<p style='color:gray;margin-top:6px'>${esc(source.originalFilePath)}</p>")
        append("</body></html>")
    }

    private fun buildMacroDoc(macro: DbtMacro): String = buildString {
        append("<html><body style='margin:4px'>")

        val argsStr = macro.arguments.joinToString(", ") { it.name }
        append("<h3 style='margin:0 0 6px 0'>${esc(macro.name)}($argsStr)</h3>")
        append("<p><code>macro</code> &middot; <i>${esc(macro.packageName)}</i></p>")

        if (macro.description.isNotEmpty()) {
            append("<p style='margin:6px 0'>${esc(macro.description)}</p>")
        }

        if (macro.arguments.isNotEmpty()) {
            append("<hr><p><b>Arguments:</b></p>")
            append("<table style='margin:2px 0'>")
            for (arg in macro.arguments) {
                append("<tr>")
                append("<td><code>${esc(arg.name)}</code></td>")
                if (arg.type != null) append("<td style='padding-left:8px;color:gray'>${esc(arg.type)}</td>")
                if (arg.description.isNotEmpty()) append("<td style='padding-left:8px'>${esc(arg.description)}</td>")
                append("</tr>")
            }
            append("</table>")
        }

        append("<p style='color:gray;margin-top:6px'>${esc(macro.originalFilePath)}</p>")
        append("</body></html>")
    }

    private fun friendlyName(uniqueId: String, index: ManifestIndex): String? =
        DbtUtils.friendlyName(uniqueId, index)

    private fun esc(s: String): String = DbtUtils.escapeHtml(s)
}
