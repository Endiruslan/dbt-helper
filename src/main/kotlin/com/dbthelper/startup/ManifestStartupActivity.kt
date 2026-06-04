package com.dbthelper.startup

import com.dbthelper.core.DbtProjectLocator
import com.dbthelper.core.ManifestService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Loads target/manifest.json in the background when a dbt project is opened. */
class ManifestStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!DbtProjectLocator(project).hasDbtProjects()) return
        ManifestService.getInstance(project).reparse()
    }
}
