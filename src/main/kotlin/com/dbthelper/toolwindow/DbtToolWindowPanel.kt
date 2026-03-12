package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.ProfilesParser
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.dbthelper.core.DbtProjectLocator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JPanel

class DbtToolWindowPanel(
    private val project: Project,
    parentDisposable: com.intellij.openapi.Disposable
) : JPanel(BorderLayout()) {

    private val statusLabel = JBLabel()
    private val modelLabel = JBLabel()
    private val dbtInfoLabel = JBLabel()
    private val targetLabel = JBLabel()
    private val errorLabel = JBLabel()

    init {
        val infoPanel = JPanel(GridLayout(0, 1, 0, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(dbtInfoLabel)
            add(targetLabel)
            add(statusLabel)
            add(modelLabel)
            add(errorLabel)
        }
        add(infoPanel, BorderLayout.NORTH)

        val connection = project.messageBus.connect(parentDisposable)
        connection.subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) {
                ApplicationManager.getApplication().invokeLater { refreshPanel() }
            }
        })

        connection.subscribe(CurrentModelListener.TOPIC, object : CurrentModelListener {
            override fun onCurrentModelChanged(file: VirtualFile) {
                ApplicationManager.getApplication().invokeLater { updateCurrentModel(file) }
            }
        })

        val manifestService = ManifestService.getInstance(project)
        manifestService.reparse()

        ApplicationManager.getApplication().invokeLater { refreshPanel() }

        // Load dbt version in background
        ApplicationManager.getApplication().executeOnPooledThread {
            val runner = DbtCommandRunner(project)
            val executable = runner.findDbtExecutable()
            val version = runner.getVersion()
            ApplicationManager.getApplication().invokeLater {
                dbtInfoLabel.text = if (version != null) {
                    "dbt: $executable (v$version)"
                } else {
                    "dbt: $executable"
                }
            }
        }
    }

    private fun refreshPanel() {
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        val error = service.lastError

        // Target info
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        val activeTarget = settings.state.activeTarget.ifBlank { profiles.getDefaultTarget() ?: "" }
        val config = profiles.parse()
        val targetConfig = config?.targets?.get(activeTarget)
        targetLabel.text = if (targetConfig != null) {
            "Target: $activeTarget (${targetConfig.type})"
        } else if (activeTarget.isNotBlank()) {
            "Target: $activeTarget"
        } else {
            "Target: (not set)"
        }

        if (error != null) {
            statusLabel.text = error
            statusLabel.icon = null
        } else if (service.isLoading) {
            statusLabel.text = "Parsing manifest..."
        } else if (index === ManifestIndex.EMPTY) {
            statusLabel.text = "manifest.json not found"
        } else {
            val dateStr = getManifestDate()
            val dateSuffix = if (dateStr != null) " — updated $dateStr" else ""
            statusLabel.text = "Manifest loaded (${index.modelCount} models, ${index.sourceCount} sources)$dateSuffix"
        }

        if (error != null) {
            errorLabel.text = "Error: $error"
            errorLabel.isVisible = true
        } else {
            errorLabel.isVisible = false
        }

        val editor = FileEditorManager.getInstance(project)
        val currentFile = editor.selectedFiles.firstOrNull()
        if (currentFile != null) {
            updateCurrentModel(currentFile)
        } else {
            modelLabel.text = "No dbt model detected"
        }
    }

    private fun getManifestDate(): String? {
        return try {
            val locator = DbtProjectLocator(project)
            val dbtRoot = locator.findProjectRoot() ?: return null
            val manifestFile = dbtRoot.findChild("target")?.findChild("manifest.json") ?: return null
            val timestamp = manifestFile.timeStamp
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(timestamp))
        } catch (_: Exception) {
            null
        }
    }

    private fun updateCurrentModel(file: VirtualFile) {
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file)
        modelLabel.text = if (modelId != null) {
            "Current model: $modelId"
        } else {
            "No dbt model detected"
        }
    }
}
