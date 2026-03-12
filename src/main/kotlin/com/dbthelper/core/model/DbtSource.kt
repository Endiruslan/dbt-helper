package com.dbthelper.core.model

data class DbtSource(
    val uniqueId: String,
    val name: String,
    val sourceName: String,
    val packageName: String,
    val originalFilePath: String,
    val database: String? = null,
    val schema: String? = null,
    val identifier: String? = null,
    val description: String = "",
    val columns: Map<String, DbtColumn> = emptyMap(),
    val tags: List<String> = emptyList(),
    val loader: String? = null,
    val sourceDescription: String? = null,
    val freshnessWarnAfter: String? = null,
    val freshnessErrorAfter: String? = null,
    val loadedAtField: String? = null,
    val externalRelationName: String? = null
) {
    val relationName: String?
        get() {
            val db = database ?: return null
            val sch = schema ?: return null
            val tbl = identifier ?: name
            return "$db.$sch.$tbl".lowercase()
        }
}
