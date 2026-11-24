package org.odk.collect.android.recordwork

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.keys.ProjectKeys
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Must run after the actual UI save test and adb am force-stop, WITHOUT
 * ResetStateRule, pm clear, project recreation or APK reinstall. Reopen
 * the exact persisted project and work using the real editor Activity.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedProtectedEditorRestartTest {
    @Test
    fun actualEditorRecoversSavedAnswerAfterForceStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = File(context.noBackupFilesDir, "u06-editor-restart-checkpoint.properties")
        assertTrue("UI save checkpoint required", marker.isFile)
        val evidence = Properties().apply { marker.inputStream().use { load(it) } }
        val project = requireNotNull(evidence.getProperty("project"))
        val work = requireNotNull(evidence.getProperty("work"))
        assertNotEquals("A distinct Android process is required",
            evidence.getProperty("pid"), android.os.Process.myPid().toString())

        val component = DaggerUtils.getComponent(context)
        var current: String? = null
        for (attempt in 1..80) {
            instrumentation.runOnMainSync {
                current = component.currentProjectProvider().getCurrentProject().value?.uuid
            }
            if (current == project) break
            Thread.sleep(250)
        }
        assertEquals("Original configured project must survive restart", project, current)
        val settings = component.settingsProvider()
        val values = settings.getUnprotectedSettings(project)
        val url = values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        val scope = QuarantinedWorkDraftVault.sha256(
            project + "\n" + RecordWorkApiPaths.fromProjectUrl(url).records() + "\n" + username
        )
        val vault = QuarantinedWorkDraftVault(context.noBackupFilesDir)
        val binding = requireNotNull(vault.read(project, work, scope))
        assertEquals(work, binding.workId)
        assertEquals(evidence.getProperty("reserved"), binding.reservedInstanceId)
        assertEquals(RecordWorkType.FOLLOW_UP, binding.workType)
        assertEquals("81000000-0000-4000-8000-000000000007", binding.sourceSubmissionId)
        assertEquals("81000000-0000-4000-8000-000000000003", binding.targetFormVersionId)
        assertFalse(binding.canFinalize)
        assertFalse(binding.canUpload)
        val file = File(File(File(File(context.noBackupFilesDir,
            QuarantinedWorkDraftVault.VAULT_DIRECTORY), project), work),
            "private-answers.aesgcm")
        val before = file.readBytes()
        assertEquals(evidence.getProperty("cipherDigest"), digest(before))
        val pendingBefore = RecordWorkReadOnlyClient(settings).load(project).pendingWork
        assertTrue("Backend work must still be READY", pendingBefore.any { it.id == work })
        ActivityScenario.launch<QuarantinedTextDraftActivity>(
            QuarantinedTextDraftActivity.intentFor(context, project, work)
        ).use { scenario ->
            var ready = false
            for (attempt in 1..120) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { editor ->
                    val widgets = descendants(editor.window.decorView)
                    val save = widgets.filterIsInstance<Button>().singleOrNull {
                        it.text.toString() == editor.getString(R.string.private_text_editor_save)
                    }
                    if (save?.isEnabled == true) {
                        val field = widgets.filterIsInstance<EditText>().single()
                        assertEquals("Recovered answer must match pre-stop digest",
                            evidence.getProperty("answerDigest"),
                            QuarantinedWorkDraftVault.sha256(field.text.toString()))
                        val expected = editor.getString(R.string.private_text_editor_ready,
                            binding.targetVersion, editor.getString(R.string.private_text_editor_recovered))
                        assertTrue("Actual editor must report recovered state",
                            widgets.filterIsInstance<TextView>().any { it.text.toString() == expected })
                        assertTrue(widgets.filterIsInstance<TextView>().any {
                            it.text.toString() == editor.getString(R.string.private_text_editor_never_submit)
                        })
                        ready = true
                    }
                }
                if (ready) break
                Thread.sleep(250)
            }
            assertTrue("Real protected editor must recover after new-process authorization", ready)
        }
        assertArrayEquals("Opening must not rewrite saved ciphertext", before, file.readBytes())
        assertEquals(binding, vault.read(project, work, scope))
        assertEquals(pendingBefore.map { it.id }.toSet(),
            RecordWorkReadOnlyClient(settings).load(project).pendingWork.map { it.id }.toSet())
        assertTrue(marker.delete())
    }

    private fun descendants(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) {
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        } else emptyList()

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
