package com.dbthelper.core.model

data class ManifestIndex(
    val nodes: Map<String, DbtNode> = emptyMap(),
    val sources: Map<String, DbtSource> = emptyMap(),
    val macros: Map<String, DbtMacro> = emptyMap(),
    val exposures: Map<String, DbtExposure> = emptyMap(),
    val parentMap: Map<String, List<String>> = emptyMap(),
    val childMap: Map<String, List<String>> = emptyMap(),
    val filePathMap: Map<String, String> = emptyMap(),
    val relationMap: Map<String, String> = emptyMap()
) {
    companion object {
        val EMPTY = ManifestIndex()
    }

    val modelCount: Int get() = nodes.count { it.value.resourceType == "model" }
    val sourceCount: Int get() = sources.size

    fun findByFilePath(relativePath: String): String? {
        val normalized = relativePath.replace('\\', '/')
        return filePathMap[normalized]
    }

    fun findByRelation(database: String, schema: String, table: String): String? {
        val key = "$database.$schema.$table".lowercase()
        return relationMap[key]
    }

    fun getUpstream(uniqueId: String): List<String> = parentMap[uniqueId] ?: emptyList()

    fun getDownstream(uniqueId: String): List<String> = childMap[uniqueId] ?: emptyList()
}
