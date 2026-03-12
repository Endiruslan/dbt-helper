package com.dbthelper.core.model

data class DbtNode(
    val uniqueId: String,
    val name: String,
    val resourceType: String,
    val packageName: String,
    val originalFilePath: String,
    val database: String? = null,
    val schema: String? = null,
    val alias: String? = null,
    val description: String = "",
    val columns: Map<String, DbtColumn> = emptyMap(),
    val dependsOnNodes: List<String> = emptyList(),
    val dependsOnMacros: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rawCode: String? = null,
    val compiledCode: String? = null,
    val config: Map<String, Any?> = emptyMap(),
    val fqn: List<String> = emptyList(),
    val patchPath: String? = null
) {
    val relationName: String?
        get() {
            val db = database ?: return null
            val sch = schema ?: return null
            val tbl = alias ?: name
            return "$db.$sch.$tbl".lowercase()
        }
}
