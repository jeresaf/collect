package org.odk.collect.android.recordwork

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Inert typed mapping decisions only. No source answer is ever rendered.
 * Not a native submission editor, approval service or upload screen.
 */
class QuarantinedTypedReviewActivity : LocalizedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var save: Button
    private lateinit var recoveryButton: Button
    private lateinit var progress: ProgressBar
    private val selectors = linkedMapOf<String, Spinner>()
    private var loaded: AuthorizedTypedReview? = null
    private var binding: QuarantinedWorkDraft? = null
    private var generation = 0

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = "Review historical typed fields (no transfer)"
        settings = DaggerUtils.getComponent(this).settingsProvider()
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "Authorizing mapping review..."
        }
        progress = ProgressBar(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(rows)
            isFillViewport = true
        }
        save = Button(this).apply {
            text = "Review decisions and confirm"
            isEnabled = false
            setOnClickListener { confirm() }
        }
        outer.addView(status)
        outer.addView(progress)
        outer.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(save)
        recoveryButton = Button(this).apply {
            text = "Review server records (manual recovery)"
            visibility = View.GONE
            isEnabled = false
            setOnClickListener { reviewConflict() }
        }
        outer.addView(recoveryButton)
        outer.addView(TextView(this).apply {
            text = "This review only records operator decisions. It does not copy historical answers, finalize or submit data."
        })
        setContentView(outer)
        if (projectId.isBlank() || !isUuid(workId) || !activeProjectMatches()) {
            deny()
        } else open()
    }

    private fun activeProjectMatches(): Boolean {
        val current = DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value
        return current != null && current.uuid == projectId
    }

    private fun actorHash(): String {
        val values = settings.getUnprotectedSettings(projectId)
        val paths = RecordWorkApiPaths.fromProjectUrl(
            values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty())
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank()) { "Current account required" }
        return QuarantinedWorkDraftVault.sha256(
            projectId + "\n" + paths.records() + "\n" + username)
    }

    private fun loadAuthorized(): Pair<QuarantinedWorkDraft, AuthorizedTypedReview> {
        require(activeProjectMatches()) { "Project changed" }
        val bound = requireNotNull(QuarantinedWorkDraftVault(noBackupFilesDir)
            .read(projectId, workId, actorHash())) { "Work binding missing" }
        require(bound.workType == RecordWorkType.AMENDMENT) {
            "Follow-up visits must not copy old data"
        }
        val authorized = RecordWorkPreparationClient(settings)
            .authorizeTypedReview(projectId, bound, cacheDir)
        return bound to authorized
    }

    private fun store() = ProtectedTypedReviewStore(
        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher())

    private fun open() {
        recoveryButton.visibility = View.GONE
        recoveryButton.isEnabled = false
        val request = ++generation
        save.isEnabled = false
        progress.visibility = View.VISIBLE
        executor.execute {
            val result = runCatching {
                val (bound, authorized) = loadAuthorized()
                val prior = store().read(bound, authorized.plan, authorized.findings)
                Triple(bound, authorized, prior)
            }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { (bound, authorized, prior) ->
                    binding = bound
                    loaded = authorized
                    selectors.clear()
                    rows.removeAllViews()
                    authorized.findings.forEach { finding ->
                        rows.addView(TextView(this).apply {
                            text = finding.targetPath + " · " + finding.nativeType.name +
                                "\n" + finding.verdict.name + ": " + finding.reason
                            setPadding(0, 16, 0, 4)
                        })
                        val values = if (finding.verdict == TypedMappingVerdict.REVIEW_SAME_TYPE)
                            listOf(TypedReviewDecision.REENTER,
                                TypedReviewDecision.LEAVE_EMPTY,
                                TypedReviewDecision.REQUEST_SEPARATE_RULE_REVIEW)
                        else listOf(TypedReviewDecision.REENTER, TypedReviewDecision.LEAVE_EMPTY)
                        val spinner = Spinner(this)
                        spinner.adapter = ArrayAdapter(this,
                            android.R.layout.simple_spinner_dropdown_item,
                            values.map { it.name })
                        prior?.decisions?.get(finding.targetPath)?.let { selected ->
                            val i = values.indexOf(selected)
                            if (i >= 0) spinner.setSelection(i)
                        }
                        spinner.isEnabled = prior == null
                        selectors[finding.targetPath] = spinner
                        rows.addView(spinner)
                    }
                    status.text = if (prior != null)
                        "Encrypted operator review exists. Decisions are immutable."
                    else "Select an action for every target field. Rule review is NOT approval to copy any answer."
                    save.isEnabled = prior == null
                }.onFailure { deny(it) }
            }
        }
    }

    private fun confirm() {
        val original = binding ?: return deny()
        val prepared = loaded ?: return deny()
        if (!activeProjectMatches() ||
            selectors.keys != prepared.findings.map { it.targetPath }.toSet()) return deny()
        val selections = linkedMapOf<String, TypedReviewDecision>()
        prepared.findings.forEach { finding ->
            val value = selectors[finding.targetPath]?.selectedItem as? String
                ?: return deny()
            selections[finding.targetPath] = TypedReviewDecision.valueOf(value)
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm protected field-by-field review")
            .setMessage("These decisions will be saved encrypted and cannot be silently changed. REQUEST_SEPARATE_RULE_REVIEW is only a request for later authorization. No old answer will be copied. Continue?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                save.isEnabled = false
                progress.visibility = View.VISIBLE
                val request = ++generation
                executor.execute {
                    val result = runCatching {
                        val (current, fresh) = loadAuthorized()
                        require(current == original &&
                            fresh == prepared) { "Work or native mapping changed after review" }
                        store().save(current, fresh.plan, fresh.findings, selections)
                        require(store().read(current, fresh.plan, fresh.findings)
                            ?.decisions == selections) { "Encrypted review readback failed" }
                    }
                    runOnUiThread {
                        if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                        progress.visibility = View.GONE
                        result.onSuccess {
                            status.text = "Encrypted review recorded. No transfer or submission enabled."
                            selectors.values.forEach { it.isEnabled = false }
                            save.isEnabled = false
                        }.onFailure { deny(it) }
                    }
                }
            }.show()
    }

    private fun reviewConflict() {
        if (recoveryButton.visibility != View.VISIBLE || !activeProjectMatches() ||
            !runCatching {
                QuarantinedWorkDraftVault(noBackupFilesDir)
                    .read(projectId, workId, actorHash())?.workType ==
                    RecordWorkType.AMENDMENT
            }.getOrDefault(false)) return deny()
        startActivity(QuarantinedConflictReviewActivity.intentFor(
            this, projectId, workId))
    }

    private fun deny(error: Throwable? = null) {
        val outcome = error?.let { ProtectedWorkRecovery.forFailure(it) }
        recoveryButton.visibility = if (outcome?.mayManuallyReviewServerRecords == true)
            View.VISIBLE else View.GONE
        recoveryButton.isEnabled = outcome?.mayManuallyReviewServerRecords == true
        binding = null
        loaded = null
        selectors.clear()
        rows.removeAllViews()
        save.isEnabled = false
        progress.visibility = View.GONE
        status.text = if (error == null)
            "Review unavailable. Recheck active project, server assignment and form versions."
        else ProtectedWorkRecovery.forFailure(error).message
    }

    override fun onStop() {
        generation++
        binding = null
        loaded = null
        selectors.clear()
        rows.removeAllViews()
        save.isEnabled = false
        recoveryButton.isEnabled = false
        recoveryButton.visibility = View.GONE
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        if (activeProjectMatches()) open() else deny()
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROJECT = "recordwork.typedreview.project"
        private const val EXTRA_WORK = "recordwork.typedreview.work"
        fun intentFor(context: Context, projectId: String, workId: String): Intent =
            Intent(context, QuarantinedTypedReviewActivity::class.java)
                .putExtra(EXTRA_PROJECT, projectId).putExtra(EXTRA_WORK, workId)
        private fun isUuid(value: String) =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
