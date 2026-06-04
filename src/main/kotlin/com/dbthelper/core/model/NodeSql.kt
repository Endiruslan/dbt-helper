package com.dbthelper.core.model

/** Raw and compiled SQL for a manifest node, loaded on demand from manifest.json. */
data class NodeSql(
    val raw: String? = null,
    val compiled: String? = null
) {
    val hasAny: Boolean
        get() = !raw.isNullOrEmpty() || !compiled.isNullOrEmpty()

    companion object {
        val EMPTY = NodeSql()
    }
}
