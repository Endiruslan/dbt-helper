package com.dbthelper.core

import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project service (single shared instance) so the discovered dbt roots are cached once and
 * reused everywhere instead of every caller re-running a project-wide index query.
 */
@Service(Service.Level.PROJECT)
class DbtProjectLocator(private val project: Project) {

    // null = not computed yet; empty list = computed, no dbt project found.
    @Volatile
    private var cachedDbtRoots: List<VirtualFile>? = null
    private val warming = AtomicBoolean(false)

    fun findAllDbtRoots(): List<VirtualFile> {
        cachedDbtRoots?.let { return it }

        // FilenameIndex is an index query and is prohibited on the EDT (2026.2 turns this into
        // a "Slow operations are prohibited on EDT" error). Warm the cache on a background thread
        // and return empty for now; UI refreshes when the manifest (re)loads, and the background
        // startup parse warms this before the user interacts anyway.
        if (ApplicationManager.getApplication().isDispatchThread) {
            scheduleWarm()
            return emptyList()
        }

        return computeRoots().also { cachedDbtRoots = it }
    }

    // Must only be called off the EDT (executeSynchronously() forbids the EDT); callers guarantee this.
    private fun computeRoots(): List<VirtualFile> =
        ReadAction.nonBlocking<List<VirtualFile>> {
            val scope = GlobalSearchScope.projectScope(project)
            FilenameIndex.getVirtualFilesByName("dbt_project.yml", scope)
                .mapNotNull { it.parent }
                .sortedBy { it.path.length }
        }.inSmartMode(project).executeSynchronously()

    private fun scheduleWarm() {
        if (!warming.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                cachedDbtRoots = computeRoots()
            } finally {
                warming.set(false)
            }
        }
    }

    fun findProjectRoot(file: VirtualFile? = null): VirtualFile? {
        // Check settings override first
        val override = DbtHelperSettings.getInstance(project).state.dbtProjectRootOverride
        if (override.isNotBlank()) {
            val overrideDir = LocalFileSystem.getInstance().findFileByPath(override)
            if (overrideDir != null && overrideDir.findChild("dbt_project.yml") != null) {
                return overrideDir
            }
        }

        if (file != null) {
            // Walk up from file to find nearest dbt_project.yml (VFS only, EDT-safe)
            var dir: VirtualFile? = file.parent
            while (dir != null) {
                if (dir.findChild("dbt_project.yml") != null) {
                    return dir
                }
                dir = dir.parent
            }
        }

        // Fallback: return the first (shortest path) discovered dbt root
        val roots = findAllDbtRoots()
        return roots.firstOrNull()
    }

    fun getTargetDir(file: VirtualFile? = null): VirtualFile? {
        val root = findProjectRoot(file) ?: return null
        return root.findChild("target")
    }

    fun getManifestFile(file: VirtualFile? = null): VirtualFile? {
        return getTargetDir(file)?.findChild("manifest.json")
    }

    fun getCatalogFile(file: VirtualFile? = null): VirtualFile? {
        return getTargetDir(file)?.findChild("catalog.json")
    }

    fun getProfilesFile(): File? {
        val envDir = System.getenv("DBT_PROFILES_DIR")
        if (envDir != null) {
            val file = File(envDir, "profiles.yml")
            if (file.exists()) return file
        }

        val homeDir = System.getProperty("user.home")
        val file = File(homeDir, ".dbt/profiles.yml")
        return if (file.exists()) file else null
    }

    fun isInsideDbtProject(file: VirtualFile): Boolean {
        return findProjectRoot(file) != null
    }

    fun getRelativePath(file: VirtualFile): String? {
        val root = findProjectRoot(file) ?: return null
        val rootPath = root.path
        val filePath = file.path
        if (!filePath.startsWith(rootPath)) return null
        return filePath.removePrefix(rootPath).removePrefix("/")
    }

    fun hasDbtProjects(): Boolean {
        return findAllDbtRoots().isNotEmpty()
    }

    fun invalidateCache() {
        cachedDbtRoots = null
        project.service<CatalogParser>().invalidateCache()
    }

    companion object {
        fun getInstance(project: Project): DbtProjectLocator = project.service()
    }
}
