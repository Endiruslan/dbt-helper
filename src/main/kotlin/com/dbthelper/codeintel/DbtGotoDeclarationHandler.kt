package com.dbthelper.codeintel

import com.dbthelper.core.DbtUtils
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

class DbtGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val file = element.containingFile ?: return null
        val vFile = file.virtualFile ?: return null
        if (!isDbtTemplateFile(vFile.name)) return null

        // Limit to reasonably-sized elements to avoid underlining the whole file
        if (element.textLength > 300) return null

        val project = file.project
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        if (index === ManifestIndex.EMPTY) return null

        val dbtRoot = service.getLocator().findProjectRoot() ?: return null
        val text = file.text

        for (ref in DbtJinjaUtils.findRefCalls(text)) {
            if (offset in ref.nameRange) {
                val node = index.nodes.values.firstOrNull {
                    (it.name == ref.modelName || it.alias == ref.modelName) && it.resourceType != "test"
                } ?: continue
                val target = DbtUtils.resolveFile(project, dbtRoot.path, node.originalFilePath) ?: continue
                return arrayOf(target)
            }
        }

        for (src in DbtJinjaUtils.findSourceCalls(text)) {
            if (offset in src.tableNameRange || offset in src.sourceNameRange) {
                val source = index.sources.values.firstOrNull {
                    it.sourceName == src.sourceName && it.name == src.tableName
                } ?: continue
                val target = DbtUtils.resolveFile(project, dbtRoot.path, source.originalFilePath) ?: continue
                return arrayOf(target)
            }
        }

        for (macro in DbtJinjaUtils.findMacroCalls(text)) {
            if (offset in macro.nameRange) {
                val m = index.macros.values.firstOrNull { it.name == macro.macroName } ?: continue
                val target = DbtUtils.resolveFile(project, dbtRoot.path, m.originalFilePath) ?: continue
                return arrayOf(target)
            }
        }

        return null
    }
}
