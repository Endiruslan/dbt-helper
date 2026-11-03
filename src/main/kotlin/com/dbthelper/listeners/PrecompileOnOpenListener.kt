package com.dbthelper.listeners

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project

/** Precompiles a model when its editor is opened or brought to the front, so a first run doesn't wait. */
class PrecompileOnOpenListener(private val project: Project) : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val precompiler = project.service<Precompiler>()
        if (!precompiler.isEnabled()) return
        val file = event.newFile ?: return
        val documents = FileDocumentManager.getInstance()
        // Unsaved edits: disk differs from the editor, and the save will precompile anyway.
        if (documents.isFileModified(file)) return
        val text = documents.getDocument(file)?.text ?: return
        precompiler.schedule(file, text, "opened")
    }
}
