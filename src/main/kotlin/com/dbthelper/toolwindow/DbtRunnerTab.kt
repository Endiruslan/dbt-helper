package com.dbthelper.toolwindow

import com.dbthelper.actions.DbtCommandRunner
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.DbtNode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.dbthelper.core.ProfilesParser
import com.dbthelper.settings.DbtHelperSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.text.DefaultCaret

class DbtRunnerTab(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private val logArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12).takeIf { it.family == "JetBrains Mono" }
            ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
    }

    private val runButton = JButton("Run").apply {
        toolTipText = "Run current model (dbt run --select)"
    }

    private val fullRefreshCheckBox = JCheckBox("full-refresh").apply {
        toolTipText = "Run with --full-refresh flag (rebuild incremental model from scratch)"
        isVisible = false
    }

    private val testButton = JButton("Test").apply {
        toolTipText = "Test current model (dbt test --select)"
    }

    private val compileButton = JButton("Compile").apply {
        toolTipText = "Compile current model (dbt compile --select)"
    }

    private val generateButton = JButton("Docs").apply {
        toolTipText = "Run dbt docs generate"
    }

    private val clearButton = JButton("Clear").apply {
        toolTipText = "Clear log output"
    }

    private val stopButton = JButton("Stop").apply {
        toolTipText = "Stop running dbt command"
        isEnabled = false
    }

    private val targetCombo = JComboBox<String>().apply {
        toolTipText = "dbt target"
        preferredSize = Dimension(120, preferredSize.height)
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentProcess: Process? = null

    init {
        Disposer.register(parentDisposable, this)

        initTargetCombo()

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JLabel("Target:"))
            add(targetCombo)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 20) })
            add(runButton)
            add(fullRefreshCheckBox)
            add(testButton)
            add(compileButton)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 20) })
            add(generateButton)
            add(clearButton)
            add(stopButton)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(logArea), BorderLayout.CENTER)

        runButton.addActionListener { runCurrentModel() }
        testButton.addActionListener { testCurrentModel() }
        compileButton.addActionListener { compileCurrentModel() }

        // Update full-refresh visibility when current model changes
        val connection = project.messageBus.connect(this)
        connection.subscribe(com.dbthelper.listeners.CurrentModelListener.TOPIC,
            object : com.dbthelper.listeners.CurrentModelListener {
                override fun onCurrentModelChanged(file: com.intellij.openapi.vfs.VirtualFile) {
                    ApplicationManager.getApplication().invokeLater { updateFullRefreshVisibility() }
                }
            })

        // Initial check
        updateFullRefreshVisibility()
        generateButton.addActionListener { runDocsGenerate() }
        clearButton.addActionListener { logArea.text = "" }
        stopButton.addActionListener { stopCurrentCommand() }
    }

    private fun initTargetCombo() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        val targets = profiles.getTargetNames()
        val defaultTarget = profiles.getDefaultTarget()

        targetCombo.removeAllItems()
        for (t in targets) {
            targetCombo.addItem(t)
        }

        // Select current active target or default
        val current = settings.state.activeTarget.ifBlank { defaultTarget ?: "" }
        if (current.isNotBlank() && targets.contains(current)) {
            targetCombo.selectedItem = current
        }

        targetCombo.addActionListener {
            val selected = targetCombo.selectedItem as? String ?: return@addActionListener
            settings.state.activeTarget = selected
            project.messageBus.syncPublisher(
                com.dbthelper.settings.SettingsChangeListener.TOPIC
            ).onSettingsChanged()
        }
    }

    fun refreshTargets() {
        val settings = DbtHelperSettings.getInstance(project)
        val profiles = ProfilesParser.getInstance(project)
        profiles.invalidateCache()
        val targets = profiles.getTargetNames()

        val current = targetCombo.selectedItem as? String
        targetCombo.removeActionListeners()
        targetCombo.removeAllItems()
        for (t in targets) {
            targetCombo.addItem(t)
        }
        if (current != null && targets.contains(current)) {
            targetCombo.selectedItem = current
        }

        targetCombo.addActionListener {
            val selected = targetCombo.selectedItem as? String ?: return@addActionListener
            settings.state.activeTarget = selected
            project.messageBus.syncPublisher(
                com.dbthelper.settings.SettingsChangeListener.TOPIC
            ).onSettingsChanged()
        }
    }

    private fun JComboBox<*>.removeActionListeners() {
        for (l in actionListeners) removeActionListener(l)
    }

    private fun getCurrentModelNode(): DbtNode? {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file) ?: return null
        return service.getIndex().nodes[modelId]
    }

    private fun getCurrentModelName(): String? = getCurrentModelNode()?.name

    fun updateFullRefreshVisibility() {
        val node = getCurrentModelNode()
        val isIncremental = node?.config?.get("materialized") == "incremental"
        fullRefreshCheckBox.isVisible = isIncremental
        if (!isIncremental) fullRefreshCheckBox.isSelected = false
    }

    private fun runCurrentModel() {
        val modelName = getCurrentModelName()
        if (modelName == null) {
            showNotification("No dbt model in current file", NotificationType.WARNING)
            return
        }
        if (isRunning) return
        setRunning(true)
        logArea.text = ""

        val runner = DbtCommandRunner(project)
        runner.runModel(modelName, fullRefreshCheckBox.isSelected, createStreamingListener("dbt run"))
    }

    private fun testCurrentModel() {
        val modelName = getCurrentModelName()
        if (modelName == null) {
            showNotification("No dbt model in current file", NotificationType.WARNING)
            return
        }
        if (isRunning) return
        setRunning(true)
        logArea.text = ""

        val runner = DbtCommandRunner(project)
        runner.runTest(modelName, createStreamingListener("dbt test"))
    }

    private fun compileCurrentModel() {
        val modelName = getCurrentModelName()
        if (modelName == null) {
            showNotification("No dbt model in current file", NotificationType.WARNING)
            return
        }
        if (isRunning) return
        setRunning(true)
        logArea.text = ""

        val runner = DbtCommandRunner(project)
        runner.runCompile(modelName, createStreamingListener("dbt compile"))
    }

    private fun createStreamingListener(commandName: String): DbtCommandRunner.OutputListener {
        return object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {
                ApplicationManager.getApplication().invokeLater {
                    logArea.append(line + "\n")
                }
            }

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    setRunning(false)
                    if (result.success) {
                        showNotification("$commandName completed", NotificationType.INFORMATION)
                    } else if (result.exitCode != -1) {
                        showNotification("$commandName failed (exit code ${result.exitCode})", NotificationType.ERROR)
                    }
                }
            }

            override fun onProcessStarted(process: Process) {
                currentProcess = process
            }
        }
    }

    private fun stopCurrentCommand() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        logArea.append("\n--- Process terminated ---\n")
    }

    fun runPreview(modelName: String?, inlineSql: String?) {
        if (isRunning) return
        setRunning(true)
        logArea.text = "Running preview...\n"

        val runner = DbtCommandRunner(project)
        runner.runShow(modelName, inlineSql, object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {
                if (line.startsWith("$") || line.matches(Regex("^\\d{2}:\\d{2}:\\d{2}.*")) || line.startsWith("Previewing")) {
                    ApplicationManager.getApplication().invokeLater {
                        logArea.append(line + "\n")
                    }
                }
            }

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    setRunning(false)

                    if (result.success) {
                        val formatted = tryFormatJsonTable(result.output)
                        if (formatted != null) {
                            logArea.append("\n" + formatted + "\n")
                        } else {
                            logArea.append("\n(no data returned)\n")
                        }
                    } else if (result.exitCode != -1) {
                        showNotification("dbt preview failed (exit code ${result.exitCode})", NotificationType.ERROR)
                    }
                }
            }

            override fun onProcessStarted(process: Process) {
                currentProcess = process
            }
        })
    }

    private fun tryFormatJsonTable(output: String): String? {
        try {
            // Strip ANSI escape codes
            val clean = output.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")

            // Find JSON object or array in output
            val jsonStart = clean.indexOfFirst { it == '{' || it == '[' }
            if (jsonStart < 0) return null
            val jsonStr = clean.substring(jsonStart)

            val rootNode = mapper.readTree(jsonStr)

            // dbt show --output json returns {"node": "...", "show": [...]}
            val rowsNode: JsonNode = when {
                rootNode.isObject && rootNode.has("show") -> rootNode.get("show")
                rootNode.isArray -> rootNode
                else -> return null
            }

            if (!rowsNode.isArray || rowsNode.size() == 0) return "(0 rows)"

            val firstRow = rowsNode[0]
            val columns = firstRow.fieldNames().asSequence().toList()

            // Calculate column widths (header vs data, capped at 60)
            val widths = columns.map { col ->
                val dataMax = (0 until rowsNode.size()).maxOf { i ->
                    val v = rowsNode[i].get(col)
                    nodeToString(v).length
                }
                maxOf(col.length, dataMax).coerceAtMost(60)
            }

            val sb = StringBuilder()
            val separator = widths.joinToString("-+-", "+-", "-+") { "-".repeat(it) }

            // Header
            sb.appendLine(separator)
            sb.appendLine(columns.mapIndexed { i, col -> col.padEnd(widths[i]) }.joinToString(" | ", "| ", " |"))
            sb.appendLine(separator)

            // Rows
            for (i in 0 until rowsNode.size()) {
                val row = rowsNode[i]
                val line = columns.mapIndexed { j, col ->
                    val value = nodeToString(row.get(col))
                    if (value.length > widths[j]) value.take(widths[j] - 3) + "..." else value.padEnd(widths[j])
                }.joinToString(" | ", "| ", " |")
                sb.appendLine(line)
            }

            sb.appendLine(separator)
            sb.appendLine("(${rowsNode.size()} rows)")

            return sb.toString()
        } catch (_: Exception) {
            return null
        }
    }

    private fun nodeToString(node: JsonNode?): String {
        if (node == null || node.isNull) return "null"
        if (node.isTextual) return node.asText()
        if (node.isNumber) return node.numberValue().toString()
        if (node.isBoolean) return node.asBoolean().toString()
        return node.toString()
    }

    private fun setRunning(running: Boolean) {
        isRunning = running
        generateButton.isEnabled = !running
        stopButton.isEnabled = running
        if (!running) currentProcess = null
    }

    private fun runDocsGenerate() {
        if (isRunning) return
        setRunning(true)
        logArea.text = ""

        val runner = DbtCommandRunner(project)
        runner.runDocsGenerate(object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {
                ApplicationManager.getApplication().invokeLater {
                    logArea.append(line + "\n")
                }
            }

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    setRunning(false)
                    if (result.success) {
                        showNotification("dbt docs generate completed", NotificationType.INFORMATION)
                    } else if (result.exitCode != -1) {
                        showNotification("dbt docs generate failed (exit code ${result.exitCode})", NotificationType.ERROR)
                    }
                }
            }

            override fun onProcessStarted(process: Process) {
                currentProcess = process
            }
        })
    }

    private fun showNotification(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dbt Helper")
            .createNotification(content, type)
            .notify(project)

        // Also send native OS notification (macOS Notification Center)
        if (DbtHelperSettings.getInstance(project).state.enableSystemNotifications) {
            val title = when (type) {
                NotificationType.ERROR -> "dbt Error"
                else -> "dbt Helper"
            }
            com.intellij.ui.SystemNotifications.getInstance().notify("dbt-helper", title, content)
        }
    }

    override fun dispose() {}
}
