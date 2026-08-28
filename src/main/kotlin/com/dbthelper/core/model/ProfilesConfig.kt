package com.dbthelper.core.model

data class ProfilesConfig(
    val profileName: String,
    val defaultTarget: String,
    val targets: Map<String, TargetConfig>
)

// Only the fields the plugin actually uses are kept. The target selector needs names, and the
// status label shows `type`; connection metadata (host/database/schema/port/threads) was read
// but never consumed, so it is deliberately not projected out of profiles.yml.
data class TargetConfig(
    val name: String,
    val type: String
)
