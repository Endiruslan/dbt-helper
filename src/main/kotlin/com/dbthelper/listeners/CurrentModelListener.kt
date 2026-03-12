package com.dbthelper.listeners

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

interface CurrentModelListener {
    companion object {
        val TOPIC = Topic.create("dbt current model changed", CurrentModelListener::class.java)
    }

    fun onCurrentModelChanged(file: VirtualFile)
}
