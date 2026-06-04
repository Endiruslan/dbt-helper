package com.dbthelper.core.model

data class ManifestIndex(
    val nodes: Map<String, DbtNode> = emptyMap(),
    val sources: Map<String, DbtSource> = emptyMap(),
    val macros: Map<String, DbtMacro> = emptyMap(),
    val exposures: Map<String, DbtExposure> = emptyMap(),
    val parentMap: Map<String, List<String>> = emptyMap(),
    val childMap: Map<String, List<String>> = emptyMap(),
    val filePathMap: Map<String, String> = emptyMap(),
    val relationMap: Map<String, String> = emptyMap(),
    val resolvableModelNames: Set<String> = emptySet(),
    val resolvableSourceKeys: Set<String> = emptySet()
) {
    companion object {
        val EMPTY = ManifestIndex()

        fun sourceKey(sourceName: String, tableName: String): String = "$sourceName\u0000$tableName"

        fun buildLookups(
            nodes: Map<String, DbtNode>,
            sources: Map<String, DbtSource>
        ): Pair<Set<String>, Set<String>> {
            val modelNames = mutableSetOf<String>()
            for ((_, node) in nodes) {
                if (node.resourceType == "test") continue
                modelNames.add(node.name)
                node.alias?.takeIf { it.isNotEmpty() }?.let { modelNames.add(it) }
            }
            val sourceKeys = sources.values.map { sourceKey(it.sourceName, it.name) }.toSet()
            return modelNames to sourceKeys
        }
    }

    fun isResolvableModel(name: String): Boolean = name in resolvableModelNames

    fun isResolvableSource(sourceName: String, tableName: String): Boolean =
        Companion.sourceKey(sourceName, tableName) in resolvableSourceKeys

    /** First non-test node matching [name] or [alias]. */
    fun findModelByNameOrAlias(name: String): DbtNode? =
        nodes.values.firstOrNull { (it.name == name || it.alias == name) && it.resourceType != "test" }

    fun findSource(sourceName: String, tableName: String): DbtSource? =
        sources.values.firstOrNull { it.sourceName == sourceName && it.name == tableName }

    fun findMacroByName(name: String): DbtMacro? =
        macros.values.firstOrNull { it.name == name }

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
