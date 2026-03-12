package com.dbthelper.core.model

data class DbtMacro(
    val uniqueId: String,
    val name: String,
    val packageName: String,
    val originalFilePath: String,
    val description: String = "",
    val arguments: List<MacroArgument> = emptyList(),
    val dependsOnMacros: List<String> = emptyList()
)

data class MacroArgument(
    val name: String,
    val type: String? = null,
    val description: String = ""
)
