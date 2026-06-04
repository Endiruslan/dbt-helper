package com.dbthelper.core

import com.dbthelper.core.model.DbtColumn
import com.dbthelper.core.model.ManifestIndex
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.PROJECT)
class CatalogParser(private val project: Project) {

    private val logger = Logger.getInstance(CatalogParser::class.java)
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val locator = DbtProjectLocator(project)

    @Volatile
    private var cachedFilePath: String? = null

    @Volatile
    private var cachedModificationStamp: Long = -1

    @Volatile
    private var cachedCatalog: ParsedCatalog? = null

    fun mergeCatalog(index: ManifestIndex): ManifestIndex {
        val catalogFile = locator.getCatalogFile() ?: return clearCacheAndReturn(index)

        val catalog = synchronized(this) {
            val path = catalogFile.path
            val stamp = catalogFile.modificationStamp
            val hit = cachedCatalog
            if (hit != null && cachedFilePath == path && cachedModificationStamp == stamp) {
                hit
            } else {
                parseCatalogFile(catalogFile).also { parsed ->
                    cachedFilePath = path
                    cachedModificationStamp = stamp
                    cachedCatalog = parsed
                }
            }
        }

        return applyCatalog(index, catalog)
    }

    fun invalidateCache() {
        synchronized(this) {
            cachedFilePath = null
            cachedModificationStamp = -1
            cachedCatalog = null
        }
    }

    private fun clearCacheAndReturn(index: ManifestIndex): ManifestIndex {
        invalidateCache()
        return index
    }

    private fun parseCatalogFile(catalogFile: VirtualFile): ParsedCatalog {
        return try {
            val root = catalogFile.inputStream.use { mapper.readTree(it) }
            ParsedCatalog(
                nodeColumnTypes = extractColumnTypes(root.get("nodes")),
                sourceColumnTypes = extractColumnTypes(root.get("sources"))
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse catalog.json", e)
            ParsedCatalog.EMPTY
        }
    }

    private fun extractColumnTypes(nodes: JsonNode?): Map<String, Map<String, String?>> {
        if (nodes == null || nodes.isMissingNode) return emptyMap()
        val result = mutableMapOf<String, Map<String, String?>>()
        val fields = nodes.fields()
        while (fields.hasNext()) {
            val (id, catalogNode) = fields.next()
            val catalogColumns = catalogNode.path("columns")
            if (catalogColumns.isMissingNode) continue

            val types = mutableMapOf<String, String?>()
            val colFields = catalogColumns.fields()
            while (colFields.hasNext()) {
                val (colName, colNode) = colFields.next()
                types[colName] = colNode.path("type").asText(null)
            }
            if (types.isNotEmpty()) {
                result[id] = types
            }
        }
        return result
    }

    private fun applyCatalog(index: ManifestIndex, catalog: ParsedCatalog): ManifestIndex {
        if (catalog.nodeColumnTypes.isEmpty() && catalog.sourceColumnTypes.isEmpty()) {
            return index
        }

        val updatedNodes = index.nodes.toMutableMap()
        for ((id, types) in catalog.nodeColumnTypes) {
            val node = updatedNodes[id] ?: continue
            updatedNodes[id] = node.copy(columns = mergeColumns(node.columns, types))
        }

        val updatedSources = index.sources.toMutableMap()
        for ((id, types) in catalog.sourceColumnTypes) {
            val source = updatedSources[id] ?: continue
            updatedSources[id] = source.copy(columns = mergeColumns(source.columns, types))
        }

        return index.copy(nodes = updatedNodes, sources = updatedSources)
    }

    private fun mergeColumns(
        existing: Map<String, DbtColumn>,
        catalogTypes: Map<String, String?>
    ): Map<String, DbtColumn> {
        val merged = existing.toMutableMap()
        for ((colName, catalogType) in catalogTypes) {
            val existingCol = merged[colName]
            merged[colName] = DbtColumn(
                name = colName,
                description = existingCol?.description ?: "",
                dataType = catalogType ?: existingCol?.dataType,
                tags = existingCol?.tags ?: emptyList()
            )
        }
        return merged
    }

    /** Column types from catalog.json, cached between manifest reparses when the file is unchanged. */
    private data class ParsedCatalog(
        val nodeColumnTypes: Map<String, Map<String, String?>>,
        val sourceColumnTypes: Map<String, Map<String, String?>>
    ) {
        companion object {
            val EMPTY = ParsedCatalog(emptyMap(), emptyMap())
        }
    }
}
