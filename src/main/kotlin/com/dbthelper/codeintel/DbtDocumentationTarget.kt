package com.dbthelper.codeintel

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Offset-based V2 provider — runs BEFORE the PSI fallback. Required for DataSpell/PyCharm
 * where the bundled SQL plugin's own target provider would otherwise short-circuit the
 * PSI dispatch path that DbtPsiDocumentationTargetProvider sits on.
 */
class DbtDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val vFile = file.virtualFile ?: return emptyList()
        if (!isDbtTemplateFile(vFile.name)) return emptyList()

        val project = file.project
        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY) return emptyList()

        val patterns = file.getDbtJinjaPatterns()

        for (ref in patterns.refs) {
            if (offset in ref.nameRange) {
                val node = index.nodes.values.firstOrNull {
                    (it.name == ref.modelName || it.alias == ref.modelName) && it.resourceType != "test"
                } ?: continue
                return listOf(DbtDocumentationTarget(project, DocKind.Node(node.uniqueId), node.name))
            }
        }

        for (src in patterns.sources) {
            if (offset in src.tableNameRange || offset in src.sourceNameRange) {
                val source = index.sources.values.firstOrNull {
                    it.sourceName == src.sourceName && it.name == src.tableName
                } ?: continue
                return listOf(DbtDocumentationTarget(project, DocKind.Source(source.uniqueId), "${source.sourceName}.${source.name}"))
            }
        }

        for (macro in patterns.macros) {
            if (offset in macro.nameRange) {
                val m = index.macros.values.firstOrNull { it.name == macro.macroName } ?: continue
                return listOf(DbtDocumentationTarget(project, DocKind.Macro(m.uniqueId), m.name))
            }
        }

        return emptyList()
    }
}

/**
 * PSI-based V2 fallback — works in IDEA Community (no SQL plugin hijack).
 * Kept as a secondary path.
 */
class DbtPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTarget(element: PsiElement, originalElement: PsiElement?): DocumentationTarget? {
        val context = originalElement ?: element
        val file = context.containingFile ?: return null
        val vFile = file.virtualFile ?: return null
        if (!isDbtTemplateFile(vFile.name)) return null

        val project = file.project
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        if (index === ManifestIndex.EMPTY) return null

        val patterns = file.getDbtJinjaPatterns()
        val offset = context.textRange.startOffset

        for (ref in patterns.refs) {
            if (offset in ref.nameRange) {
                val node = index.nodes.values.firstOrNull {
                    (it.name == ref.modelName || it.alias == ref.modelName) && it.resourceType != "test"
                } ?: continue
                return DbtDocumentationTarget(project, DocKind.Node(node.uniqueId), node.name)
            }
        }

        for (src in patterns.sources) {
            if (offset in src.tableNameRange || offset in src.sourceNameRange) {
                val source = index.sources.values.firstOrNull {
                    it.sourceName == src.sourceName && it.name == src.tableName
                } ?: continue
                return DbtDocumentationTarget(project, DocKind.Source(source.uniqueId), "${source.sourceName}.${source.name}")
            }
        }

        for (macro in patterns.macros) {
            if (offset in macro.nameRange) {
                val m = index.macros.values.firstOrNull { it.name == macro.macroName } ?: continue
                return DbtDocumentationTarget(project, DocKind.Macro(m.uniqueId), m.name)
            }
        }

        return null
    }
}

sealed class DocKind {
    data class Node(val uniqueId: String) : DocKind()
    data class Source(val uniqueId: String) : DocKind()
    data class Macro(val uniqueId: String) : DocKind()
}

class DbtDocumentationTarget(
    private val project: Project,
    private val kind: DocKind,
    private val displayName: String
) : DocumentationTarget {

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val p = project
        val k = kind
        val n = displayName
        return Pointer { DbtDocumentationTarget(p, k, n) }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(displayName).presentation()

    override fun computeDocumentationHint(): String? = renderHtml()

    override fun computeDocumentation(): DocumentationResult? {
        val html = renderHtml() ?: return null
        return DocumentationResult.documentation(html)
    }

    private fun renderHtml(): String? {
        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY) return null
        return when (kind) {
            is DocKind.Node -> index.nodes[kind.uniqueId]?.let { DbtDocRenderer.buildNodeDoc(it, index) }
            is DocKind.Source -> index.sources[kind.uniqueId]?.let { DbtDocRenderer.buildSourceDoc(it, index) }
            is DocKind.Macro -> index.macros[kind.uniqueId]?.let { DbtDocRenderer.buildMacroDoc(it) }
        }
    }
}
