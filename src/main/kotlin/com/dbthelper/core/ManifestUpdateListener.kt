package com.dbthelper.core

import com.dbthelper.core.model.ManifestIndex
import com.intellij.util.messages.Topic

interface ManifestUpdateListener {
    companion object {
        val TOPIC = Topic.create("dbt manifest updated", ManifestUpdateListener::class.java)
    }

    fun onManifestUpdated(index: ManifestIndex)
}
