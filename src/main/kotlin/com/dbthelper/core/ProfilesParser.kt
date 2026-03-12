package com.dbthelper.core

import com.dbthelper.core.model.ProfilesConfig
import com.dbthelper.core.model.TargetConfig
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.Yaml

@Service(Service.Level.PROJECT)
class ProfilesParser(private val project: Project) {

    private val logger = Logger.getInstance(ProfilesParser::class.java)
    private val locator = DbtProjectLocator(project)

    @Volatile
    private var cachedConfig: ProfilesConfig? = null

    fun parse(): ProfilesConfig? {
        cachedConfig?.let { return it }

        val file = locator.getProfilesFile() ?: return null
        return try {
            val yaml = Yaml()
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
                    type = config["type"] as? String ?: "unknown",
                    database = config["database"] as? String ?: config["dbname"] as? String,
                    schema = config["schema"] as? String,
                    host = config["host"] as? String,
                    port = (config["port"] as? Number)?.toInt(),
                    threads = (config["threads"] as? Number)?.toInt()
                )
            }.toMap()

            ProfilesConfig(
                profileName = profileName,
                defaultTarget = defaultTarget,
                targets = targets
            ).also { cachedConfig = it }
        } catch (e: Exception) {
            logger.warn("Failed to parse profiles.yml", e)
            null
        }
    }

    private fun readProfileFromProject(): String? {
        return try {
            val root = locator.findProjectRoot() ?: return null
            val dbtProjectFile = root.findChild("dbt_project.yml") ?: return null
            val yaml = Yaml()
            @Suppress("UNCHECKED_CAST")
            val data = dbtProjectFile.inputStream.use { yaml.load<Map<String, Any>>(it) }
            data["profile"] as? String
        } catch (e: Exception) {
            logger.warn("Failed to read profile from dbt_project.yml", e)
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
