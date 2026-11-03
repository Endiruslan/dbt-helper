package com.dbthelper.actions

import com.dbthelper.core.CteExtractor
import com.dbthelper.core.ManifestService
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.editor.DatabaseEditorHelper
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.vfs.DbVFSUtils
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

/**
 * Runs the CTE under the caret (or the selected SQL) on a Database Tools data source, with the CTEs
 * it reads and refs resolved, and shows the result in the IDE's own result grid.
 *
 * Two ways to get SQL the database accepts:
 * - only ref(), source(), this and config() in the part to run: resolved from the loaded manifest,
 *   like Copy for Target DB, with no dbt call;
 * - any other Jinja (macros, is_incremental(), variables): dbt compiles the whole model, and the
 *   same CTE is cut out of the compiled SQL (CTE names survive compilation). The compiled model is
 *   kept until the file changes, so the other CTEs of that model then run without compiling again.
 *
 * Registered from dbt-helper-database.xml, so it only exists in IDEs that ship Database Tools.
 */
class RunSelectionOnTargetAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val manifest = ManifestService.getInstance(project)
        val index = manifest.getIndex()
        if (index === ManifestIndex.EMPTY) {
            notify(project, "dbt manifest not loaded. Run 'dbt parse' or 'dbt compile' to generate manifest.json.", NotificationType.WARNING)
            return
        }

        val text = editor.document.text
        val selection = editor.selectionModel
        val (start, end) = if (selection.hasSelection()) selection.selectionStart to selection.selectionEnd
        else editor.caretModel.offset to editor.caretModel.offset
        val extraction = CteExtractor.extract(text, start, end)

        val modelId = file?.let { manifest.findCurrentModelId(it) }
        val dataSource = findAttachedDataSource(project, file)

        resolveWithoutDbt(extraction.sql, index, modelId)?.let { sql ->
            withDataSource(project, dataSource) { runInConsole(project, it, sql) }
            return
        }

        val modelName = modelId?.let { index.nodes[it]?.name }
        if (modelId == null || modelName == null) {
            notify(project, "This file isn't a model in the manifest, so its macros can't be compiled.", NotificationType.WARNING)
            return
        }
        if (extraction.kind == CteExtractor.Kind.SELECTION) {
            notify(
                project,
                "A selection outside the CTEs can't be mapped to the compiled model. Put the caret in a CTE, or select nothing to run the whole model.",
                NotificationType.WARNING,
            )
            return
        }

        fun runCompiled(compiled: String) {
            val sql = when (extraction.kind) {
                CteExtractor.Kind.CTE -> CteExtractor.cteQuery(compiled, extraction.targetCte!!)
                else -> compiled.trim()
            }
            if (sql == null) {
                notify(project, "CTE '${extraction.targetCte}' not found in the compiled model.", NotificationType.WARNING)
                return
            }
            withDataSource(project, dataSource) { runInConsole(project, it, sql) }
        }

        val compiler = project.service<ModelCompiler>()
        compiler.cached(modelId, text)?.let { return runCompiled(it) }

        // dbt compiles the file on disk.
        FileDocumentManager.getInstance().saveDocument(editor.document)
        // The compile shows its own progress bar; this only waits for its result.
        compiler.compile(modelId, modelName, text).thenAccept { result ->
            ApplicationManager.getApplication().invokeLater {
                val compiled = result.compiled
                if (compiled == null) notify(project, "dbt compile failed:\n${result.error}", NotificationType.ERROR)
                else runCompiled(compiled)
            }
        }
    }

    /** The data source the dbt file is attached to, if any. */
    private fun findAttachedDataSource(project: Project, file: VirtualFile?): DbDataSource? {
        file ?: return null
        JdbcConsoleProvider.getConsole(project, file)?.dataSource?.let { raw ->
            DbPsiFacade.getInstance(project).dataSources.firstOrNull { it.uniqueId == raw.uniqueId }?.let { return it }
        }
        return DbVFSUtils.getDataSource(project, file)
    }

    /** Attached data source, then the one in settings, then the only one, then ask. */
    private fun withDataSource(project: Project, attached: DbDataSource?, run: (DbDataSource) -> Unit) {
        if (attached != null) return run(attached)
        val all = DbPsiFacade.getInstance(project).dataSources
        val settings = DbtHelperSettings.getInstance(project)
        val configured = settings.state.executionDataSource.trim()
        if (configured.isNotEmpty()) {
            val match = all.firstOrNull { it.name == configured || it.uniqueId == configured }
            if (match != null) return run(match)
            notify(project, "Data source '$configured' (dbt Helper settings) not found.", NotificationType.WARNING)
        }
        when (all.size) {
            0 -> notify(project, "No data source. Add one in the Database tool window first.", NotificationType.WARNING)
            1 -> run(all.first())
            else -> JBPopupFactory.getInstance()
                .createPopupChooserBuilder(all.sortedBy { it.name })
                .setTitle("Run On Data Source")
                .setRenderer(object : SimpleListCellRenderer<DbDataSource>() {
                    override fun customize(list: JList<out DbDataSource>, value: DbDataSource?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        text = value?.name
                    }
                })
                .setItemChosenCallback {
                    // Remembered, so the next run doesn't ask again. Editable in dbt Helper settings.
                    settings.state.executionDataSource = it.name
                    run(it)
                }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    /**
     * Puts [sql] in a query console of [dataSource] that this action owns (the user's own consoles
     * are left alone), selects it and runs the IDE's Execute action on it: results land in the
     * standard grid, with paging, sorting and export.
     */
    private fun runInConsole(project: Project, dataSource: DbDataSource, sql: String) {
        val consoles = project.service<Consoles>()
        val consoleFile = consoles.byDataSource[dataSource.uniqueId]?.takeIf { it.isValid }
            ?: DatabaseEditorHelper.createNewConsoleVirtualFile(dataSource)?.also { consoles.byDataSource[dataSource.uniqueId] = it }
        if (consoleFile == null) {
            notify(project, "Could not open a console for ${dataSource.name}.", NotificationType.ERROR)
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(consoleFile) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Run Selection on Target DB", null, { document.setText(sql) })

        val consoleEditor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, consoleFile), true) ?: return
        consoleEditor.selectionModel.setSelection(0, document.textLength)

        // "Execute selection as one statement" when the IDE has it, else plain Execute (runs the selection).
        val actionManager = ActionManager.getInstance()
        val execute = actionManager.getAction("Console.Jdbc.Execute.Selection")
            ?: actionManager.getAction("Console.Jdbc.Execute")
        if (execute == null) {
            notify(project, "Query is in the console: press Execute to run it.", NotificationType.INFORMATION)
            return
        }
        // The data context comes from the console editor component, as if Execute were pressed there.
        actionManager.tryToExecute(execute, null, consoleEditor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("dbt Helper")
            .createNotification(message, type)
            .notify(project)
    }

    /** Console files created by this action, per data source id, so each run reuses its own. */
    @Service(Service.Level.PROJECT)
    class Consoles {
        val byDataSource = mutableMapOf<String, VirtualFile>()
    }

    companion object {
        private val JINJA_COMMENT = Regex("""\{#.*?#}""", RegexOption.DOT_MATCHES_ALL)
        private val JINJA_CONFIG = Regex("""\{\{-?\s*config\s*\(.*?\)\s*-?}}""", RegexOption.DOT_MATCHES_ALL)

        /**
         * [sql] with ref(), source() and this resolved from the manifest, Jinja comments and config()
         * (which render to nothing) removed. Null when any Jinja is left, an unknown ref() included:
         * only dbt can render it.
         */
        fun resolveWithoutDbt(sql: String, index: ManifestIndex, modelId: String?): String? {
            var resolved = CopyWithRefsReplacedAction.resolveRelations(sql, index, modelId)
            resolved = JINJA_COMMENT.replace(resolved, "")
            resolved = JINJA_CONFIG.replace(resolved, "")
            return if (resolved.contains("{{") || resolved.contains("{%")) null else resolved.trim()
        }
    }
}
