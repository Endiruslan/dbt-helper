package com.dbthelper.core

import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

class DbtProjectLocator(private val project: Project) {

    @Volatile
    private var cachedDbtRoots: List<VirtualFile> = emptyList()

    fun findAllDbtRoots(): List<VirtualFile> {
        if (cachedDbtRoots.isNotEmpty()) return cachedDbtRoots

        val roots = ReadAction.compute<List<VirtualFile>, Throwable> {
            val scope = GlobalSearchScope.projectScope(project)
            val files = FilenameIndex.getVirtualFilesByName("dbt_project.yml", scope)
            files.mapNotNull { it.parent }.sortedBy { it.path.length }
        }
        cachedDbtRoots = roots
        return cachedDbtRoots
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
            // Walk up from file to find nearest dbt_project.yml
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
        cachedDbtRoots = emptyList()
    }
}
