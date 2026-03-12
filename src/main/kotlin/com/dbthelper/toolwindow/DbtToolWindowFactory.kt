package com.dbthelper.toolwindow

import com.dbthelper.core.DbtProjectLocator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DbtToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        val lineageTab = LineageTab(project, toolWindow.disposable)
        val lineageContent = contentFactory.createContent(lineageTab, "Lineage", false)
        toolWindow.contentManager.addContent(lineageContent)

        val docsTab = DocsTab(project, toolWindow.disposable)
        val docsContent = contentFactory.createContent(docsTab, "Docs", false)
        toolWindow.contentManager.addContent(docsContent)

        val runnerTab = DbtRunnerTab(project, toolWindow.disposable)
        val runnerContent = contentFactory.createContent(runnerTab, "Runner", false)
        toolWindow.contentManager.addContent(runnerContent)

        val statusPanel = DbtToolWindowPanel(project, toolWindow.disposable)
        val statusContent = contentFactory.createContent(statusPanel, "Status", false)
        toolWindow.contentManager.addContent(statusContent)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        val locator = DbtProjectLocator(project)
        return locator.hasDbtProjects()
    }
}
