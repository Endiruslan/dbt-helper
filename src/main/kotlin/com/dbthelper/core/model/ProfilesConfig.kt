package com.dbthelper.core.model

data class ProfilesConfig(
    val profileName: String,
    val defaultTarget: String,
    val targets: Map<String, TargetConfig>
)

data class TargetConfig(
    val name: String,
    val type: String,
    val database: String? = null,
    val schema: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val threads: Int? = null
)
