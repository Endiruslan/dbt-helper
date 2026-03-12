package com.dbthelper.codeintel

import com.dbthelper.core.DbtUtils
import com.dbthelper.core.ManifestService
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.ProcessingContext

class DbtReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), DbtReferenceProvider())
    }
}

private data class CachedPatterns(
    val refs: List<DbtJinjaUtils.RefCall>,
    val sources: List<DbtJinjaUtils.SourceCall>,
    val macros: List<DbtJinjaUtils.MacroCall>
)

private class DbtReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        val vFile = file.virtualFile ?: return PsiReference.EMPTY_ARRAY
        if (!isDbtTemplateFile(vFile.name)) return PsiReference.EMPTY_ARRAY

        val patterns = CachedValuesManager.getCachedValue(file) {
            val text = file.text
            CachedValueProvider.Result.create(
                CachedPatterns(
                    DbtJinjaUtils.findRefCalls(text),
                    DbtJinjaUtils.findSourceCalls(text),
                    DbtJinjaUtils.findMacroCalls(text)
                ),
                file
            )
        }

        val elemRange = element.textRange
        val references = mutableListOf<PsiReference>()

        for (ref in patterns.refs) {
            rangeIfContained(ref.nameRange, elemRange)?.let { relRange ->
                references.add(DbtModelReference(element, relRange, ref.modelName))
            }
        }

        for (src in patterns.sources) {
            rangeIfContained(src.tableNameRange, elemRange)?.let { relRange ->
                references.add(DbtSourceReference(element, relRange, src.sourceName, src.tableName))
            }
            rangeIfContained(src.sourceNameRange, elemRange)?.let { relRange ->
                references.add(DbtSourceReference(element, relRange, src.sourceName, src.tableName))
            }
        }

        for (macro in patterns.macros) {
            rangeIfContained(macro.nameRange, elemRange)?.let { relRange ->
                references.add(DbtMacroReference(element, relRange, macro.macroName))
            }
        }

        return references.toTypedArray()
    }

    private fun rangeIfContained(nameRange: IntRange, elemRange: TextRange): TextRange? {
        val start = nameRange.first
        val end = nameRange.last + 1
        if (start >= elemRange.startOffset && end <= elemRange.endOffset) {
            return TextRange(start - elemRange.startOffset, end - elemRange.startOffset)
        }
        return null
    }
}

private class DbtModelReference(
    element: PsiElement,
    range: TextRange,
    private val modelName: String
) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement? {
        val project = element.project
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        val dbtRoot = service.getLocator().findProjectRoot() ?: return null
        val node = index.nodes.values.firstOrNull { (it.name == modelName || it.alias == modelName) && it.resourceType != "test" }
            ?: return null
        return DbtUtils.resolveFile(project, dbtRoot.path, node.originalFilePath)
    }
}

private class DbtSourceReference(
    element: PsiElement,
    range: TextRange,
    private val sourceName: String,
    private val tableName: String
) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement? {
        val project = element.project
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        val dbtRoot = service.getLocator().findProjectRoot() ?: return null
        val source = index.sources.values.firstOrNull {
            it.sourceName == sourceName && it.name == tableName
        } ?: return null
        return DbtUtils.resolveFile(project, dbtRoot.path, source.originalFilePath)
    }
}

private class DbtMacroReference(
    element: PsiElement,
    range: TextRange,
    private val macroName: String
) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement? {
        val project = element.project
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        val dbtRoot = service.getLocator().findProjectRoot() ?: return null
        val macro = index.macros.values.firstOrNull { it.name == macroName } ?: return null
        return DbtUtils.resolveFile(project, dbtRoot.path, macro.originalFilePath)
    }
}
