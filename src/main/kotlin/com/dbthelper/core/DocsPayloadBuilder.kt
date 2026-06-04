package com.dbthelper.core

import com.dbthelper.core.model.DbtNode
import com.dbthelper.core.model.DbtSource
import com.dbthelper.core.model.ManifestIndex
import com.dbthelper.core.model.NodeSql

object DocsPayloadBuilder {

    fun build(nodeId: String, index: ManifestIndex, sql: NodeSql = NodeSql.EMPTY): Map<String, Any?>? {
        val node = index.nodes[nodeId]
        if (node != null) return buildNode(node, index, sql)
        val source = index.sources[nodeId]
        if (source != null) return buildSource(source, index)
        return null
    }

    private fun buildNode(node: DbtNode, index: ManifestIndex, sql: NodeSql): Map<String, Any?> {
        val mat = (node.config["materialized"] as? String)?.lowercase()
        val incremental = mat == "incremental"
        val tests = findTestsForNode(node.uniqueId, index)
        val pkCols = primaryKeyColumns(node.uniqueId, index)
        val fkRefs = foreignKeyRefs(node.uniqueId, index)

        return mapOf(
            "id" to node.uniqueId,
            "kind" to node.resourceType,
            "name" to node.name,
            "schema" to schemaDisplay(node),
            "materialization" to (mat ?: ""),
            "incremental" to incremental,
            "tests" to mapOf(
                "total" to tests.size,
                "list" to tests.map { mapOf(
                    "name" to it.name,
                    "shortName" to shortTestName(it.name),
                    "column" to (it.config["column_name"] as? String ?: "")
                ) }
            ),
            "columns" to node.columns.values.map { col ->
                mapOf(
                    "name" to col.name,
                    "type" to (col.dataType ?: ""),
                    "description" to col.description,
                    "isPrimaryKey" to (col.name in pkCols),
                    "fk" to fkRefs[col.name]
                )
            },
            "sql" to mapOf(
                "raw" to (sql.raw ?: ""),
                "compiled" to (sql.compiled ?: "")
            ),
            "metadata" to mapOf(
                "filePath" to node.originalFilePath,
                "patchPath" to node.patchPath,
                "fqn" to node.fqn,
                "tags" to node.tags,
                "packageName" to node.packageName,
                "database" to node.database,
                "fullName" to listOfNotNull(node.database, node.schema, node.alias ?: node.name).joinToString("."),
                "dependsOn" to node.dependsOnNodes.mapNotNull { friendlyName(it, index) },
                "referencedBy" to index.getDownstream(node.uniqueId).mapNotNull { friendlyName(it, index) },
                "config" to filteredConfig(node.config),
                "description" to node.description
            )
        )
    }

    private fun buildSource(source: DbtSource, index: ManifestIndex): Map<String, Any?> {
        return mapOf(
            "id" to source.uniqueId,
            "kind" to "source",
            "name" to "${source.sourceName}.${source.name}",
            "schema" to (source.schema ?: source.sourceName),
            "materialization" to "source",
            "incremental" to false,
            "tests" to mapOf("total" to 0, "list" to emptyList<Any>()),
            "columns" to source.columns.values.map { col ->
                mapOf(
                    "name" to col.name,
                    "type" to (col.dataType ?: ""),
                    "description" to col.description,
                    "isPrimaryKey" to false,
                    "fk" to null
                )
            },
            "sql" to mapOf("raw" to "", "compiled" to ""),
            "metadata" to mapOf(
                "filePath" to source.originalFilePath,
                "patchPath" to null,
                "fqn" to emptyList<String>(),
                "tags" to source.tags,
                "packageName" to source.packageName,
                "database" to source.database,
                "fullName" to listOfNotNull(source.database, source.schema, source.identifier ?: source.name).joinToString("."),
                "dependsOn" to emptyList<String>(),
                "referencedBy" to index.getDownstream(source.uniqueId).mapNotNull { friendlyName(it, index) },
                "config" to emptyMap<String, String>(),
                "description" to source.description,
                "loader" to source.loader,
                "loadedAtField" to source.loadedAtField,
                "freshnessWarnAfter" to source.freshnessWarnAfter,
                "freshnessErrorAfter" to source.freshnessErrorAfter
            )
        )
    }

    private fun schemaDisplay(node: DbtNode): String {
        val sch = node.schema
        return if (!sch.isNullOrEmpty()) sch else node.fqn.dropLast(1).joinToString(".")
    }

    private fun findTestsForNode(nodeId: String, index: ManifestIndex): List<DbtNode> {
        return index.nodes.values.filter {
            it.resourceType == "test" && nodeId in it.dependsOnNodes
        }
    }

    private fun primaryKeyColumns(nodeId: String, index: ManifestIndex): Set<String> {
        // Heuristic: column has both unique and not_null tests
        val tests = findTestsForNode(nodeId, index)
        val byColumn = mutableMapOf<String, MutableSet<String>>()
        for (t in tests) {
            val col = t.config["column_name"] as? String ?: continue
            val kind = when {
                t.name.startsWith("unique") -> "unique"
                t.name.startsWith("not_null") -> "not_null"
                else -> continue
            }
            byColumn.getOrPut(col) { mutableSetOf() }.add(kind)
        }
        return byColumn.filterValues { it.containsAll(listOf("unique", "not_null")) }.keys
    }

    private fun foreignKeyRefs(nodeId: String, index: ManifestIndex): Map<String, String> {
        // From relationships tests, extract column → referenced model.column
        val tests = findTestsForNode(nodeId, index).filter { it.name.startsWith("relationships") }
        val map = mutableMapOf<String, String>()
        for (t in tests) {
            val col = t.config["column_name"] as? String ?: continue
            val toField = t.config["field"] as? String
            // The referenced model is in dependsOnNodes (the other one besides this node)
            val refId = t.dependsOnNodes.firstOrNull { it != nodeId } ?: continue
            val refName = friendlyName(refId, index) ?: continue
            map[col] = if (toField != null) "$refName.$toField" else refName
        }
        return map
    }

    private fun shortTestName(name: String): String {
        val m = Regex("^(unique|not_null|accepted_values|relationships|dbt_utils_\\w+|dbt_expectations_\\w+)").find(name)
        return m?.value ?: name
    }

    private fun filteredConfig(config: Map<String, Any?>): Map<String, String> {
        val skip = setOf("enabled", "quoting", "column_types", "persist_docs", "full_refresh")
        val out = LinkedHashMap<String, String>()
        for ((k, v) in config) {
            if (k in skip || v == null) continue
            val s: String = when (v) {
                is String -> if (v.isEmpty()) continue else v
                is Boolean, is Number -> v.toString()
                else -> {
                    val str = v.toString()
                    if (str == "{}" || str == "[]" || str == "null") continue else str
                }
            }
            out[k] = s
        }
        return out
    }

    private fun friendlyName(uniqueId: String, index: ManifestIndex): String? =
        DbtUtils.friendlyName(uniqueId, index)
}
