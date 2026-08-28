package com.dbthelper.actions

import com.dbthelper.settings.DbtHelperSettings
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless verification of the project-local dbt trust flow (PR: confirm before running a
 * project-local dbt). Uses TestDialogManager to answer the confirmation dialog without a UI.
 */
class DbtExecutableTrustTest : BasePlatformTestCase() {

    private lateinit var projectDir: File
    private lateinit var projectDbt: File

    private fun norm(s: String) = s.replace('\\', '/')

    override fun setUp() {
        super.setUp()
        projectDir = FileUtil.createTempDirectory("dbtHelperTrust", null)
        File(projectDir, "dbt_project.yml").writeText("name: demo\nprofile: demo\n")
        val venvBin = File(projectDir, ".venv/bin").apply { mkdirs() }
        projectDbt = File(venvBin, "dbt").apply {
            writeText("#!/bin/sh\necho fake dbt\n")
            setExecutable(true)
        }
        // Make the temp project visible to the VFS (findProjectRoot uses findFileByPath).
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectDir)?.refresh(false, true)

        val settings = DbtHelperSettings.getInstance(project)
        settings.state.dbtExecutablePath = "dbt"                 // default -> auto-detection runs
        settings.state.dbtProjectRootOverride = projectDir.path  // locator resolves to our temp project
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            FileUtil.delete(projectDir)
        } finally {
            super.tearDown()
        }
    }

    fun testTrustRunsProjectLocalAndDoesNotPromptAgain() {
        val settings = DbtHelperSettings.getInstance(project)
        settings.state.projectDbtTrust = "ask"
        val prompts = AtomicInteger(0)
        TestDialogManager.setTestDialog(TestDialog { prompts.incrementAndGet(); Messages.YES })

        val first = DbtCommandRunner(project).findDbtExecutable()
        assertEquals("uses the project-local dbt after trusting", norm(projectDbt.path), norm(first))
        assertEquals("choice remembered", "trusted", settings.state.projectDbtTrust)

        val second = DbtCommandRunner(project).findDbtExecutable()
        assertEquals(norm(projectDbt.path), norm(second))
        assertEquals("prompted exactly once across two calls", 1, prompts.get())
    }

    fun testDeclineFallsBackToPathAndDoesNotPromptAgain() {
        val settings = DbtHelperSettings.getInstance(project)
        settings.state.projectDbtTrust = "ask"
        val prompts = AtomicInteger(0)
        TestDialogManager.setTestDialog(TestDialog { prompts.incrementAndGet(); Messages.NO })

        val first = DbtCommandRunner(project).findDbtExecutable()
        assertFalse("does NOT run the project-local dbt after declining", norm(projectDbt.path) == norm(first))
        assertEquals("choice remembered", "declined", settings.state.projectDbtTrust)

        DbtCommandRunner(project).findDbtExecutable()
        assertEquals("prompted exactly once across two calls", 1, prompts.get())
    }

    fun testPreexistingTrustedNeverPrompts() {
        val settings = DbtHelperSettings.getInstance(project)
        settings.state.projectDbtTrust = "trusted"
        TestDialogManager.setTestDialog(TestDialog { fail("must not prompt when already trusted"); Messages.NO })

        val chosen = DbtCommandRunner(project).findDbtExecutable()
        assertEquals(norm(projectDbt.path), norm(chosen))
    }
}
