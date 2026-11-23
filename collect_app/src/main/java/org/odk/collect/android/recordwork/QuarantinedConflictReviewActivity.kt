package org.odk.collect.android.recordwork

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.UUID

/**
 * Metadata-only recovery when an online READY recheck returned HTTP 409.
 *
 * Never decrypt an old answer, read historical XML, open ODK FormFilling,
 * retarget the old instance, or submit anything. The user can only navigate
 * to the existing read-only server browser, which fetches fresh, scoped
 * records and independently authenticates the active enumerator.
 */
class QuarantinedConflictReviewActivity : LocalizedActivity() {
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var metadata: TextView
    private lateinit var browse: Button
    private var scopedBinding: QuarantinedWorkDraft? = null

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = "Review quarantined work"
        settings = DaggerUtils.getComponent(this).settingsProvider()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { textSize = 17f }
        metadata = TextView(this).apply { textSize = 14f }
        browse = Button(this).apply {
            text = "Review current server records"
            isEnabled = false
            visibility = View.GONE
            setOnClickListener { browseCurrentServerRecords() }
        }
        layout.addView(status)
        layout.addView(metadata)
        layout.addView(browse)
        layout.addView(TextView(this).apply {
            text = "This screen never opens saved answers or copies a draft. " +
                "A fresh record and new work item require separate server authorization " +
                "and explicit operator review. The old instance remains quarantined."
        })
        setContentView(layout)
    }

    private fun activeProjectMatches(): Boolean =
        DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value?.uuid == projectId

    private fun bindingForActiveActor(): QuarantinedWorkDraft {
        require(projectId.isNotBlank() && isUuid(workId) &&
            activeProjectMatches()) { "Current project changed" }
        val values = settings.getUnprotectedSettings(projectId)
        val paths = RecordWorkApiPaths.fromProjectUrl(
            values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        )
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank()) { "Current account required" }
        val actorHash = QuarantinedWorkDraftVault.sha256(
            projectId + "\n" + paths.records() + "\n" + username
        )
        return requireNotNull(
            QuarantinedWorkDraftVault(noBackupFilesDir)
                .read(projectId, workId, actorHash)
        ) { "No actor-scoped protected work binding exists" }
    }

    override fun onResume() {
        super.onResume()
        deny()
        val binding = runCatching { bindingForActiveActor() }.getOrNull() ?: return
        val review = runCatching {
            QuarantinedConflictReview.describe(binding)
        }.getOrNull() ?: return
        scopedBinding = binding
        status.text = "Work requires a manual server review. " +
            "Only local work identifiers are shown; no answers or media are opened."
        metadata.text = "Work ID: ${review.workId}\n" +
            "Original record ID: ${review.sourceSubmissionId}\n" +
            "Workflow: ${review.workType.name}\n" +
            "Selected target version: ${review.targetVersion}\n" +
            "Reserved local instance ID: ${review.reservedInstanceId}\n" +
            "Existing successfully saved encrypted answers, if any, remain " +
            "in this account's private draft storage."
        browse.visibility = View.VISIBLE
        browse.isEnabled = true
    }

    private fun browseCurrentServerRecords() {
        val before = scopedBinding ?: return deny()
        // Revalidate this exact local actor and project at the moment of the
        // explicit tap. Do not send stale IDs or ciphertext to the browser.
        val current = runCatching { bindingForActiveActor() }.getOrNull()
        if (current != before || !activeProjectMatches()) return deny()
        browse.isEnabled = false
        startActivity(Intent(this, RecordWorkBrowserActivity::class.java))
    }

    private fun deny() {
        scopedBinding = null
        browse.isEnabled = false
        browse.visibility = View.GONE
        metadata.text = ""
        status.text = "Recovery details unavailable. Verify the active project " +
            "and original enumerator account."
    }

    override fun onStop() {
        // Opaque references also leave the screen when backgrounded; the
        // actor/project check is repeated on every return.
        deny()
        super.onStop()
    }

    companion object {
        private const val EXTRA_PROJECT = "recordwork.conflict.project"
        private const val EXTRA_WORK = "recordwork.conflict.work"

        fun intentFor(context: Context, projectId: String, workId: String): Intent =
            Intent(context, QuarantinedConflictReviewActivity::class.java)
                .putExtra(EXTRA_PROJECT, projectId)
                .putExtra(EXTRA_WORK, workId)

        private fun isUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }
                .getOrDefault(false)
    }
}
