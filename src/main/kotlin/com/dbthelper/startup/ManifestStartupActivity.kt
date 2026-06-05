package com.dbthelper.startup

import com.dbthelper.core.ManifestService
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Loads the existing target/manifest.json in the background when a dbt project opens.
 *
 * Deferred until indexing finishes: project discovery uses [com.intellij.psi.search.FilenameIndex],
 * which is unavailable during indexing (dumb mode). Running at project open without this gate races
 * indexing — the parse finds no dbt roots, leaves the index empty, and nothing retries afterwards,
 * so the lineage view shows "waiting for data" until the user manually regenerates docs.
 *
 * [DumbService.runWhenSmart] runs immediately if indexes are already ready, otherwise once they are.
 * The dbt-project check is left to [ManifestService.reparse]/doParse (off the EDT, in smart mode).
 */
class ManifestStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            ManifestService.getInstance(project).reparse()
        }
    }
}
