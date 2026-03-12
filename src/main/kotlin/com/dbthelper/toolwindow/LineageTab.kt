package com.dbthelper.toolwindow

import com.dbthelper.core.LineageGraphBuilder
import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.LineageGraph
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.dbthelper.settings.SettingsChangeListener
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ide.ui.LafManagerListener
import org.cef.browser.CefBrowser
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.UIManager

class LineageTab(private val project: Project, private val parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {

    private val logger = Logger.getInstance(LineageTab::class.java)
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val browser: JBCefBrowser = JBCefBrowser()
    private val jsQueryBridge: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var isPageReady = false

    @Volatile
    private var isDisposed = false

    // Expanded boundary nodes (not persisted to settings)
    private val expandedBoundaryNodes = mutableSetOf<String>()

    init {
        Disposer.register(parentDisposable, this)

        add(browser.component, BorderLayout.CENTER)

        setupJsBridge()
        setupLoadHandler()
        loadHtml()

        val connection = project.messageBus.connect(this)

        // Subscribe to manifest updates
        connection.subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) {
                resolveCurrentModel()
                refreshGraph()
            }
        })

        // Subscribe to file changes
        connection.subscribe(CurrentModelListener.TOPIC, object : CurrentModelListener {
            override fun onCurrentModelChanged(file: VirtualFile) {
                onFileChanged(file)
            }
        })

        // Subscribe to settings changes
        connection.subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
            override fun onSettingsChanged() {
                refreshGraph()
            }
        })

        // Listen for theme changes
        val appConnection = ApplicationManager.getApplication().messageBus.connect(this)
        appConnection.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyCurrentTheme() })

        // Pick up currently open file
        val currentFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (currentFile != null) {
            val service = ManifestService.getInstance(project)
            currentModelId = service.findCurrentModelId(currentFile)
        }
    }

    private fun setupJsBridge() {
        jsQueryBridge.addHandler { request ->
            try {
                val json = mapper.readTree(request)
                val type = json.path("type").asText()
                val payload = json.get("payload")

                when (type) {
                    "ready" -> {
                        isPageReady = true
                        refreshGraph()
                    }
                    "nodeClick" -> {
                        val nodeId = payload.path("nodeId").asText()
                        val resourceType = payload.path("resourceType").asText()
                        handleNodeClick(nodeId, resourceType)
                    }
                    "expandRequest" -> {
                        val boundaryNodeId = payload.path("boundaryNodeId").asText()
                        handleExpandRequest(boundaryNodeId)
                    }
                }
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                logger.warn("Error handling JS bridge message", e)
                JBCefJSQuery.Response(null, 1, e.message ?: "error")
            }
        }
    }

    private fun setupLoadHandler() {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    // Inject the bridge function into the page
                    val bridgeCode = jsQueryBridge.inject(
                        "request",
                        "function(response) {}",
                        "function(errorCode, errorMessage) { console.error('Bridge error:', errorCode, errorMessage); }"
                    )
                    val js = "window.__cefQueryBridge = function(request) { $bridgeCode };"
                    cefBrowser?.executeJavaScript(js, cefBrowser.url, 0)

                    // Page is loaded and bridge is injected — mark ready and render
                    isPageReady = true

                    // Apply IDE theme
                    applyCurrentTheme()

                    resolveCurrentModel()
                    refreshGraph()
                }
            }
        }, browser.cefBrowser)
    }

    private fun loadHtml() {
        val html = buildString {
            append(readResource("/js/lineage.html") ?: run {
                logger.error("lineage.html not found in resources")
                return
            })
        }
        // Inline JS files into the HTML
        val cytoscape = readResource("/js/cytoscape.min.js") ?: ""
        val dagre = readResource("/js/dagre.min.js") ?: ""
        val cytoscapeDagre = readResource("/js/cytoscape-dagre.js") ?: ""
        val lineageJs = readResource("/js/lineage.js") ?: ""

        val fullHtml = html
            .replace("<script src=\"cytoscape.min.js\"></script>", "<script>$cytoscape</script>")
            .replace("<script src=\"dagre.min.js\"></script>", "<script>$dagre</script>")
            .replace("<script src=\"cytoscape-dagre.js\"></script>", "<script>$cytoscapeDagre</script>")
            .replace("<script src=\"lineage.js\"></script>", "<script>$lineageJs</script>")

        browser.loadHTML(fullHtml)
    }

    private fun readResource(path: String): String? {
        return javaClass.getResourceAsStream(path)?.use { it.bufferedReader().readText() }
    }

    private fun resolveCurrentModel() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file)
        if (modelId != null) {
            currentModelId = modelId
        }
    }

    fun onFileChanged(file: VirtualFile) {
        if (isDisposed) return
        val service = ManifestService.getInstance(project)
        val index = service.getIndex()
        val modelId = service.findCurrentModelId(file)

        // If current model is a source/exposure in the same file, don't override
        // (user may have clicked a specific node in the graph)
        if (modelId != null && modelId != currentModelId) {
            val currentIsInSameFile = currentModelId?.let { curId ->
                val curPath = index.nodes[curId]?.originalFilePath
                    ?: index.sources[curId]?.originalFilePath
                    ?: index.exposures[curId]?.originalFilePath
                val newPath = index.nodes[modelId]?.originalFilePath
                    ?: index.sources[modelId]?.originalFilePath
                    ?: index.exposures[modelId]?.originalFilePath
                curPath != null && curPath == newPath
            } ?: false

            if (!currentIsInSameFile) {
                currentModelId = modelId
                expandedBoundaryNodes.clear()
                refreshGraph()
            }
        } else if (modelId != null && modelId == currentModelId) {
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) executeJs("highlightNode('${escapeJs(modelId)}')")
            }
        }
    }

    fun refreshGraph() {
        val modelId = currentModelId ?: return
        if (!isPageReady || isDisposed) return

        // Build graph off-EDT, then send to browser on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (isDisposed) return@executeOnPooledThread
                val service = ManifestService.getInstance(project)
                val index = service.getIndex()
                if (index === ManifestIndex.EMPTY) return@executeOnPooledThread

                val settings = DbtHelperSettings.getInstance(project)
                val builder = LineageGraphBuilder(index)
                val graph = builder.build(
                    currentNodeId = modelId,
                    upstreamDepth = settings.state.upstreamDepth,
                    downstreamDepth = settings.state.downstreamDepth,
                    showTestNodes = settings.state.showTestNodes,
                    showExposures = settings.state.showExposures,
                    expandedBoundaryNodes = expandedBoundaryNodes
                ).copy(edgeCurveStyle = settings.state.edgeCurveStyle, layoutDirection = settings.state.layoutDirection)

                val graphJson = mapper.writeValueAsString(graph)
                val escaped = graphJson.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed) executeJs("renderGraph('$escaped')")
                }
            } catch (e: Exception) {
                logger.warn("Error building lineage graph", e)
            }
        }
    }

    private fun applyCurrentTheme() {
        if (!isPageReady || isDisposed) return
        val bg = UIManager.getColor("Panel.background")
        val isDark = bg != null && (bg.red + bg.green + bg.blue) / 3 < 128
        executeJs("applyTheme($isDark)")
    }

    private fun handleNodeClick(nodeId: String, resourceType: String) {
        // Focus lineage on clicked node directly (don't wait for file open event)
        if (nodeId != currentModelId) {
            currentModelId = nodeId
            expandedBoundaryNodes.clear()
            refreshGraph()
        }

        // Also open the file in editor
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) return@invokeLater
            val service = ManifestService.getInstance(project)
            val index = service.getIndex()
            val locator = service.getLocator()
            val dbtRoot = locator.findProjectRoot() ?: return@invokeLater

            val filePath = when (resourceType) {
                "source" -> index.sources[nodeId]?.originalFilePath
                "exposure" -> index.exposures[nodeId]?.originalFilePath
                else -> index.nodes[nodeId]?.originalFilePath
            } ?: return@invokeLater

            val fullPath = "${dbtRoot.path}/$filePath"
            val vFile = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun handleExpandRequest(boundaryNodeId: String) {
        expandedBoundaryNodes.add(boundaryNodeId)
        refreshGraph()
    }

    private fun executeJs(code: String) {
        if (!isDisposed) {
            browser.cefBrowser.executeJavaScript(code, browser.cefBrowser.url, 0)
        }
    }

    private fun escapeJs(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    override fun dispose() {
        isDisposed = true
        jsQueryBridge.dispose()
        browser.dispose()
    }
}
