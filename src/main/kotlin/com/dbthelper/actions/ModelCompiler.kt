package com.dbthelper.actions

import com.dbthelper.core.ManifestUpdateListener
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.settings.DbtHelperSettings
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Compiles single models with dbt and keeps the result for Run Selection on Target DB, against the
 * model's source text and the active target it was compiled with.
 *
 * Each compile is a background task with its own progress bar ("Compiling <model> with dbt"), which
 * can be cancelled. They run one at a time (later ones show "Waiting for another dbt compile"): two
 * dbt processes on the same target directory race on its partial-parse file and artifacts. A request
 * for a text that is already compiling joins that compile instead of starting another, so a run right
 * after a save waits for the precompile.
 *
 * Results also depend on macros, vars and other models, which the key doesn't cover: they are all
 * dropped when the manifest reloads, and by [invalidateAll] when a non-model project file is saved.
 */
@Service(Service.Level.PROJECT)
class ModelCompiler(private val project: Project) : Disposable {

    data class Result(val compiled: String?, val error: String?)

    private class Entry(val key: String, val future: CompletableFuture<Result>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val oneAtATime = Semaphore(1)

    init {
        project.messageBus.connect(this).subscribe(ManifestUpdateListener.TOPIC, object : ManifestUpdateListener {
            override fun onManifestUpdated(index: ManifestIndex) = invalidateAll()
        })
    }

    override fun dispose() {}

    /** Drops every kept result; compiles already running still complete for whoever waits on them. */
    fun invalidateAll() = entries.clear()

    /** Compiled SQL already available for [source], without starting dbt. */
    fun cached(modelId: String, source: String): String? =
        entries[modelId]?.takeIf { it.key == key(source) && it.future.isDone }?.future?.getNow(null)?.compiled

    /**
     * Compiled SQL for [modelId] as [source]: the kept result, the compile already running for that
     * text, or a new compile. dbt reads the file from disk, so callers save it first.
     */
    fun compile(modelId: String, modelName: String, source: String): CompletableFuture<Result> {
        val key = key(source)
        var started: CompletableFuture<Result>? = null
        val entry = entries.compute(modelId) { _, current ->
            // A failed or cancelled compile is not kept: the next request tries again.
            if (current != null && current.key == key &&
                !(current.future.isDone && current.future.getNow(null)?.compiled == null)
            ) current
            else Entry(key, CompletableFuture<Result>().also { started = it })
        }!!
        started?.let { future -> ProgressManager.getInstance().run(CompileTask(modelName, future)) }
        return entry.future
    }

    private fun key(source: String) = DbtHelperSettings.getInstance(project).state.activeTarget + "\u0000" + source

    private inner class CompileTask(
        private val modelName: String,
        private val future: CompletableFuture<Result>,
    ) : Task.Backgroundable(project, "Compiling $modelName with dbt", true) {

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            if (!oneAtATime.tryAcquire()) {
                indicator.text = "Waiting for another dbt compile…"
                while (!oneAtATime.tryAcquire(200, TimeUnit.MILLISECONDS)) indicator.checkCanceled()
            }
            try {
                indicator.text = "dbt compile --select $modelName"
                future.complete(runDbt(modelName, indicator))
            } finally {
                oneAtATime.release()
            }
        }

        override fun onCancel() {
            future.complete(Result(null, "Compile of $modelName cancelled"))
        }

        override fun onThrowable(error: Throwable) {
            future.complete(Result(null, error.message ?: error.toString()))
        }
    }

    private fun runDbt(modelName: String, indicator: ProgressIndicator): Result {
        val latch = CountDownLatch(1)
        var finished: DbtCommandRunner.RunResult? = null
        val dbtProcess = AtomicReference<Process?>()
        DbtCommandRunner(project).runCompileJson(modelName, object : DbtCommandRunner.OutputListener {
            override fun onLine(line: String) {}
            override fun onProcessStarted(process: Process) = dbtProcess.set(process)
            override fun onFinished(result: DbtCommandRunner.RunResult) {
                finished = result
                latch.countDown()
            }
        })
        // Poll so the Cancel button stops dbt. After a cancel, keep waiting until the process has
        // exited (it may not have started yet): the slot must not go to the next compile while
        // this dbt still runs on the same target directory.
        var cancelled = false
        while (!latch.await(200, TimeUnit.MILLISECONDS)) {
            if (indicator.isCanceled) cancelled = true
            if (cancelled) dbtProcess.get()?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy() }
        }
        if (cancelled) throw ProcessCanceledException()
        val run = finished ?: return Result(null, "dbt did not run")
        // Wrappers and dbt itself may log around the JSON: parse from the last line that opens an object.
        val lines = run.output.lines()
        val jsonStart = lines.indexOfLast { it.trim() == "{" }
        val compiled = if (jsonStart < 0) null else runCatching {
            ObjectMapper().readTree(lines.drop(jsonStart).joinToString("\n")).get("compiled")?.asText()
        }.getOrNull()
        if (run.success && compiled != null) return Result(compiled, null)
        return Result(null, lines.filter { it.isNotBlank() }.takeLast(8).joinToString("\n"))
    }
}
