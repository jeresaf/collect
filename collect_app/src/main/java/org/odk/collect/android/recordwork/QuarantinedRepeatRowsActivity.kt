package org.odk.collect.android.recordwork

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.odk.collect.android.R
import org.odk.collect.android.injection.DaggerUtils
import org.odk.collect.settings.SettingsProvider
import org.odk.collect.settings.keys.ProjectKeys
import org.odk.collect.strings.localization.LocalizedActivity
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Private repeat ROW ID reservation/archival only. This is deliberately
 * NOT FormFillingActivity and cannot write repeated answers or attachments.
 * Every read and mutation online-reauthorizes READY server work and the
 * exact JavaRosa target-version repeat structure.
 */
class QuarantinedRepeatRowsActivity : LocalizedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var settings: SettingsProvider
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var progress: ProgressBar
    private var generation = 0
    private var openedBinding: QuarantinedWorkDraft? = null

    private val projectId get() = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
    private val workId get() = intent.getStringExtra(EXTRA_WORK).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = DaggerUtils.getComponent(this).settingsProvider()
        title = getString(R.string.private_repeat_title)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        status = TextView(this).apply {
            setText(R.string.private_repeat_loading)
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(status)
        container.addView(progress)
        container.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        container.addView(TextView(this).apply {
            setText(R.string.private_repeat_non_submittable)
        })
        setContentView(container)
        if (projectId.isBlank() || !validUuid(workId) || !activeProjectMatches()) {
            deny()
            return
        }
        refresh()
    }

    private fun activeProjectMatches(): Boolean =
        DaggerUtils.getComponent(this)
            .currentProjectProvider().getCurrentProject().value?.uuid == projectId

    private fun actorHash(): String {
        val values = settings.getUnprotectedSettings(projectId)
        val url = RecordWorkApiPaths.fromProjectUrl(
            values.getString(ProjectKeys.KEY_SERVER_URL).orEmpty()
        )
        val username = values.getString(ProjectKeys.KEY_USERNAME).orEmpty()
        require(username.isNotBlank()) { "Server account not configured" }
        return QuarantinedWorkDraftVault.sha256(
            projectId + "\n" + url.records() + "\n" + username
        )
    }

    private data class Authorized(
        val binding: QuarantinedWorkDraft,
        val schema: PrivateRepeatSchema
    )

    private fun authorized(): Authorized {
        require(activeProjectMatches()) { "Active project changed" }
        val bound = requireNotNull(
            QuarantinedWorkDraftVault(noBackupFilesDir)
                .read(projectId, workId, actorHash())
        ) { "Private work binding missing" }
        ProtectedSubmissionAttemptGuard.requireEditable(noBackupFilesDir, bound)
        val prepared = RecordWorkPreparationClient(settings)
            .authorizePrivateTextForm(projectId, bound)
        val mixed = intent.getBooleanExtra(QuarantinedCombinedDraftActivity.EXTRA_MIXED, false)
        if (mixed) {
            require(bound.workType == RecordWorkType.FOLLOW_UP && bound.validationTransfers == 0)
            val approval = requireNotNull(ProtectedMappingApprovalStore(
                noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()).read(bound, prepared.plan))
            require(approval.decisions.values.all { it == MappingDecision.REENTER })
        }
        val schema = JavaRosaPrivateRepeatStructureProbe(
            cacheDir, bound, prepared.plan, prepared.targetXFormXml, mixed
        ).schema
        return Authorized(bound, schema)
    }

    private fun store() = QuarantinedRepeatRowVault(
        noBackupFilesDir, AndroidKeyStorePrivateDraftCipher()
    )

    private data class Display(
        val authorized: Authorized,
        val inventory: ProtectedRepeatInventory
    )

    private fun refresh() {
        rows.removeAllViews()
        progress.visibility = View.VISIBLE
        status.setText(R.string.private_repeat_loading)
        val token = ++generation
        executor.execute {
            val result = runCatching {
                val auth = authorized()
                Display(auth, store().read(auth.binding, auth.schema))
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { render(it) }.onFailure { deny() }
            }
        }
    }

    private fun render(display: Display) {
        openedBinding = display.authorized.binding
        rows.removeAllViews()
        status.setText(R.string.private_repeat_ready)
        display.authorized.schema.groups.forEach { group ->
            val active = display.inventory.active(group.path)
            rows.addView(TextView(this).apply {
                text = getString(
                    R.string.private_repeat_group_status,
                    group.path, active.size,
                    QuarantinedRepeatRowVault.MAX_ACTIVE_PER_GROUP
                )
                setPadding(0, 20, 0, 6)
            })
            rows.addView(TextView(this).apply {
                text = group.fields.joinToString(", ") {
                    it.path.substringAfterLast("/") + ": " + it.type.name.lowercase()
                }
            })
            rows.addView(Button(this).apply {
                setText(R.string.private_repeat_add)
                isAllCaps = false
                isEnabled = active.size < QuarantinedRepeatRowVault.MAX_ACTIVE_PER_GROUP
                setOnClickListener {
                    mutate(group.path, null)
                }
            })
            active.forEachIndexed { index, repeatRow ->
                rows.addView(TextView(this).apply {
                    text = getString(
                        R.string.private_repeat_row_label, index + 1,
                        repeatRow.rowId
                    )
                    setPadding(0, 16, 0, 4)
                })
                rows.addView(Button(this).apply {
                    setText(R.string.private_repeat_edit_answers)
                    isAllCaps = false
                    setOnClickListener {
                        startActivity(
                            QuarantinedRepeatAnswerActivity.intentFor(
                                this@QuarantinedRepeatRowsActivity,
                                projectId, workId, repeatRow.rowId,
                                intent.getBooleanExtra(QuarantinedCombinedDraftActivity.EXTRA_MIXED, false)
                            )
                        )
                    }
                })
                if (group.imagePaths.isNotEmpty()) {
                    rows.addView(Button(this).apply {
                        setText(R.string.private_repeat_image_open)
                        isAllCaps = false
                        setOnClickListener {
                            startActivity(
                                QuarantinedRepeatImageActivity.intentFor(
                                    this@QuarantinedRepeatRowsActivity,
                                    projectId, workId, repeatRow.rowId,
                                intent.getBooleanExtra(QuarantinedCombinedDraftActivity.EXTRA_MIXED, false)
                                )
                            )
                        }
                    })
                }
                rows.addView(Button(this).apply {
                    setText(R.string.private_repeat_archive)
                    isAllCaps = false
                    setOnClickListener {
                        AlertDialog.Builder(this@QuarantinedRepeatRowsActivity)
                            .setTitle(R.string.private_repeat_archive)
                            .setMessage(R.string.private_repeat_archive_warning)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                mutate(group.path, repeatRow.rowId)
                            }
                            .show()
                    }
                })
            }
            display.inventory.rows.count {
                it.groupPath == group.path && it.isArchived
            }.takeIf { it > 0 }?.let { archived ->
                rows.addView(TextView(this).apply {
                    text = getString(R.string.private_repeat_archived_count, archived)
                })
            }
        }
    }

    private fun mutate(groupPath: String, rowId: String?) {
        rows.removeAllViews()
        progress.visibility = View.VISIBLE
        status.setText(R.string.private_repeat_saving)
        val originalBinding = openedBinding
        val requestId = UUID.randomUUID().toString()
        val token = ++generation
        executor.execute {
            val result = runCatching {
                val auth = authorized()
                require(auth.binding == originalBinding) {
                    "Work identity or account changed while viewing repeat rows"
                }
                val rowsStore = store()
                if (rowId == null) {
                    rowsStore.addRow(
                        auth.binding, auth.schema, groupPath, requestId
                    )
                } else {
                    require(groupPath in auth.schema.groups.map { it.path }) {
                        "Repeat group no longer exists"
                    }
                    rowsStore.archiveRow(
                        auth.binding, auth.schema, rowId, requestId
                    )
                }
                // Readback must be decryptable under the same authorization.
                rowsStore.read(auth.binding, auth.schema)
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { refresh() }.onFailure { deny() }
            }
        }
    }

    private fun deny() {
        openedBinding = null
        rows.removeAllViews()
        progress.visibility = View.GONE
        status.setText(R.string.private_repeat_denied)
    }

    override fun onStop() {
        generation++
        deny()
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        if (projectId.isNotBlank() && validUuid(workId) && activeProjectMatches()) {
            refresh()
        } else deny()
    }

    override fun onDestroy() {
        generation++
        executor.shutdownNow()
        openedBinding = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "recordwork.repeat.project"
        const val EXTRA_WORK = "recordwork.repeat.work"

        fun intentFor(context: Context, projectId: String, workId: String,
            mixed: Boolean = false) =
            Intent(context, QuarantinedRepeatRowsActivity::class.java)
                .putExtra(EXTRA_PROJECT, projectId)
                .putExtra(EXTRA_WORK, workId)
                .putExtra(QuarantinedCombinedDraftActivity.EXTRA_MIXED, mixed)

        private fun validUuid(value: String) =
            runCatching { UUID.fromString(value).toString() == value }
                .getOrDefault(false)
    }
}
