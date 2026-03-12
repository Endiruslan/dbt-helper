package com.dbthelper.settings

import com.dbthelper.core.ProfilesParser
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*

class DbtHelperConfigurable(private val project: Project) : BoundConfigurable("dbt Helper") {

    private val settings get() = DbtHelperSettings.getInstance(project)

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(SettingsChangeListener.TOPIC).onSettingsChanged()
    }

    override fun createPanel() = panel {
        group("Executable") {
            row("dbt executable path:") {
                textField()
                    .bindText(settings.state::dbtExecutablePath)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Path to dbt CLI binary, e.g. /usr/local/bin/dbt or just dbt if it's in PATH")
            }
        }

        group("Project") {
            row("Project root override:") {
                textField()
                    .bindText(settings.state::dbtProjectRootOverride)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Absolute path to dbt project root. Leave empty to auto-detect from dbt_project.yml")
            }
            row("Active target:") {
                val targets = ProfilesParser.getInstance(project).getTargetNames()
                if (targets.isNotEmpty()) {
                    comboBox(targets)
                        .bindItem(settings.state::activeTarget.toNullableProperty())
                        .comment("Target from profiles.yml to use for compilation, e.g. dev, prod")
                } else {
                    textField()
                        .bindText(settings.state::activeTarget)
                        .comment("Target from profiles.yml to use for compilation, e.g. dev, prod")
                }
            }
        }

        group("Lineage Defaults") {
            row("Upstream depth:") {
                spinner(1..20)
                    .bindIntValue(settings.state::upstreamDepth)
                    .comment("How many levels of parent models to show above the current node")
            }
            row("Downstream depth:") {
                spinner(1..20)
                    .bindIntValue(settings.state::downstreamDepth)
                    .comment("How many levels of child models to show below the current node")
            }
            row("Edge style:") {
                val styles = listOf("bezier", "taxi", "round-taxi", "segments", "straight", "unbundled-bezier", "haystack")
                comboBox(styles)
                    .bindItem(settings.state::edgeCurveStyle.toNullableProperty())
                    .comment("Line style for edges. Try taxi or round-taxi for orthogonal connectors")
            }
            row("Layout direction:") {
                val directions = listOf("Left \u2192 Right", "Top \u2192 Bottom", "Right \u2192 Left", "Bottom \u2192 Top")
                comboBox(directions)
                    .bindItem(
                        { when (settings.state.layoutDirection) {
                            "LR" -> "Left \u2192 Right"
                            "TB" -> "Top \u2192 Bottom"
                            "RL" -> "Right \u2192 Left"
                            "BT" -> "Bottom \u2192 Top"
                            else -> "Left \u2192 Right"
                        }},
                        { settings.state.layoutDirection = when (it) {
                            "Top \u2192 Bottom" -> "TB"
                            "Right \u2192 Left" -> "RL"
                            "Bottom \u2192 Top" -> "BT"
                            else -> "LR"
                        }}
                    )
            }
}

        group("Preview") {
            row("Row limit:") {
                spinner(1..1000)
                    .bindIntValue(settings.state::previewRowLimit)
                    .comment("Maximum number of rows returned by dbt show (preview)")
            }
        }

        group("Behavior") {
            row {
                checkBox("Auto-open tool window on SQL file")
                    .bindSelected(settings.state::autoOpenOnSqlFile)
                    .comment("Automatically show the dbt Helper panel when opening a .sql file")
            }
            row {
                checkBox("Show test nodes in lineage")
                    .bindSelected(settings.state::showTestNodes)
                    .comment("Display dbt tests (unique, not_null, etc.) as small nodes attached to models")
            }
            row {
                checkBox("Show exposures in lineage")
                    .bindSelected(settings.state::showExposures)
                    .comment("Display dbt exposures (dashboards, reports) in the lineage graph")
            }
            row {
                checkBox("Send system notifications")
                    .bindSelected(settings.state::enableSystemNotifications)
                    .comment("Send native OS notifications (macOS Notification Center) when dbt commands finish")
            }
        }
    }
}
