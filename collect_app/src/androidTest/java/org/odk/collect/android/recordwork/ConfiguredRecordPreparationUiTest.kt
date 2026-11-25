package org.odk.collect.android.recordwork

import android.view.View
import android.content.Intent
import android.widget.LinearLayout
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.android.support.TestDependencies
import org.odk.collect.android.support.rules.CollectTestRule
import org.odk.collect.android.support.rules.TestRuleChain
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.shared.settings.Settings
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.URL
import java.util.UUID
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * U06-T04D-03B2A: real configured ODK project + real preparation Activity.
 *
 * This exercises Android's genuine project provider and rendered action
 * controls after the same metadata-only Intent production browser uses.
 * No network call, target selection, XForm editor or submission is claimed.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguredRecordPreparationUiTest {
    private val rule = CollectTestRule(false)
    private val dependencies = TestDependencies()

    @get:Rule
    val chain: RuleChain = TestRuleChain.chain(dependencies).around(rule)

    private val source = "81000000-0000-4000-8000-000000000007"
    private val corrected = "81000000-0000-4000-8000-000000000008"

    @Test
    fun historicalRecordOffersIndependentVisitButNeverAmendment() {
        configureProject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectId = activeProjectId()
        val record = RemoteRecord(
            submissionId = source,
            instanceId = "uuid:81000000-0000-4000-8000-000000000017",
            formId = "u06-synthetic-household",
            formVersionId = "81000000-0000-4000-8000-000000000003",
            submittedAt = "2026-11-23T12:00:00Z",
            amendmentRevision = RemoteAmendmentRevision.SUPERSEDED
        )
        val selected = RecordWorkNavigation.record(context, projectId, record)
        assertFalse(selected.getBooleanExtra(
            RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND, true))
        ActivityScenario.launch<RecordWorkPrepareActivity>(selected).use { scenario ->
            fun assertRendered(activity: RecordWorkPrepareActivity) {
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_amend).visibility)
                assertFalse(
                    activity.findViewById<Button>(R.id.work_amend).isEnabled)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.work_followup).visibility)
                assertTrue(activity.findViewById<Button>(R.id.work_followup).isEnabled)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_create).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_visit_key).visibility)
                assertTrue(activity.findViewById<TextView>(R.id.work_status)
                    .text.toString().contains("independent follow-up"))
                assertFalse(activity.findViewById<TextView>(R.id.work_status)
                    .text.toString().contains("PRIVATE_ANSWER"))
            }
            scenario.onActivity(::assertRendered)
            scenario.recreate()
            scenario.onActivity(::assertRendered)
        }
    }

    @Test
    fun currentRevisionShowsAmendmentChoiceWithoutPreauthorizingSubmission() {
        configureProject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = RemoteRecord(
            submissionId = corrected,
            instanceId = "uuid:81000000-0000-4000-8000-000000000018",
            formId = "u06-synthetic-household",
            formVersionId = "81000000-0000-4000-8000-000000000004",
            submittedAt = "2026-11-23T13:00:00Z",
            amendmentRevision = RemoteAmendmentRevision.CURRENT
        )
        val selected = RecordWorkNavigation.record(context, activeProjectId(), record)
        assertTrue(selected.getBooleanExtra(
            RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND, false))
        ActivityScenario.launch<RecordWorkPrepareActivity>(selected).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.work_amend).visibility)
                assertTrue(activity.findViewById<Button>(R.id.work_amend).isEnabled)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.work_followup).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_create).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_visit_key).visibility)
                assertEquals(0,
                    (activity.findViewById<View>(R.id.work_versions)
                        as android.widget.LinearLayout).childCount)
            }
        }
    }


    /**
     * U06-T04D-03B2B: genuine owned V1/V2 records fetched over TLS from the
     * seeded Spring/PostgreSQL service are rendered into production Android
     * record buttons, selected, and displayed in a real preparation Activity.
     * The test uses separate CI-scoped TLS trust/credentials for discovery;
     * it does NOT claim the configured ODK project is authenticated against
     * the backend nor that the complete browser Activity performed the GET.
     */
    @Test
    fun genuineBackendRecordSelectionOpensRealPreparationActivity() {
        configureProject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localProject = activeProjectId()
        val owned = connectedClient().load("81000000-0000-4000-8000-000000000001")
        val historical = owned.records.single { it.submissionId == source }
        val current = owned.records.single { it.submissionId == corrected }
        assertEquals(RemoteAmendmentRevision.SUPERSEDED, historical.amendmentRevision)
        assertEquals(RemoteAmendmentRevision.CURRENT, current.amendmentRevision)

        for (record in listOf(historical, current)) {
            var clicked: RemoteRecord? = null
            val row = RecordWorkRecordRow.create(context, record) { clicked = it }
            assertTrue(row.performClick())
            assertEquals(record, clicked)
            val intent = RecordWorkNavigation.record(context, localProject, requireNotNull(clicked))
            assertEquals(record.submissionId,
                intent.getStringExtra(RecordWorkPrepareActivity.EXTRA_SOURCE))
            ActivityScenario.launch<RecordWorkPrepareActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(
                        if (record.mayOfferAmendment) View.VISIBLE else View.GONE,
                        activity.findViewById<View>(R.id.work_amend).visibility
                    )
                    assertEquals(View.VISIBLE,
                        activity.findViewById<View>(R.id.work_followup).visibility)
                    assertEquals(View.GONE,
                        activity.findViewById<View>(R.id.work_create).visibility)
                    assertEquals(View.GONE,
                        activity.findViewById<View>(R.id.work_visit_key).visibility)
                    assertEquals(0,
                        (activity.findViewById<View>(R.id.work_versions)
                            as android.widget.LinearLayout).childCount)
                    assertFalse(activity.findViewById<TextView>(R.id.work_status)
                        .text.toString().contains("Synthetic V1"))
                }
            }
        }
    }


    /**
     * U06-T04D-04A: a fully configured Android project performs the genuine
     * authenticated GET inside the real RecordWorkBrowserActivity. The
     * operator then taps the historical V1 row and Android launches the
     * production preparation Activity, with AMENDMENT still unavailable.
     *
     * Ephemeral CA trust is injected into the *debug APK only* by CI before
     * compilation. Release network trust configuration is unchanged.
     */
    @Test
    fun realBrowserActivityFetchesServerRowsAndOpensHistoricalPrepareActivity() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<RecordWorkBrowserActivity>(
            Intent(context, RecordWorkBrowserActivity::class.java)
        ).use { scenario ->
            var discovered = false
            for (attempt in 1..80) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val container = activity.findViewById<LinearLayout>(
                        R.id.remote_records_list
                    )
                    val labels = (0 until container.childCount)
                        .map { container.getChildAt(it) }
                        .filterIsInstance<Button>()
                        .map { it.text.toString() }
                    discovered = labels.any { it.contains(source) &&
                        it.contains("Historical record") } &&
                        labels.any { it.contains(corrected) && it.contains("server rechecks") }
                }
                if (discovered) break
                Thread.sleep(250)
            }
            assertTrue(
                "The actual browser must fetch both owned V1/V2 records through app HTTPS",
                discovered
            )
            // Not a synthetic component callback: Espresso taps the real
            // dynamically rendered record row inside RecordWorkBrowserActivity.
            onView(allOf(
                withText(containsString(source)),
                withText(containsString("Historical record")),
                androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(Button::class.java),
                androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA(
                    withId(R.id.remote_records_list)
                )
            )).perform(click())
            onView(withId(R.id.work_followup)).check(matches(isDisplayed()))
            onView(withId(R.id.work_create)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                    androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
                )
            ))
            onView(withId(R.id.work_amend)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                    androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
                )
            ))
        }
    }


    /**
     * U06-T04D-04B1: real server target discovery through the configured
     * ODK account. A superseded record may choose a NEW independent visit
     * with server-approved target versions, but no READY work is created
     * until the operator explicitly confirms a valid visit key.
     */
    @Test
    fun supersededRecordCanReviewAuthorizedFollowUpTargetsWithoutCreatingWork() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalRecord = connectedClient()
            .load("81000000-0000-4000-8000-000000000001")
            .records.single { it.submissionId == source }
        assertFalse(originalRecord.mayOfferAmendment)
        val before = connectedClient().load("81000000-0000-4000-8000-000000000001")
            .pendingWork.map { it.id }.toSet()
        val navigation = RecordWorkNavigation.record(context, activeProjectId(), originalRecord)
        ActivityScenario.launch<RecordWorkPrepareActivity>(navigation).use { scenario ->
            onView(withId(R.id.work_amend)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                    androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
                )
            ))
            onView(withId(R.id.work_create)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                    androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
                )
            ))
            onView(withId(R.id.work_followup)).perform(click())
            var targetsReady = false
            for (attempt in 1..80) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val targets = activity.findViewById<LinearLayout>(R.id.work_versions)
                    targetsReady = targets.childCount > 0 &&
                        (0 until targets.childCount).all { index ->
                            val item = targets.getChildAt(index)
                            item is Button && item.text.toString().contains("Version ")
                        }
                    if (targetsReady) {
                        assertEquals(View.GONE,
                            activity.findViewById<View>(R.id.work_create).visibility)
                        assertEquals(View.GONE,
                            activity.findViewById<View>(R.id.work_visit_key).visibility)
                        assertEquals(View.GONE,
                            activity.findViewById<View>(R.id.work_amend).visibility)
                    }
                }
                if (targetsReady) break
                Thread.sleep(250)
            }
            assertTrue("Server must return authorized follow-up target form versions", targetsReady)
            scenario.onActivity { activity ->
                val targets = activity.findViewById<LinearLayout>(R.id.work_versions)
                // User chooses a server-reported target; do NOT click CREATE.
                assertTrue((targets.getChildAt(0) as Button).performClick())
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.work_create).visibility)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.work_visit_key).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_amend).visibility)
            }
        }
        val after = connectedClient().load("81000000-0000-4000-8000-000000000001")
            .pendingWork.map { it.id }.toSet()
        assertEquals("Browsing target versions must never POST work", before, after)
    }


    /**
     * U06-T04D-04B2: user-confirmed FOLLOW_UP creation through the real
     * preparation Activity against the real Spring/Postgres work API.
     * Explicit approval must create exactly one server-owned READY work item
     * with a unique visit key, but no new submitted household observation.
     */
    @Test
    fun confirmedSecondVisitCreatesExactlyOneReadyWorkWithoutSubmittingObservation() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val backendProject = "81000000-0000-4000-8000-000000000001"
        val before = connectedClient().load(backendProject)
        val record = before.records.single { it.submissionId == source }
        assertEquals(RemoteAmendmentRevision.SUPERSEDED, record.amendmentRevision)
        val visit = "u06-ui-create-" + UUID.randomUUID().toString()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<RecordWorkPrepareActivity>(
            RecordWorkNavigation.record(context, activeProjectId(), record)
        ).use { scenario ->
            onView(withId(R.id.work_amend)).check(matches(
                androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                    androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE
                )
            ))
            onView(withId(R.id.work_followup)).perform(click())
            var ready = false
            for (attempt in 1..80) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val versions = activity.findViewById<LinearLayout>(R.id.work_versions)
                    ready = (0 until versions.childCount).any {
                        val item = versions.getChildAt(it)
                        item is Button && item.text.toString().contains("Version ")
                    }
                }
                if (ready) break
                Thread.sleep(250)
            }
            assertTrue("Server must authorize at least one follow-up target", ready)
            scenario.onActivity { activity ->
                val buttons = activity.findViewById<LinearLayout>(R.id.work_versions)
                val chosen = (0 until buttons.childCount)
                    .map { buttons.getChildAt(it) }
                    .filterIsInstance<Button>()
                    .firstOrNull { it.text.toString().contains("(latest)") }
                    ?: (buttons.getChildAt(0) as Button)
                assertTrue(chosen.performClick())
                val input = activity.findViewById<EditText>(R.id.work_visit_key)
                assertEquals(View.VISIBLE, input.visibility)
                input.setText(visit)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.work_create).visibility)
            }
            val unconfirmed = connectedClient().load(backendProject).pendingWork
            assertEquals("Target selection and visit-key entry cannot create work",
                before.pendingWork.map { it.id }.toSet(),
                unconfirmed.map { it.id }.toSet())
            onView(withId(R.id.work_create)).perform(click())
            // Material confirmation is mandatory; an open dialog has no
            // backend side effect until the operator explicitly approves.
            assertEquals(before.pendingWork.map { it.id }.toSet(),
                connectedClient().load(backendProject).pendingWork.map { it.id }.toSet())
            onView(withText(android.R.string.ok)).perform(click())

            var created: RemoteVisitWork? = null
            for (attempt in 1..120) {
                val pending = connectedClient().load(backendProject).pendingWork
                created = pending.singleOrNull { it.visitKey == visit }
                if (created != null) break
                Thread.sleep(250)
            }
            val confirmed = requireNotNull(created) {
                "User-approved visit must appear as server-owned READY follow-up"
            }
            assertEquals(source, confirmed.sourceSubmissionId)
            assertEquals(RecordWorkType.FOLLOW_UP, confirmed.type)
            assertTrue(UUID.fromString(confirmed.id).toString() == confirmed.id)
            assertEquals(visit, confirmed.visitKey)
            assertFalse(confirmed.id in before.pendingWork.map { it.id }.toSet())
            assertEquals(1, connectedClient().load(backendProject)
                .pendingWork.count { it.visitKey == visit })
            // The original household remains historical and untouched.
            assertEquals(RemoteAmendmentRevision.SUPERSEDED,
                connectedClient().load(backendProject).records
                    .single { it.submissionId == source }.amendmentRevision)
        }
    }


    /**
     * U06-T04E-01: an authorized server READY work item alone MUST NOT grant
     * access to the protected editor. Until the operator approves mappings,
     * JavaRosa checks the target and an inert, actor-bound quarantine entry
     * exists, opening the editor via a forged Intent must fail closed.
     *
     * The preceding connected test creates this server-owned READY work;
     * the test runner executes these full first-launch methods sequentially
     * as separate Android instrumentation processes with a cleared profile.
     */
    @Test
    fun serverReadyFollowUpWithoutLocalApprovalCannotOpenProtectedEditor() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val project = "81000000-0000-4000-8000-000000000001"
        val snapshot = connectedClient().load(project)
        val pending = snapshot.pendingWork.single {
            it.type == RecordWorkType.FOLLOW_UP &&
                it.visitKey?.startsWith("u06-ui-create-") == true
        }
        assertEquals(source, pending.sourceSubmissionId)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(null, QuarantinedWorkDraftVault(context.noBackupFilesDir)
            .read(activeProjectId(), pending.id,
                QuarantinedWorkDraftVault.sha256(
                    activeProjectId() + "\n" +
                    RecordWorkApiPaths.fromProjectUrl(
                        "https://10.0.2.2:8443/v1/projects/$project"
                    ).records() + "\n" + "u06-connected-owner"
                )))
        ActivityScenario.launch<QuarantinedTextDraftActivity>(
            QuarantinedTextDraftActivity.intentFor(context, activeProjectId(), pending.id)
        ).use { scenario ->
            // Even though the work ID exists server-side, no reviewed binding
            // exists on this device. The editor cannot expose any questions,
            // private answers or a Save/Finalize/Send route.
            onView(withText(R.string.private_text_editor_save))
                .check(matches(androidx.test.espresso.matcher.ViewMatchers.isNotEnabled()))
            onView(withText(R.string.private_text_editor_never_submit))
                .check(matches(isDisplayed()))
        }
        assertEquals(snapshot.pendingWork.map { it.id }.toSet(),
            connectedClient().load(project).pendingWork.map { it.id }.toSet())
    }


    /**
     * U06-T04E-02A: real READY work must undergo the actual Android mapping
     * review, user confirmation and server reauthorization before a local
     * actor-bound quarantine is staged. Selecting REENTER for every target
     * field prevents any old-household answer from being copied.
     *
     * This is NOT a native ODK Instance, finalized draft or permission to
     * upload. It only grants the separate private editor a binding to check.
     */
    @Test
    fun reviewedFollowUpStagesOnlyAfterEveryTargetFieldAndExplicitApproval() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val project = "81000000-0000-4000-8000-000000000001"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = connectedClient().load(project)
        val pending = before.pendingWork.single {
            it.type == RecordWorkType.FOLLOW_UP &&
                it.visitKey?.startsWith("u06-ui-create-") == true
        }
        reviewAndStageFollowUp(project, pending, openEditor = false)
    }

    /**
     * U06-T04E-02B: qualify the actual private scalar editor only on an
     * eligible scalar-only XForm. The V2 repeat/media form remains outside
     * this editor's supported scope. The V1 published target is separately
     * authorized by the server, even though its source is historical.
     */
    @Test
    fun approvedScalarFollowUpOpensAndSavesNewAnswersWithoutUploadRights() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val project = "81000000-0000-4000-8000-000000000001"
        val localProject = activeProjectId()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = RecordWorkPreparationClient(
            DaggerUtils.getComponent(context).settingsProvider()
        )
        val target = "81000000-0000-4000-8000-000000000003"
        assertTrue(client.targets(localProject, source, RecordWorkType.FOLLOW_UP)
            .any { it.targetFormVersionId == target && it.version == "1" })
        val visitKey = "u06-scalar-editor-" + UUID.randomUUID()
        val created = client.create(localProject, NewRecordWork(
            source, target, RecordWorkType.FOLLOW_UP,
            "u06-scalar-editor-request-" + UUID.randomUUID(), visitKey
        ))
        val pending = connectedClient().load(project).pendingWork.single {
            it.id == created.id
        }
        assertEquals(target, pending.targetFormVersionId)
        assertEquals(visitKey, pending.visitKey)
        reviewAndStageFollowUp(project, pending, openEditor = true)
    }

    @Test
    fun reviewedConditionalFollowUpValidatesNativeRelevanceAndCalculations() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val project = "81000000-0000-4000-8000-000000000001"
        val localProject = activeProjectId()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = RecordWorkPreparationClient(
            DaggerUtils.getComponent(context).settingsProvider()
        )
        val target = "81000000-0000-4000-8000-000000000019"
        assertTrue(client.targets(localProject, source, RecordWorkType.FOLLOW_UP)
            .any { it.targetFormVersionId == target && it.version == "3" })
        val visitKey = "u06-conditional-" + UUID.randomUUID()
        val created = client.create(localProject, NewRecordWork(
            source, target, RecordWorkType.FOLLOW_UP,
            "u06-conditional-request-" + UUID.randomUUID(), visitKey
        ))
        val pending = connectedClient().load(project).pendingWork.single {
            it.id == created.id
        }
        assertEquals(target, pending.targetFormVersionId)
        assertEquals(visitKey, pending.visitKey)
        reviewAndStageFollowUp(project, pending, openEditor = false, conditionalExercise = true)
    }

    @Test
    fun reviewedRepeatImagesRetainUuidAcrossArchiveAndOrdinalChange() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val project = "81000000-0000-4000-8000-000000000001"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = RecordWorkPreparationClient(DaggerUtils.getComponent(context).settingsProvider())
        val target = "81000000-0000-4000-8000-000000000020"
        assertTrue(client.targets(activeProjectId(), source, RecordWorkType.FOLLOW_UP)
            .any { it.targetFormVersionId == target && it.version == "4" })
        val created = client.create(activeProjectId(), NewRecordWork(
            source, target, RecordWorkType.FOLLOW_UP,
            "u06-repeat-image-request-" + UUID.randomUUID(),
            "u06-repeat-image-" + UUID.randomUUID()
        ))
        val pending = connectedClient().load(project).pendingWork.single { it.id == created.id }
        reviewAndStageFollowUp(project, pending, openEditor = false, repeatImageExercise = true)
    }

    @Test
    fun reviewedMixedDraftRebuildsDirectAndRepeatAnswersAndImages() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://10.0.2.2:8443/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-connected-owner")
            .inputPassword("u06-disposable-only")
            .addProject()
        val project = "81000000-0000-4000-8000-000000000001"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = RecordWorkPreparationClient(DaggerUtils.getComponent(context).settingsProvider())
        val target = "81000000-0000-4000-8000-000000000021"
        assertTrue(client.targets(activeProjectId(), source, RecordWorkType.FOLLOW_UP)
            .any { it.targetFormVersionId == target && it.version == "5" })
        val created = client.create(activeProjectId(), NewRecordWork(
            source, target, RecordWorkType.FOLLOW_UP,
            "u06-mixed-request-" + UUID.randomUUID(), "u06-mixed-" + UUID.randomUUID()))
        val pending = connectedClient().load(project).pendingWork.single { it.id == created.id }
        reviewAndStageFollowUp(project, pending, openEditor = false, mixedExercise = true)
    }

    private fun reviewAndStageFollowUp(
        project: String, pending: RemoteVisitWork, openEditor: Boolean,
        conditionalExercise: Boolean = false,
        repeatImageExercise: Boolean = false,
        mixedExercise: Boolean = false
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = connectedClient().load(project)
        val scope = QuarantinedWorkDraftVault.sha256(
            activeProjectId() + "\n" +
                RecordWorkApiPaths.fromProjectUrl(
                    "https://10.0.2.2:8443/v1/projects/$project"
                ).records() + "\n" + "u06-connected-owner"
        )
        val vault = QuarantinedWorkDraftVault(context.noBackupFilesDir)
        assertEquals(null, vault.read(activeProjectId(), pending.id, scope))
        verifyAndroidXmlAdmissionRejectsUnsafeInputs()
        verifyConnectedPreparationLayers(project, pending, context.cacheDir)
        val selection = RecordWorkNavigation.pending(context, activeProjectId(), pending)
        ActivityScenario.launch<RecordWorkPrepareActivity>(selection).use { scenario ->
            var candidateCount = -1
            for (attempt in 1..120) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<LinearLayout>(R.id.work_versions)
                    val views = (0 until rows.childCount)
                        .map { rows.getChildAt(it) }
                    val stageLabel = activity.getString(R.string.mapping_quarantine_stage)
                    val stage = views.filterIsInstance<Button>()
                        .singleOrNull { it.text.toString() == stageLabel }
                    if (stage != null) {
                        // One review prompt + N candidates + one stage button +
                        // the non-editable quarantine warning.
                        candidateCount = views.filterIsInstance<Button>().size - 1
                        assertFalse(stage.isEnabled)
                    }
                }
                if (candidateCount >= 0) break
                Thread.sleep(250)
            }
            if (candidateCount <= 0) {
                // Report a restricted status classification only, never a
                // server response, source XML, URL, form text or credentials.
                var statusCode = "UNKNOWN"
                var childCount = -1
                scenario.onActivity { activity ->
                    val text = activity.findViewById<TextView>(R.id.work_status).text.toString()
                    statusCode = when (text) {
                        activity.getString(R.string.work_failed_server) -> "FAILED_SERVER"
                        activity.getString(R.string.work_failed_network) -> "FAILED_NETWORK"
                        activity.getString(R.string.work_failed_conflict) -> "FAILED_CONFLICT"
                        activity.getString(R.string.work_failed_access) -> "FAILED_ACCESS"
                        activity.getString(R.string.work_failed_auth) -> "FAILED_AUTH"
                        activity.getString(R.string.work_created) -> "PREVIEW_CREATED"
                        activity.getString(R.string.work_loading) -> "LOADING"
                        else -> "OTHER"
                    }
                    childCount = activity.findViewById<LinearLayout>(
                        R.id.work_versions
                    ).childCount
                }
                throw AssertionError(
                    "No verified mapping review; statusCode=$statusCode, rows=$childCount"
                )
            }
            assertTrue("Review must stay within visible safe bound", candidateCount <= 30)
            assertEquals(null, vault.read(activeProjectId(), pending.id, scope))
            for (index in 0 until candidateCount) {
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<LinearLayout>(R.id.work_versions)
                    // The review prompt is a TextView at position zero.
                    val item = rows.getChildAt(index + 1) as Button
                    assertTrue(item.performClick())
                }
                // Explicitly REENTER every path: no amendment/old answers copied
                // into an independent second visit.
                // MaterialAlertDialogBuilder.setItems uses an adapter-backed list.
                // Resolve its first item inside the dialog and verify the exact
                // action label before clicking; don't search the activity root.
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything())
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .atPosition(0)
                    .check(matches(withText(R.string.mapping_review_reenter)))
                    .perform(click())
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<LinearLayout>(R.id.work_versions)
                    val item = rows.getChildAt(index + 1) as Button
                    assertTrue("Review action must be recorded for this target field",
                        item.text.toString().endsWith("\n" + MappingDecision.REENTER.name))
                }
            }
            scenario.onActivity { activity ->
                val rows = activity.findViewById<LinearLayout>(R.id.work_versions)
                val stage = (0 until rows.childCount)
                    .map { rows.getChildAt(it) }
                    .filterIsInstance<Button>()
                    .single { it.text.toString() ==
                        activity.getString(R.string.mapping_quarantine_stage) }
                assertTrue("Complete review should activate quarantined staging",
                    stage.isEnabled)
                assertTrue(stage.performClick())
            }
            assertEquals(null, vault.read(activeProjectId(), pending.id, scope))
            // The Material positive button is transformed/themed, so its
            // displayed label alone is not a stable Espresso selector.
            // Require the actual quarantine confirmation dialog, then tap
            // Android's positive button inside that same dialog root.
            onView(withText(R.string.mapping_quarantine_title))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(matches(isDisplayed()))
            onView(withId(android.R.id.button1))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(matches(isDisplayed()))
                .perform(click())
            var staged: QuarantinedWorkDraft? = null
            for (attempt in 1..120) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                staged = vault.read(activeProjectId(), pending.id, scope)
                if (staged != null) break
                Thread.sleep(250)
            }
            val binding = requireNotNull(staged) {
                "Only explicitly reviewed and authorized work may be staged"
            }
            assertEquals(pending.id, binding.workId)
            assertEquals(source, binding.sourceSubmissionId)
            assertEquals(RecordWorkType.FOLLOW_UP, binding.workType)
            assertEquals(0, binding.validationTransfers)
            assertFalse(binding.canFinalize)
            assertFalse(binding.canUpload)
            assertFalse(binding.canOpenEditor)
            assertEquals(candidateCount, binding.reviewedPaths)
            assertEquals(pending.targetFormVersionId, binding.targetFormVersionId)
            if (!openEditor) {
                // U06-T04E-03C1: actual mixed V2 target, after real UI review
                // and encrypted approval persistence. The ordinary editor is
                // NOT launched and no native instance is registered.
                val settings = DaggerUtils.getComponent(context).settingsProvider()
                val authorized = RecordWorkPreparationClient(settings)
                    .authorizePrivateTextForm(activeProjectId(), binding)
                val approvals = ProtectedMappingApprovalStore(
                    context.noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                )
                var approvalReady = false
                for (attempt in 1..120) {
                    if (approvals.read(binding, authorized.plan) != null) {
                        approvalReady = true
                        break
                    }
                    Thread.sleep(250)
                }
                assertTrue("Encrypted mapping approval required", approvalReady)
                val probe = ProtectedNativeControllerProbe(
                    context.cacheDir, context.noBackupFilesDir, settings
                )
                if (mixedExercise) {
                    val session = JavaRosaPrivateRepeatAnswerSession(
                        context.cacheDir, binding, authorized.plan, authorized.targetXFormXml, true)
                    val cipher = AndroidKeyStorePrivateDraftCipher()
                    val rows = QuarantinedRepeatRowVault(context.noBackupFilesDir, cipher)
                    val images = QuarantinedRepeatImageStore(context.noBackupFilesDir, cipher, rows)
                    val answers = QuarantinedRepeatAnswerStore(context.noBackupFilesDir, cipher, rows)
                    val combined = ProtectedNativeRepeatImageProbe(
                        context.cacheDir, context.noBackupFilesDir, settings)
                    val direct = ProtectedNativeImageIntake(
                        context.cacheDir, context.noBackupFilesDir, settings)
                    val group = session.schema.groups.single()
                    val directText = session.schema.directTextPaths.single()
                    val directImage = session.schema.directImagePaths.single()
                    val name = group.fields.single().path
                    val photo = group.imagePaths.single()
                    assertEquals("/data/household_name", directText)
                    assertEquals("/data/photo", directImage)
                    assertEquals("/data/members/name", name)
                    assertEquals("/data/members/photo", photo)
                    assertTrue(runCatching {
                        JavaRosaPrivateRepeatAnswerSession(context.cacheDir, binding,
                            authorized.plan, authorized.targetXFormXml)
                    }.isFailure)
                    val a = rows.addRow(binding, session.schema, group.path, UUID.randomUUID().toString())
                    val b = rows.addRow(binding, session.schema, group.path, UUID.randomUUID().toString())
                    val household = "Synthetic household " + UUID.randomUUID()
                    val memberA = "Synthetic A " + UUID.randomUUID()
                    val memberB = "Synthetic B " + UUID.randomUUID()
                    combined.saveDirectText(activeProjectId(), binding, mapOf(directText to household))
                    answers.save(binding, session, a.rowId, mapOf(name to memberA))
                    answers.save(binding, session, b.rowId, mapOf(name to memberB))
                    fun png(color: Int): ByteArray {
                        val bitmap = android.graphics.Bitmap.createBitmap(16, 16,
                            android.graphics.Bitmap.Config.ARGB_8888)
                        return try {
                            bitmap.eraseColor(color)
                            java.io.ByteArrayOutputStream().use {
                                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                                it.toByteArray()
                            }
                        } finally { bitmap.recycle() }
                    }
                    val green = png(android.graphics.Color.GREEN)
                    val red = png(android.graphics.Color.RED)
                    val blue = png(android.graphics.Color.BLUE)
                    val dir = java.io.File(java.io.File(java.io.File(
                        context.noBackupFilesDir, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                        binding.projectId), binding.workId)
                    fun snapshot() = dir.walkTopDown().filter { it.isFile }.associate {
                        it.relativeTo(dir).path to QuarantinedPrivateImageStore.digest(it.readBytes())
                    }
                    fun hash(value: String) = QuarantinedWorkDraftVault.sha256(value)
                    try {
                        val directReceipt = direct.importSelectedImage(activeProjectId(), binding,
                            directImage, "image/png", green.inputStream())
                        val aReceipt = images.importSelectedImage(binding, session, a.rowId,
                            photo, "image/png", red.inputStream())
                        val bReceipt = images.importSelectedImage(binding, session, b.rowId,
                            photo, "image/png", blue.inputStream())
                        assertEquals(3, setOf(directReceipt.sha256, aReceipt.sha256, bReceipt.sha256).size)
                        val before = snapshot()
                        val evidence = combined.inspectCombined(activeProjectId(), binding, true)
                        assertEquals(mapOf(directText to hash(household)), evidence.directAnswerDigests)
                        assertEquals(mapOf(directImage to directReceipt.sha256), evidence.directImageDigests)
                        assertEquals(mapOf(a.rowId to mapOf(name to hash(memberA)),
                            b.rowId to mapOf(name to hash(memberB))), evidence.savedRowAnswerDigests)
                        assertEquals(mapOf((a.rowId to photo) to 0, (b.rowId to photo) to 1),
                            evidence.imageOrdinals)
                        assertEquals(before, snapshot())
                        val generations = ProtectedCommittedDraftStore(
                            context.cacheDir, context.noBackupFilesDir, settings)
                        assertNull(generations.reopen(activeProjectId(), binding))
                        // Real app entry control, not a direct test-only commit.
                        var launchReady = false
                        for (attempt in 1..120) {
                            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            scenario.onActivity { activity ->
                                val controls = activity.findViewById<LinearLayout>(R.id.work_versions)
                                launchReady = (0 until controls.childCount).map { controls.getChildAt(it) }
                                    .filterIsInstance<Button>().any {
                                        it.text.toString() == activity.getString(R.string.private_combined_open)
                                    }
                            }
                            if (launchReady) break
                            Thread.sleep(250)
                        }
                        assertTrue(launchReady)
                        ActivityScenario.launch<QuarantinedCombinedDraftActivity>(
                            QuarantinedCombinedDraftActivity.intentFor(context, activeProjectId(), binding.workId)
                        ).use { editor ->
                            fun awaitButton(label: Int) {
                                var ready = false
                                for (attempt in 1..120) {
                                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                                    editor.onActivity { activity ->
                                        ready = editorWidgets(activity.window.decorView)
                                            .filterIsInstance<Button>().any {
                                                it.text.toString() == activity.getString(label) && it.isEnabled
                                            }
                                    }
                                    if (ready) break
                                    Thread.sleep(250)
                                }
                                assertTrue(ready)
                            }
                            awaitButton(R.string.private_combined_publish)
                            editor.onActivity { activity ->
                                val widgets = editorWidgets(activity.window.decorView)
                                assertEquals(household, widgets.filterIsInstance<EditText>().single().text.toString())
                                assertTrue(widgets.filterIsInstance<TextView>().any {
                                    it.text.toString() == activity.getString(R.string.private_combined_no_checkpoint)
                                })
                                widgets.filterIsInstance<Button>().single {
                                    it.text.toString() == activity.getString(R.string.private_combined_publish)
                                }.performClick()
                            }
                            onView(withId(android.R.id.button2))
                                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                            assertNull(generations.reopen(activeProjectId(), binding))
                            editor.onActivity { activity ->
                                editorWidgets(activity.window.decorView).filterIsInstance<Button>().single {
                                    it.text.toString() == activity.getString(R.string.private_combined_publish)
                                }.performClick()
                            }
                            onView(withId(android.R.id.button1))
                                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
                            awaitButton(R.string.private_combined_publish)
                            val saved = requireNotNull(generations.reopen(activeProjectId(), binding))
                            editor.onActivity { activity ->
                                assertTrue(editorWidgets(activity.window.decorView)
                                    .filterIsInstance<TextView>().any {
                                        it.text.toString() == activity.getString(
                                            R.string.private_combined_checkpoint, saved.generation)
                                    })
                            }
                        }
                        val initialCommit = requireNotNull(generations.reopen(activeProjectId(), binding))
                        assertEquals(evidence, initialCommit.evidence)
                        assertEquals(initialCommit, generations.reopen(activeProjectId(), binding))
                        assertEquals(before, snapshot())
                        rows.archiveRow(binding, session.schema, a.rowId, UUID.randomUUID().toString())
                        val archived = snapshot()
                        val survivor = combined.inspectCombined(activeProjectId(), binding, true)
                        assertEquals(evidence.directAnswerDigests, survivor.directAnswerDigests)
                        assertEquals(evidence.directImageDigests, survivor.directImageDigests)
                        assertEquals(mapOf(b.rowId to mapOf(name to hash(memberB))),
                            survivor.savedRowAnswerDigests)
                        assertEquals(mapOf((b.rowId to photo) to 0), survivor.imageOrdinals)
                        assertEquals(archived, snapshot())
                        assertTrue(runCatching {
                            combined.saveDirectText(activeProjectId(), binding, mapOf(name to "invalid"))
                        }.isFailure)
                        assertEquals(archived, snapshot())
                        direct.clear(activeProjectId(), binding, directImage)
                        val cleared = snapshot()
                        val afterClear = combined.inspectCombined(activeProjectId(), binding, true)
                        assertTrue(afterClear.directImageDigests.isEmpty())
                        assertEquals(survivor.directAnswerDigests, afterClear.directAnswerDigests)
                        assertEquals(survivor.savedRowAnswerDigests, afterClear.savedRowAnswerDigests)
                        assertEquals(survivor.imageOrdinals, afterClear.imageOrdinals)
                        assertEquals(archived.filterKeys { it.startsWith("repeat-") },
                            cleared.filterKeys { it.startsWith("repeat-") })
                        assertEquals(cleared, snapshot())
                        val failed = ProtectedCommittedDraftStore(
                            context.cacheDir, context.noBackupFilesDir, settings) {
                            throw IllegalStateException("Synthetic interruption before publication")
                        }
                        assertTrue(runCatching {
                            failed.commit(activeProjectId(), binding, initialCommit.generation)
                        }.isFailure)
                        assertEquals(initialCommit, generations.reopen(activeProjectId(), binding))
                        assertEquals(cleared, snapshot())
                        val nextCommit = generations.commit(activeProjectId(), binding, initialCommit.generation)
                        assertEquals(afterClear, nextCommit.evidence)
                        assertNotEquals(initialCommit.generation, nextCommit.generation)
                        assertEquals(nextCommit, generations.reopen(activeProjectId(), binding))
                        assertTrue(runCatching {
                            generations.commit(activeProjectId(), binding, initialCommit.generation)
                        }.isFailure)
                        assertEquals(nextCommit, generations.reopen(activeProjectId(), binding))
                        assertEquals(cleared, snapshot())
                        val generationDir = java.io.File(java.io.File(java.io.File(java.io.File(
                            context.noBackupFilesDir, "recordwork-committed"), binding.projectId),
                            binding.workId), nextCommit.generation)
                        val corrupt = generationDir.walkTopDown().single {
                            it.isFile && it.name == "private-answers.aesgcm"
                        }
                        val intact = corrupt.readBytes()
                        try {
                            val changed = intact.clone()
                            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
                            corrupt.writeBytes(changed)
                            assertTrue(runCatching {
                                generations.reopen(activeProjectId(), binding)
                            }.isFailure)
                        } finally { corrupt.writeBytes(intact) }
                        assertEquals(nextCommit, generations.reopen(activeProjectId(), binding))
                        assertEquals(cleared, snapshot())
                        assertEquals(binding, vault.read(activeProjectId(), pending.id, scope))
                        val restartMarker = java.io.File(context.noBackupFilesDir,
                            "u06-publication-restart.properties")
                        assertFalse(restartMarker.exists())
                        val checkpoint = java.util.Properties().apply {
                            setProperty("project", binding.projectId)
                            setProperty("work", binding.workId)
                            setProperty("reserved", binding.reservedInstanceId)
                            setProperty("generation", nextCommit.generation)
                            setProperty("historical", initialCommit.generation)
                            setProperty("oldAnswerDigest", hash(household))
                            setProperty("survivor", b.rowId)
                            setProperty("memberDigest", hash(memberB))
                            setProperty("memberImageDigest", bReceipt.sha256)
                            setProperty("pointerDigest", QuarantinedPrivateImageStore.digest(
                                java.io.File(generationDir.parentFile, "current.aesgcm").readBytes()))
                            setProperty("pendingDigest", hash(connectedClient().load(project)
                                .pendingWork.map { it.id }.sorted().joinToString("\n")))
                        }
                        java.io.FileOutputStream(restartMarker).use {
                            checkpoint.store(it, "Synthetic interrupted publication checkpoint")
                            it.fd.sync()
                        }
                    } finally {
                        green.fill(0)
                        red.fill(0)
                        blue.fill(0)
                    }
                } else if (repeatImageExercise) {
                    val session = JavaRosaPrivateRepeatAnswerSession(
                        context.cacheDir, binding, authorized.plan, authorized.targetXFormXml)
                    val cipher = AndroidKeyStorePrivateDraftCipher()
                    val rows = QuarantinedRepeatRowVault(context.noBackupFilesDir, cipher)
                    val images = QuarantinedRepeatImageStore(context.noBackupFilesDir, cipher, rows)
                    val answers = QuarantinedRepeatAnswerStore(context.noBackupFilesDir, cipher, rows)
                    val native = ProtectedNativeRepeatImageProbe(
                        context.cacheDir, context.noBackupFilesDir, settings)
                    val group = session.schema.groups.single()
                    val path = group.imagePaths.single()
                    assertEquals("/data/members/photo", path)
                    val first = rows.addRow(binding, session.schema, group.path, UUID.randomUUID().toString())
                    val second = rows.addRow(binding, session.schema, group.path, UUID.randomUUID().toString())
                    assertTrue(first.rowId != second.rowId)
                    val namePath = group.fields.single().path
                    val firstName = "Synthetic member A " + UUID.randomUUID()
                    val secondName = "Synthetic member B " + UUID.randomUUID()
                    assertEquals(mapOf(namePath to firstName),
                        answers.save(binding, session, first.rowId, mapOf(namePath to firstName)).values)
                    assertEquals(mapOf(namePath to secondName),
                        answers.save(binding, session, second.rowId, mapOf(namePath to secondName)).values)
                    fun digest(value: String) = QuarantinedWorkDraftVault.sha256(value)
                    fun png(color: Int): ByteArray {
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                        return try {
                            bitmap.eraseColor(color)
                            java.io.ByteArrayOutputStream().use { output ->
                                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,
                                    100, output))
                                output.toByteArray()
                            }
                        } finally { bitmap.recycle() }
                    }
                    val red = png(android.graphics.Color.RED)
                    val blue = png(android.graphics.Color.BLUE)
                    try {
                        val a = images.importSelectedImage(binding, session, first.rowId,
                            path, "image/png", red.inputStream())
                        val b = images.importSelectedImage(binding, session, second.rowId,
                            path, "image/png", blue.inputStream())
                        assertTrue(a.sha256 != b.sha256)
                        val dir = java.io.File(java.io.File(java.io.File(
                            context.noBackupFilesDir, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                            binding.projectId), binding.workId)
                        fun snapshot() = dir.walkTopDown().filter { it.isFile }.associate {
                            it.relativeTo(dir).path to QuarantinedPrivateImageStore.digest(it.readBytes())
                        }
                        val before = snapshot()
                        assertEquals(mapOf((first.rowId to path) to 0, (second.rowId to path) to 1),
                            native.inspect(activeProjectId(), binding))
                        assertEquals(before, snapshot())
                        val combined = native.inspectCombined(activeProjectId(), binding)
                        assertEquals(mapOf((first.rowId to path) to 0, (second.rowId to path) to 1),
                            combined.imageOrdinals)
                        assertEquals(mapOf(first.rowId to mapOf(namePath to digest(firstName)),
                            second.rowId to mapOf(namePath to digest(secondName))),
                            combined.savedRowAnswerDigests)
                        assertEquals(before, snapshot())
                        rows.archiveRow(binding, session.schema, first.rowId, UUID.randomUUID().toString())
                        val afterArchive = snapshot()
                        assertTrue(runCatching {
                            images.receipt(binding, session, first.rowId, path)
                        }.isFailure)
                        assertEquals(mapOf((second.rowId to path) to 0),
                            native.inspect(activeProjectId(), binding))
                        assertEquals(true, images.withVerifiedImage(binding, session, second.rowId, path) {
                            receipt, bytes -> receipt == b && bytes.contentEquals(blue)
                        })
                        assertEquals(afterArchive, snapshot())
                        assertTrue(runCatching {
                            answers.read(binding, session, first.rowId)
                        }.isFailure)
                        val survivor = native.inspectCombined(activeProjectId(), binding)
                        assertEquals(mapOf((second.rowId to path) to 0), survivor.imageOrdinals)
                        assertEquals(mapOf(second.rowId to mapOf(namePath to digest(secondName))),
                            survivor.savedRowAnswerDigests)
                        assertEquals(afterArchive, snapshot())
                        val third = rows.addRow(binding, session.schema, group.path, UUID.randomUUID().toString())
                        assertTrue(third.rowId !in setOf(first.rowId, second.rowId))
                        assertEquals(null, images.receipt(binding, session, third.rowId, path))
                        val afterAdd = snapshot()
                        assertEquals(mapOf((second.rowId to path) to 0),
                            native.inspect(activeProjectId(), binding))
                        assertEquals(afterAdd, snapshot())
                        assertEquals(before.filterKeys { it.startsWith("repeat-image-") ||
                            it.startsWith("repeat-answer-") },
                            afterAdd.filterKeys { it.startsWith("repeat-image-") ||
                                it.startsWith("repeat-answer-") })
                        assertFalse(answers.read(binding, session, third.rowId).hasSavedAnswers)
                        val editedName = "Synthetic updated member " + UUID.randomUUID()
                        answers.save(binding, session, second.rowId, mapOf(namePath to editedName))
                        val editedSnapshot = snapshot()
                        repeat(2) {
                            val fresh = ProtectedNativeRepeatImageProbe(
                                context.cacheDir, context.noBackupFilesDir, settings)
                                .inspectCombined(activeProjectId(), binding)
                            assertEquals(mapOf((second.rowId to path) to 0), fresh.imageOrdinals)
                            assertEquals(mapOf(second.rowId to mapOf(namePath to digest(editedName))),
                                fresh.savedRowAnswerDigests)
                        }
                        assertEquals(editedSnapshot, snapshot())
                        assertEquals(afterAdd.filterKeys { it.startsWith("repeat-image-") },
                            editedSnapshot.filterKeys { it.startsWith("repeat-image-") })
                        assertEquals(binding, vault.read(activeProjectId(), pending.id, scope))
                        // Test-only restart oracle: UUIDs and digests, not
                        // plaintext names, image bytes, tokens or credentials.
                        val marker = java.io.File(context.noBackupFilesDir,
                            "u06-combined-repeat-restart.properties")
                        assertFalse(marker.exists())
                        val checkpoint = java.util.Properties().apply {
                            setProperty("pid", android.os.Process.myPid().toString())
                            setProperty("project", binding.projectId)
                            setProperty("work", binding.workId)
                            setProperty("reserved", binding.reservedInstanceId)
                            setProperty("archived", first.rowId)
                            setProperty("survivor", second.rowId)
                            setProperty("blank", third.rowId)
                            setProperty("answerDigest", digest(editedName))
                            setProperty("imageDigest", b.sha256)
                            setProperty("pendingDigest", digest(
                                connectedClient().load(project).pendingWork.map { it.id }
                                    .sorted().joinToString("\n")))
                            editedSnapshot.forEach { (file, hash) ->
                                setProperty("file." + file, hash)
                            }
                        }
                        java.io.FileOutputStream(marker).use {
                            checkpoint.store(it, "Synthetic combined restart: identifiers and digests only")
                            it.fd.sync()
                        }
                    } finally {
                        red.fill(0)
                        blue.fill(0)
                    }
                } else if (conditionalExercise) {
                    val dir = java.io.File(java.io.File(java.io.File(
                        context.noBackupFilesDir, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                        binding.projectId), binding.workId)
                    fun snapshot(): Map<String, String> = dir.walkTopDown()
                        .filter { it.isFile }.associate { file ->
                            file.relativeTo(dir).path to java.security.MessageDigest
                                .getInstance("SHA-256").digest(file.readBytes())
                                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        }
                    val original = snapshot()
                    repeat(2) {
                        val evidence = probe.inspect(activeProjectId(), binding,
                            conditionalExercise = true)
                        assertTrue(evidence.conditionalValidationVerified)
                        assertEquals(setOf("/data/gate", "/data/count", "/data/total"),
                            evidence.questionPaths)
                        assertEquals("/data/gate", evidence.firstQuestionPath)
                        assertFalse(evidence.canSave)
                        assertFalse(evidence.canFinalize)
                        assertFalse(evidence.canUpload)
                    }
                    assertEquals(original, snapshot())
                    assertEquals(binding, vault.read(activeProjectId(), pending.id, scope))
                    assertFalse(context.cacheDir.listFiles().orEmpty().any {
                        it.name.startsWith("recordwork-native-controller-")
                    })
                } else {
                val evidence = probe.inspect(activeProjectId(), binding)
                assertEquals(pending.id, evidence.workId)
                assertEquals(binding.targetFormVersionId, evidence.targetFormVersionId)
                assertEquals(binding.targetXFormSha256, evidence.targetXFormSha256)
                assertEquals(setOf("/data/household_name", "/data/photo",
                    "/data/children/child_name", "/data/children/child_age_text"),
                    evidence.questionPaths)
                assertEquals(setOf("/data/photo"), evidence.imagePaths)
                assertEquals(setOf("/data/children"), evidence.repeatPaths)
                assertEquals("/data/household_name", evidence.firstQuestionPath)
                assertFalse(evidence.inMemoryAnswerVerified)
                assertEquals(null, evidence.nextQuestionPath)
                // Native answer entry must not register a draft, write XML
                // or create an encrypted private scalar answer file. Capture
                // the exact vault files before and after this RAM-only probe.
                val dir = java.io.File(java.io.File(java.io.File(
                    context.noBackupFilesDir, QuarantinedWorkDraftVault.VAULT_DIRECTORY),
                    binding.projectId), binding.workId)
                fun snapshot(): Map<String, String> = dir.walkTopDown()
                    .filter { it.isFile }.associate { file ->
                        file.relativeTo(dir).path to java.security.MessageDigest
                            .getInstance("SHA-256").digest(file.readBytes())
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    }
                val originalFiles = snapshot()
                val temporaryAnswer = "Synthetic native entry " + UUID.randomUUID()
                val entered = probe.inspect(activeProjectId(), binding, temporaryAnswer)
                assertTrue("Native answer must survive forward/back navigation",
                    entered.inMemoryAnswerVerified)
                assertEquals("/data/photo", entered.nextQuestionPath)
                assertEquals(evidence.questionPaths, entered.questionPaths)
                assertEquals(evidence.repeatPaths, entered.repeatPaths)
                assertEquals(evidence.imagePaths, entered.imagePaths)
                assertFalse(entered.canSave)
                assertFalse(entered.canFinalize)
                assertFalse(entered.canUpload)
                // Another fresh native session must start blank, not reuse
                // the discarded first session's answer or historical data.
                val fresh = probe.inspect(activeProjectId(), binding,
                    "Synthetic second session " + UUID.randomUUID())
                assertTrue(fresh.inMemoryAnswerVerified)
                val group = "/data/children"
                val name = "$group/child_name"
                val age = "$group/child_age_text"
                val exercise = NativeRepeatExercise(
                    groupPath = group,
                    rows = listOf(
                        mapOf(name to "Synthetic member A", age to "7"),
                        mapOf(name to "Synthetic member B", age to "11")
                    ),
                    editedFirstPath = name,
                    editedFirstValue = "Synthetic member A edited"
                )
                val repeated = probe.inspect(activeProjectId(), binding,
                    "Synthetic repeat session " + UUID.randomUUID(), exercise)
                assertTrue(repeated.inMemoryAnswerVerified)
                assertTrue("Native repeat creation, edit and deletion must pass",
                    repeated.inMemoryRepeatVerified)
                assertEquals(2, repeated.repeatRowsBeforeDeletion)
                assertEquals(1, repeated.repeatRowsAfterDeletion)
                assertEquals(evidence.questionPaths, repeated.questionPaths)
                assertFalse(repeated.canSave)
                assertFalse(repeated.canFinalize)
                assertFalse(repeated.canUpload)
                // Repeat the complete exercise in a new controller: no
                // previous native row values may survive in this session.
                val repeatedFresh = probe.inspect(activeProjectId(), binding,
                    "Synthetic fresh repeat session " + UUID.randomUUID(), exercise)
                assertTrue(repeatedFresh.inMemoryRepeatVerified)
                assertEquals(2, repeatedFresh.repeatRowsBeforeDeletion)
                assertEquals(1, repeatedFresh.repeatRowsAfterDeletion)
                assertEquals(originalFiles, snapshot())

                // Native direct image answer references are qualified using
                // real Android-generated JPEGs and the private AES-GCM store.
                // No camera, ordinary ODK instance or plaintext file is used.
                val media = ProtectedNativeImageIntake(
                    context.cacheDir, context.noBackupFilesDir, settings
                )
                val photo = "/data/photo"
                fun jpeg(color: Int): ByteArray {
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        32, 32, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    try {
                        bitmap.eraseColor(color)
                        return java.io.ByteArrayOutputStream().use { output ->
                            assertTrue(bitmap.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG, 90, output
                            ))
                            output.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                val firstPhoto = jpeg(android.graphics.Color.RED)
                val secondPhoto = jpeg(android.graphics.Color.BLUE)
                assertEquals(null, media.inspect(activeProjectId(), binding, photo).directImageReceipt)
                assertTrue(runCatching {
                    media.inspect(activeProjectId(), binding, photo, requireAsset = true)
                }.isFailure)
                assertEquals(originalFiles, snapshot())
                val firstReceipt = media.importSelectedImage(
                    activeProjectId(), binding, photo, "image/jpeg", firstPhoto.inputStream()
                )
                assertEquals(firstPhoto.size, firstReceipt.bytes)
                assertEquals(QuarantinedPrivateImageStore.digest(firstPhoto), firstReceipt.sha256)
                assertFalse(firstReceipt.canSubmit)
                assertFalse(firstReceipt.canFinalize)
                val slot = dir.listFiles().orEmpty().single {
                    it.name.startsWith("image-") && it.name.endsWith(".aesgcm")
                }
                val firstCipher = slot.readBytes()
                assertEquals(1, firstCipher[0].toInt())
                assertEquals(12, firstCipher[1].toInt())
                assertFalse(firstCipher.contentEquals(firstPhoto))
                val withFirstPhoto = snapshot()
                assertTrue(runCatching {
                    media.importSelectedImage(activeProjectId(), binding, photo,
                        "image/png", firstPhoto.inputStream())
                }.isFailure)
                assertTrue(runCatching {
                    media.importSelectedImage(activeProjectId(), binding, name,
                        "image/jpeg", secondPhoto.inputStream())
                }.isFailure)
                assertEquals(withFirstPhoto, snapshot())
                val secondReceipt = media.importSelectedImage(
                    activeProjectId(), binding, photo, "image/jpeg", secondPhoto.inputStream()
                )
                assertEquals(QuarantinedPrivateImageStore.digest(secondPhoto), secondReceipt.sha256)
                assertFalse(firstReceipt.sha256 == secondReceipt.sha256)
                assertFalse(firstCipher.contentEquals(slot.readBytes()))
                val beforeRevisit = snapshot()
                val reread = media.inspect(activeProjectId(), binding, photo, requireAsset = true)
                assertEquals(beforeRevisit, snapshot())
                assertEquals(secondReceipt, reread.directImageReceipt)
                assertTrue(reread.nativeImageReferenceVerified)
                assertFalse(reread.canUpload)
                val store = QuarantinedPrivateImageStore(
                    context.noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                )
                assertEquals(true, store.withVerifiedImage(binding, photo, listOf(photo)) {
                    receipt, bytes -> receipt == secondReceipt && bytes.contentEquals(secondPhoto)
                })
                // Exercise a second supported format on the canonical intake
                // bridge, not a separate direct-store implementation.
                val pngBitmap = android.graphics.Bitmap.createBitmap(
                    16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                val png = try {
                    pngBitmap.eraseColor(android.graphics.Color.GREEN)
                    java.io.ByteArrayOutputStream().use { output ->
                        assertTrue(pngBitmap.compress(
                            android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                        output.toByteArray()
                    }
                } finally {
                    pngBitmap.recycle()
                }
                val pngReceipt = media.importSelectedImage(
                    activeProjectId(), binding, photo, "image/png", png.inputStream()
                )
                assertEquals(QuarantinedPrivateImageStore.digest(png), pngReceipt.sha256)
                assertTrue(pngReceipt.sha256 != secondReceipt.sha256)
                val pngSnapshot = snapshot()
                assertEquals(pngReceipt, media.inspect(
                    activeProjectId(), binding, photo, requireAsset = true).directImageReceipt)
                assertEquals(pngSnapshot, snapshot())
                assertEquals(true, store.withVerifiedImage(binding, photo, listOf(photo)) {
                    receipt, bytes -> receipt == pngReceipt && bytes.contentEquals(png)
                })
                media.clear(activeProjectId(), binding, photo)
                assertFalse(slot.exists())
                assertEquals(null, media.inspect(activeProjectId(), binding, photo).directImageReceipt)
                assertTrue(runCatching {
                    media.inspect(activeProjectId(), binding, photo, requireAsset = true)
                }.isFailure)
                assertEquals(originalFiles, snapshot())
                firstPhoto.fill(0)
                secondPhoto.fill(0)
                png.fill(0)
                assertFalse(evidence.canSave)
                assertFalse(evidence.canFinalize)
                assertFalse(evidence.canUpload)
                assertEquals(binding, vault.read(activeProjectId(), pending.id, scope))
                assertFalse(context.cacheDir.listFiles().orEmpty().any {
                    it.name.startsWith("recordwork-native-controller-")
                })
                }
            }
            if (openEditor) {
                assertEquals("1", binding.targetVersion)
                // Wait for the stage callback, which runs only after encrypted
                // approval persistence, not merely the vault manifest write.
                var editorOffered = false
                for (attempt in 1..120) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        val rows = activity.findViewById<LinearLayout>(R.id.work_versions)
                        editorOffered = (0 until rows.childCount)
                            .map { rows.getChildAt(it) }.filterIsInstance<Button>()
                            .any { it.text.toString() ==
                                activity.getString(R.string.mapping_open_private_editor) }
                    }
                    if (editorOffered) break
                    Thread.sleep(250)
                }
                assertTrue("Reviewed staging must offer protected editor navigation", editorOffered)
                scenario.onActivity { activity ->
                    val rows = activity.findViewById<LinearLayout>(R.id.work_versions)
                    val launch = (0 until rows.childCount).map { rows.getChildAt(it) }
                        .filterIsInstance<Button>().single { it.text.toString() ==
                            activity.getString(R.string.mapping_open_private_editor) }
                    assertTrue(launch.performClick())
                }
                // Actual private Activity must independently authorize the
                // exact target against the genuine server on opening.
                var editorReady = false
                for (attempt in 1..120) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance().getActivitiesInStage(
                                androidx.test.runner.lifecycle.Stage.RESUMED
                            ).filterIsInstance<QuarantinedTextDraftActivity>().singleOrNull()
                        if (resumed != null) {
                            val root = resumed.window.decorView
                            fun descendants(view: View): List<View> =
                                listOf(view) + if (view is android.view.ViewGroup) {
                                    (0 until view.childCount).flatMap {
                                        descendants(view.getChildAt(it))
                                    }
                                } else emptyList()
                            val widgets = descendants(root)
                            val save = widgets.filterIsInstance<Button>().singleOrNull {
                                it.text.toString() ==
                                    resumed.getString(R.string.private_text_editor_save)
                            }
                            if (save?.isEnabled == true) {
                                val fields = widgets.filterIsInstance<EditText>()
                                assertEquals("Exact scalar form has one editable question", 1, fields.size)
                                assertTrue("Independent visit must start without old answers",
                                    fields.single().text.isEmpty())
                                assertTrue(widgets.filterIsInstance<TextView>().any {
                                    it.text.toString() == resumed.getString(
                                        R.string.private_text_editor_never_submit)
                                })
                                assertFalse(widgets.filterIsInstance<Button>().any {
                                    it.visibility == View.VISIBLE && it.text.toString() ==
                                        resumed.getString(R.string.mapping_approved_import_action)
                                })
                                editorReady = true
                            }
                        }
                    }
                    if (editorReady) break
                    Thread.sleep(250)
                }
                assertTrue("Actual private scalar editor must open after renewed authorization",
                    editorReady)
                // U06-T04E-03A: operate the actual editor controls, not the
                // store's save() method. The server must authorize the same
                // READY work again and JavaRosa must validate the new answer.
                val paths = listOf("/data/household_name")
                val answer = "Synthetic new visit " + UUID.randomUUID().toString()
                val store = EncryptedPrivateTextDraftStore(
                    context.noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
                )
                assertFalse(store.read(binding, paths).hasSavedDraft)
                val ciphertext = java.io.File(
                    java.io.File(java.io.File(java.io.File(context.noBackupFilesDir,
                        QuarantinedWorkDraftVault.VAULT_DIRECTORY), binding.projectId),
                        binding.workId),
                    "private-answers.aesgcm"
                )
                assertFalse(ciphertext.exists())
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val editor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(
                            androidx.test.runner.lifecycle.Stage.RESUMED
                        ).filterIsInstance<QuarantinedTextDraftActivity>().single()
                    val widgets = editorWidgets(editor.window.decorView)
                    widgets.filterIsInstance<EditText>().single().setText(answer)
                }
                // Typing alone is not a committed private save.
                assertFalse(ciphertext.exists())
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val editor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(
                            androidx.test.runner.lifecycle.Stage.RESUMED
                        ).filterIsInstance<QuarantinedTextDraftActivity>().single()
                    val save = editorWidgets(editor.window.decorView)
                        .filterIsInstance<Button>().single {
                            it.text.toString() == editor.getString(R.string.private_text_editor_save)
                        }
                    assertTrue(save.isEnabled)
                    assertTrue(save.performClick())
                }
                var saved = false
                for (attempt in 1..120) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val editor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance().getActivitiesInStage(
                                androidx.test.runner.lifecycle.Stage.RESUMED
                            ).filterIsInstance<QuarantinedTextDraftActivity>().singleOrNull()
                        if (editor != null) {
                            val widgets = editorWidgets(editor.window.decorView)
                            saved = widgets.filterIsInstance<TextView>().any {
                                it.text.toString() ==
                                    editor.getString(R.string.private_text_editor_saved)
                            }
                            if (saved) {
                                assertTrue(widgets.filterIsInstance<Button>().single {
                                    it.text.toString() ==
                                        editor.getString(R.string.private_text_editor_save)
                                }.isEnabled)
                                assertEquals(answer,
                                    widgets.filterIsInstance<EditText>().single().text.toString())
                            }
                        }
                    }
                    if (saved) break
                    Thread.sleep(250)
                }
                assertTrue("Actual editor must acknowledge successful private save", saved)
                assertTrue(ciphertext.isFile)
                val sealed = ciphertext.readBytes()
                assertTrue(sealed.size >= 30)
                assertEquals(1, sealed[0].toInt())
                assertEquals(12, sealed[1].toInt())
                assertFalse("No plaintext answer in encrypted envelope",
                    String(sealed, Charsets.ISO_8859_1).contains(answer))
                val persisted = store.read(binding, paths)
                assertTrue(persisted.hasSavedDraft)
                assertEquals(mapOf(paths.single() to answer), persisted.answers)
                assertFalse(persisted.canFinalize)
                assertFalse(persisted.canSubmit)
                assertEquals(binding, vault.read(activeProjectId(), pending.id, scope))
                // Test-only checkpoint: identifiers and digests, never the
                // answer itself. The second instrumentation process must
                // reopen this exact work through the real Activity.
                val checkpoint = java.io.File(context.noBackupFilesDir,
                    "u06-editor-restart-checkpoint.properties")
                assertFalse(checkpoint.exists())
                val evidence = java.util.Properties().apply {
                    setProperty("project", binding.projectId)
                    setProperty("work", binding.workId)
                    setProperty("reserved", binding.reservedInstanceId)
                    setProperty("answerDigest", QuarantinedWorkDraftVault.sha256(answer))
                    setProperty("cipherDigest", java.security.MessageDigest.getInstance("SHA-256")
                        .digest(sealed).joinToString("") { "%02x".format(it.toInt() and 0xff) })
                    setProperty("pid", android.os.Process.myPid().toString())
                }
                java.io.FileOutputStream(checkpoint).use {
                    evidence.store(it, "Synthetic editor restart checkpoint: no answer data")
                    it.fd.sync()
                }


            }
            assertEquals(before.pendingWork.map { it.id }.toSet(),
                connectedClient().load(project).pendingWork.map { it.id }.toSet())
        }
    }



    private fun editorWidgets(view: View): List<View> =
        listOf(view) + if (view is android.view.ViewGroup) {
            (0 until view.childCount).flatMap { editorWidgets(view.getChildAt(it)) }
        } else emptyList()

    // Exercise the actual Android parser, not only the desktop unit-test
    // provider. These synthetic inputs do not resolve external resources.
    private fun verifyAndroidXmlAdmissionRejectsUnsafeInputs() {
        val good = RecordWorkSafeXml.parse(
            "<data><answer>A &amp; B</answer></data>", 1024, 4, 8
        )
        assertEquals("A & B", good.getElementsByTagName("answer").item(0).textContent)
        val forbidden = listOf(
            "<!DOCTYPE data><data/>",
            "<!DOCTYPE data [<!ENTITY value 'synthetic'>]><data>&value;</data>",
            "<!DOCTYPE data SYSTEM 'urn:u06:forbidden'><data/>",
            "<data xmlns:i='http://www.w3.org/2001/XInclude'><i:include href='urn:u06:forbidden'/></data>",
            "<data><a><b><c><d><e/></d></c></b></a></data>",
            "<data><a/><a/><a/><a/><a/><a/><a/><a/></data>"
        )
        forbidden.forEachIndexed { index, xml ->
            val failure = runCatching { RecordWorkSafeXml.parse(xml, 1024, 4, 8) }
                .exceptionOrNull()
            assertTrue("Unsafe XML admission case $index must fail closed",
                failure is IllegalArgumentException)
        }
    }

    // Diagnostic only: use the genuine disposable server response in RAM,
    // independently exercising each of the existing production validation
    // layers. Never expose the response, form XML, values or auth headers.
    private fun verifyConnectedPreparationLayers(
        project: String, work: RemoteVisitWork, cache: java.io.File
    ) {
        fun <T> checked(phase: String, action: () -> T): T {
            try {
                return action()
            } catch (error: Exception) {
                val feature = listOf(
                    "disallow-doctype-decl",
                    "external-general-entities",
                    "external-parameter-entities",
                    "load-external-dtd",
                    "secure-processing",
                    "XInclude",
                    "No primary instance",
                    "Native JavaRosa rejected XForm",
                    "Target XForm ID differs",
                    "Target XForm version differs",
                    "Invalid primary instance",
                    "Unsupported relative or missing form path"
                ).firstOrNull { error.message.orEmpty().contains(it, ignoreCase = true) }
                    ?: "UNCLASSIFIED"
                throw AssertionError(
                    "Preparation layer=$phase exception=${error.javaClass.simpleName} category=$feature"
                )
            }
        }
        val path = RecordWorkApiPaths.fromProjectUrl(
            "https://10.0.2.2:8443/v1/projects/$project"
        ).prepare(work.id)
        val payload = checked("HTTPS_RESPONSE") {
            val connection = URL(path).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Authorization", "Basic " +
                    android.util.Base64.encodeToString(
                        "u06-connected-owner:u06-disposable-only".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    ))
                require(connection.responseCode == 200) { "Unexpected prepare status" }
                org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        val sourceXml = payload.getString("sourceXml")
        val targetXml = payload.getString("targetXFormXml")
        val original = if (payload.isNull("sourceXFormXml")) null
            else payload.getString("sourceXFormXml")
        val targetId = payload.getString("targetFormId")
        val targetVersion = payload.getString("targetFormVersion")
        checked("CROSS_VERSION_ASSESSMENT") {
            CrossVersionAssessmentEngine.assess(
                work.id, work.sourceSubmissionId, work.targetFormVersionId,
                work.type, sourceXml, targetXml, targetId, targetVersion
            )
        }
        val plan = checked("HISTORICAL_SCHEMA_MAPPING") {
            HistoricalSchemaMappingEngine.plan(
                work.id, work.sourceSubmissionId, work.targetFormVersionId,
                work.type, sourceXml, original, targetXml, targetId, targetVersion
            )
        }
        checked("JAVAROSA_INITIALIZATION") {
            JavaRosaStagingProbe(cache).inspect(plan, targetXml, original)
        }
    }

    private fun connectedClient(): RecordWorkReadOnlyClient {
        val backendProject = "81000000-0000-4000-8000-000000000001"
        val settings = mock<Settings>()
        val provider = mock<SettingsProvider>()
        whenever(provider.getUnprotectedSettings(backendProject)).thenReturn(settings)
        whenever(settings.getString(ProjectKeys.KEY_SERVER_URL)).thenReturn(
            "https://10.0.2.2:8443/v1/projects/$backendProject"
        )
        whenever(settings.getString(ProjectKeys.KEY_USERNAME)).thenReturn("u06-connected-owner")
        whenever(settings.getString(ProjectKeys.KEY_PASSWORD)).thenReturn("u06-disposable-only")
        val cert = InstrumentationRegistry.getInstrumentation().context.assets
            .open("u06_t04a_ca.pem").use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("disposable-ci-only", cert)
        }
        val managers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(store) }.trustManagers
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers, null) }
        return RecordWorkReadOnlyClient(provider) { destination: URL ->
            (destination.openConnection() as HttpsURLConnection).also {
                it.sslSocketFactory = tls.socketFactory
            }
        }
    }

    private fun configureProject() {
        rule.startAtFirstLaunch()
            .clickManuallyEnterProjectDetails()
            .inputUrl("https://example.com/v1/projects/81000000-0000-4000-8000-000000000001")
            .inputUsername("u06-ui-test-owner")
            .inputPassword("disposable-test-only")
            .addProject()
    }

    private fun activeProjectId(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return requireNotNull(
            DaggerUtils.getComponent(context).currentProjectProvider()
                .getCurrentProject().value?.uuid
        )
    }
}
