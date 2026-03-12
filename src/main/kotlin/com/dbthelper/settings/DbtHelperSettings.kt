package com.dbthelper.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "DbtHelperSettings",
    storages = [Storage("dbt-helper.xml")]
)
class DbtHelperSettings : PersistentStateComponent<DbtHelperSettings.State> {

    data class State(
        var dbtExecutablePath: String = "dbt",
        var dbtProjectRootOverride: String = "",
        var activeTarget: String = "",
        var upstreamDepth: Int = 5,
        var downstreamDepth: Int = 5,
        var autoOpenOnSqlFile: Boolean = true,
        var showTestNodes: Boolean = false,
        var showExposures: Boolean = true,
        var edgeCurveStyle: String = "bezier",
        var layoutDirection: String = "LR",
        var showCompiledCode: Boolean = false,
        var previewRowLimit: Int = 10,
        var enableSystemNotifications: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): DbtHelperSettings =
            project.service<DbtHelperSettings>()
    }
}
