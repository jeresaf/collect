package org.odk.collect.android.recordwork

import android.view.View
import android.widget.Button
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
