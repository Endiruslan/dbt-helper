package com.dbthelper.core.model

data class DbtColumn(
    val name: String,
    val description: String = "",
    val dataType: String? = null,
    val tags: List<String> = emptyList()
)
