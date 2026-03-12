package com.dbthelper.core

import com.dbthelper.core.model.DbtColumn
import com.dbthelper.core.model.ManifestIndex
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CatalogParser(private val project: Project) {

    private val logger = Logger.getInstance(CatalogParser::class.java)
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun mergeCatalog(index: ManifestIndex): ManifestIndex {
        val locator = DbtProjectLocator(project)
        val catalogFile = locator.getCatalogFile() ?: return index

        return try {
            val root = catalogFile.inputStream.use { mapper.readTree(it) }
            val catalogNodes = root.get("nodes") ?: return index
            val catalogSources = root.get("sources")

            val updatedNodes = index.nodes.toMutableMap()
            mergeNodeColumns(catalogNodes, updatedNodes)

            val updatedSources = index.sources.toMutableMap()
            if (catalogSources != null) {
                mergeSourceColumns(catalogSources, updatedSources)
            }

            index.copy(nodes = updatedNodes, sources = updatedSources)
        } catch (e: Exception) {
            logger.warn("Failed to parse catalog.json", e)
            index
        }
    }

    private fun mergeNodeColumns(catalogNodes: JsonNode, nodes: MutableMap<String, com.dbthelper.core.model.DbtNode>) {
        val fields = catalogNodes.fields()
        while (fields.hasNext()) {
            val (id, catalogNode) = fields.next()
            val existingNode = nodes[id] ?: continue
            val catalogColumns = catalogNode.path("columns")
            if (catalogColumns.isMissingNode) continue

            val mergedColumns = existingNode.columns.toMutableMap()
            val colFields = catalogColumns.fields()
            while (colFields.hasNext()) {
                val (colName, colNode) = colFields.next()
                val existing = mergedColumns[colName]
                mergedColumns[colName] = DbtColumn(
                    name = colName,
                    description = existing?.description ?: "",
                    dataType = colNode.path("type").asText(null) ?: existing?.dataType,
                    tags = existing?.tags ?: emptyList()
                )
            }
            nodes[id] = existingNode.copy(columns = mergedColumns)
        }
    }

    private fun mergeSourceColumns(catalogSources: JsonNode, sources: MutableMap<String, com.dbthelper.core.model.DbtSource>) {
        val fields = catalogSources.fields()
        while (fields.hasNext()) {
            val (id, catalogNode) = fields.next()
            val existingSource = sources[id] ?: continue
            val catalogColumns = catalogNode.path("columns")
            if (catalogColumns.isMissingNode) continue

            val mergedColumns = existingSource.columns.toMutableMap()
            val colFields = catalogColumns.fields()
            while (colFields.hasNext()) {
                val (colName, colNode) = colFields.next()
                val existing = mergedColumns[colName]
                mergedColumns[colName] = DbtColumn(
                    name = colName,
                    description = existing?.description ?: "",
                    dataType = colNode.path("type").asText(null) ?: existing?.dataType,
                    tags = existing?.tags ?: emptyList()
                )
            }
            sources[id] = existingSource.copy(columns = mergedColumns)
        }
    }
}
