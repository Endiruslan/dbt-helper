package com.dbthelper.core

import com.dbthelper.core.model.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*

@Service(Service.Level.PROJECT)
class ManifestService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(ManifestService::class.java)
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val locator = DbtProjectLocator(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var cachedIndex: ManifestIndex = ManifestIndex.EMPTY
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var isLoading: Boolean = false
        private set

    fun getIndex(): ManifestIndex = cachedIndex

    fun getLocator(): DbtProjectLocator = locator

    fun reparse() {
        scope.launch {
            doParse()
        }
    }

    private fun doParse() {
        isLoading = true
        lastError = null
        try {
            // Parse all dbt projects found in the workspace
            val dbtRoots = locator.findAllDbtRoots()
            if (dbtRoots.isEmpty()) {
                cachedIndex = ManifestIndex.EMPTY
                lastError = "No dbt projects found"
                return
            }

            val allManifests = dbtRoots.mapNotNull { root ->
                root.findChild("target")?.findChild("manifest.json")
            }
            if (allManifests.isEmpty()) {
                cachedIndex = ManifestIndex.EMPTY
                lastError = "manifest.json not found"
                return
            }

            // Merge all manifests — for now just parse the first one
            // TODO: support multi-project manifest merging
            val manifestFile = allManifests.first()
            val root = manifestFile.inputStream.use { mapper.readTree(it) }

            val nodes = parseNodes(root.get("nodes"))
            val sources = parseSources(root.get("sources"))
            val macros = parseMacros(root.get("macros"))
            val exposures = parseExposures(root.get("exposures"))

            val parentMap = mutableMapOf<String, List<String>>()
            val childMapBuilder = mutableMapOf<String, MutableList<String>>()
            val filePathMap = mutableMapOf<String, String>()
            val relationMap = mutableMapOf<String, String>()

            for ((id, node) in nodes) {
                parentMap[id] = node.dependsOnNodes
                for (parentId in node.dependsOnNodes) {
                    childMapBuilder.getOrPut(parentId) { mutableListOf() }.add(id)
                }
                val path = node.originalFilePath.replace('\\', '/')
                filePathMap[path] = id
                node.relationName?.let { relationMap[it] = id }
            }

            for ((id, source) in sources) {
                source.relationName?.let { relationMap[it] = id }
                // Add source file paths — multiple sources can share one yml file,
                // so only store first match per path
                val srcPath = source.originalFilePath.replace('\\', '/')
                filePathMap.putIfAbsent(srcPath, id)
            }

            for ((id, exposure) in exposures) {
                val expPath = exposure.originalFilePath.replace('\\', '/')
                filePathMap.putIfAbsent(expPath, id)
                parentMap[id] = exposure.dependsOnNodes
                for (parentId in exposure.dependsOnNodes) {
                    childMapBuilder.getOrPut(parentId) { mutableListOf() }.add(id)
                }
            }

            var index = ManifestIndex(
                nodes = nodes,
                sources = sources,
                macros = macros,
                exposures = exposures,
                parentMap = parentMap,
                childMap = childMapBuilder.mapValues { it.value.toList() },
                filePathMap = filePathMap,
                relationMap = relationMap
            )

            // Merge catalog if available
            val catalogParser = project.service<CatalogParser>()
            index = catalogParser.mergeCatalog(index)

            cachedIndex = index
            logger.info("dbt manifest parsed: ${index.modelCount} models, ${index.sourceCount} sources")

            // Notify listeners via message bus
            project.messageBus.syncPublisher(ManifestUpdateListener.TOPIC).onManifestUpdated(index)
        } catch (e: Exception) {
            lastError = e.message
            logger.warn("Failed to parse manifest", e)
        } finally {
            isLoading = false
        }
    }

    fun findCurrentModelId(file: VirtualFile): String? {
        val relativePath = locator.getRelativePath(file) ?: return null
        return cachedIndex.findByFilePath(relativePath)
    }

    private fun parseNodes(nodesNode: JsonNode?): Map<String, DbtNode> {
        if (nodesNode == null) return emptyMap()
        val result = mutableMapOf<String, DbtNode>()
        val fields = nodesNode.fields()
        while (fields.hasNext()) {
            val (id, node) = fields.next()
            // Parse config as flat map
            val configNode = node.get("config")
            val configMap = if (configNode != null && configNode.isObject) {
                val m = mutableMapOf<String, Any?>()
                configNode.fields().forEach { (k, v) ->
                    when {
                        v.isTextual -> m[k] = v.asText()
                        v.isBoolean -> m[k] = v.asBoolean()
                        v.isNumber -> m[k] = v.numberValue()
                        v.isNull -> {}
                        else -> m[k] = v.toString()
                    }
                }
                m
            } else emptyMap()

            result[id] = DbtNode(
                uniqueId = id,
                name = node.path("name").asText(""),
                resourceType = node.path("resource_type").asText(""),
                packageName = node.path("package_name").asText(""),
                originalFilePath = node.path("original_file_path").asText(""),
                database = node.path("database").asText(null),
                schema = node.path("schema").asText(null),
                alias = node.path("alias").asText(null),
                description = node.path("description").asText(""),
                columns = parseColumns(node.get("columns")),
                dependsOnNodes = node.path("depends_on").path("nodes").map { it.asText() },
                dependsOnMacros = node.path("depends_on").path("macros").map { it.asText() },
                tags = node.path("tags").map { it.asText() },
                rawCode = node.path("raw_code").asText(null) ?: node.path("raw_sql").asText(null),
                compiledCode = node.path("compiled_code").asText(null) ?: node.path("compiled_sql").asText(null),
                config = configMap,
                fqn = node.path("fqn").map { it.asText() },
                patchPath = node.path("patch_path").asText(null)
            )
        }
        return result
    }

    private fun parseSources(sourcesNode: JsonNode?): Map<String, DbtSource> {
        if (sourcesNode == null) return emptyMap()
        val result = mutableMapOf<String, DbtSource>()
        val fields = sourcesNode.fields()
        while (fields.hasNext()) {
            val (id, node) = fields.next()
            // Parse freshness
            val freshness = node.get("freshness")
            val warnAfter = freshness?.get("warn_after")?.let { fa ->
                val count = fa.path("count").asInt(0)
                val period = fa.path("period").asText(null)
                if (count > 0 && period != null) "$count $period" else null
            }
            val errorAfter = freshness?.get("error_after")?.let { fa ->
                val count = fa.path("count").asInt(0)
                val period = fa.path("period").asText(null)
                if (count > 0 && period != null) "$count $period" else null
            }

            result[id] = DbtSource(
                uniqueId = id,
                name = node.path("name").asText(""),
                sourceName = node.path("source_name").asText(""),
                packageName = node.path("package_name").asText(""),
                originalFilePath = node.path("original_file_path").asText(""),
                database = node.path("database").asText(null),
                schema = node.path("schema").asText(null),
                identifier = node.path("identifier").asText(null),
                description = node.path("description").asText(""),
                columns = parseColumns(node.get("columns")),
                tags = node.path("tags").map { it.asText() },
                loader = node.path("loader").asText(null),
                sourceDescription = node.path("source_description").asText(null),
                freshnessWarnAfter = warnAfter,
                freshnessErrorAfter = errorAfter,
                loadedAtField = node.path("loaded_at_field").asText(null),
                externalRelationName = node.path("relation_name").asText(null)
            )
        }
        return result
    }

    private fun parseMacros(macrosNode: JsonNode?): Map<String, DbtMacro> {
        if (macrosNode == null) return emptyMap()
        val result = mutableMapOf<String, DbtMacro>()
        val fields = macrosNode.fields()
        while (fields.hasNext()) {
            val (id, node) = fields.next()
            result[id] = DbtMacro(
                uniqueId = id,
                name = node.path("name").asText(""),
                packageName = node.path("package_name").asText(""),
                originalFilePath = node.path("original_file_path").asText(""),
                description = node.path("description").asText(""),
                arguments = node.path("arguments").map { arg ->
                    MacroArgument(
                        name = arg.path("name").asText(""),
                        type = arg.path("type").asText(null),
                        description = arg.path("description").asText("")
                    )
                },
                dependsOnMacros = node.path("depends_on").path("macros").map { it.asText() }
            )
        }
        return result
    }

    private fun parseExposures(exposuresNode: JsonNode?): Map<String, DbtExposure> {
        if (exposuresNode == null) return emptyMap()
        val result = mutableMapOf<String, DbtExposure>()
        val fields = exposuresNode.fields()
        while (fields.hasNext()) {
            val (id, node) = fields.next()
            result[id] = DbtExposure(
                uniqueId = id,
                name = node.path("name").asText(""),
                type = node.path("type").asText(""),
                packageName = node.path("package_name").asText(""),
                originalFilePath = node.path("original_file_path").asText(""),
                description = node.path("description").asText(""),
                owner = node.get("owner")?.let { ownerNode ->
                    ExposureOwner(
                        name = ownerNode.path("name").asText(null),
                        email = ownerNode.path("email").asText(null)
                    )
                },
                dependsOnNodes = node.path("depends_on").path("nodes").map { it.asText() },
                dependsOnMacros = node.path("depends_on").path("macros").map { it.asText() },
                tags = node.path("tags").map { it.asText() },
                url = node.path("url").asText(null)
            )
        }
        return result
    }

    private fun parseColumns(columnsNode: JsonNode?): Map<String, DbtColumn> {
        if (columnsNode == null) return emptyMap()
        val result = mutableMapOf<String, DbtColumn>()
        val fields = columnsNode.fields()
        while (fields.hasNext()) {
            val (name, node) = fields.next()
            result[name] = DbtColumn(
                name = name,
                description = node.path("description").asText(""),
                dataType = node.path("data_type").asText(null),
                tags = node.path("tags").map { it.asText() }
            )
        }
        return result
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): ManifestService =
            project.service<ManifestService>()
    }
}
