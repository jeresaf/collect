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
