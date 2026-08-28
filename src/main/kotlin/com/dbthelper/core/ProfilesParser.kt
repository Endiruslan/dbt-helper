package com.dbthelper.core

import com.dbthelper.core.model.ProfilesConfig
import com.dbthelper.core.model.TargetConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

@Service(Service.Level.PROJECT)
class ProfilesParser(private val project: Project) {

    private val logger = Logger.getInstance(ProfilesParser::class.java)
    private val locator = DbtProjectLocator.getInstance(project)

    @Volatile
    private var cachedConfig: ProfilesConfig? = null

    /**
     * SnakeYAML restricted to safe (standard-tag) construction. SafeConstructor forbids the
     * `!!some.java.Class` global tags that allow arbitrary type instantiation (CVE-2022-1471
     * class), independent of the resolved SnakeYAML version, and the LoaderOptions cap alias
     * expansion and recursive keys. These files come from repositories the user may not control.
     */
    private fun safeYaml(): Yaml {
        // allowRecursiveKeys already defaults to false in LoaderOptions; the alias cap bounds
        // billion-laughs-style expansion. SafeConstructor is the load-bearing control (no
        // arbitrary Java-type construction).
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 50
        }
        return Yaml(SafeConstructor(options))
    }

    fun parse(): ProfilesConfig? {
        cachedConfig?.let { return it }

        val file = locator.getProfilesFile() ?: return null
        return try {
            val yaml = safeYaml()
            val data = file.inputStream().use { yaml.load<Map<String, Any>>(it) }

            // Read profile name from dbt_project.yml
            val profileName = readProfileFromProject() ?: data.keys.firstOrNull { it != "config" } ?: return null

            @Suppress("UNCHECKED_CAST")
            val profileData = data[profileName] as? Map<String, Any> ?: return null
            val defaultTarget = profileData["target"] as? String ?: "dev"

            @Suppress("UNCHECKED_CAST")
            val outputs = profileData["outputs"] as? Map<String, Map<String, Any>> ?: emptyMap()
            val targets = outputs.map { (name, config) ->
                name to TargetConfig(
                    name = name,
                    type = config["type"] as? String ?: "unknown"
                )
            }.toMap()

            ProfilesConfig(
                profileName = profileName,
                defaultTarget = defaultTarget,
                targets = targets
            ).also { cachedConfig = it }
        } catch (e: Exception) {
            // Do NOT attach the exception: a SnakeYAML parse error embeds a snippet of the
            // offending source line, which in profiles.yml could be a plaintext credential.
            logger.warn("Failed to parse profiles.yml (${e.javaClass.simpleName})")
            null
        }
    }

    private fun readProfileFromProject(): String? {
        return try {
            val root = locator.findProjectRoot() ?: return null
            val dbtProjectFile = root.findChild("dbt_project.yml") ?: return null
            val yaml = safeYaml()
            @Suppress("UNCHECKED_CAST")
            val data = dbtProjectFile.inputStream.use { yaml.load<Map<String, Any>>(it) }
            data["profile"] as? String
        } catch (e: Exception) {
            logger.warn("Failed to read profile from dbt_project.yml (${e.javaClass.simpleName})")
            null
        }
    }

    fun getTargetNames(): List<String> = parse()?.targets?.keys?.toList() ?: emptyList()

    fun getDefaultTarget(): String? = parse()?.defaultTarget

    fun invalidateCache() {
        cachedConfig = null
    }

    companion object {
        fun getInstance(project: Project): ProfilesParser =
            project.service<ProfilesParser>()
    }
}
