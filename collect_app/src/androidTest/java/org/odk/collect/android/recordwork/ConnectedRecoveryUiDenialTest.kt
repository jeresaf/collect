package org.odk.collect.android.recordwork

import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.odk.collect.android.R

/**
 * U06-T04D-01: real emulator Activity lifecycle and visible fail-closed UI.
 *
 * No synthetic server token or permission is injected. Invalid and unbound
 * navigation must never reveal a draft or a button that could open editing.
 * This checks actual rendered Android Views, not mock Activity logic.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedRecoveryUiDenialTest {

    @Test
    fun conflictRecoveryWithoutOriginalActorBindingNeverShowsDraftOrBrowse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, QuarantinedConflictReviewActivity::class.java)
        ActivityScenario.launch<QuarantinedConflictReviewActivity>(intent).use { scenario ->
            fun check(activity: QuarantinedConflictReviewActivity) {
                assertTrue(
                    "Private recovery screen must prohibit screenshots",
                    activity.window.attributes.flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0
                )
                val root = activity.findViewById<View>(android.R.id.content)
                val buttons = mutableListOf<Button>()
                fun collect(view: View) {
                    if (view is Button) buttons.add(view)
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) collect(view.getChildAt(i))
                    }
                }
                collect(root)
                assertEquals(1, buttons.size)
                assertEquals(View.GONE, buttons.single().visibility)
                assertFalse(buttons.single().isEnabled)
                val labels = mutableListOf<TextView>()
                fun read(view: View) {
                    if (view is TextView) labels.add(view)
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) read(view.getChildAt(i))
                    }
                }
                read(root)
                val displayed = labels.filter { it.visibility == View.VISIBLE }
                    .joinToString("\n") { it.text.toString() }
                assertTrue(displayed.contains("Recovery details unavailable"))
                assertFalse(displayed.contains("PRIVATE_STALE_SOURCE"))
                assertFalse(displayed.contains("PRIVATE_LOCAL_DRAFT_ANSWER"))
                assertFalse(displayed.contains("Work ID:"))
            }
            scenario.onActivity(::check)
            // Recreating the actual Activity must not accidentally restore
            // a privileged browse button or expose stale metadata.
            scenario.recreate()
            scenario.onActivity(::check)
        }
    }


    /**
     * A deep link or restored intent claiming that a historical record is
     * amendable must not override the active project binding. Test both a
     * forged "current" amendment hint and a forged pre-existing work item.
     */
    @Test
    fun forgedProjectAndAmendmentHintCannotEnableMutationOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wrongProject = "93000000-0000-4000-8000-000000000001"
        val source = "93000000-0000-4000-8000-000000000002"
        val intent = Intent(context, RecordWorkPrepareActivity::class.java)
            .putExtra(RecordWorkPrepareActivity.EXTRA_PROJECT, wrongProject)
            .putExtra(RecordWorkPrepareActivity.EXTRA_SOURCE, source)
            .putExtra(RecordWorkPrepareActivity.EXTRA_SOURCE_CAN_AMEND, true)
            .putExtra(RecordWorkPrepareActivity.EXTRA_WORK,
                "93000000-0000-4000-8000-000000000003")
            .putExtra(RecordWorkPrepareActivity.EXTRA_TARGET,
                "93000000-0000-4000-8000-000000000004")
            .putExtra(RecordWorkPrepareActivity.EXTRA_TYPE, "AMENDMENT")
        ActivityScenario.launch<RecordWorkPrepareActivity>(intent).use { scenario ->
            fun check(activity: RecordWorkPrepareActivity) {
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_amend).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_followup).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_create).visibility)
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.work_visit_key).visibility)
                assertEquals(0,
                    (activity.findViewById<View>(R.id.work_versions)
                        as android.widget.LinearLayout).childCount)
                val status = activity.findViewById<TextView>(R.id.work_status).text.toString()
                assertTrue("Foreign project must fail closed", status.isNotBlank())
            }
            scenario.onActivity(::check)
            scenario.recreate()
            scenario.onActivity(::check)
        }
    }

    @Test
    fun forgedConflictIdentifiersNeverRevealRecoveryNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = QuarantinedConflictReviewActivity.intentFor(
            context,
            "93000000-0000-4000-8000-000000000001",
            "93000000-0000-4000-8000-000000000003"
        )
        ActivityScenario.launch<QuarantinedConflictReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                val views = mutableListOf<View>()
                fun walk(view: View) {
                    views.add(view)
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) walk(view.getChildAt(i))
                    }
                }
                walk(content)
                assertTrue(
                    "Unauthorized conflict navigation must stay hidden",
                    views.filterIsInstance<Button>().all {
                        it.visibility != View.VISIBLE && !it.isEnabled
                    }
                )
                assertFalse(views.filterIsInstance<TextView>()
                    .filter { it.visibility == View.VISIBLE }
                    .joinToString("\n") { it.text.toString() }
                    .contains("Work ID:"))
            }
        }
    }

    @Test
    fun prepareScreenWithoutValidSourceKeepsEveryMutationActionHidden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, RecordWorkPrepareActivity::class.java)
        ActivityScenario.launch<RecordWorkPrepareActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.work_amend).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.work_followup).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.work_create).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.work_visit_key).visibility)
                val status = activity.findViewById<TextView>(R.id.work_status).text.toString()
                assertTrue("Missing project/source must block preparation", status.isNotBlank())
            }
        }
    }
}
