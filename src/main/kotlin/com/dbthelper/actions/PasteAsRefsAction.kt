package com.dbthelper.actions

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor

class PasteAsRefsAction : AnAction("Paste as dbt Refs") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val hasText = CopyPasteManager.getInstance().contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true
        e.presentation.isEnabledAndVisible = editor != null && project != null && hasText
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return

        val clipboard = CopyPasteManager.getInstance().contents ?: return
        val text = clipboard.getTransferData(DataFlavor.stringFlavor) as? String ?: return

        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY) return

        val replaced = replaceRelationsWithRefs(text, index)

        WriteCommandAction.runWriteCommandAction(project, "Paste as dbt Refs", null, {
            val caret = editor.caretModel.offset
            if (editor.selectionModel.hasSelection()) {
                val start = editor.selectionModel.selectionStart
                val end = editor.selectionModel.selectionEnd
                editor.document.replaceString(start, end, replaced)
                editor.caretModel.moveToOffset(start + replaced.length)
            } else {
                editor.document.insertString(caret, replaced)
                editor.caretModel.moveToOffset(caret + replaced.length)
            }
        })
    }

    companion object {
        // Matches patterns like: database.schema.table, schema.table
        // Must handle quoted identifiers too: "database"."schema"."table"
        private val TABLE_PATTERN = Regex(
            """(?:"([^"]+)"|(\w+))\.(?:"([^"]+)"|(\w+))\.(?:"([^"]+)"|(\w+))""" +
            """|(?:"([^"]+)"|(\w+))\.(?:"([^"]+)"|(\w+))"""
        )

        // Matches /* {% ... %} */ or /* {{ ... }} */ — commented-out Jinja blocks
        private val COMMENTED_JINJA = Regex("""/\*\s*(\{[%{]-?.*?-?[%}]\})\s*\*/""", RegexOption.DOT_MATCHES_ALL)

        fun replaceRelationsWithRefs(text: String, index: ManifestIndex): String {
            // Uncomment Jinja blocks: /* {% if ... %} */ → {% if ... %}
            var result = COMMENTED_JINJA.replace(text) { it.groupValues[1] }

            // Replace table references with ref/source
            result = TABLE_PATTERN.replace(result) { match ->
                val parts = extractParts(match)
                val replacement = findReplacement(parts, index)
                replacement ?: match.value
            }
            return result
        }

        private fun extractParts(match: MatchResult): List<String> {
            val g = match.groupValues
            // 3-part: groups 1-6 (pairs of quoted/unquoted for db, schema, table)
            val db = g[1].ifEmpty { g[2] }
            val schema3 = g[3].ifEmpty { g[4] }
            val table3 = g[5].ifEmpty { g[6] }

            if (db.isNotEmpty() && schema3.isNotEmpty() && table3.isNotEmpty()) {
                return listOf(db, schema3, table3)
            }

            // 2-part: groups 7-10
            val schema2 = g[7].ifEmpty { g[8] }
            val table2 = g[9].ifEmpty { g[10] }
            if (schema2.isNotEmpty() && table2.isNotEmpty()) {
                return listOf(schema2, table2)
            }

            return emptyList()
        }

        private fun findReplacement(parts: List<String>, index: ManifestIndex): String? {
            if (parts.size == 3) {
                val (db, schema, table) = parts
                val uniqueId = index.findByRelation(db, schema, table) ?: return null
                return makeRefOrSource(uniqueId, index)
            }

            if (parts.size == 2) {
                val (schema, table) = parts
                // Try to find by scanning all relations with matching schema.table suffix
                val key = ".$schema.$table".lowercase()
                val match = index.relationMap.entries.firstOrNull { it.key.endsWith(key) }
                    ?: return null
                return makeRefOrSource(match.value, index)
            }

            return null
        }

        private fun makeRefOrSource(uniqueId: String, index: ManifestIndex): String? {
            index.nodes[uniqueId]?.let { node ->
                return "{{ ref('${node.name}') }}"
            }
            index.sources[uniqueId]?.let { source ->
                return "{{ source('${source.sourceName}', '${source.name}') }}"
            }
            return null
        }
    }
}
