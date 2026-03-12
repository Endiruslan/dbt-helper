package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class DbtAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val vFile = element.virtualFile ?: return
        if (!isDbtTemplateFile(vFile.name)) return

        val project = element.project
        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY) return

        val text = element.text

        for (ref in DbtJinjaUtils.findRefCalls(text)) {
            val range = TextRange(ref.nameRange.first, ref.nameRange.last + 1)
            val found = index.nodes.values.any {
                (it.name == ref.modelName || it.alias == ref.modelName) && it.resourceType != "test"
            }
            if (!found) {
                holder.newAnnotation(HighlightSeverity.WARNING, "Unresolved ref: '${ref.modelName}'")
                    .range(range)
                    .create()
            }
        }

        for (src in DbtJinjaUtils.findSourceCalls(text)) {
            val range = TextRange(src.fullRange.first, src.fullRange.last + 1)
            val found = index.sources.values.any {
                it.sourceName == src.sourceName && it.name == src.tableName
            }
            if (!found) {
                holder.newAnnotation(HighlightSeverity.WARNING, "Unresolved source: '${src.sourceName}.${src.tableName}'")
                    .range(range)
                    .create()
            }
        }
    }
}
