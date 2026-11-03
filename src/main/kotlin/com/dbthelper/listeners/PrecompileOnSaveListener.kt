package com.dbthelper.listeners

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project

/** Saves (⌘S, autosave on frame deactivation or idle) feed [Precompiler]: precompile a model, or drop stale results. */
class PrecompileOnSaveListener(private val project: Project) : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        // The text being saved is what dbt will compile, even if typing goes on before the debounce ends.
        project.service<Precompiler>().onSaved(file, document.text)
    }
}
