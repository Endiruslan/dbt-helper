package com.dbthelper.core.model

data class DbtExposure(
    val uniqueId: String,
    val name: String,
    val type: String,
    val packageName: String,
    val originalFilePath: String,
    val description: String = "",
    val owner: ExposureOwner? = null,
    val dependsOnNodes: List<String> = emptyList(),
    val dependsOnMacros: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val url: String? = null
)

data class ExposureOwner(
    val name: String? = null,
    val email: String? = null
)
