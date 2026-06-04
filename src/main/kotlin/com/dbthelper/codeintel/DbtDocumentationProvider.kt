package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.*
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class DbtDocumentationProvider : AbstractDocumentationProvider() {

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

            val nodeId = index.findByFilePath(normalized)
            if (nodeId != null) {
                val node = index.nodes[nodeId]
                if (node != null) return DbtDocRenderer.buildNodeDoc(node, index)
            }

            val sourceMatch = findSourceFromContext(originalElement, index)
            if (sourceMatch != null) return DbtDocRenderer.buildSourceDoc(sourceMatch, index)

            for ((_, macro) in index.macros) {
                if (macro.originalFilePath.replace('\\', '/') == normalized) {
                    return DbtDocRenderer.buildMacroDoc(macro)
                }
            }
        }

        return null
    }

    private fun findSourceFromContext(originalElement: PsiElement?, index: ManifestIndex): DbtSource? {
        if (originalElement == null) return null
        val file = originalElement.containingFile ?: return null
        val offset = originalElement.textRange.startOffset
        val sources = file.getDbtJinjaPatterns().sources

        for (src in sources) {
            if (offset in src.sourceNameRange || offset in src.tableNameRange) {
                return index.sources.values.firstOrNull {
                    it.sourceName == src.sourceName && it.name == src.tableName
                }
            }
        }

        if (offset == 0) {
            val firstSource = sources.firstOrNull()
            if (firstSource != null) {
                return index.sources.values.firstOrNull {
                    it.sourceName == firstSource.sourceName && it.name == firstSource.tableName
                }
            }
        }

        return null
    }
}
