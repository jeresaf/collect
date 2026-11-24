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
            assertEquals(before.pendingWork.map { it.id }.toSet(),
                connectedClient().load(project).pendingWork.map { it.id }.toSet())
        }
    }



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
