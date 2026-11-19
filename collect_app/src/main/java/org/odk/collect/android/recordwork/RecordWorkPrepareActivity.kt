package org.odk.collect.android.recordwork

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.UUID
import java.util.concurrent.Executors

/**
 * M4B-2: server-owned work selection and read-only preparation. No form editor,
 * no instance import, no answer mapping, no attachment download and no upload.
 *
 * Source/project/work target are bound to the active ODK project and checked by
 * M3 each time. This screen cannot generate an editable ODK draft.
 */
class RecordWorkPrepareActivity : LocalizedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var provider: SettingsProvider
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var versions: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var amend: Button
    private lateinit var followup: Button
    private lateinit var create: Button
    private lateinit var visitKey: EditText
    private var generation = 0
    private var chosenType: RecordWorkType? = null
    private var chosenTarget: RecordTargetVersion? = null
    // Review choices live only in this activity; never used to create a draft.
    private var pathReview: HistoricalMappingReview? = null
    private var attemptSpec: String? = null
    private var attemptId: String? = null

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val sourceId get() = intent.getStringExtra(EXTRA_SOURCE).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.record_work_prepare)
        title = getString(R.string.work_title)
        provider = DaggerUtils.getComponent(this).settingsProvider()
        status = findViewById(R.id.work_status)
        preview = findViewById(R.id.work_preview)
        versions = findViewById(R.id.work_versions)
        progress = findViewById(R.id.work_progress)
        amend = findViewById(R.id.work_amend)
        followup = findViewById(R.id.work_followup)
        create = findViewById(R.id.work_create)
        visitKey = findViewById(R.id.work_visit_key)
        findViewById<TextView>(R.id.work_source).text =
            getString(R.string.work_source_label, sourceId)

        attemptId = savedInstanceState?.getString(SAVED_ID)
        attemptSpec = savedInstanceState?.getString(SAVED_SPEC)

        if (projectId.isBlank() || !canonicalUuid(sourceId) || session() == null) {
            disableActions()
            status.setText(R.string.work_failed_changed_project)
            return
        }

        val existing = intent.getStringExtra(EXTRA_WORK)
        if (existing != null) {
            disableActions()
            val target = intent.getStringExtra(EXTRA_TARGET)
            val type = try {
                RecordWorkType.valueOf(intent.getStringExtra(EXTRA_TYPE).orEmpty())
            } catch (_: IllegalArgumentException) {
                null
            }
            if (!canonicalUuid(existing) || !canonicalUuid(target) || type == null) {
                status.setText(R.string.work_failed_server)
                return
            }
            status.setText(R.string.work_pending)
            previewExisting(existing, target!!, type)
        } else {
            amend.setOnClickListener { loadVersions(RecordWorkType.AMENDMENT) }
            followup.setOnClickListener { loadVersions(RecordWorkType.FOLLOW_UP) }
            create.setOnClickListener { confirmCreate() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_SPEC, attemptSpec)
        outState.putString(SAVED_ID, attemptId)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        // A source/project/account switch must not deliver stale review data.
        generation++
        preview.text = ""
        versions.removeAllViews()
        pathReview = null
        progress.visibility = View.GONE
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun disableActions() {
        amend.visibility = View.GONE
        followup.visibility = View.GONE
        create.visibility = View.GONE
        visitKey.visibility = View.GONE
    }

    private fun loadVersions(type: RecordWorkType) {
        if (session() == null) {
            status.setText(R.string.work_failed_changed_project)
            return
        }
        chosenType = type
        chosenTarget = null
        // Preserve the previous attempt key across rotation/reselection. If
        // source, type, version or visitKey differs, confirmCreate() generates
        // a new key only after comparing the complete immutable request spec.
        create.visibility = View.GONE
        visitKey.visibility = View.GONE
        versions.removeAllViews()
        preview.text = ""
        status.setText(R.string.work_loading)
        runOnline({ RecordWorkPreparationClient(provider).targets(projectId, sourceId, type) }) {
            candidates ->
            versions.removeAllViews()
            if (candidates.isEmpty()) {
                status.setText(R.string.work_no_targets)
                return@runOnline
            }
            status.setText(R.string.work_pick_version)
            candidates.forEach { candidate ->
                versions.addView(Button(this).apply {
                    isAllCaps = false
                    text = "${candidate.formTitle}\nVersion ${candidate.version}" +
                        if (candidate.latest) " (latest)" else ""
                    setOnClickListener {
                        chosenTarget = candidate
                        status.text = getString(R.string.work_selected, candidate.version)
                        create.visibility = View.VISIBLE
                        visitKey.visibility =
                            if (type == RecordWorkType.FOLLOW_UP) View.VISIBLE else View.GONE
                    }
                })
            }
        }
    }

    private fun confirmCreate() {
        val type = chosenType ?: return
        val target = chosenTarget ?: return
        val visit = if (type == RecordWorkType.FOLLOW_UP) {
            visitKey.text.toString().trim()
        } else {
            null
        }
        if (type == RecordWorkType.FOLLOW_UP &&
            (visit.isNullOrBlank() || !TOKEN.matches(visit))) {
            status.setText(R.string.work_require_visit_key)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.work_confirm_title)
            .setMessage(R.string.work_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val spec = "${sourceId}:${type.name}:${target.targetFormVersionId}:${visit.orEmpty()}"
                if (attemptSpec != spec || attemptId == null) {
                    attemptSpec = spec
                    attemptId = "m4b2-${UUID.randomUUID()}"
                }
                val work = try {
                    NewRecordWork(
                        sourceSubmissionId = sourceId,
                        targetFormVersionId = target.targetFormVersionId,
                        workType = type,
                        clientRequestId = requireNotNull(attemptId),
                        visitKey = visit
                    )
                } catch (_: IllegalArgumentException) {
                    status.setText(R.string.work_failed_server)
                    return@setPositiveButton
                }
                runOnline({
                    val client = nativeValidatedClient()
                    val created = client.create(projectId, work)
                    client.prepare(
                        projectId, created.id, sourceId, target.targetFormVersionId, type,
                        assessCrossVersion = true
                    )
                }) { prepared ->
                    disableActions()
                    versions.removeAllViews()
                    showPreview(prepared)
                }
            }
            .show()
    }

    private fun previewExisting(id: String, targetId: String, type: RecordWorkType) {
        runOnline({
            nativeValidatedClient()
                .prepare(projectId, id, sourceId, targetId, type, assessCrossVersion = true)
        }) { prepared -> showPreview(prepared) }
    }

    private fun nativeValidatedClient() = RecordWorkPreparationClient(
        provider,
        nativeProbe = { plan, targetXForm, originalXForm ->
            JavaRosaStagingProbe(cacheDir)
                .inspect(plan, targetXForm, originalXForm)
        }
    )

    private fun showPreview(prepared: PreparedRecordPreview) {
        status.setText(R.string.work_created)
        val base = getString(
            R.string.work_preview_summary,
            prepared.workId, prepared.sourceSubmissionId, prepared.targetFormId,
            prepared.targetFormVersion, prepared.type.name
        )
        val assessment = prepared.assessment
        val prior = if (assessment == null) {
            getString(R.string.mapping_assessment_unavailable)
        } else {
            val findings = assessment.findings.take(20).joinToString("\n") { f ->
                f.path + ": " + f.issues.joinToString(", ") { it.name }
            }
            getString(
                R.string.mapping_assessment_summary,
                assessment.sourceFieldCount, assessment.targetFieldCount,
                assessment.unchangedPathCount, assessment.findings.size
            ) + "\n" + assessment.globalIssues.joinToString(", ") { it.name } +
                if (findings.isNotBlank()) "\n" + findings else ""
        }
        val plan = prepared.mappingPlan
        val planSummary = if (plan == null) {
            getString(R.string.mapping_assessment_unavailable)
        } else {
            val candidateCount = plan.candidates.count { it.safeScalarProposal }
            getString(
                R.string.mapping_review_summary,
                plan.sourceVersion ?: "unknown",
                plan.targetVersion,
                plan.candidates.size,
                candidateCount
            ) + "\n" + plan.globalRisks.joinToString(", ") { it.name } +
                if (plan.removedSourcePaths.isNotEmpty()) {
                    "\n" + getString(R.string.mapping_review_removed) +
                        "\n" + plan.removedSourcePaths.take(12).joinToString("\n")
                } else ""
        }
        val native = prepared.nativeStaging
        val nativeStatus = if (native != null && native.targetJavaRosaInitialized) {
            getString(
                R.string.mapping_native_ready,
                native.targetFormVersionId,
                if (native.historicalJavaRosaInitialized) "YES" else "NO"
            )
        } else {
            getString(R.string.mapping_native_unavailable)
        }
        preview.text = base + "\n\n" + prior + "\n\n" + planSummary +
            "\n\n" + nativeStatus +
            "\n\n" + getString(R.string.mapping_assessment_blocked)

        versions.removeAllViews()
        pathReview = null
        if (plan == null || plan.candidates.isEmpty()) return
        val review = HistoricalMappingReview(plan)
        pathReview = review
        val prompt = TextView(this).apply {
            setText(R.string.mapping_review_path_prompt)
            setPadding(0, 16, 0, 8)
        }
        versions.addView(prompt)
        // Present a bounded preview rather than rendering thousands of buttons
        // on the Android main thread. Unseen fields cannot be marked reviewed.
        plan.candidates.take(MAX_VISIBLE_REVIEW_FIELDS).forEach { candidate ->
            val button = Button(this)
            button.isAllCaps = false
            val riskText = candidate.risks.joinToString(", ") { it.name }
                .ifBlank { getString(R.string.mapping_review_candidate) }
            button.text = candidate.targetPath + "\n" + riskText
            button.setOnClickListener {
                val options = mutableListOf(
                    getString(R.string.mapping_review_reenter),
                    getString(R.string.mapping_review_leave_empty)
                )
                if (candidate.safeScalarProposal) {
                    options += getString(R.string.mapping_review_request_copy)
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(candidate.targetPath)
                    .setItems(options.toTypedArray()) { _, which ->
                        val action = when (which) {
                            0 -> MappingDecision.REENTER
                            1 -> MappingDecision.LEAVE_EMPTY
                            else -> MappingDecision.REQUEST_REVIEWED_TRANSFER
                        }
                        try {
                            review.decide(candidate.targetPath, action)
                            button.text = candidate.targetPath + "\n" + action.name
                            val snapshot = review.snapshot()
                            // This status never claims the record is editable or
                            // that mapping approval passed JavaRosa validation.
                            status.text = getString(
                                R.string.mapping_review_progress,
                                snapshot.decisions.size, plan.candidates.size
                            )
                        } catch (_: IllegalArgumentException) {
                            status.setText(R.string.mapping_review_unavailable)
                        }
                    }.show()
            }
            versions.addView(button)
        }
        if (plan.candidates.size > MAX_VISIBLE_REVIEW_FIELDS) {
            versions.addView(TextView(this).apply {
                setText(R.string.mapping_review_unavailable)
            })
        }
    }

    private data class Session(val project: String, val server: String, val username: String)

    private fun session(): Session? {
        val active = DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value ?: return null
        if (active.uuid != projectId) return null
        val settings = provider.getUnprotectedSettings(projectId)
        return Session(
            projectId,
            settings.getString(ProjectKeys.KEY_SERVER_URL).orEmpty(),
            settings.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        )
    }

    private fun <T> runOnline(fetch: () -> T, show: (T) -> Unit) {
        val expected = session()
        if (expected == null) {
            status.setText(R.string.work_failed_changed_project)
            return
        }
        val requestId = ++generation
        progress.visibility = View.VISIBLE
        amend.isEnabled = false
        followup.isEnabled = false
        create.isEnabled = false
        executor.execute {
            val result = try {
                Result.success(fetch())
            } catch (ex: PreparationException) {
                Result.failure(ex)
            } catch (_: Exception) {
                Result.failure(PreparationException(PreparationError.NETWORK))
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || requestId != generation) {
                    return@runOnUiThread
                }
                if (expected != session()) {
                    preview.text = ""
                    versions.removeAllViews()
                    status.setText(R.string.work_failed_changed_project)
                    progress.visibility = View.GONE
                    return@runOnUiThread
                }
                progress.visibility = View.GONE
                amend.isEnabled = true
                followup.isEnabled = true
                create.isEnabled = true
                result.fold(
                    onSuccess = show,
                    onFailure = { error ->
                        status.setText(
                            when ((error as? PreparationException)?.reason) {
                                PreparationError.LOGIN_REQUIRED,
                                PreparationError.MISSING_CREDENTIALS -> R.string.work_failed_auth
                                PreparationError.ACCESS_DENIED,
                                PreparationError.NOT_FOUND -> R.string.work_failed_access
                                PreparationError.CONFLICT -> R.string.work_failed_conflict
                                PreparationError.NETWORK -> R.string.work_failed_network
                                else -> R.string.work_failed_server
                            }
                        )
                    }
                )
            }
        }
    }

    companion object {
        private const val MAX_VISIBLE_REVIEW_FIELDS = 30
        const val EXTRA_PROJECT = "recordwork.project"
        const val EXTRA_SOURCE = "recordwork.source"
        const val EXTRA_WORK = "recordwork.work"
        const val EXTRA_TARGET = "recordwork.target"
        const val EXTRA_TYPE = "recordwork.type"
        private const val SAVED_ID = "recordwork.request.id"
        private const val SAVED_SPEC = "recordwork.request.spec"
        private val TOKEN = Regex("[A-Za-z0-9._:-]{1,128}")

        private val UUID_TEXT = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        private fun canonicalUuid(id: String?): Boolean =
            id != null && UUID_TEXT.matches(id)
    }
}
