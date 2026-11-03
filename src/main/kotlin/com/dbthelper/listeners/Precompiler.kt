package com.dbthelper.listeners

import com.dbthelper.actions.ModelCompiler
import com.dbthelper.actions.RunSelectionOnTargetAction
import com.dbthelper.core.ManifestService
import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * "Precompile models": compiles a model in the background when it is saved (⌘S or autosave) or
 * brought to the front in an editor, so Run Selection on Target DB finds it ready. Only for models
 * with Jinja that needs dbt; the others resolve from the manifest anyway. Requests for the same
 * file within [DEBOUNCE_MS] of each other give one compile, and an already compiled text none.
 */
@Service(Service.Level.PROJECT)
class Precompiler(private val project: Project) {

    private class Scheduled(val future: ScheduledFuture<*>)

    private val pending = ConcurrentHashMap<String, Scheduled>()

    fun isEnabled() = DbtHelperSettings.getInstance(project).state.precompileOnSave

    /**
     * A project file was saved with [text]. A model is scheduled for a compile. Anything else in the
     * dbt project (a macro, a properties .yml) can change how every model compiles, so the kept
     * results are dropped.
     */
    fun onSaved(file: VirtualFile, text: String) {
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) return
        if (file.extension != "sql" && file.extension != "yml") return
        val isModel = ManifestService.getInstance(project).findCurrentModelId(file) != null
        if (!isModel) {
            project.service<ModelCompiler>().invalidateAll()
            return
        }
        if (isEnabled() && FileEditorManager.getInstance(project).isFileOpen(file)) schedule(file, text, "saved")
    }

    /** [text] must be what is on disk: dbt compiles the file, the result is kept against [text]. */
    fun schedule(file: VirtualFile, text: String, reason: String) {
        if (!isEnabled() || file.extension != "sql" || !ProjectFileIndex.getInstance(project).isInContent(file)) return
        LOG.info("Precompile: ${file.name} $reason, compiling in $DEBOUNCE_MS ms unless asked again")
        // The task only runs if it is still the latest one for this file when it fires.
        lateinit var scheduled: Scheduled
        val future = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (pending.remove(file.url, scheduled)) precompile(file, text)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        scheduled = Scheduled(future)
        pending.put(file.url, scheduled)?.future?.cancel(false)
    }

    private fun precompile(file: VirtualFile, text: String) {
        if (project.isDisposed || !file.isValid) return
        val manifest = ManifestService.getInstance(project)
        // Off the EDT (the debounce runs on a pooled thread), as DbtProjectLocator does.
        val modelId = ReadAction.nonBlocking<String?> { manifest.findCurrentModelId(file) }.executeSynchronously()
        val modelName = modelId?.let { manifest.getIndex().nodes[it]?.name }
        if (modelName == null) return LOG.info("Precompile: ${file.name} skipped, not a model in the manifest")
        if (RunSelectionOnTargetAction.resolveWithoutDbt(text, manifest.getIndex(), modelId) != null) {
            return LOG.info("Precompile: $modelName skipped, only ref()/source(): resolved from the manifest without dbt")
        }
        val compiler = project.service<ModelCompiler>()
        if (compiler.cached(modelId, text) != null) return LOG.info("Precompile: $modelName already compiled")
        LOG.info("Precompile: compiling $modelName")
        // Errors stay quiet here; Run Selection reports them when the user asks for this model.
        compiler.compile(modelId, modelName, text)
    }

    companion object {
        private val LOG = Logger.getInstance(Precompiler::class.java)
        private const val DEBOUNCE_MS = 2000L
    }
}
