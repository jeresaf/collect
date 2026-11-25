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
        // Preserve a digest-only oracle for the next real UI phase.
        state.evidence.setProperty("generation", retry.generation)
        state.evidence.setProperty("oldAnswerDigest", state.required("newAnswerDigest"))
        java.io.FileOutputStream(state.marker).use {
            state.evidence.store(it, "Synthetic graphical qualification input")
            it.fd.sync()
        }
    }

    private fun <A : android.app.Activity> awaitUi(
        scenario: ActivityScenario<A>, check: (A, List<View>) -> Boolean
    ) {
        for (attempt in 1..120) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var ready = false
            scenario.onActivity { activity -> ready = check(activity, descendants(activity.window.decorView)) }
            if (ready) return
            Thread.sleep(250)
        }
        throw AssertionError("Protected graphical qualification timed out")
    }

    private fun <A : android.app.Activity> clickButton(scenario: ActivityScenario<A>, label: Int) {
        awaitUi(scenario) { activity, views ->
            val button = views.filterIsInstance<Button>().singleOrNull {
                it.text.toString() == activity.getString(label) && it.isEnabled
            }
            button?.performClick() == true
        }
    }

    @Test
    fun mixedChildEditorsSaveAndPublishGraphically() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val normal = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val previous = requireNotNull(normal.reopen(b.projectId, b))
        assertEquals(state.required("generation"), previous.generation)
        val authorized = RecordWorkPreparationClient(state.settings).authorizePrivateTextForm(b.projectId, b)
        val session = JavaRosaPrivateRepeatAnswerSession(state.context.cacheDir, b,
            authorized.plan, authorized.targetXFormXml, true)
        val rows = QuarantinedRepeatRowVault(root, AndroidKeyStorePrivateDraftCipher())
        val group = session.schema.groups.single()
        val survivor = state.required("survivor")
        val rowBefore = rows.read(b, session.schema)
        assertEquals(listOf(survivor), rowBefore.active(group.path).map { it.rowId })

        // Real row inventory Activity and Add control, not a vault mutation.
        ActivityScenario.launch<QuarantinedRepeatRowsActivity>(
            QuarantinedRepeatRowsActivity.intentFor(state.context, b.projectId, b.workId, true)
        ).use { editor ->
            clickButton(editor, R.string.private_repeat_add)
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<Button>().count {
                    it.text.toString() == activity.getString(R.string.private_repeat_edit_answers)
                } == 2
            }
        }
        val rowAfter = rows.read(b, session.schema)
        val added = rowAfter.active(group.path).single { it.rowId != survivor }.rowId
        val answer = "Synthetic graphical repeat " + java.util.UUID.randomUUID()
        ActivityScenario.launch<QuarantinedRepeatAnswerActivity>(
            QuarantinedRepeatAnswerActivity.intentFor(state.context, b.projectId, b.workId, added, true)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                val save = views.filterIsInstance<Button>().singleOrNull {
                    it.text.toString() == activity.getString(R.string.private_repeat_answer_save) && it.isEnabled
                }
                if (save == null) false else {
                    val input = views.filterIsInstance<EditText>().single()
                    assertTrue(input.text.isEmpty())
                    input.setText(answer)
                    save.performClick()
                }
            }
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.private_repeat_answer_saved)
                }
            }
        }
        val answerDigest = QuarantinedWorkDraftVault.sha256(answer)
        val workingText = "Synthetic graphical household " + java.util.UUID.randomUUID()
        val householdDigest = QuarantinedWorkDraftVault.sha256(workingText)

        // Actual repeat image screen authenticates existing asset and clears it.
        ActivityScenario.launch<QuarantinedRepeatImageActivity>(
            QuarantinedRepeatImageActivity.intentFor(state.context, b.projectId, b.workId, survivor, true)
        ).use { editor ->
            clickButton(editor, R.string.private_repeat_image_clear)
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<Button>().any {
                    it.text.toString() == activity.getString(R.string.private_repeat_image_choose) && it.isEnabled
                }
            }
        }
        assertEquals(previous, normal.reopen(b.projectId, b))
        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
            QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                val publish = views.filterIsInstance<Button>().singleOrNull {
                    it.text.toString() == activity.getString(R.string.private_combined_publish) && it.isEnabled
                }
                if (publish == null) false else {
                    views.filterIsInstance<EditText>().single().setText(workingText)
                    publish.performClick()
                }
            }
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.private_combined_unsaved)
                }
            }
            assertEquals(previous, normal.reopen(b.projectId, b))
            clickButton(editor, R.string.private_combined_save_text)
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<Button>().any {
                    it.text.toString() == activity.getString(R.string.private_combined_publish) && it.isEnabled
                } && views.filterIsInstance<EditText>().singleOrNull()?.text?.toString() == workingText
            }
            assertEquals(previous, normal.reopen(b.projectId, b))
            clickButton(editor, R.string.private_combined_publish)
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<Button>().any {
                    it.text.toString() == activity.getString(R.string.private_combined_publish) && it.isEnabled
                }
            }
        }
        val committed = requireNotNull(normal.reopen(b.projectId, b))
        assertNotEquals(previous.generation, committed.generation)
        assertEquals(mapOf("/data/household_name" to householdDigest), committed.evidence.directAnswerDigests)
        assertEquals(mapOf(survivor to mapOf("/data/members/name" to state.required("memberDigest")),
            added to mapOf("/data/members/name" to answerDigest)), committed.evidence.savedRowAnswerDigests)
        assertTrue(committed.evidence.imageOrdinals.isEmpty())
        assertTrue(committed.evidence.directImageDigests.isEmpty())
        state.evidence.setProperty("generation", committed.generation)
        state.evidence.setProperty("graphicalPid", android.os.Process.myPid().toString())
        state.evidence.setProperty("newAnswerDigest", householdDigest)
        state.evidence.setProperty("added", added)
        state.evidence.setProperty("addedDigest", answerDigest)
        val workDir = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)
        workDir.walkTopDown().filter { it.isFile }.forEach {
            state.evidence.setProperty("graphicalFile." + it.relativeTo(workDir).path, digest(it.readBytes()))
        }
        state.evidence.setProperty("pointerDigest", digest(File(state.area, "current.aesgcm").readBytes()))
        java.io.FileOutputStream(state.marker).use {
            state.evidence.store(it, "Synthetic graphical restart checkpoint")
            it.fd.sync()
        }
    }

    @Test
    fun mixedGraphicalEditorReopensAfterForceStop() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        assertNotEquals(state.required("graphicalPid"), android.os.Process.myPid().toString())
        val workDir = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)
        fun snapshot() = workDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(workDir).path to digest(it.readBytes())
        }
        val expected = state.evidence.stringPropertyNames().filter { it.startsWith("graphicalFile.") }
            .associate { it.removePrefix("graphicalFile.") to state.required(it) }
        assertEquals(expected, snapshot())
        val pointer = File(state.area, "current.aesgcm")
        assertEquals(state.required("pointerDigest"), digest(pointer.readBytes()))
        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
            QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                val input = views.filterIsInstance<EditText>().singleOrNull()
                if (input == null) false else {
                    assertEquals(state.required("newAnswerDigest"),
                        QuarantinedWorkDraftVault.sha256(input.text.toString()))
                    assertTrue(views.filterIsInstance<TextView>().any {
                        it.text.toString() == activity.getString(
                            R.string.private_combined_checkpoint, state.required("generation"))
                    })
                    assertTrue(views.filterIsInstance<TextView>().any {
                        it.text.toString() == activity.getString(R.string.private_combined_rows, 2)
                    })
                    true
                }
            }
        }
        ActivityScenario.launch<QuarantinedRepeatAnswerActivity>(
            QuarantinedRepeatAnswerActivity.intentFor(state.context, b.projectId, b.workId,
                state.required("added"), true)
        ).use { editor ->
            awaitUi(editor) { _, views ->
                val input = views.filterIsInstance<EditText>().singleOrNull()
                if (input == null) false else {
                    assertEquals(state.required("addedDigest"),
                        QuarantinedWorkDraftVault.sha256(input.text.toString()))
                    true
                }
            }
        }
        assertEquals(expected, snapshot())
        assertEquals(state.required("pointerDigest"), digest(pointer.readBytes()))
        val pending = RecordWorkReadOnlyClient(state.settings).load(b.projectId).pendingWork
        assertTrue(pending.any { it.id == b.workId })
        assertEquals(state.required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
            pending.map { it.id }.sorted().joinToString("\n")))
        // Keep the same persisted work for the competing-writer gate.
    }

    @Test
    fun competingCheckpointPublicationsRejectStaleWorkingState() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val normal = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val probe = ProtectedNativeRepeatImageProbe(state.context.cacheDir, root, state.settings)
        val original = requireNotNull(normal.reopen(b.projectId, b))
        assertEquals(state.required("generation"), original.generation)
        val workDir = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)
        val pointer = File(state.area, "current.aesgcm")
        fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to digest(it.readBytes())
        }
        fun candidates() = state.area.listFiles().orEmpty().filter { it.name.startsWith(".candidate-") }
        val oldPointer = digest(pointer.readBytes())
        val oldGenerationDir = File(state.area, original.generation)
        val oldGeneration = snapshot(oldGenerationDir)
        val previousWorking = snapshot(workDir)
        val oldDirs = state.area.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        assertTrue(candidates().isEmpty())
        val approved = RecordWorkPreparationClient(state.settings).authorizePrivateTextForm(b.projectId, b)
        val session = JavaRosaPrivateRepeatAnswerSession(
            state.context.cacheDir, b, approved.plan, approved.targetXFormXml, true)
        val path = session.schema.directTextPaths.single()
        val directStore = EncryptedPrivateTextDraftStore(root, AndroidKeyStorePrivateDraftCipher())
        val baseValues = directStore.read(b, session.schema.directTextPaths).answers
        assertEquals(state.required("newAnswerDigest"),
            QuarantinedWorkDraftVault.sha256(requireNotNull(baseValues[path])))
        val updated = mapOf(path to ("Synthetic competing edit " + java.util.UUID.randomUUID()))

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            // Hold a real worker at the validated-candidate boundary, while
            // another thread changes the working text through authorized save.
            val captured = java.util.concurrent.CountDownLatch(1)
            val resume = java.util.concurrent.CountDownLatch(1)
            val first = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings) {
                captured.countDown()
                check(resume.await(30, java.util.concurrent.TimeUnit.SECONDS))
            }
            val future = executor.submit<Boolean> {
                runCatching { first.commit(b.projectId, b, original.generation) }.isFailure
            }
            try {
                assertTrue(captured.await(30, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1, candidates().size)
                assertEquals(original, normal.reopen(b.projectId, b))
                assertEquals(1, candidates().size) // active candidate not scavenged
                assertEquals(oldPointer, digest(pointer.readBytes()))
                probe.saveDirectText(b.projectId, b, updated, baseValues)
                val changedWorking = snapshot(workDir)
                assertNotEquals(previousWorking, changedWorking)
                assertTrue(runCatching {
                    probe.saveDirectText(b.projectId, b,
                        mapOf(path to "Synthetic stale edit"), baseValues)
                }.isFailure)
                assertEquals(changedWorking, snapshot(workDir))
            } finally { resume.countDown() }
            assertTrue(future.get(30, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(candidates().isEmpty())
            assertEquals(oldDirs, state.area.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet())
            assertEquals(original, normal.reopen(b.projectId, b))
            assertEquals(oldPointer, digest(pointer.readBytes()))
            assertEquals(oldGeneration, snapshot(oldGenerationDir))

            // A competing publisher wins while a different worker is
            // validating the same expected generation. Only one may publish.
            val secondCaptured = java.util.concurrent.CountDownLatch(1)
            val secondResume = java.util.concurrent.CountDownLatch(1)
            val second = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings) {
                secondCaptured.countDown()
                check(secondResume.await(30, java.util.concurrent.TimeUnit.SECONDS))
            }
            val competing = executor.submit<Boolean> {
                runCatching { second.commit(b.projectId, b, original.generation) }.isFailure
            }
            val finalWorking = snapshot(workDir)
            val winner = try {
                assertTrue(secondCaptured.await(30, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1, candidates().size)
                normal.commit(b.projectId, b, original.generation)
            } finally { secondResume.countDown() }
            assertTrue(competing.get(30, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(candidates().isEmpty())
            assertEquals(oldDirs + winner.generation,
                state.area.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet())
            assertEquals(winner, normal.reopen(b.projectId, b))
            assertEquals(mapOf(path to QuarantinedWorkDraftVault.sha256(updated.getValue(path))),
                winner.evidence.directAnswerDigests)
            assertEquals(original.evidence.savedRowAnswerDigests, winner.evidence.savedRowAnswerDigests)
            assertEquals(original.evidence.imageOrdinals, winner.evidence.imageOrdinals)
            assertEquals(finalWorking, snapshot(workDir))
            assertEquals(oldGeneration, snapshot(oldGenerationDir))
            assertFalse(b.canOpenEditor || b.canFinalize || b.canUpload)
            val pending = RecordWorkReadOnlyClient(state.settings).load(b.projectId).pendingWork
            assertTrue(pending.any { it.id == b.workId })
            assertEquals(state.required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
                pending.map { it.id }.sorted().joinToString("\n")))
            state.evidence.setProperty("generation", winner.generation)
            java.io.FileOutputStream(state.marker).use {
                state.evidence.store(it, "Synthetic finalization eligibility input")
                it.fd.sync()
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun finalizationEligibilityRequiresCompleteCurrentCommittedNativeModel() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val generations = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val probe = ProtectedNativeRepeatImageProbe(state.context.cacheDir, root, state.settings)
        val previous = requireNotNull(generations.reopen(b.projectId, b))
        assertEquals(state.required("generation"), previous.generation)
        val workDir = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)
        val pointer = File(state.area, "current.aesgcm")
        fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to digest(it.readBytes())
        }
        val oldDir = File(state.area, previous.generation)
        val old = snapshot(oldDir)
        val initial = snapshot(workDir)
        val initialPointer = digest(pointer.readBytes())
        assertTrue(previous.evidence.imageOrdinals.isEmpty() && previous.evidence.directImageDigests.isEmpty())
        assertTrue(runCatching {
            generations.verifyFinalizationEligibility(b.projectId, b, previous.generation)
        }.isFailure)
        assertEquals(initial, snapshot(workDir))
        assertEquals(initialPointer, digest(pointer.readBytes()))

        val authorized = RecordWorkPreparationClient(state.settings).authorizePrivateTextForm(b.projectId, b)
        val native = JavaRosaPrivateRepeatAnswerSession(state.context.cacheDir, b,
            authorized.plan, authorized.targetXFormXml, true)
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val rows = QuarantinedRepeatRowVault(root, cipher)
        val images = QuarantinedRepeatImageStore(root, cipher, rows)
        val direct = ProtectedNativeImageIntake(state.context.cacheDir, root, state.settings)
        val group = native.schema.groups.single()
        val active = rows.read(b, native.schema).active(group.path)
        assertEquals(2, active.size)
        fun png(color: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                java.io.ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        val media = listOf(png(android.graphics.Color.GREEN), png(android.graphics.Color.RED),
            png(android.graphics.Color.BLUE))
        try {
            val directPath = native.schema.directImagePaths.single()
            val directReceipt = direct.importSelectedImage(b.projectId, b, directPath,
                "image/png", media[0].inputStream())
            active.forEachIndexed { index, row ->
                val receipt = images.importSelectedImage(b, native, row.rowId,
                    group.imagePaths.single(), "image/png", media[index + 1].inputStream())
                assertEquals(digest(media[index + 1]), receipt.sha256)
            }
            val directText = native.schema.directTextPaths.single()
            val values = EncryptedPrivateTextDraftStore(root, cipher).read(b, native.schema.directTextPaths).answers
            val incomplete = mapOf(directText to "")
            probe.saveDirectText(b.projectId, b, incomplete, values)
            val dirty = snapshot(workDir)
            assertTrue(runCatching {
                generations.verifyFinalizationEligibility(b.projectId, b, previous.generation)
            }.isFailure)
            assertEquals(dirty, snapshot(workDir))
            val blank = generations.commit(b.projectId, b, previous.generation)
            assertTrue(runCatching {
                generations.verifyFinalizationEligibility(b.projectId, b, blank.generation)
            }.isFailure)
            probe.saveDirectText(b.projectId, b, values, incomplete)
            val complete = generations.commit(b.projectId, b, blank.generation)
            val before = snapshot(workDir)
            val committedDir = File(state.area, complete.generation)
            val committedBefore = snapshot(committedDir)
            val pointerBefore = digest(pointer.readBytes())
            assertTrue(runCatching {
                generations.verifyFinalizationEligibility(b.projectId, b, blank.generation)
            }.isFailure)
            val eligible = generations.verifyFinalizationEligibility(b.projectId, b, complete.generation)
            assertEquals(b.workId, eligible.workId)
            assertEquals(b.reservedInstanceId, eligible.reservedInstanceId)
            assertNotEquals(b.sourceInstanceId, eligible.reservedInstanceId)
            assertEquals(b.targetFormVersionId, eligible.targetFormVersionId)
            assertEquals(complete.generation, eligible.generation)
            assertEquals(complete.evidence, eligible.evidence)
            assertEquals(mapOf(directPath to directReceipt.sha256), eligible.evidence.directImageDigests)
            assertEquals(active.mapIndexed { index, row ->
                (row.rowId to group.imagePaths.single()) to index
            }.toMap(), eligible.evidence.imageOrdinals)
            assertFalse(eligible.canFinalize || eligible.canUpload)
            assertEquals(eligible, generations.verifyFinalizationEligibility(b.projectId, b, complete.generation))
            assertEquals(before, snapshot(workDir))
            assertEquals(committedBefore, snapshot(committedDir))
            assertEquals(old, snapshot(oldDir))
            assertEquals(pointerBefore, digest(pointer.readBytes()))
            assertEquals(b, QuarantinedWorkDraftVault(root).read(b.projectId, b.workId, b.actorContextSha256))
            val pending = RecordWorkReadOnlyClient(state.settings).load(b.projectId).pendingWork
            assertTrue(pending.any { it.id == b.workId })
            assertEquals(state.required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
                pending.map { it.id }.sorted().joinToString("\n")))
            // Native serialization must deny stale generation and return only
            // deterministic digest evidence for the complete current generation.
            assertTrue(runCatching {
                generations.verifyNativeXmlSerialization(b.projectId, b, blank.generation)
            }.isFailure)
            val xml = generations.verifyNativeXmlSerialization(b.projectId, b, complete.generation)
            assertEquals(eligible, xml.eligibility)
            assertTrue(xml.xml.xmlBytes > 0)
            assertEquals(2, xml.xml.repeatCount)
            assertEquals(active.map { it.rowId to group.imagePaths.single() }.toSet() +
                ("direct" to directPath), xml.xml.mediaReferenceDigests.keys)
            assertEquals(3, xml.xml.mediaReferenceDigests.values.toSet().size)
            assertEquals(xml, generations.verifyNativeXmlSerialization(b.projectId, b, complete.generation))
            assertEquals(before, snapshot(workDir))
            assertEquals(committedBefore, snapshot(committedDir))
            assertEquals(pointerBefore, digest(pointer.readBytes()))
            assertTrue(runCatching {
                generations.verifyNativeAttachmentPackage(b.projectId, b, blank.generation)
            }.isFailure)
            val pack = generations.verifyNativeAttachmentPackage(b.projectId, b, complete.generation)
            assertEquals(eligible, pack.eligibility)
            val expectedMedia = mapOf(("direct" to directPath) to media[0]) +
                active.mapIndexed { index, row ->
                    (row.rowId to group.imagePaths.single()) to media[index + 1]
                }.toMap()
            assertEquals(expectedMedia.keys, pack.packaged.attachments.keys)
            assertEquals(3, pack.packaged.attachments.values.map { it.filename }.toSet().size)
            assertEquals(expectedMedia.mapValues { digest(it.value) },
                pack.packaged.attachments.mapValues { it.value.sha256 })
            assertEquals(expectedMedia.mapValues { it.value.size },
                pack.packaged.attachments.mapValues { it.value.bytes })
            assertTrue(pack.packaged.attachments.values.all { it.mime == "image/png" &&
                it.filename.matches(Regex("protected-[0-9a-f]{64}\\.png")) })
            assertEquals(pack.packaged.attachments.mapValues {
                QuarantinedWorkDraftVault.sha256(it.value.filename)
            }, pack.packaged.xml.mediaReferenceDigests)
            assertEquals(pack.packaged.xml.xmlBytes.toLong() + media.sumOf { it.size.toLong() },
                pack.packaged.totalContentBytes)
            assertEquals(pack, generations.verifyNativeAttachmentPackage(b.projectId, b, complete.generation))
            assertNotEquals(xml.xml.xmlSha256, pack.packaged.xml.xmlSha256)

            // Independent negative package-builder tests on synthetic bytes,
            // without reading or exporting any real household data.
            val syntheticXml = "<synthetic/>".toByteArray()
            val slot = "direct" to directPath
            val name = ProtectedNativePackageBuilder.filename(b, slot, "image/png", digest(media[0]))
            val syntheticEvidence = ProtectedNativeXmlEvidence(digest(syntheticXml), syntheticXml.size, 0,
                mapOf(slot to QuarantinedWorkDraftVault.sha256(name)))
            ProtectedNativePackageBuilder(b, complete.generation).use { builder ->
                assertTrue(runCatching { builder.finish(syntheticEvidence, syntheticXml) }.isFailure)
                assertTrue(runCatching {
                    builder.add(slot, "image/jpeg", media[0].size, digest(media[0]), media[0])
                }.isFailure)
                assertTrue(runCatching {
                    builder.add(slot, "image/png", media[0].size, digest(media[1]), media[0])
                }.isFailure)
                builder.add(slot, "image/png", media[0].size, digest(media[0]), media[0])
                assertTrue(runCatching {
                    builder.add(slot, "image/png", media[0].size, digest(media[0]), media[0])
                }.isFailure)
                assertTrue(runCatching {
                    builder.finish(syntheticEvidence.copy(mediaReferenceDigests = mapOf(
                        slot to QuarantinedWorkDraftVault.sha256("wrong.png"))), syntheticXml)
                }.isFailure)
                assertTrue(runCatching {
                    builder.finish(syntheticEvidence.copy(mediaReferenceDigests = emptyMap()), syntheticXml)
                }.isFailure)
                assertTrue(runCatching {
                    builder.finish(syntheticEvidence, "<changed/>".toByteArray())
                }.isFailure)
                assertEquals(1, builder.finish(syntheticEvidence, syntheticXml).attachments.size)
            }
            assertEquals(before, snapshot(workDir))
            assertEquals(committedBefore, snapshot(committedDir))
            assertEquals(pointerBefore, digest(pointer.readBytes()))
            state.evidence.setProperty("generation", complete.generation)
            state.evidence.setProperty("packageDigest", pack.packaged.packageSha256)
            state.evidence.setProperty("xmlDigest", pack.packaged.xml.xmlSha256)
            java.io.FileOutputStream(state.marker).use {
                state.evidence.store(it, "Synthetic native serialization evidence")
                it.fd.sync()
            }
        } finally { media.forEach { it.fill(0) } }
    }

    @Test
    fun persistFinalizedPackageAndRejectCorruption() {
        val state = publicationContext()
        val b = state.binding
        val generations = ProtectedCommittedDraftStore(state.context.cacheDir,
            state.context.noBackupFilesDir, state.settings)
        val id = state.required("generation")
        val expected = generations.verifyNativeAttachmentPackage(b.projectId, b, id)
        assertEquals(state.required("packageDigest"), expected.packaged.packageSha256)
        val file = File(state.area, "finalized-" + id + ".aesgcm")
        val pending = File(state.area, ".finalized-write-" + id)
        assertFalse(file.exists() || pending.exists())
        assertTrue(runCatching { generations.reopenFinalizedPackage(b.projectId, b, id) }.isFailure)
        // Model an unpublished encrypted temp without granting it authority.
        pending.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { generations.reopenFinalizedPackage(b.projectId, b, id) }.isFailure)
        assertFalse(file.exists())
        val saved = generations.persistFinalizedPackage(b.projectId, b, id)
        assertEquals(expected, saved.evidence)
        assertFalse(saved.canUpload)
        assertFalse(pending.exists())
        val original = file.readBytes()
        assertEquals(saved.ciphertextSha256, digest(original))
        assertEquals(saved, generations.persistFinalizedPackage(b.projectId, b, id))
        assertArrayEquals(original, file.readBytes())
        assertEquals(saved, generations.reopenFinalizedPackage(b.projectId, b, id))
        assertTrue(runCatching {
            generations.persistFinalizedPackage(b.projectId, b, state.required("historical"))
        }.isFailure)
        val altered = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(altered)
        try {
            assertTrue(runCatching { generations.reopenFinalizedPackage(b.projectId, b, id) }.isFailure)
            assertTrue(runCatching { generations.persistFinalizedPackage(b.projectId, b, id) }.isFailure)
            assertArrayEquals(altered, file.readBytes())
        } finally { file.writeBytes(original) }
        assertEquals(saved, generations.reopenFinalizedPackage(b.projectId, b, id))
        val work = File(File(File(state.context.noBackupFilesDir,
            QuarantinedWorkDraftVault.VAULT_DIRECTORY), b.projectId), b.workId)
        val committed = File(state.area, id)
        fun record(prefix: String, dir: File) {
            dir.walkTopDown().filter { it.isFile }.forEach {
                state.evidence.setProperty(prefix + it.relativeTo(dir).path, digest(it.readBytes()))
            }
        }
        record("finalWorking.", work)
        record("finalCommitted.", committed)
        state.evidence.setProperty("finalizedDigest", saved.ciphertextSha256)
        state.evidence.setProperty("finalizedPid", android.os.Process.myPid().toString())
        state.evidence.setProperty("pointerDigest", digest(File(state.area, "current.aesgcm").readBytes()))
        java.io.FileOutputStream(state.marker).use {
            state.evidence.store(it, "Synthetic finalized package restart oracle")
            it.fd.sync()
        }
    }

    @Test
    fun finalizedPackageRecoversAfterForceStop() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val id = state.required("generation")
        assertNotEquals(state.required("finalizedPid"), android.os.Process.myPid().toString())
        val file = File(state.area, "finalized-" + id + ".aesgcm")
        val before = file.readBytes()
        assertEquals(state.required("finalizedDigest"), digest(before))
        val pointer = File(state.area, "current.aesgcm")
        assertEquals(state.required("pointerDigest"), digest(pointer.readBytes()))
        val store = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val reopened = store.reopenFinalizedPackage(b.projectId, b, id)
        assertEquals(state.required("packageDigest"), reopened.evidence.packaged.packageSha256)
        assertEquals(state.required("xmlDigest"), reopened.evidence.packaged.xml.xmlSha256)
        assertEquals(state.required("finalizedDigest"), reopened.ciphertextSha256)
        assertEquals(b.reservedInstanceId, reopened.evidence.eligibility.reservedInstanceId)
        assertEquals(3, reopened.evidence.packaged.attachments.size)
        assertFalse(reopened.canUpload)
        assertEquals(reopened, store.persistFinalizedPackage(b.projectId, b, id))
        assertArrayEquals(before, file.readBytes())
        val work = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
            b.projectId), b.workId)
        fun checkSnapshot(prefix: String, dir: File) {
            val expected = state.evidence.stringPropertyNames().filter { it.startsWith(prefix) }
                .associate { it.removePrefix(prefix) to state.required(it) }
            assertEquals(expected, dir.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(dir).path to digest(it.readBytes())
            })
        }
        checkSnapshot("finalWorking.", work)
        checkSnapshot("finalCommitted.", File(state.area, id))
        assertEquals(state.required("pointerDigest"), digest(pointer.readBytes()))
        assertEquals(b, QuarantinedWorkDraftVault(root).read(b.projectId, b.workId, b.actorContextSha256))
        val pending = RecordWorkReadOnlyClient(state.settings).load(b.projectId).pendingWork
        assertTrue(pending.any { it.id == b.workId })
        assertEquals(state.required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
            pending.map { it.id }.sorted().joinToString("\n")))
        // Retain digest-only state for the graphical finalization gate.
    }

    @Test
    fun graphicalFinalizationConfirmsCurrentGeneration() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val store = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val oldId = state.required("generation")
        val oldFile = File(state.area, "finalized-" + oldId + ".aesgcm")
        val oldBytes = oldFile.readBytes()
        val edited = "Synthetic graphical finalization " + java.util.UUID.randomUUID()
        fun textVisible(activity: android.app.Activity, views: List<View>, label: Int) =
            views.filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
        fun dialog(id: Int) {
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withId(id))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
        }
        var generation = ""
        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
            QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                if (!textVisible(activity, views, R.string.private_finalization_complete)) false
                else {
                    assertFalse(views.filterIsInstance<Button>().single {
                        it.text.toString() == activity.getString(R.string.private_finalization_action)
                    }.isEnabled)
                    views.filterIsInstance<EditText>().single().setText(edited)
                    true
                }
            }
            clickButton(editor, R.string.private_combined_save_text)
            awaitUi(editor) { activity, views ->
                if (!textVisible(activity, views, R.string.private_finalization_dirty)) false
                else {
                    assertFalse(views.filterIsInstance<Button>().single {
                        it.text.toString() == activity.getString(R.string.private_finalization_action)
                    }.isEnabled)
                    true
                }
            }
            assertArrayEquals(oldBytes, oldFile.readBytes())
            clickButton(editor, R.string.private_combined_publish)
            dialog(android.R.id.button1)
            awaitUi(editor) { activity, views ->
                textVisible(activity, views, R.string.private_finalization_not_yet)
            }
            generation = requireNotNull(store.reopen(b.projectId, b)).generation
            assertNotEquals(oldId, generation)
            val file = File(state.area, "finalized-" + generation + ".aesgcm")
            assertFalse(file.exists())
            // Unsaved text is not eligible for any finalization action.
            editor.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<EditText>().single().setText("Unsaved synthetic answer")
                views.filterIsInstance<Button>().single {
                    it.text.toString() == activity.getString(R.string.private_finalization_action)
                }.performClick()
                assertTrue(textVisible(activity, views, R.string.private_combined_unsaved))
                views.filterIsInstance<EditText>().single().setText(edited)
            }
            assertFalse(file.exists())
            clickButton(editor, R.string.private_finalization_check)
            awaitUi(editor) { activity, views ->
                textVisible(activity, views, R.string.private_finalization_eligible)
            }
            assertFalse(file.exists())
            clickButton(editor, R.string.private_finalization_action)
            dialog(android.R.id.button2)
            assertFalse(file.exists())
            clickButton(editor, R.string.private_finalization_action)
            dialog(android.R.id.button1)
            awaitUi(editor) { activity, views ->
                textVisible(activity, views, R.string.private_finalization_complete)
            }
            assertTrue(file.isFile)
        }
        val saved = store.reopenFinalizedPackage(b.projectId, b, generation)
        assertFalse(saved.canUpload)
        assertEquals(3, saved.evidence.packaged.attachments.size)
        assertEquals(mapOf("/data/household_name" to QuarantinedWorkDraftVault.sha256(edited)),
            saved.evidence.eligibility.evidence.directAnswerDigests)
        assertArrayEquals(oldBytes, oldFile.readBytes())
        state.evidence.setProperty("generation", generation)
        state.evidence.setProperty("packageDigest", saved.evidence.packaged.packageSha256)
        state.evidence.setProperty("xmlDigest", saved.evidence.packaged.xml.xmlSha256)
        state.evidence.setProperty("finalizedDigest", saved.ciphertextSha256)
        state.evidence.setProperty("graphicalFinalPid", android.os.Process.myPid().toString())
        state.evidence.setProperty("pointerDigest", digest(File(state.area, "current.aesgcm").readBytes()))
        java.io.FileOutputStream(state.marker).use {
            state.evidence.store(it, "Synthetic graphical finalized package")
            it.fd.sync()
        }
    }

    @Test
    fun graphicalFinalizedStatusRecoversAfterForceStop() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        assertNotEquals(state.required("graphicalFinalPid"), android.os.Process.myPid().toString())
        val id = state.required("generation")
        val file = File(state.area, "finalized-" + id + ".aesgcm")
        val before = file.readBytes()
        assertEquals(state.required("finalizedDigest"), digest(before))
        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
            QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                val ready = views.filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.private_finalization_complete)
                }
                if (ready) {
                    assertTrue(views.filterIsInstance<TextView>().any {
                        it.text.toString() == activity.getString(R.string.private_combined_checkpoint, id)
                    })
                    assertFalse(views.filterIsInstance<Button>().single {
                        it.text.toString() == activity.getString(R.string.private_finalization_action)
                    }.isEnabled)
                }
                ready
            }
        }
        val saved = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
            .reopenFinalizedPackage(b.projectId, b, id)
        assertEquals(state.required("packageDigest"), saved.evidence.packaged.packageSha256)
        assertEquals(state.required("xmlDigest"), saved.evidence.packaged.xml.xmlSha256)
        assertArrayEquals(before, file.readBytes())
        assertEquals(state.required("pointerDigest"), digest(File(state.area, "current.aesgcm").readBytes()))
        assertFalse(saved.canUpload || b.canFinalize || b.canUpload)
        val pending = RecordWorkReadOnlyClient(state.settings).load(b.projectId).pendingWork
        assertTrue(pending.any { it.id == b.workId })
        assertEquals(state.required("pendingDigest"), QuarantinedWorkDraftVault.sha256(
            pending.map { it.id }.sorted().joinToString("\n")))
    }

    @Test
    fun finalizedSubmissionAdmissionBindsCurrentReadyWorkWithoutSending() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val id = state.required("generation")
        val sha = state.required("packageDigest")
        val file = File(state.area, "finalized-" + id + ".aesgcm")
        val pointer = File(state.area, "current.aesgcm")
        val original = file.readBytes()
        val pointerDigest = digest(pointer.readBytes())
        val work = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
            b.projectId), b.workId)
        fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to digest(it.readBytes())
        }
        val workingBefore = snapshot(work)
        val committedBefore = snapshot(File(state.area, id))
        val remote = RecordWorkReadOnlyClient(state.settings)
        val before = remote.load(b.projectId)
        val gate = ProtectedSubmissionAdmission(state.context.cacheDir, root, state.settings)
        val admission = gate.inspect(b.projectId, b, id, sha)
        assertEquals(b.projectId, admission.projectId)
        assertEquals(b.actorContextSha256, admission.actorContextSha256)
        assertEquals(b.workId, admission.workId)
        assertEquals(b.sourceSubmissionId, admission.sourceSubmissionId)
        assertEquals(b.sourceInstanceId, admission.sourceInstanceId)
        assertEquals(b.targetFormVersionId, admission.targetFormVersionId)
        assertEquals(b.targetVersion, admission.targetVersion)
        assertEquals(b.visitKey, admission.visitKey)
        assertEquals(b.reservedInstanceId, admission.reservedInstanceId)
        assertNotEquals(admission.sourceInstanceId, admission.reservedInstanceId)
        assertEquals(id, admission.generation)
        assertEquals(sha, admission.packageSha256)
        assertEquals(digest(original), admission.ciphertextSha256)
        assertEquals(RecordWorkApiPaths.fromProjectUrl(
            state.settings.getUnprotectedSettings(b.projectId).getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        ).submission(), admission.endpoint)
        assertEquals("X-DataCollector-Work-Item-Id", admission.workHeaderName)
        assertEquals(b.workId, admission.workHeaderValue)
        assertFalse(admission.canUpload)
        assertEquals(admission, gate.inspect(b.projectId, b, id, sha))
        assertTrue(runCatching { gate.inspect(b.projectId, b, id, "0".repeat(64)) }.isFailure)
        assertTrue(runCatching { gate.inspect(b.projectId, b, state.required("historical"), sha) }.isFailure)
        assertTrue(runCatching {
            gate.inspect(b.projectId, b.copy(actorContextSha256 = "0".repeat(64)), id, sha)
        }.isFailure)
        assertTrue(runCatching {
            gate.inspect(b.projectId, b.copy(reservedInstanceId = b.sourceInstanceId), id, sha)
        }.isFailure)
        assertTrue(runCatching {
            gate.inspect(b.projectId, b.copy(visitKey = "wrong-visit"), id, sha)
        }.isFailure)
        val pending = before.pendingWork.single { it.id == b.workId }
        fun rejectRemote(snapshot: RemoteRecordSnapshot) {
            assertTrue(runCatching { ProtectedSubmissionAdmission.verifySnapshot(b, snapshot) }.isFailure)
        }
        rejectRemote(before.copy(pendingWork = before.pendingWork.filter { it.id != b.workId }))
        rejectRemote(before.copy(pendingWork = before.pendingWork.filter { it.id != b.workId } +
            pending.copy(targetFormVersionId = java.util.UUID.randomUUID().toString())))
        rejectRemote(before.copy(pendingWork = before.pendingWork.filter { it.id != b.workId } +
            pending.copy(visitKey = "wrong-visit")))
        rejectRemote(before.copy(records = before.records.filter { it.submissionId != b.sourceSubmissionId }))
        rejectRemote(before.copy(records = before.records + before.records.single {
            it.submissionId == b.sourceSubmissionId
        }.copy(submissionId = java.util.UUID.randomUUID().toString(), instanceId = b.reservedInstanceId)))
        val tampered = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(tampered)
        try {
            assertTrue(runCatching { gate.inspect(b.projectId, b, id, sha) }.isFailure)
            assertArrayEquals(tampered, file.readBytes())
        } finally { file.writeBytes(original) }
        assertEquals(admission, gate.inspect(b.projectId, b, id, sha))
        assertArrayEquals(original, file.readBytes())
        assertEquals(pointerDigest, digest(pointer.readBytes()))
        assertEquals(workingBefore, snapshot(work))
        assertEquals(committedBefore, snapshot(File(state.area, id)))
        val after = remote.load(b.projectId)
        assertEquals(before.records.toSet(), after.records.toSet())
        assertEquals(before.pendingWork.toSet(), after.pendingWork.toSet())
        // Retain digest-only oracle for dedicated multipart sender.
    }

    @Test
    fun finalizedMultipartUsesExactPersistedBytesWithoutRealPost() {
        var phase = "SETUP"
        try {
            verifyFinalizedMultipart { phase = it }
        } catch (failure: Throwable) {
            val line = failure.stackTrace.firstOrNull {
                it.className.startsWith(ConnectedProtectedEditorRestartTest::class.java.name)
            }?.lineNumber ?: -1
            val kind = when (failure) {
                is AssertionError -> "AssertionError"
                is IllegalArgumentException -> "IllegalArgumentException"
                is IllegalStateException -> "IllegalStateException"
                is java.io.IOException -> "IOException"
                else -> "OtherException"
            }
            val allowed = setOf("ProtectedMultipartSubmission", "ProtectedCommittedDraftStore",
                "ProtectedFinalizedPackageFile", "ProtectedSubmissionAdmission",
                "ProtectedNativePackageBuilder", "RecordWorkPreparationClient",
                "RecordWorkReadOnlyClient", "AndroidKeyStorePrivateDraftCipher")
            val origin = failure.stackTrace.firstOrNull {
                it.className.substringAfterLast('.').substringBefore('$') in allowed
            }
            val component = origin?.className?.substringAfterLast('.')?.substringBefore('$') ?: "Unknown"
            val originLine = origin?.lineNumber ?: -1
            throw AssertionError("U06_MULTIPART_FAILURE phase=$phase kind=$kind line=$line origin=$component:$originLine")
        }
    }

    private class CapturedHttps(url: java.net.URL, private val code: Int,
                                private val receipt: ByteArray) : javax.net.ssl.HttpsURLConnection(url) {
        val body = java.io.ByteArrayOutputStream()
        val headers = linkedMapOf<String, String>()
        var length = -1L
        var inputReads = 0
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "synthetic-capture-only"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
        override fun getOutputStream(): java.io.OutputStream = body
        override fun getInputStream(): java.io.InputStream {
            inputReads++
            return receipt.inputStream()
        }
        override fun getResponseCode() = code
        override fun getContentType() = "text/xml; charset=UTF-8"
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun setFixedLengthStreamingMode(contentLength: Long) { length = contentLength }
    }

    private fun verifyFinalizedMultipart(phase: (String) -> Unit) {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val generation = state.required("generation")
        val sha = state.required("packageDigest")
        val saved = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
            .reopenFinalizedPackage(b.projectId, b, generation)
        val remote = RecordWorkReadOnlyClient(state.settings)
        val before = remote.load(b.projectId)
        val latch = File(state.area, "submission-attempt.aesgcm")
        val envelope = File(state.area, "finalized-" + generation + ".aesgcm")
        val envelopeBefore = envelope.readBytes()
        assertFalse(latch.exists())
        phase("DECRYPTED_FRAME")
        // Android may supply an alias for the app-private no-backup root.
        // The exact published subtree is still required, including no links.
        val canonicalArea = state.area.canonicalFile
        assertEquals(canonicalArea, ProtectedMultipartSubmission.submissionAttemptArea(root, b))
        assertEquals(canonicalArea, ProtectedMultipartSubmission.submissionAttemptArea(
            File(root, "."), b))
        val hostile = File(root, "u06-attempt-path-negative-" + java.util.UUID.randomUUID())
        try {
            assertTrue(hostile.mkdir())
            java.nio.file.Files.createSymbolicLink(File(hostile, "recordwork-committed").toPath(),
                File(root, "recordwork-committed").canonicalFile.toPath())
            assertTrue(runCatching {
                ProtectedMultipartSubmission.submissionAttemptArea(hostile, b)
            }.isFailure)
        } finally {
            java.nio.file.Files.deleteIfExists(File(hostile, "recordwork-committed").toPath())
            assertTrue(hostile.delete())
        }
        // Separately check the exact published bytes before transport.
        ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
            .withFinalizedSubmissionContent(b.projectId, b, generation) { reopened, bytes ->
                assertEquals(saved, reopened)
                assertEquals(sha, digest(bytes))
                java.io.DataInputStream(bytes.inputStream()).use { input ->
                    assertEquals("DC_NATIVE_PACKAGE_V1", input.readUTF())
                    assertEquals(b.projectId, input.readUTF())
                    assertEquals(b.actorContextSha256, input.readUTF())
                    assertEquals(b.workId, input.readUTF())
                    assertEquals(b.sourceSubmissionId, input.readUTF())
                    assertEquals(b.targetFormVersionId, input.readUTF())
                    assertEquals(b.reservedInstanceId, input.readUTF())
                    assertEquals(generation, input.readUTF())
                    val n = input.readInt()
                    assertEquals(saved.evidence.packaged.xml.xmlBytes, n)
                    val xml = ByteArray(n)
                    try {
                        input.readFully(xml)
                        assertEquals(saved.evidence.packaged.xml.xmlSha256, digest(xml))
                    } finally { xml.fill(0) }
                    assertEquals(saved.evidence.packaged.attachments.size, input.readInt())
                    for ((key, part) in saved.evidence.packaged.attachments.entries.sortedBy { it.value.filename }) {
                        assertEquals(key.first, input.readUTF())
                        assertEquals(key.second, input.readUTF())
                        assertEquals(part.filename, input.readUTF())
                        assertEquals(part.mime, input.readUTF())
                        assertEquals(part.bytes, input.readInt())
                        val media = ByteArray(part.bytes)
                        try {
                            input.readFully(media)
                            assertEquals(part.sha256, digest(media))
                        } finally { media.fill(0) }
                    }
                    assertEquals(-1, input.read())
                }
            }
        assertFalse(latch.exists())
        phase("CAPTURE_SETUP")
        val endpoint = RecordWorkApiPaths.fromProjectUrl(state.settings.getUnprotectedSettings(b.projectId)
            .getString(ProjectKeys.KEY_SERVER_URL).orEmpty()).submission()
        val receipt = """<OpenRosaResponse xmlns="http://openrosa.org/http/response"><message>Submission received</message><status>201</status><instanceID>${b.reservedInstanceId}</instanceID></OpenRosaResponse>"""
        val conn = CapturedHttps(java.net.URL(endpoint), 201, receipt.toByteArray(Charsets.UTF_8))
        var calls = 0
        val sender = ProtectedMultipartSubmission(state.context.cacheDir, root, state.settings) { url ->
            calls++
            assertEquals(RecordWorkApiPaths.fromProjectUrl(state.settings.getUnprotectedSettings(b.projectId)
                .getString(ProjectKeys.KEY_SERVER_URL).orEmpty()).submission(), url.toString())
            assertTrue(latch.isFile && latch.length() > 30)
            conn
        }
        try {
            phase("WRONG_DIGEST")
            assertTrue(runCatching { sender.send(b.projectId, b, generation, "0".repeat(64)) }.isFailure)
            assertFalse(latch.exists())
            phase("SEND")
            val result = sender.send(b.projectId, b, generation, sha)
            phase("RECEIPT")
            assertEquals(ProtectedTransportResult(201, true, b.reservedInstanceId, sha), result)
            assertEquals(1, calls)
            phase("HEADERS")
            assertFalse(conn.instanceFollowRedirects)
            assertEquals("POST", conn.requestMethod)
            assertEquals(b.workId, conn.headers["X-DataCollector-Work-Item-Id"])
            assertEquals("1.0", conn.headers["X-OpenRosa-Version"])
            val config = state.settings.getUnprotectedSettings(b.projectId)
            val credentials = config.getString(ProjectKeys.KEY_USERNAME).orEmpty() + ":" +
                config.getString(ProjectKeys.KEY_PASSWORD).orEmpty()
            assertTrue(conn.headers["Authorization"] == "Basic " + android.util.Base64.encodeToString(
                credentials.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            val body = conn.body.toByteArray()
            assertEquals(body.size.toLong(), conn.length)
            phase("MULTIPART")
            val latin = String(body, Charsets.ISO_8859_1)
            val boundary = latin.substringBefore("\r\n").removePrefix("--")
            assertEquals("multipart/form-data; boundary=$boundary", conn.headers["Content-Type"])
            val segments = latin.split("--$boundary")
            assertEquals("", segments.first())
            assertEquals("--\r\n", segments.last())
            val actual = linkedMapOf<String, Pair<String, ByteArray>>()
            for (segment in segments.subList(1, segments.lastIndex)) {
                val head = segment.substringBefore("\r\n\r\n")
                val filename = Regex("filename=\"([^\"]+)\"").find(head)!!.groupValues[1]
                val mime = head.substringAfter("Content-Type: ")
                val payload = segment.substringAfter("\r\n\r\n").removeSuffix("\r\n")
                    .toByteArray(Charsets.ISO_8859_1)
                assertFalse(actual.containsKey(filename))
                actual[filename] = mime to payload
            }
            val xml = actual.remove("submission.xml")!!
            assertEquals("text/xml", xml.first)
            assertEquals(saved.evidence.packaged.xml.xmlSha256, digest(xml.second))
            assertEquals(saved.evidence.packaged.attachments.values.map { it.filename }.toSet(), actual.keys)
            saved.evidence.packaged.attachments.values.forEach { part ->
                val observed = actual.getValue(part.filename)
                assertEquals(part.mime, observed.first)
                assertEquals(part.bytes, observed.second.size)
                assertEquals(part.sha256, digest(observed.second))
            }
            phase("REPLAY")
            assertTrue(runCatching { sender.send(b.projectId, b, generation, sha) }.isFailure)
            assertEquals(1, calls)
            assertArrayEquals(envelopeBefore, envelope.readBytes())
            // The only connection was a mock; no real POST occurred.
            val after = remote.load(b.projectId)
            assertEquals(before.records.toSet(), after.records.toSet())
            assertEquals(before.pendingWork.toSet(), after.pendingWork.toSet())
        } finally {
            // CI-only cleanup of a mock attempt, never a product retry path.
            if (latch.exists()) assertTrue(latch.delete())
        }

        // A real sender must never follow redirects or accept a non-201.
        phase("REDIRECT")
        val redirect = CapturedHttps(java.net.URL(endpoint), 307, byteArrayOf())
        var redirects = 0
        try {
            val blocked = ProtectedMultipartSubmission(state.context.cacheDir, root, state.settings) {
                redirects++
                redirect
            }.send(b.projectId, b, generation, sha)
            assertEquals(ProtectedTransportResult(307, false, null, sha), blocked)
            assertEquals(1, redirects)
            assertFalse(redirect.instanceFollowRedirects)
            assertEquals(0, redirect.inputReads)
            assertTrue(latch.isFile)
        } finally { if (latch.exists()) assertTrue(latch.delete()) }
        phase("WRONG_RECEIPT")
        val wrong = CapturedHttps(java.net.URL(endpoint), 201,
            receipt.replace(b.reservedInstanceId, "uuid:00000000-0000-4000-8000-000000000000")
                .toByteArray(Charsets.UTF_8))
        try {
            val result = ProtectedMultipartSubmission(state.context.cacheDir, root, state.settings) {
                wrong
            }.send(b.projectId, b, generation, sha)
            assertEquals(ProtectedTransportResult(201, false, null, sha), result)
            assertTrue(latch.isFile)
        } finally { if (latch.exists()) assertTrue(latch.delete()) }
        phase("POSTCONDITIONS")
        // Persisted work remains available for the real backend acceptance gate.
        assertArrayEquals(envelopeBefore, envelope.readBytes())
        val after = remote.load(b.projectId)
        assertEquals(before.records.toSet(), after.records.toSet())
        assertEquals(before.pendingWork.toSet(), after.pendingWork.toSet())
    }

    @Test
    fun actualFinalizedMultipartCreatesIndependentLinkedObservation() {
        var phase = "SETUP"
        try {
            verifyActualFinalizedSubmission { phase = it }
        } catch (failure: Throwable) {
            val line = failure.stackTrace.firstOrNull {
                it.className.startsWith(ConnectedProtectedEditorRestartTest::class.java.name)
            }?.lineNumber ?: -1
            throw AssertionError("U06_NATIVE_POST_FAILURE phase=$phase line=$line")
        }
    }

    private fun verifyActualFinalizedSubmission(phase: (String) -> Unit) {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        val generation = state.required("generation")
        val sha = state.required("packageDigest")
        val store = ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
        val saved = store.reopenFinalizedPackage(b.projectId, b, generation)
        assertEquals(sha, saved.evidence.packaged.packageSha256)
        assertEquals(3, saved.evidence.packaged.attachments.size)
        assertEquals(2, saved.evidence.packaged.xml.repeatCount)
        val latch = File(state.area, "submission-attempt.aesgcm")
        val envelope = File(state.area, "finalized-" + generation + ".aesgcm")
        val pointer = File(state.area, "current.aesgcm")
        val envelopeBefore = envelope.readBytes()
        val pointerBefore = pointer.readBytes()
        val work = File(File(File(root, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
            b.projectId), b.workId)
        fun inventory(dir: File) = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to digest(it.readBytes())
        }
        val workingBefore = inventory(work)
        val committedBefore = inventory(File(state.area, generation))
        assertFalse(latch.exists())
        val remote = RecordWorkReadOnlyClient(state.settings)
        val before = remote.load(b.projectId)
        ProtectedSubmissionAdmission.verifySnapshot(b, before)
        val original = before.records.single { it.submissionId == b.sourceSubmissionId }
        // The default production connection factory uses the app's project
        // credentials and the disposable debug-only CA, not a test POST stub.
        phase("POST")
        val sender = ProtectedMultipartSubmission(state.context.cacheDir, root, state.settings)
        // Exercise the actual protected UI and production sender. A partial
        // attempt must show a read-only unconfirmed state before the real POST.
        fun visible(activity: android.app.Activity, views: List<View>, label: Int) =
            views.filterIsInstance<TextView>().any { it.text.toString() == activity.getString(label) }
        fun assertReadOnly(activity: android.app.Activity, views: List<View>) {
            assertTrue(views.filterIsInstance<EditText>().isEmpty())
            val buttons = views.filterIsInstance<Button>()
            assertTrue(buttons.none { it.text.toString() == activity.getString(R.string.private_submission_action) })
            assertTrue(buttons.none { it.text.toString() == activity.getString(R.string.private_combined_publish) })
            assertTrue(buttons.any { it.text.toString() == activity.getString(R.string.private_submission_refresh) })
        }
        fun dialog(id: Int) {
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withId(id))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
        }
        phase("UNCONFIRMED_UI")
        val authorized = RecordWorkPreparationClient(state.settings).authorizePrivateTextForm(b.projectId, b)
        val session = JavaRosaPrivateRepeatAnswerSession(state.context.cacheDir, b,
            authorized.plan, authorized.targetXFormXml, true)
        val group = session.schema.groups.single()
        val cipher = AndroidKeyStorePrivateDraftCipher()
        val rows = QuarantinedRepeatRowVault(root, cipher)
        val row = rows.read(b, session.schema).active(group.path).first()
        val direct = session.schema.directTextPaths
        val images = session.schema.directImagePaths
        val rowImage = group.imagePaths.first()
        val directStore = EncryptedPrivateTextDraftStore(root, cipher)
        val rowStore = QuarantinedRepeatAnswerStore(root, cipher)
        val directImage = QuarantinedPrivateImageStore(root, cipher)
        val repeatImage = QuarantinedRepeatImageStore(root, cipher)
        val directValues = directStore.read(b, direct).answers
        val rowValues = rowStore.read(b, session, row.rowId).values
        assertFalse(ProtectedSubmissionAttemptGuard.hasAttempt(root, b))
        assertTrue(latch.createNewFile())
        try {
            // Already-open child editors hold a valid native session; live
            // READY alone cannot authorize another read or write.
            assertTrue(ProtectedSubmissionAttemptGuard.hasAttempt(root, b))
            ProtectedSubmissionAdmission.verifySnapshot(b, remote.load(b.projectId))
            val blocked = listOf<() -> Unit>(
                { directStore.save(b, directValues, direct) },
                { directStore.saveIfAbsent(b, directValues, direct) },
                { directStore.read(b, direct) },
                { directImage.clear(b, images.single(), images) },
                { directImage.importSelectedImage(b, images.single(), images, "image/png", byteArrayOf().inputStream()) },
                { rows.addRow(b, session.schema, group.path, java.util.UUID.randomUUID().toString()) },
                { rows.archiveRow(b, session.schema, row.rowId, java.util.UUID.randomUUID().toString()) },
                { rowStore.save(b, session, row.rowId, rowValues) },
                { repeatImage.clear(b, session, row.rowId, rowImage) },
                { repeatImage.importSelectedImage(b, session, row.rowId, rowImage, "image/png", byteArrayOf().inputStream()) },
                { store.commit(b.projectId, b, generation) },
                { store.persistFinalizedPackage(b.projectId, b, generation) },
                { store.reopenFinalizedPackage(b.projectId, b, generation) }
            )
            blocked.forEachIndexed { index, operation ->
                val failure = runCatching(operation).exceptionOrNull()
                assertTrue("Attempt guard operation $index",
                    failure is IllegalArgumentException &&
                        failure.message == "Submission attempt requires reconciliation")
            }
            assertEquals(workingBefore, inventory(work))
            assertEquals(committedBefore, inventory(File(state.area, generation)))
            assertArrayEquals(envelopeBefore, envelope.readBytes())
            assertArrayEquals(pointerBefore, pointer.readBytes())
            ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
                QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
            ).use { editor ->
                awaitUi(editor) { activity, views ->
                    if (!visible(activity, views, R.string.private_submission_unconfirmed)) false
                    else { assertReadOnly(activity, views); true }
                }
            }
            assertEquals(0L, latch.length())
            assertEquals(before.records.toSet(), remote.load(b.projectId).records.toSet())
        } finally { assertTrue(latch.delete()) } // CI-only synthetic marker cleanup.
        for (name in listOf("submitted-receipt.aesgcm", ".submitted-receipt-write")) {
            val marker = File(state.area, name)
            assertTrue(marker.createNewFile())
            try {
                assertTrue(ProtectedSubmissionAttemptGuard.hasAttempt(root, b))
                assertTrue(runCatching { directStore.save(b, directValues, direct) }.isFailure)
            } finally { assertTrue(marker.delete()) }
        }
        java.nio.file.Files.createSymbolicLink(latch.toPath(), File(state.area, "nonexistent").toPath())
        try {
            assertTrue(ProtectedSubmissionAttemptGuard.hasAttempt(root, b))
            assertTrue(runCatching { directStore.save(b, directValues, direct) }.isFailure)
        } finally { java.nio.file.Files.delete(latch.toPath()) }
        assertFalse(ProtectedSubmissionAttemptGuard.hasAttempt(root, b))
        assertEquals(directValues, directStore.read(b, direct).answers)
        phase("RECONCILIATION_INSPECTION")
        val reconciliation = ProtectedSubmissionReceiptStore(root, state.settings)
        val receiptPath = File(state.area, "submitted-receipt.aesgcm")
        assertFalse(receiptPath.exists())
        // CI-only authentic attempt fixture, using the exact sender wire format.
        // No network write is made; the real work remains READY.
        val aad = listOf("DC_SUBMISSION_ATTEMPT_V1", b.projectId,
            b.actorContextSha256, b.workId).joinToString("\n").toByteArray(Charsets.UTF_8)
        val clear = listOf(generation, sha, digest(envelopeBefore), b.reservedInstanceId,
            b.targetFormVersionId).joinToString("\n").toByteArray(Charsets.UTF_8)
        val syntheticAttempt = try { cipher.seal(clear, aad) } finally { clear.fill(0) }
        assertTrue(latch.createNewFile())
        try {
            latch.writeBytes(syntheticAttempt)
            val ready = reconciliation.inspectAttempt(b.projectId, b)
            assertEquals(ProtectedReconciliationState.READY_WITHOUT_VISIBLE_RECORD, ready.state)
            assertEquals(generation, ready.generation)
            assertEquals(sha, ready.packageSha256)
            assertEquals(digest(syntheticAttempt), ready.attemptSha256)
            assertNull(ready.submissionId)
            assertFalse(ready.canEdit || ready.canUpload)
            assertFalse(receiptPath.exists())
            assertArrayEquals(syntheticAttempt, latch.readBytes())
            assertEquals(before, remote.load(b.projectId))
            assertTrue(runCatching {
                reconciliation.inspectAttempt(b.projectId, b.copy(actorContextSha256 = "0".repeat(64)))
            }.isFailure)
            val corrupt = syntheticAttempt.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            latch.writeBytes(corrupt)
            assertTrue(runCatching { reconciliation.inspectAttempt(b.projectId, b) }.isFailure)
            assertArrayEquals(corrupt, latch.readBytes())
            latch.writeBytes(syntheticAttempt)
            val badEnvelope = envelopeBefore.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            envelope.writeBytes(badEnvelope)
            try {
                assertTrue(runCatching { reconciliation.inspectAttempt(b.projectId, b) }.isFailure)
                assertArrayEquals(badEnvelope, envelope.readBytes())
            } finally { envelope.writeBytes(envelopeBefore) }
            assertFalse(receiptPath.exists())
            assertArrayEquals(syntheticAttempt, latch.readBytes())
        } finally { assertTrue(latch.delete()) } // CI-only fixture, never production recovery.
        assertTrue(runCatching { reconciliation.inspectAttempt(b.projectId, b) }.isFailure)
        val pending = before.pendingWork.single { it.id == b.workId }
        val sourceRecord = before.records.single { it.submissionId == b.sourceSubmissionId }
        val fakeChild = sourceRecord.copy(submissionId = java.util.UUID.randomUUID().toString(),
            instanceId = b.reservedInstanceId, formVersionId = b.targetFormVersionId)
        fun classification(snapshot: RemoteRecordSnapshot) =
            ProtectedSubmissionReceiptStore.classifySnapshot(b, snapshot).first
        assertEquals(ProtectedReconciliationState.INDETERMINATE,
            classification(before.copy(pendingWork = before.pendingWork.filter { it.id != b.workId })))
        assertEquals(ProtectedReconciliationState.INDETERMINATE,
            classification(before.copy(records = before.records.filter { it.submissionId != b.sourceSubmissionId })))
        assertEquals(ProtectedReconciliationState.CONFLICT,
            classification(before.copy(records = before.records + sourceRecord)))
        assertEquals(ProtectedReconciliationState.CONFLICT,
            classification(before.copy(pendingWork = before.pendingWork + pending)))
        assertEquals(ProtectedReconciliationState.CONFLICT,
            classification(before.copy(records = before.records + fakeChild)))
        val completed = before.copy(pendingWork = before.pendingWork.filter { it.id != b.workId })
        assertEquals(ProtectedReconciliationState.CONFLICT,
            classification(completed.copy(records = completed.records +
                fakeChild.copy(formVersionId = sourceRecord.formVersionId))))
        assertEquals(ProtectedReconciliationState.CONFLICT,
            classification(completed.copy(records = completed.records + fakeChild +
                fakeChild.copy(submissionId = java.util.UUID.randomUUID().toString()))))
        assertEquals(ProtectedReconciliationState.CONFLICT,
            classification(before.copy(pendingWork = before.pendingWork.map {
                if (it.id == b.workId) it.copy(visitKey = "wrong-visit") else it
            })))
        assertArrayEquals(envelopeBefore, envelope.readBytes())
        assertArrayEquals(pointerBefore, pointer.readBytes())
        assertEquals(workingBefore, inventory(work))
        assertEquals(committedBefore, inventory(File(state.area, generation)))
        phase("CONFIRM_UI")
        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
            QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                if (!visible(activity, views, R.string.private_finalization_complete)) false
                else {
                    val field = views.filterIsInstance<EditText>().single()
                    val originalText = field.text.toString()
                    field.setText("Synthetic unsaved submission edit")
                    views.filterIsInstance<Button>().single {
                        it.text.toString() == activity.getString(R.string.private_submission_action)
                    }.performClick()
                    assertTrue(visible(activity, views, R.string.private_combined_unsaved))
                    field.setText(originalText)
                    true
                }
            }
            assertFalse(latch.exists())
            clickButton(editor, R.string.private_submission_action)
            dialog(android.R.id.button2)
            assertFalse(latch.exists())
            assertEquals(before.records.toSet(), remote.load(b.projectId).records.toSet())
            phase("POST")
            clickButton(editor, R.string.private_submission_action)
            dialog(android.R.id.button1)
            awaitUi(editor) { activity, views ->
                if (!visible(activity, views, R.string.private_submission_complete)) false
                else { assertReadOnly(activity, views); true }
            }
        }
        val receipt = ProtectedSubmissionReceiptStore(root, state.settings).reopen(b.projectId, b)
        phase("ACK")
        assertEquals(b.reservedInstanceId, receipt.instanceId)
        assertEquals(sha, receipt.packageSha256)
        assertEquals(generation, receipt.generation)
        assertFalse(receipt.canEdit || receipt.canUpload)
        assertTrue(latch.isFile && latch.length() > 30)
        val latchBefore = latch.readBytes()
        phase("RECONCILE")
        val after = remote.load(b.projectId)
        val child = after.records.single { it.instanceId == b.reservedInstanceId }
        assertNotEquals(b.sourceSubmissionId, child.submissionId)
        assertEquals(receipt.submissionId, child.submissionId)
        assertEquals(b.targetFormVersionId, child.formVersionId)
        assertEquals(original.formId, child.formId)
        assertEquals(before.records.toSet() + child, after.records.toSet())
        assertEquals(original, after.records.single { it.submissionId == b.sourceSubmissionId })
        assertEquals(before.pendingWork.filter { it.id != b.workId }.toSet(), after.pendingWork.toSet())
        phase("RECONCILIATION_INSPECTION")
        // Simulate missing acknowledgement locally after the actual server
        // commit. Inspection must corroborate the source timeline but must
        // not write a new receipt, unlock editing or retransmit.
        val receiptBytes = receiptPath.readBytes()
        val heldReceipt = File(state.area, ".ci-held-receipt-for-inspection")
        assertTrue(receiptPath.renameTo(heldReceipt))
        try {
            val linked = reconciliation.inspectAttempt(b.projectId, b)
            assertEquals(ProtectedReconciliationState.LINKED_RECORD_VISIBLE, linked.state)
            assertEquals(child.submissionId, linked.submissionId)
            assertEquals(generation, linked.generation)
            assertEquals(sha, linked.packageSha256)
            assertEquals(digest(latchBefore), linked.attemptSha256)
            assertFalse(linked.canEdit || linked.canUpload)
            assertFalse(receiptPath.exists())
            assertArrayEquals(latchBefore, latch.readBytes())
            assertEquals(after, remote.load(b.projectId))
        } finally { assertTrue(heldReceipt.renameTo(receiptPath)) }
        assertArrayEquals(receiptBytes, receiptPath.readBytes())
        phase("REPLAY")
        assertTrue(runCatching { sender.send(b.projectId, b, generation, sha) }.isFailure)
        assertEquals(after.records.toSet(), remote.load(b.projectId).records.toSet())
        assertArrayEquals(latchBefore, latch.readBytes())
        assertArrayEquals(envelopeBefore, envelope.readBytes())
        assertArrayEquals(pointerBefore, pointer.readBytes())
        assertEquals(workingBefore, inventory(work))
        assertEquals(committedBefore, inventory(File(state.area, generation)))
        assertEquals(b, QuarantinedWorkDraftVault(root).read(b.projectId, b.workId, b.actorContextSha256))
        phase("ORACLE")
        val oracle = org.json.JSONObject()
            .put("work", b.workId).put("source", b.sourceSubmissionId)
            .put("child", child.submissionId).put("reserved", b.reservedInstanceId)
            .put("target", b.targetFormVersionId).put("visit", b.visitKey)
            .put("xmlSha256", saved.evidence.packaged.xml.xmlSha256)
            .put("xmlBytes", saved.evidence.packaged.xml.xmlBytes)
            .put("packageSha256", sha)
        val attachments = org.json.JSONArray()
        saved.evidence.packaged.attachments.values.sortedBy { it.filename }.forEach {
            attachments.put(org.json.JSONObject().put("filename", it.filename)
                .put("mime", it.mime).put("bytes", it.bytes).put("sha256", it.sha256))
        }
        oracle.put("attachments", attachments)
        // Synthetic CI-only oracle: identifiers, lengths and hashes, never
        // answers, credentials, XML or media bytes.
        java.io.FileOutputStream(File(root, "u06-native-submission-oracle.json")).use {
            it.write(oracle.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        val receiptFile = File(state.area, "submitted-receipt.aesgcm")
        assertTrue(receiptFile.isFile)
        assertEquals(receipt, ProtectedSubmissionReceiptStore(root, state.settings).reopen(b.projectId, b))
        state.evidence.setProperty("receiptDigest", digest(receiptFile.readBytes()))
        state.evidence.setProperty("submittedChild", child.submissionId)
        state.evidence.setProperty("attemptDigest", digest(latchBefore))
        state.evidence.setProperty("submittedPid", android.os.Process.myPid().toString())
        java.io.FileOutputStream(state.marker).use {
            state.evidence.store(it, "Synthetic submitted observation identity")
            it.fd.sync()
        }
    }

    @Test
    fun submittedReceiptRecoversAfterForceStopAndRejectsCorruption() {
        val state = publicationContext()
        val b = state.binding
        val root = state.context.noBackupFilesDir
        assertNotEquals(state.required("submittedPid"), android.os.Process.myPid().toString())
        val store = ProtectedSubmissionReceiptStore(root, state.settings)
        val file = File(state.area, "submitted-receipt.aesgcm")
        val attempt = File(state.area, "submission-attempt.aesgcm")
        val envelope = File(state.area, "finalized-" + state.required("generation") + ".aesgcm")
        val original = file.readBytes()
        val latch = attempt.readBytes()
        val sealed = envelope.readBytes()
        assertEquals(state.required("receiptDigest"), digest(original))
        val r = store.reopen(b.projectId, b)
        assertEquals(state.required("submittedChild"), r.submissionId)
        assertEquals(state.required("generation"), r.generation)
        assertEquals(state.required("packageDigest"), r.packageSha256)
        assertEquals(state.required("attemptDigest"), r.attemptSha256)
        assertEquals(state.required("finalizedDigest"), r.ciphertextSha256)
        assertFalse(r.canEdit || r.canUpload)
        assertTrue(runCatching { store.reopen(b.projectId, b.copy(actorContextSha256 = "0".repeat(64))) }.isFailure)
        for (target in listOf(file, attempt, envelope)) {
            val bytes = target.readBytes()
            val corrupted = bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            target.writeBytes(corrupted)
            try {
                assertTrue(runCatching { store.reopen(b.projectId, b) }.isFailure)
                assertArrayEquals(corrupted, target.readBytes())
            } finally { target.writeBytes(bytes) }
        }
        val missing = File(state.area, ".ci-receipt-held")
        assertTrue(file.renameTo(missing))
        try { assertTrue(runCatching { store.reopen(b.projectId, b) }.isFailure) }
        finally { assertTrue(missing.renameTo(file)) }
        assertEquals(r, store.reopen(b.projectId, b))
        // Actual graphical reopening after the process boundary, no edit UI.
        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
            QuarantinedCombinedDraftActivity.intentFor(state.context, b.projectId, b.workId)
        ).use { editor ->
            awaitUi(editor) { activity, views ->
                val submitted = views.filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.private_submission_complete)
                }
                if (submitted) {
                    assertTrue(views.filterIsInstance<EditText>().isEmpty())
                    assertTrue(views.filterIsInstance<TextView>().any {
                        it.text.toString() == activity.getString(R.string.private_submission_reference, r.submissionId)
                    })
                    assertTrue(views.filterIsInstance<Button>().none {
                        it.text.toString() == activity.getString(R.string.private_submission_action)
                    })
                }
                submitted
            }
            clickButton(editor, R.string.private_submission_refresh)
            awaitUi(editor) { activity, views ->
                views.filterIsInstance<TextView>().any {
                    it.text.toString() == activity.getString(R.string.private_submission_complete)
                }
            }
        }
        // READY-authorized edit/finalize/send must fail for completed work.
        assertTrue(runCatching {
            ProtectedNativeRepeatImageProbe(state.context.cacheDir, root, state.settings)
                .saveDirectText(b.projectId, b, mapOf("/data/household_name" to "Synthetic prohibited edit"))
        }.isFailure)
        assertTrue(runCatching {
            ProtectedCommittedDraftStore(state.context.cacheDir, root, state.settings)
                .reopenFinalizedPackage(b.projectId, b, r.generation)
        }.isFailure)
        assertTrue(runCatching {
            ProtectedMultipartSubmission(state.context.cacheDir, root, state.settings)
                .send(b.projectId, b, r.generation, r.packageSha256)
        }.isFailure)
        assertArrayEquals(original, file.readBytes())
        assertArrayEquals(latch, attempt.readBytes())
        assertArrayEquals(sealed, envelope.readBytes())
        assertEquals(state.required("pointerDigest"), digest(File(state.area, "current.aesgcm").readBytes()))
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
