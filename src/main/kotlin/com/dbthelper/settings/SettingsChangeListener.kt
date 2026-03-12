package com.dbthelper.settings

import com.intellij.util.messages.Topic

interface SettingsChangeListener {
    companion object {
        val TOPIC = Topic.create("dbt settings changed", SettingsChangeListener::class.java)
    }

    fun onSettingsChanged()
}
