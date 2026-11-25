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
        var phase = "CHECKPOINT"
        try {
            verifyRestart { phase = it }
        } catch (failure: AssertionError) {
            throw AssertionError("U06_RESTART_FAILURE phase=$phase kind=AssertionError")
        } catch (failure: Exception) {
            val kind = when (failure) {
                is PreparationException -> "PreparationException"
                is ReadOnlyRecordException -> "ReadOnlyRecordException"
                is IllegalStateException -> "IllegalStateException"
                is IllegalArgumentException -> "IllegalArgumentException"
                is java.io.IOException -> "IOException"
                is SecurityException -> "SecurityException"
                else -> "OtherException"
            }
            throw AssertionError("U06_RESTART_FAILURE phase=$phase kind=$kind")
        }
    }

    @Test
    fun combinedRepeatDraftRecoversAfterForceStop() {
        var phase = "CHECKPOINT"
        try {
            verifyCombinedRepeatRestart { phase = it }
        } catch (failure: AssertionError) {
            throw AssertionError("U06_RESTART_FAILURE phase=$phase kind=AssertionError")
        } catch (failure: Exception) {
            val kind = when (failure) {
                is PreparationException -> "PreparationException"
                is ReadOnlyRecordException -> "ReadOnlyRecordException"
                is IllegalStateException -> "IllegalStateException"
                is IllegalArgumentException -> "IllegalArgumentException"
                is java.io.IOException -> "IOException"
                is SecurityException -> "SecurityException"
                else -> "OtherException"
            }
            throw AssertionError("U06_RESTART_FAILURE phase=$phase kind=$kind")
        }
    }

    private fun verifyCombinedRepeatRestart(phase: (String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.noBackupFilesDir, "u06-combined-repeat-restart.properties")
        assertTrue(marker.isFile)
        val evidence = Properties().apply { marker.inputStream().use { load(it) } }
        fun required(key: String) = requireNotNull(evidence.getProperty(key))
        val project = required("project")
        val work = required("work")
        val survivor = required("survivor")
        val archived = required("archived")
        val blank = required("blank")
        assertEquals(3, setOf(survivor, archived, blank).size)
        assertNotEquals(required("pid"), android.os.Process.myPid().toString())

        phase("PERSISTED_PROJECT")
        val component = DaggerUtils.getComponent(context)
        val settings = component.settingsProvider()
        assertEquals(project, settings.getMetaSettings().getString(
            org.odk.collect.settings.keys.MetaKeys.CURRENT_PROJECT_ID))
        assertEquals(project, component.projectsRepository().get(project)?.uuid)
        phase("PROJECT_RESTORE")
        ActivityScenario.launch(
            org.odk.collect.android.mainmenu.MainMenuActivity::class.java
        ).use { menu ->
            menu.onActivity {
                assertEquals(project, component.currentProjectProvider().getCurrentProject().value?.uuid)
            }
        }
        phase("ACTOR_SCOPE")
        val values = settings.getUnprotectedSettings(project)
        val scope = QuarantinedWorkDraftVault.sha256(
            project + "\n" + RecordWorkApiPaths.fromProjectUrl(
                values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
            ).records() + "\n" + values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        )
        phase("BINDING")
        val vault = QuarantinedWorkDraftVault(context.noBackupFilesDir)
        val binding = requireNotNull(vault.read(project, work, scope))
        assertEquals(required("reserved"), binding.reservedInstanceId)
        assertEquals("81000000-0000-4000-8000-000000000007", binding.sourceSubmissionId)
        assertEquals("81000000-0000-4000-8000-000000000020", binding.targetFormVersionId)
        assertEquals(RecordWorkType.FOLLOW_UP, binding.workType)
        assertEquals(0, binding.validationTransfers)
        assertFalse(binding.canOpenEditor)
        assertFalse(binding.canFinalize)
        assertFalse(binding.canUpload)
        phase("CIPHERTEXT")
        val dir = File(File(File(context.noBackupFilesDir,
            QuarantinedWorkDraftVault.VAULT_DIRECTORY), project), work)
        fun snapshot() = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to digest(it.readBytes())
        }
        val expected = evidence.stringPropertyNames().filter { it.startsWith("file.") }
            .associate { it.removePrefix("file.") to required(it) }
        assertTrue(expected.keys.count { it.startsWith("repeat-answer-") } == 2)
        assertTrue(expected.keys.count { it.startsWith("repeat-image-") } == 2)
        assertEquals(expected, snapshot())

        phase("LIVE_READY")
        val client = RecordWorkReadOnlyClient(settings)
        val pending = client.load(project).pendingWork
        assertTrue(pending.any { it.id == work })
        assertEquals(required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
            pending.map { it.id }.sorted().joinToString("\n")))
        val authorized = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(project, binding)
        val session = JavaRosaPrivateRepeatAnswerSession(
            context.cacheDir, binding, authorized.plan, authorized.targetXFormXml)
        val group = session.schema.groups.single()
        val path = group.imagePaths.single()
        val answerPath = group.fields.single().path
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val rows = QuarantinedRepeatRowVault(context.noBackupFilesDir, cipher)
        val answers = QuarantinedRepeatAnswerStore(context.noBackupFilesDir, cipher, rows)
        val images = QuarantinedRepeatImageStore(context.noBackupFilesDir, cipher, rows)
        phase("EDITOR_REOPEN")
        val inventory = rows.read(binding, session.schema)
        assertEquals(listOf(survivor, blank), inventory.active(group.path).map { it.rowId })
        assertTrue(inventory.rows.single { it.rowId == archived }.isArchived)
        assertTrue(runCatching { answers.read(binding, session, archived) }.isFailure)
        assertTrue(runCatching { images.receipt(binding, session, archived, path) }.isFailure)
        assertFalse(answers.read(binding, session, blank).hasSavedAnswers)
        assertNull(images.receipt(binding, session, blank, path))
        val recovered = ProtectedNativeRepeatImageProbe(
            context.cacheDir, context.noBackupFilesDir, settings).inspectCombined(project, binding)
        assertEquals(mapOf((survivor to path) to 0), recovered.imageOrdinals)
        assertEquals(mapOf(survivor to mapOf(answerPath to required("answerDigest"))),
            recovered.savedRowAnswerDigests)
        val recoveredAnswer = answers.read(binding, session, survivor)
        assertTrue(recoveredAnswer.hasSavedAnswers)
        assertEquals(required("answerDigest"), QuarantinedWorkDraftVault.sha256(
            requireNotNull(recoveredAnswer.values[answerPath])))
        assertEquals(true, images.withVerifiedImage(binding, session, survivor, path) { receipt, bytes ->
            receipt.sha256 == required("imageDigest") && digest(bytes) == required("imageDigest")
        })

        phase("POSTCONDITIONS")
        assertEquals(expected, snapshot())
        assertEquals(binding, vault.read(project, work, scope))
        assertEquals(pending.map { it.id }.toSet(), client.load(project).pendingWork.map { it.id }.toSet())
        assertFalse(context.cacheDir.listFiles().orEmpty().any {
            it.name.startsWith("recordwork-repeat-answers-") ||
                it.name.startsWith("recordwork-repeat-")
        })
        assertTrue(marker.delete())
    }

    private data class PublicationContext(
        val context: android.content.Context,
        val settings: org.odk.collect.settings.SettingsProvider,
        val binding: QuarantinedWorkDraft,
        val marker: File,
        val evidence: Properties,
        val area: File
    ) {
        fun required(key: String) = requireNotNull(evidence.getProperty(key))
    }

    private fun publicationContext(): PublicationContext {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.noBackupFilesDir, "u06-publication-restart.properties")
        require(marker.isFile)
        val evidence = Properties().apply { marker.inputStream().use { load(it) } }
        val project = requireNotNull(evidence.getProperty("project"))
        val work = requireNotNull(evidence.getProperty("work"))
        val component = DaggerUtils.getComponent(context)
        val settings = component.settingsProvider()
        assertEquals(project, settings.getMetaSettings().getString(
            org.odk.collect.settings.keys.MetaKeys.CURRENT_PROJECT_ID))
        assertEquals(project, component.projectsRepository().get(project)?.uuid)
        ActivityScenario.launch(
            org.odk.collect.android.mainmenu.MainMenuActivity::class.java
        ).use { menu ->
            menu.onActivity {
                assertEquals(project, component.currentProjectProvider().getCurrentProject().value?.uuid)
            }
        }
        val values = settings.getUnprotectedSettings(project)
        val scope = QuarantinedWorkDraftVault.sha256(
            project + "\n" + RecordWorkApiPaths.fromProjectUrl(
                values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
            ).records() + "\n" + values.getString(ProjectKeys.KEY_USERNAME).orEmpty())
        val binding = requireNotNull(QuarantinedWorkDraftVault(context.noBackupFilesDir)
            .read(project, work, scope))
        assertEquals(evidence.getProperty("reserved"), binding.reservedInstanceId)
        assertEquals("81000000-0000-4000-8000-000000000021", binding.targetFormVersionId)
        val area = File(File(File(context.noBackupFilesDir, "recordwork-committed"), project), work)
        return PublicationContext(context, settings, binding, marker, evidence, area)
    }

    @Test
    fun terminateProcessBeforeCombinedPublication() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val normal = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val previous = requireNotNull(normal.reopen(b.projectId, b))
        assertEquals(state.required("generation"), previous.generation)
        val probe = ProtectedNativeRepeatImageProbe(state.context.cacheDir, root, state.settings)
        val changed = "Synthetic interrupted edit " + java.util.UUID.randomUUID()
        probe.saveDirectText(b.projectId, b, mapOf("/data/household_name" to changed))
        val working = probe.inspectCombined(b.projectId, b, true)
        assertNotEquals(previous.evidence, working)
        val interrupted = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings) {
            val candidates = state.area.listFiles().orEmpty().filter {
                it.name.startsWith(".candidate-") && it.name.endsWith(".aesgcm")
            }
            assertEquals(1, candidates.size)
            val id = candidates.single().name.removePrefix(".candidate-").removeSuffix(".aesgcm")
            assertTrue(File(state.area, id).isDirectory)
            assertEquals(state.required("pointerDigest"),
                digest(File(state.area, "current.aesgcm").readBytes()))
            state.evidence.setProperty("candidate", id)
            state.evidence.setProperty("killedPid", android.os.Process.myPid().toString())
            state.evidence.setProperty("newAnswerDigest", QuarantinedWorkDraftVault.sha256(changed))
            java.io.FileOutputStream(state.marker).use {
                state.evidence.store(it, "Synthetic publication interrupted at validated candidate")
                it.fd.sync()
            }
            // Do not throw an exception: finally blocks must not execute.
            android.os.Process.killProcess(android.os.Process.myPid())
            Thread.sleep(30_000)
            throw AssertionError("Process termination did not occur")
        }
        interrupted.commit(b.projectId, b, previous.generation)
        throw AssertionError("Interrupted commit must never return")
    }

    @Test
    fun recoverCommittedDraftAndAbandonedCandidateAfterProcessDeath() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val id = state.required("candidate")
        assertNotEquals(state.required("killedPid"), android.os.Process.myPid().toString())
        val orphan = File(state.area, id)
        val orphanMarker = File(state.area, ".candidate-" + id + ".aesgcm")
        assertTrue(orphan.isDirectory && orphanMarker.isFile)
        val pointer = File(state.area, "current.aesgcm")
        assertEquals(state.required("pointerDigest"), digest(pointer.readBytes()))
        fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to digest(it.readBytes())
        }
        val committedDir = File(state.area, state.required("generation"))
        val historicalDir = File(state.area, state.required("historical"))
        val before = snapshot(committedDir)
        val historical = snapshot(historicalDir)
        assertTrue(before.isNotEmpty() && historical.isNotEmpty())
        val workingDir = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
            b.projectId), b.workId)
        val workingBefore = snapshot(workingDir)
        val normal = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val recovered = requireNotNull(normal.reopen(b.projectId, b))
        assertEquals(state.required("generation"), recovered.generation)
        assertEquals(mapOf("/data/household_name" to state.required("oldAnswerDigest")),
            recovered.evidence.directAnswerDigests)
        assertEquals(mapOf(state.required("survivor") to
            mapOf("/data/members/name" to state.required("memberDigest"))),
            recovered.evidence.savedRowAnswerDigests)
        assertEquals(mapOf((state.required("survivor") to "/data/members/photo") to 0),
            recovered.evidence.imageOrdinals)
        assertTrue(recovered.evidence.directImageDigests.isEmpty())
        assertFalse(orphan.exists() || orphanMarker.exists())
        assertEquals(before, snapshot(committedDir))
        assertEquals(historical, snapshot(historicalDir))
        assertEquals(workingBefore, snapshot(workingDir))
        assertEquals(state.required("pointerDigest"), digest(pointer.readBytes()))
        val probe = ProtectedNativeRepeatImageProbe(state.context.cacheDir, root, state.settings)
        val working = probe.inspectCombined(b.projectId, b, true)
        assertEquals(mapOf("/data/household_name" to state.required("newAnswerDigest")),
            working.directAnswerDigests)
        val retry = normal.commit(b.projectId, b, recovered.generation)
        assertEquals(working, retry.evidence)
        assertNotEquals(id, retry.generation)
        assertEquals(retry, normal.reopen(b.projectId, b))
        assertEquals(workingBefore, snapshot(workingDir))
        assertEquals(before, snapshot(committedDir))
        assertEquals(historical, snapshot(historicalDir))
        assertFalse(b.canOpenEditor || b.canFinalize || b.canUpload)
        val pending = RecordWorkReadOnlyClient(state.settings).load(b.projectId).pendingWork
        assertTrue(pending.any { it.id == b.workId })
        assertEquals(state.required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
            pending.map { it.id }.sorted().joinToString("\n")))
        assertTrue(state.marker.delete())
    }

    private fun verifyRestart(phase: (String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = File(context.noBackupFilesDir, "u06-editor-restart-checkpoint.properties")
        assertTrue("UI save checkpoint required", marker.isFile)
        val evidence = Properties().apply { marker.inputStream().use { load(it) } }
        val project = requireNotNull(evidence.getProperty("project"))
        val work = requireNotNull(evidence.getProperty("work"))
        assertNotEquals("A distinct Android process is required",
            evidence.getProperty("pid"), android.os.Process.myPid().toString())

        phase("PROJECT_RESTORE")
        val component = DaggerUtils.getComponent(context)
        val settings = component.settingsProvider()
        // Before launching any UI, require the existing durable selection
        // and project record. Never write settings or recreate the project.
        phase("PERSISTED_PROJECT")
        assertTrue("Original project selection must be persisted",
            settings.getMetaSettings().getString(
                org.odk.collect.settings.keys.MetaKeys.CURRENT_PROJECT_ID) == project)
        assertTrue("Original project record must be persisted",
            component.projectsRepository().get(project)?.uuid == project)

        // The process-local StateFlow initially has a null default.
        // ODK's ordinary MainMenuActivity constructs CurrentProjectViewModel,
        // which invokes ProjectsDataService.update() and restores selection.
        // Launch that real startup path rather than calling setCurrentProject
        // or waiting on an uninitialized StateFlow.
        phase("PROJECT_RESTORE")
        ActivityScenario.launch(
            org.odk.collect.android.mainmenu.MainMenuActivity::class.java
        ).use { mainMenu ->
            var restored = false
            mainMenu.onActivity {
                restored = component.currentProjectProvider().getCurrentProject().value?.uuid == project
            }
            assertTrue("Normal app startup must restore the original configured project", restored)
        }
        phase("ACTOR_SCOPE")
        val values = settings.getUnprotectedSettings(project)
        val url = values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        val scope = QuarantinedWorkDraftVault.sha256(
            project + "\n" + RecordWorkApiPaths.fromProjectUrl(url).records() + "\n" + username
        )
        phase("BINDING")
        val vault = QuarantinedWorkDraftVault(context.noBackupFilesDir)
        val binding = requireNotNull(vault.read(project, work, scope))
        assertEquals(work, binding.workId)
        assertEquals(evidence.getProperty("reserved"), binding.reservedInstanceId)
        assertEquals(RecordWorkType.FOLLOW_UP, binding.workType)
        assertEquals("81000000-0000-4000-8000-000000000007", binding.sourceSubmissionId)
        assertEquals("81000000-0000-4000-8000-000000000003", binding.targetFormVersionId)
        assertFalse(binding.canFinalize)
        assertFalse(binding.canUpload)
        phase("CIPHERTEXT")
        val file = File(File(File(File(context.noBackupFilesDir,
            QuarantinedWorkDraftVault.VAULT_DIRECTORY), project), work),
            "private-answers.aesgcm")
        val before = file.readBytes()
        assertEquals(evidence.getProperty("cipherDigest"), digest(before))
        phase("LIVE_READY")
        val pendingBefore = RecordWorkReadOnlyClient(settings).load(project).pendingWork
        assertTrue("Backend work must still be READY", pendingBefore.any { it.id == work })
        phase("EDITOR_REOPEN")
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
        phase("POSTCONDITIONS")
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
