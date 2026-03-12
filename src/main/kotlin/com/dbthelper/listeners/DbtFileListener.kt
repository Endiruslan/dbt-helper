package com.dbthelper.listeners

import com.dbthelper.core.ManifestService
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

class DbtFileListener(private val project: Project) : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!isSqlFile(file)) return

        val settings = DbtHelperSettings.getInstance(project)
        if (!settings.state.autoOpenOnSqlFile) return

        val manifestService = ManifestService.getInstance(project)
        val modelId = manifestService.findCurrentModelId(file)
        if (modelId != null) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("dbt Helper")
            toolWindow?.show()
        }

        notifyFileChanged(file)
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val file = event.newFile ?: return
        if (!isSqlFile(file)) return
        notifyFileChanged(file)
    }

    private fun notifyFileChanged(file: VirtualFile) {
        project.messageBus.syncPublisher(CurrentModelListener.TOPIC)
            .onCurrentModelChanged(file)
    }

    private fun isSqlFile(file: VirtualFile): Boolean {
        return file.extension?.lowercase() in setOf("sql", "yml", "yaml")
    }
}
