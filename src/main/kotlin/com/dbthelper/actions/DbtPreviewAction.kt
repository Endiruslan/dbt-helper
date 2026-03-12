package com.dbthelper.actions

import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class DbtPreviewAction : AnAction("dbt Preview") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val isDbtFile = if (project != null && file != null) {
            ManifestService.getInstance(project).findCurrentModelId(file) != null
        } else false
        e.presentation.isEnabledAndVisible = editor != null && project != null && isDbtFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        if (index === ManifestIndex.EMPTY) return

        val modelId = service.findCurrentModelId(file)
        val node = modelId?.let { index.nodes[it] }

        val selectionModel = editor.selectionModel
        val hasSelection = selectionModel.hasSelection()

        // Determine what to preview
        val modelName: String?
        val inlineSql: String?

        if (hasSelection) {
            // Selected text → dbt show --inline "..."
            modelName = null
            inlineSql = selectionModel.selectedText
        } else {
            // No selection → dbt show --select model_name
            modelName = node?.name
            inlineSql = null
        }

        if (modelName == null && inlineSql == null) return

        // Activate Runner tab and run preview
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("dbt Helper") ?: return
        toolWindow.show {
            // Find Runner tab content
            val runnerContent = toolWindow.contentManager.findContent("Runner") ?: return@show
            toolWindow.contentManager.setSelectedContent(runnerContent)

            val runnerTab = runnerContent.component
            if (runnerTab is com.dbthelper.toolwindow.DbtRunnerTab) {
                runnerTab.runPreview(modelName, inlineSql)
            }
        }
    }
}
