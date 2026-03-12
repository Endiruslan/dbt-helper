package com.dbthelper.listeners

import com.dbthelper.core.ManifestService
import com.dbthelper.core.ProfilesParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import javax.swing.Timer

class ManifestFileWatcher(private val project: Project) : BulkFileListener {

    private val manifestDebounce = Timer(500) { _ ->
        ManifestService.getInstance(project).reparse()
    }.apply { isRepeats = false }

    private val profilesDebounce = Timer(500) { _ ->
        ProfilesParser.getInstance(project).invalidateCache()
    }.apply { isRepeats = false }

    override fun after(events: List<VFileEvent>) {
        for (event in events) {
            val name = event.file?.name ?: event.path.substringAfterLast('/')
            when (name) {
                "manifest.json", "catalog.json" -> manifestDebounce.restart()
                "profiles.yml" -> profilesDebounce.restart()
            }
        }
    }
}
