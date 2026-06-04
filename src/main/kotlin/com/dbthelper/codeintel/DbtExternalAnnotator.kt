package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * File-level unresolved ref/source warnings. Runs once per daemon pass on a background thread
 * instead of [com.intellij.lang.annotation.Annotator], which is invoked for every PSI element.
 */
class DbtExternalAnnotator : ExternalAnnotator<DbtExternalAnnotator.Collected, List<DbtExternalAnnotator.Issue>>() {

    data class Collected(val project: Project, val text: String)

    data class Issue(val range: TextRange, val message: String)

    override fun collectInformation(file: PsiFile): Collected? {
        val vFile = file.virtualFile ?: return null
        if (!isDbtTemplateFile(vFile.name)) return null
        return Collected(file.project, file.text)
    }

    override fun doAnnotate(collectedInfo: Collected): List<Issue> {
        val index = ManifestService.getInstance(collectedInfo.project).getIndex()
        if (index === ManifestIndex.EMPTY) return emptyList()

        val text = collectedInfo.text
        val refs = DbtJinjaUtils.findRefCalls(text)
        val sources = DbtJinjaUtils.findSourceCalls(text)

        val issues = mutableListOf<Issue>()

        for (ref in refs) {
            if (!index.isResolvableModel(ref.modelName)) {
                issues.add(
                    Issue(
                        TextRange(ref.nameRange.first, ref.nameRange.last + 1),
                        "Unresolved ref: '${ref.modelName}'"
                    )
                )
            }
        }

        for (src in sources) {
            if (!index.isResolvableSource(src.sourceName, src.tableName)) {
                issues.add(
                    Issue(
                        TextRange(src.fullRange.first, src.fullRange.last + 1),
                        "Unresolved source: '${src.sourceName}.${src.tableName}'"
                    )
                )
            }
        }

        return issues
    }

    override fun apply(file: PsiFile, annotationResult: List<Issue>?, holder: AnnotationHolder) {
        if (annotationResult.isNullOrEmpty()) return
        for (issue in annotationResult) {
            holder.newAnnotation(HighlightSeverity.WARNING, issue.message)
                .range(issue.range)
                .create()
        }
    }
}
