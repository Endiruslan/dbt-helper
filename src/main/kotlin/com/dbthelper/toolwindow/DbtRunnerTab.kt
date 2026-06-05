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
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class DbtRunnerTab(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    // When true the pane tracks the viewport width → soft-wrap, no horizontal
    // scrollbar (default, for run/build/test/compile/docs output). When false
    // the pane sizes to its content → wide lines get a horizontal scrollbar
    // instead of wrapping (used for preview tables, which must stay aligned).
    private var wrapOutput = true

    private val logPane = object : JTextPane() {
        override fun getScrollableTracksViewportWidth(): Boolean = wrapOutput
    }.apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12).takeIf { it.family == "JetBrains Mono" }
            ?: Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    private val logDoc get() = logPane.styledDocument

    // Matches a full ANSI SGR sequence including the leading ESC (0x1B) byte.
    private val ansiRegex = Regex("\u001B\\[([0-9;]*)m")

    private val baseStyle: AttributeSet = SimpleAttributeSet()

    private var currentStyle: AttributeSet = baseStyle

    private val runButton = JButton("Run").apply {
        toolTipText = "Run current model (dbt run --select)"
    }

    private val buildButton = JButton("Build").apply {
        toolTipText = "Build current model (dbt build --select — runs + tests + snapshots + seeds)"
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

    private val previewButton = JButton("Preview").apply {
        toolTipText = "Preview current model or selection (dbt show)"
    }

    private val generateButton = JButton("Generate Docs").apply {
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
            add(buildButton)
            add(fullRefreshCheckBox)
            add(testButton)
            add(compileButton)
            add(previewButton)
            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(2, 20) })
            add(generateButton)
            add(clearButton)
            add(stopButton)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(logPane).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        runButton.addActionListener { runCurrentModel() }
        buildButton.addActionListener { buildCurrentModel() }
        testButton.addActionListener { testCurrentModel() }
        compileButton.addActionListener { compileCurrentModel() }
        previewButton.addActionListener { previewCurrentModel() }

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
        clearButton.addActionListener { clearLog() }
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
        clearLog()

        val runner = DbtCommandRunner(project)
        runner.runModel(modelName, fullRefreshCheckBox.isSelected, createStreamingListener("dbt run"))
    }

    private fun buildCurrentModel() {
        val modelName = getCurrentModelName()
        if (modelName == null) {
            showNotification("No dbt model in current file", NotificationType.WARNING)
            return
        }
        if (isRunning) return
        setRunning(true)
        clearLog()

        val runner = DbtCommandRunner(project)
        runner.runBuild(modelName, fullRefreshCheckBox.isSelected, createStreamingListener("dbt build"))
    }

    private fun testCurrentModel() {
        val modelName = getCurrentModelName()
        if (modelName == null) {
            showNotification("No dbt model in current file", NotificationType.WARNING)
            return
        }
        if (isRunning) return
        setRunning(true)
        clearLog()

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
        clearLog()

        val runner = DbtCommandRunner(project)
        runner.runCompile(modelName, createStreamingListener("dbt compile"))
    }

    private fun createStreamingListener(commandName: String): DbtCommandRunner.OutputListener {
        return object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {
                ApplicationManager.getApplication().invokeLater {
                    appendLogLine(line)
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
        appendLogLine("")
        appendLogLine("--- Process terminated ---")
    }

    private fun previewCurrentModel() {
        val file = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .selectedFiles.firstOrNull()
        if (file == null) {
            setLog("No file open.")
            return
        }
        val service = com.dbthelper.core.ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file)
        val node = modelId?.let { service.getIndex().nodes[it] }

        // Detect text selection in the active editor
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val selectedText = editor?.selectionModel?.selectedText?.takeIf { it.isNotBlank() }

        val modelName: String?
        val inlineSql: String?
        if (selectedText != null) {
            modelName = null
            inlineSql = selectedText
        } else {
            modelName = node?.name
            inlineSql = null
        }
        if (modelName == null && inlineSql == null) {
            setLog("No model resolved for current file. Generate docs first or select SQL.")
            return
        }
        runPreview(modelName, inlineSql)
    }

    fun runPreview(modelName: String?, inlineSql: String?) {
        if (isRunning) return
        setRunning(true)
        setLog("Running preview...")
        // Preview renders a fixed-width table — keep lines intact and let a
        // horizontal scrollbar appear for wide tables instead of wrapping.
        setWrap(false)

        val runner = DbtCommandRunner(project)
        runner.runShow(modelName, inlineSql, object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {
                if (line.startsWith("$") || line.matches(Regex("^\\d{2}:\\d{2}:\\d{2}.*")) || line.startsWith("Previewing")) {
                    ApplicationManager.getApplication().invokeLater {
                        appendLogLine(line)
                    }
                }
            }

            override fun onFinished(result: DbtCommandRunner.RunResult) {
                ApplicationManager.getApplication().invokeLater {
                    setRunning(false)

                    if (result.success) {
                        val formatted = tryFormatJsonTable(result.output)
                        if (formatted != null) {
                            appendLogLine("")
                            for (l in formatted.lines()) appendLogLine(l)
                        } else {
                            appendLogLine("")
                            appendLogLine("(no data returned)")
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
        runButton.isEnabled = !running
        buildButton.isEnabled = !running
        testButton.isEnabled = !running
        compileButton.isEnabled = !running
        previewButton.isEnabled = !running
        stopButton.isEnabled = running
        if (!running) currentProcess = null
    }

    private fun runDocsGenerate() {
        if (isRunning) return
        setRunning(true)
        clearLog()

        val runner = DbtCommandRunner(project)
        runner.runDocsGenerate(object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {
                ApplicationManager.getApplication().invokeLater {
                    appendLogLine(line)
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

    private fun clearLog() {
        logDoc.remove(0, logDoc.length)
        currentStyle = baseStyle
        setWrap(true)
    }

    private fun setWrap(wrap: Boolean) {
        if (wrapOutput == wrap) return
        wrapOutput = wrap
        logPane.revalidate()
        logPane.repaint()
    }

    private fun setLog(text: String) {
        clearLog()
        appendLogLine(text)
    }

    private fun appendLogLine(line: String) {
        val coloredEnabled = DbtHelperSettings.getInstance(project).state.enableColoredOutput
        if (!coloredEnabled) {
            // Strip ANSI sequences entirely so leaked codes don't show as garbage.
            val clean = ansiRegex.replace(line, "")
            logDoc.insertString(logDoc.length, clean + "\n", baseStyle)
        } else {
            var pos = 0
            for (m in ansiRegex.findAll(line)) {
                if (m.range.first > pos) {
                    logDoc.insertString(logDoc.length, line.substring(pos, m.range.first), currentStyle)
                }
                currentStyle = styleForAnsiParams(m.groupValues[1])
                pos = m.range.last + 1
            }
            if (pos < line.length) {
                logDoc.insertString(logDoc.length, line.substring(pos), currentStyle)
            }
            logDoc.insertString(logDoc.length, "\n", currentStyle)
        }
        logPane.caretPosition = logDoc.length
    }

    private fun styleForAnsiParams(params: String): AttributeSet {
        // Apply each SGR code in order on top of the current style.
        val codes = if (params.isBlank()) listOf(0) else params.split(';').mapNotNull { it.toIntOrNull() }
        var attrs = SimpleAttributeSet(currentStyle)
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> attrs = SimpleAttributeSet(baseStyle)
                1 -> StyleConstants.setBold(attrs, true)
                22 -> StyleConstants.setBold(attrs, false)
                in 30..37 -> StyleConstants.setForeground(attrs, ansiColor(code - 30, bright = false))
                in 90..97 -> StyleConstants.setForeground(attrs, ansiColor(code - 90, bright = true))
                39 -> attrs.removeAttribute(StyleConstants.Foreground)
                38, 48 -> {
                    // Extended color: 38;5;n (256) or 38;2;r;g;b (truecolor).
                    // Consume the mode params so they aren't read as standalone codes.
                    when (codes.getOrNull(i + 1)) {
                        5 -> i += 2
                        2 -> i += 4
                        else -> i += 1
                    }
                }
            }
            i++
        }
        return attrs
    }

    private fun ansiColor(idx: Int, bright: Boolean): Color = when (idx) {
        0 -> if (bright) Color(102, 102, 102) else Color(0, 0, 0)         // black / gray
        1 -> if (bright) Color(255, 85, 85) else Color(205, 49, 49)        // red
        2 -> if (bright) Color(80, 200, 120) else Color(13, 188, 121)      // green
        3 -> if (bright) Color(245, 245, 67) else Color(229, 229, 16)      // yellow
        4 -> if (bright) Color(95, 159, 255) else Color(36, 114, 200)      // blue
        5 -> if (bright) Color(214, 112, 214) else Color(188, 63, 188)     // magenta
        6 -> if (bright) Color(85, 255, 255) else Color(17, 168, 205)      // cyan
        7 -> if (bright) Color(229, 229, 229) else Color(170, 170, 170)    // white
        else -> Color(229, 229, 229)
    }

    override fun dispose() {}
}
