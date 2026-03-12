package com.dbthelper.toolwindow

import com.dbthelper.core.ManifestService
import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.*
import com.dbthelper.listeners.CurrentModelListener
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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

class DocsTab(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val browser = JBCefBrowser()
    private val jsQueryBridge = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    private var currentModelId: String? = null

    @Volatile
    private var isDisposed = false

    @Volatile
    private var isPageReady = false

    init {
        Disposer.register(parentDisposable, this)
        add(browser.component, BorderLayout.CENTER)

        browser.loadHTML(wrapHtml("<p class='empty'>Loading...</p>"))

        setupJsBridge()
        setupLoadHandler()

        val connection = project.messageBus.connect(this)

        connection.subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) {
                resolveCurrentModel()
                refreshDocs()
            }
        })

        connection.subscribe(CurrentModelListener.TOPIC, object : CurrentModelListener {
            override fun onCurrentModelChanged(file: VirtualFile) {
                val service = ManifestService.getInstance(project)
                val modelId = service.findCurrentModelId(file)
                if (modelId != null && modelId != currentModelId) {
                    currentModelId = modelId
                    refreshDocs()
                }
            }
        })

        val appConnection = ApplicationManager.getApplication().messageBus.connect(this)
        appConnection.subscribe(LafManagerListener.TOPIC, LafManagerListener { refreshDocs() })

        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed) {
                resolveCurrentModel()
                refreshDocs()
            }
        }
    }

    private fun setupJsBridge() {
        jsQueryBridge.addHandler { request ->
            if (request == "toggleCode") {
                val settings = DbtHelperSettings.getInstance(project)
                settings.state.showCompiledCode = !settings.state.showCompiledCode
                refreshDocs()
            }
            JBCefJSQuery.Response("ok")
        }
    }

    private fun setupLoadHandler() {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    val bridgeCode = jsQueryBridge.inject(
                        "request",
                        "function(response) {}",
                        "function(errorCode, errorMessage) {}"
                    )
                    val js = "window.__cefBridge = function(request) { $bridgeCode };"
                    cefBrowser?.executeJavaScript(js, cefBrowser.url, 0)
                    isPageReady = true
                }
            }
        }, browser.cefBrowser)
    }

    private fun resolveCurrentModel() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val service = ManifestService.getInstance(project)
        val modelId = service.findCurrentModelId(file)
        if (modelId != null) currentModelId = modelId
    }

    fun refreshDocs() {
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            val html = buildDocsHtml()
            ApplicationManager.getApplication().invokeLater {
                if (!isDisposed) browser.loadHTML(html)
            }
        }
    }

    private fun buildDocsHtml(): String {
        val modelId = currentModelId
        val index = ManifestService.getInstance(project).getIndex()
        if (index === ManifestIndex.EMPTY || modelId == null) {
            return wrapHtml("<p class='empty'>No model selected or manifest not loaded.</p>")
        }

        val node = index.nodes[modelId]
        if (node != null) return buildNodeDocsHtml(node, index)

        val source = index.sources[modelId]
        if (source != null) return buildSourceDocsHtml(source, index)

        return wrapHtml("<p class='empty'>Model not found in manifest: ${esc(modelId)}</p>")
    }

    private fun buildNodeDocsHtml(node: DbtNode, index: ManifestIndex): String = buildString {
        val showCompiled = DbtHelperSettings.getInstance(project).state.showCompiledCode

        append("<div class='doc-container'>")

        // Header
        append("<h2>${esc(node.name)}</h2>")
        append("<div class='meta'>")
        append("<span class='badge type'>${esc(node.resourceType)}</span>")
        val mat = node.config["materialized"] as? String
        if (mat != null) append(" <span class='badge mat'>${esc(mat)}</span>")
        append(" <span class='badge pkg'>${esc(node.packageName)}</span>")
        append("</div>")

        // Location
        if (node.database != null || node.schema != null) {
            val parts = listOfNotNull(node.database, node.schema, node.alias ?: node.name)
            append("<p class='location'>${parts.joinToString(".") { esc(it) }}</p>")
        }

        // FQN
        if (node.fqn.isNotEmpty()) {
            append("<p class='fqn'>${node.fqn.joinToString(" &rarr; ") { esc(it) }}</p>")
        }

        // Description
        if (node.description.isNotEmpty()) {
            append("<div class='description'>${esc(node.description)}</div>")
        }

        // Config
        val configItems = buildConfigItems(node.config)
        if (configItems.isNotEmpty()) {
            append("<div class='section'><h3>Config</h3>")
            append("<table class='kv-table'>")
            for ((k, v) in configItems) {
                append("<tr><td class='kv-key'>${esc(k)}</td><td>${esc(v)}</td></tr>")
            }
            append("</table></div>")
        }

        // Dependencies
        val upstream = index.getUpstream(node.uniqueId)
        val downstream = index.getDownstream(node.uniqueId)
        if (upstream.isNotEmpty() || downstream.isNotEmpty()) {
            append("<div class='section'>")
            if (upstream.isNotEmpty()) {
                append("<h3>Depends on (${upstream.size})</h3>")
                append("<div class='dep-list'>")
                upstream.mapNotNull { friendlyName(it, index) }.forEach {
                    append("<span class='dep-item'>${esc(it)}</span>")
                }
                append("</div>")
            }
            if (downstream.isNotEmpty()) {
                append("<h3>Referenced by (${downstream.size})</h3>")
                append("<div class='dep-list'>")
                downstream.mapNotNull { friendlyName(it, index) }.forEach {
                    append("<span class='dep-item'>${esc(it)}</span>")
                }
                append("</div>")
            }
            append("</div>")
        }

        // Columns
        if (node.columns.isNotEmpty()) {
            append("<div class='section'>")
            append("<h3>Columns (${node.columns.size})</h3>")
            append("<table class='col-table'><thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead><tbody>")
            for ((_, col) in node.columns) {
                append("<tr>")
                append("<td class='col-name'>${esc(col.name)}</td>")
                append("<td class='col-type'>${esc(col.dataType ?: "")}</td>")
                append("<td class='col-desc'>${esc(col.description)}</td>")
                append("</tr>")
            }
            append("</tbody></table></div>")
        }

        // SQL Code with toggle
        val rawCode = node.rawCode
        val compiledCode = node.compiledCode
        if (rawCode != null || compiledCode != null) {
            append("<div class='section'>")
            append("<div class='code-header'>")
            append("<h3>SQL</h3>")
            if (rawCode != null && compiledCode != null) {
                val rawClass = if (!showCompiled) "active" else ""
                val compiledClass = if (showCompiled) "active" else ""
                append("<div class='code-toggle'>")
                append("<button class='toggle-btn $rawClass' onclick=\"window.__cefBridge('toggleCode')\">Raw</button>")
                append("<button class='toggle-btn $compiledClass' onclick=\"window.__cefBridge('toggleCode')\">Compiled</button>")
                append("</div>")
            }
            append("</div>")
            val code = if (showCompiled && compiledCode != null) compiledCode else rawCode ?: ""
            append("<pre class='code-block'>${esc(code)}</pre>")
            append("</div>")
        }

        // Tags
        if (node.tags.isNotEmpty()) {
            append("<div class='section'><h3>Tags</h3><div class='dep-list'>")
            node.tags.forEach { append("<span class='dep-item'>${esc(it)}</span>") }
            append("</div></div>")
        }

        // File path + patch path
        append("<div class='section filepath-section'>")
        append("<p class='filepath'>${esc(node.originalFilePath)}</p>")
        if (node.patchPath != null) {
            append("<p class='filepath'>docs: ${esc(node.patchPath)}</p>")
        }
        append("</div>")

        append("</div>")
    }.let { wrapHtml(it) }

    private fun buildSourceDocsHtml(source: DbtSource, index: ManifestIndex): String = buildString {
        append("<div class='doc-container'>")
        append("<h2>${esc(source.sourceName)}.${esc(source.name)}</h2>")
        append("<div class='meta'>")
        append("<span class='badge type'>source</span>")
        append(" <span class='badge pkg'>${esc(source.packageName)}</span>")
        if (source.loader != null) append(" <span class='badge loader'>${esc(source.loader)}</span>")
        append("</div>")

        // Location
        val relationDisplay = source.externalRelationName
        if (relationDisplay != null) {
            append("<p class='location'>${esc(relationDisplay)}</p>")
        } else {
            val parts = listOfNotNull(source.database, source.schema, source.identifier ?: source.name)
            if (parts.isNotEmpty()) {
                append("<p class='location'>${parts.joinToString(".") { esc(it) }}</p>")
            }
        }

        // Source-level description
        if (!source.sourceDescription.isNullOrEmpty() && source.sourceDescription != source.description) {
            append("<div class='description'><b>Source:</b> ${esc(source.sourceDescription)}</div>")
        }

        // Table description
        if (source.description.isNotEmpty()) {
            append("<div class='description'>${esc(source.description)}</div>")
        }

        // Freshness
        if (source.freshnessWarnAfter != null || source.freshnessErrorAfter != null || source.loadedAtField != null) {
            append("<div class='section'><h3>Freshness</h3>")
            append("<table class='kv-table'>")
            if (source.loadedAtField != null) {
                append("<tr><td class='kv-key'>loaded_at_field</td><td>${esc(source.loadedAtField)}</td></tr>")
            }
            if (source.freshnessWarnAfter != null) {
                append("<tr><td class='kv-key'>warn_after</td><td>${esc(source.freshnessWarnAfter)}</td></tr>")
            }
            if (source.freshnessErrorAfter != null) {
                append("<tr><td class='kv-key'>error_after</td><td>${esc(source.freshnessErrorAfter)}</td></tr>")
            }
            append("</table></div>")
        }

        // Referenced by
        val downstream = index.getDownstream(source.uniqueId)
        if (downstream.isNotEmpty()) {
            append("<div class='section'>")
            append("<h3>Referenced by (${downstream.size})</h3>")
            append("<div class='dep-list'>")
            downstream.mapNotNull { friendlyName(it, index) }.forEach {
                append("<span class='dep-item'>${esc(it)}</span>")
            }
            append("</div></div>")
        }

        // Columns
        if (source.columns.isNotEmpty()) {
            append("<div class='section'>")
            append("<h3>Columns (${source.columns.size})</h3>")
            append("<table class='col-table'><thead><tr><th>Name</th><th>Type</th><th>Description</th></tr></thead><tbody>")
            for ((_, col) in source.columns) {
                append("<tr>")
                append("<td class='col-name'>${esc(col.name)}</td>")
                append("<td class='col-type'>${esc(col.dataType ?: "")}</td>")
                append("<td class='col-desc'>${esc(col.description)}</td>")
                append("</tr>")
            }
            append("</tbody></table></div>")
        }

        // Tags
        if (source.tags.isNotEmpty()) {
            append("<div class='section'><h3>Tags</h3><div class='dep-list'>")
            source.tags.forEach { append("<span class='dep-item'>${esc(it)}</span>") }
            append("</div></div>")
        }

        // File path
        append("<p class='filepath'>${esc(source.originalFilePath)}</p>")
        append("</div>")
    }.let { wrapHtml(it) }

    private fun buildConfigItems(config: Map<String, Any?>): List<Pair<String, String>> {
        val skip = setOf("enabled", "quoting", "column_types", "persist_docs", "full_refresh")
        return config.entries
            .filter { it.key !in skip && it.value != null }
            .mapNotNull { (k, v) ->
                val str = when (v) {
                    is String -> v.ifEmpty { return@mapNotNull null }
                    is Boolean -> v.toString()
                    is Number -> v.toString()
                    else -> {
                        val s = v.toString()
                        if (s == "{}" || s == "[]" || s == "null") return@mapNotNull null
                        s
                    }
                }
                k to str
            }
    }

    private fun wrapHtml(body: String): String {
        val isDark = isDarkTheme()
        val bg = if (isDark) "#1e1e1e" else "#f5f5f5"
        val text = if (isDark) "#ccc" else "#333"
        val heading = if (isDark) "#fff" else "#111"
        val muted = if (isDark) "#888" else "#666"
        val border = if (isDark) "#444" else "#ddd"
        val badgeBg = if (isDark) "#333" else "#e8e8e8"
        val tableStripe = if (isDark) "#2a2a2a" else "#f9f9f9"
        val depBg = if (isDark) "#2a3a4a" else "#e3f2fd"
        val codeBg = if (isDark) "#252525" else "#f8f8f8"
        val codeBorder = if (isDark) "#3a3a3a" else "#e0e0e0"
        val toggleActive = if (isDark) "#2196F3" else "#1976D2"
        val toggleInactive = if (isDark) "#444" else "#ddd"

        return """<!DOCTYPE html>
<html><head><meta charset="UTF-8"><style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:$bg; color:$text; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif; font-size:13px; line-height:1.5; }
.doc-container { padding:16px 20px; max-width:900px; }
.empty { color:$muted; padding:20px; }
h2 { color:$heading; font-size:18px; margin-bottom:4px; }
h3 { color:$heading; font-size:14px; margin:12px 0 6px 0; }
.meta { margin-bottom:8px; }
.badge { display:inline-block; padding:2px 8px; border-radius:3px; font-size:11px; background:$badgeBg; color:$text; margin-right:4px; }
.badge.type { background:#2196F3; color:#fff; }
.badge.mat { background:#FF9800; color:#fff; }
.badge.loader { background:#9C27B0; color:#fff; }
.badge.pkg { background:$badgeBg; }
.location { font-family:monospace; font-size:12px; color:$muted; margin-bottom:4px; }
.fqn { font-size:11px; color:$muted; margin-bottom:8px; }
.description { margin:8px 0; padding:8px 12px; border-left:3px solid #2196F3; background:$tableStripe; white-space:pre-wrap; }
.section { margin-top:12px; }
.dep-list { display:flex; flex-wrap:wrap; gap:4px; }
.dep-item { display:inline-block; padding:2px 8px; border-radius:3px; font-size:12px; font-family:monospace; background:$depBg; }
.col-table, .kv-table { width:100%; border-collapse:collapse; font-size:12px; }
.col-table th, .kv-table th { text-align:left; padding:6px 8px; border-bottom:2px solid $border; color:$muted; font-weight:600; }
.col-table td, .kv-table td { padding:4px 8px; border-bottom:1px solid $border; vertical-align:top; }
.col-table tr:nth-child(even), .kv-table tr:nth-child(even) { background:$tableStripe; }
.col-name { font-family:monospace; font-weight:500; }
.col-type { color:$muted; font-family:monospace; }
.kv-key { font-family:monospace; font-weight:500; color:$muted; white-space:nowrap; width:1%; }
.code-header { display:flex; align-items:center; justify-content:space-between; }
.code-toggle { display:flex; gap:0; }
.toggle-btn { padding:3px 10px; font-size:11px; border:1px solid $border; background:$toggleInactive; color:$text; cursor:pointer; font-family:inherit; }
.toggle-btn:first-child { border-radius:3px 0 0 3px; }
.toggle-btn:last-child { border-radius:0 3px 3px 0; }
.toggle-btn.active { background:$toggleActive; color:#fff; border-color:$toggleActive; }
.code-block { background:$codeBg; border:1px solid $codeBorder; border-radius:4px; padding:10px 12px; font-family:'JetBrains Mono',monospace; font-size:12px; line-height:1.5; overflow-x:auto; white-space:pre; margin-top:6px; max-height:400px; overflow-y:auto; }
.filepath { font-size:11px; color:$muted; font-family:monospace; }
.filepath-section { margin-top:16px; }
</style></head><body>$body</body></html>"""
    }

    private fun isDarkTheme(): Boolean {
        val bg = UIManager.getColor("Panel.background") ?: return true
        return (bg.red + bg.green + bg.blue) / 3 < 128
    }

    private fun friendlyName(uniqueId: String, index: ManifestIndex): String? =
        com.dbthelper.core.DbtUtils.friendlyName(uniqueId, index)

    private fun esc(s: String) = com.dbthelper.core.DbtUtils.escapeHtml(s)

    override fun dispose() {
        isDisposed = true
        jsQueryBridge.dispose()
        browser.dispose()
    }
}
